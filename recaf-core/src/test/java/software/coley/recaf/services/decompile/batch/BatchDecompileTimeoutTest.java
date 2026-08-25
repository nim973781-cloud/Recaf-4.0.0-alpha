package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.AbstractJvmDecompiler;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.NoopDecompilerConfig;
import software.coley.recaf.services.decompile.cfr.CfrDecompiler;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.workspace.model.Workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a batch run does when a decompiler simply never comes back.
 * <p>
 * The run has to end on the schedule the request asked for, every class has to be accounted for as a
 * timeout rather than silently dropped, and the engine has to be usable afterwards. Without that last
 * part a single hostile class would poison every later run of the session.
 */
class BatchDecompileTimeoutTest extends TestBase {
	private static final List<String> CLASSES = List.of(
			BatchTestJars.HELLO_WORLD, BatchTestJars.STRING_SUPPLIER, BatchTestJars.STRING_CONSUMER);
	private static final Duration TIMEOUT = Duration.ofSeconds(1);
	static DefaultBatchDecompileEngine engine;
	static DecompilerManager decompilerManager;

	@TempDir
	Path workDir;
	private HangingDecompiler hanging;

	@BeforeAll
	static void setup() {
		engine = recaf.get(DefaultBatchDecompileEngine.class);
		decompilerManager = recaf.get(DecompilerManager.class);
	}

	@AfterEach
	void releaseDecompiler() {
		if (hanging != null)
			hanging.release();
	}

	@Test
	@Timeout(value = 2, unit = TimeUnit.MINUTES)
	void aDecompilerThatNeverReturnsTimesOutAndLeavesTheEngineUsable() throws Exception {
		hanging = new HangingDecompiler();
		decompilerManager.register(hanging);

		Path input = workDir.resolve("in");
		BatchTestJars.writeSampleJar(input, "sample.jar");
		Path output = workDir.resolve("out");

		long startNanos = System.nanoTime();
		BatchDecompileReport report = engine.run(BatchDecompileRequest.builder(input, output)
				.decompilerName(HangingDecompiler.NAME)
				.timeoutPerClass(TIMEOUT)
				.workers(CLASSES.size(), 2)
				.build(), BatchDecompileProgressListener.NOOP);
		Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

		assertTrue(elapsed.compareTo(TIMEOUT.plusSeconds(5)) < 0,
				"Run took " + elapsed.toMillis() + "ms for a " + TIMEOUT.toMillis() + "ms per-class timeout");
		assertTrue(hanging.started() > 0, "The decompiler was never even reached");

		assertEquals(CLASSES.size(), report.totalClasses());
		assertEquals(CLASSES.size(), report.failedClasses());
		assertEquals(0, report.okClasses());
		assertEquals(CLASSES.size(), report.failures().size(), "Unexpected failures: " + report.failures());
		for (BatchDecompileFailure failure : report.failures())
			assertEquals(BatchDecompileFailure.PHASE_TIMEOUT, failure.phase(),
					"Expected a timeout for " + failure.className() + ", got: " + failure.message());

		// Every class still produced a file, so the output describes the whole input rather than silently
		// missing the classes that hung.
		for (String className : CLASSES) {
			Path javaFile = output.resolve("sample").resolve(className + ".java");
			assertTrue(Files.isRegularFile(javaFile), "No failure stub written for " + className);
			assertTrue(Files.readString(javaFile, StandardCharsets.UTF_8).contains("Failed to decompile"),
					"Stub for " + className + " does not look like a failure stub");
		}

		// The hung decompilations are still parked on the shared decompile pool, since a batch run cannot
		// interrupt work it did not start. Letting them go is what a real decompiler doing this would need
		// too, and afterwards the next run has to be back to full throughput.
		hanging.release();
		assertTrue(hanging.finished(30, TimeUnit.SECONDS), "Hung decompilations never unwound");

		Path secondInput = workDir.resolve("in2");
		BatchTestJars.writeSampleJar(secondInput, "alpha.jar");
		BatchTestJars.writeSampleJar(secondInput, "beta.jar");
		Path secondOutput = workDir.resolve("out2");
		BatchDecompileReport second = engine.run(BatchDecompileRequest.builder(secondInput, secondOutput)
				.decompilerName(CfrDecompiler.NAME)
				.timeoutPerClass(Duration.ofSeconds(60))
				.workers(4, 2)
				.build(), BatchDecompileProgressListener.NOOP);

		assertTrue(second.isClean(), "A later run inherited failures: " + second.failures());
		assertEquals(2 * CLASSES.size(), second.okClasses(), "A later run did not decompile every class");
		assertEquals(2, second.okJars());
	}

	/**
	 * Decompiler that parks every class until it is released.
	 */
	private static final class HangingDecompiler extends AbstractJvmDecompiler {
		private static final String NAME = "hangs-forever-test";
		private final CountDownLatch release = new CountDownLatch(1);
		private final AtomicInteger started = new AtomicInteger();
		private final AtomicInteger completed = new AtomicInteger();

		private HangingDecompiler() {
			super(NAME, "1.0.0", new NoopDecompilerConfig());
		}

		@Nonnull
		@Override
		protected DecompileResult decompileInternal(@Nonnull Workspace workspace, @Nonnull JvmClassInfo classInfo) {
			started.incrementAndGet();
			try {
				release.await();
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			completed.incrementAndGet();
			return new DecompileResult("// released\n", 0);
		}

		private int started() {
			return started.get();
		}

		private void release() {
			release.countDown();
		}

		private boolean finished(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
			long deadline = System.nanoTime() + unit.toNanos(timeout);
			while (System.nanoTime() < deadline) {
				if (completed.get() >= started.get())
					return true;
				Thread.sleep(20);
			}
			return completed.get() >= started.get();
		}
	}
}
