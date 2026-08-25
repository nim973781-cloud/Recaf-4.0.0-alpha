package software.coley.recaf.services.decompile.batch;

/**
 * Controls which decompilation path a {@link BatchDecompileEngine} is allowed to take.
 *
 * @author Matt Coley
 */
public enum BatchAccuracyMode {
	/**
	 * Match the existing single-class decompile path.
	 * No fast adapter may be used unless it proves output equivalence for the configured decompiler.
	 */
	ACCURATE,

	/**
	 * Allow decompiler-specific batch sessions after equivalence checks pass.
	 * <p/>
	 * Session factories decompile whole chunks of classes per backend context and resolve supporting
	 * classes through the workspace type index. When no factory claims the decompiler the engine
	 * falls back to {@link #ACCURATE}.
	 */
	FAST_VERIFIED,

	/**
	 * Use the fastest available adapter and report that output is not acceptance-grade.
	 * <p/>
	 * Same chunked path as {@link #FAST_VERIFIED}, but the shared library source resolves classes lazily
	 * instead of mirroring the eager listing of the single-class path, so output may drift further.
	 */
	FAST_UNSAFE
}
