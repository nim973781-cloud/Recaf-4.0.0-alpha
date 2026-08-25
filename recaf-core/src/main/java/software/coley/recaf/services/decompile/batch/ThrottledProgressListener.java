package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Wraps a {@link BatchDecompileProgressListener} so that a fast batch run cannot flood the delegate.
 * <p>
 * An event is forwarded when either the {@link #DEFAULT_INTERVAL time window} has elapsed or
 * {@link #DEFAULT_CLASS_STEP enough classes} have completed since the last forwarded event.
 * Milestones the caller must not lose <i>(run start, JAR completion, run completion)</i> should be
 * passed to {@link #flush(BatchDecompileProgress)}.
 *
 * @author Matt Coley
 */
public final class ThrottledProgressListener implements BatchDecompileProgressListener {
	/** Default minimum time between forwarded events. */
	public static final Duration DEFAULT_INTERVAL = Duration.ofMillis(100);
	/** Default minimum number of completed classes between forwarded events. */
	public static final int DEFAULT_CLASS_STEP = 64;

	private final BatchDecompileProgressListener delegate;
	private final long intervalNanos;
	private final int classStep;
	private long lastEmitNanos;
	private int lastEmitCompleted;
	private int suppressed;
	private boolean emitted;

	/**
	 * @param delegate
	 * 		Listener to forward throttled events to.
	 */
	public ThrottledProgressListener(@Nonnull BatchDecompileProgressListener delegate) {
		this(delegate, DEFAULT_INTERVAL, DEFAULT_CLASS_STEP);
	}

	/**
	 * @param delegate
	 * 		Listener to forward throttled events to.
	 * @param interval
	 * 		Minimum time between forwarded events.
	 * @param classStep
	 * 		Minimum number of completed classes between forwarded events.
	 */
	public ThrottledProgressListener(@Nonnull BatchDecompileProgressListener delegate,
	                                 @Nonnull Duration interval, int classStep) {
		this.delegate = delegate;
		this.intervalNanos = Math.max(0, interval.toNanos());
		this.classStep = Math.max(1, classStep);
	}

	@Override
	public void onProgress(@Nonnull BatchDecompileProgress event) {
		synchronized (this) {
			if (emitted) {
				long now = System.nanoTime();
				boolean timeElapsed = now - lastEmitNanos >= intervalNanos;
				boolean stepElapsed = event.completedClasses() - lastEmitCompleted >= classStep;
				if (!timeElapsed && !stepElapsed)
					return;
			}
			mark(event);
		}
		delegate.onProgress(event);
	}

	/**
	 * Throttled variant that only builds the snapshot when the event will actually be forwarded.
	 * <p>
	 * On a fast run this is called once per completed class, and nearly every call is dropped. Taking a
	 * supplier means the dropped calls cost a clock read instead of a progress record, which matters
	 * because the caller is the thread that also has to keep the writer fed.
	 *
	 * @param snapshot
	 * 		Supplier of the current progress, invoked only when the event is forwarded.
	 */
	public void onProgress(@Nonnull Supplier<BatchDecompileProgress> snapshot) {
		BatchDecompileProgress event;
		synchronized (this) {
			if (emitted) {
				long now = System.nanoTime();
				boolean timeElapsed = now - lastEmitNanos >= intervalNanos;
				boolean stepElapsed = ++suppressed >= classStep;
				if (!timeElapsed && !stepElapsed)
					return;
			}
			event = snapshot.get();
			mark(event);
		}
		delegate.onProgress(event);
	}

	/**
	 * Forwards the event regardless of the throttle, and resets the throttle window.
	 *
	 * @param event
	 * 		Progress snapshot to forward.
	 */
	public void flush(@Nonnull BatchDecompileProgress event) {
		synchronized (this) {
			mark(event);
		}
		delegate.onProgress(event);
	}

	private void mark(@Nonnull BatchDecompileProgress event) {
		lastEmitNanos = System.nanoTime();
		lastEmitCompleted = event.completedClasses();
		suppressed = 0;
		emitted = true;
	}
}
