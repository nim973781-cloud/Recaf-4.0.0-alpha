package software.coley.recaf.services.decompile.vineflower;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.AccessibleFields;
import software.coley.recaf.test.dummy.ClassWithConstructor;
import software.coley.recaf.test.dummy.ClassWithExceptions;
import software.coley.recaf.test.dummy.ClassWithInner;
import software.coley.recaf.test.dummy.ClassWithMultipleMethods;
import software.coley.recaf.test.dummy.ClassWithStaticInit;
import software.coley.recaf.test.dummy.DummyEnum;
import software.coley.recaf.test.dummy.HelloWorld;
import software.coley.recaf.test.dummy.StringConsumer;
import software.coley.recaf.test.dummy.StringConsumerUser;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link VineflowerChunkDecompiler}.
 */
class VineflowerChunkDecompilerTest extends TestBase {
	private static final Logger logger = Logging.get(VineflowerChunkDecompilerTest.class);
	private static final Class<?>[] TARGETS = {
			HelloWorld.class,
			AccessibleFields.class,
			ClassWithConstructor.class,
			ClassWithExceptions.class,
			ClassWithMultipleMethods.class,
			ClassWithStaticInit.class,
			ClassWithInner.class,
			DummyEnum.class,
			StringConsumer.class,
			StringConsumerUser.class
	};
	static VineflowerChunkDecompiler chunkDecompiler;
	static JvmDecompiler singleDecompiler;
	static Workspace workspace;
	static List<JvmClassInfo> classes;

	@BeforeAll
	static void setup() throws IOException {
		chunkDecompiler = recaf.get(VineflowerChunkDecompiler.class);

		// The single class decompiler is not proxyable, so it has to come from the manager rather than CDI directly.
		singleDecompiler = recaf.get(DecompilerManager.class).getJvmDecompiler(VineflowerDecompiler.NAME);
		assertNotNull(singleDecompiler, "Vineflower decompiler was never registered with manager");

		BasicJvmClassBundle bundle = TestClassUtils.fromClasses(TARGETS);
		workspace = TestClassUtils.fromBundle(bundle);
		classes = new ArrayList<>(bundle.values());
	}

	@Test
	void chunkYieldsOutputForEveryClass() {
		Map<String, String> output = chunkDecompiler.decompileChunk(workspace, classes);
		assertEquals(classes.size(), output.size(), "Chunk did not decompile every class");
		for (JvmClassInfo info : classes) {
			String text = output.get(info.getName());
			assertNotNull(text, "Missing chunk output for " + info.getName());
			assertFalse(text.isBlank(), "Blank chunk output for " + info.getName());
		}
	}

	@Test
	void chunkReportsNothingAsFailedWhenAllClassesDecompile() {
		VineflowerChunkDecompiler.ChunkResult result = chunkDecompiler.decompileChunkDetailed(workspace, classes, null);
		assertTrue(result.isComplete(), "Unexpected chunk failures: " + result.failures().keySet());
	}

	@Test
	void emptyChunkIsAllowed() {
		VineflowerChunkDecompiler.ChunkResult result = chunkDecompiler.decompileChunkDetailed(workspace, List.of(), null);
		assertTrue(result.decompiled().isEmpty());
		assertTrue(result.isComplete());
	}

	@Test
	void batchingSplitsWorkButCoversEveryClass() {
		// A chunk size below the class count forces multiple chunks, all sharing one library source.
		Map<String, String> output = chunkDecompiler.decompileBatched(workspace, classes, 3);
		assertEquals(classes.size(), output.size(), "Batched run did not decompile every class");
		for (JvmClassInfo info : classes)
			assertFalse(output.getOrDefault(info.getName(), "").isBlank(), "Blank batch output for " + info.getName());
	}

	@Test
	void partitionRespectsChunkSize() {
		List<List<JvmClassInfo>> chunks = VineflowerBatchSupport.partition(classes, 4);
		assertEquals((classes.size() + 3) / 4, chunks.size());
		int total = 0;
		for (List<JvmClassInfo> chunk : chunks) {
			assertTrue(chunk.size() <= 4);
			assertFalse(chunk.isEmpty());
			total += chunk.size();
		}
		assertEquals(classes.size(), total);

		// Oversized requests are clamped, and an empty input yields no chunks.
		assertTrue(VineflowerBatchSupport.partition(classes, Integer.MAX_VALUE).size() <= 1);
		assertTrue(VineflowerBatchSupport.partition(List.of(), 8).isEmpty());
	}

	/**
	 * A class that yields no output must land in {@link VineflowerChunkDecompiler.ChunkResult#failures()},
	 * and it must not take the rest of its chunk down with it. Batch consumers rely on both properties to
	 * write per-class failure stubs instead of dropping whole chunks.
	 */
	@Test
	void classWithNoOutputFailsWithoutDroppingItsChunk() {
		// A class nothing else in the chunk references, with bytecode Vineflower cannot read.
		// Reusing the name of a real class here would also break every chunk sibling referencing it.
		JvmClassInfo broken = new JvmClassInfoBuilder()
				.withName("software/coley/recaf/test/dummy/BrokenBytecode")
				.withSuperName("java/lang/Object")
				.withBytecode(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 0})
				.build();
		List<JvmClassInfo> chunk = new ArrayList<>(classes);
		chunk.add(broken);

		VineflowerChunkDecompiler.ChunkResult result = chunkDecompiler.decompileChunkDetailed(workspace, chunk, null);
		assertFalse(result.isComplete(), "A class without output must be reported as a failure");
		assertNotNull(result.failures().get(broken.getName()),
				"The broken class must map to the reason it produced no output");
		assertFalse(result.decompiled().containsKey(broken.getName()),
				"The broken class must not also be reported as decompiled");
		for (JvmClassInfo info : chunk) {
			if (info == broken) continue;
			String text = result.decompiled().get(info.getName());
			assertNotNull(text, "Broken chunk entry dropped sibling " + info.getName());
			assertFalse(text.isBlank(), "Broken chunk entry blanked sibling " + info.getName());
		}
		assertEquals(chunk.size(), result.decompiled().size() + result.failures().size(),
				"Every requested class must be accounted for exactly once");
	}

	/**
	 * The chunk API shares one Fernflower context between classes, so its text is allowed to drift from the single
	 * class path. We only record the drift here. Empty output is the one thing treated as a failure, since that
	 * would mean the shared context lost a class outright.
	 */
	@Test
	void chunkOutputIsComparedAgainstSingleClassOutput() {
		Map<String, String> chunkOutput = chunkDecompiler.decompileChunk(workspace, classes);
		List<String> differing = new ArrayList<>();
		for (JvmClassInfo info : classes) {
			String single = assertDoesNotThrow(() -> textOf(singleDecompiler.decompile(workspace, info)));
			String chunked = chunkOutput.get(info.getName());
			assertNotNull(chunked, "Missing chunk output for " + info.getName());
			assertFalse(chunked.isBlank(), "Blank chunk output for " + info.getName());
			if (!single.equals(chunked))
				differing.add(info.getName());
		}
		if (!differing.isEmpty())
			logger.info("Chunk output differs from single class output for {} of {} classes: {}",
					differing.size(), classes.size(), differing);
	}

	@Nonnull
	private static String textOf(@Nonnull DecompileResult result) {
		String text = result.getText();
		assertNotNull(text, "Single class decompile produced no text");
		return text;
	}
}
