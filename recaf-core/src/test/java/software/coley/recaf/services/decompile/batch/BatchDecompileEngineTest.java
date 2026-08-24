package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.recaf.services.decompile.cfr.CfrDecompiler;
import software.coley.recaf.services.mapping.format.SimpleMappings;
import software.coley.recaf.test.TestBase;

import java.io.IOException;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DefaultBatchDecompileEngine}.
 */
class BatchDecompileEngineTest extends TestBase {
	static DefaultBatchDecompileEngine engine;

	@TempDir
	Path workDir;

	@BeforeAll
	static void setupEngine() {
		engine = recaf.get(DefaultBatchDecompileEngine.class);
	}

	@Test
	void directoryOutputWritesClassesAndResources() throws Exception {
		Path input = workDir.resolve("in");
		BatchTestJars.writeSampleJar(input, "sample.jar");
		Path output = workDir.resolve("out");
		Path reportPath = workDir.resolve("report.json");

		RecordingProgressListener listener = new RecordingProgressListener();
		BatchDecompileReport report = engine.run(request(input, output, BatchOutputFormat.DIRECTORY)
				.reportPath(reportPath)
				.build(), listener);

		Path helloJava = output.resolve("sample").resolve(BatchTestJars.HELLO_WORLD + ".java");
		assertTrue(Files.isRegularFile(helloJava), "Missing decompiled class output");
		assertTrue(Files.readString(helloJava, StandardCharsets.UTF_8).contains("Hello world"),
				"Decompiled output does not look like the source class");

		Path resource = output.resolve("sample").resolve(BatchTestJars.RESOURCE_NAME);
		assertArrayEquals(BatchTestJars.RESOURCE_CONTENT, Files.readAllBytes(resource),
				"Resource bytes were not copied verbatim");

		assertEquals(1, report.totalJars());
		assertEquals(1, report.okJars());
		assertEquals(0, report.failedJars());
		assertEquals(0, report.skippedJars());
		assertEquals(3, report.totalClasses());
		assertEquals(3, report.okClasses());
		assertEquals(0, report.failedClasses());
		assertEquals(4, report.outputFileCount());
		assertTrue(report.isClean(), "Unexpected failures: " + report.failures());
		assertTrue(report.resourceSha256().containsKey("sample/" + BatchTestJars.RESOURCE_NAME));

		assertTrue(Files.isRegularFile(reportPath), "Report was not written");
		String reportJson = Files.readString(reportPath, StandardCharsets.UTF_8);
		assertTrue(reportJson.contains("\"okClasses\": 3"), "Report JSON missing class counts");

		BatchDecompileProgress last = listener.last();
		assertNotNull(last, "Listener never received an event");
		assertEquals(1.0, last.fraction(), 1e-9, "Final progress event was not complete");
		assertEquals(1, last.completedJars());
		assertEquals(3, last.completedClasses());
	}

	@Test
	void zipOutputProducesReadableArchive() throws Exception {
		Path input = workDir.resolve("in");
		BatchTestJars.writeSampleJar(input, "sample.jar");
		Path archive = workDir.resolve("out").resolve("sources.zip");

		BatchDecompileReport report = engine.run(request(input, archive, BatchOutputFormat.ZIP).build(),
				BatchDecompileProgressListener.NOOP);
		assertTrue(report.isClean(), "Unexpected failures: " + report.failures());
		assertTrue(Files.isRegularFile(archive), "Archive was not written");

		List<String> names = new ArrayList<>();
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements())
				names.add(entries.nextElement().getName());

