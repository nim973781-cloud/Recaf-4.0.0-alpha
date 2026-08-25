package software.coley.recaf.services.decompile;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.coley.observables.ObservableBoolean;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.cfr.CfrDecompiler;
import software.coley.recaf.services.decompile.fallback.FallbackDecompiler;
import software.coley.recaf.services.decompile.filter.JvmBytecodeFilter;
import software.coley.recaf.services.decompile.filter.OutputTextFilter;
import software.coley.recaf.services.decompile.procyon.ProcyonDecompiler;
import software.coley.recaf.services.decompile.vineflower.VineflowerDecompiler;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.HelloWorld;
import software.coley.recaf.util.ReflectUtil;
import software.coley.recaf.util.threading.DecompileParallelism;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link DecompilerManager}.
 */
public class DecompileManagerTest extends TestBase {
	private static final ObservableBoolean OB_TRUE = new ObservableBoolean(true);
	private static final ObservableBoolean OB_FALSE = new ObservableBoolean(false);
	static final TestJvmBytecodeFilter bytecodeFilter = new TestJvmBytecodeFilter();
	static final TestOutputTextFilter textFilter = new TestOutputTextFilter();
	static DecompilerManager decompilerManager;
	static DecompilerManagerConfig decompilerManagerConfig;
	static Workspace workspace;
	static JvmClassInfo classHelloWorld;

	@BeforeAll
	static void setup() throws IOException {
		decompilerManager = recaf.get(DecompilerManager.class);

		// Setup workspace to pull from
		classHelloWorld = TestClassUtils.fromRuntimeClass(HelloWorld.class);
		workspace = TestClassUtils.fromBundle(TestClassUtils.fromClasses(classHelloWorld));
		workspaceManager.setCurrent(workspace);
	}

	@BeforeEach
	void setupEach() {
		// We don't want to cache decompilations for this test, but we also
		// do not want to edit the shared config in tests.
		// Thus, we will make new config instances each test run so there's no cross-test pollution.
		decompilerManagerConfig = new DecompilerManagerConfig();
		decompilerManagerConfig.getCacheDecompilations().setValue(false);
		assertDoesNotThrow(() -> ReflectUtil.quietSet(unwrapProxy(decompilerManager),
				DecompilerManager.class.getDeclaredField("config"),
				decompilerManagerConfig));
	}

	@Test
	void testCfr() {
		JvmDecompiler decompiler = decompilerManager.getJvmDecompiler(CfrDecompiler.NAME);
		assertNotNull(decompiler, "CFR decompiler was never registered with manager");
		runJvmDecompilation(decompiler);
	}

	@Test
	void testProcyon() {
		JvmDecompiler decompiler = decompilerManager.getJvmDecompiler(ProcyonDecompiler.NAME);
		assertNotNull(decompiler, "Procyon decompiler was never registered with manager");
		runJvmDecompilation(decompiler);
	}

	@Test
	void testVineflower() {
		JvmDecompiler decompiler = decompilerManager.getJvmDecompiler(VineflowerDecompiler.NAME);
		assertNotNull(decompiler, "Vineflower decompiler was never registered with manager");
		runJvmDecompilation(decompiler);
	}

	@Test
	void testFallback() {
		JvmDecompiler decompiler = decompilerManager.getJvmDecompiler(FallbackDecompiler.NAME);
		assertNotNull(decompiler, "Fallback decompiler was never registered with manager");
		runJvmDecompilation(decompiler);
	}

