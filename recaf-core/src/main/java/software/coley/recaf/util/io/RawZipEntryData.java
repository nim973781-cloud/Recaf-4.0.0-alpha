package software.coley.recaf.util.io;

import jakarta.annotation.Nonnull;
import software.coley.recaf.info.properties.Property;

import java.util.Arrays;

/**
 * Raw data of an entry in a ZIP archive.
 *
 * @param compressedBytes
 * 		Entry bytes exactly as stored in the archive.
 * @param crc32
 * 		CRC-32 of the uncompressed entry.
 * @param uncompressedSize
 * 		Uncompressed entry size.
 */
public record RawZipEntryData(@Nonnull byte[] compressedBytes, long crc32, long uncompressedSize)
		implements Property<RawZipEntryData> {
	public static final String KEY = "raw-zip-entry";

	@Nonnull
	@Override
	public String key() {
		return KEY;
	}

	@Nonnull
	@Override
	public RawZipEntryData value() {
		return this;
	}

	@Override
	public boolean persistent() {
		// Any bytecode or file-content change invalidates the original compressed representation.
		return false;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (!(obj instanceof RawZipEntryData other)) return false;
		return crc32 == other.crc32
				&& uncompressedSize == other.uncompressedSize
				&& Arrays.equals(compressedBytes, other.compressedBytes);
	}

	@Override
	public int hashCode() {
		int result = Arrays.hashCode(compressedBytes);
		result = 31 * result + Long.hashCode(crc32);
		result = 31 * result + Long.hashCode(uncompressedSize);
		return result;
	}
}
