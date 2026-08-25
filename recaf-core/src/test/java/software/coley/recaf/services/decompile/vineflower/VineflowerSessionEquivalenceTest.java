package software.coley.recaf.services.decompile.vineflower;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.decompile.batch.BatchAccuracyMode;
import software.coley.recaf.services.source.AstService;
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
import software.coley.sourcesolver.Parser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static software.coley.recaf.services.decompile.batch.session.SessionEquivalenceTestSupport.assertAccurateMatchesSingleClassPath;
import static software.coley.recaf.services.decompile.batch.session.SessionEquivalenceTestSupport.assertChunkOrderDoesNotChangeOutput;
import static software.coley.recaf.services.decompile.batch.session.SessionEquivalenceTestSupport.assertFastSignaturesMatchAccurate;
import static software.coley.recaf.services.decompile.batch.session.SessionEquivalenceTestSupport.assertInterruptStopsSession;

/**
 * Equivalence gates for {@link VineflowerSessionFactory}.
 */
class VineflowerSessionEquivalenceTest extends TestBase {
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
	static VineflowerSessionFactory factory;
	static JvmDecompiler singleDecompiler;
	static Workspace workspace;
	static List<JvmClassInfo> classes;
	static Parser parser;

	@BeforeAll
	static void setup() throws IOException {
		factory = recaf.get(VineflowerSessionFactory.class);
		singleDecompiler = recaf.get(DecompilerManager.class).getJvmDecompiler(VineflowerDecompiler.NAME);
		assertNotNull(singleDecompiler, "Vineflower decompiler was never registered with manager");
		parser = recaf.get(AstService.class).getSharedJavaParser();

		BasicJvmClassBundle bundle = TestClassUtils.fromClasses(TARGETS);
		workspace = TestClassUtils.fromBundle(bundle);
		classes = new ArrayList<>(bundle.values());
	}

	@Test
	void accurateSessionIsByteIdenticalWithSingleClassPath() throws InterruptedException {
		assertAccurateMatchesSingleClassPath(factory, singleDecompiler, workspace, classes);
	}

	@Test
	void fastSessionDeclaresSameSignaturesAsAccuratePath() throws InterruptedException {
		assertFastSignaturesMatchAccurate(factory, singleDecompiler, workspace, classes, parser);
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
