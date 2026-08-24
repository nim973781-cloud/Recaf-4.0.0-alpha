package software.coley.recaf.services.mapping;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.StubFileInfo;
import software.coley.recaf.info.annotation.AnnotationElement;
import software.coley.recaf.info.annotation.AnnotationInfo;
import software.coley.recaf.info.properties.builtin.CachedDecompileProperty;
import software.coley.recaf.info.member.LocalVariable;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.info.properties.builtin.OriginalClassNameProperty;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.mapping.aggregate.AggregatedMappings;
import software.coley.recaf.services.mapping.gen.MappingGenerator;
import software.coley.recaf.services.mapping.gen.naming.NameGenerator;
import software.coley.recaf.services.mapping.gen.filter.NameGeneratorFilter;
import software.coley.recaf.services.mapping.gen.naming.AlphabetNameGenerator;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.*;
import software.coley.recaf.util.ClassDefiner;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicVersionedJvmClassBundle;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.bundle.VersionedJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResourceBuilder;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MappingApplier} with some edge case classes.
 */
class MappingApplierTest extends TestBase {
	private static final String EMBEDDED_NAME = "embedded.jar";
	private static final int PRIMARY_VERSION = 11;
	private static final int EMBEDDED_VERSION = 17;
	private static final String MAPPED_SUPPLIER = "mapped/RenamedSupplier";
	private static final String MAPPED_OUTER = "mapped/RenamedOuter";
	private static final String MAPPED_ENUM = "mapped/RenamedEnum";
	static NameGenerator nameGenerator;
	MappingGenerator mappingGenerator;
	Workspace workspace;
	WorkspaceResource resource;
	AggregateMappingManager aggregateMappingManager;
	InheritanceGraph inheritanceGraph;
	MappingApplierService mappingApplierService;

	@BeforeAll
	static void setupGenerator() {
		String alphabet = "abcdefghijklmnopqrstuvwxyz";
		nameGenerator = new AlphabetNameGenerator(alphabet, 3);
	}

	@BeforeEach
	void prepareWorkspace() throws IOException {
		// We want to reset the workspace before each test
		workspace = TestClassUtils.fromBundle(TestClassUtils.fromClasses(
				AnonymousLambda.class,
				StringSupplier.class,
				//
				DummyEnum.class,
				DummyEnumPrinter.class,
				//
				AnnotationImpl.class,
				ClassWithAnnotation.class,
				//
				ParameterBase.class,
				ParameterChild.class,
				//
				OverlapInterfaceA.class,
				OverlapInterfaceB.class,
				OverlapClassAB.class,
				OverlapCaller.class
		));
		resource = workspace.getPrimaryResource();

		// Get and initialize the aggregate mapping manager
		aggregateMappingManager = recaf.get(AggregateMappingManager.class);
		aggregateMappingManager.toString();

		// Get and initialize the inheritance graph service
		InheritanceGraphService graphService = recaf.get(InheritanceGraphService.class);
		graphService.toString();

		// Get inherit graph for the
		mappingGenerator = recaf.get(MappingGenerator.class);
		mappingApplierService = recaf.get(MappingApplierService.class);

		// Set the workspace
		workspaceManager.setCurrent(workspace);
		inheritanceGraph = graphService.getCurrentWorkspaceInheritanceGraph();
	}

	@Test
	void longName() {
		String alphabet = "abcdefghijklmnopqrstuvwxyz";
		AlphabetNameGenerator longNameGenerator = new AlphabetNameGenerator(alphabet, 2048);
		workspace.getPrimaryResource().getJvmClassBundle().forEach(cls -> {
			assertDoesNotThrow(() -> longNameGenerator.mapClass(cls));
		});
	}

