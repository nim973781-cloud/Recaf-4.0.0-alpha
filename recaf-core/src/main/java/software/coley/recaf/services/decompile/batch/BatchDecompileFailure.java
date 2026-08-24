package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import software.coley.recaf.util.StringUtil;

/**
 * One failure recorded during a batch decompile run.
 *
 * @param jarName
 * 		File name of the input JAR the failure belongs to.
 * @param className
 * 		Internal name of the class the failure belongs to, or {@code null} for JAR-scoped failures.
 * @param phase
 * 		Stage the failure occurred in. See the {@code PHASE_} constants.
 * @param message
 * 		Human readable failure description.
 * @param trace
 * 		Stack trace of the root cause, if one was available.
 *
 * @author Matt Coley
 */
public record BatchDecompileFailure(
		@Nonnull String jarName,
		@Nullable String className,
		@Nonnull String phase,
		@Nonnull String message,
		@Nullable String trace
) {
	/** Phase covering workspace import of an input JAR. */
	public static final String PHASE_IMPORT = "import";
	/** Phase covering mapping application to an imported workspace. */
	public static final String PHASE_MAPPING = "mapping";
	/** Phase covering output preparation for an input JAR. */
	public static final String PHASE_PREPARE = "prepare";
	/** Phase covering resource copying. */
	public static final String PHASE_RESOURCE = "resource"; // NOSONAR - phase id, not a credential
	/** Phase covering class decompilation. */
	public static final String PHASE_DECOMPILE = "decompile";
	/** Phase covering class decompilation that exceeded the configured per-class timeout. */
	public static final String PHASE_TIMEOUT = "timeout";
	/** Phase covering writing output through the sink. */
	public static final String PHASE_WRITE = "write";

	/**
	 * @param jarName
	 * 		File name of the input JAR.
	 * @param className
	 * 		Internal name of the class, or {@code null}.
	 * @param phase
	 * 		Stage the failure occurred in.
	 * @param error
	 * 		Root cause.
	 *
	 * @return Failure describing the given error.
	 */
	@Nonnull
	public static BatchDecompileFailure of(@Nonnull String jarName, @Nullable String className,
	                                       @Nonnull String phase, @Nonnull Throwable error) {
		String message = error.getMessage();
		if (message == null || message.isBlank())
			message = error.getClass().getName();
		return new BatchDecompileFailure(jarName, className, phase, message, StringUtil.traceToString(error));
	}
}
