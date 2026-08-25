package software.coley.recaf.services.decompile.batch.session;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Outcome of one class within a {@link BatchDecompileSession#decompile(java.util.List) session chunk}.
 * Exactly one of the two components is present.
 *
 * @param text
 * 		Decompiled text, or {@code null} when the class produced no output.
 * @param failure
 * 		Reason no output was produced, or {@code null} on success.
 *
 * @author Matt Coley
 */
public record SessionClassResult(@Nullable String text, @Nullable Throwable failure) {
	/**
	 * @param text
	 * 		Decompiled text.
	 *
	 * @return Successful result carrying the text.
	 */
	@Nonnull
	public static SessionClassResult ok(@Nonnull String text) {
		return new SessionClassResult(text, null);
	}

	/**
	 * @param failure
	 * 		Reason no output was produced.
	 *
	 * @return Failed result carrying the reason.
	 */
	@Nonnull
	public static SessionClassResult failed(@Nonnull Throwable failure) {
		return new SessionClassResult(null, failure);
	}

	/**
	 * @return {@code true} when the class produced decompiled text.
	 */
	public boolean isSuccess() {
		return text != null;
	}
}