	@Test
	void applyAnonymousLambda() {
		String stringSupplierName = StringSupplier.class.getName().replace('.', '/');
		String anonymousLambdaName = AnonymousLambda.class.getName().replace('.', '/');

		// Create mappings for all classes but the runner 'AnonymousLambda'
		Mappings mappings = mappingGenerator.generate(workspace, resource, inheritanceGraph, nameGenerator, new NameGeneratorFilter(null, true) {
			@Override
			public boolean shouldMapClass(@Nonnull ClassInfo info) {
				return !info.getName().equals(anonymousLambdaName);
			}

			@Override
			public boolean shouldMapMethod(@Nonnull ClassInfo owner, @Nonnull MethodMember method) {
				return shouldMapClass(owner);
			}
		});

		// Preview the mapping operation
		MappingResults results = mappingApplierService.inCurrentWorkspace().applyToPrimaryResource(mappings);

		// The supplier class we define should be remapped.
		// The runner class (AnonymousLambda) itself should not be remapped, but should be updated to point to
		// the new StringSupplier class name.
		String mappedStringSupplierName = mappings.getMappedClassName(stringSupplierName);
		assertNotNull(mappedStringSupplierName, "StringSupplier should be remapped");
		assertNull(mappings.getMappedClassName(anonymousLambdaName), "AnonymousLambda should not be remapped");
		assertTrue(results.wasMapped(stringSupplierName), "StringSupplier should have updated");
		assertTrue(results.wasMapped(anonymousLambdaName), "AnonymousLambda should have updated");

		// Verify that the original name is stored as a property.
		ClassPathNode classPath = results.getPostMappingPath(stringSupplierName);
		assertNotNull(classPath, "Could not find mapped StringSupplier in workspace");
		JvmClassInfo mappedStringSupplier = classPath.getValue().asJvmClass();
		assertEquals(stringSupplierName, OriginalClassNameProperty.get(mappedStringSupplier),
				"Did not record original name after applying mappings");

		// Assert that the method is still runnable.
		String result = runMapped(AnonymousLambda.class, "run");
		assertTrue(result.contains("One: java.util.function.Supplier"),
				"JDK class reference should not be mapped");
		assertFalse(result.contains(stringSupplierName),
				"Class reference to '" + stringSupplierName + "' should have been remapped");

		// Assert aggregate updated too.
		// We will validate this is only done AFTER 'results.apply()' is run.
		// For future tests we will skip this since if it works here, it works there.
		AggregatedMappings aggregatedMappings = aggregateMappingManager.getAggregatedMappings();
		assertNotNull(aggregatedMappings);
		assertNull(aggregatedMappings.getMappedClassName(stringSupplierName),
				"StringSupplier should not yet be tracked in aggregate");
		results.apply();
		assertNotNull(aggregatedMappings.getMappedClassName(stringSupplierName),
				"StringSupplier should be tracked in aggregate");
	}

	@Test
	void applyDummyEnumPrinter() {
		String dummyEnumName = DummyEnum.class.getName().replace('.', '/');
		String dummyEnumPrinterName = DummyEnumPrinter.class.getName().replace('.', '/');

		// Create mappings for all classes but the runner 'DummyEnumPrinter'
		Mappings mappings = mappingGenerator.generate(workspace, resource, inheritanceGraph, nameGenerator, new NameGeneratorFilter(null, true) {
			@Override
			public boolean shouldMapClass(@Nonnull ClassInfo info) {
				return !info.getName().equals(dummyEnumPrinterName);
			}

			@Override
			public boolean shouldMapMethod(@Nonnull ClassInfo owner, @Nonnull MethodMember method) {
				return shouldMapClass(owner);
			}
		});

		// Preview the mapping operation
		MappingResults results = mappingApplierService.inCurrentWorkspace().applyToPrimaryResource(mappings);

		// The enum class we define should be remapped.
		// The runner class (DummyEnumPrinter) itself should not be remapped, but should be updated to point to
		// the new DummyEnum class name.
		assertNotNull(mappings.getMappedClassName(dummyEnumName), "DummyEnum should be remapped");
		assertNull(mappings.getMappedClassName(dummyEnumPrinterName), "DummyEnumPrinter should not be remapped");
		assertNull(mappings.getMappedMethodName(dummyEnumName, "values", "()[L" + dummyEnumName + ";"),
				"DummyEnum#values() should not be remapped");
		assertNull(mappings.getMappedMethodName(dummyEnumName, "valueOf", "(Ljava/lang/String;)L" + dummyEnumName + ";"),
				"DummyEnum#valueOf(String) should not be remapped");
		assertTrue(results.wasMapped(dummyEnumName), "DummyEnum should have updated");
		assertTrue(results.wasMapped(dummyEnumPrinterName), "DummyEnumPrinter should have updated");

		// Assert aggregate updated too.
		results.apply();
		AggregatedMappings aggregatedMappings = aggregateMappingManager.getAggregatedMappings();
		assertNotNull(aggregatedMappings);
		assertNotNull(aggregatedMappings.getMappedClassName(dummyEnumName),
				"DummyEnum should be tracked in aggregate");

		// Assert that the methods are still runnable.
		runMapped(DummyEnumPrinter.class, "run1");
		runMapped(DummyEnumPrinter.class, "run2");
	}

