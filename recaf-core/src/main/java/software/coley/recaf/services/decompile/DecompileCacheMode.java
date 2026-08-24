package software.coley.recaf.services.decompile;

import software.coley.recaf.info.properties.builtin.CachedDecompileProperty;

/**
 * Controls how {@link DecompilerManager} interacts with {@link CachedDecompileProperty} for a single request.
 *
 * @author Matt Coley
 */
public enum DecompileCacheMode {
	/**
	 * Cached results are used when available, and new results are written back to the cache.
	 * <br>
	 * This is what {@link DecompilerManager} uses when no mode is given and
	 * {@link DecompilerManagerConfig#getCacheDecompilations()} is enabled.
	 */
	READ_WRITE,
	/**
	 * Cached results are used when available, but new results are not written back to the cache.
	 * <br>
	 * Useful for one-off decompilations such as bulk exports where polluting the cache is undesirable.
	 */
	READ_ONLY,
	/**
	 * The cache is bypassed entirely.
	 */
	NONE;

	/**
	 * @return {@code true} when existing cached results may be returned.
	 */
	public boolean isReadable() {
		return this != NONE;
	}

	/**
	 * @return {@code true} when new results should be written to the cache.
	 */
	public boolean isWritable() {
		return this == READ_WRITE;
	}
}
