package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;

import java.nio.file.Path;
import java.util.List;

/**
 * Work planned for one input JAR.
 * <p>
 * A plan is created twice per JAR. {@link #scanned(Path, String)} produces the input-only form used
 * while building the {@link BatchDecompilePlan}, since classes are not known until the JAR has been
 * imported. The engine then replaces it with {@link #withContents(List, List)} once the JAR is open.
 * Keeping the populated form scoped to a single JAR is what stops memory from scaling with the total
 * class count of the run.
 *
 * @param inputJar
 * 		Path of the input JAR.
 * @param outputName
 * 		Output sub-path for this JAR, which is the file name without its extension.
 * @param classes
 * 		Classes to decompile, in output order.
 * @param resources
 * 		Non-class files to copy, in output order.
 *
 * @author Matt Coley
 */
public record JarDecompilePlan(
		@Nonnull Path inputJar,
		@Nonnull String outputName,
		@Nonnull List<ClassExportTask> classes,
		@Nonnull List<ResourceExportTask> resources
) {
	/**
	 * @param inputJar
	 * 		Path of the input JAR.
	 * @param outputName
	 * 		Output sub-path for this JAR.
	 *
	 * @return Plan with no contents resolved yet.
	 */
	@Nonnull
	public static JarDecompilePlan scanned(@Nonnull Path inputJar, @Nonnull String outputName) {
		return new JarDecompilePlan(inputJar, outputName, List.of(), List.of());
	}

	/**
	 * @param classes
	 * 		Classes to decompile, in output order.
	 * @param resources
	 * 		Non-class files to copy, in output order.
	 *
	 * @return Copy of this plan with its contents resolved.
	 */
	@Nonnull
	public JarDecompilePlan withContents(@Nonnull List<ClassExportTask> classes,
	                                     @Nonnull List<ResourceExportTask> resources) {
		return new JarDecompilePlan(inputJar, outputName, List.copyOf(classes), List.copyOf(resources));
	}

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
	 * @return File name of the input JAR.
	 */
	@Nonnull
	public String jarName() {
		Path fileName = inputJar.getFileName();
		return fileName == null ? outputName : fileName.toString();
	}
}
