package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.builder.TextFileInfoBuilder;
import software.coley.recaf.services.decompile.cfr.CfrDecompiler;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.HelloWorld;
import software.coley.recaf.test.dummy.StringConsumer;
import software.coley.recaf.test.dummy.StringSupplier;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DefaultBatchDecompileEngine#exportWorkspace(WorkspaceDecompileRequest, BatchDecompileProgressListener)}.
 */
class WorkspaceDecompileExportTest extends TestBase {
	private static final String DUMMY_PACKAGE = "software/coley/recaf/test/dummy/";
	private static final String EXAMPLE_PACKAGE = "com/example/";
	private static final byte[] EXAMPLE_RESOURCE = "{\"example.title\": \"Example\"}".getBytes(StandardCharsets.UTF_8);

	static DefaultBatchDecompileEngine engine;
	static ResourceImporter resourceImporter;

	@TempDir
	Path workDir;

	@BeforeAll
	static void setupEngine() {
		engine = recaf.get(DefaultBatchDecompileEngine.class);
		resourceImporter = recaf.get(ResourceImporter.class);
	}

	@Test
	void directoryExportWritesEveryClassAndResourceOfTheWorkspace() throws Exception {
		Workspace workspace = mixedWorkspace();
		Path output = workDir.resolve("out");
		Path reportPath = workDir.resolve("report.json");

		RecordingProgressListener listener = new RecordingProgressListener();
		BatchDecompileReport report = engine.exportWorkspace(request(workspace, output, BatchOutputFormat.DIRECTORY)
				.reportPath(reportPath)
				.build(), listener);

		assertTrue(report.isClean(), "Unexpected failures: " + report.failures());
		assertEquals(3, report.totalClasses());
		assertEquals(3, report.okClasses());
		assertEquals(0, report.failedClasses());
		assertEquals(1, report.okJars(), "Workspace export is reported as a single unit of work");

		Path helloJava = output.resolve(DUMMY_PACKAGE + "HelloWorld.java");
		assertTrue(Files.isRegularFile(helloJava), "Missing decompiled class output");
		assertTrue(Files.readString(helloJava, StandardCharsets.UTF_8).contains("Hello world"),
				"Decompiled output does not look like the source class");
		assertTrue(Files.isRegularFile(output.resolve(EXAMPLE_PACKAGE + "Alpha.java")));
		assertTrue(Files.isRegularFile(output.resolve(EXAMPLE_PACKAGE + "Beta.java")));

		assertArrayEquals(EXAMPLE_RESOURCE, Files.readAllBytes(output.resolve(EXAMPLE_PACKAGE + "lang.json")));
		assertArrayEquals(EXAMPLE_RESOURCE, Files.readAllBytes(output.resolve("other/lang.json")));
		assertEquals(5, report.outputFileCount(), "Expected three classes and two resources");

		assertTrue(Files.isRegularFile(reportPath), "Report was not written");

		BatchDecompileProgress last = listener.last();
		assertNotNull(last, "Listener never received an event");
		assertEquals(1.0, last.fraction(), 1e-9, "Final progress event was not complete");
		assertEquals(3, last.completedClasses());
	}

	@Test
	void zipExportProducesReadableArchive() throws Exception {
		Workspace workspace = mixedWorkspace();
		Path archive = workDir.resolve("out").resolve("sources.zip");

		BatchDecompileReport report = engine.exportWorkspace(request(workspace, archive, BatchOutputFormat.ZIP).build(),
				BatchDecompileProgressListener.NOOP);
		assertTrue(report.isClean(), "Unexpected failures: " + report.failures());
		assertTrue(Files.isRegularFile(archive), "Archive was not written");

		List<String> names = new ArrayList<>();
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements())
				names.add(entries.nextElement().getName());