	@Test
	void applyClassWithAnnotation() {
		String annotationName = AnnotationImpl.class.getName().replace('.', '/');
		String classWithAnnotationName = ClassWithAnnotation.class.getName().replace('.', '/');

		// Create mappings for all classes but the target 'ClassWithAnnotation'
		Mappings mappings = mappingGenerator.generate(workspace, resource, inheritanceGraph, nameGenerator, new NameGeneratorFilter(null, true) {
			@Override
			public boolean shouldMapClass(@Nonnull ClassInfo info) {
				return !info.getName().equals(classWithAnnotationName);
			}
		});

		// Preview the mapping operation
		MappingResults results = mappingApplierService.inCurrentWorkspace().applyToPrimaryResource(mappings);

		// The annotation class we define should be remapped.
		// The user class (ClassWithAnnotation) itself should not be remapped,
		// but its annotation usage should be updated.
		String mappedAnnotationName = mappings.getMappedClassName(annotationName);
		assertNotNull(mappedAnnotationName, "AnnotationImpl should be remapped");
		assertNull(mappings.getMappedClassName(classWithAnnotationName), "ClassWithAnnotation should not be remapped");
		assertTrue(results.wasMapped(annotationName), "AnnotationImpl should have updated");
		assertTrue(results.wasMapped(classWithAnnotationName), "ClassWithAnnotation should have updated");

		// Assert aggregate updated too.
		results.apply();
		AggregatedMappings aggregatedMappings = aggregateMappingManager.getAggregatedMappings();
		assertNotNull(aggregatedMappings);
		assertNotNull(aggregatedMappings.getMappedClassName(annotationName),
				"AnnotationImpl should be tracked in aggregate");

		// Get the names of the annotation's mapped attribute methods
		String annoValueName = mappings.getMappedMethodName(annotationName, "value", "()Ljava/lang/String;");
		String annoPolicyName = mappings.getMappedMethodName(annotationName, "policy", "()Ljava/lang/annotation/Retention;");

		// Assert the user class has the correct new values
		ClassPathNode classPath = results.getPostMappingPath(classWithAnnotationName);
		assertNotNull(classPath, "Could not find: " + classWithAnnotationName);
		JvmClassInfo classWithAnnotation = classPath.getValue().asJvmClass();
		AnnotationInfo annotationInfo = classWithAnnotation.getAnnotations().get(0);
		assertEquals("L" + mappedAnnotationName + ";", annotationInfo.getDescriptor(),
				"AnnotationImpl not remapped in ClassWithAnnotation");
		AnnotationElement valueElement = annotationInfo.getElements().get(annoValueName);
		AnnotationElement policyElement = annotationInfo.getElements().get(annoPolicyName);
		assertNotNull(valueElement, "Missing mapped value element");
		assertNotNull(policyElement, "Missing mapped policy element");
	}

	@Test
	void applyOverlapping() {
		String overlapInterfaceAName = OverlapInterfaceA.class.getName().replace('.', '/');
		String overlapInterfaceBName = OverlapInterfaceB.class.getName().replace('.', '/');
		String overlapClassABName = OverlapClassAB.class.getName().replace('.', '/');
		String overlapCallerName = OverlapCaller.class.getName().replace('.', '/');

		// Create mappings for all classes but the runner 'OverlapCaller'
		Mappings mappings = mappingGenerator.generate(workspace, resource, inheritanceGraph, nameGenerator, new NameGeneratorFilter(null, true) {
			@Override
			public boolean shouldMapClass(@Nonnull ClassInfo info) {
				return !info.getName().equals(overlapCallerName);
			}

			@Override
			public boolean shouldMapMethod(@Nonnull ClassInfo owner, @Nonnull MethodMember method) {
				return shouldMapClass(owner);
			}
		});

		// Preview the mapping operation
		MappingResults results = mappingApplierService.inCurrentWorkspace().applyToPrimaryResource(mappings);

		assertNotNull(mappings.getMappedClassName(overlapInterfaceAName), "OverlapInterfaceA should be remapped");
		assertNotNull(mappings.getMappedClassName(overlapInterfaceBName), "OverlapInterfaceB should be remapped");
		assertNotNull(mappings.getMappedClassName(overlapClassABName), "OverlapClassAB should be remapped");
		assertNull(mappings.getMappedClassName(overlapCallerName), "OverlapCaller should not be remapped");
		assertTrue(results.wasMapped(overlapInterfaceAName), "OverlapInterfaceA should have updated");
		assertTrue(results.wasMapped(overlapInterfaceBName), "OverlapInterfaceB should have updated");
		assertTrue(results.wasMapped(overlapClassABName), "OverlapClassAB should have updated");
		assertTrue(results.wasMapped(overlapCallerName), "OverlapCaller should have updated");

		// Assert aggregate updated too.
		results.apply();
		AggregatedMappings aggregatedMappings = aggregateMappingManager.getAggregatedMappings();
		assertNotNull(aggregatedMappings);
		assertNotNull(aggregatedMappings.getMappedClassName(overlapInterfaceAName),
				"OverlapInterfaceA should be tracked in aggregate");
		assertNotNull(aggregatedMappings.getMappedClassName(overlapInterfaceBName),
				"OverlapInterfaceB should be tracked in aggregate");
		assertNotNull(aggregatedMappings.getMappedClassName(overlapClassABName),
				"OverlapClassAB should be tracked in aggregate");
		assertNull(aggregatedMappings.getMappedClassName(overlapCallerName),
				"OverlapCaller should not be tracked in aggregate");

		// Assert that the method is still runnable.
		runMapped(OverlapCaller.class, "run");
	}

