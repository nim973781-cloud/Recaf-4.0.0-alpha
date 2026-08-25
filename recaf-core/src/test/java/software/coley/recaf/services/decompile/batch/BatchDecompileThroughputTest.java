package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.recaf.services.decompile.vineflower.VineflowerDecompiler;
import software.coley.recaf.test.TestBase;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Throughput guard for {@link BatchAccuracyMode#FAST_VERIFIED}.
 * <p/>
 * The fast mode exists for exactly one reason: decompiling a whole archive through shared Vineflower contexts
 * has to be meaningfully faster than driving the accurate single-class path once per class. This test measures
 * both against the same generated input and fails if the fast mode is not at least
 * {@value #REQUIRED_SPEEDUP_PERCENT}% faster, so a regression that quietly puts the run back on the
 * single-class path cannot pass unnoticed.
 * <p/>
 * Correctness is asserted alongside speed: the fast run may not decompile fewer classes than the accurate run,
 * and every class must end up as either source or a failure stub.
 */
@Tag("throughput")
class BatchDecompileThroughputTest extends TestBase {
	/** Fast mode must finish within this fraction of the accurate run's wall clock. */
	private static final double MAX_FAST_RATIO = 0.70;
	private static final int REQUIRED_SPEEDUP_PERCENT = (int) Math.round((1 - MAX_FAST_RATIO) * 100);
	/**
	 * Many small classes rather than a few large ones. What the chunked path saves is the per-class context
	 * setup, so this is the shape where the two paths actually differ, and it is also the shape a real batch
	 * run over an application archive has.
	 */
	private static final int CLASS_COUNT = 200;
	private static final int METHODS_PER_CLASS = 4;
	/** Both modes get the same worker budget; lowering the accurate one would fake the speedup. */
	private static final int DECOMPILE_WORKERS = 4;
	private static final int IO_WORKERS = 2;
	private static final int MEASURED_RUNS = 5;
	/**
	 * Warm-up passes per mode. Both modes run the same Vineflower internals, and the first pass over them is
	 * dominated by class loading and JIT compilation rather than by the work being compared, so a single pass
	 * is not enough to make the measurement about the decompile paths themselves.
	 */
	private static final int WARMUP_RUNS = 2;

	static DefaultBatchDecompileEngine engine;

	@TempDir
	Path workDir;

	@BeforeAll
	static void setup() {
		engine = recaf.get(DefaultBatchDecompileEngine.class);
	}

	@Test
	void fastVerifiedIsSubstantiallyFasterThanAccurate() throws Exception {
		Path input = workDir.resolve("in");
		List<String> generated = BatchTestJars.writeGeneratedJar(input, "generated.jar",
				CLASS_COUNT, METHODS_PER_CLASS);
		assertEquals(CLASS_COUNT, generated.size());

		for (int i = 0; i < WARMUP_RUNS; i++) {
			RunOutcome accurateWarmup = run(input, BatchAccuracyMode.ACCURATE, "warmup-accurate-" + i);
			RunOutcome fastWarmup = run(input, BatchAccuracyMode.FAST_VERIFIED, "warmup-fast-" + i);
			System.out.printf("[throughput] warmup %d accurate=%s fast=%s%n", i, accurateWarmup, fastWarmup);
		}

		Measurement first = measure(input, 1);
		// A single noisy run on a shared CI box should not fail the build, but two in a row is a real signal.
		Measurement measurement = first.ratio() > MAX_FAST_RATIO ? measure(input, 2) : first;

		System.out.printf("[throughput] classes=%d accurate median=%dms (%.1f classes/sec) " +
						"fast_verified median=%dms (%.1f classes/sec) ratio=%.2f (limit %.2f)%n",
				CLASS_COUNT, measurement.accurateMillis(), measurement.accuratePerSecond(),
				measurement.fastMillis(), measurement.fastPerSecond(), measurement.ratio(), MAX_FAST_RATIO);

		assertTrue(measurement.ratio() <= MAX_FAST_RATIO, () -> String.format(
				"FAST_VERIFIED must be at least %d%% faster than ACCURATE, but took %.0f%% of its time " +
						"(%dms vs %dms over %d classes). Check that the run still reaches the chunked " +
						"Vineflower path instead of falling back to the single-class path.",
				REQUIRED_SPEEDUP_PERCENT, measurement.ratio() * 100, measurement.fastMillis(),
				measurement.accurateMillis(), CLASS_COUNT));
	}

	@Nonnull
	private Measurement measure(@Nonnull Path input, int attempt) throws Exception {
		List<Long> accurate = new ArrayList<>(MEASURED_RUNS);
		List<Long> fast = new ArrayList<>(MEASURED_RUNS);
		for (int i = 0; i < MEASURED_RUNS; i++) {
			// Modes alternate so that a slow patch of the machine hits both of them rather than only one.
			RunOutcome accurateRun = run(input, BatchAccuracyMode.ACCURATE, "accurate-" + attempt + "-" + i);
			RunOutcome fastRun = run(input, BatchAccuracyMode.FAST_VERIFIED, "fast-" + attempt + "-" + i);
			assertTrue(fastRun.okClasses() >= accurateRun.okClasses(),
					"FAST_VERIFIED decompiled fewer classes than ACCURATE: "
							+ fastRun.okClasses() + " < " + accurateRun.okClasses());
			accurate.add(accurateRun.millis());
			fast.add(fastRun.millis());
		}
		return new Measurement(median(accurate), median(fast));
	}

	@Nonnull
	private RunOutcome run(@Nonnull Path input, @Nonnull BatchAccuracyMode mode, @Nonnull String tag)
			throws BatchDecompileException, IOException {
		Path output = workDir.resolve("out-" + tag);
		BatchDecompileRequest request = BatchDecompileRequest.builder(input, output)
				.outputFormat(BatchOutputFormat.ZIP)
				.decompilerName(VineflowerDecompiler.NAME)
				.accuracyMode(mode)
				.includeResources(false)
				.writeFailureStubs(true)
				.timeoutPerClass(Duration.ofSeconds(120))
				.workers(DECOMPILE_WORKERS, IO_WORKERS)
				.build();

		long start = System.nanoTime();
		BatchDecompileReport report = engine.run(request, BatchDecompileProgressListener.NOOP);
		long millis = (System.nanoTime() - start) / 1_000_000L;

		assertEquals(CLASS_COUNT, report.totalClasses(), "Run '" + tag + "' saw the wrong class count");
		assertEquals(report.totalClasses(), report.okClasses() + report.failedClasses(),
				"Run '" + tag + "' left classes unaccounted for");
		assertEquals(report.failedClasses(), report.failures().stream()
						.filter(failure -> failure.className() != null).count(),
				"Run '" + tag + "' has failed classes without a failure entry");
		assertEquals(CLASS_COUNT, report.outputFileCount(),
				"Run '" + tag + "' must write one file per class, as source or as a failure stub");
		return new RunOutcome(millis, report.okClasses());
	}

	private static long median(@Nonnull List<Long> samples) {
		List<Long> sorted = new ArrayList<>(samples);
		Collections.sort(sorted);
		return sorted.get(sorted.size() / 2);
	}

	/**
	 * @param millis
	 * 		Wall-clock duration of one run.
	 * @param okClasses
	 * 		Classes the run decompiled successfully.
	 */
	private record RunOutcome(long millis, int okClasses) {
		@Override
		public String toString() {
			return millis + "ms/" + okClasses + " classes";
		}
	}

	/**
	 * @param accurateMillis
	 * 		Median wall-clock of the accurate runs.
	 * @param fastMillis
	 * 		Median wall-clock of the fast runs.
	 */
	private record Measurement(long accurateMillis, long fastMillis) {
		private double ratio() {
			return accurateMillis <= 0 ? Double.MAX_VALUE : (double) fastMillis / accurateMillis;
		}

		private double accuratePerSecond() {
			return perSecond(accurateMillis);
		}

		private double fastPerSecond() {
			return perSecond(fastMillis);
		}

		private static double perSecond(long millis) {
			return millis <= 0 ? Double.NaN : CLASS_COUNT * 1000d / millis;
		}
	}
}
