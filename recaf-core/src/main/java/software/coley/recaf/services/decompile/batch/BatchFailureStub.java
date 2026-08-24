package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.util.StringUtil;

/**
 * Builds the deterministic Java comment written in place of a class that could not be decompiled.
 * <p>
 * The layout matches what the UI batch runner produced so existing output can be diffed against
 * engine output without noise.
 *
 * @author Matt Coley
 */
public final class BatchFailureStub {
	private BatchFailureStub() {}

	/**
	 * @param className
	 * 		Internal name of the class that failed.
	 * @param error
	 * 		Root cause, if one was reported.
	 * @param resultType
	 * 		Decompile result type, if a result was produced at all.
	 *
	 * @return Stub file contents.
	 */
	@Nonnull
	public static String build(@Nonnull String className,
	                           @Nullable Throwable error,
	                           @Nullable DecompileResult.ResultType resultType) {
		StringBuilder builder = new StringBuilder();
		builder.append("// Failed to decompile '").append(className).append('\'').append('\n');
		if (resultType != null)
			builder.append("// Result type: ").append(resultType).append('\n');
		if (error != null)
			builder.append("// ").append(StringUtil.traceToString(error).replace("\n", "\n// "));
		else
			builder.append("// No additional error details were reported.");
		return builder.toString();
	}
}
