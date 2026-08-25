package software.coley.recaf.services.decompile.batch.session;

import jakarta.annotation.Nonnull;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.batch.ClassExportTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Helpers for {@link BatchDecompileSession} implementations that decompile {@link JvmClassInfo}
 * lists and need to speak the engine's {@link ClassExportTask} grouping contract.
 *
 * @author Matt Coley
 */
public final class BatchSessionSupport {
	/**
	 * Parallelism used to size chunks so a session keeps the engine's decompile pool busy.
	 */
	public static final int PARALLELISM = Math.max(2, Runtime.getRuntime().availableProcessors());
	private static final int MIN_CHUNK_SIZE = 8;
	private static final int MAX_CHUNK_SIZE = 256;
	private static final int CHUNKS_PER_WORKER = 3;

	private BatchSessionSupport() {
	}

	/**
	 * @param tasks
	 * 		Export tasks in plan order.
	 *
	 * @return The classes those tasks carry, in the same order.
	 */
	@Nonnull
	public static List<JvmClassInfo> infos(@Nonnull List<ClassExportTask> tasks) {
		List<JvmClassInfo> infos = new ArrayList<>(tasks.size());
		for (ClassExportTask task : tasks)
			infos.add(task.classInfo());
		return infos;
	}

	/**
	 * Wraps classes as export tasks for tests that drive a session without the engine.
	 *
	 * @param classes
	 * 		Classes to wrap.
	 *
	 * @return Tasks in the same order.
	 */
	@Nonnull
	public static List<ClassExportTask> tasks(@Nonnull List<JvmClassInfo> classes) {
		List<ClassExportTask> tasks = new ArrayList<>(classes.size());
		for (JvmClassInfo info : classes)
			tasks.add(new ClassExportTask("session-test", info.getName(), info.getName() + ".java", info));
		return tasks;
	}

	/**
	 * One group per class, so the engine can parallelize the accurate per-class path.
	 *
	 * @param tasks
	 * 		Classes in plan order.
	 *
	 * @return Singleton groups in plan order.
	 */
	@Nonnull
	public static List<List<ClassExportTask>> onePerClass(@Nonnull List<ClassExportTask> tasks) {
		List<List<ClassExportTask>> groups = new ArrayList<>(tasks.size());
		for (ClassExportTask task : tasks)
			groups.add(List.of(task));
		return groups;
	}

	/**
	 * Splits the plan into chunks sized for {@link #PARALLELISM} workers.
	 *
	 * @param tasks
	 * 		Classes in plan order.
	 *
	 * @return Chunks in plan order.
	 */
	@Nonnull
	public static List<List<ClassExportTask>> chunked(@Nonnull List<ClassExportTask> tasks) {
		int size = chunkSizeFor(tasks.size());
		List<List<ClassExportTask>> groups = new ArrayList<>((tasks.size() / size) + 1);
		for (int i = 0; i < tasks.size(); i += size)
			groups.add(tasks.subList(i, Math.min(tasks.size(), i + size)));
		return groups;
	}

	/**
	 * Runs session work on the calling thread so the engine's decompile pool is the only pool,
	 * and so a pending interrupt is observed by the implementation's own flag checks.
	 *
	 * @param work
	 * 		Synchronous decompilation of one group.
	 *
	 * @return Completed or failed future carrying that group's outcomes.
	 */
	@Nonnull
	public static CompletableFuture<Map<String, SessionClassResult>> completed(
			@Nonnull Interruptible<Map<String, SessionClassResult>> work) {
		try {
			return CompletableFuture.completedFuture(work.run());
		} catch (InterruptedException ex) {
			return CompletableFuture.failedFuture(ex);
		}
	}

	@FunctionalInterface
	public interface Interruptible<T> {
		/**
		 * @return Result of the work.
		 *
		 * @throws InterruptedException
		 * 		When the calling thread was interrupted.
		 */
		T run() throws InterruptedException;
	}

	private static int chunkSizeFor(int classCount) {
		if (classCount <= 0)
			return MIN_CHUNK_SIZE;
		int targetChunks = PARALLELISM * CHUNKS_PER_WORKER;
		int size = (classCount + targetChunks - 1) / targetChunks;
		return Math.min(MAX_CHUNK_SIZE, Math.max(MIN_CHUNK_SIZE, size));
	}
}
