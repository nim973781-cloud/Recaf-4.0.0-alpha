package software.coley.recaf.services.decompile;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.HelloWorld;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecompilerManagerCancelTest {
	@Test
	void hardCancellationInterruptsWorkerAndAllowsImmediateResubmission() throws Exception {
		DecompilerManagerConfig config = new DecompilerManagerConfig();
		config.getCacheDecompilations().setValue(false);
		@SuppressWarnings("unchecked")
		Instance<Decompiler> implementations = mock(Instance.class);
		when(implementations.iterator()).thenReturn(Collections.emptyIterator());
		DecompilerManager manager = new DecompilerManager(config, implementations);

		Workspace workspace = TestClassUtils.fromBundle(TestClassUtils.fromClasses(HelloWorld.class));
		JvmClassInfo target = workspace.getPrimaryResource().getJvmClassBundle()
				.get(HelloWorld.class.getName().replace('.', '/'));
		InterruptibleDecompiler decompiler = new InterruptibleDecompiler();

		CompletableFuture<DecompileResult> cancelled = manager.decompile(decompiler, workspace, target);
		assertTrue(decompiler.started.await(1, TimeUnit.SECONDS), "Fake backend never started");
		assertTrue(manager.cancelHard(decompiler, workspace, target), "Manager did not find the in-flight request");
		assertThrows(Exception.class, () -> cancelled.get(1, TimeUnit.SECONDS));
		assertTrue(decompiler.exited.await(1, TimeUnit.SECONDS), "Interrupted backend did not exit within one second");

		DecompileResult retry = manager.decompile(decompiler, workspace, target).get(1, TimeUnit.SECONDS);
		assertEquals(DecompileResult.ResultType.SUCCESS, retry.getType());
		assertEquals("// retry", retry.getText());
		assertEquals(2, decompiler.invocations.get(), "Equivalent request was not resubmitted");
	}

	private static class InterruptibleDecompiler extends AbstractJvmDecompiler {
		private final AtomicInteger invocations = new AtomicInteger();
		private final CountDownLatch started = new CountDownLatch(1);
		private final CountDownLatch exited = new CountDownLatch(1);

		private InterruptibleDecompiler() {
			super("interruptible-test", "1", new BaseDecompilerConfig("interruptible-test-config"));
		}

		@Nonnull
		@Override
		protected DecompileResult decompileInternal(@Nonnull Workspace workspace, @Nonnull JvmClassInfo classInfo) {
			if (invocations.incrementAndGet() > 1)
				return new DecompileResult("// retry", getConfig().getHash());

			started.countDown();
			try {
				while (true)
					Thread.sleep(10_000);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return new DecompileResult(getConfig().getHash());
			} finally {
				exited.countDown();
			}
		}
	}
}
