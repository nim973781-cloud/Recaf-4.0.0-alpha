package software.coley.recaf.services.decompile.vineflower;

import jakarta.annotation.Nonnull;
import org.jetbrains.java.decompiler.main.decompiler.CancelationManager;

/**
 * Bridges Recaf's cancellation <i>(thread interruption)</i> into the worker threads Vineflower spins up
 * inside one {@link org.jetbrains.java.decompiler.main.Fernflower} context.
 * <p/>
 * A context with {@code thread-count > 1} decompiles its classes on a pool it creates itself, so the
 * thread that gets interrupted when a batch run is cancelled is only the one waiting on that pool.
 * Worse, Vineflower never shuts the pool down when the wait is aborted, so without a second signal those
 * workers would keep decompiling long after the run gave up. {@link CancelationManager} is the hook
 * Vineflower offers for this: it is polled once per method body, and throwing from it unwinds the worker.
 * The interrupt itself is not enough of a signal, because the aborted wait consumes the flag before those
 * workers could ever observe it, so the token is also cancelled explicitly when a context dies.
 * <h2>Scope</h2>
 * The checker is a single global in Vineflower, so it must be inert for every caller that did not opt in.
 * The active token is held in an {@link InheritableThreadLocal}, which the pool threads pick up because
 * Vineflower creates them from the thread that opened the context. Threads outside a
 * {@link #begin() begin}/{@link Token#close() close} pair see no token and never cancel, which is what
 * keeps the interactive and {@code ACCURATE} paths untouched.
 * <p/>
 * Inheritance is by thread creation, so any <i>other</i> thread that happens to be started from inside a
 * scope would carry the token too. Callers therefore cancel a token only when a context is genuinely
 * abandoned, which is the one case where its workers outlive it; a context that merely failed has already
 * waited for and shut down its own pool.
 *
 * @author Matt Coley
 */
final class VineflowerCancellation {
	private static final InheritableThreadLocal<Token> ACTIVE = new InheritableThreadLocal<>();

	static {
		CancelationManager.setCancelationChecker(() -> {
			Token token = ACTIVE.get();
			if (token != null && token.isCancelled())
				CancelationManager.cancel();
		});
	}

	private VineflowerCancellation() {
	}

	/**
	 * Opens a cancellation scope for the calling thread and any Vineflower worker created from it.
	 *
	 * @return Token to close once the context is done with.
	 */
	@Nonnull
	static Token begin() {
		Token token = new Token(Thread.currentThread());
		ACTIVE.set(token);
		return token;
	}

	/**
	 * Cancellation state of one {@link org.jetbrains.java.decompiler.main.Fernflower} context.
	 * <p/>
	 * A token is only ever cancelled explicitly, never by closing the scope. Workers of a context that
	 * completed normally have all finished before the scope closes, and leaving their inherited token
	 * uncancelled means a thread that inherited one by accident cannot be taken down by it either.
	 */
	static final class Token implements AutoCloseable {
		private final Thread owner;
		private volatile boolean cancelled;

		private Token(@Nonnull Thread owner) {
			this.owner = owner;
		}

		/**
		 * Stops every Vineflower worker still running under this token.
		 */
		void cancel() {
			cancelled = true;
		}

		private boolean isCancelled() {
			return cancelled || owner.isInterrupted();
		}

		@Override
		public void close() {
			// Only the thread that opened the scope may clear it. Worker threads hold their own
			// inherited copy of the field and are not ours to reset.
			if (Thread.currentThread() == owner)
				ACTIVE.remove();
		}
	}
}
