package software.coley.recaf.services.decompile.batch.pipeline;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded hand-off between two {@link PipelineStage stages} of a {@link BatchPipeline}.
 * <p>
 * This is a blocking queue with two additions the pipeline needs and {@code ArrayBlockingQueue} does not
 * offer: an end-of-stream signal that lets {@link #take()} return {@code null} rather than block forever,
 * and a failure signal that hands the producer's error to the consumer instead of deadlocking it. Capacity
 * is what keeps the pipeline from turning into an unbounded fan-out: a producer that runs ahead of its
 * consumer blocks, so memory tracks the queue depth rather than the size of the run.
 *
 * @param <T>
 * 		Item type passed between stages.
 *
 * @author Matt Coley
 */
public final class BoundedStageQueue<T> {
	private final ReentrantLock lock = new ReentrantLock();
	private final Condition notFull = lock.newCondition();
	private final Condition notEmpty = lock.newCondition();
	private final Deque<T> items;
	private final int capacity;
	private Throwable failure;
	private boolean completed;
	private boolean aborted;

	/**
	 * @param capacity
	 * 		How many items may sit in the queue before producers block. Values below one are raised to one.
	 */
	public BoundedStageQueue(int capacity) {
		this.capacity = Math.max(1, capacity);
		this.items = new ArrayDeque<>(this.capacity);
	}

	/**
	 * @return Maximum number of buffered items.
	 */
	public int capacity() {
		return capacity;
	}

	/**
	 * Adds an item, blocking while the queue is full.
	 *
	 * @param item
	 * 		Item to hand to the consumer.
	 *
	 * @return {@code true} when the item was queued, {@code false} when the queue was already aborted or
	 * completed and the item should be discarded.
	 *
	 * @throws InterruptedException
	 * 		When the producer is interrupted while waiting for room.
	 */
	public boolean put(@Nonnull T item) throws InterruptedException {
		lock.lockInterruptibly();
		try {
			while (items.size() >= capacity && !aborted && !completed)
				notFull.await();
			if (aborted || completed)
				return false;
			items.addLast(item);
			notEmpty.signal();
			return true;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Takes the next item, blocking until one is available or the stream ends.
	 *
	 * @return Next item, or {@code null} once the producer completed and the queue drained.
	 *
	 * @throws InterruptedException
	 * 		When the consumer is interrupted while waiting.
	 * @throws PipelineException
	 * 		When the producer failed. The producer's error is the cause.
	 */
	@Nullable
	public T take() throws InterruptedException {
		lock.lockInterruptibly();
		try {
			while (items.isEmpty() && !completed && !aborted && failure == null)
				notEmpty.await();
			if (!items.isEmpty()) {
				T item = items.pollFirst();
				notFull.signal();
				return item;
			}
			if (failure != null)
				throw new PipelineException("Pipeline stage failed", failure);
			return null;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Signals that no more items will be produced. Items already queued are still delivered.
	 */
	public void complete() {
		lock.lock();
		try {
			completed = true;
			notEmpty.signalAll();
			notFull.signalAll();
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Signals that the producer failed. The next {@link #take()} that finds the queue drained rethrows it.
	 *
	 * @param error
	 * 		Error that ended the producer.
	 */
	public void fail(@Nonnull Throwable error) {
		lock.lock();
		try {
			if (failure == null)
				failure = error;
			completed = true;
			notEmpty.signalAll();
			notFull.signalAll();
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Discards everything queued and wakes both sides. Used when the consumer gives up.
	 *
	 * @return Items that were still buffered, so the caller can release whatever they hold.
	 */
	@Nonnull
	public List<T> abort() {
		lock.lock();
		try {
			aborted = true;
			List<T> abandoned = List.copyOf(items);
			items.clear();
			notEmpty.signalAll();
			notFull.signalAll();
			return abandoned;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Error thrown to a consumer whose producer failed.
	 */
	public static final class PipelineException extends RuntimeException {
		/**
		 * @param message
		 * 		Description of the failure.
		 * @param cause
		 * 		Error the producing stage ended with.
		 */
		public PipelineException(@Nonnull String message, @Nonnull Throwable cause) {
			super(message, cause);
		}
	}
}
