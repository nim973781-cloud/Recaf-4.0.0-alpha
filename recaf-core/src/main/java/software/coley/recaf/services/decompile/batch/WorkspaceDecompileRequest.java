package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.services.decompile.DecompileCacheMode;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Immutable description of exporting the classes of an already open {@link Workspace}.
 * <p>
 * Unlike {@link BatchDecompileRequest} there is no input path and no mapping file. The workspace is
 * used as-is, including any edits the user has made, so nothing is round-tripped through a temporary
 * archive first.
 *
 * @param workspace
 * 		Workspace to export the {@link Workspace#getPrimaryResource() primary resource} of.
 * @param targetBundle
 * 		Single bundle to export. When {@code null} the whole primary resource is walked, which also
 * 		covers {@code includeEmbeddedResources} and {@code includeMultiReleaseClasses}.
 * @param packageFilter
 * 		Internal package prefix limiting which classes and files are exported, for example
 *        {@code com/example/}. When {@code null} or blank everything is exported.
 * @param outputPath
 * 		Output directory <i>({@link BatchOutputFormat#DIRECTORY})</i> or archive file
 * 		<i>({@link BatchOutputFormat#ZIP})</i>.
 * @param outputFormat
 * 		Shape of the output.
 * @param outputName
 * 		Sub-path within the output that everything is written under. Empty writes to the output root.
 * @param decompilerName
 * 		Name of the {@link software.coley.recaf.services.decompile.JvmDecompiler} to use.
 * 		Blank uses {@link software.coley.recaf.services.decompile.DecompilerManager#getTargetJvmDecompiler()}.
 * @param includeResources
 * 		Copy non-class files into the output.
 * @param decompileWorkers
 * 		Number of concurrent class decompilations. Zero or less means auto.
 * @param ioWorkers
 * 		Number of concurrent resource writes. Zero or less means auto.
 * @param timeoutPerClass
 * 		How long a single class may spend in the decompiler before being recorded as a timeout.
 * @param cacheMode
 * 		How the export interacts with the {@link software.coley.recaf.info.properties.builtin.CachedDecompileProperty
 * 		per-class decompilation cache}. Exporting a workspace should not evict or fill what the user
 * 		has open, so {@link DecompileCacheMode#READ_ONLY} is the default.
 * @param includeEmbeddedResources
 * 		Descend into embedded archives <i>(JAR-in-JAR)</i>, writing them under {@code _embedded/}.
 * @param includeMultiReleaseClasses
 * 		Export classes found under {@code META-INF/versions/<n>/}.
 * @param writeFailureStubs
 * 		Write a {@link BatchFailureStub} comment file for classes that fail to decompile.
 * @param reportPath
 * 		Optional path to write the JSON run report to.
 *
 * @author Matt Coley
 */
public record WorkspaceDecompileRequest(
		@Nonnull Workspace workspace,
		@Nullable JvmClassBundle targetBundle,
		@Nullable String packageFilter,
		@Nonnull Path outputPath,
		@Nonnull BatchOutputFormat outputFormat,
		@Nonnull String outputName,
		@Nonnull String decompilerName,
		boolean includeResources,
		int decompileWorkers,
		int ioWorkers,
		@Nonnull Duration timeoutPerClass,
		@Nonnull DecompileCacheMode cacheMode,
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
				? Math.max(2, Runtime.getRuntime().availableProcessors() - 2)
				: decompileWorkers;
	}

	/**
	 * @return Effective resource IO worker count.
	 */
	public int normalizedIoWorkers() {
		return ioWorkers <= 0
				? Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2))
				: ioWorkers;
	}

	/**
	 * @return Package prefix ending in {@code /}, or {@code null} when everything is exported.
	 */
	@Nullable
	public String normalizedPackageFilter() {
		if (packageFilter == null)
			return null;
		String trimmed = packageFilter.replace('.', '/').trim();
		if (trimmed.isEmpty())
			return null;
		return trimmed.endsWith("/") ? trimmed : trimmed + '/';
	}

	/**
	 * @param workspace
	 * 		Workspace to export.
	 * @param outputPath
	 * 		Output directory or archive file.
	 *
	 * @return New builder seeded with the engine defaults.
	 */
	@Nonnull
	public static Builder builder(@Nonnull Workspace workspace, @Nonnull Path outputPath) {
		return new Builder(workspace, outputPath);
	}

	/**
	 * Builder for {@link WorkspaceDecompileRequest}, since the record has a wide constructor.
	 */
	public static class Builder {
		private final Workspace workspace;
		private final Path outputPath;
		private JvmClassBundle targetBundle;
		private String packageFilter;
		private BatchOutputFormat outputFormat = BatchOutputFormat.DIRECTORY;
		private String outputName = "";
		private String decompilerName = "";
		private boolean includeResources = true;
		private int decompileWorkers;
		private int ioWorkers;
		private Duration timeoutPerClass = Duration.ofSeconds(60);
		private DecompileCacheMode cacheMode = DecompileCacheMode.READ_ONLY;
		private boolean includeEmbeddedResources = true;
		private boolean includeMultiReleaseClasses = true;
		private boolean writeFailureStubs = true;
		private Path reportPath;

		private Builder(@Nonnull Workspace workspace, @Nonnull Path outputPath) {
			this.workspace = Objects.requireNonNull(workspace, "workspace");
			this.outputPath = Objects.requireNonNull(outputPath, "outputPath");
		}

		/**
		 * @param targetBundle
		 * 		Single bundle to export, or {@code null} to walk the whole primary resource.
		 *
		 * @return Builder.
		 */
		@Nonnull
		public Builder targetBundle(@Nullable JvmClassBundle targetBundle) {
			this.targetBundle = targetBundle;
			return this;
		}

		/**
		 * @param packageFilter
		 * 		Internal package prefix limiting what is exported.
		 *
		 * @return Builder.
		 */
		@Nonnull
		public Builder packageFilter(@Nullable String packageFilter) {
			this.packageFilter = packageFilter;
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
		 * @param outputName
		 * 		Sub-path within the output that everything is written under.
		 *
		 * @return Builder.
		 */
		@Nonnull
		public Builder outputName(@Nonnull String outputName) {
			this.outputName = outputName;
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
		 * 		Number of concurrent class decompilations.
		 * @param ioWorkers
		 * 		Number of concurrent resource writes.
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
		 * @param cacheMode
		 * 		How the export interacts with the per-class decompilation cache.
		 *
		 * @return Builder.
		 */
		@Nonnull
		public Builder cacheMode(@Nonnull DecompileCacheMode cacheMode) {
			this.cacheMode = cacheMode;
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
		public WorkspaceDecompileRequest build() {
			return new WorkspaceDecompileRequest(workspace, targetBundle, packageFilter, outputPath, outputFormat,
					outputName, decompilerName, includeResources, decompileWorkers, ioWorkers, timeoutPerClass,
					cacheMode, includeEmbeddedResources, includeMultiReleaseClasses, writeFailureStubs, reportPath);
		}
	}
}
