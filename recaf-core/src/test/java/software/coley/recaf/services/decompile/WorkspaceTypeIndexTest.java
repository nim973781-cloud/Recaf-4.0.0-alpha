package software.coley.recaf.services.decompile;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.StubFileInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.decompile.index.WorkspaceTypeIndex;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.AccessibleFields;
import software.coley.recaf.test.dummy.AccessibleMethods;
import software.coley.recaf.test.dummy.ClassWithExceptions;
import software.coley.recaf.test.dummy.HelloWorld;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.BasicAndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.bundle.BasicVersionedJvmClassBundle;
import software.coley.recaf.workspace.model.bundle.Bundle;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.bundle.VersionedJvmClassBundle;
import software.coley.recaf.workspace.model.resource.ResourceJvmClassListener;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResourceBuilder;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link WorkspaceTypeIndex}, which decompiler adapters use in place of repeated
 * {@link Workspace#findClass(String)} calls. Every case here pins the index to the workspace search
 * rather than to a hard-coded expectation, since matching that search is the whole contract.
 */
class WorkspaceTypeIndexTest {
	private static final String NAME_ACCESSIBLE_FIELDS = "software/coley/recaf/test/dummy/AccessibleFields";
	private static final String NAME_ACCESSIBLE_METHODS = "software/coley/recaf/test/dummy/AccessibleMethods";
	private static final String NAME_CLASS_WITH_EXCEPTIONS = "software/coley/recaf/test/dummy/ClassWithExceptions";
	private static final String NAME_HELLO_WORLD = "software/coley/recaf/test/dummy/HelloWorld";
	private static final String NAME_STRING = "java/lang/String";
	private static final String NAME_MISSING = "does/not/Exist";

	@Test
	void resolvesAcrossPrimaryAndSupportingResources() throws IOException {
		WorkspaceResource primary = new WorkspaceResourceBuilder()
				.withJvmClassBundle(TestClassUtils.fromClasses(AccessibleFields.class, AccessibleMethods.class))
				.build();
		WorkspaceResource supporting = new WorkspaceResourceBuilder()
				.withJvmClassBundle(TestClassUtils.fromClasses(ClassWithExceptions.class))
				.build();
		Workspace workspace = new BasicWorkspace(primary, List.of(supporting));

		assertMatchesWorkspaceSearch(workspace, NAME_ACCESSIBLE_FIELDS, NAME_ACCESSIBLE_METHODS,
				NAME_CLASS_WITH_EXCEPTIONS, NAME_STRING, NAME_MISSING);
	}

	@Test
	void prefersEarlierResourceWhenNameIsDuplicated() throws IOException {
		JvmClassInfo shadowed = TestClassUtils.createEmptyClass(NAME_ACCESSIBLE_FIELDS);
		WorkspaceResource primary = new WorkspaceResourceBuilder()
				.withJvmClassBundle(TestClassUtils.fromClasses(AccessibleFields.class))
				.build();
		WorkspaceResource supporting = new WorkspaceResourceBuilder()
				.withJvmClassBundle(TestClassUtils.fromClasses(shadowed))
				.build();
		Workspace workspace = new BasicWorkspace(primary, List.of(supporting));

		assertMatchesWorkspaceSearch(workspace, NAME_ACCESSIBLE_FIELDS);
		assertSame(primary.getJvmClassBundle().get(NAME_ACCESSIBLE_FIELDS),
				workspace.getTypeIndex().getJvmClass(NAME_ACCESSIBLE_FIELDS));
	}

	@Test
	void resolvesVersionedClasses() throws IOException {
		BasicVersionedJvmClassBundle versioned = new BasicVersionedJvmClassBundle(17);
		versioned.initialPut(TestClassUtils.fromRuntimeClass(ClassWithExceptions.class));
		NavigableMap<Integer, VersionedJvmClassBundle> versionedBundles = new TreeMap<>();
		versionedBundles.put(17, versioned);
		WorkspaceResource primary = new WorkspaceResourceBuilder()
				.withJvmClassBundle(TestClassUtils.fromClasses(AccessibleFields.class))
				.withVersionedJvmClassBundles(versionedBundles)
				.build();
		Workspace workspace = new BasicWorkspace(primary);

		assertMatchesWorkspaceSearch(workspace, NAME_ACCESSIBLE_FIELDS, NAME_CLASS_WITH_EXCEPTIONS);
		assertSame(versioned, workspace.getTypeIndex()
				.getClassPath(NAME_CLASS_WITH_EXCEPTIONS)
				.getValueOfType(Bundle.class));
	}

	@Test
	void resolvesClassesInEmbeddedResources() throws IOException {
		WorkspaceFileResource embedded = new WorkspaceFileResourceBuilder()
				.withFileInfo(new StubFileInfo("embedded.jar"))
				.withJvmClassBundle(TestClassUtils.fromClasses(HelloWorld.class))
				.build();
		WorkspaceResource primary = new WorkspaceResourceBuilder()
				.withJvmClassBundle(TestClassUtils.fromClasses(AccessibleFields.class))
				.withEmbeddedResources(Map.of("embedded.jar", embedded))
				.build();
		Workspace workspace = new BasicWorkspace(primary);

		assertMatchesWorkspaceSearch(workspace, NAME_ACCESSIBLE_FIELDS, NAME_HELLO_WORLD);
	}

	@Test
	void resolvesRuntimeClassesAheadOfEmbeddedResources() throws IOException {
		// 'HelloWorld' is on the test class-path, so the runtime resource can serve it too. The workspace search
		// visits internal resources before descending into embedded ones, and the index has to agree.
		WorkspaceFileResource embedded = new WorkspaceFileResourceBuilder()
				.withFileInfo(new StubFileInfo("embedded.jar"))
				.withJvmClassBundle(TestClassUtils.fromClasses(HelloWorld.class))
				.build();
		WorkspaceResource primary = new WorkspaceResourceBuilder()
				.withEmbeddedResources(Map.of("embedded.jar", embedded))
				.build();
		Workspace workspace = new BasicWorkspace(primary);

		assertMatchesWorkspaceSearch(workspace, NAME_HELLO_WORLD, NAME_STRING);
	}

	@Test
	void resolvesAndroidClassesOnlyWhenNoJvmClassMatches() throws IOException {
		AndroidClassInfo androidHelloWorld = mockAndroidClass(NAME_HELLO_WORLD);
		AndroidClassInfo androidOnly = mockAndroidClass("android/OnlyHere");
		BasicAndroidClassBundle androidBundle = new BasicAndroidClassBundle();
		androidBundle.initialPut(androidHelloWorld);
		androidBundle.initialPut(androidOnly);
		WorkspaceResource primary = new WorkspaceResourceBuilder()
				.withJvmClassBundle(TestClassUtils.fromClasses(HelloWorld.class))
				.withAndroidClassBundles(Map.<String, AndroidClassBundle>of("classes.dex", androidBundle))
				.build();
		Workspace workspace = new BasicWorkspace(primary);

		assertMatchesWorkspaceSearch(workspace, NAME_HELLO_WORLD, "android/OnlyHere", NAME_MISSING);
		assertNull(workspace.getTypeIndex().getJvmClass("android/OnlyHere"));
	}

	@Test
	void indexIsReusedByTheWorkspace() {
		Workspace workspace = new BasicWorkspace(new WorkspaceResourceBuilder().build());
		assertSame(workspace.getTypeIndex(), workspace.getTypeIndex());
	}

	@Test
	void picksUpClassEdits() throws IOException {
		BasicJvmClassBundle bundle = TestClassUtils.fromClasses(AccessibleFields.class);
		Workspace workspace = TestClassUtils.fromBundle(bundle);
		WorkspaceTypeIndex index = workspace.getTypeIndex();
		assertSame(bundle.get(NAME_ACCESSIBLE_FIELDS), index.getJvmClass(NAME_ACCESSIBLE_FIELDS));

		// Replacing a class must be visible to the index, or decompilation would keep referencing stale bytecode.
		JvmClassInfo replacement = TestClassUtils.createEmptyClass(NAME_ACCESSIBLE_FIELDS);
		bundle.put(replacement);
		assertSame(replacement, index.getJvmClass(NAME_ACCESSIBLE_FIELDS));

		// Same for additions and removals.
		JvmClassInfo added = TestClassUtils.createEmptyClass("com/example/Added");
		bundle.put(added);
		assertSame(added, index.getJvmClass("com/example/Added"));
		bundle.remove("com/example/Added");
		assertNull(index.getJvmClass("com/example/Added"));
		assertMatchesWorkspaceSearch(workspace, NAME_ACCESSIBLE_FIELDS, "com/example/Added");
	}

	@Test
	void findClassFromResourceListenerSeesNewlyAddedClass() throws IOException {
		// CallGraph (and other resource listeners) look up classes while handling onNewClass. The index has
		// to already be invalidated by then; a generation rebuilt after those listeners would be too late.
		BasicJvmClassBundle bundle = TestClassUtils.fromClasses(AccessibleFields.class);
		Workspace workspace = TestClassUtils.fromBundle(bundle);
		workspace.getTypeIndex().getJvmClass(NAME_ACCESSIBLE_FIELDS);

		JvmClassInfo added = TestClassUtils.createEmptyClass("com/example/Added");
		var seen = new JvmClassInfo[1];
		workspace.getPrimaryResource().addResourceJvmClassListener(new ResourceJvmClassListener() {
			@Override
			public void onNewClass(@Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle b,
			                       @Nonnull JvmClassInfo cls) {
				ClassPathNode path = workspace.findJvmClass(cls.getName());
				seen[0] = path == null ? null : path.getValue().asJvmClass();
			}

			@Override
			public void onUpdateClass(@Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle b,
			                          @Nonnull JvmClassInfo oldCls, @Nonnull JvmClassInfo newCls) {
			}

			@Override
			public void onRemoveClass(@Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle b,
			                          @Nonnull JvmClassInfo cls) {
			}
		});
		bundle.put(added);
		assertSame(added, seen[0]);
	}

	@Test
	void picksUpResourceChanges() throws IOException {
		// The runtime resource is left out so that classes only resolve through the resources under test.
		Workspace workspace = new BasicWorkspace(new WorkspaceResourceBuilder()
				.withJvmClassBundle(TestClassUtils.fromClasses(AccessibleFields.class))
				.build(), List.of(), false);
		WorkspaceTypeIndex index = workspace.getTypeIndex();
		assertNull(index.getJvmClass(NAME_CLASS_WITH_EXCEPTIONS));

		WorkspaceResource supporting = new WorkspaceResourceBuilder()
				.withJvmClassBundle(TestClassUtils.fromClasses(ClassWithExceptions.class))
				.build();
		workspace.addSupportingResource(supporting);
		assertNotNull(index.getJvmClass(NAME_CLASS_WITH_EXCEPTIONS));
		assertMatchesWorkspaceSearch(workspace, NAME_ACCESSIBLE_FIELDS, NAME_CLASS_WITH_EXCEPTIONS);

		workspace.removeSupportingResource(supporting);
		assertNull(index.getJvmClass(NAME_CLASS_WITH_EXCEPTIONS));
	}

	@Test
	void libraryClassNamesMatchNonInternalResources() throws IOException {
		WorkspaceResource primary = new WorkspaceResourceBuilder()
				.withJvmClassBundle(TestClassUtils.fromClasses(AccessibleFields.class, AccessibleMethods.class))
				.build();
		WorkspaceResource supporting = new WorkspaceResourceBuilder()
				.withJvmClassBundle(TestClassUtils.fromClasses(ClassWithExceptions.class))
				.build();
		Workspace workspace = new BasicWorkspace(primary, List.of(supporting));

		List<String> expected = workspace.getAllResources(false).stream()
				.map(WorkspaceResource::getJvmClassBundle)
				.flatMap(b -> b.keySet().stream())
				.toList();
		assertEquals(expected, workspace.getTypeIndex().getLibraryClassNames());
	}

	@Test
	void cachedViewsSurviveLookupsButNotEdits() throws IOException {
		BasicJvmClassBundle bundle = TestClassUtils.fromClasses(AccessibleFields.class);
		Workspace workspace = TestClassUtils.fromBundle(bundle);
		WorkspaceTypeIndex index = workspace.getTypeIndex();

		Object view = index.getView("test", i -> new Object());
		assertSame(view, index.getView("test", i -> new Object()));

		bundle.put(TestClassUtils.createEmptyClass("com/example/Added"));
		assertNotSame(view, index.getView("test", i -> new Object()));
	}

	@Test
	void concurrentReplacementAndRemovalNeverReturnsDeletedGeneration() throws Exception {
		String name = "com/example/Racy";
		BasicJvmClassBundle bundle = new BasicJvmClassBundle();
		Workspace workspace = TestClassUtils.fromBundle(bundle);
		WorkspaceTypeIndex index = workspace.getTypeIndex();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			for (int i = 0; i < 100; i++) {
				JvmClassInfo oldInfo = TestClassUtils.createEmptyClass(name);
				bundle.put(oldInfo);
				assertSame(oldInfo, index.getJvmClass(name));

				CountDownLatch start = new CountDownLatch(1);
				Future<JvmClassInfo> lookup = executor.submit(() -> {
					start.await();
					return index.getJvmClass(name);
				});
				JvmClassInfo newInfo = TestClassUtils.createEmptyClass(name);
				start.countDown();
				bundle.put(newInfo);

				JvmClassInfo raced = lookup.get(1, TimeUnit.SECONDS);
				assertTrue(raced == oldInfo || raced == newInfo,
						"Lookup returned an object outside the old/new generations");

				bundle.remove(name);
				assertNull(index.getJvmClass(name), "Index returned a class after its removal completed");
			}
		} finally {
			executor.shutdownNow();
		}
	}

	/**
	 * @param workspace
	 * 		Workspace to compare against.
	 * @param names
	 * 		Class names to look up in both the workspace and its index.
	 */
	private static void assertMatchesWorkspaceSearch(@Nonnull Workspace workspace, @Nonnull String... names) {
		WorkspaceTypeIndex index = workspace.getTypeIndex();
		for (String name : names) {
			ClassPathNode expected = workspace.findClass(name);
			ClassPathNode actual = index.getClassPath(name);
			if (expected == null) {
				assertNull(actual, "Index found '" + name + "' but the workspace search did not");
				assertNull(index.getClassInfo(name), "Index found '" + name + "' but the workspace search did not");
				continue;
			}
			assertNotNull(actual, "Index missed '" + name + "' but the workspace search found it");
			assertEquals(expected, actual, "Index resolved '" + name + "' to a different path");
			assertSame(expected.getValue(), actual.getValue(), "Index resolved '" + name + "' to a different class");
			assertSame(expected.getValueOfType(WorkspaceResource.class), actual.getValueOfType(WorkspaceResource.class),
					"Index resolved '" + name + "' in a different resource");
			assertSame(expected.getValueOfType(Bundle.class), actual.getValueOfType(Bundle.class),
					"Index resolved '" + name + "' in a different bundle");
			assertSame(expected.getValue(), index.getClassInfo(name));

			ClassPathNode expectedJvm = workspace.findJvmClass(name);
			assertEquals(expectedJvm, index.getJvmClassPath(name), "Index disagreed on the JVM-only search for " + name);
			assertEquals(workspace.findAndroidClass(name), index.getAndroidClassPath(name),
					"Index disagreed on the Android-only search for " + name);
		}
	}

	@Nonnull
	private static AndroidClassInfo mockAndroidClass(@Nonnull String name) {
		AndroidClassInfo info = mock(AndroidClassInfo.class);
		when(info.getName()).thenReturn(name);
		when(info.getPackageName()).thenReturn(name.substring(0, name.lastIndexOf('/')));
		when(info.isAndroidClass()).thenReturn(true);
		when(info.isJvmClass()).thenReturn(false);
		when(info.asAndroidClass()).thenReturn(info);
		return info;
	}
}