	@Test
	void testFiltersUsed() {
		JvmDecompiler decompiler = decompilerManager.getTargetJvmDecompiler();
		TestJvmBytecodeFilter bytecodeFilterSpy = spy(bytecodeFilter);
		TestOutputTextFilter textFilterSpy = spy(textFilter);
		try {
			// Add input/output filters
			decompilerManager.addJvmBytecodeFilter(bytecodeFilterSpy);
			decompilerManager.addOutputTextFilter(textFilterSpy);

			// Decompile
			decompilerManager.decompile(decompiler, workspace, classHelloWorld).get();

			// Verify each filter was called once
			verify(bytecodeFilterSpy, times(1)).filter(any(), any(), any());
			verify(textFilterSpy, times(1)).filter(any(), any(), anyString());
		} catch (Exception ex) {
			fail(ex);
		} finally {
			decompilerManager.removeJvmBytecodeFilter(bytecodeFilterSpy);
			decompilerManager.removeOutputTextFilter(textFilterSpy);
		}
	}

	@Test
	void testCaching() {
		decompilerManagerConfig.getCacheDecompilations().setValue(true);
		JvmDecompiler decompiler = decompilerManager.getJvmDecompiler(CfrDecompiler.NAME);
		DecompileResult firstResult = assertDoesNotThrow(() -> decompilerManager.decompile(decompiler, workspace, classHelloWorld).get(1, TimeUnit.DAYS));

		// Assert that repeated decompiles use the same result (caching, should be handled by abstract base)
		// Only the manager will cache results. Using decompilers direcrly will not cache.
		assertTrue(decompilerManagerConfig.getCacheDecompilations().getValue(), "Cache config not 'true'");
		DecompileResult newResult = assertDoesNotThrow(() -> decompilerManager.decompile(decompiler, workspace, classHelloWorld).get(1, TimeUnit.SECONDS));
		assertSame(firstResult, newResult, "Decompiler did not cache results");

		// Change the decompiler hash. The decompiler result should change.
		decompiler.getConfig().setHash(-1);
		newResult = assertDoesNotThrow(() -> decompilerManager.decompile(decompiler, workspace, classHelloWorld).get(1, TimeUnit.SECONDS));
		assertNotSame(firstResult, newResult, "Decompiler used cached result even though config hash changed");

		// Verify direct decompiler usage does not cache
		DecompileResult direct1 = decompiler.decompile(workspace, classHelloWorld);
		DecompileResult direct2 = decompiler.decompile(workspace, classHelloWorld);
		assertNotSame(direct1, direct2, "Direct decompiler use cached results unexpectedly");
	}

	@Test
	void testCacheHitSkipsDecompilerWork() throws IOException {
		decompilerManagerConfig.getCacheDecompilations().setValue(true);
		JvmClassInfo target = TestClassUtils.fromRuntimeClass(HelloWorld.class);
		TestJvmDecompiler decompiler = new TestJvmDecompiler("test-cache-hit", null,
				run -> new DecompileResult("// run " + run, 0));

		DecompileResult first = decompile(decompiler, target);
		DecompileResult second = decompile(decompiler, target);
		assertSame(first, second, "Cached result was not reused");
		assertEquals(1, decompiler.getInvocations(), "Cache hit still ran the decompiler");

		// Read-only reuses the existing entry.
		DecompileResult readOnly = decompile(decompiler, target, DecompileCacheMode.READ_ONLY);
		assertSame(first, readOnly, "Read-only mode did not reuse the cached result");
		assertEquals(1, decompiler.getInvocations(), "Read-only mode still ran the decompiler");

		// Disabling the cache for a single request bypasses it without evicting the existing entry.
		DecompileResult uncached = decompile(decompiler, target, DecompileCacheMode.NONE);
		assertNotSame(first, uncached, "Cache was used even though the request opted out");
		assertEquals(2, decompiler.getInvocations(), "Expected a fresh decompilation");
		assertSame(first, decompile(decompiler, target), "Opting out of the cache overwrote the existing entry");
	}

