package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;

import java.io.IOException;

/**
 * Destination for everything a batch run produces.
 * <p>
 * Implementations must be safe to call from multiple threads. An implementation that cannot write
 * concurrently, or whose output order is part of its contract, must report
 * {@link #requiresOrderedWrites()} as {@code true} so the engine serializes writes in plan order.
 *
 * @author Matt Coley
 * @see DirectoryDecompileSink
 * @see StreamingZipDecompileSink
 */
public interface BatchDecompileSink extends AutoCloseable {
	/**
	 * Called before any write belonging to the given JAR.
	 *
	 * @param plan
	 * 		Plan of the JAR about to be processed.
	 *
	 * @throws IOException
	 * 		When the output location cannot be prepared.
	 */
	default void beginJar(@Nonnull JarDecompilePlan plan) throws IOException {
		// no-op by default
	}

	/**
	 * Called after the last write belonging to the given JAR.
	 *
	 * @param plan
	 * 		Plan of the JAR that was processed.
	 *
	 * @throws IOException
	 * 		When the output location cannot be finalized.
	 */
	default void endJar(@Nonnull JarDecompilePlan plan) throws IOException {
		// no-op by default
	}

	/**
	 * @param task
	 * 		Class that was decompiled.
	 * @param text
	 * 		Decompiled source.
	 *
	 * @throws IOException
	 * 		When the output cannot be written.
	 */
	void writeClass(@Nonnull ClassExportTask task, @Nonnull String text) throws IOException;

	/**
	 * @param task
	 * 		Resource to copy.
	 * @param content
	 * 		Raw file bytes.
	 *
	 * @throws IOException
	 * 		When the output cannot be written.
	 */
	void writeResource(@Nonnull ResourceExportTask task, @Nonnull byte[] content) throws IOException;

	/**
	 * @param task
	 * 		Class that could not be decompiled.
	 * @param failureStub
	 * 		Stub contents from {@link BatchFailureStub}.
	 *
	 * @throws IOException
	 * 		When the output cannot be written.
	 */
	void writeFailure(@Nonnull ClassExportTask task, @Nonnull String failureStub) throws IOException;

	/**
	 * @return {@code true} when the engine must hand writes to this sink one at a time, in plan order.
	 */
	default boolean requiresOrderedWrites() {
		return true;
	}

	/**
	 * @return Counters describing what has been written so far.
	 */
	@Nonnull
	BatchSinkStats stats();

	@Override
	void close() throws IOException;
}