			ZipEntry helloEntry = zip.getEntry("sample/" + BatchTestJars.HELLO_WORLD + ".java");
			assertNotNull(helloEntry, "Archive is missing the decompiled class");
			try (var in = zip.getInputStream(helloEntry)) {
				assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("Hello world"));
			}

			ZipEntry resourceEntry = zip.getEntry("sample/" + BatchTestJars.RESOURCE_NAME);
			assertNotNull(resourceEntry, "Archive is missing the copied resource");
			try (var in = zip.getInputStream(resourceEntry)) {
				assertArrayEquals(BatchTestJars.RESOURCE_CONTENT, in.readAllBytes());
			}
		}

		assertEquals(4, names.size(), "Unexpected archive contents: " + names);
		assertEquals("sample/" + BatchTestJars.RESOURCE_NAME, names.getFirst(),
				"Resources are expected before classes, in plan order");
		List<String> classNames = names.subList(1, names.size());
		assertEquals(classNames.stream().sorted().toList(), classNames,
				"Class entries were not written in deterministic plan order");
	}

	@Test
	void embeddedAndMultiReleaseClassesKeepTheirPaths() throws Exception {
		Path input = workDir.resolve("in");
		BatchTestJars.writeNestedJar(input, "nested.jar");
		Path output = workDir.resolve("out");

		BatchDecompileReport report = engine.run(request(input, output, BatchOutputFormat.DIRECTORY).build(),
				BatchDecompileProgressListener.NOOP);
		assertTrue(report.isClean(), "Unexpected failures: " + report.failures());

		Path base = output.resolve("nested");
		assertTrue(Files.isRegularFile(base.resolve(BatchTestJars.HELLO_WORLD + ".java")));
		assertTrue(Files.isRegularFile(base.resolve("META-INF/versions/17")
				.resolve(BatchTestJars.STRING_CONSUMER + ".java")), "Multi-release class was not exported");
		Path embedded = base.resolve("_embedded/META-INF/jars/inner");
		assertTrue(Files.isRegularFile(embedded.resolve(BatchTestJars.STRING_SUPPLIER + ".java")),
				"Embedded archive class was not exported");
		assertArrayEquals(BatchTestJars.RESOURCE_CONTENT,
				Files.readAllBytes(embedded.resolve(BatchTestJars.RESOURCE_NAME)),
				"Embedded archive resource was not copied");
	}

	@Test
	void multipleJarsEachGetTheirOwnOutputRoot() throws Exception {
		Path input = workDir.resolve("in");
		BatchTestJars.writeSampleJar(input, "alpha.jar");
		BatchTestJars.writeSampleJar(input, "beta.zip");
		Files.writeString(input.resolve("ignored.txt"), "not an archive");
		Path output = workDir.resolve("out");

		RecordingProgressListener listener = new RecordingProgressListener();
		BatchDecompileReport report = engine.run(request(input, output, BatchOutputFormat.DIRECTORY).build(), listener);

		assertEquals(2, report.totalJars());
		assertEquals(2, report.okJars());
		assertEquals(6, report.okClasses());
		assertTrue(Files.isRegularFile(output.resolve("alpha").resolve(BatchTestJars.HELLO_WORLD + ".java")));
		assertTrue(Files.isRegularFile(output.resolve("beta").resolve(BatchTestJars.HELLO_WORLD + ".java")));

		List<String> seenJars = listener.events().stream()
				.map(BatchDecompileProgress::currentJar)
				.filter(java.util.Objects::nonNull)
				.distinct()
				.toList();
		assertEquals(List.of("alpha.jar", "beta.zip"), seenJars, "JARs were not reported in scan order");
	}

	@Test
	void mappingsAreAppliedBeforeExport() throws Exception {
		Path input = workDir.resolve("in");
		BatchTestJars.writeSampleJar(input, "sample.jar");
		Path mappingFile = workDir.resolve("mappings.txt");
		Files.writeString(mappingFile, BatchTestJars.HELLO_WORLD + " com/example/Renamed\n");
		Path output = workDir.resolve("out");

		BatchDecompileReport report = engine.run(request(input, output, BatchOutputFormat.DIRECTORY)
				.mappings(mappingFile, SimpleMappings.NAME)
				.build(), BatchDecompileProgressListener.NOOP);
		assertTrue(report.isClean(), "Unexpected failures: " + report.failures());

		assertTrue(Files.isRegularFile(output.resolve("sample/com/example/Renamed.java")),
				"Mapped class was not written under its new name");
		assertFalse(Files.exists(output.resolve("sample").resolve(BatchTestJars.HELLO_WORLD + ".java")),
				"Mapped class was also written under its old name");
	}

	@Test
	void noMappingFileYieldsTheEmptyMappingPlan() throws Exception {
		Path input = workDir.resolve("in");
		BatchTestJars.writeSampleJar(input, "sample.jar");
		BatchDecompileRequest request = request(input, workDir.resolve("out"), BatchOutputFormat.DIRECTORY).build();

		BatchMappingPlan mappingPlan = engine.parseMappings(request);
		assertTrue(mappingPlan.isEmpty());
		assertNull(mappingPlan.mappings());
		assertNull(mappingPlan.source());
		assertSameMappingPlan(mappingPlan);

		BatchDecompilePlan plan = engine.plan(request);
		assertEquals(1, plan.jarCount());
		assertTrue(plan.mappingPlan().isEmpty());
		assertEquals("sample", plan.jars().getFirst().outputName());
	}

	@Test
	void mappingFileWithoutFormatIsRejected() throws Exception {
		Path input = workDir.resolve("in");
		BatchTestJars.writeSampleJar(input, "sample.jar");
		Path mappingFile = workDir.resolve("mappings.txt");
		Files.writeString(mappingFile, BatchTestJars.HELLO_WORLD + " com/example/Renamed\n");

		BatchDecompileRequest missingFormat = request(input, workDir.resolve("out"), BatchOutputFormat.DIRECTORY)
				.mappings(mappingFile, null).build();
		assertThrows(BatchDecompileException.class, () -> engine.parseMappings(missingFormat));

		BatchDecompileRequest unknownFormat = request(input, workDir.resolve("out"), BatchOutputFormat.DIRECTORY)
				.mappings(mappingFile, "Not-A-Real-Format").build();
		assertThrows(BatchDecompileException.class, () -> engine.parseMappings(unknownFormat));
	}

	@Test
	void invalidInputsFailFast() throws IOException {
		Path missing = workDir.resolve("does-not-exist");
		assertThrows(BatchDecompileException.class, () -> engine.run(
				request(missing, workDir.resolve("out"), BatchOutputFormat.DIRECTORY).build(),
				BatchDecompileProgressListener.NOOP));

		Path input = workDir.resolve("in");
		Files.createDirectories(input);
		assertThrows(BatchDecompileException.class, () -> engine.run(
				request(input, workDir.resolve("out"), BatchOutputFormat.DIRECTORY)
						.decompilerName("Not-A-Real-Decompiler").build(),
				BatchDecompileProgressListener.NOOP));
	}

	@Test
	void emptyInputDirectoryCompletesWithAnEmptyReport() throws Exception {
		Path input = workDir.resolve("in");
		Files.createDirectories(input);

		RecordingProgressListener listener = new RecordingProgressListener();
		BatchDecompileReport report = engine.run(request(input, workDir.resolve("out"), BatchOutputFormat.DIRECTORY)
				.build(), listener);
		assertEquals(0, report.totalJars());
		assertEquals(0, report.totalClasses());
		assertTrue(report.isClean());
		assertNotNull(listener.last());
		assertEquals(1.0, listener.last().fraction(), 1e-9);
	}

	private static void assertSameMappingPlan(@Nonnull BatchMappingPlan plan) {
		assertEquals(BatchMappingPlan.none(), plan);
	}

	@Nonnull
	private static BatchDecompileRequest.Builder request(@Nonnull Path input, @Nonnull Path output,
	                                                     @Nonnull BatchOutputFormat format) {
		return BatchDecompileRequest.builder(input, output)
				.outputFormat(format)
				.decompilerName(CfrDecompiler.NAME)
				.timeoutPerClass(Duration.ofSeconds(30))
				.workers(2, 2);
	}
}