	@Test
	void testConfigChangeOnlyVoidsTheAffectedDecompiler() throws IOException {
		decompilerManagerConfig.getCacheDecompilations().setValue(true);
		JvmClassInfo target = TestClassUtils.fromRuntimeClass(HelloWorld.class);
		TestJvmDecompiler changing = new TestJvmDecompiler("test-config-changing", null,
				run -> new DecompileResult("// changing " + run, 0));
		TestJvmDecompiler stable = new TestJvmDecompiler("test-config-stable", null,
				run -> new DecompileResult("// stable " + run, 0));

		decompile(changing, target);
		DecompileResult stableResult = decompile(stable, target);

		changing.getConfig().setHash(-1);
		decompile(changing, target);
		assertEquals(2, changing.getInvocations(), "Expected a fresh decompilation after the config changed");
		assertSame(stableResult, decompile(stable, target), "Unrelated decompiler lost its cached result");
		assertEquals(1, stable.getInvocations(), "Unrelated decompiler was re-run");
	}

	@Test
	void testCacheHitIsServedOnTheCallingThread() throws Exception {
		DecompilerManagerConfig config = new DecompilerManagerConfig();
		config.getCacheDecompilations().setValue(true);
		@SuppressWarnings("unchecked")
		Instance<Decompiler> implementations = mock(Instance.class);
		when(implementations.iterator()).thenReturn(Collections.emptyIterator());
		DecompilerManager manager = new DecompilerManager(config, implementations);

		// Populate the cache for our target class.
		JvmClassInfo target = TestClassUtils.fromRuntimeClass(HelloWorld.class);
		TestJvmDecompiler cachedDecompiler = new TestJvmDecompiler("test-cache-hit-thread", null,
				run -> new DecompileResult("// cached", 0));
		DecompileResult cached = manager.decompile(cachedDecompiler, workspace, target).get(10, TimeUnit.SECONDS);

		// Occupy every thread of the interactive pool with a decompilation that will not finish until released.
		int workerCount = DecompileParallelism.decompileThreads();
		CountDownLatch blockersStarted = new CountDownLatch(workerCount);
		CountDownLatch releaseBlockers = new CountDownLatch(1);
		TestJvmDecompiler blockingDecompiler = new TestJvmDecompiler("test-cache-hit-blocker",
				blockersStarted, releaseBlockers, run -> new DecompileResult("// blocked " + run, 0));
		List<CompletableFuture<DecompileResult>> blocked = new ArrayList<>();
		try {
			for (int i = 0; i < workerCount; i++)
				blocked.add(manager.decompile(blockingDecompiler, workspace, TestClassUtils.fromRuntimeClass(HelloWorld.class)));
			assertTrue(blockersStarted.await(10, TimeUnit.SECONDS), "Interactive pool was never saturated");

			// With every worker busy the cache hit can only be complete already if it never touched the pool.
			CompletableFuture<DecompileResult> future = manager.decompile(cachedDecompiler, workspace, target);
			assertTrue(future.isDone(), "Cache hit was queued onto the decompile thread pool");
			assertSame(cached, future.getNow(null), "Cache hit did not yield the cached result");
			assertEquals(1, cachedDecompiler.getInvocations(), "Cache hit ran the decompiler again");
		} finally {
			releaseBlockers.countDown();
		}

		for (CompletableFuture<DecompileResult> future : blocked)
			future.get(10, TimeUnit.SECONDS);
	}

	@Test
	void testCacheHitIsFarFasterThanDecompiling() throws IOException {
		decompilerManagerConfig.getCacheDecompilations().setValue(true);
		JvmDecompiler decompiler = decompilerManager.getJvmDecompiler(CfrDecompiler.NAME);
		assertNotNull(decompiler, "CFR decompiler was never registered with manager");

		// Warm up on throwaway copies of the class so the cold measurement below is not paying for
		// one-time class loading and JIT costs, which would make the comparison flattering for the cache.
		for (int i = 0; i < 3; i++)
			decompile(decompiler, TestClassUtils.fromRuntimeClass(HelloWorld.class));

		JvmClassInfo target = TestClassUtils.fromRuntimeClass(HelloWorld.class);
		long coldStart = System.nanoTime();
		DecompileResult cold = decompile(decompiler, target);
		long coldNanos = System.nanoTime() - coldStart;

		long hotNanos = Long.MAX_VALUE;
		for (int i = 0; i < 10; i++) {
			long hotStart = System.nanoTime();
			DecompileResult hot = decompile(decompiler, target);
			hotNanos = Math.min(hotNanos, System.nanoTime() - hotStart);
			assertSame(cold, hot, "Repeat decompilation did not come from the cache");
		}

		// A generous margin, since all we want to catch is the cache silently not being used at all.
		long finalHotNanos = hotNanos;
		assertTrue(hotNanos * 5 < coldNanos, () -> "Cache hit was not meaningfully faster than decompiling: hit took "
				+ finalHotNanos + "ns, cold decompilation took " + coldNanos + "ns");
	}

