package software.coley.recaf.services.decompile.batch.session;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.decompile.batch.BatchAccuracyMode;
import software.coley.recaf.workspace.model.Workspace;

/**
 * Opens a {@link BatchDecompileSession} for a decompiler that can share work between classes.
 * <p>
 * The batch engine discovers factories through CDI and uses the first one that
 * {@link #supports(JvmDecompiler) supports} the decompiler of the run. When no factory claims it, the
 * engine falls back to decompiling one class at a time through
 * {@link software.coley.recaf.services.decompile.DecompilerManager}, which is the path that defines
 * correctness.
 *
 * @author Matt Coley
 */
public interface BatchDecompileSessionFactory {
	/**
	 * @param decompiler
	 * 		Decompiler the run was configured with.
	 *
	 * @return {@code true} when this factory has a session implementation for that decompiler.
	 */
	boolean supports(@Nonnull JvmDecompiler decompiler);

	/**
	 * Opens a session over one batch input.
	 *
	 * @param workspace
	 * 		Workspace holding the classes of one batch input.
	 * @param mode
	 * 		Accuracy the run asked for.
	 *
	 * @return Session for that workspace, or {@code null} when this factory cannot honor the requested
	 * accuracy and the engine should use the single-class path instead.
	 */
	@Nullable
	BatchDecompileSession open(@Nonnull Workspace workspace, @Nonnull BatchAccuracyMode mode);
}
