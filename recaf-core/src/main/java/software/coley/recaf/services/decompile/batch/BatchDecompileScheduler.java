package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.util.threading.ThreadPoolFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Bounded work scheduling for a batch run.
 * <p>
 * The old UI runner submitted every class of a JAR at once and let completion callbacks race to write
 * output. That has no backpressure, so memory scales with the class count, and it gives no control
 * over output order. This scheduler instead keeps a bounded number of tasks in flight and hands
 * results back in submission order:
 * <ul>
 *     <li>{@link #runOrdered} submits class work, allowing at most
 *     {@link #IN_FLIGHT_FACTOR} times the worker count outstanding, and invokes the completion
 *     callback on the calling thread in plan order.</li>
 *     <li>{@link #runIo} runs resource writes on a dedicated pool, again bounded, without ordering.</li>
 * </ul>
 *
 * @author Matt Coley
 */
public final class BatchDecompileScheduler implements AutoCloseable {
	/** How many tasks may be in flight per worker. */
	public static final int IN_FLIGHT_FACTOR = 2;

	private final int classInFlight;
	private final int ioInFlight;
	private final ExecutorService ioPool;

	/**
	 * @param decompileWorkers
	 * 		Number of concurrent class decompilations.
	 * @param ioWorkers
	 * 		Number of concurrent resource writes.
	 */
	public BatchDecompileScheduler(int decompileWorkers, int ioWorkers) {
		this.classInFlight = Math.max(2, decompileWorkers * IN_FLIGHT_FACTOR);
		this.ioInFlight = Math.max(2, ioWorkers * IN_FLIGHT_FACTOR);
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
	 * Submits each item in order, keeping at most {@link #classInFlight()} outstanding, and invokes
	 * {@code completion} on the calling thread in submission order.
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
				window.addLast(new Pending<>(item, submitter.apply(item)));
				while (window.size() >= classInFlight)
					drain(window.pollFirst(), completion);
			}
			while (!window.isEmpty())
				drain(window.pollFirst(), completion);
		} finally {
			// Any work still outstanding after an abnormal exit must not keep running.
			for (Pending<T, R> pending : window)
				pending.future.cancel(true);
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
		ioPool.shutdown();
	}

	private static <T, R> void drain(@Nullable Pending<T, R> pending,
	                                 @Nonnull OrderedCompletion<T, R> completion) throws InterruptedException {
		if (pending == null) return;
		R value = null;
		Throwable error = null;
		try {
			value = pending.future.get();
		} catch (InterruptedException ex) {
			pending.future.cancel(true);
			throw ex;
		} catch (ExecutionException | CompletionException ex) {
			error = ex.getCause() == null ? ex : ex.getCause();
		} catch (Throwable t) {
			error = t;
		}
		completion.accept(pending.item, value, error);
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
