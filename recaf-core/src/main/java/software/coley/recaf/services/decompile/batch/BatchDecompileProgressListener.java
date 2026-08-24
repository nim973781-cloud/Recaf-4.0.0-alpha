package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;

/**
 * Receives {@link BatchDecompileProgress} updates from a {@link BatchDecompileEngine}.
 * <p>
 * Events are delivered on engine threads and are throttled by the engine, see
 * {@link ThrottledProgressListener}. Implementations must not block for long.
 *
 * @author Matt Coley
 */
public interface BatchDecompileProgressListener {
	/** Listener that discards all events. */
	BatchDecompileProgressListener NOOP = event -> {};

	/**
	 * @param event
	 * 		Latest progress snapshot.
	 */
	void onProgress(@Nonnull BatchDecompileProgress event);
}
