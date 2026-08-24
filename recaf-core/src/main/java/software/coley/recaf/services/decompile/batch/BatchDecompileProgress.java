package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Snapshot of batch decompile progress.
 * <p>
 * {@code totalClasses} is a running total. Classes are only known once their containing JAR has been
 * imported, so the value grows as the run advances. {@link #fraction()} is therefore derived from JAR
 * completion rather than class completion.
 *
 * @param totalJars
 * 		Number of JARs the run will process.
 * @param completedJars
 * 		Number of JARs fully processed so far.
 * @param totalClasses
 * 		Number of classes planned for export so far. Filtered classes are counted by {@code skippedClasses}.
 * @param completedClasses
 * 		Number of classes processed so far, successful or not.
 * @param okClasses
 * 		Number of classes decompiled and written successfully.
 * @param skippedClasses
 * 		Number of classes excluded by export filtering.
 * @param failedClasses
 * 		Number of classes that failed to decompile or write.
 * @param currentJar
 * 		Name of the JAR being processed, if any.
 * @param currentClass
 * 		Name of the class most recently processed, if any.
 * @param fraction
 * 		Overall completion between {@code 0.0} and {@code 1.0}.
 *
 * @author Matt Coley
 */
public record BatchDecompileProgress(
		int totalJars,
		int completedJars,
		int totalClasses,
		int completedClasses,
		int okClasses,
		int skippedClasses,
		int failedClasses,
		@Nullable String currentJar,
		@Nullable String currentClass,
		double fraction
) {
	/**
	 * @param totalJars
	 * 		Number of JARs the run will process.
	 *
	 * @return Progress representing a run that has not started yet.
	 */
	@Nonnull
	public static BatchDecompileProgress initial(int totalJars) {
		return new BatchDecompileProgress(totalJars, 0, 0, 0, 0, 0, 0, null, null, 0);
	}
}
