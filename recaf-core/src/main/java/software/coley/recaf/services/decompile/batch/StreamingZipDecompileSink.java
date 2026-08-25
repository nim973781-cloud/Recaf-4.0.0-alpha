package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.decompile.batch.zip.DeflatedBlob;
import software.coley.recaf.services.decompile.batch.zip.ParallelDeflatePool;
import software.coley.recaf.services.decompile.batch.zip.RawZipWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Sink streaming every output file into a single archive.
 * <p>
 * Compression happens on the thread that produced the content and only the finished
 * {@link DeflatedBlob blob} is handed to {@link RawZipWriter}, whose writer thread does nothing but
 * append. The old {@code ZipOutputStream} did both under one lock, so every producer queued behind the
 * compressor and the run went no faster than a single core could deflate.
 * <p>
 * Entries are appended in the order the engine supplies them, which is plan order. Because a ZIP cannot
 * hold two entries with the same name, a repeated output path is rejected rather than written twice, and
 * counted in {@link #stats()}.
 *
 * @author Matt Coley
 */
public class StreamingZipDecompileSink implements BatchDecompileSink {
	private static final Logger logger = Logging.get(StreamingZipDecompileSink.class);
	/** How many finished entries may wait on the writer thread. */
	private static final int WRITE_QUEUE_DEPTH = 64;
	private final Set<String> writtenPaths = new HashSet<>();
	private final ParallelDeflatePool deflatePool = ParallelDeflatePool.shared();
	private final RawZipWriter writer;
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
		this.writer = new RawZipWriter(archivePath, WRITE_QUEUE_DEPTH);
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
		writeText(task.outputPath(), text);
	}

	@Override
	public void writeResource(@Nonnull ResourceExportTask task, @Nonnull byte[] content) throws IOException {
		String entryName = claim(task.outputPath());
		if (entryName == null)
			return;

		// A resource that already carries a compressed form is appended as-is rather than round-tripped
		// through the compressor again.
		Optional<DeflatedBlob> raw = task.raw();
		DeflatedBlob blob = raw.isPresent()
				? withName(raw.get(), entryName)
				: deflatePool.compress(entryName, content);
		append(blob);
	}

	@Override
	public void writeFailure(@Nonnull ClassExportTask task, @Nonnull String failureStub) throws IOException {
		writeText(task.outputPath(), failureStub);
	}

	@Nonnull
	@Override
	public synchronized BatchSinkStats stats() {
		return new BatchSinkStats(fileCount, byteCount, duplicateCount);
	}

	@Override
	public void close() throws IOException {
		synchronized (this) {
			if (closed) return;
			closed = true;
		}
		try {
			writer.close();
		} finally {
			deflatePool.close();
		}
	}

	private void writeText(@Nonnull String outputPath, @Nonnull String text) throws IOException {
		String entryName = claim(outputPath);
		if (entryName == null)
			return;
		append(deflatePool.compress(entryName, text.getBytes(StandardCharsets.UTF_8)));
	}

	private void append(@Nonnull DeflatedBlob blob) throws IOException {
		writer.append(blob);
		synchronized (this) {
			fileCount++;
			byteCount += blob.rawSize();
		}
	}

	/**
	 * Reserves an entry name, rejecting empty and duplicate paths.
	 *
	 * @param outputPath
	 * 		Path the engine wants to write.
	 *
	 * @return Normalized entry name, or {@code null} when nothing should be written.
	 *
	 * @throws IOException
	 * 		When the sink is already closed.
	 */
	private synchronized String claim(@Nonnull String outputPath) throws IOException {
		if (closed)
			throw new IOException("Archive sink already closed: " + archivePath);

		String entryName = BatchOutputPath.normalize(outputPath);
		if (entryName.isEmpty())
			return null;
		if (!writtenPaths.add(entryName)) {
			duplicateCount++;
			logger.warn("Skipping duplicate archive entry '{}'", entryName);
			return null;
		}
		return entryName;
	}

	@Nonnull
	private static DeflatedBlob withName(@Nonnull DeflatedBlob blob, @Nonnull String entryName) {
		if (blob.entryName().equals(entryName))
			return blob;
		return new DeflatedBlob(entryName, blob.data(), blob.rawSize(), blob.crc(), blob.deflated());
	}
}