	@Test
	void applyClearsCachedDecompilations() {
		String stringSupplierName = StringSupplier.class.getName().replace('.', '/');
		String anonymousLambdaName = AnonymousLambda.class.getName().replace('.', '/');

		Mappings mappings = mappingGenerator.generate(workspace, resource, inheritanceGraph, nameGenerator, new NameGeneratorFilter(null, true) {
			@Override
			public boolean shouldMapClass(@Nonnull ClassInfo info) {
				return !info.getName().equals(anonymousLambdaName);
			}

			@Override
			public boolean shouldMapMethod(@Nonnull ClassInfo owner, @Nonnull MethodMember method) {
				return shouldMapClass(owner);
			}
		});

		DecompilerManager decompilerManager = recaf.get(DecompilerManager.class);
		var decompiler = decompilerManager.getTargetJvmDecompiler();
		assertNotNull(decompiler, "Expected a target JVM decompiler");

		JvmClassInfo sameNamedClassBeforeMapping = resource.getJvmClassBundle().get(anonymousLambdaName);
		assertNotNull(sameNamedClassBeforeMapping, "Could not find same-named class");
		JvmClassInfo renamedClassBeforeMapping = resource.getJvmClassBundle().get(stringSupplierName);
		assertNotNull(renamedClassBeforeMapping, "Could not find class that will be renamed");
		CachedDecompileProperty.set(sameNamedClassBeforeMapping, decompiler, new DecompileResult("// stale source", 0));
		CachedDecompileProperty.set(renamedClassBeforeMapping, decompiler, new DecompileResult("// stale source", 0));

		MappingResults results = mappingApplierService.inCurrentWorkspace().applyToPrimaryResource(mappings);
		results.apply();

		// AnonymousLambda keeps its name, but its reference to StringSupplier causes mapping to replace its class-info.
		// Inspect the live bundle entry rather than the stale pre-mapping object.
		JvmClassInfo sameNamedClass = resource.getJvmClassBundle().get(anonymousLambdaName);
		assertNotNull(sameNamedClass, "Could not find same-named class after mapping");
		assertNotSame(sameNamedClassBeforeMapping, sameNamedClass, "Expected mapped references to replace the class-info");
		assertNull(CachedDecompileProperty.get(sameNamedClass, decompiler),
				"Mapping apply should clear cached decompilation results for same-named classes too");

		ClassPathNode renamedClassPath = results.getPostMappingPath(stringSupplierName);
		assertNotNull(renamedClassPath, "Could not find renamed class");
		JvmClassInfo renamedClass = renamedClassPath.getValue().asJvmClass();
		assertNull(CachedDecompileProperty.get(renamedClass, decompiler),
				"Mapping apply should clear cached decompilation results for renamed classes too");
	}

	@Test
	void applyInheritedVariableMappings() {
		String parentName = ParameterBase.class.getName().replace('.', '/');
		String childName = ParameterChild.class.getName().replace('.', '/');
		String methodName = "render";
		String methodDesc = "(Ljava/lang/String;I)V";

		IntermediateMappings mappings = new IntermediateMappings();
		mappings.addVariable(parentName, methodName, methodDesc, "Ljava/lang/String;", null, 1, "text");
		mappings.addVariable(parentName, methodName, methodDesc, "I", null, 2, "count");

		MappingResults results = mappingApplierService.inCurrentWorkspace().applyToPrimaryResource(mappings);
		results.apply();

		ClassPathNode childPath = results.getPostMappingPath(childName);
		assertNotNull(childPath, "Expected override class to be updated by inherited variable mappings");
		MethodMember childMethod = childPath.getValue().asJvmClass().getDeclaredMethod(methodName, methodDesc);
		assertNotNull(childMethod, "Expected override method to remain present");

		LocalVariable first = childMethod.getLocalVariable(1);
		LocalVariable second = childMethod.getLocalVariable(2);
		assertNotNull(first, "Expected first parameter local variable");
		assertNotNull(second, "Expected second parameter local variable");
		assertEquals("text", first.getName(), "Expected inherited parameter name for first slot");
		assertEquals("count", second.getName(), "Expected inherited parameter name for second slot");

		DecompilerManager decompilerManager = recaf.get(DecompilerManager.class);
		String decompiled = assertDoesNotThrow(() -> decompilerManager.decompile(workspace, childPath.getValue().asJvmClass()).get().getText());
		assertNotNull(decompiled, "Expected decompiled output for override class");
		assertTrue(decompiled.contains("render(String text, int count)"),
				"Expected decompiled method signature to use inherited parameter names");
	}

