package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.recaf.services.decompile.vineflower.VineflowerDecompiler;
import software.coley.recaf.test.TestBase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the fast accuracy modes to the same accounting as the accurate one.
 * <p>
 * A session decompiles whole groups of classes at once, which is where the speed comes from and also
 * where a class can quietly disappear into a group that failed as a whole. So both modes must account
 * for every class in the input, and the fast mode is not allowed to fail more of them than the accurate
 * mode does. Whether the two produce the same <i>text</i> is a stronger claim than this test makes.
 */
class BatchAccuracyModeParityTest extends TestBase {
	private static final List<String> CLASSES = List.of(
			BatchTestJars.HELLO_WORLD, BatchTestJars.STRING_SUPPLIER, BatchTestJars.STRING_CONSUMER);
	static DefaultBatchDecompileEngine engine;

	@TempDir
	Path workDir;

	@BeforeAll
	static void setupEngine() {
		engine = recaf.get(DefaultBatchDecompileEngine.class);
	}

	@Test
	void fastVerifiedAccountsForEveryClassTheAccuratePathDoes() throws Exception {
		BatchDecompileReport accurate = run(BatchAccuracyMode.ACCURATE, "accurate");
		BatchDecompileReport fast = run(BatchAccuracyMode.FAST_VERIFIED, "fast-verified");

		assertEquals(accurate.totalClasses(), fast.totalClasses());
		assertEquals(accurate.okClasses() + accurate.failedClasses(),
				fast.okClasses() + fast.failedClasses(),
				"The fast path lost track of classes the accurate path accounted for");
		assertTrue(fast.failedClasses() <= accurate.failedClasses(),
				"The fast path failed " + fast.failedClasses() + " classes against "
						+ accurate.failedClasses() + " on the accurate path");
	}

	@Test
	void fastUnsafeAccountsForEveryClassTheAccuratePathDoes() throws Exception {
		BatchDecompileReport accurate = run(BatchAccuracyMode.ACCURATE, "accurate");
		BatchDecompileReport fast = run(BatchAccuracyMode.FAST_UNSAFE, "fast-unsafe");

		assertEquals(accurate.totalClasses(), fast.totalClasses());
		assertEquals(accurate.okClasses() + accurate.failedClasses(),
				fast.okClasses() + fast.failedClasses());
		assertTrue(fast.failedClasses() <= accurate.failedClasses());
	}

	@Test
	void everyModeWritesAFileForEveryClass() throws Exception {
		for (BatchAccuracyMode mode : BatchAccuracyMode.values()) {
			String name = mode.name().toLowerCase(java.util.Locale.ROOT);
			run(mode, name);
			Path output = workDir.resolve("out-" + name).resolve("sample");
			for (String className : CLASSES) {
				Path javaFile = output.resolve(className + ".java");
				assertTrue(Files.isRegularFile(javaFile), mode + " produced no file for " + className);
				assertFalse(Files.readString(javaFile, StandardCharsets.UTF_8).isBlank(),
						mode + " produced an empty file for " + className);
			}
		}
	}

	@Nonnull
	private BatchDecompileReport run(@Nonnull BatchAccuracyMode mode, @Nonnull String label) throws Exception {
		Path input = input();
		Path output = workDir.resolve("out-" + label);
		BatchDecompileReport report = engine.run(BatchDecompileRequest.builder(input, output)
				.decompilerName(VineflowerDecompiler.NAME)
				.accuracyMode(mode)
				.timeoutPerClass(Duration.ofSeconds(60))
				.workers(4, 2)
				.build(), BatchDecompileProgressListener.NOOP);
		assertEquals(CLASSES.size(), report.totalClasses(), mode + " did not see every class");
		return report;
	}

	@Nonnull
	private Path input() throws IOException {
		Path input = workDir.resolve("in");
		if (!Files.isDirectory(input))
			BatchTestJars.writeSampleJar(input, "sample.jar");
		return input;
	}
}
