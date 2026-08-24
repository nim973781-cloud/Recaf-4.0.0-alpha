package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import software.coley.recaf.info.JvmClassInfo;

/**
 * One class scheduled for decompilation and export.
 *
 * @param jarName
 * 		File name of the input JAR the class came from.
 * @param className
 * 		Internal name of the class, for example {@code com/example/Foo}.
 * @param outputPath
 * 		Output path relative to the sink root, always {@code /} separated,
 * 		for example {@code example-lib/com/example/Foo.java}.
 * @param classInfo
 * 		The class to decompile. This reflects the state after any mappings were applied.
 *
 * @author Matt Coley
 */
public record ClassExportTask(
		@Nonnull String jarName,
		@Nonnull String className,
		@Nonnull String outputPath,
		@Nonnull JvmClassInfo classInfo
) {
	/**
	 * @return Name suitable for progress display.
	 */
	@Nonnull
	public String displayName() {
		return className;
	}
}
