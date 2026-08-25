package software.coley.recaf.util.threading;

import jakarta.annotation.Nonnull;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wrapper for {@link ExecutorService} with easier inline configuration.
 *
 * @author Matt Coley
 */
public class ThreadPoolFactory {
	private static final int MAX = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);

	/**
	 * @param name
	 * 		Thread pool name.
	 *
	 * @return {@link Executors#newFixedThreadPool(int)}.
	 */
	public static ExecutorService newFixedThreadPool(String name) {
		return newFixedThreadPool(name, true);
	}

	/**
	 * @param name
	 * 		Thread pool name.
	 * @param daemon
	 * 		Flag to set created threads as daemon threads.
	 *
	 * @return {@link Executors#newFixedThreadPool(int)}.
	 */
	public static ExecutorService newFixedThreadPool(String name, boolean daemon) {
		return newFixedThreadPool(name, MAX, daemon);
	}

	/**
	 * The requested size is used as-is. Callers that ask for an explicit size have already decided how much
	 * concurrency their workload needs, and clamping it to the CPU-derived default silently starved pools
	 * whose threads spend their time blocked rather than burning CPU.
	 *
	 * @param name
	 * 		Thread pool name.
	 * @param size
	 * 		Thread pool size.
	 * @param daemon
	 * 		Flag to set created threads as daemon threads.
	 *
	 * @return {@link Executors#newFixedThreadPool(int)}.
	 */
	public static ExecutorService newFixedThreadPool(String name, int size, boolean daemon) {
		return new ExecutorServiceDelegate(Executors.newFixedThreadPool(Math.max(1, size), new FactoryImpl(name, daemon)));
	}

	/**
	 * Fixed pool ordering queued work by priority instead of submission order.
	 * <p>
	 * Work submitted through {@link #prioritized(int, Runnable)} runs before work of a lower priority value.
	 * Anything else is treated as priority {@code 0}. Ties keep submission order.
	 *
	 * @param name
	 * 		Thread pool name.
	 * @param size
	 * 		Thread pool size.
	 * @param daemon
	 * 		Flag to set created threads as daemon threads.
	 *
	 * @return Fixed pool draining its queue in priority order.
	 */
	public static ExecutorService newPriorityPool(String name, int size, boolean daemon) {
		return new PriorityPool(Math.max(1, size), new FactoryImpl(name, daemon));
	}

	/**
	 * @param priority
	 * 		Priority of the task, lower values run first.
	 * @param task
	 * 		Task to run.
	 *
	 * @return Task carrying its priority, for use with {@link #newPriorityPool(String, int, boolean)}.
	 */
	@Nonnull
	public static Runnable prioritized(int priority, @Nonnull Runnable task) {
		return new PrioritizedRunnable(task, priority);
	}

	/**
	 * @param name
	 * 		Thread pool name.
	 *
	 * @return {@link Executors#newCachedThreadPool()}.
	 */
	public static ExecutorService newCachedThreadPool(String name) {
		return newCachedThreadPool(name, true);
	}

	/**
	 * @param name
	 * 		Thread pool name.
	 * @param daemon
	 * 		Flag to set created threads as daemon threads.
	 *
	 * @return {@link Executors#newCachedThreadPool()}.
	 */
	public static ExecutorService newCachedThreadPool(String name, boolean daemon) {
		return new ExecutorServiceDelegate(Executors.newCachedThreadPool(new FactoryImpl(name, daemon)));
	}

	/**
	 * @param name
	 * 		Thread pool name.
	 *
	 * @return {@link Executors#newSingleThreadExecutor()}.
	 */
	public static ExecutorService newSingleThreadExecutor(String name) {
		return newSingleThreadExecutor(name, true);
	}

	/**
	 * @param name
	 * 		Thread pool name.
	 * @param daemon
	 * 		Flag to set created threads as daemon threads.
	 *
	 * @return {@link Executors#newSingleThreadExecutor()}.
	 */
	public static ExecutorService newSingleThreadExecutor(String name, boolean daemon) {
		return new ExecutorServiceDelegate(Executors.newSingleThreadExecutor(new FactoryImpl(name, daemon)));
	}

	/**
	 * @param name
	 * 		Thread pool name.
	 *
	 * @return {@link Executors#newScheduledThreadPool(int)}.
	 */
	public static ScheduledExecutorService newScheduledThreadPool(String name) {
		return newScheduledThreadPool(name, true);
	}

	/**
	 * @param name
	 * 		Thread pool name.
	 * @param daemon
	 * 		Flag to set created threads as daemon threads.
	 *
	 * @return {@link Executors#newScheduledThreadPool(int)}.
	 */
	public static ScheduledExecutorService newScheduledThreadPool(String name, boolean daemon) {
		return newScheduledThreadPool(name, MAX, daemon);
	}

	/**
	 * @param name
	 * 		Thread pool name.
	 * @param size
	 * 		Thread pool size.
	 * @param daemon
	 * 		Flag to set created threads as daemon threads.
	 *
	 * @return {@link Executors#newScheduledThreadPool(int)}.
	 */
	public static ScheduledExecutorService newScheduledThreadPool(String name, int size, boolean daemon) {
		return new ScheduledExecutorServiceDelegate(Executors.newScheduledThreadPool(size, new FactoryImpl(name, daemon)));
	}

	/**
	 * Fixed pool whose queue is drained by priority.
	 * <p>
	 * Everything handed to the pool is wrapped in a {@link PrioritizedRunnable}, which is what makes the
	 * queue elements comparable. {@link ThreadPoolExecutor} routes both {@code execute} and {@code submit}
	 * through {@link #execute(Runnable)}, so wrapping there covers every entry point, and {@code submit}
	 * keeps the caller's priority because {@link #newTaskFor} carries it onto the future.
	 */
	private static final class PriorityPool extends ThreadPoolExecutor {
		private PriorityPool(int threads, @Nonnull ThreadFactory factory) {
			super(threads, threads, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(64), factory);
		}

		@Override
		public void execute(@Nonnull Runnable command) {
			super.execute(new PrioritizedRunnable(ThreadUtil.wrap(command), priorityOf(command)));
		}

		@Nonnull
		@Override
		protected <T> RunnableFuture<T> newTaskFor(@Nonnull Runnable runnable, T value) {
			return new PriorityFutureTask<>(runnable, value, priorityOf(runnable));
		}

		@Nonnull
		@Override
		protected <T> RunnableFuture<T> newTaskFor(@Nonnull Callable<T> callable) {
			return new PriorityFutureTask<>(callable, 0);
		}

		private static int priorityOf(@Nonnull Runnable runnable) {
			if (runnable instanceof PrioritizedRunnable prioritized)
				return prioritized.priority;
			if (runnable instanceof PriorityFutureTask<?> task)
				return task.priority;
			return 0;
		}
	}

	private static final class PriorityFutureTask<T> extends FutureTask<T> {
		private final int priority;

		private PriorityFutureTask(@Nonnull Runnable runnable, T value, int priority) {
			super(runnable, value);
			this.priority = priority;
		}

		private PriorityFutureTask(@Nonnull Callable<T> callable, int priority) {
			super(callable);
			this.priority = priority;
		}
	}

	private static final class PrioritizedRunnable implements Runnable, Comparable<PrioritizedRunnable> {
		private static final AtomicLong sequencer = new AtomicLong();
		private final Runnable delegate;
		private final int priority;
		private final long sequence = sequencer.getAndIncrement();

		private PrioritizedRunnable(@Nonnull Runnable delegate, int priority) {
			this.delegate = delegate;
			this.priority = priority;
		}

		@Override
		public void run() {
			delegate.run();
		}

		@Override
		public int compareTo(@Nonnull PrioritizedRunnable other) {
			int cmp = Integer.compare(priority, other.priority);
			return cmp != 0 ? cmp : Long.compare(sequence, other.sequence);
		}
	}

	private static class FactoryImpl implements ThreadFactory {
		private static int fidCounter;
		private final String name;
		private final boolean daemon;
		private final int fid = fidCounter++;
		private int tid = 0;

		public FactoryImpl(String name, boolean daemon) {
			this.name = name;
			this.daemon = daemon;
		}

		@Override
		public Thread newThread(@Nonnull Runnable r) {
			Thread thread = new Thread(r);
			thread.setDaemon(daemon);
			thread.setName("Recaf-" + name + " [" + fid + ":" + tid++ + "]");
			return thread;
		}
	}
}
