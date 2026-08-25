package software.coley.recaf.services.decompile.batch.zip;

import jakarta.annotation.Nonnull;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * One archive entry that has already been compressed and checksummed, ready to be appended verbatim.
 * <p>
 * Splitting an entry into "compress it" and "append it" is what lets {@link RawZipWriter} keep a single
 * writer thread. Compression is the expensive part and happens on whichever thread produced the content,
 * while the writer only concatenates finished blobs, so the archive stays byte-for-byte ordered without
 * serializing the CPU work behind the file handle.
 *
 * @param entryName
 * 		Archive entry name, always {@code /} separated.
 * @param data
 * 		Entry payload as it goes on disk: raw deflate output when {@link #deflated()}, plain bytes otherwise.
 * @param rawSize
 * 		Uncompressed size in bytes.
 * @param crc
 * 		CRC-32 of the uncompressed bytes.
 * @param deflated
 * 		{@code true} when {@link #data()} is deflate compressed, {@code false} when it is stored as-is.
 *
 * @author Matt Coley
 */
public record DeflatedBlob(@Nonnull String entryName, @Nonnull byte[] data, int rawSize, long crc, boolean deflated) {
	/**
	 * @param entryName
	 * 		Archive entry name.
	 * @param raw
	 * 		Uncompressed bytes.
	 *
	 * @return Blob storing the bytes without compression.
	 */
	@Nonnull
	public static DeflatedBlob stored(@Nonnull String entryName, @Nonnull byte[] raw) {
		CRC32 crc = new CRC32();
		crc.update(raw);
		return new DeflatedBlob(entryName, raw, raw.length, crc.getValue(), false);
	}

	/**
	 * @param entryName
	 * 		Archive entry name.
	 * @param text
	 * 		Text to store as UTF-8.
	 * @param pool
	 * 		Pool supplying the compressor.
	 *
	 * @return Compressed blob.
	 */
	@Nonnull
	public static DeflatedBlob compress(@Nonnull String entryName, @Nonnull String text,
	                                    @Nonnull ParallelDeflatePool pool) {
		return pool.compress(entryName, text.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * @return Number of bytes this entry occupies in the archive payload.
	 */
	public int compressedSize() {
		return data.length;
	}

	/**
	 * @return ZIP compression method identifier.
	 */
	public int method() {
		return deflated ? RawZipWriter.METHOD_DEFLATED : RawZipWriter.METHOD_STORED;
	}
}
