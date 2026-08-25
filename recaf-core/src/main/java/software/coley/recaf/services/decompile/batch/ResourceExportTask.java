package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.services.decompile.batch.zip.DeflatedBlob;

import java.util.Optional;
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
 * @param blobSupplier
 * 		Optional supplier of an already compressed form of the file, or {@code null} when only the plain
 * 		bytes are available. An archive sink can append such a blob without compressing it again, which
 * 		matters for resources that were already stored compressed in the input.
 *
 * @author Matt Coley
 */
public record ResourceExportTask(
		@Nonnull String jarName,
		@Nonnull String resourceName,
		@Nonnull String outputPath,
		@Nonnull Supplier<byte[]> contentSupplier,
		@Nullable Supplier<DeflatedBlob> blobSupplier
) {
	/**
	 * @param jarName
	 * 		File name of the input JAR the resource came from.
	 * @param resourceName
	 * 		Name of the file within its containing resource.
	 * @param outputPath
	 * 		Output path relative to the sink root.
	 * @param contentSupplier
	 * 		Supplier of the raw file bytes.
	 */
	public ResourceExportTask(@Nonnull String jarName, @Nonnull String resourceName,
	                          @Nonnull String outputPath, @Nonnull Supplier<byte[]> contentSupplier) {
		this(jarName, resourceName, outputPath, contentSupplier, null);
	}

	/**
	 * @return Raw file bytes.
	 */
	@Nonnull
	public byte[] content() {
		return contentSupplier.get();
	}

	/**
	 * @return Already compressed form of the file, if the source can provide one without recompressing.
	 * Callers that get {@link Optional#empty()} fall back to {@link #content()}.
	 */
	@Nonnull
	public Optional<DeflatedBlob> raw() {
		if (blobSupplier == null)
			return Optional.empty();
		return Optional.ofNullable(blobSupplier.get());
	}
}