	@Test
	void applyRecursivelyMatchesPerBundlePath() throws IOException {
		// Two identical workspaces, one mapped bundle-by-bundle like the batch decompile path does,
		// the other mapped in one recursive operation.
		Workspace perBundleWorkspace = newRecursiveWorkspace();
		Workspace recursiveWorkspace = newRecursiveWorkspace();

		applyPerBundle(perBundleWorkspace, newRecursiveMappings());
		mappingApplierService.inWorkspace(recursiveWorkspace)
				.applyToResourceRecursive(newRecursiveMappings(), recursiveWorkspace.getPrimaryResource())
				.apply();

		List<JvmClassBundle> perBundleBundles = collectBundles(perBundleWorkspace.getPrimaryResource());
		List<JvmClassBundle> recursiveBundles = collectBundles(recursiveWorkspace.getPrimaryResource());
		assertEquals(perBundleBundles.size(), recursiveBundles.size(), "Expected the same bundle layout");
		for (int i = 0; i < perBundleBundles.size(); i++) {
			JvmClassBundle expectedBundle = perBundleBundles.get(i);
			JvmClassBundle actualBundle = recursiveBundles.get(i);
			assertEquals(new TreeSet<>(expectedBundle.keySet()), new TreeSet<>(actualBundle.keySet()),
					"Recursive application yielded different class names in bundle " + i);
			for (String name : expectedBundle.keySet()) {
				JvmClassInfo expectedClass = expectedBundle.get(name);
				JvmClassInfo actualClass = actualBundle.get(name);
				assertNotNull(actualClass, "Missing class '" + name + "' in bundle " + i);
				assertArrayEquals(expectedClass.getBytecode(), actualClass.getBytecode(),
						"Recursive application yielded different bytecode for '" + name + "' in bundle " + i);
			}
		}
	}

	@Test
	void applyRecursivelyCoversVersionedAndEmbeddedBundles() throws IOException {
		String stringSupplierName = StringSupplier.class.getName().replace('.', '/');
		String classWithInnerName = ClassWithInner.class.getName().replace('.', '/');
		String theInnerName = ClassWithInner.TheInner.class.getName().replace('.', '/');
		String dummyEnumName = DummyEnum.class.getName().replace('.', '/');

		Workspace recursiveWorkspace = newRecursiveWorkspace();
		WorkspaceResource root = recursiveWorkspace.getPrimaryResource();
		WorkspaceResource embedded = root.getEmbeddedResources().get(EMBEDDED_NAME);
		assertNotNull(embedded, "Missing embedded resource in test workspace");

		RecursiveMappingResults results = mappingApplierService.inWorkspace(recursiveWorkspace)
				.applyToResourceRecursive(newRecursiveMappings(), root);

		// Targets should follow the same order the batch decompile path walks bundles in:
		// primary bundle, versioned bundles, then embedded resources.
		List<RecursiveMappingResults.BundleTarget> targets = results.getTargets();
		assertEquals(List.of(
				root.getJvmClassBundle(),
				root.getVersionedJvmClassBundles().get(PRIMARY_VERSION),
				embedded.getJvmClassBundle(),
				embedded.getVersionedJvmClassBundles().get(EMBEDDED_VERSION)
		), targets.stream().map(RecursiveMappingResults.BundleTarget::bundle).toList(), "Unexpected bundle order");
		assertFalse(results.isApplied(), "Results should not be applied until requested");

		results.apply();
		assertTrue(results.isApplied());
		assertEquals(4, results.getResults().size(), "Expected one result per non-empty bundle");

		// Renamed classes in the primary bundle, including the inner class which is only renamed
		// because the mappings were enriched with a workspace class look-up.
		assertTrue(results.wasMapped(stringSupplierName), "StringSupplier should have updated");
		assertTrue(results.wasMapped(theInnerName), "Inner class should have updated");
		assertNotNull(root.getJvmClassBundle().get(MAPPED_SUPPLIER), "Missing mapped supplier in primary bundle");
		assertNotNull(root.getJvmClassBundle().get(MAPPED_OUTER), "Missing mapped outer in primary bundle");
		assertNotNull(root.getJvmClassBundle().get(MAPPED_OUTER + "$TheInner"), "Missing mapped inner in primary bundle");
		assertNull(root.getJvmClassBundle().get(stringSupplierName), "Old supplier name should be gone");
		assertNull(root.getJvmClassBundle().get(classWithInnerName), "Old outer name should be gone");

		// The multi-release copy of the supplier should be renamed in its own bundle.
		JvmClassBundle versionedBundle = root.getVersionedJvmClassBundles().get(PRIMARY_VERSION);
		assertNotNull(versionedBundle.get(MAPPED_SUPPLIER), "Missing mapped supplier in versioned bundle");
		assertNull(versionedBundle.get(stringSupplierName), "Old supplier name should be gone from versioned bundle");

		// Classes in the embedded resource, and its own versioned bundle, should be renamed too.
		assertNotNull(embedded.getJvmClassBundle().get(MAPPED_ENUM), "Missing mapped enum in embedded bundle");
		assertNull(embedded.getJvmClassBundle().get(dummyEnumName), "Old enum name should be gone from embedded bundle");
		JvmClassBundle embeddedVersionedBundle = embedded.getVersionedJvmClassBundles().get(EMBEDDED_VERSION);
		assertNotNull(embeddedVersionedBundle.get(MAPPED_ENUM), "Missing mapped enum in embedded versioned bundle");

		// The enum user was not renamed, but should point at the new enum name.
		JvmClassInfo enumPrinter = embedded.getJvmClassBundle()
				.get(DummyEnumPrinter.class.getName().replace('.', '/'));
		assertNotNull(enumPrinter, "Missing enum printer in embedded bundle");
		assertTrue(enumPrinter.getReferencedClasses().contains(MAPPED_ENUM),
				"Enum printer should reference the mapped enum name");
	}

