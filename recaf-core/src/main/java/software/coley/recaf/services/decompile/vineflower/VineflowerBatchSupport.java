package software.coley.recaf.services.decompile.vineflower;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import software.coley.recaf.info.InnerClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.index.WorkspaceTypeIndex;
import software.coley.recaf.workspace.model.Workspace;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Building blocks for feeding several classes to a single {@link org.jetbrains.java.decompiler.main.Fernflower}
 * context at once.
 * <p/>
 * This is deliberately kept separate from {@link VineflowerDecompiler}. The single class path used by
 * {@link software.coley.recaf.services.decompile.DecompilerManager} is unchanged, see
 * {@link VineflowerChunkDecompiler} for why.
 *
 * @author Matt Coley
 * @see VineflowerChunkDecompiler Service exposing the batch API.
 */
public class VineflowerBatchSupport {
	/**
	 * Lower bound of the recommended chunk size range.
	 */
	public static final int MIN_CHUNK_SIZE = 64;
	/**
	 * Upper bound of the recommended chunk size range.
	 * <p/>
	 * Larger chunks amortize the per-context setup over more classes, but also grow the amount of decompiler state
	 * that must be held in memory at once, and make a single failure affect more classes.
	 */
	public static final int MAX_CHUNK_SIZE = 256;
	/**
	 * Default number of classes to place in a single chunk.
	 */
	public static final int DEFAULT_CHUNK_SIZE = 128;
	/**
	 * Smallest chunk {@link #chunkSizeFor(int, int)} will produce.
	 * <p/>
	 * Below {@link #MIN_CHUNK_SIZE} the per-context setup is no longer amortized as well, but a chunk of this
	 * size still pays it once for several classes instead of once per class, which is the bulk of the win.
	 */
	public static final int MIN_PARALLEL_CHUNK_SIZE = 8;
	/**
	 * How many chunks each worker should get. More than one, so that a chunk which happens to hold slow
	 * classes does not leave the other workers idle at the tail of a run.
	 */
	public static final int CHUNKS_PER_WORKER = 3;

	private VineflowerBatchSupport() {
	}

	/**
	 * Picks a chunk size balancing two opposing costs.
	 * <p/>
	 * Large chunks amortize the per-context setup over more classes, but a whole run split into fewer chunks
	 * than there are workers cannot use those workers: {@link #DEFAULT_CHUNK_SIZE} over a few hundred classes
	 * leaves a batch run effectively single-threaded, which is slower than the single-class path it replaces.
	 * So the size is derived from the work available per worker, and only falls back toward the default when
	 * there are enough classes to keep everyone busy anyway.
	 *
	 * @param classCount
	 * 		Number of classes to be decompiled.
	 * @param parallelism
	 * 		Number of chunks that can be decompiled at the same time.
	 *
	 * @return Number of classes to place in one chunk, within {@code [MIN_PARALLEL_CHUNK_SIZE, MAX_CHUNK_SIZE]}.
	 */
	public static int chunkSizeFor(int classCount, int parallelism) {
		if (classCount <= 0)
			return DEFAULT_CHUNK_SIZE;
		int targetChunks = Math.max(1, parallelism) * CHUNKS_PER_WORKER;
		int size = (classCount + targetChunks - 1) / targetChunks;
		return Math.min(MAX_CHUNK_SIZE, Math.max(MIN_PARALLEL_CHUNK_SIZE, size));
	}

	/**
	 * @param classes
	 * 		Classes <i>(or class-carrying work items)</i> to split up.
	 * @param chunkSize
	 * 		Maximum number of classes per chunk. Clamped to {@code [1, MAX_CHUNK_SIZE]}.
	 * @param <T>
	 * 		Item type.
	 *
	 * @return Chunks of the input list, each backed by the input list.
	 */
	@Nonnull
	public static <T> List<List<T>> partition(@Nonnull List<T> classes, int chunkSize) {
		int size = Math.min(Math.max(1, chunkSize), MAX_CHUNK_SIZE);
		List<List<T>> chunks = new ArrayList<>((classes.size() / size) + 1);
		for (int i = 0; i < classes.size(); i += size)
			chunks.add(classes.subList(i, Math.min(classes.size(), i + size)));
		return chunks;
	}

