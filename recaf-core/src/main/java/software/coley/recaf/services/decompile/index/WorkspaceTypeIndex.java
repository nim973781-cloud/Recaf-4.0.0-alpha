package software.coley.recaf.services.decompile.index;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.WorkspaceModificationListener;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.Bundle;
import software.coley.recaf.workspace.model.bundle.BundleListener;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.bundle.VersionedJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Class-path index over a {@link Workspace}, resolving names with the exact same priority as
 * {@link Workspace#findClass(String)} but without walking every resource and bundle on each lookup.
 * <p/>
 * Decompilers ask the workspace for hundreds of supporting classes while decompiling a single class, and each
 * of those lookups otherwise re-walks the resource tree. The index flattens that walk once per <i>generation</i>
 * and is shared by all decompiler adapters through {@link Workspace#getTypeIndex()}.
 * <p/>
 * The index never copies bytecode. Entries point at the same {@link ClassInfo} instances held by the bundles.
 * <p/>
 * <b>Resolution priority</b><br>
 * {@link Workspace#findClass(String)} resolves in three steps: {@link Workspace#findJvmClass(boolean, String)},
 * then {@link Workspace#findLatestVersionedJvmClass(String)}, then {@link Workspace#findAndroidClass(String)}.
 * The second step can never contribute a result the first did not already find, since the JVM search covers the
 * versioned bundles of every resource while the versioned search only covers non-internal ones. So the index
 * mirrors the JVM search and the Android search, in that order.
 * <p/>
 * <b>Invalidation</b><br>
 * Bundles of non-internal resources are enumerated eagerly and observed with a {@link BundleListener}, so any
 * class added, replaced, or removed drops the current generation. Those observers are prepended so that a
 * {@link Workspace#findClass(String)} call from another bundle listener on the same put sees the new class.
 * Resources being added to or removed from the workspace drop the generation as well. Bundles of internal
 * resources <i>(the JVM runtime resource in particular)</i> are populated on demand and have no stable key set,
 * so they are queried live in traversal order rather than being enumerated.
 *
 * @author Matt Coley
 */
public class WorkspaceTypeIndex {
	private final Workspace workspace;
	private final Object buildLock = new Object();
	private final Set<Bundle<?>> attachedBundles = Collections.newSetFromMap(new IdentityHashMap<>());
	private final BundleListener<ClassInfo> structureListener = new BundleListener<>() {
		@Override
		public void onNewItem(@Nonnull String key, @Nonnull ClassInfo value) {
			invalidateStructure();
		}

		@Override
		public void onUpdateItem(@Nonnull String key, @Nonnull ClassInfo oldValue, @Nonnull ClassInfo newValue) {
			invalidateStructure();
		}

		@Override
		public void onRemoveItem(@Nonnull String key, @Nonnull ClassInfo value) {
			invalidateStructure();
		}
	};
	private volatile Generation generation;

	/**
	 * @param workspace
	 * 		Workspace to index.
	 */
	public WorkspaceTypeIndex(@Nonnull Workspace workspace) {
		this.workspace = workspace;
		workspace.addWorkspaceModificationListener(new WorkspaceModificationListener() {
			@Override
			public void onAddLibrary(@Nonnull Workspace workspace, @Nonnull WorkspaceResource library) {
				attachToResource(library);
				invalidateStructure();
			}

			@Override
			public void onRemoveLibrary(@Nonnull Workspace workspace, @Nonnull WorkspaceResource library) {
				invalidateStructure();
			}
		});
		for (WorkspaceResource resource : workspace.getAllResources(false))
			attachToResource(resource);
	}

	private void invalidateStructure() {
		// Structural updates can race a generation build. Waiting for the build lock guarantees the listener
		// invalidates either the old complete generation or the newly published one, never misses both.
		synchronized (buildLock) {
			invalidate();
		}
	}

	private void attachToResource(@Nonnull WorkspaceResource resource) {
		Queue<WorkspaceResource> queue = new ArrayDeque<>();
		queue.add(resource);
		while (!queue.isEmpty()) {
			WorkspaceResource current = queue.remove();
			prepend(current.getJvmClassBundle());
			for (VersionedJvmClassBundle versionedBundle : current.getVersionedJvmClassBundles().values())
				prepend(versionedBundle);
			for (AndroidClassBundle androidBundle : current.getAndroidClassBundles().values())
				prepend(androidBundle);
			queue.addAll(current.getEmbeddedResources().values());
		}
	}

	private void prepend(@Nonnull Bundle<? extends ClassInfo> bundle) {
		if (!attachedBundles.add(bundle))
			return;
		@SuppressWarnings("unchecked")
		Bundle<ClassInfo> typed = (Bundle<ClassInfo>) bundle;
		typed.prependBundleListener(structureListener);
	}

	/**
	 * @return Workspace this index covers.
	 */
	@Nonnull
	public Workspace getWorkspace() {
		return workspace;
	}

	/**
	 * @param name
	 * 		Internal class name.
	 *
	 * @return Matching JVM class, or {@code null} if no JVM class in the workspace goes by that name.
	 * Equivalent to the value of {@link Workspace#findJvmClass(String)}.
	 */
	@Nullable
	public JvmClassInfo getJvmClass(@Nonnull String name) {
		while (true) {
			Generation gen = current();
			IndexedClass<JvmClassInfo> indexed = resolveJvm(gen, name);
			if (gen.isValid())
				return indexed == null ? null : indexed.info();
		}
	}

	/**
	 * @param name
	 * 		Internal class name.
	 *
	 * @return Matching class, or {@code null} if the workspace holds no class by that name.
	 * Equivalent to the value of {@link Workspace#findClass(String)}.
	 */
	@Nullable
	public ClassInfo getClassInfo(@Nonnull String name) {
		while (true) {
			Generation gen = current();
			IndexedClass<JvmClassInfo> jvm = resolveJvm(gen, name);
			IndexedClass<AndroidClassInfo> android = jvm == null ? resolveAndroid(gen, name) : null;
			if (gen.isValid())
				return jvm != null ? jvm.info() : android == null ? null : android.info();
		}
	}

	/**
	 * @param name
	 * 		Internal class name.
	 *
	 * @return JVM bytecode of the matching class, or {@code null} if there is no match. Android classes are
	 * translated through {@link ClassInfo#asJvmClass()} just as a manual {@link Workspace#findClass(String)}
	 * would have to do.
	 */
	@Nullable
	public byte[] getBytecode(@Nonnull String name) {
		ClassInfo info = getClassInfo(name);
		return info == null ? null : info.asJvmClass().getBytecode();
	}

	/**
	 * @param name
	 * 		Internal class name.
	 *
	 * @return Path to the matching JVM class. Equivalent to {@link Workspace#findJvmClass(String)}.
	 */
	@Nullable
	public ClassPathNode getJvmClassPath(@Nonnull String name) {
		while (true) {
			Generation gen = current();
			ClassPathNode path = path(resolveJvm(gen, name));
			if (gen.isValid())
				return path;
		}
	}

	/**
	 * @param name
	 * 		Internal class name.
	 *
	 * @return Path to the matching Android class. Equivalent to {@link Workspace#findAndroidClass(String)}.
	 */
	@Nullable
	public ClassPathNode getAndroidClassPath(@Nonnull String name) {
		while (true) {
			Generation gen = current();
			ClassPathNode path = path(resolveAndroid(gen, name));
			if (gen.isValid())
				return path;
		}
	}

	/**
	 * @param name
	 * 		Internal class name.
	 *
	 * @return Path to the matching class. Equivalent to {@link Workspace#findClass(String)}.
	 */
	@Nullable
	public ClassPathNode getClassPath(@Nonnull String name) {
		while (true) {
			Generation gen = current();
			IndexedClass<JvmClassInfo> jvm = resolveJvm(gen, name);
			ClassPathNode path = path(jvm != null ? jvm : resolveAndroid(gen, name));
			if (gen.isValid())
				return path;
		}
	}

	/**
	 * The names here are the ones each non-internal {@link WorkspaceResource} exposes through its immediate
	 * {@link WorkspaceResource#getJvmClassBundle() JVM class bundle}, which is what decompilers declare as the
	 * classes available to them as libraries.
	 *
	 * @return Names of all classes visible as library content, computed once per generation.
	 */
	@Nonnull
	public List<String> getLibraryClassNames() {
		while (true) {
			Generation gen = current();
			List<String> names = gen.libraryClassNames;
			if (gen.isValid())
				return names;
		}
	}

	/**
	 * Caches a derived view of the index, dropped when the index is invalidated. Lets adapters keep a
	 * decompiler-specific model <i>(such as a library entry listing)</i> alive for exactly as long as the
	 * underlying class-path is unchanged, without teaching this class about decompiler types.
	 *
	 * @param key
	 * 		Key identifying the view.
	 * @param factory
	 * 		Factory to build the view when it is absent.
	 * @param <T>
	 * 		View type.
	 *
	 * @return The cached view.
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	public <T> T getView(@Nonnull Object key, @Nonnull Function<WorkspaceTypeIndex, T> factory) {
		while (true) {
			Generation gen = current();
			T view = (T) gen.views.computeIfAbsent(key, k -> factory.apply(this));
			if (gen.isValid())
				return view;
		}
	}

	/**
	 * Drops the current generation. The next lookup rebuilds it.
	 */
	public void invalidate() {
		Generation gen = generation;
		if (gen != null)
			gen.invalidate();
	}

	@Nullable
	private ClassPathNode path(@Nullable IndexedClass<? extends ClassInfo> indexed) {
		if (indexed == null)
			return null;
		return PathNodes.classPath(workspace, indexed.resource(), indexed.bundle(), indexed.info());
	}

	@Nullable
	private static IndexedClass<JvmClassInfo> resolveJvm(@Nonnull Generation gen, @Nonnull String name) {
		IndexedClass<JvmClassInfo> indexed = gen.jvmClasses.get(name);
		if (indexed != null && indexed.bundle().get(name) != indexed.info()) {
			gen.invalidate();
			return null;
		}

		// Bundles that populate themselves on demand cannot be enumerated, so they get queried directly.
		// Only those sitting earlier in the traversal order than the indexed match can take priority over it.
		int limit = indexed == null ? Integer.MAX_VALUE : indexed.order();
		for (LazyLayer layer : gen.lazyLayers) {
			if (layer.order > limit)
				break;
			JvmClassInfo info = layer.bundle.get(name);
			if (info != null)
				return new IndexedClass<>(layer.order, layer.resource, layer.bundle, info);
		}
		return indexed;
	}

	@Nullable
	private static IndexedClass<AndroidClassInfo> resolveAndroid(@Nonnull Generation gen, @Nonnull String name) {
		IndexedClass<AndroidClassInfo> indexed = gen.androidClasses.get(name);
		if (indexed != null && indexed.bundle().get(name) != indexed.info()) {
			gen.invalidate();
			return null;
		}
		return indexed;
	}

	@Nonnull
	private Generation current() {
		Generation gen = generation;
		if (gen != null && gen.isValid())
			return gen;
		synchronized (buildLock) {
			gen = generation;
			if (gen != null && gen.isValid())
				return gen;
			if (gen != null)
				gen.invalidate();
			gen = build();
			generation = gen;
			return gen;
		}
	}

	@Nonnull
	private Generation build() {
		Generation gen = new Generation();

		Set<WorkspaceResource> internalResources = Collections.newSetFromMap(new IdentityHashMap<>());
		internalResources.addAll(workspace.getInternalSupportingResources());

		// Mirrors 'Workspace#findJvmClass(true, name)': for each resource in breadth-first order the immediate
		// JVM bundle is checked before the versioned bundles, and embedded resources are visited last.
		int order = 0;
		Queue<WorkspaceResource> queue = new ArrayDeque<>(workspace.getAllResources(true));
		while (!queue.isEmpty()) {
			WorkspaceResource resource = queue.remove();
			boolean lazy = internalResources.contains(resource);
			order = gen.addJvmLayer(resource, resource.getJvmClassBundle(), order, lazy);
			for (VersionedJvmClassBundle versionedBundle : resource.getVersionedJvmClassBundles().values())
				order = gen.addJvmLayer(resource, versionedBundle, order, lazy);
			queue.addAll(resource.getEmbeddedResources().values());
		}

		// Mirrors 'Workspace#findAndroidClass(name)', which never looks at internal resources.
		queue.addAll(workspace.getAllResources(false));
		while (!queue.isEmpty()) {
			WorkspaceResource resource = queue.remove();
			for (AndroidClassBundle bundle : resource.getAndroidClassBundles().values())
				gen.addAndroidLayer(resource, bundle);
			queue.addAll(resource.getEmbeddedResources().values());
		}

		// Mirrors the library listing decompilers build from non-internal resources.
		List<String> libraryClassNames = new ArrayList<>();
		for (WorkspaceResource resource : workspace.getAllResources(false))
			libraryClassNames.addAll(resource.getJvmClassBundle().keySet());
		gen.libraryClassNames = Collections.unmodifiableList(libraryClassNames);
		return gen;
	}

	/**
	 * Records where a class was found so that a {@link ClassPathNode} can be rebuilt without another search.
	 *
	 * @param order
	 * 		Position of the containing bundle in the workspace traversal order.
	 * @param resource
	 * 		Resource holding the bundle.
	 * @param bundle
	 * 		Bundle holding the class.
	 * @param info
	 * 		The class.
	 * @param <I>
	 * 		Class type.
	 */
	private record IndexedClass<I extends ClassInfo>(int order, @Nonnull WorkspaceResource resource,
	                                                 @Nonnull Bundle<?> bundle, @Nonnull I info) {}

	/**
	 * A bundle that must be queried live because its contents are produced on demand.
	 */
	private record LazyLayer(int order, @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle) {}

	/**
	 * A snapshot of the workspace class-path. Superseded as a whole rather than patched in place, so a lookup
	 * only ever sees a self-consistent view.
	 */
	private class Generation {
		private final Map<String, IndexedClass<JvmClassInfo>> jvmClasses = new HashMap<>();
		private final Map<String, IndexedClass<AndroidClassInfo>> androidClasses = new HashMap<>();
		private final Map<Object, Object> views = new ConcurrentHashMap<>();
		private final List<LazyLayer> lazyLayers = new ArrayList<>();
		private final List<Bundle<?>> observedBundles = new ArrayList<>();
		private final AtomicBoolean valid = new AtomicBoolean(true);
		private final BundleListener<ClassInfo> listener = new BundleListener<>() {
			@Override
			public void onNewItem(@Nonnull String key, @Nonnull ClassInfo value) {
				invalidate();
			}

			@Override
			public void onUpdateItem(@Nonnull String key, @Nonnull ClassInfo oldValue, @Nonnull ClassInfo newValue) {
				invalidate();
			}

			@Override
			public void onRemoveItem(@Nonnull String key, @Nonnull ClassInfo value) {
				invalidate();
			}
		};
		private List<String> libraryClassNames = Collections.emptyList();
		private int addJvmLayer(@Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle,
		                        int order, boolean lazy) {
			if (lazy) {
				lazyLayers.add(new LazyLayer(order, resource, bundle));
			} else {
				observe(bundle);
				for (String name : bundle.keySet()) {
					// Read through 'get' so that bundles overriding it are honored, matching what the
					// workspace search would have seen.
					JvmClassInfo info = bundle.get(name);

					// Earlier layers win, matching the 'first match' behavior of the workspace search.
					if (info != null && !jvmClasses.containsKey(name))
						jvmClasses.put(name, new IndexedClass<>(order, resource, bundle, info));
				}
			}
			return order + 1;
		}

		private void addAndroidLayer(@Nonnull WorkspaceResource resource, @Nonnull AndroidClassBundle bundle) {
			observe(bundle);
			for (String name : bundle.keySet()) {
				AndroidClassInfo info = bundle.get(name);
				if (info != null && !androidClasses.containsKey(name))
					androidClasses.put(name, new IndexedClass<>(0, resource, bundle, info));
			}
		}

		@SuppressWarnings("unchecked")
		private void observe(@Nonnull Bundle<? extends ClassInfo> bundle) {
			// Registered before the contents are read so that a concurrent edit invalidates this generation
			// rather than slipping past it.
			((Bundle<ClassInfo>) bundle).addBundleListener(listener);
			observedBundles.add(bundle);
		}

		@SuppressWarnings("unchecked")
		private void invalidate() {
			if (!valid.compareAndSet(true, false))
				return;
			for (Bundle<?> bundle : observedBundles)
				((Bundle<ClassInfo>) bundle).removeBundleListener(listener);
			views.clear();
		}

		private boolean isValid() {
			return valid.get();
		}
	}
}
