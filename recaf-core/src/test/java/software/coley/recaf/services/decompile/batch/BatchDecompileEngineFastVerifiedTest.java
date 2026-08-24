package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.decompile.cfr.CfrDecompiler;
import software.coley.recaf.services.decompile.vineflower.VineflowerDecompiler;
import software.coley.recaf.test.TestBase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@link BatchAccuracyMode#FAST_VERIFIED} / {@link BatchAccuracyMode#FAST_UNSAFE} paths of
 * {@link DefaultBatchDecompileEngine}, backed by {@link VineflowerFastVerifiedAdapter}.
 * <p/>
 * The {@link BatchAccuracyMode#ACCURATE} behavior itself is covered by {@link BatchDecompileEngineTest};
 * here we only pin down when the chunked Vineflower path may be taken and what it produces.
 */
class BatchDecompileEngineFastVerifiedTest extends TestBase {
	private static final List<String> OUTER_CLASSES = List.of(
			BatchTestJars.HELLO_WORLD, BatchTestJars.STRING_SUPPLIER, BatchTestJars.STRING_CONSUMER);
	static DefaultBatchDecompileEngine engine;
	static VineflowerFastVerifiedAdapter adapter;
	static DecompilerManager decompilerManager;

	@TempDir
	Path workDir;

	@BeforeAll
	static void setup() {
		engine = recaf.get(DefaultBatchDecompileEngine.class);
		adapter = recaf.get(VineflowerFastVerifiedAdapter.class);
		decompilerManager = recaf.get(DecompilerManager.class);
	}

	@Test
	void chunkPathIsOnlyApplicableToFastModesWithVineflower() {
		JvmDecompiler vineflower = decompilerManager.getJvmDecompiler(VineflowerDecompiler.NAME);
		assertNotNull(vineflower, "Vineflower decompiler was never registered with manager");
		JvmDecompiler cfr = decompilerManager.getJvmDecompiler(CfrDecompiler.NAME);
		assertNotNull(cfr, "CFR decompiler was never registered with manager");

		assertFalse(adapter.isApplicable(BatchAccuracyMode.ACCURATE, vineflower),
				"ACCURATE must stay on the single-class path even with Vineflower");
		assertTrue(adapter.isApplicable(BatchAccuracyMode.FAST_VERIFIED, vineflower));
		assertTrue(adapter.isApplicable(BatchAccuracyMode.FAST_UNSAFE, vineflower));
		assertFalse(adapter.isApplicable(BatchAccuracyMode.FAST_VERIFIED, cfr),
				"Only Vineflower has a fast adapter");
		assertFalse(adapter.isApplicable(BatchAccuracyMode.FAST_UNSAFE, cfr),
				"Only Vineflower has a fast adapter");
	}

	@Test
	void fastVerifiedWithVineflowerDecompilesEveryOuterClass() throws Exception {
		Path input = workDir.resolve("in");
		BatchTestJars.writeSampleJar(input, "sample.jar");
		Path output = workDir.resolve("out");

		BatchDecompileReport report = engine.run(request(input, output, BatchAccuracyMode.FAST_VERIFIED).build(),
				BatchDecompileProgressListener.NOOP);
		assertTrue(report.isClean(), "Unexpected failures: " + report.failures());
		assertEquals(OUTER_CLASSES.size(), report.totalClasses());
		assertEquals(OUTER_CLASSES.size(), report.okClasses());
		assertEquals(0, report.failedClasses());

		for (String className : OUTER_CLASSES) {
			Path javaFile = output.resolve("sample").resolve(className + ".java");
			assertTrue(Files.isRegularFile(javaFile), "Missing chunked output for " + className);
			String text = Files.readString(javaFile, StandardCharsets.UTF_8);
			assertFalse(text.isBlank(), "Blank chunked output for " + className);
			assertFalse(text.startsWith("// Failed to decompile"),
					"Chunked output for " + className + " is a failure stub");
		}
		assertTrue(Files.readString(output.resolve("sample").resolve(BatchTestJars.HELLO_WORLD + ".java"),
						StandardCharsets.UTF_8).contains("Hello world"),
				"Chunked output does not look like the source class");
	}

	@Test
	void fastUnsafeWithVineflowerAlsoDecompilesEveryOuterClass() throws Exception {
		Path input = workDir.resolve("in");
		BatchTestJars.writeSampleJar(input, "sample.jar");
		Path output = workDir.resolve("out");

		BatchDecompileReport report = engine.run(request(input, output, BatchAccuracyMode.FAST_UNSAFE).build(),
				BatchDecompileProgressListener.NOOP);
		assertTrue(report.isClean(), "Unexpected failures: " + report.failures());
		assertEquals(OUTER_CLASSES.size(), report.okClasses());
		for (String className : OUTER_CLASSES)
			assertTrue(Files.isRegularFile(output.resolve("sample").resolve(className + ".java")),
					"Missing chunked output for " + className);
	}

	@Test
	void fastVerifiedWithoutVineflowerFallsBackToTheAccuratePath() throws Exception {
		Path input = workDir.resolve("in");
		BatchTestJars.writeSampleJar(input, "sample.jar");
		Path output = workDir.resolve("out");

		BatchDecompileReport report = engine.run(request(input, output, BatchAccuracyMode.FAST_VERIFIED)
				.decompilerName(CfrDecompiler.NAME)
				.build(), BatchDecompileProgressListener.NOOP);
		assertTrue(report.isClean(), "Unexpected failures: " + report.failures());
		assertEquals(OUTER_CLASSES.size(), report.okClasses());
		assertTrue(Files.readString(output.resolve("sample").resolve(BatchTestJars.HELLO_WORLD + ".java"),
				StandardCharsets.UTF_8).contains("Hello world"));
	}

	/**
	 * Whatever happens inside a chunk, every outer class must end up as either decompiled source or a
	 * failure stub, and the report must account for all of them. A near-zero timeout pushes the run
	 * toward the timeout path; on a machine fast enough to beat it the classes simply succeed, which
	 * the assertions below accept as well.
	 */
	@Test
	void everyOuterClassYieldsJavaOrFailureStub() throws Exception {
		Path input = workDir.resolve("in");
		BatchTestJars.writeSampleJar(input, "sample.jar");
		Path output = workDir.resolve("out");

		BatchDecompileReport report = engine.run(request(input, output, BatchAccuracyMode.FAST_VERIFIED)
				.timeoutPerClass(Duration.ofNanos(1))
				.build(), BatchDecompileProgressListener.NOOP);

		assertEquals(OUTER_CLASSES.size(), report.totalClasses());
		assertEquals(report.totalClasses(), report.okClasses() + report.failedClasses(),
				"Every class must be reported as either ok or failed");
		assertEquals(report.failedClasses(), report.failures().stream()
						.filter(failure -> failure.className() != null).count(),
				"Each failed class must carry a failure entry");
		for (String className : OUTER_CLASSES) {
			Path javaFile = output.resolve("sample").resolve(className + ".java");
			assertTrue(Files.isRegularFile(javaFile),
					"Class " + className + " produced neither source nor a failure stub");
			assertFalse(Files.readString(javaFile, StandardCharsets.UTF_8).isBlank(),
					"Empty output written for " + className);
		}
	}

	@Nonnull
	private static BatchDecompileRequest.Builder request(@Nonnull Path input, @Nonnull Path output,
	                                                     @Nonnull BatchAccuracyMode mode) {
		return BatchDecompileRequest.builder(input, output)
				.outputFormat(BatchOutputFormat.DIRECTORY)
				.decompilerName(VineflowerDecompiler.NAME)
				.accuracyMode(mode)
				.timeoutPerClass(Duration.ofSeconds(30))
				.workers(2, 2);
	}
}
