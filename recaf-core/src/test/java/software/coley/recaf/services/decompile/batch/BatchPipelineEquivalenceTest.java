package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.recaf.services.decompile.cfr.CfrDecompiler;
import software.coley.recaf.services.mapping.format.SimpleMappings;
import software.coley.recaf.test.TestBase;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the pipelined engine to the sequential path it replaced.
 * <p>
 * Overlapping the import of one archive with the decompilation of the previous one, and compressing
 * archive entries away from the writer thread, are both allowed to change <i>when</i> work happens.
 * Neither is allowed to change what lands in the output, so both runs must produce the same entry names,
 * in the same order, with the same bytes.
 */
class BatchPipelineEquivalenceTest extends TestBase {
	static DefaultBatchDecompileEngine engine;

	@TempDir
	Path workDir;

	@BeforeAll
	static void setupEngine() {
		engine = recaf.get(DefaultBatchDecompileEngine.class);
	}

	@AfterEach
	void restorePipeline() {
		engine.setPipelinedImports(true);
	}

	@Test
	void pipelinedRunMatchesSequentialRunEntryForEntry() throws Exception {
		Path input = manyArchives();

		Archive legacy = runToArchive(input, "legacy.zip", false, null);
		Archive pipelined = runToArchive(input, "pipelined.zip", true, null);

		assertFalse(legacy.entries().isEmpty(), "The run produced no output to compare");
		assertEquals(legacy.names(), pipelined.names(), "Pipelining changed the archive entry order");
		legacy.entries().forEach((name, content) -> assertArrayEquals(content, pipelined.entries().get(name),
				"Pipelining changed the content of " + name));

		assertReportsMatch(legacy, pipelined);
	}

	@Test
	void pipelinedRunMatchesSequentialRunWithMappingsApplied() throws Exception {
		Path input = manyArchives();
		Path mappingFile = workDir.resolve("mappings.txt");
		Files.writeString(mappingFile, BatchTestJars.HELLO_WORLD + " com/example/Renamed\n");

		Archive legacy = runToArchive(input, "legacy-mapped.zip", false, mappingFile);
		Archive pipelined = runToArchive(input, "pipelined-mapped.zip", true, mappingFile);

		assertTrue(legacy.names().stream().anyMatch(name -> name.endsWith("com/example/Renamed.java")),
				"Mappings were not applied on the sequential path");
		assertEquals(legacy.names(), pipelined.names(),
				"Applying mappings off the driving thread changed the archive entry order");
		legacy.entries().forEach((name, content) -> assertArrayEquals(content, pipelined.entries().get(name),
				"Applying mappings off the driving thread changed the content of " + name));
		assertReportsMatch(legacy, pipelined);
	}

	@Test
	void archiveAndDirectoryOutputHoldTheSameFiles() throws Exception {
		Path input = manyArchives();

		Archive archive = runToArchive(input, "out.zip", true, null);
		Path directory = workDir.resolve("out-dir");
		engine.setPipelinedImports(true);
		engine.run(request(input, directory).outputFormat(BatchOutputFormat.DIRECTORY).build(),
				BatchDecompileProgressListener.NOOP);

		Map<String, byte[]> onDisk = new LinkedHashMap<>();
		try (var stream = Files.walk(directory)) {
			for (Path path : stream.filter(Files::isRegularFile).sorted().toList())
				onDisk.put(directory.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
		}

		assertEquals(archive.names().stream().sorted().toList(), onDisk.keySet().stream().sorted().toList(),
				"The archive and directory sinks disagree on what the run produced");
		onDisk.forEach((name, content) -> assertArrayEquals(content, archive.entries().get(name),
				"The archive and directory sinks disagree on the content of " + name));
	}

	private static void assertReportsMatch(@Nonnull Archive legacy, @Nonnull Archive pipelined) {
		BatchDecompileReport a = legacy.report();
		BatchDecompileReport b = pipelined.report();
		assertEquals(a.totalJars(), b.totalJars());
		assertEquals(a.okJars(), b.okJars());
		assertEquals(a.failedJars(), b.failedJars());
		assertEquals(a.skippedJars(), b.skippedJars());
		assertEquals(a.totalClasses(), b.totalClasses());
		assertEquals(a.okClasses(), b.okClasses());
		assertEquals(a.failedClasses(), b.failedClasses());
		assertEquals(a.skippedClasses(), b.skippedClasses());
		assertEquals(a.outputFileCount(), b.outputFileCount());
		assertEquals(a.resourceSha256(), b.resourceSha256());
	}

	@Nonnull
	private Path manyArchives() throws IOException {
		// Several archives, so the pipeline actually has a previous archive to overlap with.
		Path input = workDir.resolve("in");
		BatchTestJars.writeSampleJar(input, "alpha.jar");
		BatchTestJars.writeNestedJar(input, "beta.jar");
		BatchTestJars.writeSampleJar(input, "gamma.zip");
		BatchTestJars.writeNestedJar(input, "delta.zip");
		return input;
	}

	@Nonnull
	private Archive runToArchive(@Nonnull Path input, @Nonnull String archiveName,
	                             boolean pipelined, Path mappingFile) throws Exception {
		Path archive = workDir.resolve(archiveName);
		Path reportPath = workDir.resolve(archiveName + ".report.json");
		engine.setPipelinedImports(pipelined);
		BatchDecompileReport report = engine.run(request(input, archive)
				.outputFormat(BatchOutputFormat.ZIP)
				.mappings(mappingFile, mappingFile == null ? null : SimpleMappings.NAME)
				.reportPath(reportPath)
				.build(), BatchDecompileProgressListener.NOOP);
		assertTrue(report.isClean(), "Unexpected failures: " + report.failures());
		return new Archive(report, readEntries(archive));
	}

	@Nonnull
	private static BatchDecompileRequest.Builder request(@Nonnull Path input, @Nonnull Path output) {
		return BatchDecompileRequest.builder(input, output)
				.decompilerName(CfrDecompiler.NAME)
				.timeoutPerClass(Duration.ofSeconds(60))
				.workers(4, 2);
	}

	@Nonnull
	private static Map<String, byte[]> readEntries(@Nonnull Path archive) throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			Enumeration<? extends ZipEntry> enumeration = zip.entries();
			while (enumeration.hasMoreElements()) {
				ZipEntry entry = enumeration.nextElement();
				try (InputStream in = zip.getInputStream(entry)) {
					entries.put(entry.getName(), in.readAllBytes());
				}
			}
		}
		return entries;
	}

	/**
	 * One finished run, as the comparison sees it.
	 *
	 * @param report
	 * 		Report the run produced.
	 * @param entries
	 * 		Archive contents in entry order.
	 */
	private record Archive(@Nonnull BatchDecompileReport report, @Nonnull Map<String, byte[]> entries) {
		@Nonnull
		private List<String> names() {
			return List.copyOf(entries.keySet());
		}
	}
}
