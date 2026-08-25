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
import software.coley.recaf.services.decompile.batch.session.AbstractBatchDecompileSession;
import software.coley.recaf.services.decompile.batch.session.BatchDecompileSession;
import software.coley.recaf.services.decompile.batch.session.BatchDecompileSessionFactory;
import software.coley.recaf.services.decompile.batch.session.SessionClassResult;
import software.coley.recaf.workspace.model.Workspace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <h2>Accuracy</h2>
 * {@link BatchAccuracyMode#ACCURATE} sessions run one class per context, mirroring
 * {@link VineflowerDecompiler#decompileInternal} exactly, so the text is byte-identical with the single-class
 * path. The fast modes decompile whole chunks per context; {@link BatchAccuracyMode#FAST_UNSAFE} additionally
 * switches the library source to {@link SharedLibrarySource#isLazy() lazy} resolution.
 * <h2>Interruption</h2>
 * The accurate loop checks the interrupt flag at every class boundary. Chunk contexts abort at the next
 * class-output boundary through {@link VineflowerBatchSupport.ChunkOutputSink#acceptClass}.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class VineflowerSessionFactory implements BatchDecompileSessionFactory {
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
	 */
	private class ChunkSession extends AbstractBatchDecompileSession {
		private final Workspace workspace;
		private final SharedLibrarySource library;

		private ChunkSession(@Nonnull Workspace workspace, boolean lazyLibrary) {
			super(true);
			this.workspace = workspace;
			this.library = new SharedLibrarySource(workspace, lazyLibrary);
		}

		@Nonnull
		@Override
		protected Map<String, SessionClassResult> decompileClasses(@Nonnull List<JvmClassInfo> classes)
				throws InterruptedException {
			if (Thread.interrupted())
				throw new InterruptedException("Vineflower session interrupted");

			VineflowerChunkDecompiler.ChunkResult chunk =
					chunkDecompiler.decompileChunkDetailed(workspace, classes, library);

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