			ZipEntry helloEntry = zip.getEntry(DUMMY_PACKAGE + "HelloWorld.java");
			assertNotNull(helloEntry, "Archive is missing the decompiled class");
			try (var in = zip.getInputStream(helloEntry)) {
				assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("Hello world"));
			}

			ZipEntry resourceEntry = zip.getEntry(EXAMPLE_PACKAGE + "lang.json");
			assertNotNull(resourceEntry, "Archive is missing the copied resource");
			try (var in = zip.getInputStream(resourceEntry)) {
				assertArrayEquals(EXAMPLE_RESOURCE, in.readAllBytes());
			}
		}
		assertEquals(5, names.size(), "Unexpected archive contents: " + names);
	}

	@Test
	void packageFilterLimitsClassesAndResources() throws Exception {
		Workspace workspace = mixedWorkspace();
		Path output = workDir.resolve("out");

		BatchDecompileReport report = engine.exportWorkspace(request(workspace, output, BatchOutputFormat.DIRECTORY)
				.packageFilter(EXAMPLE_PACKAGE)
				.build(), BatchDecompileProgressListener.NOOP);

		assertTrue(report.isClean(), "Unexpected failures: " + report.failures());
		assertEquals(2, report.totalClasses(), "Only the filtered package should be planned");
		assertEquals(2, report.okClasses());
		assertEquals(1, report.skippedClasses(), "The class outside the filter should be counted as skipped");

		assertTrue(Files.isRegularFile(output.resolve(EXAMPLE_PACKAGE + "Alpha.java")));
		assertTrue(Files.isRegularFile(output.resolve(EXAMPLE_PACKAGE + "Beta.java")));
		assertFalse(Files.exists(output.resolve(DUMMY_PACKAGE + "HelloWorld.java")),
				"Class outside the filtered package was exported");
		assertTrue(Files.isRegularFile(output.resolve(EXAMPLE_PACKAGE + "lang.json")));
		assertFalse(Files.exists(output.resolve("other/lang.json")),
				"Resource outside the filtered package was exported");
	}

	@Test
	void targetBundleLimitsExportToThatBundle() throws Exception {
		BasicJvmClassBundle bundle = TestClassUtils.fromClasses(StringSupplier.class, StringConsumer.class);
		Workspace workspace = mixedWorkspace();
		Path output = workDir.resolve("out");

		BatchDecompileReport report = engine.exportWorkspace(request(workspace, output, BatchOutputFormat.DIRECTORY)
				.targetBundle(bundle)
				.includeResources(false)
				.build(), BatchDecompileProgressListener.NOOP);

		assertTrue(report.isClean(), "Unexpected failures: " + report.failures());
		assertEquals(2, report.totalClasses());
		assertTrue(Files.isRegularFile(output.resolve(DUMMY_PACKAGE + "StringSupplier.java")));
		assertTrue(Files.isRegularFile(output.resolve(DUMMY_PACKAGE + "StringConsumer.java")));
		assertFalse(Files.exists(output.resolve(DUMMY_PACKAGE + "HelloWorld.java")),
				"Classes outside the target bundle were exported");
		assertFalse(Files.exists(output.resolve(EXAMPLE_PACKAGE + "lang.json")),
				"Resources were exported even though they were disabled");
	}

	@Test
	void embeddedAndMultiReleaseClassesKeepTheirPaths() throws Exception {
		Path input = workDir.resolve("in");
		Path nested = BatchTestJars.writeNestedJar(input, "nested.jar");
		WorkspaceResource resource = resourceImporter.importResource(nested);
		Path output = workDir.resolve("out");

		Workspace workspace = new BasicWorkspace(resource, List.of());
		try {
			BatchDecompileReport report = engine.exportWorkspace(
					request(workspace, output, BatchOutputFormat.DIRECTORY).build(),
					BatchDecompileProgressListener.NOOP);
			assertTrue(report.isClean(), "Unexpected failures: " + report.failures());

			assertTrue(Files.isRegularFile(output.resolve(BatchTestJars.HELLO_WORLD + ".java")));
			assertTrue(Files.isRegularFile(output.resolve("META-INF/versions/17")
					.resolve(BatchTestJars.STRING_CONSUMER + ".java")), "Multi-release class was not exported");
			Path embedded = output.resolve("_embedded/META-INF/jars/inner");
			assertTrue(Files.isRegularFile(embedded.resolve(BatchTestJars.STRING_SUPPLIER + ".java")),
					"Embedded archive class was not exported");
			assertArrayEquals(BatchTestJars.RESOURCE_CONTENT,
					Files.readAllBytes(embedded.resolve(BatchTestJars.RESOURCE_NAME)),
					"Embedded archive resource was not copied");
		} finally {
			workspace.close();
		}
	}

	@Test
	void filterMatchingNothingWritesNoOutput() throws Exception {
		Workspace workspace = mixedWorkspace();
		Path output = workDir.resolve("out");

		BatchDecompileReport report = engine.exportWorkspace(request(workspace, output, BatchOutputFormat.DIRECTORY)
				.packageFilter("not/a/package/")
				.build(), BatchDecompileProgressListener.NOOP);

		assertEquals(0, report.totalClasses());
		assertEquals(0, report.outputFileCount());
		assertEquals(1, report.skippedJars());
		assertTrue(report.isClean(), "Unexpected failures: " + report.failures());
		assertFalse(Files.exists(output), "An empty export should not create the output directory");
	}

	@Test
	void planDescribesTheExportWithoutDecompiling() throws Exception {
		Workspace workspace = mixedWorkspace();
		WorkspaceBatchExport plan = engine.planWorkspace(request(workspace, workDir.resolve("out"),
				BatchOutputFormat.DIRECTORY).outputName("sources").build());

		assertEquals(3, plan.classCount());
		assertEquals(2, plan.resourceCount());
		assertFalse(plan.isEmpty());
		assertEquals(List.of("sources/" + EXAMPLE_PACKAGE + "Alpha.java",
						"sources/" + EXAMPLE_PACKAGE + "Beta.java",
						"sources/" + DUMMY_PACKAGE + "HelloWorld.java"),
				plan.classes().stream().map(ClassExportTask::outputPath).toList(),
				"Classes are expected in deterministic output order, under the requested output name");
	}

	@Nonnull
	private static Workspace mixedWorkspace() throws Exception {
		BasicJvmClassBundle classes = TestClassUtils.fromClasses(HelloWorld.class);
		classes.initialPut(TestClassUtils.createEmptyClass(EXAMPLE_PACKAGE + "Alpha"));
		classes.initialPut(TestClassUtils.createEmptyClass(EXAMPLE_PACKAGE + "Beta"));
		return TestClassUtils.fromBundles(classes, TestClassUtils.fromFiles(
				textFile(EXAMPLE_PACKAGE + "lang.json"),
				textFile("other/lang.json")));
	}

	@Nonnull
	private static FileInfo textFile(@Nonnull String name) {
		return new TextFileInfoBuilder()
				.withName(name)
				.withRawContent(EXAMPLE_RESOURCE)
				.build();
	}

	@Nonnull
	private static WorkspaceDecompileRequest.Builder request(@Nonnull Workspace workspace, @Nonnull Path output,
	                                                         @Nonnull BatchOutputFormat format) {
		return WorkspaceDecompileRequest.builder(workspace, output)
				.outputFormat(format)
				.decompilerName(CfrDecompiler.NAME)
				.timeoutPerClass(Duration.ofSeconds(30))
				.workers(2, 2);
	}
}
