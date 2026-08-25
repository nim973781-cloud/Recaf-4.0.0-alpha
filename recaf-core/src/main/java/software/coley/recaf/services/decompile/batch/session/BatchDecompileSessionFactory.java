package software.coley.recaf.services.decompile.batch.session;

import jakarta.annotation.Nonnull;
import software.coley.recaf.services.decompile.batch.BatchAccuracyMode;
import software.coley.recaf.workspace.model.Workspace;

/**
 * Factory opening {@link BatchDecompileSession sessions} for one decompiler backend.
 * <p/>
 * Implementations are CDI beans, one per backend, discovered by {@link #decompilerName()}.
 *
 * @author Matt Coley
 */
public interface BatchDecompileSessionFactory {
	/**
	 * @return Name of the decompiler this factory serves, matching
	 * {@link software.coley.recaf.services.decompile.Decompiler#getName()}.
	 */
	@Nonnull
	String decompilerName();

	/**
	 * Opens a session over the given workspace.
	 * <ul>
	 *     <li>{@link BatchAccuracyMode#ACCURATE} sessions produce byte-identical output to the backend's
	 *     single-class {@link software.coley.recaf.services.decompile.JvmDecompiler#decompile} path.</li>
	 *     <li>The fast modes may share more decompiler state between classes for throughput. Their output
	 *     must still declare the same types and member signatures as the accurate path, but comments,
	 *     whitespace, import order and local variable naming are allowed to differ.</li>
	 * </ul>
	 *
	 * @param workspace
	 * 		Workspace the session's classes belong to.
	 * @param mode
	 * 		Accuracy the session must provide.
	 *
	 * @return New session. Callers own it and should {@link BatchDecompileSession#close() close} it.
	 */
	@Nonnull
	BatchDecompileSession open(@Nonnull Workspace workspace, @Nonnull BatchAccuracyMode mode);
}
