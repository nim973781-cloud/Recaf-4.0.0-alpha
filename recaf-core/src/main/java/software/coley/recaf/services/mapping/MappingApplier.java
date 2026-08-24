package software.coley.recaf.services.mapping;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.properties.builtin.HasMappedReferenceProperty;
import software.coley.recaf.info.properties.builtin.OriginalClassNameProperty;
import software.coley.recaf.info.properties.builtin.RemapOriginTaskProperty;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.util.threading.ThreadPoolFactory;
import software.coley.recaf.util.threading.ThreadUtil;
import software.coley.recaf.util.visitors.IllegalSignatureRemovingVisitor;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.bundle.VersionedJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

/**
 * Applies mappings to workspaces and workspace resources, wrapping the results in a {@link MappingResults}.
 * To update the workspace with the mapping results, use {@link MappingResults#apply()}.
 *
 * @author Matt Coley
 * @see MappingResults
 */
public class MappingApplier {
	private static final ExecutorService applierThreadPool = ThreadPoolFactory.newFixedThreadPool(MappingApplierService.SERVICE_ID);
	private final InheritanceGraph inheritanceGraph;
	private final AggregateMappingManager aggregateMappingManager;
	private final MappingListeners listeners;
	private final Workspace workspace;

	/**
	 * @param workspace
	 * 		Workspace to apply mappings in.
	 * @param inheritanceGraph
	 * 		Inheritance graph for the given workspace.
	 * @param listeners
	 * 		Application mapping listeners
	 * 		<i>(If the target workspace is the {@link WorkspaceManager#getCurrent() current one})</i>
	 * @param aggregateMappingManager
	 * 		Aggregate mappings for tracking applications in the current workspace
	 * 		<i>(If the target workspace is the {@link WorkspaceManager#getCurrent() current one})</i>
	 */
	public MappingApplier(@Nonnull Workspace workspace,
	                      @Nonnull InheritanceGraph inheritanceGraph,
	                      @Nullable MappingListeners listeners,
	                      @Nullable AggregateMappingManager aggregateMappingManager) {
		this.inheritanceGraph = inheritanceGraph;
		this.aggregateMappingManager = aggregateMappingManager;
		this.listeners = listeners;
		this.workspace = workspace;
	}

	/**
	 * Applies the mapping operation to the given classes.
	 *
	 * @param mappings
	 * 		The mappings to apply.
	 * @param resource
	 * 		Resource containing the classes.
	 * @param bundle
	 * 		Bundle containing the classes.
	 * @param classes
	 * 		Classes to apply mappings to.
	 *
	 * @return Result wrapper detailing affected classes from the mapping operation.
	 */
	@Nonnull
	public MappingResults applyToClasses(@Nonnull Mappings mappings,
	                                     @Nonnull WorkspaceResource resource,
	                                     @Nonnull JvmClassBundle bundle,
	                                     @Nonnull Collection<JvmClassInfo> classes) {
		return applyToClassesEnriched(enrich(mappings), resource, bundle, classes);
	}

	/**
	 * Applies the mapping operation to the given classes, skipping mapping enrichment.
	 *
	 * @param mappings
	 * 		The already {@link #enrich(Mappings) enriched} mappings to apply.
	 * @param resource
	 * 		Resource containing the classes.
	 * @param bundle
	 * 		Bundle containing the classes.
	 * @param classes
	 * 		Classes to apply mappings to.
	 *
	 * @return Result wrapper detailing affected classes from the mapping operation.
	 */
	@Nonnull
	private MappingResults applyToClassesEnriched(@Nonnull Mappings mappings,
	                                              @Nonnull WorkspaceResource resource,
	                                              @Nonnull JvmClassBundle bundle,
	                                              @Nonnull Collection<JvmClassInfo> classes) {
		MappingApplicationListener listener = listeners == null ? null : listeners.createBundledMappingApplicationListener();
		MappingResults results = new MappingResults(workspace, mappings, listener);
		if (aggregateMappingManager != null)
			results.withAggregateManager(aggregateMappingManager);

		// Apply mappings to the provided classes, collecting into the results model.
		ExecutorService service = ThreadUtil.phasingService(applierThreadPool);
		for (JvmClassInfo classInfo : classes)
			service.execute(() -> dumpIntoResults(results, workspace, resource, bundle, classInfo, mappings));
		ThreadUtil.blockUntilComplete(service);

		// Yield results
		return results;
	}

