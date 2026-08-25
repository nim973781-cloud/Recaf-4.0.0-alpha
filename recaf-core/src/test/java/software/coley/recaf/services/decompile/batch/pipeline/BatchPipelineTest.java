package software.coley.recaf.services.decompile.batch.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link BatchPipeline} and the {@link BoundedStageQueue} it hands items over on.
 */
class BatchPipelineTest {
	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void itemsComeBackInInputOrder() throws InterruptedException {
		List<Integer> inputs = IntStream.range(0, 64).boxed().toList();
		List<Integer> seen = new ArrayList<>();
		try (BatchPipeline<Integer, String> pipeline =
				     BatchPipeline.prefetch("test-order", inputs, input -> "v" + input, 4)) {
			BatchPipeline.Item<Integer, String> item;
			while ((item = pipeline.next()) != null) {
				assertTrue(item.isOk());
				assertEquals("v" + item.input(), item.value());
				seen.add(item.input());
			}
		}
		assertEquals(inputs, seen);
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void aFailingItemDoesNotEndThePipeline() throws InterruptedException {
		List<Integer> inputs = List.of(0, 1, 2, 3);
		List<String> outcomes = new ArrayList<>();
		try (BatchPipeline<Integer, String> pipeline = BatchPipeline.prefetch("test-failure", inputs, input -> {
			if (input == 1)
				throw new IllegalStateException("boom");
			return "v" + input;
		}, 2)) {
			BatchPipeline.Item<Integer, String> item;
			while ((item = pipeline.next()) != null)
				outcomes.add(item.error() == null ? item.value() : "err:" + item.error().getMessage());
		}
		assertEquals(List.of("v0", "err:boom", "v2", "v3"), outcomes);
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void theStageRunsAheadOfTheConsumerButOnlyToTheGivenDepth() throws InterruptedException {
		int depth = 2;
		AtomicInteger produced = new AtomicInteger();
		List<Integer> inputs = IntStream.range(0, 32).boxed().toList();
		try (BatchPipeline<Integer, Integer> pipeline = BatchPipeline.prefetch("test-depth", inputs, input -> {
			produced.incrementAndGet();
			return input;
		}, depth)) {
			BatchPipeline.Item<Integer, Integer> first = pipeline.next();
			assertNotNull(first);
			// The stage keeps working while the consumer sits still, but the bounded queue stops it from
			// running away with the whole input.
			Thread.sleep(200);
			assertTrue(produced.get() > 1, "The stage never ran ahead of the consumer");
			assertTrue(produced.get() <= depth + 2,
					"The stage ran " + produced.get() + " items ahead of a depth of " + depth);

			int drained = 1;
			while (pipeline.next() != null)
				drained++;
			assertEquals(inputs.size(), drained);
		}
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void abandonedItemsAreHandedToTheDiscardHandler() throws InterruptedException {
		List<Integer> inputs = IntStream.range(0, 32).boxed().toList();
		List<Integer> discarded = new CopyOnWriteArrayList<>();
		BatchPipeline<Integer, Integer> pipeline =
				BatchPipeline.prefetch("test-discard", inputs, input -> input, 4, discarded::add);
		assertNotNull(pipeline.next());
		// Let the stage fill its buffer, then walk away from it the way an aborted run would.
		Thread.sleep(200);
		pipeline.close();

		assertFalse(discarded.isEmpty(), "Buffered items were dropped without being released");
		for (Integer value : discarded)
			assertTrue(inputs.contains(value));
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void queueHandsProducerFailuresToTheConsumer() throws InterruptedException {
		BoundedStageQueue<String> queue = new BoundedStageQueue<>(2);
		assertTrue(queue.put("a"));
		IllegalStateException failure = new IllegalStateException("producer died");
		queue.fail(failure);

		assertEquals("a", queue.take());
		BoundedStageQueue.PipelineException thrown =
				org.junit.jupiter.api.Assertions.assertThrows(BoundedStageQueue.PipelineException.class, queue::take);
		assertSame(failure, thrown.getCause());
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void queueBlocksTheProducerWhileFull() throws Exception {
		BoundedStageQueue<Integer> queue = new BoundedStageQueue<>(1);
		CountDownLatch blocked = new CountDownLatch(1);
		AtomicInteger put = new AtomicInteger();
		Thread producer = new Thread(() -> {
			try {
				queue.put(1);
				put.incrementAndGet();
				blocked.countDown();
				queue.put(2);
				put.incrementAndGet();
				queue.complete();
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		producer.start();
		assertTrue(blocked.await(10, TimeUnit.SECONDS));
		Thread.sleep(100);
		assertEquals(1, put.get(), "The producer ran past the queue capacity");

		assertEquals(1, queue.take());
		assertEquals(2, queue.take());
		assertNull(queue.take());
		producer.join(TimeUnit.SECONDS.toMillis(10));
		assertFalse(producer.isAlive());
	}
}
