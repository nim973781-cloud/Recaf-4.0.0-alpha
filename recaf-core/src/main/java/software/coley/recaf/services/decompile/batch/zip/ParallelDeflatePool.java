package software.coley.recaf.services.decompile.batch.zip;

import jakarta.annotation.Nonnull;
import software.coley.recaf.util.threading.DecompileParallelism;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Recycles {@link Deflater} instances so many threads can compress archive entries at once.
 * <p>
 * A {@code Deflater} owns a native window that costs far more to allocate than compressing a source file
 * costs to run, and it is not thread safe. Allocating one per entry burns most of the win of compressing
 * off the writer thread, and holding a single shared one puts every producer back in a queue. This pool
 * hands each caller an idle compressor, resets it on return, and caps how many are kept alive so a burst
 * of parallelism does not leave the native memory behind.
 *
 * @author Matt Coley
 */
public final class ParallelDeflatePool implements AutoCloseable {
	/** Compression level matching what {@code ZipOutputStream} uses by default. */
	public static final int DEFAULT_LEVEL = Deflater.DEFAULT_COMPRESSION;
	private final ConcurrentLinkedQueue<Deflater> idle = new ConcurrentLinkedQueue<>();
	private final AtomicInteger idleCount = new AtomicInteger();
	private final int level;
	private final int maxIdle;
	private volatile boolean closed;

	/**
	 * @param level
	 * 		Deflate compression level.
	 * @param maxIdle
	 * 		How many compressors to keep alive between uses.
	 */
	public ParallelDeflatePool(int level, int maxIdle) {
		this.level = level;
		this.maxIdle = Math.max(1, maxIdle);
	}

	/**
	 * @return Pool sized for the current machine, at the default compression level.
	 */
	@Nonnull
	public static ParallelDeflatePool shared() {
		return new ParallelDeflatePool(DEFAULT_LEVEL, DecompileParallelism.ioThreads());
	}

	/**
	 * Compresses the content on the calling thread.
	 *
	 * @param entryName
	 * 		Archive entry name.
	 * @param raw
	 * 		Uncompressed bytes.
	 *
	 * @return Compressed blob, or a {@link DeflatedBlob#stored(String, byte[]) stored} one when compression
	 * would not make the entry smaller.
	 */
	@Nonnull
	public DeflatedBlob compress(@Nonnull String entryName, @Nonnull byte[] raw) {
		CRC32 crc = new CRC32();
		crc.update(raw);
		if (raw.length == 0)
			return new DeflatedBlob(entryName, raw, 0, crc.getValue(), false);

		Deflater deflater = borrow();
		byte[] compressed;
		try {
			deflater.setInput(raw);
			deflater.finish();
			ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, raw.length / 3));
			byte[] buffer = new byte[8192];
			while (!deflater.finished()) {
				int read = deflater.deflate(buffer);
				if (read <= 0) break;
				out.write(buffer, 0, read);
			}
			compressed = out.toByteArray();
		} finally {
			release(deflater);
		}

		// A stored entry beats a deflated one that grew, which happens for already compressed resources.
		if (compressed.length >= raw.length)
			return new DeflatedBlob(entryName, raw, raw.length, crc.getValue(), false);
		return new DeflatedBlob(entryName, compressed, raw.length, crc.getValue(), true);
	}

	@Override
	public void close() {
		closed = true;
		Deflater deflater;
		while ((deflater = idle.poll()) != null) {
			idleCount.decrementAndGet();
			deflater.end();
		}
	}

	@Nonnull
	private Deflater borrow() {
		Deflater deflater = idle.poll();
		if (deflater == null)
			return new Deflater(level, true);
		idleCount.decrementAndGet();
		return deflater;
	}

	private void release(@Nonnull Deflater deflater) {
		deflater.reset();
		if (closed || idleCount.get() >= maxIdle) {
			deflater.end();
			return;
		}
		idle.add(deflater);
		idleCount.incrementAndGet();
	}
}