	/**
	 * Applies the mapping operation to the current workspace's primary resource.
	 *
	 * @param mappings
	 * 		The mappings to apply.
	 *
	 * @return Result wrapper detailing affected classes from the mapping operation.
	 */
	@Nonnull
	public MappingResults applyToPrimaryResource(@Nonnull Mappings mappings) {
		mappings = enrich(mappings);
		MappingApplicationListener listener = listeners == null ? null : listeners.createBundledMappingApplicationListener();
		MappingResults results = new MappingResults(workspace, mappings, listener);
		if (aggregateMappingManager != null)
			results.withAggregateManager(aggregateMappingManager);

		// Apply mappings to all classes in the primary resource, collecting into the results model.
		Mappings finalMappings = mappings;
		ExecutorService service = ThreadUtil.phasingService(applierThreadPool);
		WorkspaceResource resource = workspace.getPrimaryResource();
		Stream.concat(resource.jvmClassBundleStream(), resource.versionedJvmClassBundleStream()).forEach(bundle -> {
			bundle.forEach(classInfo -> {
				service.execute(() -> dumpIntoResults(results, workspace, resource, bundle, classInfo, finalMappings));
			});
		});
		ThreadUtil.blockUntilComplete(service);

		// Yield results
		return results;
	}

	/**
	 * Applies the mapping operation to every JVM class bundle of the given resource, recursively covering its
	 * {@link WorkspaceResource#getVersionedJvmClassBundles() versioned bundles} and
	 * {@link WorkspaceResource#getEmbeddedResources() embedded resources}.
	 * <p>
	 * This is the entry point for batch operations such as bulk decompilation, where a whole JAR is remapped before
	 * being processed. Compared to calling {@link #applyToClasses(Mappings, WorkspaceResource, JvmClassBundle, Collection)}
	 * once per bundle it:
	 * <ul>
	 *     <li>Enriches the given mappings <i>(mapping adapter creation, hierarchy and class lookups)</i> only once
	 *     per resource tree, instead of once per bundle.</li>
	 *     <li>Clears cached decompilations only once at the end, instead of once per bundle. The clearing operation
	 *     covers the whole workspace, so the resulting state is the same.</li>
	 * </ul>
	 * Bundles are still mapped and applied one at a time, in the same order as a manual per-bundle loop would use,
	 * so that classes mapped in earlier bundles are visible to later ones.
	 *
	 * @param mappings
	 * 		The mappings to apply.
	 * @param resource
	 * 		Root resource to map. Typically the {@link Workspace#getPrimaryResource() primary resource}.
	 *
	 * @return Result wrapper. Nothing is modified until {@link RecursiveMappingResults#apply()} is called.
	 */
	@Nonnull
	public RecursiveMappingResults applyToResourceRecursive(@Nonnull Mappings mappings,
	                                                        @Nonnull WorkspaceResource resource) {
		return applyToResourceRecursive(mappings, resource, RecursiveMappingOptions.defaults());
	}

	/**
	 * Applies the mapping operation to every JVM class bundle of the given resource, recursively.
	 *
	 * @param mappings
	 * 		The mappings to apply.
	 * @param resource
	 * 		Root resource to map. Typically the {@link Workspace#getPrimaryResource() primary resource}.
	 * @param options
	 * 		Options controlling which bundles are visited and when cached decompilations are cleared.
	 *
	 * @return Result wrapper. Nothing is modified until {@link RecursiveMappingResults#apply()} is called.
	 *
	 * @see #applyToResourceRecursive(Mappings, WorkspaceResource) Details on how this differs from per-bundle application.
	 */
	@Nonnull
	public RecursiveMappingResults applyToResourceRecursive(@Nonnull Mappings mappings,
	                                                        @Nonnull WorkspaceResource resource,
	                                                        @Nonnull RecursiveMappingOptions options) {
		// Enrich once for the whole resource tree. The enrichment only wires up the inheritance graph and workspace
		// for look-ups, so sharing it between bundles yields the same mapping output as enriching per-bundle.
		Mappings enrichedMappings = enrich(mappings);

		List<RecursiveMappingResults.BundleTarget> targets = new ArrayList<>();
		collectBundleTargets(resource, options, targets);

		boolean defer = options.deferDecompileCacheInvalidation();
		return new RecursiveMappingResults(workspace, enrichedMappings, targets, target -> {
			// Snapshot the bundle contents at the time the target is processed, so mappings applied to prior
			// bundles are accounted for.
			List<JvmClassInfo> classes = target.bundle().stream().toList();
			if (classes.isEmpty())
				return null;

			MappingResults results = applyToClassesEnriched(enrichedMappings, target.resource(), target.bundle(), classes);
			if (defer)
				results.withDeferredDecompileCacheInvalidation();
			return results;
		}, defer);
	}

