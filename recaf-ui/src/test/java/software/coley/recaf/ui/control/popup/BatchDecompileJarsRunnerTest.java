package software.coley.recaf.ui.control.popup;

import org.junit.jupiter.api.Test;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.FileInfoBuilder;
import software.coley.recaf.info.builder.TextFileInfoBuilder;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.MappingApplier;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.services.mapping.format.InvalidMappingException;
import software.coley.recaf.services.mapping.format.MappingFileFormat;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.test.dummy.AccessibleFields;
import software.coley.recaf.test.dummy.AccessibleMethods;
import software.coley.recaf.test.dummy.HelloWorld;
import software.coley.recaf.test.dummy.StringConsumer;
import software.coley.recaf.ui.pane.editing.jvm.DecompilerPaneConfig;
import software.coley.recaf.util.IOUtil;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.bundle.BasicVersionedJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResourceBuilder;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static software.coley.recaf.test.TestClassUtils.createEmptyClass;
import static software.coley.recaf.test.TestClassUtils.fromClasses;
import static software.coley.recaf.test.TestClassUtils.fromFiles;
import static software.coley.recaf.test.TestClassUtils.fromRuntimeClass;

public class BatchDecompileJarsRunnerTest {
	private static final String DECOMPILER_THREAD_NAME = "test-decompiler-worker";

