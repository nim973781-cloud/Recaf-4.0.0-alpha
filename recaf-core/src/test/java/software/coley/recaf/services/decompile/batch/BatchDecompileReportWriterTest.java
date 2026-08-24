package software.coley.recaf.services.decompile.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link BatchDecompileReportWriter}.
 */
class BatchDecompileReportWriterTest {
	@TempDir
	Path workDir;

	@Test
	void jsonHoldsCountsHashesAndFailures() {
		String json = BatchDecompileReportWriter.toJson(report());
		assertTrue(json.contains("\"totalJars\": 2"), json);
		assertTrue(json.contains("\"okClasses\": 5"), json);
		assertTrue(json.contains("\"durationMs\": 1500"), json);
		assertTrue(json.contains("\"sample/data.json\": \"abc123\""), json);
		assertTrue(json.contains("\"phase\": \"timeout\""), json);
		assertTrue(json.contains("\\n"), "Multi-line trace text was not escaped");
	}

	@Test
	void outputIsDeterministic() {
		BatchDecompileReport report = report();
		assertEquals(BatchDecompileReportWriter.toJson(report), BatchDecompileReportWriter.toJson(report));
	}

	@Test
	void writeCreatesParentDirectories() throws IOException {
		Path path = workDir.resolve("nested").resolve("report.json");
		BatchDecompileReportWriter.write(report(), path);
		assertTrue(Files.isRegularFile(path));
		assertEquals(BatchDecompileReportWriter.toJson(report()),
				Files.readString(path, StandardCharsets.UTF_8));
	}

	private static BatchDecompileReport report() {
		Instant start = Instant.parse("2026-06-21T10:00:00Z");
		return new BatchDecompileReport(start, start.plusMillis(1500), 2, 1, 0, 1,
				6, 5, 2, 1, 7,
				Map.of("sample/data.json", "abc123"),
				List.of(new BatchDecompileFailure("sample.jar", "com/example/Slow",
						BatchDecompileFailure.PHASE_TIMEOUT, "timed out",
						"java.util.concurrent.TimeoutException\n\tat example")));
	}
}
