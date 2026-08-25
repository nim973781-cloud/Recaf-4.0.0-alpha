package software.coley.recaf.ui.control.popup;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.Test;
import software.coley.recaf.services.decompile.batch.BatchAccuracyMode;
import software.coley.recaf.services.decompile.batch.BatchDecompileEngine;
import software.coley.recaf.services.decompile.batch.BatchDecompileException;
import software.coley.recaf.services.decompile.batch.BatchDecompileProgress;
import software.coley.recaf.services.decompile.batch.BatchDecompileProgressListener;
import software.coley.recaf.services.decompile.batch.BatchDecompileReport;
import software.coley.recaf.services.decompile.batch.BatchDecompileRequest;
import software.coley.recaf.services.decompile.batch.BatchOutputFormat;
import software.coley.recaf.services.decompile.batch.WorkspaceDecompileRequest;
import software.coley.recaf.ui.pane.editing.jvm.DecompilerPaneConfig;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The batch behavior itself lives in {@code software.coley.recaf.services.decompile.batch} and is covered there.
 * These tests only pin down what the UI adapter contributes: request assembly from the popup form, and how the
 * engine outcome is handed back to the popup.
 */
class BatchDecompileJarsRunnerTest {
	private static final Path JAR_DIR = Paths.get("in");
	private static final Path MAPPING_FILE = Paths.get("mappings.txt");
	private static final Path OUTPUT_ROOT = Paths.get("out");

	@Test
	void run_buildsRequestFromFormAndConfiguredTimeout() throws Exception {
		RecordingEngine engine = new RecordingEngine(report(1, 0, 0, 5, 0));

		newRunner(engine, 42).run(JAR_DIR, MAPPING_FILE, OUTPUT_ROOT, "TSRG", "Vineflower", new RecordingCallbacks());

		BatchDecompileRequest request = engine.request;
		assertNotNull(request, "Engine was never invoked");
		assertEquals(JAR_DIR, request.inputDirectory());
		assertEquals(OUTPUT_ROOT, request.outputPath());
		assertEquals(MAPPING_FILE, request.mappingFile());
		assertEquals("TSRG", request.mappingFormat());
		assertEquals("Vineflower", request.decompilerName());
		assertEquals(Duration.ofSeconds(42), request.timeoutPerClass());
		assertEquals(BatchOutputFormat.DIRECTORY, request.outputFormat());
		assertEquals(BatchAccuracyMode.ACCURATE, request.accuracyMode());
	}

	@Test
	void run_forwardsProgressAndReport() throws Exception {
		BatchDecompileProgress progress = new BatchDecompileProgress(2, 1, 10, 6, 5, 0, 1,
				"sample.jar", "com/example/Sample", 0.5);
		BatchDecompileReport report = report(2, 0, 1, 9, 1);
		RecordingEngine engine = new RecordingEngine(report, progress);
		RecordingCallbacks callbacks = new RecordingCallbacks();

		newRunner(engine, 1).run(JAR_DIR, MAPPING_FILE, OUTPUT_ROOT, "TSRG", "", callbacks);

		assertEquals(List.of(progress), callbacks.progress);
		assertSame(report, callbacks.report);
		assertFalse(callbacks.noJarsFound);
	}

	@Test
	void run_reportsNoJarsFoundForEmptyInput() throws Exception {
		RecordingEngine engine = new RecordingEngine(report(0, 0, 0, 0, 0));
		RecordingCallbacks callbacks = new RecordingCallbacks();

		newRunner(engine, 1).run(JAR_DIR, MAPPING_FILE, OUTPUT_ROOT, "TSRG", "", callbacks);

		assertTrue(callbacks.noJarsFound);
		assertNull(callbacks.report);
	}

	@Test
	void run_propagatesRunLevelFailure() {
		BatchDecompileException failure = new BatchDecompileException("nope");
		BatchDecompileEngine engine = new BatchDecompileEngine() {
			@Nonnull
			@Override
			public BatchDecompileReport run(@Nonnull BatchDecompileRequest request,
			                                @Nonnull BatchDecompileProgressListener listener) throws BatchDecompileException {
				throw failure;
			}

			@Nonnull
			@Override
			public BatchDecompileReport exportWorkspace(@Nonnull WorkspaceDecompileRequest request,
			                                            @Nonnull BatchDecompileProgressListener listener) {
				throw new UnsupportedOperationException("The JAR runner never exports a workspace");
			}
		};
		RecordingCallbacks callbacks = new RecordingCallbacks();

		BatchDecompileException thrown = assertThrows(BatchDecompileException.class,
				() -> newRunner(engine, 1).run(JAR_DIR, MAPPING_FILE, OUTPUT_ROOT, "TSRG", "", callbacks));

		assertSame(failure, thrown);
		assertNull(callbacks.report);
		assertFalse(callbacks.noJarsFound);
	}

	@Nonnull
	private static BatchDecompileJarsRunner newRunner(@Nonnull BatchDecompileEngine engine, int timeoutSeconds) {
		DecompilerPaneConfig config = new DecompilerPaneConfig();
		config.getTimeoutSeconds().setValue(timeoutSeconds);
		return new BatchDecompileJarsRunner(engine, config);
	}

	@Nonnull
	private static BatchDecompileReport report(int totalJars, int okJars, int failedJars, int okClasses, int failedClasses) {
		Instant now = Instant.now();
		return new BatchDecompileReport(now, now, totalJars, okJars, 0, failedJars,
				okClasses + failedClasses, okClasses, 0, failedClasses, okClasses, Map.of(), List.of());
	}

	private static final class RecordingEngine implements BatchDecompileEngine {
		private final BatchDecompileReport report;
		private final BatchDecompileProgress[] events;
		private BatchDecompileRequest request;

		private RecordingEngine(@Nonnull BatchDecompileReport report, @Nonnull BatchDecompileProgress... events) {
			this.report = report;
			this.events = events;
		}

		@Nonnull
		@Override
		public BatchDecompileReport run(@Nonnull BatchDecompileRequest request,
		                                @Nonnull BatchDecompileProgressListener listener) {
			this.request = request;
			for (BatchDecompileProgress event : events)
				listener.onProgress(event);
			return report;
		}

		@Nonnull
		@Override
		public BatchDecompileReport exportWorkspace(@Nonnull WorkspaceDecompileRequest request,
		                                            @Nonnull BatchDecompileProgressListener listener) {
			throw new UnsupportedOperationException("The JAR runner never exports a workspace");
		}
	}

	private static final class RecordingCallbacks implements BatchDecompileJarsRunner.Callbacks {
		private final List<BatchDecompileProgress> progress = new ArrayList<>();
		private BatchDecompileReport report;
		private boolean noJarsFound;

		@Override
		public void onNoJarsFound() {
			noJarsFound = true;
		}

		@Override
		public void onProgress(@Nonnull BatchDecompileProgress event) {
			progress.add(event);
		}

		@Override
		public void onComplete(@Nonnull BatchDecompileReport report) {
			this.report = report;
		}
	}
}
