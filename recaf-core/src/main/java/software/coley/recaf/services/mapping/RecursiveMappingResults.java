package software.coley.recaf.services.mapping;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Result wrapper for {@link MappingApplier#applyToResourceRecursive(Mappings, WorkspaceResource)}.
 * <br>
 * Unlike {@link MappingResults}, which models a single bundle-level operation, this models a whole
 * {@link WorkspaceResource} tree. Each targeted bundle is mapped and applied in turn, so that bundles processed later
 * observe the workspace state left behind by earlier ones. Because of that, the per-bundle {@link MappingResults} are
 * only computed when {@link #apply()} is called.
 *
 * @author Matt Coley
 * @see MappingApplier#applyToResourceRecursive(Mappings, WorkspaceResource)
 */
public class RecursiveMappingResults {
	private final List<MappingResults> results = new ArrayList<>();
	private final List<BundleTarget> targets;
	private final Function<BundleTarget, MappingResults> bundleApplier;
	private final Workspace workspace;
	private final Mappings mappings;
	private final boolean deferDecompileCacheInvalidation;
	private boolean applied;

	/**
	 * @param workspace
	 * 		The workspace the mappings are being applied to.
	 * @param mappings
	 * 		The enriched mappings implementation used in the operation.
	 * @param targets
	 * 		Bundles to map, in the order they will be processed.
	 * @param bundleApplier
	 * 		Function mapping a single bundle, yielding {@code null} for bundles with no classes to map.
	 * @param deferDecompileCacheInvalidation
	 *        {@code true} when the per-bundle results skip clearing cached decompilations,
	 * 		requiring us to do so once at the end.
	 */
	RecursiveMappingResults(@Nonnull Workspace workspace,
	                        @Nonnull Mappings mappings,
	                        @Nonnull List<BundleTarget> targets,
	                        @Nonnull Function<BundleTarget, MappingResults> bundleApplier,
	                        boolean deferDecompileCacheInvalidation) {
		this.workspace = workspace;
		this.mappings = mappings;
		this.targets = List.copyOf(targets);
		this.bundleApplier = bundleApplier;
		this.deferDecompileCacheInvalidation = deferDecompileCacheInvalidation;
	}

	/**
	 * Maps and applies each {@link #getTargets() targeted bundle} in order.
	 * <p>
	 * When cached decompilation invalidation is deferred, it is done once here after all bundles are handled
	 * rather than once per bundle.
	 *
	 * @throws IllegalStateException
	 * 		When called more than once.
	 */
	public void apply() {
		if (applied)
			throw new IllegalStateException("Recursive mapping results have already been applied");
		applied = true;

		for (BundleTarget target : targets) {
			MappingResults bundleResults = bundleApplier.apply(target);

			// Null when the bundle has no classes to map.
			if (bundleResults == null)
				continue;

			bundleResults.apply();
			results.add(bundleResults);
		}

		if (deferDecompileCacheInvalidation && !results.isEmpty())
			MappingResults.clearCachedDecompilations(workspace);
	}

	/**
	 * @return {@code true} once {@link #apply()} has been called.
	 */
	public boolean isApplied() {
		return applied;
	}

	/**
	 * @return The enriched mappings implementation used in the operation.
	 */
	@Nonnull
	public Mappings getMappings() {
		return mappings;
	}

	/**
	 * @return Bundles to map, in the order they are processed.
	 */
	@Nonnull
	public List<BundleTarget> getTargets() {
		return targets;
	}

	/**
	 * @return Per-bundle results, in application order. Empty until {@link #apply()} is called.
	 */
	@Nonnull
	public List<MappingResults> getResults() {
		return Collections.unmodifiableList(results);
	}

	/**
	 * @return Stream of per-bundle results, in application order.
	 */
	@Nonnull
	public Stream<MappingResults> streamResults() {
		return results.stream();
	}

	/**
	 * @param preMappedName
	 * 		Pre-mapping name.
	 *
	 * @return {@code true} when the class was affected by the mapping operation in any of the targeted bundles.
	 */
	public boolean wasMapped(@Nonnull String preMappedName) {
		for (MappingResults bundleResults : results)
			if (bundleResults.wasMapped(preMappedName))
				return true;
		return false;
	}

	/**
	 * @param preMappingName
	 * 		Pre-mapping name.
	 *
	 * @return Path node of the post-mapped class, or {@code null} if the name was not affected.
	 * When the same name occurs in multiple bundles <i>(such as with multi-release classes)</i>
	 * the last processed bundle's path is yielded. Use {@link #getResults()} for per-bundle granularity.
	 */
	@Nullable
	public ClassPathNode getPostMappingPath(@Nonnull String preMappingName) {
		for (int i = results.size() - 1; i >= 0; i--) {
			ClassPathNode path = results.get(i).getPostMappingPath(preMappingName);
			if (path != null)
				return path;
		}
		return null;
	}

	/**
	 * @param preMappingName
	 * 		Pre-mapping name.
	 *
	 * @return Post-mapped class info, or {@code null} if the name was not affected.
	 */
	@Nullable
	public ClassInfo getPostMappingClass(@Nonnull String preMappingName) {
		ClassPathNode path = getPostMappingPath(preMappingName);
		return path == null ? null : path.getValue();
	}

	/**
	 * @return Merged mapping of affected classes across all bundles, to their new names.
	 * If a class was affected, but the name not changed, the key and value for that entry will be the same.
	 */
	@Nonnull
	public Map<String, String> getMappedClasses() {
		Map<String, String> merged = new LinkedHashMap<>();
		for (MappingResults bundleResults : results)
			merged.putAll(bundleResults.getMappedClasses());
		return merged;
	}

	/**
	 * A single bundle to map, paired with the resource declaring it.
	 *
	 * @param resource
	 * 		Resource containing the bundle. May be an embedded resource rather than the root one.
	 * @param bundle
	 * 		Bundle of classes to map.
	 */
	public record BundleTarget(@Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle) {
	}
}
