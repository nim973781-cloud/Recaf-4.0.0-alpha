package software.coley.recaf.services.decompile.batch.zip;

import jakarta.annotation.Nonnull;
import software.coley.recaf.services.decompile.batch.pipeline.BoundedStageQueue;
import software.coley.recaf.util.threading.ThreadPoolFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Appends pre-compressed {@link DeflatedBlob blobs} into a ZIP archive from a single writer thread.
 * <p>
 * {@code ZipOutputStream} compresses on whichever thread calls {@code write}, and because a stream can
 * only have one entry open at a time every producer ends up serialized behind the compressor. A batch
 * decompile produces its output from many threads at once, so that single lock decided the throughput of
 * the whole run. This writer inverts the split: producers compress their own entry into a blob, and the
 * writer thread only concatenates finished blobs and remembers where each one landed. Entries land in
 * the order they were appended, so the archive stays deterministic.
 * <p>
 * Zip64 records are emitted when an entry lands past the four gigabyte mark, or when the archive exceeds
 * the entry count or directory size a classic end-of-central-directory record can describe.
 *
 * @author Matt Coley
 */
public final class RawZipWriter implements AutoCloseable {
	/** ZIP compression method for entries stored without compression. */
	public static final int METHOD_STORED = 0;
	/** ZIP compression method for deflate compressed entries. */
	public static final int METHOD_DEFLATED = 8;

	private static final int LOCAL_HEADER_SIGNATURE = 0x04034b50;
	private static final int CENTRAL_HEADER_SIGNATURE = 0x02014b50;
	private static final int END_SIGNATURE = 0x06054b50;
	private static final int ZIP64_END_SIGNATURE = 0x06064b50;
	private static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
	private static final int ZIP64_EXTRA_ID = 0x0001;
	private static final int FLAG_UTF8 = 0x0800;
	private static final int VERSION_DEFAULT = 20;
	private static final int VERSION_ZIP64 = 45;
	private static final long UINT32_MAX = 0xFFFFFFFFL;
	private static final int UINT16_MAX = 0xFFFF;
	/** Fixed MS-DOS timestamp of 1980-01-01, so the same inputs always produce the same archive. */
	private static final int DOS_TIME = 0;
	private static final int DOS_DATE = 0x0021;

	private final List<CentralEntry> directory = new ArrayList<>();
	private final BoundedStageQueue<DeflatedBlob> queue;
	private final CountDownLatch writerDone = new CountDownLatch(1);
	private final ExecutorService writerPool;
	private final AtomicLong rawByteCount = new AtomicLong();
	private final OutputStream out;
	private final Path archivePath;
	private final byte[] scratch = new byte[46];
	private volatile IOException failure;
	private long offset;
	private boolean closed;

	/**
	 * @param archivePath
	 * 		File to write the archive to. Any existing file is replaced.
	 * @param queueDepth
	 * 		How many finished blobs may wait on the writer thread before producers block.
	 *
	 * @throws IOException
	 * 		When the archive cannot be opened for writing.
	 */
	public RawZipWriter(@Nonnull Path archivePath, int queueDepth) throws IOException {
		this.archivePath = archivePath;
		Path parent = archivePath.getParent();
		if (parent != null)
			Files.createDirectories(parent);
		this.out = new BufferedOutputStream(Files.newOutputStream(archivePath), 1 << 16);
		this.queue = new BoundedStageQueue<>(queueDepth);
		this.writerPool = ThreadPoolFactory.newSingleThreadExecutor("batch-zip-writer", true);
		this.writerPool.execute(this::drain);
	}

	/**
	 * @return File the archive is written to.
	 */
	@Nonnull
	public Path archivePath() {
		return archivePath;
	}

	/**
	 * @return Number of entries appended so far.
	 */
	public long entryCount() {
		synchronized (directory) {
			return directory.size();
		}
	}

	/**
	 * @return Total uncompressed bytes appended so far.
	 */
	public long rawByteCount() {
		return rawByteCount.get();
	}

