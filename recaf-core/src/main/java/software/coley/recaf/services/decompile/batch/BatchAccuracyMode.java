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
	 */
	FAST_VERIFIED,

	/**
	 * Use the fastest available adapter and report that output is not acceptance-grade.
	 */
	FAST_UNSAFE
}
