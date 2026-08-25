package software.coley.recaf.services.decompile.batch.pipeline;

import jakarta.annotation.Nonnull;

/**
 * One transformation step of a {@link BatchPipeline}.
 *
 * @param <I>
 * 		Input item type.
 * @param <O>
 * 		Produced item type.
 *
 * @author Matt Coley
 */
@FunctionalInterface
public interface PipelineStage<I, O> {
	/**
	 * @param input
	 * 		Item to process.
	 *
	 * @return Produced item.
	 *
	 * @throws Exception
	 * 		When this item cannot be processed. The pipeline records the error against the item and keeps
	 * 		running, so one bad item never ends the run.
	 */
	@Nonnull
	O apply(@Nonnull I input) throws Exception;
}
