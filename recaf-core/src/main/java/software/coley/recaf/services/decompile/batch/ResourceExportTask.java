package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;

import java.util.function.Supplier;

/**
 * One non-class file scheduled for copying into the output.
 *
 * @param jarName
 * 		File name of the input JAR the resource came from.
 * @param resourceName
 * 		Name of the file within its containing resource.
 * @param outputPath
 * 		Output path relative to the sink root, always {@code /} separated.
 * @param contentSupplier
 * 		Supplier of the raw file bytes. Deferred so a plan can be built without holding copies.
 *
 * @author Matt Coley
 */
public record ResourceExportTask(
		@Nonnull String jarName,
		@Nonnull String resourceName,
		@Nonnull String outputPath,
		@Nonnull Supplier<byte[]> contentSupplier
) {
	/**
	 * @return Raw file bytes.
	 */
	@Nonnull
	public byte[] content() {
		return contentSupplier.get();
	}
}
