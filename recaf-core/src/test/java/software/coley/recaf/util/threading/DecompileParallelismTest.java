package software.coley.recaf.util.threading;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.coley.recaf.services.decompile.batch.BatchDecompileRequest;
import software.coley.recaf.services.decompile.batch.WorkspaceDecompileRequest;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.HelloWorld;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DecompileParallelism}.
 */
class DecompileParallelismTest {
	@ParameterizedTest
	@CsvSource({
			"1, 1, 1",
			"2, 1, 1",
			"3, 2, 1",
			"4, 3, 1",
			"8, 6, 2",
			"16, 11, 4",
			"20, 14, 4",
			"32, 22, 4"
	})
	void seventyPercentOfDetectedCores(int processors, int decompile, int io) {
		assertEquals(decompile, DecompileParallelism.decompileThreads(processors),
				processors + " cores should give 70% to decompilation");
		assertEquals(io, DecompileParallelism.ioThreads(processors),
				processors + " cores should leave a capped remainder for IO");
		assertTrue(DecompileParallelism.decompileThreads(processors)
				+ DecompileParallelism.ioThreads(processors) <= Math.max(processors + 1, 2),
				"Decompile plus IO should not pile onto the whole machine");
	}

	@Test
	void thisMachineUsesSeventyPercent() {
		int processors = Runtime.getRuntime().availableProcessors();
		assertEquals(DecompileParallelism.decompileThreads(processors),
				DecompileParallelism.decompileThreads());
		assertEquals(DecompileParallelism.ioThreads(processors),
				DecompileParallelism.ioThreads());
	}

	@Test
	void autoBatchRequestFollowsTheSameBudget() {
		Path in = Path.of("in");
		Path out = Path.of("out");
		BatchDecompileRequest request = BatchDecompileRequest.builder(in, out).build();
		assertEquals(DecompileParallelism.decompileThreads(), request.normalizedDecompileWorkers());
		assertEquals(DecompileParallelism.ioThreads(), request.normalizedIoWorkers());
	}

	@Test
	void explicitWorkerCountsAreKept() {
		BatchDecompileRequest request = BatchDecompileRequest.builder(Path.of("in"), Path.of("out"))
				.workers(5, 3)
				.build();
		assertEquals(5, request.normalizedDecompileWorkers());
		assertEquals(3, request.normalizedIoWorkers());
	}

	@Test
	void autoWorkspaceExportFollowsTheSameBudget() throws IOException {
		Workspace workspace = TestClassUtils.fromBundle(TestClassUtils.fromClasses(HelloWorld.class));
		WorkspaceDecompileRequest request = WorkspaceDecompileRequest.builder(workspace, Path.of("out")).build();
		assertEquals(DecompileParallelism.decompileThreads(), request.normalizedDecompileWorkers());
		assertEquals(DecompileParallelism.ioThreads(), request.normalizedIoWorkers());
	}
}
