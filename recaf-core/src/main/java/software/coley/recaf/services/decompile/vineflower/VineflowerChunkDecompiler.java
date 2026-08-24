package software.coley.recaf.services.decompile.vineflower;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch oriented companion to {@link VineflowerDecompiler}.
 * <p/>
 * Where {@link VineflowerDecompiler} builds one {@link Fernflower} context per class, this service places a whole
 * chunk of classes into a single context. The per-context setup <i>(library registration, decompiler options,
 * inner class linking)</i> is then paid once for the chunk rather than once per class, which is where most of the
 * time goes when decompiling a whole workspace.
 *
 * <h2>This is not wired into the default decompile path</h2>
 * {@link software.coley.recaf.services.decompile.DecompilerManager} still routes single class requests through
 * {@link VineflowerDecompiler}, and that must stay the case until the output of this API has been diffed against it.
 * Sharing a context changes what Vineflower can see while writing a class, so the text produced here is allowed to
 * differ from the single class output, most notably in:
 * <ul>
 *     <li>Inner classes: an inner class that is also passed in as a chunk entry may be emitted inline in its outer
 *     class here, but standalone in single class mode <i>(or the other way around)</i>.</li>
 *     <li>Imports: the import list is derived from what the context knows about, so a shared context can shorten
 *     or qualify names differently.</li>
 * </ul>
 * Treat this as a building block for a future batch engine <i>(such as a "fast, verified" mode)</i>, not as a drop-in
 * replacement for the accurate single class path.
 *
 * @author Matt Coley
 * @see VineflowerBatchSupport Chunk source, output sink and chunk splitting.
 * @see SharedLibrarySource Library source that can be shared between chunks.
 */
@ApplicationScoped
public class VineflowerChunkDecompiler {
	private static final Logger logger = Logging.get(VineflowerChunkDecompiler.class);
	private final IResultSaver dummySaver = new DummyResultSaver();
	private final VineflowerConfig config;
	private final IFernflowerLogger fernflowerLogger;

	/**
	 * @param config
	 * 		Decompiler configuration, shared with {@link VineflowerDecompiler}.
	 */
	@Inject
	public VineflowerChunkDecompiler(@Nonnull VineflowerConfig config) {
		this.config = config;
		this.fernflowerLogger = new VineflowerLogger(config);
	}

	/**
	 * Decompiles the given classes in a single {@link Fernflower} context.
	 *
	 * @param workspace
	 * 		Workspace to pull class files from.
	 * @param classes
	 * 		Classes to decompile. Should be no larger than {@link VineflowerBatchSupport#MAX_CHUNK_SIZE}.
	 *
	 * @return Map of internal class names to decompiled text.
	 * Classes that failed to decompile are <i>absent</i> from the map, see
	 * {@link #decompileChunkDetailed(Workspace, List, SharedLibrarySource)} to inspect the failures.
	 */
	@Nonnull
	public Map<String, String> decompileChunk(@Nonnull Workspace workspace, @Nonnull List<JvmClassInfo> classes) {
		return decompileChunkDetailed(workspace, classes, null).decompiled();
	}

	/**
	 * Decompiles the given classes in a single {@link Fernflower} context, reporting failures.
	 *
	 * @param workspace
	 * 		Workspace to pull class files from.
	 * @param classes
	 * 		Classes to decompile. Should be no larger than {@link VineflowerBatchSupport#MAX_CHUNK_SIZE}.
	 * @param library
	 * 		Library source to reuse across chunks, or {@code null} to build one for this chunk alone.
	 *
	 * @return Result holding the decompiled text of each class, plus the classes that yielded nothing.
	 */
	@Nonnull
	public ChunkResult decompileChunkDetailed(@Nonnull Workspace workspace, @Nonnull List<JvmClassInfo> classes,
	                                          @Nullable SharedLibrarySource library) {
		if (classes.isEmpty())
			return new ChunkResult(Collections.emptyMap(), Collections.emptyMap());

		VineflowerBatchSupport.ChunkSource source = new VineflowerBatchSupport.ChunkSource(workspace, classes);
		Fernflower fernflower = new Fernflower(dummySaver, config.getFernflowerProperties(), fernflowerLogger);
		Throwable chunkFailure = null;
		try {
			fernflower.addSource(source);
			fernflower.addLibrary(library == null ? new SharedLibrarySource(workspace) : library);
			fernflower.decompileContext();
		} catch (Throwable t) {
			// A failure here can still leave partial output in the sink, so we record the problem and
			// report on whatever did make it through rather than dropping the whole chunk silently.
			chunkFailure = t;
			logger.error("Vineflower failed to decompile a chunk of {} classes", classes.size(), t);
		} finally {
			fernflower.clearContext();
		}

		Map<String, String> decompiled = source.getSink().getOutput();
		Map<String, Throwable> failures = new LinkedHashMap<>();
		for (JvmClassInfo info : classes) {
			String name = info.getName();
			if (decompiled.containsKey(name)) continue;
			failures.put(name, chunkFailure != null ? chunkFailure :
					new IllegalStateException("Missing decompilation output for " + name));
		}
		return new ChunkResult(decompiled, failures);
	}

	/**
	 * Decompiles the given classes by splitting them into chunks of {@link VineflowerBatchSupport#DEFAULT_CHUNK_SIZE}.
	 *
	 * @param workspace
	 * 		Workspace to pull class files from.
	 * @param classes
	 * 		Classes to decompile.
	 *
	 * @return Map of internal class names to decompiled text. Failed classes are absent.
	 */
	@Nonnull
	public Map<String, String> decompileBatched(@Nonnull Workspace workspace, @Nonnull List<JvmClassInfo> classes) {
		return decompileBatched(workspace, classes, VineflowerBatchSupport.DEFAULT_CHUNK_SIZE);
	}

	/**
	 * Decompiles the given classes by splitting them into chunks of the given size.
	 * A fresh {@link Fernflower} is created per chunk, but the workspace library source is built only once.
	 *
	 * @param workspace
	 * 		Workspace to pull class files from.
	 * @param classes
	 * 		Classes to decompile.
	 * @param chunkSize
	 * 		Number of classes per chunk. Clamped to {@code [1, MAX_CHUNK_SIZE]}.
	 *
	 * @return Map of internal class names to decompiled text. Failed classes are absent.
	 */
	@Nonnull
	public Map<String, String> decompileBatched(@Nonnull Workspace workspace, @Nonnull List<JvmClassInfo> classes,
	                                            int chunkSize) {
		SharedLibrarySource library = new SharedLibrarySource(workspace);
		Map<String, String> combined = new LinkedHashMap<>(classes.size());
		for (List<JvmClassInfo> chunk : VineflowerBatchSupport.partition(classes, chunkSize))
			combined.putAll(decompileChunkDetailed(workspace, chunk, library).decompiled());
		return combined;
	}

	/**
	 * @param decompiled
	 * 		Map of internal class names to decompiled text.
	 * @param failures
	 * 		Map of internal class names to the reason no output was produced.
	 */
	public record ChunkResult(@Nonnull Map<String, String> decompiled, @Nonnull Map<String, Throwable> failures) {
		/**
		 * @return {@code true} when every requested class produced output.
		 */
		public boolean isComplete() {
			return failures.isEmpty();
		}
	}
}
