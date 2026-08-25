package software.coley.recaf.services.decompile.batch.session;

import jakarta.annotation.Nonnull;
import software.coley.recaf.info.JvmClassInfo;

import java.util.List;
import java.util.Map;

/**
 * One batch decompilation session over a single workspace.
 * <p/>
 * A session may hold decompiler state <i>(class-path listings, metadata caches, shared decompiler contexts)</i>
 * alive between {@link #decompile(List) chunks}, so the per-chunk setup cost is paid once per workspace instead
 * of once per class or chunk. Sessions are <b>not</b> thread-safe unless documented otherwise by the factory;
 * callers wanting parallelism should open one session per worker or feed chunks to one session sequentially.
 *
 * @author Matt Coley
 * @see BatchDecompileSessionFactory Factories creating sessions per backend.
 */
public interface BatchDecompileSession extends AutoCloseable {
	/**
	 * Decompiles one chunk of classes.
	 * <p/>
	 * Every requested class is accounted for in the returned map, either with text or with a failure;
	 * one broken class never drops its chunk siblings.
	 *
	 * @param classes
	 * 		Classes to decompile together. All must belong to the workspace the session was opened for.
	 *
	 * @return Map of internal class names to their outcome, holding one entry per requested class.
	 *
	 * @throws InterruptedException
	 * 		When the calling thread was interrupted. The interrupt is consumed and decompilation stops
	 * 		at the next class boundary.
	 */
	@Nonnull
	Map<String, SessionClassResult> decompile(@Nonnull List<JvmClassInfo> classes) throws InterruptedException;

	/**
	 * Releases session state. Idempotent.
	 */
	@Override
	default void close() {
		// Sessions hold no closeable resources by default.
	}
}
