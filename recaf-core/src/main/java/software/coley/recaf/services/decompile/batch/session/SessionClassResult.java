package software.coley.recaf.services.decompile.batch.session;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Outcome of decompiling one class inside a {@link BatchDecompileSession}.
 * <p>
 * Exactly one of the two components is present. Reporting failures per class rather than per submitted
 * group is what lets a session decompile many classes together without one broken class discarding the
 * output of everything it was decompiled with.
 *
 * @param text
 * 		Decompiled source, or {@code null} when the class produced no output.
 * @param failure
 * 		Reason no output was produced, or {@code null} on success.
 *
 * @author Matt Coley
 */
public record SessionClassResult(@Nullable String text, @Nullable Throwable failure) {
	/**
	 * @param text
	 * 		Decompiled source.
	 *
	 * @return Successful result.
	 */
	@Nonnull
	public static SessionClassResult of(@Nonnull String text) {
		return new SessionClassResult(text, null);
	}

	/**
	 * @param failure
	 * 		Reason the class produced no output.
	 *
	 * @return Failed result.
	 */
	@Nonnull
	public static SessionClassResult failed(@Nonnull Throwable failure) {
		return new SessionClassResult(null, failure);
	}

	/**
	 * @return {@code true} when decompiled source is present.
	 */
	public boolean isOk() {
		return text != null;
	}
}