	@Test
	void testConcurrentRequestsAreMerged() throws Exception {
		JvmClassInfo target = TestClassUtils.fromRuntimeClass(HelloWorld.class);
		int requests = 10;
		CountDownLatch gate = new CountDownLatch(1);
		TestJvmDecompiler decompiler = new TestJvmDecompiler("test-in-flight", gate,
				run -> new DecompileResult("// run " + run, 0));

		// Fire all the requests off from separate threads at the same time. The decompiler is held open by the
		// gate for the duration, so every request should latch onto the same in-flight decompilation.
		ExecutorService submitters = Executors.newFixedThreadPool(requests);
		CountDownLatch submittersReady = new CountDownLatch(requests);
		CountDownLatch startSubmitting = new CountDownLatch(1);
		List<CompletableFuture<DecompileResult>> futures = new ArrayList<>();
		try {
			List<Future<CompletableFuture<DecompileResult>>> submissions = new ArrayList<>();
			for (int i = 0; i < requests; i++)
				submissions.add(submitters.submit(() -> {
					submittersReady.countDown();
					assertTrue(startSubmitting.await(10, TimeUnit.SECONDS), "Submitter was never started");
					return decompilerManager.decompile(decompiler, workspace, target);
				}));
			assertTrue(submittersReady.await(10, TimeUnit.SECONDS), "Submitter threads did not all start");
			startSubmitting.countDown();
			for (Future<CompletableFuture<DecompileResult>> submission : submissions)
				futures.add(submission.get(10, TimeUnit.SECONDS));
		} finally {
			gate.countDown();
			submitters.shutdownNow();
		}

		DecompileResult expected = assertDoesNotThrow(() -> futures.getFirst().get(10, TimeUnit.SECONDS));
		for (CompletableFuture<DecompileResult> future : futures)
			assertSame(expected, assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS)),
					"Merged requests yielded differing results");
		assertEquals(1, decompiler.getInvocations(), "Concurrent requests were not merged into one decompilation");
	}

	@Test
	void testBatchCacheModesDoNotStarveInteractiveRequests() throws Exception {
		assertBatchModeDoesNotStarveInteractiveRequest(DecompileCacheMode.NONE);
		assertBatchModeDoesNotStarveInteractiveRequest(DecompileCacheMode.READ_ONLY);
	}

	@Test
	void testFailureResultIsNotFilteredIntoSuccess() throws IOException {
		JvmClassInfo target = TestClassUtils.fromRuntimeClass(HelloWorld.class);
		RuntimeException failure = new RuntimeException("Intentional failure");
		TestJvmDecompiler decompiler = new TestJvmDecompiler("test-failure", null,
				run -> new DecompileResult(failure, 0));
		TestOutputTextFilter textFilterSpy = spy(textFilter);
		try {
			decompilerManager.addOutputTextFilter(textFilterSpy);

			DecompileResult result = decompile(decompiler, target);
			assertEquals(DecompileResult.ResultType.FAILURE, result.getType(), "Failure was re-wrapped as a success");
			assertSame(failure, result.getException(), "Failure reason was dropped");
			verify(textFilterSpy, never()).filter(any(), any(), anyString());
		} finally {
			decompilerManager.removeOutputTextFilter(textFilterSpy);
		}
	}

	@Test
	void testFilterHollow() {
		String decompilationBefore = assertDoesNotThrow(() -> decompilerManager.decompile(workspace, classHelloWorld).get().getText());
		assertTrue(decompilationBefore.contains("\"Hello world\""));

		decompilerManagerConfig.getFilterHollow().setValue(true);

		// Hollowing will remove method bodies, so the 'println' call should no longer exist in the output
		String decompilationAfter = assertDoesNotThrow(() -> decompilerManager.decompile(workspace, classHelloWorld).get().getText());
		assertFalse(decompilationAfter.contains("\"Hello world\""));
	}

	@Test
	void testDisplay() {
		for (JvmDecompiler decompiler : decompilerManager.getJvmDecompilers()) {
			assertTrue(decompiler.toString().contains(decompiler.getName()));
			assertTrue(decompiler.toString().contains(decompiler.getVersion()));
		}
	}

	@Test
	void testComparison() {
		JvmDecompiler cfr = decompilerManager.getJvmDecompiler(CfrDecompiler.NAME);
		JvmDecompiler pro = decompilerManager.getJvmDecompiler(ProcyonDecompiler.NAME);
		assertNotNull(cfr);
		assertNotNull(pro);
		assertNotEquals(cfr, pro);
		assertNotEquals(cfr.hashCode(), pro.hashCode());
	}

	@Nonnull
	private static DecompileResult decompile(@Nonnull JvmDecompiler decompiler, @Nonnull JvmClassInfo classInfo) {
		return assertDoesNotThrow(() -> decompilerManager.decompile(decompiler, workspace, classInfo).get(10, TimeUnit.SECONDS));
	}

	@Nonnull
	private static DecompileResult decompile(@Nonnull JvmDecompiler decompiler, @Nonnull JvmClassInfo classInfo,
	                                         @Nonnull DecompileCacheMode cacheMode) {
		return assertDoesNotThrow(() -> decompilerManager.decompile(decompiler, workspace, classInfo, cacheMode).get(10, TimeUnit.SECONDS));
	}

	private static void assertBatchModeDoesNotStarveInteractiveRequest(@Nonnull DecompileCacheMode batchMode) throws Exception {
		DecompilerManagerConfig config = new DecompilerManagerConfig();
		config.getCacheDecompilations().setValue(false);
		@SuppressWarnings("unchecked")
		Instance<Decompiler> implementations = mock(Instance.class);
		when(implementations.iterator()).thenReturn(Collections.emptyIterator());
		DecompilerManager manager = new DecompilerManager(config, implementations);

		int workerCount = DecompileParallelism.decompileThreads();
		CountDownLatch batchStarted = new CountDownLatch(workerCount);
		CountDownLatch releaseBatch = new CountDownLatch(1);
		TestJvmDecompiler batchDecompiler = new TestJvmDecompiler("test-batch-" + batchMode,
				batchStarted, releaseBatch, run -> new DecompileResult("// batch " + run, 0));
		List<CompletableFuture<DecompileResult>> batchFutures = new ArrayList<>();
		for (int i = 0; i < workerCount; i++) {
			JvmClassInfo target = TestClassUtils.fromRuntimeClass(HelloWorld.class);
			batchFutures.add(manager.decompile(batchDecompiler, workspace, target, batchMode));
		}

		try {
			assertTrue(batchStarted.await(10, TimeUnit.SECONDS), "Batch pool was not saturated by " + batchMode);

			// The default API is the interactive path even when global caching is disabled and its effective mode is NONE.
			TestJvmDecompiler interactiveDecompiler = new TestJvmDecompiler("test-interactive-" + batchMode, null,
					run -> new DecompileResult("// interactive", 0));
			JvmClassInfo interactiveTarget = TestClassUtils.fromRuntimeClass(HelloWorld.class);
			DecompileResult result = manager.decompile(interactiveDecompiler, workspace, interactiveTarget)
					.get(2, TimeUnit.SECONDS);
			assertEquals("// interactive", result.getText(),
					"Interactive request was starved by " + batchMode + " requests");
		} finally {
			releaseBatch.countDown();
		}

		for (CompletableFuture<DecompileResult> future : batchFutures)
			future.get(10, TimeUnit.SECONDS);
	}

	private static void runJvmDecompilation(@Nonnull JvmDecompiler decompiler) {
		try {
			// Generally, you'd handle results like this, with a when-complete.
			// The blocking 'get' at the end is just so our test works.
			DecompileResult firstResult = decompilerManager.decompile(decompiler, workspace, classHelloWorld)
					.whenComplete((result, throwable) -> {
						assertNull(throwable);

						// Throwable thrown when unhandled exception occurs.
						assertEquals(DecompileResult.ResultType.SUCCESS, result.getType(), "Decompile result was not successful");
						assertNotNull(result.getText(), "Decompile result missing text");
						assertTrue(result.getText().contains("\"Hello world\""), "Decompilation seems to be wrong");
					}) // Block on this thread until we have the value.
					.get(5, TimeUnit.SECONDS);

			// Verify direct decompiler usage does not cache
			DecompileResult result = decompiler.decompile(workspace, classHelloWorld);
			assertNull(result.getException(), "No exceptions should be reported during decompilation");
			assertNotNull(result.getText(), "Missing decompilation output");
		} catch (InterruptedException e) {
			fail("Decompile was interrupted", e);
		} catch (ExecutionException e) {
			fail("Decompile was encountered exception", e.getCause());
		} catch (TimeoutException e) {
			fail("Decompile timed out", e);
		}
	}

	/**
	 * Decompiler that records how often it actually ran, and can be held open to model a slow decompilation.
	 */
	static class TestJvmDecompiler extends AbstractJvmDecompiler {
		private final AtomicInteger invocations = new AtomicInteger();
		private final CountDownLatch started;
		private final CountDownLatch gate;
		private final IntFunction<DecompileResult> resultSupplier;

		TestJvmDecompiler(@Nonnull String name, @Nullable CountDownLatch gate,
		                  @Nonnull IntFunction<DecompileResult> resultSupplier) {
			this(name, null, gate, resultSupplier);
		}

		TestJvmDecompiler(@Nonnull String name, @Nullable CountDownLatch started, @Nullable CountDownLatch gate,
		                  @Nonnull IntFunction<DecompileResult> resultSupplier) {
			super(name, "1.0", new BaseDecompilerConfig(name + "-config"));
			this.started = started;
			this.gate = gate;
			this.resultSupplier = resultSupplier;
		}

		int getInvocations() {
			return invocations.get();
		}

		@Nonnull
		@Override
		protected DecompileResult decompileInternal(@Nonnull Workspace workspace, @Nonnull JvmClassInfo classInfo) {
			int invocation = invocations.incrementAndGet();
			if (started != null)
				started.countDown();
			if (gate != null) {
				try {
					if (!gate.await(10, TimeUnit.SECONDS))
						throw new IllegalStateException("Timed out waiting for the test gate to open");
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(ex);
				}
			}
			return resultSupplier.apply(invocation);
		}
	}

	static class TestJvmBytecodeFilter implements JvmBytecodeFilter {
		@Nonnull
		@Override
		public byte[] filter(@Nonnull Workspace workspace, @Nonnull JvmClassInfo initialClassInfo, @Nonnull byte[] bytecode) {
			return bytecode;
		}
	}

	static class TestOutputTextFilter implements OutputTextFilter {
		@Nonnull
		@Override
		public String filter(@Nonnull Workspace workspace, @Nonnull ClassInfo classInfo, @Nonnull String code) {
			return code;
		}
	}
}
