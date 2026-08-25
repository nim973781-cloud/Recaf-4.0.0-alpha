package software.coley.recaf.services.decompile.batch.pipeline;

import jakarta.annotation.Nonnull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Deadline that only starts counting once the work it guards actually begins.
 * <p>
 * A batch run bounds how long a single class may spend in the decompiler. Arming that deadline when the
 * work is <i>submitted</i> makes the class compete with everything queued ahead of it, so on a saturated
 * pool a class can be reported as timed out without a decompiler ever having looked at it. A budget is
 * therefore created up front, carried alongside the work item, and {@link #start() started} from inside
 * the task body.
 *
 * @author Matt Coley
 */
public final class TimeoutBudget {
	private final long budgetNanos;
	private volatile long startNanos = -1;

	private TimeoutBudget(long budgetNanos) {
		this.budgetNanos = budgetNanos;
	}

	/**
	 * @param budgetMillis
	 * 		How long the guarded work may run once it starts. Values below one are raised to one.
	 *
	 * @return Unstarted budget.
	 */
	@Nonnull
	public static TimeoutBudget ofMillis(long budgetMillis) {
		return new TimeoutBudget(TimeUnit.MILLISECONDS.toNanos(Math.max(1L, budgetMillis)));
	}

	/**
	 * @param budgetMillis
	 * 		How long the guarded work may run. Values below one are raised to one.
	 *
	 * @return Budget that is already running.
	 */
	@Nonnull
	public static TimeoutBudget startedMillis(long budgetMillis) {
		return ofMillis(budgetMillis).start();
	}

	/**
	 * Starts the clock. Calling this more than once keeps the first start, so a budget that is handed to
	 * nested work cannot be silently extended.
	 *
	 * @return This budget.
	 */
	@Nonnull
	public TimeoutBudget start() {
		if (startNanos < 0)
			startNanos = System.nanoTime();
		return this;
	}

	/**
	 * @return {@code true} once {@link #start()} has been called.
	 */
	public boolean isStarted() {
		return startNanos >= 0;
	}

	/**
	 * @return Total budget in milliseconds.
	 */
	public long budgetMillis() {
		return TimeUnit.NANOSECONDS.toMillis(budgetNanos);
	}

	/**
	 * @return Milliseconds left before the budget expires, never below one so callers can pass the value
	 * straight to a timed wait. An unstarted budget reports its full length.
	 */
	public long remainingMillis() {
		long start = startNanos;
		if (start < 0)
			return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(budgetNanos));
		long remaining = budgetNanos - (System.nanoTime() - start);
		return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
	}

	/**
	 * @return {@code true} when a started budget has run out.
	 */
	public boolean isExpired() {
		long start = startNanos;
		return start >= 0 && System.nanoTime() - start >= budgetNanos;
	}

	/**
	 * @param what
	 * 		Description of the work that ran out of budget.
	 *
	 * @return Exception describing the expiry, for callers that detect it themselves.
	 */
	@Nonnull
	public TimeoutException expired(@Nonnull String what) {
		return new TimeoutException(what + " exceeded its " + budgetMillis() + "ms budget");
	}
}
