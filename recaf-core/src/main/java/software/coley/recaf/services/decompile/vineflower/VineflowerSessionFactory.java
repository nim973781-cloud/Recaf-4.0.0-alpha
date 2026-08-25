package software.coley.recaf.services.decompile.vineflower;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.decompile.batch.BatchAccuracyMode;
import software.coley.recaf.services.decompile.batch.ClassExportTask;
import software.coley.recaf.services.decompile.batch.session.AbstractBatchDecompileSession;
import software.coley.recaf.services.decompile.batch.session.BatchDecompileSession;
import software.coley.recaf.services.decompile.batch.session.BatchDecompileSessionFactory;
import software.coley.recaf.services.decompile.batch.session.BatchSessionSupport;
import software.coley.recaf.services.decompile.batch.session.SessionClassResult;
import software.coley.recaf.workspace.model.Workspace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * {@link BatchDecompileSessionFactory} for Vineflower.
 * <h2>Sharing granularity</h2>
 * The {@link SharedLibrarySource} is created once per session <i>(one per workspace)</i> and shared by every
 * chunk. Each chunk however gets a <b>fresh</b> {@link Fernflower} context:
 * <ul>
 *     <li>Sharing one {@code Fernflower} across chunks was tried and rejected. On the equivalence fixtures the
 *     text it produced was byte-identical and chunk-order independent, so the textual gates did not falsify
 *     it. What falsified it is throughput: {@link Fernflower#decompileContext()} re-processes every class
 *     registered so far, so each chunk re-decompiles all of its predecessors <i>(measured as cumulative sink
 *     emissions of 4 &rarr; 13 &rarr; 25 over three chunks of 4/4/2 classes instead of 4 &rarr; 8 &rarr; 10)</i>,
 *     making a session quadratic in its chunk count.</li>
 *     <li>Within a chunk context, inner classes are registered exactly once alongside their outer class,
 *     see {@link VineflowerBatchSupport.ChunkSource}. With per-chunk contexts the registration cannot move
 *     to session scope: an outer class can only inline its inner if that inner is registered in the
 *     <b>same</b> context.</li>
 * </ul>
 * Concurrent chunks are safe: each {@code decompile} call uses its own {@code Fernflower}, and the shared
 * library source only reads the workspace type index.
 * <h2>Where the parallelism lives</h2>
 * Fast sessions put the plan into as few contexts as possible, admit one context at a time and let
 * Vineflower's own {@code thread-count} pool spread that context's classes over the machine, rather than
 * handing the engine a pile of small single-threaded contexts. See {@code ChunkSession#partition} for why
 * the two are not interchangeable.
 * <h2>Accuracy</h2>
 * {@link BatchAccuracyMode#ACCURATE} sessions run one class per context, mirroring
 * {@link VineflowerDecompiler#decompileInternal} exactly, so the text is byte-identical with the single-class
 * path. The fast modes decompile whole chunks per context; {@link BatchAccuracyMode#FAST_UNSAFE} additionally
 * switches the library source to {@link SharedLibrarySource#isLazy() lazy} resolution.
 * <h2>Interruption</h2>
 * The accurate loop checks the interrupt flag at every class boundary. Chunk contexts abort at the next
 * class-output boundary through {@link VineflowerBatchSupport.ChunkOutputSink#acceptClass}, and the
 * threads a chunk context decompiles on stop through {@link VineflowerCancellation}.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class VineflowerSessionFactory implements BatchDecompileSessionFactory {
	/**
	 * Threads a fast session gives to a context that is the only one in flight.
	 */
	static final int CONTEXT_THREADS = BatchSessionSupport.PARALLELISM;
	private final IResultSaver dummySaver = new DummyResultSaver();
	private final VineflowerConfig config;
	private final VineflowerChunkDecompiler chunkDecompiler;
	private final IFernflowerLogger fernflowerLogger;

	/**
	 * @param config
	 * 		Decompiler configuration, shared with {@link VineflowerDecompiler}.
	 * @param chunkDecompiler
	 * 		Chunk decompiler running the fast modes.
	 */
	@Inject
	public VineflowerSessionFactory(@Nonnull VineflowerConfig config,
	                                @Nonnull VineflowerChunkDecompiler chunkDecompiler) {
		this.config = config;
		this.chunkDecompiler = chunkDecompiler;
		this.fernflowerLogger = new VineflowerLogger(config);
	}

	@Override
	public boolean supports(@Nonnull JvmDecompiler decompiler) {
		return VineflowerDecompiler.NAME.equals(decompiler.getName());
	}

	@Nullable
	@Override
	public BatchDecompileSession open(@Nonnull Workspace workspace, @Nonnull BatchAccuracyMode mode) {
		if (mode == BatchAccuracyMode.ACCURATE)
			return new AccurateSession(workspace);
		return new ChunkSession(workspace, mode == BatchAccuracyMode.FAST_UNSAFE);
	}

	/**
	 * One fresh {@link Fernflower} per class, byte-identical with {@link VineflowerDecompiler}.
	 */
	private class AccurateSession extends AbstractBatchDecompileSession {
		private final Workspace workspace;

		private AccurateSession(@Nonnull Workspace workspace) {
			super(false);
			this.workspace = workspace;
		}

		@Nonnull
		@Override
		protected Map<String, SessionClassResult> decompileClasses(@Nonnull List<JvmClassInfo> classes)
				throws InterruptedException {
			Map<String, SessionClassResult> results = new LinkedHashMap<>(classes.size());
			for (JvmClassInfo info : classes) {
				if (Thread.interrupted())
					throw new InterruptedException("Vineflower session interrupted");
				results.put(info.getName(), decompileOne(info));
			}
			return results;
		}

		@Nonnull
		private SessionClassResult decompileOne(@Nonnull JvmClassInfo info) {
			// Mirrors 'VineflowerDecompiler#decompileInternal' step for step. Any deviation here shows up
			// as a byte-level diff in the ACCURATE equivalence gate.
			Fernflower fernflower = new Fernflower(dummySaver, config.getFernflowerProperties(), fernflowerLogger);
			try {
				ClassSource source = new ClassSource(workspace, info);
				fernflower.addSource(source);
				fernflower.addLibrary(new LibrarySource(workspace, info));
				fernflower.decompileContext();

				String decompiled = source.getSink().getDecompiledOutput().get();
				if (decompiled == null || decompiled.isEmpty())
					return SessionClassResult.failed(new IllegalStateException("Missing decompilation output"));
				return SessionClassResult.ok(decompiled);
			} catch (Exception ex) {
				return SessionClassResult.failed(ex);
			}
		}
	}

	/**
	 * One fresh {@link Fernflower} per chunk, sharing the session's {@link SharedLibrarySource}.
	 * <p>
	 * Chunks are as large as a context is allowed to get, not as small as the engine pool is wide, see
	 * {@link #partition(List)}.
	 */
	private class ChunkSession extends AbstractBatchDecompileSession {
		private final Workspace workspace;
		private final SharedLibrarySource library;
		/**
		 * Contexts admitted at once. Vineflower spends {@link #CONTEXT_THREADS} threads inside each one,
		 * so this is what keeps engine workers times Vineflower threads off the machine's core count.
		 */
		private final Semaphore admission =
				new Semaphore(Math.max(1, BatchSessionSupport.PARALLELISM / CONTEXT_THREADS));

		private ChunkSession(@Nonnull Workspace workspace, boolean lazyLibrary) {
			// The chunking of the base class is deliberately not used, see 'partition'.
			super(true);
			this.workspace = workspace;
			this.library = new SharedLibrarySource(workspace, lazyLibrary);
		}

		/**
		 * Groups as few contexts as possible rather than as many as there are workers.
		 * <p>
		 * The engine's default chunking sizes groups for its own pool, which over a few hundred classes
		 * is a dozen small contexts, one per worker with some slack. That gives Vineflower nothing to
		 * work with: a context re-derives everything it was handed, so a dozen contexts on a dozen
		 * threads costs what a dozen threads cost, which is what the accurate per-class path already
		 * spends. Vineflower's own CLI instead puts a whole archive into one context and spreads its
		 * classes over an internal pool of {@code thread-count} workers, which is what this reproduces.
		 * <p>
		 * Everything up to {@link VineflowerBatchSupport#MAX_CHUNK_SIZE} is therefore a single group.
		 * Beyond that the plan is sliced at the same ceiling, because a context that holds an entire
		 * large archive gets measurably worse per class, and because the memory it pins and the blast
		 * radius of a context-wide failure both scale with its size. Those slices are still admitted one
		 * at a time, see {@link #admission}.
		 */
		@Nonnull
		@Override
		public List<List<ClassExportTask>> partition(@Nonnull List<ClassExportTask> tasks) {
			if (tasks.isEmpty())
				return List.of();
			if (tasks.size() <= VineflowerBatchSupport.MAX_CHUNK_SIZE)
				return List.of(tasks);
			return VineflowerBatchSupport.partition(tasks, VineflowerBatchSupport.MAX_CHUNK_SIZE);
		}

		@Nonnull
		@Override
		protected Map<String, SessionClassResult> decompileClasses(@Nonnull List<JvmClassInfo> classes)
				throws InterruptedException {
			if (Thread.interrupted())
				throw new InterruptedException("Vineflower session interrupted");

			VineflowerChunkDecompiler.ChunkResult chunk;
			admission.acquire();
			try {
				chunk = chunkDecompiler.decompileChunkDetailed(workspace, classes, library, CONTEXT_THREADS);
			} finally {
				admission.release();
			}

			// The chunk sink aborts output delivery at the next class boundary when the worker is
			// interrupted, leaving the flag set for this check to consume.
			if (Thread.interrupted())
				throw new InterruptedException("Vineflower session interrupted");

			Map<String, SessionClassResult> results = new LinkedHashMap<>(classes.size());
			chunk.decompiled().forEach((name, text) -> results.put(name, SessionClassResult.ok(text)));
			chunk.failures().forEach((name, failure) -> results.put(name, SessionClassResult.failed(failure)));
			return results;
		}
	}
}
