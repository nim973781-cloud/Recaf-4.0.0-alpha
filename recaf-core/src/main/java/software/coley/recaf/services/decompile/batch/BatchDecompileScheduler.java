package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.services.decompile.batch.pipeline.TimeoutBudget;
import software.coley.recaf.util.threading.ThreadPoolFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Bounded work scheduling for a batch run.
 * <p>
 * The old UI runner submitted every class of a JAR at once and let completion callbacks race to write
 * output. That has no backpressure, so memory scales with the class count, and it gives no control over
 * output order. This scheduler instead keeps a bounded number of tasks in flight and hands results back
 * in submission order:
 * <ul>
 *     <li>{@link #runBudgeted} runs class work on its own pool, taking an in-flight slot
 *     <i>before</i> anything is submitted, and invokes the completion callback on the calling thread in
 *     plan order.</li>
 *     <li>{@link #runOrdered} does the same for work that is already asynchronous elsewhere.</li>
 *     <li>{@link #runIo} runs resource writes on a dedicated pool, again bounded, without ordering.</li>
 * </ul>
 * <h2>Timeouts</h2>
 * {@link #runBudgeted} hands each work item a {@link TimeoutBudget} that is started inside the task
 * body. A class that sits in the pool queue therefore spends none of its budget waiting, which is what
 * the old {@code orTimeout} on the submitted future got wrong: on a saturated pool it could report a
 * class as timed out before a decompiler had ever looked at it.
 * <h2>Shutdown</h2>
 * Nothing outlives {@link #close()}. Every task still outstanding is cancelled with interruption and
 * both pools are shut down with {@code shutdownNow}, so an abandoned run cannot leave a decompiler
 * chewing on a class in the background.
 *
 * @author Matt Coley
 */
public final class BatchDecompileScheduler implements AutoCloseable {
	/** How many tasks may be in flight per worker. */
	public static final int IN_FLIGHT_FACTOR = 2;

	private final Set<CompletableFuture<?>> outstanding = ConcurrentHashMap.newKeySet();
	private final int classInFlight;
	private final int ioInFlight;
	private final ExecutorService decompilePool;
	private final ExecutorService ioPool;
	private volatile boolean closed;

	/**
	 * @param decompileWorkers
	 * 		Number of concurrent class decompilations.
	 * @param ioWorkers
	 * 		Number of concurrent resource writes.
	 */
	public BatchDecompileScheduler(int decompileWorkers, int ioWorkers) {
		int workers = Math.max(1, decompileWorkers);
		this.classInFlight = Math.max(2, workers * IN_FLIGHT_FACTOR);
		this.ioInFlight = Math.max(2, ioWorkers * IN_FLIGHT_FACTOR);
		this.decompilePool = ThreadPoolFactory.newFixedThreadPool("batch-decompile", workers, true);
		this.ioPool = ThreadPoolFactory.newFixedThreadPool("batch-decompile-io", Math.max(1, ioWorkers), true);
	}

	/**
	 * @return Maximum number of class tasks in flight.
	 */
	public int classInFlight() {
		return classInFlight;
	}

	/**
	 * @return Maximum number of IO tasks in flight.
	 */
	public int ioInFlight() {
		return ioInFlight;
	}

	/**
	 * Runs each item on the decompile pool under its own timeout budget, keeping at most
	 * {@link #classInFlight()} outstanding, and invokes {@code completion} on the calling thread in
	 * submission order.
	 *
	 * @param items
	 * 		Work items in plan order.
	 * @param budgetMillis
	 * 		How long one item may run once it starts.
	 * @param work
	 * 		Work to run for one item.
	 * @param completion
	 * 		Receives each item with either its value or the error that ended it.
	 * @param <T>
	 * 		Work item type.
	 * @param <R>
	 * 		Work result type.
	 *
	 * @throws InterruptedException
	 * 		When the calling thread is interrupted while waiting on outstanding work.
	 */
	public <T, R> void runBudgeted(@Nonnull List<T> items, long budgetMillis,
	                               @Nonnull BudgetedWork<T, R> work,
	                               @Nonnull OrderedCompletion<T, R> completion) throws InterruptedException {
		runBudgeted(items, item -> budgetMillis, work, completion);
	}

	/**
	 * Variant of {@link #runBudgeted(List, long, BudgetedWork, OrderedCompletion)} giving each item its
	 * own budget, for work whose items cover different amounts of the run.
	 *
	 * @param items
	 * 		Work items in plan order.
	 * @param budgetMillis
	 * 		How long one item may run once it starts.
	 * @param work
	 * 		Work to run for one item.
	 * @param completion
	 * 		Receives each item with either its value or the error that ended it.
	 * @param <T>
	 * 		Work item type.
	 * @param <R>
	 * 		Work result type.
	 *
	 * @throws InterruptedException
	 * 		When the calling thread is interrupted while waiting on outstanding work.
	 */
	public <T, R> void runBudgeted(@Nonnull List<T> items, @Nonnull ToLongFunction<T> budgetMillis,
	                               @Nonnull BudgetedWork<T, R> work,
	                               @Nonnull OrderedCompletion<T, R> completion) throws InterruptedException {
		runOrdered(items, item -> submit(item, budgetMillis.applyAsLong(item), work), completion);
	}

	/**
	 * Submits each item in order, keeping at most {@link #classInFlight()} outstanding, and invokes
	 * {@code completion} on the calling thread in submission order.
	 * <p>
	 * The in-flight slot is taken before {@code submitter} runs, so the submitter itself is never the
	 * thing that overshoots the bound.
	 *
	 * @param items
	 * 		Work items in plan order.
	 * @param submitter
	 * 		Starts the asynchronous work for one item.
	 * @param completion
	 * 		Receives each item with either its value or the error that ended it.
	 * @param <T>
	 * 		Work item type.
	 * @param <R>
	 * 		Work result type.
	 *
	 * @throws InterruptedException
	 * 		When the calling thread is interrupted while waiting on outstanding work.
	 */
	public <T, R> void runOrdered(@Nonnull List<T> items,
	                              @Nonnull Function<T, CompletableFuture<R>> submitter,
	                              @Nonnull OrderedCompletion<T, R> completion) throws InterruptedException {
		Deque<Pending<T, R>> window = new ArrayDeque<>(classInFlight);
		try {
			for (T item : items) {
				// Free a slot before starting anything, so no work exists that the bound does not cover.
				while (window.size() >= classInFlight)
					drain(window.pollFirst(), completion);
				window.addLast(new Pending<>(item, submitter.apply(item)));
			}
			while (!window.isEmpty())
				drain(window.pollFirst(), completion);
		} finally {
			// Any work still outstanding after an abnormal exit must not keep running.
			for (Pending<T, R> pending : window)
				cancelHard(pending.future);
		}
	}

	/**
	 * Runs each item on the IO pool, keeping at most {@link #ioInFlight()} outstanding, and blocks
	 * until all of them finish.
	 *
	 * @param items
	 * 		Work items.
	 * @param work
	 * 		Action to run for one item.
	 * @param onError
	 * 		Receives any item whose action threw.
	 * @param <T>
	 * 		Work item type.
	 *
	 * @throws InterruptedException
	 * 		When the calling thread is interrupted while waiting on outstanding work.
	 */
	public <T> void runIo(@Nonnull List<T> items,
	                      @Nonnull IoAction<T> work,
	                      @Nonnull BiConsumer<T, Throwable> onError) throws InterruptedException {
		if (items.isEmpty()) return;

		Semaphore permits = new Semaphore(ioInFlight);
		int submitted = 0;
		for (T item : items) {
			permits.acquire();
			try {
				ioPool.execute(() -> {
					try {
						work.run(item);
					} catch (Throwable t) {
						onError.accept(item, t);
					} finally {
						permits.release();
					}
				});
				submitted++;
			} catch (RejectedExecutionException ex) {
				permits.release();
				onError.accept(item, ex);
			}
		}
		if (submitted > 0)
			permits.acquire(ioInFlight);
	}

	@Override
	public void close() {
		closed = true;
		for (CompletableFuture<?> future : outstanding)
			cancelHard(future);
		outstanding.clear();
		decompilePool.shutdownNow();
		ioPool.shutdownNow();
	}

	/**
	 * Cancels a task as hard as the platform allows: the future is completed exceptionally and any thread
	 * already running it is interrupted.
	 *
	 * @param future
	 * 		Task to cancel.
	 */
	private static void cancelHard(@Nonnull CompletableFuture<?> future) {
		future.cancel(true);
	}

	@Nonnull
	private <T, R> CompletableFuture<R> submit(@Nonnull T item, long budgetMillis,
	                                           @Nonnull BudgetedWork<T, R> work) {
		TimeoutBudget budget = TimeoutBudget.ofMillis(budgetMillis);
		CompletableFuture<R> future = new CompletableFuture<>();
		if (closed) {
			future.completeExceptionally(new RejectedExecutionException("Scheduler is closed"));
			return future;
		}
		outstanding.add(future);
		future.whenComplete((value, error) -> outstanding.remove(future));
		try {
			decompilePool.execute(() -> {
				// Nothing to do for work that was cancelled while it sat in the queue.
				if (future.isDone())
					return;
				// The clock only starts here, so queue time is not charged against the item.
				budget.start();
				try {
					future.complete(work.run(item, budget));
				} catch (Throwable t) {
					future.completeExceptionally(t);
				}
			});
		} catch (RejectedExecutionException ex) {
			future.completeExceptionally(ex);
		}
		return future;
	}

	private static <T, R> void drain(@Nullable Pending<T, R> pending,
	                                 @Nonnull OrderedCompletion<T, R> completion) throws InterruptedException {
		if (pending == null) return;
		R value = null;
		Throwable error = null;
		try {
			value = pending.future.get();
		} catch (InterruptedException ex) {
			cancelHard(pending.future);
			throw ex;
		} catch (ExecutionException | CompletionException ex) {
			error = ex.getCause() == null ? ex : ex.getCause();
		} catch (Throwable t) {
			error = t;
		}
		completion.accept(pending.item, value, error);
	}

	/**
	 * Work run on the decompile pool under a timeout budget.
	 *
	 * @param <T>
	 * 		Work item type.
	 * @param <R>
	 * 		Work result type.
	 */
	@FunctionalInterface
	public interface BudgetedWork<T, R> {
		/**
		 * @param item
		 * 		Work item.
		 * @param budget
		 * 		Budget already started for this item. Implementations pass
		 *        {@link TimeoutBudget#remainingMillis()} to whatever they block on.
		 *
		 * @return Produced value.
		 *
		 * @throws Exception
		 * 		When the work fails or runs out of budget.
		 */
		R run(@Nonnull T item, @Nonnull TimeoutBudget budget) throws Exception;
	}

	/**
	 * Receives the outcome of one ordered work item.
	 *
	 * @param <T>
	 * 		Work item type.
	 * @param <R>
	 * 		Work result type.
	 */
	@FunctionalInterface
	public interface OrderedCompletion<T, R> {
		/**
		 * @param item
		 * 		Work item.
		 * @param value
		 * 		Produced value, or {@code null} when the work failed.
		 * @param error
		 * 		Error that ended the work, or {@code null} when it succeeded.
		 */
		void accept(@Nonnull T item, @Nullable R value, @Nullable Throwable error);
	}

	/**
	 * Action run on the IO pool.
	 *
	 * @param <T>
	 * 		Work item type.
	 */
	@FunctionalInterface
	public interface IoAction<T> {
		/**
		 * @param item
		 * 		Work item.
		 *
		 * @throws Exception
		 * 		When the action fails.
		 */
		void run(@Nonnull T item) throws Exception;
	}

	private record Pending<T, R>(@Nonnull T item, @Nonnull CompletableFuture<R> future) {}
}
