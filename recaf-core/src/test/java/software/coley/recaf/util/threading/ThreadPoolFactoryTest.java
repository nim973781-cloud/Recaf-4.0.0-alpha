package software.coley.recaf.util.threading;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ThreadPoolFactory}.
 */
class ThreadPoolFactoryTest {
	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void anExplicitSizeIsHonoredEvenAboveTheCpuCount() throws Exception {
		// The factory used to clamp explicit sizes to a CPU-derived maximum, which starved pools whose
		// threads sit blocked rather than burning CPU.
		int requested = Runtime.getRuntime().availableProcessors() + 8;
		CountDownLatch running = new CountDownLatch(requested);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService pool = ThreadPoolFactory.newFixedThreadPool("test-size", requested, true);
		try {
			for (int i = 0; i < requested; i++) {
				pool.execute(() -> {
					running.countDown();
					try {
						release.await();
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
				});
			}
			assertTrue(running.await(20, TimeUnit.SECONDS),
					"Only " + (requested - running.getCount()) + " of " + requested + " tasks got a thread");
		} finally {
			release.countDown();
			pool.shutdownNow();
		}
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void queuedWorkRunsInPriorityOrder() throws Exception {
		CountDownLatch blocked = new CountDownLatch(1);
		CountDownLatch occupied = new CountDownLatch(1);
		List<Integer> order = new CopyOnWriteArrayList<>();
		CountDownLatch done = new CountDownLatch(4);
		ExecutorService pool = ThreadPoolFactory.newPriorityPool("test-priority", 1, true);
		try {
			// Occupy the only worker so the rest of the work has to queue up behind it.
			pool.execute(() -> {
				occupied.countDown();
				try {
					blocked.await();
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			});
			assertTrue(occupied.await(20, TimeUnit.SECONDS));

			for (int priority : new int[]{5, 1, 9, 3}) {
				pool.execute(ThreadPoolFactory.prioritized(priority, () -> {
					order.add(priority);
					done.countDown();
				}));
			}
			blocked.countDown();
			assertTrue(done.await(20, TimeUnit.SECONDS));
		} finally {
			pool.shutdownNow();
		}
		assertEquals(List.of(1, 3, 5, 9), order);
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void submittedWorkAlsoCarriesItsPriority() throws Exception {
		CountDownLatch blocked = new CountDownLatch(1);
		CountDownLatch occupied = new CountDownLatch(1);
		List<Integer> order = new CopyOnWriteArrayList<>();
		ExecutorService pool = ThreadPoolFactory.newPriorityPool("test-priority-submit", 1, true);
		try {
			pool.execute(() -> {
				occupied.countDown();
				try {
					blocked.await();
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			});
			assertTrue(occupied.await(20, TimeUnit.SECONDS));

			var last = pool.submit(ThreadPoolFactory.prioritized(7, () -> order.add(7)));
			pool.submit(ThreadPoolFactory.prioritized(2, () -> order.add(2)));
			blocked.countDown();
			last.get(20, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
		assertEquals(List.of(2, 7), order);
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void unprioritizedWorkKeepsSubmissionOrder() throws Exception {
		AtomicInteger next = new AtomicInteger();
		List<Integer> order = new CopyOnWriteArrayList<>();
		CountDownLatch blocked = new CountDownLatch(1);
		CountDownLatch occupied = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(16);
		ExecutorService pool = ThreadPoolFactory.newPriorityPool("test-priority-fifo", 1, true);
		try {
			pool.execute(() -> {
				occupied.countDown();
				try {
					blocked.await();
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			});
			assertTrue(occupied.await(20, TimeUnit.SECONDS));

			for (int i = 0; i < 16; i++) {
				int value = next.getAndIncrement();
				pool.execute(() -> {
					order.add(value);
					done.countDown();
				});
			}
			blocked.countDown();
			assertTrue(done.await(20, TimeUnit.SECONDS));
		} finally {
			pool.shutdownNow();
		}
		assertEquals(java.util.stream.IntStream.range(0, 16).boxed().toList(), order);
	}

	@Test
	void aPriorityPoolIsStillAThreadPoolExecutor() {
		ExecutorService pool = ThreadPoolFactory.newPriorityPool("test-priority-type", 2, true);
		try {
			assertTrue(pool instanceof ThreadPoolExecutor);
			assertEquals(2, ((ThreadPoolExecutor) pool).getCorePoolSize());
		} finally {
			pool.shutdownNow();
		}
	}
}
