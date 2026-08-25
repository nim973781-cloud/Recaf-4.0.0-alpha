package software.coley.recaf.services.decompile.fallback;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.decompile.batch.BatchAccuracyMode;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static software.coley.recaf.services.decompile.batch.session.SessionEquivalenceTestSupport.assertAccurateMatchesSingleClassPath;
import static software.coley.recaf.services.decompile.batch.session.SessionEquivalenceTestSupport.assertChunkOrderDoesNotChangeOutput;
import static software.coley.recaf.services.decompile.batch.session.SessionEquivalenceTestSupport.assertInterruptStopsSession;
import static software.coley.recaf.services.decompile.batch.session.SessionEquivalenceTestSupport.decompileInChunks;
import static software.coley.recaf.services.decompile.batch.session.SessionEquivalenceTestSupport.partition;
import static software.coley.recaf.services.decompile.batch.session.SessionEquivalenceTestSupport.singleClassBaseline;

/**
 * Equivalence gates for {@link FallbackSessionFactory}.
 * <p/>
 * The fallback printer has no fast mode of its own; the session-level descriptor cache is pure memoization,
 * so the fast gate here is byte-identity <i>(strictly stronger than the signature-set gate)</i>.
 */
class FallbackSessionEquivalenceTest extends TestBase {
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
	static FallbackSessionFactory factory;
	static JvmDecompiler singleDecompiler;
	static Workspace workspace;
	static List<JvmClassInfo> classes;

	@BeforeAll
	static void setup() throws IOException {
		factory = recaf.get(FallbackSessionFactory.class);
		singleDecompiler = recaf.get(DecompilerManager.class).getJvmDecompiler(FallbackDecompiler.NAME);
		assertNotNull(singleDecompiler, "Fallback decompiler was never registered with manager");

		BasicJvmClassBundle bundle = TestClassUtils.fromClasses(TARGETS);
		workspace = TestClassUtils.fromBundle(bundle);
		classes = new ArrayList<>(bundle.values());
	}

	@Test
	void accurateSessionIsByteIdenticalWithSingleClassPath() throws InterruptedException {
		assertAccurateMatchesSingleClassPath(factory, singleDecompiler, workspace, classes);
	}

	@Test
	void fastSessionIsAlsoByteIdenticalWithSingleClassPath() throws InterruptedException {
		// The descriptor cache must be invisible in the output: byte-identity implies the FAST gate's
		// signature-set equality with 100% coverage.
		Map<String, String> baseline = singleClassBaseline(singleDecompiler, workspace, classes);
		Map<String, String> fast = decompileInChunks(factory, workspace, BatchAccuracyMode.FAST_VERIFIED,
				partition(classes, 3));
		assertEquals(classes.size(), fast.size(), "FAST session did not cover every class");
		for (JvmClassInfo info : classes)
			assertEquals(baseline.get(info.getName()), fast.get(info.getName()),
					"Cached descriptor conversion changed the output of " + info.getName());
	}

	@Test
	void fastSessionOutputDoesNotDependOnChunkOrder() throws InterruptedException {
		assertChunkOrderDoesNotChangeOutput(factory, workspace, classes, BatchAccuracyMode.FAST_VERIFIED);
	}

	@Test
	void sessionsRespondToInterrupts() throws Exception {
		assertInterruptStopsSession(factory, workspace, classes, BatchAccuracyMode.ACCURATE);
		assertInterruptStopsSession(factory, workspace, classes, BatchAccuracyMode.FAST_VERIFIED);
	}
}
