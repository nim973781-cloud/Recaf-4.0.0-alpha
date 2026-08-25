package software.coley.recaf.ui.control.popup;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.services.decompile.batch.BatchDecompileEngine;
import software.coley.recaf.services.decompile.batch.BatchDecompileException;
import software.coley.recaf.services.decompile.batch.BatchDecompileProgress;
import software.coley.recaf.services.decompile.batch.BatchDecompileReport;
import software.coley.recaf.services.decompile.batch.BatchDecompileRequest;
import software.coley.recaf.ui.pane.editing.jvm.DecompilerPaneConfig;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Adapter turning the {@link BatchDecompileJarsPopup} form into a {@link BatchDecompileRequest} and handing it to the
 * {@link BatchDecompileEngine}.
 * <p>
 * All batch behavior lives in the engine. This type only exists so the popup does not have to know how a request is
 * assembled, and so the "no inputs found" case can be surfaced as its own callback rather than an empty report.
 */
final class BatchDecompileJarsRunner {
	private final BatchDecompileEngine engine;
	private final DecompilerPaneConfig decompilerPaneConfig;

	/**
	 * @param engine
	 * 		Engine performing the run.
	 * @param decompilerPaneConfig
	 * 		Config supplying the per-class decompile timeout.
	 */
	BatchDecompileJarsRunner(@Nonnull BatchDecompileEngine engine,
	                         @Nonnull DecompilerPaneConfig decompilerPaneConfig) {
		this.engine = engine;
		this.decompilerPaneConfig = decompilerPaneConfig;
	}

	/**
	 * Runs the batch to completion on the calling thread.
	 *
	 * @param jarDir
	 * 		Directory holding the archives to decompile.
	 * @param mappingFile
	 * 		Optional mapping file to apply before decompiling.
	 * @param outputRoot
	 * 		Directory to write output into.
	 * @param mappingFormatName
	 * 		Name of the mapping format, required when a mapping file is given.
	 * @param decompilerName
	 * 		Name of the decompiler to use. Blank uses the configured target decompiler.
	 * @param callbacks
	 * 		Receives progress and the final outcome. Invoked on the calling thread.
	 *
	 * @throws BatchDecompileException
	 * 		When the run as a whole cannot be started or completed.
	 */
	void run(@Nonnull Path jarDir,
	         @Nullable Path mappingFile,
	         @Nonnull Path outputRoot,
	         @Nullable String mappingFormatName,
	         @Nonnull String decompilerName,
	         @Nonnull Callbacks callbacks) throws BatchDecompileException {
		BatchDecompileReport report = engine.run(buildRequest(jarDir, mappingFile, outputRoot, mappingFormatName, decompilerName),
				callbacks::onProgress);
		if (report.totalJars() == 0) {
			callbacks.onNoJarsFound();
			return;
		}
		callbacks.onComplete(report);
	}

	@Nonnull
	private BatchDecompileRequest buildRequest(@Nonnull Path jarDir,
	                                           @Nullable Path mappingFile,
	                                           @Nonnull Path outputRoot,
	                                           @Nullable String mappingFormatName,
	                                           @Nonnull String decompilerName) {
		int timeoutSeconds = Math.max(1, decompilerPaneConfig.getTimeoutSeconds().getValue());
		return BatchDecompileRequest.builder(jarDir, outputRoot)
				.mappings(mappingFile, mappingFormatName)
				.decompilerName(decompilerName)
				.timeoutPerClass(Duration.ofSeconds(timeoutSeconds))
				.build();
	}

	/**
	 * Outcome of a run, as the popup consumes it.
	 */
	interface Callbacks {
		/**
		 * Called instead of {@link #onComplete(BatchDecompileReport)} when the input directory held no archives.
		 */
		void onNoJarsFound();

		/**
		 * @param progress
		 * 		Latest progress snapshot. Already throttled by the engine.
		 */
		void onProgress(@Nonnull BatchDecompileProgress progress);

		/**
		 * @param report
		 * 		Summary of the finished run.
		 */
		void onComplete(@Nonnull BatchDecompileReport report);
	}
}