	@Test
	void run_overwritesExistingOutputAndExportsResourcesAndEmbeddedContent() throws Exception {
		Path jarDir = Files.createTempDirectory("recaf-batch-in");
		Path outputRoot = Files.createTempDirectory("recaf-batch-out");
		Path mappingFile = Files.createTempFile("recaf-batch", ".txt");
		try {
			Path jarPath = jarDir.resolve("sample.jar");
			Files.write(jarPath, new byte[]{1});
			Path stale = outputRoot.resolve("sample").resolve("stale.txt");
			Files.createDirectories(stale.getParent());
			Files.writeString(stale, "stale", StandardCharsets.UTF_8);

			WorkspaceResource resource = createWorkspaceResource(false);
			BatchDecompileJarsRunner runner = newRunner(resource, cls -> new DecompileResult("// decompiled " + cls.getName(), 0));
			RecordingCallbacks callbacks = new RecordingCallbacks();

			runner.run(jarDir, mappingFile, outputRoot, mockMappingFileFormat(), mock(JvmDecompiler.class), callbacks);

			assertFalse(Files.exists(stale));
			assertEquals(1, callbacks.ok);
			assertEquals(0, callbacks.failed);
			assertEquals(0, callbacks.skipped);

			assertArrayEquals("root-config".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(outputRoot.resolve("sample/config.yml")));
			assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(outputRoot.resolve("sample/META-INF/jars/inner.jar")));
			assertArrayEquals("inner-config".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(outputRoot.resolve("sample/_embedded/META-INF/jars/inner/inner.yml")));

			assertTrue(Files.exists(outputRoot.resolve("sample/software/coley/recaf/test/dummy/HelloWorld.java")));
			assertTrue(Files.exists(outputRoot.resolve("sample/_embedded/META-INF/jars/inner/software/coley/recaf/test/dummy/StringConsumer.java")));
			assertTrue(Files.exists(outputRoot.resolve("sample/META-INF/versions/11/software/coley/recaf/test/dummy/AccessibleFields.java")));
		} finally {
			cleanup(jarDir, outputRoot, mappingFile);
		}
	}

	@Test
	void run_writesFailureSourceAndMarksJarFailed() throws Exception {
		Path jarDir = Files.createTempDirectory("recaf-batch-in");
		Path outputRoot = Files.createTempDirectory("recaf-batch-out");
		Path mappingFile = Files.createTempFile("recaf-batch", ".txt");
		try {
			Path jarPath = jarDir.resolve("sample.jar");
			Files.write(jarPath, new byte[]{1});

			String failedClassName = fromRuntimeClass(AccessibleMethods.class).getName();
			WorkspaceResource resource = createWorkspaceResource(true);
			BatchDecompileJarsRunner runner = newRunner(resource, cls -> {
				if (cls.getName().equals(failedClassName)) {
					return new DecompileResult(new IllegalStateException("boom"), 0);
				}
				return new DecompileResult("// decompiled " + cls.getName(), 0);
			});
			RecordingCallbacks callbacks = new RecordingCallbacks();

			runner.run(jarDir, mappingFile, outputRoot, mockMappingFileFormat(), mock(JvmDecompiler.class), callbacks);

			Path failedJava = outputRoot.resolve("sample/software/coley/recaf/test/dummy/AccessibleMethods.java");
			assertTrue(Files.exists(failedJava));
			assertTrue(Files.readString(failedJava, StandardCharsets.UTF_8).contains("boom"));
			assertEquals(0, callbacks.ok);
			assertEquals(1, callbacks.failed);
		} finally {
			cleanup(jarDir, outputRoot, mappingFile);
		}
	}

	@Test
	void run_writesOffDecompilerThreadsAndThrottlesProgress() throws Exception {
		Path jarDir = Files.createTempDirectory("recaf-batch-in");
		Path outputRoot = Files.createTempDirectory("recaf-batch-out");
		Path mappingFile = Files.createTempFile("recaf-batch", ".txt");
		ExecutorService decompilerPool = Executors.newFixedThreadPool(2, runnable -> {
			Thread thread = new Thread(runnable, DECOMPILER_THREAD_NAME);
			thread.setDaemon(true);
			return thread;
		});
		try {
			Files.write(jarDir.resolve("sample.jar"), new byte[]{1});

			int classCount = 200;
			BasicJvmClassBundle bundle = new BasicJvmClassBundle();
			for (int i = 0; i < classCount; i++)
				bundle.initialPut(createEmptyClass("com/example/Generated" + i));
			WorkspaceResource resource = new WorkspaceResourceBuilder(bundle, fromFiles()).build();

			BatchDecompileJarsRunner runner = newRunner(resource,
					cls -> new DecompileResult("// decompiled " + cls.getName(), 0), decompilerPool);
			RecordingCallbacks callbacks = new RecordingCallbacks();

			runner.run(jarDir, mappingFile, outputRoot, mockMappingFileFormat(), mock(JvmDecompiler.class), callbacks);

			// Every class should still be written out.
			for (int i = 0; i < classCount; i++)
				assertTrue(Files.exists(outputRoot.resolve("sample/com/example/Generated" + i + ".java")),
						"Missing output for generated class " + i);

			// Writes and their progress notifications must not run on the decompiler's own threads.
			assertFalse(callbacks.progressThreads.contains(DECOMPILER_THREAD_NAME),
					"Decompiler worker threads were used for output: " + callbacks.progressThreads);

			// The old implementation fired two FX updates per class. Progress is now rate limited, plus one final
			// update per jar emitted by the runner itself.
			assertTrue(callbacks.progressCount.get() < classCount / 2,
					"Progress was not throttled, got " + callbacks.progressCount.get() + " updates for " + classCount + " classes");
			assertEquals(callbacks.currentClassCount.get() + 1, callbacks.progressCount.get(),
					"Class name and progress updates should be emitted together");
			assertTrue(callbacks.currentClassCount.get() >= 2,
					"Throttling suppressed all intermediate progress updates");

			assertEquals(1, callbacks.ok);
			assertEquals(0, callbacks.failed);
		} finally {
			decompilerPool.shutdownNow();
			cleanup(jarDir, outputRoot, mappingFile);
		}
	}

	private static BatchDecompileJarsRunner newRunner(WorkspaceResource resource,
	                                                 Function<JvmClassInfo, DecompileResult> results) throws IOException {
		return newRunner(resource, results, null);
	}

	private static BatchDecompileJarsRunner newRunner(WorkspaceResource resource,
	                                                 Function<JvmClassInfo, DecompileResult> results,
	                                                 ExecutorService decompilerPool) throws IOException {
		DecompilerManager decompilerManager = mock(DecompilerManager.class);
		when(decompilerManager.decompile(any(JvmDecompiler.class), any(Workspace.class), any(JvmClassInfo.class))).thenAnswer(invocation -> {
			JvmClassInfo classInfo = invocation.getArgument(2);
			if (decompilerPool == null)
				return CompletableFuture.completedFuture(results.apply(classInfo));
			return CompletableFuture.supplyAsync(() -> results.apply(classInfo), decompilerPool);
		});

		ResourceImporter resourceImporter = mock(ResourceImporter.class);
		when(resourceImporter.importResource(any(Path.class))).thenReturn(resource);

		MappingResults mappingResults = mock(MappingResults.class);
		doNothing().when(mappingResults).apply();
		MappingApplier mappingApplier = mock(MappingApplier.class);
		when(mappingApplier.applyToClasses(any(), any(), any(), any())).thenReturn(mappingResults);
		MappingApplierService mappingApplierService = mock(MappingApplierService.class);
		when(mappingApplierService.inWorkspace(any())).thenReturn(mappingApplier);

		DecompilerPaneConfig config = new DecompilerPaneConfig();
		config.getTimeoutSeconds().setValue(1);
		return new BatchDecompileJarsRunner(decompilerManager, config, mappingApplierService, resourceImporter);
	}

	private static WorkspaceResource createWorkspaceResource(boolean includeFailureClass) throws IOException {
		var primaryBundle = fromClasses(fromRuntimeClass(HelloWorld.class));
		if (includeFailureClass) {
			primaryBundle.initialPut(fromRuntimeClass(AccessibleMethods.class));
		}

		BasicVersionedJvmClassBundle versionedBundle = new BasicVersionedJvmClassBundle(11);
		versionedBundle.initialPut(fromRuntimeClass(AccessibleFields.class));
		TreeMap<Integer, software.coley.recaf.workspace.model.bundle.VersionedJvmClassBundle> versionedBundles = new TreeMap<>();
		versionedBundles.put(versionedBundle.version(), versionedBundle);

		FileInfo embeddedJar = binaryFile("META-INF/jars/inner.jar", new byte[]{1, 2, 3});
		WorkspaceFileResource embedded = new WorkspaceFileResourceBuilder(fromClasses(fromRuntimeClass(StringConsumer.class)), fromFiles(textFile("inner.yml", "inner-config")))
				.withFileInfo(embeddedJar)
				.build();

		return new WorkspaceResourceBuilder(primaryBundle, fromFiles(textFile("config.yml", "root-config"), embeddedJar))
				.withVersionedJvmClassBundles(new TreeMap<>(versionedBundles))
				.withEmbeddedResources(Map.of("META-INF/jars/inner.jar", embedded))
				.build();
	}

	private static MappingFileFormat mockMappingFileFormat() {
		return new MappingFileFormat() {
			@Override
			public String implementationName() {
				return "test";
			}

			@Override
			public IntermediateMappings parse(String mappingsText) throws InvalidMappingException {
				return new IntermediateMappings();
			}

			@Override
			public boolean doesSupportFieldTypeDifferentiation() {
				return true;
			}

			@Override
			public boolean doesSupportVariableTypeDifferentiation() {
				return true;
			}
		};
	}

	private static FileInfo textFile(String name, String text) {
		return new TextFileInfoBuilder().withName(name).withRawContent(text.getBytes(StandardCharsets.UTF_8)).build();
	}

	private static FileInfo binaryFile(String name, byte[] bytes) {
		return new FileInfoBuilder<>().withName(name).withRawContent(bytes).build();
	}

	private static void cleanup(Path... paths) {
		for (Path path : paths) {
			IOUtil.deleteQuietly(path);
		}
	}

	private static final class RecordingCallbacks implements BatchDecompileJarsRunner.Callbacks {
		private final Set<String> progressThreads = ConcurrentHashMap.newKeySet();
		private final AtomicInteger progressCount = new AtomicInteger();
		private final AtomicInteger currentClassCount = new AtomicInteger();
		private volatile int ok;
		private volatile int skipped;
		private volatile int failed;

		@Override
		public void onNoJarsFound() {
		}

		@Override
		public void onPreparingJar(int jarNumber, int totalJars, String jarName) {
		}

		@Override
		public void onCurrentClass(String className) {
			currentClassCount.incrementAndGet();
			progressThreads.add(Thread.currentThread().getName());
		}

		@Override
		public void onProgress(int jarNumber, int totalJars, double jarFraction, int ok, int skipped, int failed) {
			progressCount.incrementAndGet();
			progressThreads.add(Thread.currentThread().getName());
		}

		@Override
		public void onComplete(int ok, int skipped, int failed) {
			this.ok = ok;
			this.skipped = skipped;
			this.failed = failed;
		}
	}
}