	private static void collectBundleTargets(@Nonnull WorkspaceResource resource,
	                                         @Nonnull RecursiveMappingOptions options,
	                                         @Nonnull List<RecursiveMappingResults.BundleTarget> targets) {
		targets.add(new RecursiveMappingResults.BundleTarget(resource, resource.getJvmClassBundle()));
		if (options.includeVersionedBundles())
			for (VersionedJvmClassBundle bundle : resource.getVersionedJvmClassBundles().values())
				targets.add(new RecursiveMappingResults.BundleTarget(resource, bundle));
		if (options.includeEmbeddedResources())
			for (WorkspaceResource embedded : resource.getEmbeddedResources().values())
				collectBundleTargets(embedded, options, targets);
	}

	@Nonnull
	private Mappings enrich(@Nonnull Mappings mappings) {
		// Map intermediate mappings to the adapter so that we can pass in the inheritance graph for better coverage
		// of cases inherited field/method references.
		if (mappings instanceof IntermediateMappings intermediateMappings) {
			// Mapping formats that export to intermediate should mark whether they support
			// differentiation of field and variable types.
			boolean fieldDifferentiation = mappings.doesSupportFieldTypeDifferentiation();
			boolean varDifferentiation = mappings.doesSupportVariableTypeDifferentiation();
			MappingsAdapter adapter = new MappingsAdapter(fieldDifferentiation, varDifferentiation);
			adapter.importIntermediate(intermediateMappings);
			mappings = adapter;
		}

		// Check if mappings can be enriched with type look-ups
		if (mappings instanceof MappingsAdapter adapter) {
			// If we have "Dog extends Animal" and both define "jump" this lets "Dog.jump()" see "Animal.jump()"
			// allowing mappings that aren't complete for their type hierarchies to be filled in.
			adapter.enableHierarchyLookup(inheritanceGraph);
			adapter.enableClassLookup(workspace);
		}

		return mappings;
	}

	/**
	 * Applies mappings locally and dumps them into the provided results collection.
	 * <p>
	 * To apply these mappings you need to call {@link MappingResults#apply()}.
	 *
	 * @param results
	 * 		Results collection to insert into.
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param classInfo
	 * 		The class to apply mappings to.
	 * @param mappings
	 * 		The mappings to apply.
	 */
	private static void dumpIntoResults(@Nonnull MappingResults results,
	                                    @Nonnull Workspace workspace,
	                                    @Nonnull WorkspaceResource resource,
	                                    @Nonnull JvmClassBundle bundle,
	                                    @Nonnull JvmClassInfo classInfo,
	                                    @Nonnull Mappings mappings) {
		String originalName = classInfo.getName();

		// Apply renamer
		ClassReader reader = classInfo.getClassReader();
		ClassWriter writer = new ClassWriter(reader, 0);
		WorkspaceClassRemapper remapVisitor = new WorkspaceClassRemapper(writer, workspace, mappings);
		ClassVisitor cv = new IllegalSignatureRemovingVisitor(remapVisitor); // Wrap because ASM crashes otherwise with obfuscated inputs.
		reader.accept(cv, classInfo.getClassReaderFlags());

		// Update class if it has any modified references
		if (remapVisitor.hasMappingBeenApplied()) {
			JvmClassInfo updatedInfo = classInfo.toJvmClassBuilder()
					.adaptFrom(writer.toByteArray())
					.build();

			// Mark has referencing something mapped.
			HasMappedReferenceProperty.set(updatedInfo);

			// Set the result wrapper that caused this class to update.
			updatedInfo.setProperty(new RemapOriginTaskProperty(results));

			// If the name changed, mark what the original was.
			// If this property was set before (A --> B, now B --> C) then we won't update it.
			if (!updatedInfo.getName().equals(originalName))
				updatedInfo.setPropertyIfMissing(OriginalClassNameProperty.KEY,
						() -> new OriginalClassNameProperty(originalName));

			// Add to the results collection.
			results.add(workspace, resource, bundle, classInfo, updatedInfo);
		}
	}
}
