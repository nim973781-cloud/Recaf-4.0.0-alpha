package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;

import java.nio.file.Path;

/**
 * Normalization of the {@code /} separated output paths carried by {@link ClassExportTask} and
 * {@link ResourceExportTask}.
 * <p>
 * Archive entry names come from untrusted input, so absolute prefixes and {@code ..} segments are
 * dropped rather than being allowed to escape the sink root.
 *
 * @author Matt Coley
 */
final class BatchOutputPath {
	private BatchOutputPath() {}

	/**
	 * @param prefix
	 * 		Parent path, either empty or already normalized.
	 * @param name
	 * 		Child path.
	 *
	 * @return Normalized {@code prefix/name}.
	 */
	@Nonnull
	static String join(@Nonnull String prefix, @Nonnull String name) {
		if (prefix.isEmpty())
			return normalize(name);
		return normalize(prefix + '/' + name);
	}

	/**
	 * @param path
	 * 		Raw path, possibly using {@code \} separators or containing relative segments.
	 *
	 * @return Path using {@code /} separators, with no empty, {@code .} or {@code ..} segments.
	 */
	@Nonnull
	static String normalize(@Nonnull String path) {
		String replaced = path.replace('\\', '/');
		StringBuilder builder = new StringBuilder(replaced.length());
		int start = 0;
		int length = replaced.length();
		while (start < length) {
			int end = replaced.indexOf('/', start);
			if (end < 0) end = length;
			String segment = replaced.substring(start, end);
			start = end + 1;
			if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
				continue;
			if (!builder.isEmpty()) builder.append('/');
			builder.append(segment);
		}
		return builder.toString();
	}

	/**
	 * @param root
	 * 		Sink root directory.
	 * @param relative
	 * 		Normalized relative path.
	 *
	 * @return Platform path under the root.
	 */
	@Nonnull
	static Path resolve(@Nonnull Path root, @Nonnull String relative) {
		Path resolved = root;
		for (String segment : normalize(relative).split("/"))
			if (!segment.isEmpty())
				resolved = resolved.resolve(segment);
		return resolved;
	}
}
