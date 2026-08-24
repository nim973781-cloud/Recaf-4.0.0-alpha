package software.coley.recaf.services.decompile.batch;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ThrottledProgressListener}.
 */
class ThrottledProgressListenerTest {
	@Test
	void classStepLetsEveryNthEventThrough() {
		RecordingProgressListener delegate = new RecordingProgressListener();
		ThrottledProgressListener throttle = new ThrottledProgressListener(delegate, Duration.ofDays(1), 64);

		for (int i = 1; i <= 256; i++)
			throttle.onProgress(progress(i));

		// The first event always passes, then one per 64 completed classes.
		List<Integer> forwarded = delegate.events().stream()
				.map(BatchDecompileProgress::completedClasses)
				.toList();
		assertEquals(List.of(1, 65, 129, 193), forwarded, "Throttle did not step by 64 completed classes");
	}

	@Test
	void timeWindowLetsEventsThroughWithoutClassProgress() throws InterruptedException {
		RecordingProgressListener delegate = new RecordingProgressListener();
		ThrottledProgressListener throttle = new ThrottledProgressListener(delegate,
				Duration.ofMillis(20), Integer.MAX_VALUE);

		throttle.onProgress(progress(1));
		throttle.onProgress(progress(1));
		assertEquals(1, delegate.events().size(), "Second event should have been throttled");

		Thread.sleep(40);
		throttle.onProgress(progress(1));
		assertEquals(2, delegate.events().size(), "Event after the time window should pass");
	}

	@Test
	void flushAlwaysForwards() {
		RecordingProgressListener delegate = new RecordingProgressListener();
		ThrottledProgressListener throttle = new ThrottledProgressListener(delegate,
				Duration.ofDays(1), Integer.MAX_VALUE);

		throttle.onProgress(progress(1));
		throttle.onProgress(progress(2));
		throttle.flush(progress(3));

		assertEquals(2, delegate.events().size());
		assertEquals(3, delegate.events().getLast().completedClasses());
	}

	@Test
	void defaultsMatchThePlannedThrottle() {
		assertEquals(100, ThrottledProgressListener.DEFAULT_INTERVAL.toMillis());
		assertEquals(64, ThrottledProgressListener.DEFAULT_CLASS_STEP);
		assertTrue(ThrottledProgressListener.DEFAULT_CLASS_STEP > 0);
	}

	private static BatchDecompileProgress progress(int completedClasses) {
		return new BatchDecompileProgress(1, 0, 512, completedClasses, completedClasses, 0, 0,
				"sample.jar", "com/example/Foo", completedClasses / 512d);
	}
}