	/**
	 * Source supplying a group of classes to Vineflower as "own" classes, so that they all get decompiled by a
	 * single {@code decompileContext()} call.
	 */
	public static class ChunkSource implements IContextSource {
		private final WorkspaceTypeIndex index;
		private final Map<String, JvmClassInfo> targets;
		private final ChunkOutputSink sink;
		private final List<Entry> entries;

		/**
		 * @param workspace
		 * 		Workspace to pull class files from.
		 * @param classes
		 * 		Classes to decompile in this chunk.
		 */
		public ChunkSource(@Nonnull Workspace workspace, @Nonnull List<JvmClassInfo> classes) {
			this.index = workspace.getTypeIndex();

			Map<String, JvmClassInfo> targets = new LinkedHashMap<>(classes.size());
			for (JvmClassInfo info : classes)
				targets.putIfAbsent(info.getName(), info);
			this.targets = targets;
			this.sink = new ChunkOutputSink(targets.keySet());

			// Inner classes are registered alongside their outer class, mirroring what the single class
			// 'ClassSource' does. Names are de-duplicated so that a class passed in by the caller and also
			// referenced as an inner of another chunk entry is only registered once.
			Set<String> names = new LinkedHashSet<>(targets.keySet());
			for (JvmClassInfo info : targets.values())
				for (InnerClassInfo innerClass : info.getInnerClasses())
					if (index.getClassInfo(innerClass.getInnerClassName()) != null)
						names.add(innerClass.getName());
			List<Entry> entries = new ArrayList<>(names.size());
			for (String name : names)
				entries.add(new Entry(name, Entry.BASE_VERSION));
			this.entries = entries;
		}

		/**
		 * @return Sink holding the decompiled output after the decompilation task completes.
		 */
		@Nonnull
		public ChunkOutputSink getSink() {
			return sink;
		}

		@Override
		public String getName() {
			return "Recaf-chunk";
		}

		@Override
		public Entries getEntries() {
			return new Entries(entries, Collections.emptyList(), Collections.emptyList());
		}

		@Override
		public InputStream getInputStream(String resource) {
			String name = resource.substring(0, resource.length() - IContextSource.CLASS_SUFFIX.length());
			JvmClassInfo target = targets.get(name);
			if (target != null)
				return new ByteArrayInputStream(target.getBytecode());

			byte[] bytecode = index.getBytecode(name);
			if (bytecode == null) return null; // VF wants missing data to be null here, not an IOException or empty stream.
			return new ByteArrayInputStream(bytecode);
		}

		@Override
		public IOutputSink createOutputSink(IResultSaver saver) {
			return sink;
		}
	}

	/**
	 * Sink collecting the decompiled text of each requested class, keyed by internal class name.
	 */
	public static class ChunkOutputSink implements IContextSource.IOutputSink {
		private final Map<String, String> output = new LinkedHashMap<>();
		private final Set<String> requested;

		/**
		 * @param requested
		 * 		Names of the classes the caller asked for. Output of any other class <i>(such as an inner class
		 * 		emitted on its own)</i> is discarded.
		 */
		public ChunkOutputSink(@Nonnull Set<String> requested) {
			this.requested = requested;
		}

		/**
		 * @return Map of internal class names to decompiled text. Classes yielding no output are absent.
		 */
		@Nonnull
		public Map<String, String> getOutput() {
			return output;
		}

		/**
		 * @param name
		 * 		Internal class name.
		 *
		 * @return Decompiled text, or {@code null} if the class produced no output.
		 */
		@Nullable
		public String get(@Nonnull String name) {
			return output.get(name);
		}

		@Override
		public void begin() {
			// no-op
		}

		@Override
		public void acceptClass(String qualifiedName, String fileName, String content, int[] mapping) {
			if (content != null && !content.isEmpty() && requested.contains(qualifiedName))
				output.put(qualifiedName, content);
		}

		@Override
		public void acceptDirectory(String directory) {
			// no-op
		}

		@Override
		public void acceptOther(String path) {
			// no-op
		}

		@Override
		public void close() {
			// no-op
		}
	}
}