	/**
	 * Hands a finished blob to the writer thread. Blocks while the writer is behind.
	 *
	 * @param blob
	 * 		Compressed entry to append.
	 *
	 * @throws IOException
	 * 		When the writer thread already failed, or the caller is interrupted while waiting for room.
	 */
	public void append(@Nonnull DeflatedBlob blob) throws IOException {
		throwIfFailed();
		if (closed)
			throw new IOException("Archive already closed: " + archivePath);
		try {
			if (!queue.put(blob)) {
				throwIfFailed();
				throw new IOException("Archive writer stopped accepting entries: " + archivePath);
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("Interrupted while appending to " + archivePath);
		}
	}

	@Override
	public void close() throws IOException {
		if (closed) return;
		closed = true;
		queue.complete();
		try {
			writerDone.await();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			queue.abort();
		}
		writerPool.shutdownNow();

		try {
			throwIfFailed();
			writeCentralDirectory();
		} finally {
			out.close();
		}
	}

	private void drain() {
		try {
			DeflatedBlob blob;
			while ((blob = queue.take()) != null)
				writeEntry(blob);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		} catch (IOException ex) {
			failure = ex;
			queue.abort();
		} catch (Throwable t) {
			failure = new IOException("Archive writer thread failed for " + archivePath, t);
			queue.abort();
		} finally {
			writerDone.countDown();
		}
	}

	private void writeEntry(@Nonnull DeflatedBlob blob) throws IOException {
		byte[] name = blob.entryName().getBytes(StandardCharsets.UTF_8);
		long localOffset = offset;
		boolean zip64 = localOffset > UINT32_MAX;

		int index = 0;
		index = putInt(scratch, index, LOCAL_HEADER_SIGNATURE);
		index = putShort(scratch, index, zip64 ? VERSION_ZIP64 : VERSION_DEFAULT);
		index = putShort(scratch, index, FLAG_UTF8);
		index = putShort(scratch, index, blob.method());
		index = putShort(scratch, index, DOS_TIME);
		index = putShort(scratch, index, DOS_DATE);
		index = putInt(scratch, index, (int) blob.crc());
		index = putInt(scratch, index, blob.compressedSize());
		index = putInt(scratch, index, blob.rawSize());
		index = putShort(scratch, index, name.length);
		index = putShort(scratch, index, 0);
		out.write(scratch, 0, index);
		out.write(name);
		out.write(blob.data());
		offset += index + name.length + blob.data().length;

		synchronized (directory) {
			directory.add(new CentralEntry(name, blob.method(), blob.crc(),
					blob.compressedSize(), blob.rawSize(), localOffset));
		}
		rawByteCount.addAndGet(blob.rawSize());
	}

	private void writeCentralDirectory() throws IOException {
		List<CentralEntry> entries;
		synchronized (directory) {
			entries = List.copyOf(directory);
		}

		long directoryOffset = offset;
		for (CentralEntry entry : entries)
			writeCentralEntry(entry);
		long directorySize = offset - directoryOffset;

		boolean zip64 = entries.size() > UINT16_MAX || directoryOffset > UINT32_MAX || directorySize > UINT32_MAX;
		if (zip64)
			writeZip64End(entries.size(), directoryOffset, directorySize);

		byte[] end = new byte[22];
		int index = 0;
		index = putInt(end, index, END_SIGNATURE);
		index = putShort(end, index, 0);
		index = putShort(end, index, 0);
		index = putShort(end, index, zip64 ? UINT16_MAX : entries.size());
		index = putShort(end, index, zip64 ? UINT16_MAX : entries.size());
		index = putInt(end, index, (int) Math.min(directorySize, UINT32_MAX));
		index = putInt(end, index, (int) Math.min(directoryOffset, UINT32_MAX));
		putShort(end, index, 0);
		out.write(end);
		out.flush();
	}

	private void writeCentralEntry(@Nonnull CentralEntry entry) throws IOException {
		boolean zip64 = entry.localOffset > UINT32_MAX;
		int extraLength = zip64 ? 12 : 0;

		int index = 0;
		index = putInt(scratch, index, CENTRAL_HEADER_SIGNATURE);
		index = putShort(scratch, index, zip64 ? VERSION_ZIP64 : VERSION_DEFAULT);
		index = putShort(scratch, index, zip64 ? VERSION_ZIP64 : VERSION_DEFAULT);
		index = putShort(scratch, index, FLAG_UTF8);
		index = putShort(scratch, index, entry.method);
		index = putShort(scratch, index, DOS_TIME);
		index = putShort(scratch, index, DOS_DATE);
		index = putInt(scratch, index, (int) entry.crc);
		index = putInt(scratch, index, entry.compressedSize);
		index = putInt(scratch, index, entry.rawSize);
		index = putShort(scratch, index, entry.name.length);
		index = putShort(scratch, index, extraLength);
		index = putShort(scratch, index, 0);
		index = putShort(scratch, index, 0);
		index = putShort(scratch, index, 0);
		index = putInt(scratch, index, 0);
		index = putInt(scratch, index, zip64 ? (int) UINT32_MAX : (int) entry.localOffset);
		out.write(scratch, 0, index);
		out.write(entry.name);
		offset += index + entry.name.length;

		if (zip64) {
			byte[] extra = new byte[extraLength];
			int extraIndex = 0;
			extraIndex = putShort(extra, extraIndex, ZIP64_EXTRA_ID);
			extraIndex = putShort(extra, extraIndex, 8);
			putLong(extra, extraIndex, entry.localOffset);
			out.write(extra);
			offset += extraLength;
		}
	}

	private void writeZip64End(int entryCount, long directoryOffset, long directorySize) throws IOException {
		long zip64EndOffset = offset;

		byte[] record = new byte[56];
		int index = 0;
		index = putInt(record, index, ZIP64_END_SIGNATURE);
		index = putLong(record, index, 44);
		index = putShort(record, index, VERSION_ZIP64);
		index = putShort(record, index, VERSION_ZIP64);
		index = putInt(record, index, 0);
		index = putInt(record, index, 0);
		index = putLong(record, index, entryCount);
		index = putLong(record, index, entryCount);
		index = putLong(record, index, directorySize);
		putLong(record, index, directoryOffset);
		out.write(record);
		offset += record.length;

		byte[] locator = new byte[20];
		index = 0;
		index = putInt(locator, index, ZIP64_LOCATOR_SIGNATURE);
		index = putInt(locator, index, 0);
		index = putLong(locator, index, zip64EndOffset);
		putInt(locator, index, 1);
		out.write(locator);
		offset += locator.length;
	}

	private void throwIfFailed() throws IOException {
		IOException error = failure;
		if (error != null)
			throw new IOException("Archive writer failed for " + archivePath, error);
	}

	private static int putShort(@Nonnull byte[] buffer, int index, int value) {
		buffer[index] = (byte) (value & 0xFF);
		buffer[index + 1] = (byte) ((value >>> 8) & 0xFF);
		return index + 2;
	}

	private static int putInt(@Nonnull byte[] buffer, int index, int value) {
		buffer[index] = (byte) (value & 0xFF);
		buffer[index + 1] = (byte) ((value >>> 8) & 0xFF);
		buffer[index + 2] = (byte) ((value >>> 16) & 0xFF);
		buffer[index + 3] = (byte) ((value >>> 24) & 0xFF);
		return index + 4;
	}

	private static int putLong(@Nonnull byte[] buffer, int index, long value) {
		for (int i = 0; i < 8; i++)
			buffer[index + i] = (byte) ((value >>> (i * 8)) & 0xFF);
		return index + 8;
	}

	private record CentralEntry(@Nonnull byte[] name, int method, long crc,
	                            int compressedSize, int rawSize, long localOffset) {}
}
