package software.coley.recaf.services.decompile.batch;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link BatchDecompileScheduler}.
 */
class BatchDecompileSchedulerTest {
	@Test
	void orderedResultsFollowSubmissionOrderDespiteRandomCompletion() throws InterruptedException {
		List<Integer> items = IntStream.range(0, 200).boxed().toList();
		List<Integer> completed = new ArrayList<>();
		try (BatchDecompileScheduler scheduler = new BatchDecompileScheduler(4, 2)) {
			scheduler.runOrdered(items,
					item -> CompletableFuture.supplyAsync(() -> {
						sleepBriefly();
						return item * 2;
					}),
					(item, value, error) -> {
						assertNull(error);
						assertNotNull(value);
						assertEquals(item * 2, value);
						completed.add(item);
					});
		}
		assertEquals(items, completed, "Ordered completion did not follow submission order");
	}

	@Test
	void orderedSchedulingBoundsInFlightWork() throws InterruptedException {
		AtomicInteger inFlight = new AtomicInteger();
		AtomicInteger peak = new AtomicInteger();
		List<Integer> items = IntStream.range(0, 500).boxed().toList();
		try (BatchDecompileScheduler scheduler = new BatchDecompileScheduler(4, 2)) {
			int limit = scheduler.classInFlight();
			scheduler.runOrdered(items,
					item -> {
						peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
						return CompletableFuture.supplyAsync(() -> {
							sleepBriefly();
							return item;
						});
					},
					(item, value, error) -> inFlight.decrementAndGet());
			assertTrue(peak.get() <= limit,
					"In-flight work peaked at " + peak.get() + " with a limit of " + limit);
		}
	}

	@Test
	void orderedCompletionSeesFailures() throws InterruptedException {
		List<Integer> items = List.of(0, 1, 2);
		List<String> outcomes = new ArrayList<>();
		try (BatchDecompileScheduler scheduler = new BatchDecompileScheduler(2, 1)) {
			scheduler.runOrdered(items,
					item -> item == 1
							? CompletableFuture.failedFuture(new IllegalStateException("boom"))
							: CompletableFuture.completedFuture(item),
					(item, value, error) -> outcomes.add(error == null ? "ok:" + value : "err:" + error.getMessage()));
		}
		assertEquals(List.of("ok:0", "err:boom", "ok:2"), outcomes);
	}

	@Test
	void budgetOnlyStartsWhenTheItemDoes() throws InterruptedException {
		// One worker, so every item after the first spends real time queued. A budget that started at
		// submission time would have been eaten by that wait and the last items would report timeouts.
		List<Integer> items = IntStream.range(0, 6).boxed().toList();
		List<Throwable> errors = new CopyOnWriteArrayList<>();
		try (BatchDecompileScheduler scheduler = new BatchDecompileScheduler(1, 1)) {
			scheduler.runBudgeted(items, 2_000,
					(item, budget) -> {
						assertTrue(budget.isStarted(), "Budget was not started inside the task body");
						assertTrue(budget.remainingMillis() > 1_500,
								"Item " + item + " only had " + budget.remainingMillis() + "ms left on entry");
						Thread.sleep(200);
						return item;
					},
					(item, value, error) -> {
						if (error != null) errors.add(error);
					});
		}
		assertTrue(errors.isEmpty(), "Queued items were charged for their wait: " + errors);
	}

	@Test
	void budgetedWorkStillReportsFailuresInOrder() throws InterruptedException {
		List<Integer> items = List.of(0, 1, 2);
		List<String> outcomes = new ArrayList<>();
		try (BatchDecompileScheduler scheduler = new BatchDecompileScheduler(2, 1)) {
			scheduler.runBudgeted(items, 5_000,
					(item, budget) -> {
						if (item == 1) throw new IllegalStateException("boom");
						return item;
					},
					(item, value, error) -> outcomes.add(error == null ? "ok:" + value : "err:" + error.getMessage()));
		}
		assertEquals(List.of("ok:0", "err:boom", "ok:2"), outcomes);
	}

	@Test
	void closingCancelsWorkThatIsStillOutstanding() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		BatchDecompileScheduler scheduler = new BatchDecompileScheduler(1, 1);
		Thread runner = new Thread(() -> {
			try {
				scheduler.runBudgeted(IntStream.range(0, 32).boxed().toList(), 60_000,
						(item, budget) -> {
							entered.countDown();
							try {
								Thread.sleep(60_000);
							} catch (InterruptedException ex) {
								interrupted.countDown();
								throw ex;
							}
							return item;
						},
						(item, value, error) -> {});
			} catch (InterruptedException ignored) {
				// Expected once the scheduler is torn down.
			}
		});
		runner.start();
		assertTrue(entered.await(10, TimeUnit.SECONDS), "Work never started");

		scheduler.close();
		runner.join(TimeUnit.SECONDS.toMillis(20));
		assertFalse(runner.isAlive(), "Scheduler shutdown left the caller blocked");
		assertTrue(interrupted.await(20, TimeUnit.SECONDS),
				"Work already running was left to finish on its own after shutdown");
	}

	@Test
	void ioWorkRunsEveryItemAndReportsFailures() throws InterruptedException {
		List<Integer> items = IntStream.range(0, 64).boxed().toList();
		List<Integer> done = new CopyOnWriteArrayList<>();
		List<Integer> failed = new CopyOnWriteArrayList<>();
		try (BatchDecompileScheduler scheduler = new BatchDecompileScheduler(4, 2)) {
			scheduler.runIo(items, item -> {
				if (item % 8 == 0)
					throw new IllegalStateException("boom");
				done.add(item);
			}, (item, error) -> failed.add(item));
		}
		assertEquals(56, done.size());
		assertEquals(8, failed.size());
	}

	private static void sleepBriefly() {
		try {
			Thread.sleep(ThreadLocalRandom.current().nextInt(3));
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
