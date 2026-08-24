package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;

/**
 * Thrown when a batch decompile run cannot be started or completed as a whole.
 * <p>
 * Failures scoped to a single JAR or class do not throw. They are recorded in the
 * {@link BatchDecompileReport#failures() report failure list} instead.
 *
 * @author Matt Coley
 */
public class BatchDecompileException extends Exception {
	/**
	 * @param message
	 * 		Failure description.
	 */
	public BatchDecompileException(@Nonnull String message) {
		super(message);
	}

	/**
	 * @param message
	 * 		Failure description.
	 * @param cause
	 * 		Root cause.
	 */
	public BatchDecompileException(@Nonnull String message, @Nonnull Throwable cause) {
		super(message, cause);
	}
}
