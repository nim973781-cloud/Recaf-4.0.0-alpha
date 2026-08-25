package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;

/**
 * Decompiles a folder of archives, or an already open workspace, into source output.
 * <p>
 * This is the single owner of batch decompile behavior. The JavaFX popups, a headless CLI, tests and
 * benchmarks are all expected to build a {@link BatchDecompileRequest} or a
 * {@link WorkspaceDecompileRequest}, run it here, and present the resulting
 * {@link BatchDecompileReport}. No implementation may depend on JavaFX.
 *
 * @author Matt Coley
 * @see DefaultBatchDecompileEngine
 */
public interface BatchDecompileEngine {
	/**
	 * Runs the request to completion on the calling thread.
	 *
	 * @param request
	 * 		Description of the run.
	 * @param listener
	 * 		Receives throttled progress updates. Use {@link BatchDecompileProgressListener#NOOP} to ignore them.
	 *
	 * @return Report describing what the run produced.
	 *
	 * @throws BatchDecompileException
	 * 		When the run cannot start or cannot complete. Failures scoped to a single JAR or class are
	 * 		recorded in the report instead.
	 */
	@Nonnull
	BatchDecompileReport run(@Nonnull BatchDecompileRequest request,
	                         @Nonnull BatchDecompileProgressListener listener) throws BatchDecompileException;

	/**
	 * Runs the request to completion on the calling thread, exporting an already open workspace.
	 * <p>
	 * The workspace is read as-is, including unsaved edits, and is neither closed nor modified.
	 *
	 * @param request
	 * 		Description of the export.
	 * @param listener
	 * 		Receives throttled progress updates. Use {@link BatchDecompileProgressListener#NOOP} to ignore them.
	 *
	 * @return Report describing what the export produced.
	 *
	 * @throws BatchDecompileException
	 * 		When the export cannot start or cannot complete. Failures scoped to a single class are
	 * 		recorded in the report instead.
	 */
	@Nonnull
	BatchDecompileReport exportWorkspace(@Nonnull WorkspaceDecompileRequest request,
	                                     @Nonnull BatchDecompileProgressListener listener) throws BatchDecompileException;
}
