package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Sink streaming every output file into a single archive.
 * <p>
 * The archive is written by this sink alone. No {@code ZipBuilder} or other mutable shared state is
 * handed to worker threads, and entries are appended in the order the engine supplies them, which is
 * plan order. Because a ZIP cannot hold two entries with the same name, a repeated output path is
 * rejected rather than written twice, and counted in {@link #stats()}.
 *
 * @author Matt Coley
 */
public class StreamingZipDecompileSink implements BatchDecompileSink {
	private static final Logger logger = Logging.get(StreamingZipDecompileSink.class);
	private final Set<String> writtenPaths = new HashSet<>();
	private final ZipOutputStream zos;
	private final Path archivePath;
	private long fileCount;
	private long byteCount;
	private long duplicateCount;
	private boolean closed;

	/**
	 * @param archivePath
	 * 		File to write the archive to. Any existing file is replaced.
	 *
	 * @throws IOException
	 * 		When the archive cannot be opened for writing.
	 */
	public StreamingZipDecompileSink(@Nonnull Path archivePath) throws IOException {
		this.archivePath = archivePath;
		Path parent = archivePath.getParent();
		if (parent != null)
			Files.createDirectories(parent);
		OutputStream out = new BufferedOutputStream(Files.newOutputStream(archivePath));
		this.zos = new ZipOutputStream(out, StandardCharsets.UTF_8);
	}

	/**
	 * @return File the archive is written to.
	 */
	@Nonnull
	public Path archivePath() {
		return archivePath;
	}

	@Override
	public void writeClass(@Nonnull ClassExportTask task, @Nonnull String text) throws IOException {
		write(task.outputPath(), text.getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public void writeResource(@Nonnull ResourceExportTask task, @Nonnull byte[] content) throws IOException {
		write(task.outputPath(), content);
	}

	@Override
	public void writeFailure(@Nonnull ClassExportTask task, @Nonnull String failureStub) throws IOException {
		write(task.outputPath(), failureStub.getBytes(StandardCharsets.UTF_8));
	}

	@Nonnull
	@Override
	public synchronized BatchSinkStats stats() {
		return new BatchSinkStats(fileCount, byteCount, duplicateCount);
	}

	@Override
	public synchronized void close() throws IOException {
		if (closed) return;
		closed = true;
		zos.close();
	}

	private synchronized void write(@Nonnull String outputPath, @Nonnull byte[] content) throws IOException {
		if (closed)
			throw new IOException("Archive sink already closed: " + archivePath);

		String entryName = BatchOutputPath.normalize(outputPath);
		if (entryName.isEmpty())
			return;
		if (!writtenPaths.add(entryName)) {
			duplicateCount++;
			logger.warn("Skipping duplicate archive entry '{}'", entryName);
			return;
		}

		zos.putNextEntry(new ZipEntry(entryName));
		zos.write(content);
		zos.closeEntry();
		fileCount++;
		byteCount += content.length;
	}
}