	@Test
	void applyRecursivelyEnrichesMappingsOnce() throws IOException {
		Workspace recursiveWorkspace = newRecursiveWorkspace();
		IntermediateMappings mappings = newRecursiveMappings();

		RecursiveMappingResults results = mappingApplierService.inWorkspace(recursiveWorkspace)
				.applyToResourceRecursive(mappings, recursiveWorkspace.getPrimaryResource());
		results.apply();

		// Enrichment adapts the intermediate mappings once for the whole resource tree,
		// so every bundle-level operation must share that one instance.
		Mappings enriched = results.getMappings();
		assertInstanceOf(MappingsAdapter.class, enriched, "Mappings should have been enriched");
		assertNotSame(mappings, enriched, "Intermediate mappings should have been adapted");
		for (MappingResults bundleResults : results.getResults())
			assertSame(enriched, bundleResults.getMappings(), "Each bundle should re-use the enriched mappings");
	}

	@Test
	void applyRecursivelyDefersDecompileCacheInvalidation() throws IOException {
		DecompilerManager decompilerManager = recaf.get(DecompilerManager.class);
		var decompiler = decompilerManager.getTargetJvmDecompiler();
		assertNotNull(decompiler, "Expected a target JVM decompiler");

		Workspace recursiveWorkspace = newRecursiveWorkspace();
		WorkspaceResource root = recursiveWorkspace.getPrimaryResource();
		WorkspaceResource embedded = root.getEmbeddedResources().get(EMBEDDED_NAME);
		assertNotNull(embedded, "Missing embedded resource in test workspace");

		// 'HelloWorld' is not touched by the mappings, so the class instance holding our cache entry
		// remains in its bundle for the whole operation.
		String helloWorldName = HelloWorld.class.getName().replace('.', '/');
		JvmClassInfo cachedClass = embedded.getJvmClassBundle().get(helloWorldName);
		assertNotNull(cachedClass, "Missing cache marker class in embedded bundle");
		CachedDecompileProperty.set(cachedClass, decompiler, new DecompileResult("// stale source", 0));

		// Record the cache state seen by each bundle-level application.
		List<Boolean> cachedDuringApply = new ArrayList<>();
		MappingListeners listeners = recaf.get(MappingListeners.class);
		MappingApplicationListener listener = new MappingApplicationListener() {
			@Override
			public void onPreApply(@Nonnull Workspace workspace, @Nonnull MappingResults mappingResults) {
				// no-op
			}

			@Override
			public void onPostApply(@Nonnull Workspace workspace, @Nonnull MappingResults mappingResults) {
				cachedDuringApply.add(CachedDecompileProperty.get(cachedClass, decompiler) != null);
			}
		};
		listeners.addMappingApplicationListener(listener);
		try {
			mappingApplierService.inWorkspace(recursiveWorkspace)
					.applyToResourceRecursive(newRecursiveMappings(), root)
					.apply();
		} finally {
			listeners.removeMappingApplicationListener(listener);
		}

		assertEquals(4, cachedDuringApply.size(), "Expected one bundle-level application per bundle");
		assertTrue(cachedDuringApply.stream().allMatch(Boolean::booleanValue),
				"Cached decompilations should not be cleared by intermediate bundle applications");
		assertNull(CachedDecompileProperty.get(cachedClass, decompiler),
				"Cached decompilations should be cleared once the recursive operation completes");
	}

