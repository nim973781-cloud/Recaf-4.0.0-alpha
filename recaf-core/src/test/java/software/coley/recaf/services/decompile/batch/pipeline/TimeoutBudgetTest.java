package software.coley.recaf.services.decompile.batch.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TimeoutBudget}.
 */
class TimeoutBudgetTest {
	@Test
	void anUnstartedBudgetNeverExpires() throws InterruptedException {
		TimeoutBudget budget = TimeoutBudget.ofMillis(20);
		assertFalse(budget.isStarted());
		Thread.sleep(80);
		assertFalse(budget.isExpired(), "A budget expired before its work ever started");
		assertEquals(20, budget.remainingMillis(), "An unstarted budget should report its full length");
	}

	@Test
	void aStartedBudgetRunsDown() throws InterruptedException {
		TimeoutBudget budget = TimeoutBudget.startedMillis(50);
		assertTrue(budget.isStarted());
		Thread.sleep(120);
		assertTrue(budget.isExpired());
		assertEquals(1, budget.remainingMillis(), "An expired budget should report the minimum wait, not a negative");
	}

	@Test
	void restartingDoesNotExtendTheBudget() throws InterruptedException {
		TimeoutBudget budget = TimeoutBudget.startedMillis(50);
		Thread.sleep(80);
		budget.start();
		assertTrue(budget.isExpired(), "Starting an already running budget reset its clock");
	}

	@Test
	void budgetsAreNeverZero() {
		TimeoutBudget budget = TimeoutBudget.ofMillis(0);
		assertEquals(1, budget.budgetMillis());
		assertTrue(budget.remainingMillis() >= 1);
	}
}
