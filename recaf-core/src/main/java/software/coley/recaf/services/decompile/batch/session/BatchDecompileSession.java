package software.coley.recaf.services.decompile.batch.session;

import jakarta.annotation.Nonnull;
import software.coley.recaf.services.decompile.batch.ClassExportTask;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Decompiles the classes of one workspace for the length of one batch input.
 * <p>
 * The single-class decompile path pays for a fresh decompiler context, and for resolving every
 * supporting class, once per class. Over a whole archive that dominates the run. A session exists so a
 * decompiler that can share that setup between classes gets to do so: it is opened once per input,
 * decides how to {@link #partition(List) group} the work, and decompiles a whole group per call.
 * <p>
 * Sessions are the engine's normal path, not an opt-in fast lane. Accuracy is a parameter the session is
 * opened with rather than a different code path in the engine, so a session that cannot honor the
 * requested accuracy must decline at
 * {@link BatchDecompileSessionFactory#open(software.coley.recaf.workspace.model.Workspace, software.coley.recaf.services.decompile.batch.BatchAccuracyMode)}
 * time instead of quietly returning different output.
 *
 * @author Matt Coley
 */
public interface BatchDecompileSession extends AutoCloseable {
	/**
	 * Splits the classes of the input into the groups this session wants to decompile together.
	 * <p>
	 * Order must be preserved, both between groups and within a group, so the engine can write output in
	 * plan order. A session with nothing to share may return one group per class.
	 *
	 * @param tasks
	 * 		Classes to decompile, in plan order.
	 *
	 * @return Groups of classes, in plan order.
	 */
	@Nonnull
	List<List<ClassExportTask>> partition(@Nonnull List<ClassExportTask> tasks);

	/**
	 * Decompiles one group produced by {@link #partition(List)}.
	 *
	 * @param group
	 * 		Classes to decompile together.
	 *
	 * @return Future completing with one result per requested class, keyed by
	 * {@link ClassExportTask#className()}. A class missing from the map is treated as a failure.
	 */
	@Nonnull
	CompletableFuture<Map<String, SessionClassResult>> decompile(@Nonnull List<ClassExportTask> group);

	/**
	 * Releases whatever the session held for its workspace. Called exactly once per session.
	 */
	@Override
	default void close() {
		// no-op by default
	}
}
