package software.coley.recaf.services.decompile.batch.pipeline;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.util.threading.ThreadPoolFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Runs a {@link PipelineStage} ahead of its consumer on a dedicated thread.
 * <p>
 * A batch run is a chain of stages with very different costs. Importing an archive is IO and single
 * threaded, decompiling it saturates every core, and writing the output is one sequential append. Run
 * back to back, the cores idle through every import and the importer idles through every decompile. The
 * pipeline overlaps them: this class drives one stage on its own thread, buffering up to
 * {@link #depth()} finished items, so by the time the consumer asks for the next archive it has usually
 * already been imported.
 * <p>
 * Items come back in input order and carry their own failure. A stage that throws for one input does not
 * end the pipeline, it just marks that item, which is what keeps a single unreadable archive from
 * aborting a hundred-archive run.
 *
 * @param <I>
 * 		Input item type.
 * @param <O>
 * 		Produced item type.
 *
 * @author Matt Coley
 */
public final class BatchPipeline<I, O> implements AutoCloseable {
	private final BoundedStageQueue<Item<I, O>> queue;
	private final ExecutorService producerPool;
	private final Consumer<O> discard;
	private final int depth;
	private volatile boolean closed;

	private BatchPipeline(@Nonnull String name, @Nonnull List<I> inputs,
	                      @Nonnull PipelineStage<I, O> stage, int depth, @Nullable Consumer<O> discard) {
		this.depth = Math.max(1, depth);
		this.discard = discard;
		this.queue = new BoundedStageQueue<>(this.depth);
		this.producerPool = ThreadPoolFactory.newSingleThreadExecutor(name, true);
		producerPool.execute(() -> produce(inputs, stage));
	}

	/**
	 * Starts running the stage over the inputs immediately.
	 *
	 * @param name
	 * 		Thread pool name of the producing stage.
	 * @param inputs
	 * 		Items to feed through the stage, in order.
	 * @param stage
	 * 		Stage to run.
	 * @param depth
	 * 		How many finished items may be buffered ahead of the consumer.
	 * @param <I>
	 * 		Input item type.
	 * @param <O>
	 * 		Produced item type.
	 *
	 * @return Running pipeline.
	 */
	@Nonnull
	public static <I, O> BatchPipeline<I, O> prefetch(@Nonnull String name, @Nonnull List<I> inputs,
	                                                  @Nonnull PipelineStage<I, O> stage, int depth) {
		return new BatchPipeline<>(name, inputs, stage, depth, null);
	}

	/**
	 * Starts running the stage over the inputs immediately, releasing anything the consumer never asks for.
	 * <p>
	 * A prefetching stage can hold resources, an imported workspace being the case this exists for. When
	 * the consumer stops early those buffered results would otherwise be dropped on the floor still open,
	 * so they are handed to {@code discard} instead.
	 *
	 * @param name
	 * 		Thread pool name of the producing stage.
	 * @param inputs
	 * 		Items to feed through the stage, in order.
	 * @param stage
	 * 		Stage to run.
	 * @param depth
	 * 		How many finished items may be buffered ahead of the consumer.
	 * @param discard
	 * 		Receives every produced item the consumer never took.
	 * @param <I>
	 * 		Input item type.
	 * @param <O>
	 * 		Produced item type.
	 *
	 * @return Running pipeline.
	 */
	@Nonnull
	public static <I, O> BatchPipeline<I, O> prefetch(@Nonnull String name, @Nonnull List<I> inputs,
	                                                  @Nonnull PipelineStage<I, O> stage, int depth,
	                                                  @Nonnull Consumer<O> discard) {
		return new BatchPipeline<>(name, inputs, stage, depth, discard);
	}

	/**
	 * @return How many finished items may be buffered ahead of the consumer.
	 */
	public int depth() {
		return depth;
	}

	/**
	 * @return Next finished item in input order, or {@code null} once every input has been handed out.
	 *
	 * @throws InterruptedException
	 * 		When the consumer is interrupted while waiting for the stage.
	 */
	@Nullable
	public Item<I, O> next() throws InterruptedException {
		return queue.take();
	}

	@Override
	public void close() {
		closed = true;
		for (Item<I, O> abandoned : queue.abort())
			release(abandoned.value());
		producerPool.shutdownNow();
	}

	private void produce(@Nonnull List<I> inputs, @Nonnull PipelineStage<I, O> stage) {
		try {
			for (I input : inputs) {
				if (closed || Thread.currentThread().isInterrupted())
					break;
				Item<I, O> item;
				try {
					item = new Item<>(input, stage.apply(input), null);
				} catch (Throwable t) {
					item = new Item<>(input, null, t);
				}
				if (!queue.put(item)) {
					release(item.value());
					break;
				}
			}
			queue.complete();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			queue.complete();
		} catch (Throwable t) {
			queue.fail(t);
		}
	}

	private void release(@Nullable O value) {
		if (discard != null && value != null)
			discard.accept(value);
	}

	/**
	 * One input together with what the stage made of it.
	 *
	 * @param input
	 * 		Item that was fed into the stage.
	 * @param value
	 * 		Produced item, or {@code null} when the stage threw.
	 * @param error
	 * 		Error the stage threw, or {@code null} on success.
	 * @param <I>
	 * 		Input item type.
	 * @param <O>
	 * 		Produced item type.
	 */
	public record Item<I, O>(@Nonnull I input, @Nullable O value, @Nullable Throwable error) {
		/**
		 * @return {@code true} when the stage produced a value.
		 */
		public boolean isOk() {
			return error == null && value != null;
		}
	}
}
