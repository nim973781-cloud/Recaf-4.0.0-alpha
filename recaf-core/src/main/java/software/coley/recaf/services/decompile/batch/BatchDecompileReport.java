package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Machine readable summary of one batch decompile run.
 *
 * @param startedAt
 * 		When the run started.
 * @param endedAt
 * 		When the run finished.
 * @param totalJars
 * 		Number of input JARs the run considered.
 * @param okJars
 * 		Number of JARs that produced output without any failure.
 * @param skippedJars
 * 		Number of JARs that produced no output because they held nothing exportable.
 * @param failedJars
 * 		Number of JARs with at least one failure.
 * @param totalClasses
 * 		Number of classes planned for export across all JARs. Filtered classes are not counted here,
 * 		they are counted by {@code skippedClasses}.
 * @param okClasses
 * 		Number of classes decompiled and written successfully.
 * @param skippedClasses
 * 		Number of classes excluded by export filtering.
 * @param failedClasses
 * 		Number of classes that failed to decompile or write.
 * @param outputFileCount
 * 		Number of files/entries written by the sink.
 * @param resourceSha256
 * 		Map of output path to SHA-256 hex digest, for every copied resource.
 * @param failures
 * 		Every recorded failure, in the order it occurred.
 *
 * @author Matt Coley
 */
public record BatchDecompileReport(
		@Nonnull Instant startedAt,
		@Nonnull Instant endedAt,
		int totalJars,
		int okJars,
		int skippedJars,
		int failedJars,
		int totalClasses,
		int okClasses,
		int skippedClasses,
		int failedClasses,
		long outputFileCount,
		@Nonnull Map<String, String> resourceSha256,
		@Nonnull List<BatchDecompileFailure> failures
) {
	/**
	 * @return How long the run took.
	 */
	@Nonnull
	public Duration duration() {
		return Duration.between(startedAt, endedAt);
	}

	/**
	 * @return {@code true} when no failure of any kind was recorded.
	 */
	public boolean isClean() {
		return failures.isEmpty();
	}
}
