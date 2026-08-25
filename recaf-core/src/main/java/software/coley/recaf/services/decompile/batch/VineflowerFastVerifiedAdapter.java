package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.JvmDecompiler;
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
 * Adapter letting {@link BatchDecompileEngine} run the fast accuracy modes through
 * {@link VineflowerChunkDecompiler} without knowing anything about the chunk API.
 * <p/>
 * The adapter only ever applies when the run's decompiler is {@link VineflowerDecompiler Vineflower} and the
 * request opted out of {@link BatchAccuracyMode#ACCURATE}. Everything else keeps the accurate single-class path.
 * <p/>
 * Each {@link Session} covers one workspace <i>(one input JAR of a batch run)</i>:
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
public class VineflowerFastVerifiedAdapter {
	private final VineflowerChunkDecompiler chunkDecompiler;
	private final ExecutorService chunkPool = ThreadPoolFactory.newFixedThreadPool("batch-vineflower-chunks");

	/**
	 * @param chunkDecompiler
	 * 		Chunk decompiler doing the actual work.
	 */
	@Inject
	public VineflowerFastVerifiedAdapter(@Nonnull VineflowerChunkDecompiler chunkDecompiler) {
		this.chunkDecompiler = chunkDecompiler;
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
		return mode != BatchAccuracyMode.ACCURATE && VineflowerDecompiler.NAME.equals(decompiler.getName());
	}

	/**
	 * @param workspace
	 * 		Workspace holding the classes of one batch input.
	 * @param mode
	 * 		Accuracy mode of the run, controls how the shared library source resolves classes.
	 *
	 * @return Session decompiling chunks of that workspace.
	 */
	@Nonnull
	public Session open(@Nonnull Workspace workspace, @Nonnull BatchAccuracyMode mode) {
		return new Session(workspace, mode == BatchAccuracyMode.FAST_UNSAFE);
	}

	/**
	 * Chunked decompilation over a single workspace, sharing one library source between chunks.
	 */
	public class Session {
		private final Workspace workspace;
		private final SharedLibrarySource library;

		private Session(@Nonnull Workspace workspace, boolean lazyLibrary) {
			this.workspace = workspace;
			this.library = new SharedLibrarySource(workspace, lazyLibrary);
		}

		/**
		 * @param tasks
		 * 		Class export tasks in plan order.
		 *
		 * @return Tasks split into chunks of {@link VineflowerBatchSupport#DEFAULT_CHUNK_SIZE}, preserving order.
		 */
		@Nonnull
		public List<List<ClassExportTask>> partition(@Nonnull List<ClassExportTask> tasks) {
			return VineflowerBatchSupport.partition(tasks, VineflowerBatchSupport.DEFAULT_CHUNK_SIZE);
		}

		/**
		 * Decompiles one chunk on the adapter's pool. The chunk gets its own {@code Fernflower} context, only
		 * the session's library source is reused.
		 *
		 * @param chunk
		 * 		Classes to decompile together.
		 *
		 * @return Future completing with one {@link ChunkClassResult} per requested class.
		 */
		@Nonnull
		public CompletableFuture<Map<String, ChunkClassResult>> decompileChunk(@Nonnull List<ClassExportTask> chunk) {
			return CompletableFuture.supplyAsync(() -> decompileNow(chunk), chunkPool);
		}

		@Nonnull
		private Map<String, ChunkClassResult> decompileNow(@Nonnull List<ClassExportTask> chunk) {
			List<JvmClassInfo> classes = new ArrayList<>(chunk.size());
			for (ClassExportTask task : chunk)
				classes.add(task.classInfo());
			VineflowerChunkDecompiler.ChunkResult result =
					chunkDecompiler.decompileChunkDetailed(workspace, classes, library);
			Map<String, ChunkClassResult> outcomes = new LinkedHashMap<>(chunk.size());
			result.decompiled().forEach((name, text) -> outcomes.put(name, new ChunkClassResult(text, null)));
			result.failures().forEach((name, cause) -> outcomes.put(name, new ChunkClassResult(null, cause)));
			return outcomes;
		}
	}

	/**
	 * Outcome of one class within a chunk. Exactly one of the two components is present.
	 *
	 * @param text
	 * 		Decompiled text, or {@code null} when the class produced no output.
	 * @param failure
	 * 		Reason no output was produced, or {@code null} on success.
	 */
	public record ChunkClassResult(@Nullable String text, @Nullable Throwable failure) {}
}