	@Test
	void applyRecursivelyWithoutDeferredInvalidationClearsPerBundle() throws IOException {
		DecompilerManager decompilerManager = recaf.get(DecompilerManager.class);
		var decompiler = decompilerManager.getTargetJvmDecompiler();
		assertNotNull(decompiler, "Expected a target JVM decompiler");

		Workspace recursiveWorkspace = newRecursiveWorkspace();
		WorkspaceResource root = recursiveWorkspace.getPrimaryResource();
		WorkspaceResource embedded = root.getEmbeddedResources().get(EMBEDDED_NAME);
		assertNotNull(embedded, "Missing embedded resource in test workspace");

		String helloWorldName = HelloWorld.class.getName().replace('.', '/');
		JvmClassInfo cachedClass = embedded.getJvmClassBundle().get(helloWorldName);
		assertNotNull(cachedClass, "Missing cache marker class in embedded bundle");
		CachedDecompileProperty.set(cachedClass, decompiler, new DecompileResult("// stale source", 0));

		List<Boolean> cachedDuringApply = new ArrayList<>();
		MappingListeners listeners = recaf.get(MappingListeners.class);
		MappingApplicationListener listener = new MappingApplicationListener() {
			@Override
			public void onPreApply(@Nonnull Workspace workspace, @Nonnull MappingResults mappingResults) {
				// no-op
			}

			@Override
			public void onPostApply(@Nonnull Workspace workspace, @Nonnull MappingResults mappingResults) {
				cachedDuringApply.add(CachedDecompileProperty.get(cachedClass, decompiler) != null);
			}
		};
		listeners.addMappingApplicationListener(listener);
		try {
			RecursiveMappingOptions options = RecursiveMappingOptions.defaults()
					.withDeferredDecompileCacheInvalidation(false);
			mappingApplierService.inWorkspace(recursiveWorkspace)
					.applyToResourceRecursive(newRecursiveMappings(), root, options)
					.apply();
		} finally {
			listeners.removeMappingApplicationListener(listener);
		}

		assertFalse(cachedDuringApply.isEmpty(), "Expected bundle-level applications");
		assertFalse(cachedDuringApply.getFirst(),
				"Without deferral the first bundle application should clear cached decompilations");
		assertNull(CachedDecompileProperty.get(cachedClass, decompiler),
				"Cached decompilations should be cleared");
	}

	@Test
	void applyRecursivelyCanSkipVersionedAndEmbeddedBundles() throws IOException {
		String stringSupplierName = StringSupplier.class.getName().replace('.', '/');
		String dummyEnumName = DummyEnum.class.getName().replace('.', '/');

		Workspace recursiveWorkspace = newRecursiveWorkspace();
		WorkspaceResource root = recursiveWorkspace.getPrimaryResource();
		WorkspaceResource embedded = root.getEmbeddedResources().get(EMBEDDED_NAME);
		assertNotNull(embedded, "Missing embedded resource in test workspace");

		RecursiveMappingOptions options = RecursiveMappingOptions.defaults()
				.withVersionedBundles(false)
				.withEmbeddedResources(false);
		RecursiveMappingResults results = mappingApplierService.inWorkspace(recursiveWorkspace)
				.applyToResourceRecursive(newRecursiveMappings(), root, options);
		assertEquals(1, results.getTargets().size(), "Only the primary bundle should be targeted");
		results.apply();

		assertNotNull(root.getJvmClassBundle().get(MAPPED_SUPPLIER), "Primary bundle should still be mapped");
		assertNotNull(root.getVersionedJvmClassBundles().get(PRIMARY_VERSION).get(stringSupplierName),
				"Versioned bundle should have been skipped");
		assertNotNull(embedded.getJvmClassBundle().get(dummyEnumName),
				"Embedded resource should have been skipped");
	}

	/**
	 * Mirrors how {@code BatchDecompileJarsRunner} applies mappings, one bundle at a time.
	 */
	private void applyPerBundle(@Nonnull Workspace workspace, @Nonnull IntermediateMappings mappings) {
		MappingApplier applier = mappingApplierService.inWorkspace(workspace);
		applyPerBundle(applier, workspace.getPrimaryResource(), mappings);
	}

	private void applyPerBundle(@Nonnull MappingApplier applier,
	                            @Nonnull WorkspaceResource resource,
	                            @Nonnull IntermediateMappings mappings) {
		for (JvmClassBundle bundle : collectOwnBundles(resource)) {
			List<JvmClassInfo> classes = bundle.stream().toList();
			if (classes.isEmpty()) continue;
			applier.applyToClasses(mappings, resource, bundle, classes).apply();
		}
		resource.getEmbeddedResources().values()
				.forEach(embedded -> applyPerBundle(applier, embedded, mappings));
	}

