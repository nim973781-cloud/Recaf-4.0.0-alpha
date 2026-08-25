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
	 * Allow decompiler-specific batch adapters after equivalence checks pass.
	 * <p/>
	 * With Vineflower this routes through {@link VineflowerFastVerifiedAdapter}, which decompiles whole
	 * chunks of classes per {@code Fernflower} context and resolves supporting classes through the
	 * workspace type index. Other decompilers have no fast adapter and fall back to {@link #ACCURATE}.
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
