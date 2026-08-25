package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.decompile.batch.session.BatchDecompileSession;
import software.coley.recaf.services.decompile.batch.session.BatchDecompileSessionFactory;
import software.coley.recaf.services.decompile.batch.session.SessionClassResult;
import software.coley.recaf.services.decompile.vineflower.SharedLibrarySource;
import software.coley.recaf.services.decompile.vineflower.VineflowerBatchSupport;
import software.coley.recaf.services.decompile.vineflower.VineflowerChunkDecompiler;
import software.coley.recaf.services.decompile.vineflower.VineflowerDecompiler;
import software.coley.recaf.util.threading.ThreadPoolFactory;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * {@link BatchDecompileSessionFactory} backing the fast accuracy modes with
 * {@link VineflowerChunkDecompiler}, so {@link BatchDecompileEngine} never has to know anything about
 * the chunk API.
 * <p/>
 * The factory only claims {@link VineflowerDecompiler Vineflower}, and declines
 * {@link BatchAccuracyMode#ACCURATE} runs, which keeps the accurate path on the single-class code that
 * defines correctness.
 * <p/>
 * Each session covers one workspace <i>(one input JAR of a batch run)</i>:
 * <ul>
 *     <li>The {@link SharedLibrarySource library source} is created once per session and resolves supporting
 *     classes through the shared {@link Workspace#getTypeIndex() workspace type index}.</li>
 *     <li>Every chunk is decompiled in its own fresh {@code Fernflower} context; only the library source is
 *     shared between chunks.</li>
 *     <li>Classes that yield no output are reported per class, so one broken class never swallows the rest of
 *     its chunk.</li>
 * </ul>
 * In {@link BatchAccuracyMode#FAST_VERIFIED} the library source lists workspace classes eagerly, mirroring what
 * the single-class path offers Vineflower. {@link BatchAccuracyMode#FAST_UNSAFE} switches the library source to
 * {@link SharedLibrarySource#isLazy() lazy} resolution, which skips the listing and may let Vineflower reach
 * classes the verified mode would not, so its output is not acceptance-grade.
 *
 * @author Matt Coley
 * @see VineflowerChunkDecompiler Chunk API this adapter delegates to.
 */
@ApplicationScoped
public class VineflowerFastVerifiedAdapter implements BatchDecompileSessionFactory {
	private static final int CHUNK_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors());
	private final VineflowerChunkDecompiler chunkDecompiler;
	private final ExecutorService chunkPool =
			ThreadPoolFactory.newFixedThreadPool("batch-vineflower-chunks", CHUNK_THREADS, true);

	/**
	 * @param chunkDecompiler
	 * 		Chunk decompiler doing the actual work.
	 */
	@Inject
	public VineflowerFastVerifiedAdapter(@Nonnull VineflowerChunkDecompiler chunkDecompiler) {
		this.chunkDecompiler = chunkDecompiler;
	}

	@Override
	public boolean supports(@Nonnull JvmDecompiler decompiler) {
		return VineflowerDecompiler.NAME.equals(decompiler.getName());
	}

	@Nullable
	@Override
	public BatchDecompileSession open(@Nonnull Workspace workspace, @Nonnull BatchAccuracyMode mode) {
		if (mode == BatchAccuracyMode.ACCURATE)
			return null;
		return new Session(workspace, mode == BatchAccuracyMode.FAST_UNSAFE);
	}

	/**
	 * @param mode
	 * 		Accuracy mode of the run.
	 * @param decompiler
	 * 		Decompiler of the run.
	 *
	 * @return {@code true} when the run may go through the chunked Vineflower path.
	 * Always {@code false} for {@link BatchAccuracyMode#ACCURATE}.
	 */
	public boolean isApplicable(@Nonnull BatchAccuracyMode mode, @Nonnull JvmDecompiler decompiler) {
		return mode != BatchAccuracyMode.ACCURATE && supports(decompiler);
	}

	/**
	 * Chunked decompilation over a single workspace, sharing one library source between chunks.
	 */
	public class Session implements BatchDecompileSession {
		private final Workspace workspace;
		private final SharedLibrarySource library;

		private Session(@Nonnull Workspace workspace, boolean lazyLibrary) {
			this.workspace = workspace;
			this.library = new SharedLibrarySource(workspace, lazyLibrary);
		}

		/**
		 * Splits the run into chunks sized for the pool that will decompile them. A fixed chunk size would
		 * leave most of the pool idle on all but the largest inputs, giving up the parallelism the accurate
		 * single-class path gets for free.
		 *
		 * @param tasks
		 * 		Class export tasks in plan order.
		 *
		 * @return Tasks split into chunks, preserving order.
		 */
		@Nonnull
		@Override
		public List<List<ClassExportTask>> partition(@Nonnull List<ClassExportTask> tasks) {
			return VineflowerBatchSupport.partition(tasks,
					VineflowerBatchSupport.chunkSizeFor(tasks.size(), CHUNK_THREADS));
		}

		/**
		 * Decompiles one chunk on the adapter's pool. The chunk gets its own {@code Fernflower} context, only
		 * the session's library source is reused.
		 *
		 * @param group
		 * 		Classes to decompile together.
		 *
		 * @return Future completing with one {@link SessionClassResult} per requested class.
		 */
		@Nonnull
		@Override
		public CompletableFuture<Map<String, SessionClassResult>> decompile(@Nonnull List<ClassExportTask> group) {
			return CompletableFuture.supplyAsync(() -> decompileNow(group), chunkPool);
		}

		@Nonnull
		private Map<String, SessionClassResult> decompileNow(@Nonnull List<ClassExportTask> chunk) {
			List<JvmClassInfo> classes = new ArrayList<>(chunk.size());
			for (ClassExportTask task : chunk)
				classes.add(task.classInfo());
			VineflowerChunkDecompiler.ChunkResult result =
					chunkDecompiler.decompileChunkDetailed(workspace, classes, library);
			Map<String, SessionClassResult> outcomes = new LinkedHashMap<>(chunk.size());
			result.decompiled().forEach((name, text) -> outcomes.put(name, SessionClassResult.of(text)));
			result.failures().forEach((name, cause) -> outcomes.put(name, SessionClassResult.failed(cause)));
			return outcomes;
		}
	}
}
