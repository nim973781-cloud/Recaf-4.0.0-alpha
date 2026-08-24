package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;

import java.util.List;

/**
 * Work planned for exporting one already open workspace.
 * <p>
 * This is the {@link WorkspaceDecompileRequest} counterpart of {@link JarDecompilePlan}. There is no
 * scanned form because an open workspace already knows its contents, so a plan is always populated.
 *
 * @param workspaceName
 * 		Display name of the workspace, used for progress reporting and failure records.
 * @param classes
 * 		Classes to decompile, in output order.
 * @param resources
 * 		Non-class files to copy, in output order.
 * @param skippedClasses
 * 		Number of classes left out by export filtering.
 *
 * @author Matt Coley
 */
public record WorkspaceBatchExport(
		@Nonnull String workspaceName,
		@Nonnull List<ClassExportTask> classes,
		@Nonnull List<ResourceExportTask> resources,
		int skippedClasses
) {
	/**
	 * @return Number of classes to decompile.
	 */
	public int classCount() {
		return classes.size();
	}

	/**
	 * @return Number of non-class files to copy.
	 */
	public int resourceCount() {
		return resources.size();
	}

	/**
	 * @return {@code true} when there is nothing to write.
	 */
	public boolean isEmpty() {
		return classes.isEmpty() && resources.isEmpty();
	}
}