	@Nonnull
	private static List<JvmClassBundle> collectOwnBundles(@Nonnull WorkspaceResource resource) {
		List<JvmClassBundle> bundles = new ArrayList<>();
		bundles.add(resource.getJvmClassBundle());
		bundles.addAll(resource.getVersionedJvmClassBundles().values());
		return bundles;
	}

	@Nonnull
	private static List<JvmClassBundle> collectBundles(@Nonnull WorkspaceResource resource) {
		List<JvmClassBundle> bundles = new ArrayList<>(collectOwnBundles(resource));
		resource.getEmbeddedResources().values()
				.forEach(embedded -> bundles.addAll(collectBundles(embedded)));
		return bundles;
	}

	/**
	 * @return Workspace with a versioned bundle in the primary resource, plus an embedded resource
	 * which itself has a versioned bundle.
	 */
	@Nonnull
	private static Workspace newRecursiveWorkspace() throws IOException {
		BasicVersionedJvmClassBundle versionedBundle = new BasicVersionedJvmClassBundle(PRIMARY_VERSION);
		versionedBundle.initialPut(TestClassUtils.fromRuntimeClass(StringSupplier.class));
		versionedBundle.initialPut(TestClassUtils.fromRuntimeClass(AnonymousLambda.class));
		NavigableMap<Integer, VersionedJvmClassBundle> versionedBundles = new TreeMap<>();
		versionedBundles.put(PRIMARY_VERSION, versionedBundle);

		BasicVersionedJvmClassBundle embeddedVersionedBundle = new BasicVersionedJvmClassBundle(EMBEDDED_VERSION);
		embeddedVersionedBundle.initialPut(TestClassUtils.fromRuntimeClass(DummyEnum.class));
		NavigableMap<Integer, VersionedJvmClassBundle> embeddedVersionedBundles = new TreeMap<>();
		embeddedVersionedBundles.put(EMBEDDED_VERSION, embeddedVersionedBundle);

		WorkspaceFileResource embedded = new WorkspaceFileResourceBuilder()
				.withFileInfo(new StubFileInfo(EMBEDDED_NAME))
				.withJvmClassBundle(TestClassUtils.fromClasses(
						DummyEnum.class,
						DummyEnumPrinter.class,
						HelloWorld.class
				))
				.withVersionedJvmClassBundles(embeddedVersionedBundles)
				.build();

		WorkspaceResource root = new WorkspaceResourceBuilder()
				.withJvmClassBundle(TestClassUtils.fromClasses(
						AnonymousLambda.class,
						StringSupplier.class,
						ClassWithInner.class,
						ClassWithInner.TheInner.class
				))
				.withVersionedJvmClassBundles(versionedBundles)
				.withEmbeddedResources(Map.of(EMBEDDED_NAME, embedded))
				.build();
		return new BasicWorkspace(root);
	}

	@Nonnull
	private static IntermediateMappings newRecursiveMappings() {
		String stringSupplierName = StringSupplier.class.getName().replace('.', '/');
		String classWithInnerName = ClassWithInner.class.getName().replace('.', '/');
		String dummyEnumName = DummyEnum.class.getName().replace('.', '/');
		String dummyEnumPrinterName = DummyEnumPrinter.class.getName().replace('.', '/');

		IntermediateMappings mappings = new IntermediateMappings();
		mappings.addClass(stringSupplierName, MAPPED_SUPPLIER);
		mappings.addClass(classWithInnerName, MAPPED_OUTER);
		mappings.addClass(dummyEnumName, MAPPED_ENUM);
		mappings.addMethod(dummyEnumPrinterName, "()Ljava/lang/String;", "run1", "printValues");
		return mappings;
	}

	private String runMapped(Class<?> cls, String methodName) {
		String className = cls.getName();
		ClassDefiner definer = newDefinerFromWorkspace();
		try {
			Class<?> runner = definer.findClass(className);
			Method main = runner.getDeclaredMethod(methodName);
			try {
				return (String) main.invoke(null);
			} catch (ReflectiveOperationException ex) {
				fail("Failed to execute '" + methodName + "' method", ex);
			}
		} catch (ClassNotFoundException | NoSuchMethodException ex) {
			fail("Class '" + className + "' or '" + methodName + "' method missing", ex);
		}
		throw new IllegalStateException();
	}

	private ClassDefiner newDefinerFromWorkspace() {
		Map<String, byte[]> map = resource.getJvmClassBundle().values().stream()
				.collect(Collectors.toMap(e -> e.getName().replace('/', '.'), JvmClassInfo::getBytecode));
		return new ClassDefiner(map);
	}

	public static class ParameterBase {
		public void render(String pText, int pCount) {
		}
	}

	public static class ParameterChild extends ParameterBase {
		@Override
		public void render(String pText, int pCount) {
			super.render(pText, pCount);
		}
	}
}
