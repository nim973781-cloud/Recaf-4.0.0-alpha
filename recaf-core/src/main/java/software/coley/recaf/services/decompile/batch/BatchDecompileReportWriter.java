package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Serializes a {@link BatchDecompileReport} to JSON.
 * <p>
 * Output is deterministic: maps are emitted in sorted key order and failures keep the order they were
 * recorded in, so two runs over the same input produce byte-identical reports.
 *
 * @author Matt Coley
 */
public final class BatchDecompileReportWriter {
	private BatchDecompileReportWriter() {}

	/**
	 * @param report
	 * 		Report to serialize.
	 * @param path
	 * 		File to write to. Parent directories are created as needed.
	 *
	 * @throws IOException
	 * 		When the report cannot be written.
	 */
	public static void write(@Nonnull BatchDecompileReport report, @Nonnull Path path) throws IOException {
		Path parent = path.getParent();
		if (parent != null)
			Files.createDirectories(parent);
		Files.writeString(path, toJson(report), StandardCharsets.UTF_8);
	}

	/**
	 * @param report
	 * 		Report to serialize.
	 *
	 * @return JSON text of the report.
	 */
	@Nonnull
	public static String toJson(@Nonnull BatchDecompileReport report) {
		StringBuilder sb = new StringBuilder();
		sb.append("{\n");
		appendString(sb, "startedAt", report.startedAt().toString()).append(",\n");
		appendString(sb, "endedAt", report.endedAt().toString()).append(",\n");
		appendNumber(sb, "durationMs", report.duration().toMillis()).append(",\n");
		appendNumber(sb, "totalJars", report.totalJars()).append(",\n");
		appendNumber(sb, "okJars", report.okJars()).append(",\n");
		appendNumber(sb, "skippedJars", report.skippedJars()).append(",\n");
		appendNumber(sb, "failedJars", report.failedJars()).append(",\n");
		appendNumber(sb, "totalClasses", report.totalClasses()).append(",\n");
		appendNumber(sb, "okClasses", report.okClasses()).append(",\n");
		appendNumber(sb, "skippedClasses", report.skippedClasses()).append(",\n");
		appendNumber(sb, "failedClasses", report.failedClasses()).append(",\n");
		appendNumber(sb, "outputFileCount", report.outputFileCount()).append(",\n");

		sb.append("\t\"resourceSha256\": {");
		Map<String, String> sorted = new TreeMap<>(report.resourceSha256());
		boolean first = true;
		for (Map.Entry<String, String> entry : sorted.entrySet()) {
			if (!first) sb.append(',');
			first = false;
			sb.append("\n\t\t");
			escape(sb, entry.getKey()).append(": ");
			escape(sb, entry.getValue());
		}
		sb.append(sorted.isEmpty() ? "}" : "\n\t}").append(",\n");

		sb.append("\t\"failures\": [");
		first = true;
		for (BatchDecompileFailure failure : report.failures()) {
			if (!first) sb.append(',');
			first = false;
			sb.append("\n\t\t{");
			sb.append("\"jarName\": ");
			escape(sb, failure.jarName()).append(", \"className\": ");
			escape(sb, failure.className()).append(", \"phase\": ");
			escape(sb, failure.phase()).append(", \"message\": ");
			escape(sb, failure.message()).append(", \"trace\": ");
			escape(sb, failure.trace()).append('}');
		}
		sb.append(report.failures().isEmpty() ? "]" : "\n\t]").append('\n');
		sb.append("}\n");
		return sb.toString();
	}

	@Nonnull
	private static StringBuilder appendString(@Nonnull StringBuilder sb, @Nonnull String key, @Nullable String value) {
		sb.append("\t\"").append(key).append("\": ");
		return escape(sb, value);
	}

	@Nonnull
	private static StringBuilder appendNumber(@Nonnull StringBuilder sb, @Nonnull String key, long value) {
		return sb.append("\t\"").append(key).append("\": ").append(value);
	}

	@Nonnull
	private static StringBuilder escape(@Nonnull StringBuilder sb, @Nullable String value) {
		if (value == null)
			return sb.append("null");
		sb.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				default -> {
					if (c < 0x20)
						sb.append(String.format("\\u%04x", (int) c));
					else
						sb.append(c);
				}
			}
		}
		return sb.append('"');
	}
}
