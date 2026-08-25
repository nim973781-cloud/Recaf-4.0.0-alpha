package software.coley.recaf.services.decompile.vineflower;

import jakarta.annotation.Nonnull;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.decompile.batch.BatchAccuracyMode;
import software.coley.recaf.services.decompile.batch.ClassExportTask;
import software.coley.recaf.services.decompile.batch.session.BatchDecompileSession;
import software.coley.recaf.services.decompile.batch.session.BatchSessionSupport;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.HelloWorld;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the fast Vineflower sessions group their work.
 * <p>
 * Vineflower parallelizes one context internally, so a fast session has to hand it whole contexts and let
 * {@code thread-count} do the spreading. Splitting the plan across engine workers instead leaves every
 * context single threaded, which is the arrangement the accurate per-class path already has.
 */
class VineflowerFastPartitionTest extends TestBase {
	static VineflowerSessionFactory factory;
	static VineflowerConfig config;
	static Workspace workspace;

	@BeforeAll
	static void setup() throws IOException {
		factory = recaf.get(VineflowerSessionFactory.class);
		config = recaf.get(VineflowerConfig.class);
		BasicJvmClassBundle bundle = TestClassUtils.fromClasses(HelloWorld.class);
		workspace = TestClassUtils.fromBundle(bundle);
	}

	@Test
	void fastSessionPutsEverythingThatFitsInOneContext() {
		for (int classCount : new int[]{1, 17, 200, VineflowerBatchSupport.MAX_CHUNK_SIZE}) {
			List<ClassExportTask> tasks = tasks(classCount);
			List<List<ClassExportTask>> groups = partition(BatchAccuracyMode.FAST_VERIFIED, tasks);
			assertEquals(1, groups.size(), "Expected one context for " + classCount + " classes");
			assertEquals(tasks, groups.getFirst());
		}
	}

	@Test
	void fastSessionSlicesLargerPlansAtTheContextCeiling() {
		int max = VineflowerBatchSupport.MAX_CHUNK_SIZE;
		for (int classCount : new int[]{max + 1, 700, max * 3}) {
			List<ClassExportTask> tasks = tasks(classCount);
			List<List<ClassExportTask>> groups = partition(BatchAccuracyMode.FAST_VERIFIED, tasks);
			assertEquals((classCount + max - 1) / max, groups.size(),
					"Expected " + max + "-class slices for " + classCount + " classes");

			List<ClassExportTask> flattened = new ArrayList<>(classCount);
			for (List<ClassExportTask> group : groups) {
				assertTrue(group.size() <= max, "Slice of " + group.size() + " exceeds the context ceiling");
				flattened.addAll(group);
			}
			assertEquals(tasks, flattened, "Slicing dropped or reordered classes");
		}
	}

	@Test
	void fastSessionHasNothingToGroupWhenThePlanIsEmpty() {
		assertTrue(partition(BatchAccuracyMode.FAST_VERIFIED, List.of()).isEmpty());
	}

	/**
	 * The accurate path is defined by the single-class decompiler, so it must keep getting one class per
	 * group no matter what the fast path does with its own grouping.
	 */
	@Test
	void accurateSessionStillGetsOneGroupPerClass() {
		List<ClassExportTask> tasks = tasks(300);
		List<List<ClassExportTask>> groups = partition(BatchAccuracyMode.ACCURATE, tasks);
		assertEquals(tasks.size(), groups.size());
		for (List<ClassExportTask> group : groups)
			assertEquals(1, group.size());
	}

	/**
	 * The properties a fast context runs with are the fast snapshot plus a thread count, and taking that
	 * overlay must not move the snapshot the interactive and accurate paths share off one thread.
	 */
	@Test
	void fastContextThreadCountIsOverlaidWithoutTouchingTheSharedSnapshots() {
		assertEquals(BatchSessionSupport.PARALLELISM, VineflowerSessionFactory.CONTEXT_THREADS,
				"A fast context should be able to use the whole machine");
		assertTrue(VineflowerSessionFactory.CONTEXT_THREADS >= Runtime.getRuntime().availableProcessors(),
				"A fast context should not be narrower than the machine");

		assertEquals(String.valueOf(VineflowerSessionFactory.CONTEXT_THREADS),
				config.getFastFernflowerProperties(VineflowerSessionFactory.CONTEXT_THREADS)
						.get(IFernflowerPreferences.THREADS));
		assertEquals("1", config.getFastFernflowerProperties().get(IFernflowerPreferences.THREADS),
				"The shared fast snapshot must stay single threaded");
		assertEquals("1", config.getFernflowerProperties().get(IFernflowerPreferences.THREADS),
				"Interactive and ACCURATE decompiles must stay single threaded");
		assertSame(config.getFastFernflowerProperties(), config.getFastFernflowerProperties(1),
				"A single threaded context should reuse the snapshot rather than copy it");
	}

	@Nonnull
	private static List<List<ClassExportTask>> partition(@Nonnull BatchAccuracyMode mode,
	                                                     @Nonnull List<ClassExportTask> tasks) {
		try (BatchDecompileSession session = factory.open(workspace, mode)) {
			assertNotNull(session, "Factory declined to open a session for " + mode);
			return session.partition(tasks);
		}
	}

	/**
	 * Partitioning never looks at bytecode, so the tasks only need to be distinct and cheap.
	 */
	@Nonnull
	private static List<ClassExportTask> tasks(int classCount) {
		List<JvmClassInfo> classes = new ArrayList<>(classCount);
		for (int i = 0; i < classCount; i++)
			classes.add(new JvmClassInfoBuilder()
					.withName("software/coley/recaf/test/generated/Generated" + i)
					.withSuperName("java/lang/Object")
					.withVersion(JvmClassInfo.BASE_VERSION + 17)
					.withBytecode(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE})
					.build());
		return BatchSessionSupport.tasks(classes);
	}
}
