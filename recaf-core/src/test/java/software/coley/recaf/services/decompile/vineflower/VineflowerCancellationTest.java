package software.coley.recaf.services.decompile.vineflower;

import org.jetbrains.java.decompiler.main.decompiler.CancelationManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link VineflowerCancellation}, the only handle Recaf has on the worker threads a Vineflower
 * context creates for itself.
 * <p>
 * Scopes are opened on threads this test owns rather than on the thread JUnit runs it on. A token reaches
 * every thread started while its scope is open, and the shared JUnit pool grows on demand, so opening a
 * scope on a JUnit thread could hand the token to an unrelated test.
 */
class VineflowerCancellationTest {
	/**
	 * Vineflower creates its context pool from the thread that opened the context, which is what carries
	 * the token into workers Recaf never sees.
	 */
	@Test
	void workersStartedInsideAScopeStopWhenItIsCancelled() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch cancelled = new CountDownLatch(1);
		AtomicReference<Throwable> beforeCancel = new AtomicReference<>();
		AtomicReference<Throwable> afterCancel = new AtomicReference<>();
		AtomicReference<Thread> workerRef = new AtomicReference<>();

		onOwnThread("scope-owner", () -> {
			try (VineflowerCancellation.Token token = VineflowerCancellation.begin()) {
				Thread worker = new Thread(() -> {
					try {
						CancelationManager.checkCanceled();
					} catch (Throwable t) {
						beforeCancel.set(t);
					}
					started.countDown();
					try {
						cancelled.await();
						CancelationManager.checkCanceled();
					} catch (Throwable t) {
						afterCancel.set(t);
					}
				}, "vineflower-cancellation-test-worker");
				worker.setDaemon(true);
				worker.start();
				workerRef.set(worker);
				assertTrue(started.await(5, TimeUnit.SECONDS), "Worker never reached the first check");
				token.cancel();
			}
		});
		cancelled.countDown();
		workerRef.get().join(TimeUnit.SECONDS.toMillis(5));

		assertNull(beforeCancel.get(), "A live scope must not cancel its workers");
		assertInstanceOf(CancelationManager.CanceledException.class, afterCancel.get(),
				"A cancelled scope must unwind its workers");
	}

	/**
	 * The checker is a single global inside Vineflower, so every caller that did not open a scope has to
	 * be unaffected by it. That is what keeps the interactive and {@code ACCURATE} paths out of this.
	 */
	@Test
	void threadsWithoutAScopeAreNeverCancelled() throws Exception {
		AtomicReference<Throwable> owner = new AtomicReference<>();
		AtomicReference<Throwable> outsider = new AtomicReference<>();

		onOwnThread("closed-scope-owner", () -> {
			try (VineflowerCancellation.Token token = VineflowerCancellation.begin()) {
				token.cancel();
			}
			try {
				CancelationManager.checkCanceled();
			} catch (Throwable t) {
				owner.set(t);
			}
		});
		onOwnThread("scope-free", () -> {
			try {
				CancelationManager.checkCanceled();
			} catch (Throwable t) {
				outsider.set(t);
			}
		});

		assertNull(owner.get(), "A closed scope must not leave its owner cancelled");
		assertNull(outsider.get(), "A thread outside every scope must never be cancelled");
		assertDoesNotThrow(CancelationManager::checkCanceled);
	}

	private static void onOwnThread(String name, ThrowingRunnable body) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread thread = new Thread(() -> {
			try {
				body.run();
			} catch (Throwable t) {
				failure.set(t);
			}
		}, name);
		thread.setDaemon(true);
		thread.start();
		thread.join(TimeUnit.SECONDS.toMillis(15));
		if (failure.get() instanceof Exception ex)
			throw ex;
		if (failure.get() != null)
			throw new AssertionError(failure.get());
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
