package software.coley.recaf.services.mapping;

import jakarta.annotation.Nonnull;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

/**
 * Options for {@link MappingApplier#applyToResourceRecursive(Mappings, WorkspaceResource, RecursiveMappingOptions)}.
 *
 * @param includeVersionedBundles
 *        {@code true} to include {@link WorkspaceResource#getVersionedJvmClassBundles() versioned bundles} of visited resources.
 * @param includeEmbeddedResources
 *        {@code true} to recurse into {@link WorkspaceResource#getEmbeddedResources() embedded resources}.
 * @param deferDecompileCacheInvalidation
 *        {@code true} to clear cached decompilations only once after all bundles have been mapped,
 * 		instead of once per bundle.
 *
 * @author Matt Coley
 */
public record RecursiveMappingOptions(boolean includeVersionedBundles,
                                      boolean includeEmbeddedResources,
                                      boolean deferDecompileCacheInvalidation) {
	private static final RecursiveMappingOptions DEFAULTS = new RecursiveMappingOptions(true, true, true);

	/**
	 * @return Options covering versioned bundles and embedded resources, with deferred cache invalidation.
	 */
	@Nonnull
	public static RecursiveMappingOptions defaults() {
		return DEFAULTS;
	}

	/**
	 * @param includeVersionedBundles
	 * 		New value.
	 *
	 * @return Copy of these options with the given value.
	 */
	@Nonnull
	public RecursiveMappingOptions withVersionedBundles(boolean includeVersionedBundles) {
		return new RecursiveMappingOptions(includeVersionedBundles, includeEmbeddedResources, deferDecompileCacheInvalidation);
	}

	/**
	 * @param includeEmbeddedResources
	 * 		New value.
	 *
	 * @return Copy of these options with the given value.
	 */
	@Nonnull
	public RecursiveMappingOptions withEmbeddedResources(boolean includeEmbeddedResources) {
		return new RecursiveMappingOptions(includeVersionedBundles, includeEmbeddedResources, deferDecompileCacheInvalidation);
	}

	/**
	 * @param deferDecompileCacheInvalidation
	 * 		New value.
	 *
	 * @return Copy of these options with the given value.
	 */
	@Nonnull
	public RecursiveMappingOptions withDeferredDecompileCacheInvalidation(boolean deferDecompileCacheInvalidation) {
		return new RecursiveMappingOptions(includeVersionedBundles, includeEmbeddedResources, deferDecompileCacheInvalidation);
	}
}
