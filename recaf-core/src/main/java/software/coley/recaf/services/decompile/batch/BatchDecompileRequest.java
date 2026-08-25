package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.util.threading.DecompileParallelism;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Immutable description of one batch decompile run.
 *
 * @param inputDirectory
 * 		Directory holding the {@code jar}/{@code zip} inputs to process.
 * @param mappingFile
 * 		Optional mapping file to apply to every input before decompiling.
 * @param mappingFormat
 * 		Name of the mapping format, as registered with
 *        {@link software.coley.recaf.services.mapping.format.MappingFormatManager}.
 * 		Required when {@code mappingFile} is set.
 * @param outputPath
 * 		Output directory <i>({@link BatchOutputFormat#DIRECTORY})</i> or archive file
 * 		<i>({@link BatchOutputFormat#ZIP})</i>.
 * @param outputFormat
 * 		Shape of the output.
 * @param decompilerName
 * 		Name of the {@link software.coley.recaf.services.decompile.JvmDecompiler} to use.
 * 		Blank uses {@link software.coley.recaf.services.decompile.DecompilerManager#getTargetJvmDecompiler()}.
 * @param includeResources
 * 		Copy non-class files into the output.
 * @param decompileWorkers
 * 		Number of concurrent class decompilations. Zero or less means auto:
 *        {@link DecompileParallelism#decompileThreads()} (70% of detected cores).
 * @param ioWorkers
 * 		Number of concurrent resource writes. Zero or less means auto:
 *        {@link DecompileParallelism#ioThreads()}.
 * @param timeoutPerClass
 * 		How long a single class may spend in the decompiler before being recorded as a timeout.
 * @param accuracyMode
 * 		Which decompilation path the engine may take.
 * @param includeEmbeddedResources
 * 		Descend into embedded archives <i>(JAR-in-JAR)</i>.
 * @param includeMultiReleaseClasses
 * 		Export classes found under {@code META-INF/versions/<n>/}.
 * @param writeFailureStubs
 * 		Write a {@link BatchFailureStub} comment file for classes that fail to decompile.
 * @param reportPath
 * 		Optional path to write the JSON run report to.
 *
 * @author Matt Coley
 */
public record BatchDecompileRequest(
		@Nonnull Path inputDirectory,
		@Nullable Path mappingFile,
		@Nullable String mappingFormat,
		@Nonnull Path outputPath,
		@Nonnull BatchOutputFormat outputFormat,
		@Nonnull String decompilerName,
		boolean includeResources,
		int decompileWorkers,
		int ioWorkers,
		@Nonnull Duration timeoutPerClass,
		@Nonnull BatchAccuracyMode accuracyMode,
		boolean includeEmbeddedResources,
		boolean includeMultiReleaseClasses,
		boolean writeFailureStubs,
		@Nullable Path reportPath
) {
	/**
	 * @return Effective class decompile worker count.
	 */
	public int normalizedDecompileWorkers() {
		return decompileWorkers <= 0
				? DecompileParallelism.decompileThreads()
				: decompileWorkers;
	}

	/**
	 * @return Effective resource IO worker count.
	 */
	public int normalizedIoWorkers() {
		return ioWorkers <= 0
				? DecompileParallelism.ioThreads()
				: ioWorkers;
	}

	/**
	 * @return {@code true} when a mapping file was configured.
	 */
	public boolean hasMappings() {
		return mappingFile != null;
	}

	/**
	 * @param inputDirectory
	 * 		Directory holding the inputs to process.
	 * @param outputPath
	 * 		Output directory or archive file.
	 *
	 * @return New builder seeded with the engine defaults.
	 */
	@Nonnull
	public static Builder builder(@Nonnull Path inputDirectory, @Nonnull Path outputPath) {
		return new Builder(inputDirectory, outputPath);
	}

	/**
	 * Builder for {@link BatchDecompileRequest}, since the record has a wide constructor.
	 */
	public static class Builder {
		private final Path inputDirectory;
		private final Path outputPath;
		private Path mappingFile;
		private String mappingFormat;
		private BatchOutputFormat outputFormat = BatchOutputFormat.DIRECTORY;
		private String decompilerName = "";
		private boolean includeResources = true;
		private int decompileWorkers;
		private int ioWorkers;
		private Duration timeoutPerClass = Duration.ofSeconds(60);
		private BatchAccuracyMode accuracyMode = BatchAccuracyMode.ACCURATE;
		private boolean includeEmbeddedResources = true;
		private boolean includeMultiReleaseClasses = true;
		private boolean writeFailureStubs = true;
		private Path reportPath;

		private Builder(@Nonnull Path inputDirectory, @Nonnull Path outputPath) {
			this.inputDirectory = Objects.requireNonNull(inputDirectory, "inputDirectory");
			this.outputPath = Objects.requireNonNull(outputPath, "outputPath");
		}

		/**
		 * @param mappingFile
		 * 		Mapping file to apply.
		 * @param mappingFormat
		 * 		Name of the mapping format.
		 *
		 * @return Builder.
		 */
		@Nonnull
		public Builder mappings(@Nullable Path mappingFile, @Nullable String mappingFormat) {
			this.mappingFile = mappingFile;
			this.mappingFormat = mappingFormat;
			return this;
		}

		/**
		 * @param outputFormat
		 * 		Shape of the output.
		 *
		 * @return Builder.
		 */
		@Nonnull
		public Builder outputFormat(@Nonnull BatchOutputFormat outputFormat) {
			this.outputFormat = outputFormat;
			return this;
		}

		/**
		 * @param decompilerName
		 * 		Name of the decompiler to use.
		 *
		 * @return Builder.
		 */
		@Nonnull
		public Builder decompilerName(@Nonnull String decompilerName) {
			this.decompilerName = decompilerName;
			return this;
		}

		/**
		 * @param includeResources
		 * 		Copy non-class files into the output.
		 *
		 * @return Builder.
		 */
		@Nonnull
		public Builder includeResources(boolean includeResources) {
			this.includeResources = includeResources;
			return this;
		}

		/**
		 * @param decompileWorkers
		 * 		Number of concurrent class decompilations. Zero or less means auto
		 *        ({@link DecompileParallelism#decompileThreads()}).
		 * @param ioWorkers
		 * 		Number of concurrent resource writes. Zero or less means auto
		 *        ({@link DecompileParallelism#ioThreads()}).
		 *
		 * @return Builder.
		 */
		@Nonnull
		public Builder workers(int decompileWorkers, int ioWorkers) {
			this.decompileWorkers = decompileWorkers;
			this.ioWorkers = ioWorkers;
			return this;
		}

		/**
		 * @param timeoutPerClass
		 * 		How long a single class may spend in the decompiler.
		 *
		 * @return Builder.
		 */
		@Nonnull
		public Builder timeoutPerClass(@Nonnull Duration timeoutPerClass) {
			this.timeoutPerClass = timeoutPerClass;
			return this;
		}

		/**
		 * @param accuracyMode
		 * 		Which decompilation path the engine may take.
		 *
		 * @return Builder.
		 */
		@Nonnull
		public Builder accuracyMode(@Nonnull BatchAccuracyMode accuracyMode) {
			this.accuracyMode = accuracyMode;
			return this;
		}

		/**
		 * @param includeEmbeddedResources
		 * 		Descend into embedded archives.
		 *
		 * @return Builder.
		 */
		@Nonnull
		public Builder includeEmbeddedResources(boolean includeEmbeddedResources) {
			this.includeEmbeddedResources = includeEmbeddedResources;
			return this;
		}

		/**
		 * @param includeMultiReleaseClasses
		 * 		Export classes found under {@code META-INF/versions/<n>/}.
		 *
		 * @return Builder.
		 */
		@Nonnull
		public Builder includeMultiReleaseClasses(boolean includeMultiReleaseClasses) {
			this.includeMultiReleaseClasses = includeMultiReleaseClasses;
			return this;
		}

		/**
		 * @param writeFailureStubs
		 * 		Write a stub file for classes that fail to decompile.
		 *
		 * @return Builder.
		 */
		@Nonnull
		public Builder writeFailureStubs(boolean writeFailureStubs) {
			this.writeFailureStubs = writeFailureStubs;
			return this;
		}

		/**
		 * @param reportPath
		 * 		Path to write the JSON run report to.
		 *
		 * @return Builder.
		 */
		@Nonnull
		public Builder reportPath(@Nullable Path reportPath) {
			this.reportPath = reportPath;
			return this;
		}

		/**
		 * @return Built request.
		 */
		@Nonnull
		public BatchDecompileRequest build() {
			return new BatchDecompileRequest(inputDirectory, mappingFile, mappingFormat, outputPath, outputFormat,
					decompilerName, includeResources, decompileWorkers, ioWorkers, timeoutPerClass, accuracyMode,
					includeEmbeddedResources, includeMultiReleaseClasses, writeFailureStubs, reportPath);
		}
	}
}
