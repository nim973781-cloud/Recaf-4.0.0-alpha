package software.coley.recaf.util.io;

import jakarta.annotation.Nonnull;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Path byte source.
 *
 * @author xDark
 */
final class PathByteSource implements ByteSource {
	private final Path path;

	/**
	 * @param path
	 * 		Path to read bytes from.
	 */
	PathByteSource(Path path) {
		this.path = path;
	}

	@Nonnull
	@Override
	public byte[] readAll() throws IOException {
		return Files.readAllBytes(path);
	}

	@Nonnull
	@Override
	public byte[] peek(int count) throws IOException {
		try (InputStream in = openStream()) {
			return in.readNBytes(count);
		}
	}

	@Nonnull
	@Override
	public InputStream openStream() throws IOException {
		return Files.newInputStream(path);
	}

	@Nonnull
	@Override
	public MemorySegment mmap() throws IOException {
		try (FileChannel fc = FileChannel.open(path)) {
			return fc.map(FileChannel.MapMode.READ_ONLY, 0L, fc.size(), Arena.ofAuto());
		}
	}
}
