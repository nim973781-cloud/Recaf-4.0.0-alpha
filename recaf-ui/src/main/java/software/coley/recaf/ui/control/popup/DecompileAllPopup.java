package software.coley.recaf.ui.control.popup;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.GridPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import org.slf4j.Logger;
import software.coley.observables.ObservableObject;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.decompile.DecompileCacheMode;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.decompile.batch.BatchDecompileEngine;
import software.coley.recaf.services.decompile.batch.BatchDecompileFailure;
import software.coley.recaf.services.decompile.batch.BatchDecompileProgress;
import software.coley.recaf.services.decompile.batch.BatchDecompileReport;
import software.coley.recaf.services.decompile.batch.BatchOutputFormat;
import software.coley.recaf.services.decompile.batch.WorkspaceDecompileRequest;
import software.coley.recaf.ui.config.RecentFilesConfig;
import software.coley.recaf.ui.control.ActionButton;
import software.coley.recaf.ui.control.BoundLabel;
import software.coley.recaf.ui.control.ObservableComboBox;
import software.coley.recaf.ui.pane.editing.jvm.DecompilerPaneConfig;
import software.coley.recaf.ui.window.RecafScene;
import software.coley.recaf.ui.window.RecafStage;
import software.coley.recaf.util.*;
import software.coley.recaf.util.threading.ThreadPoolFactory;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Popup for initiating decompilation of all classes, saved to a specified location.
 * Supports exporting to ZIP archive or directory structure with .java files.
 * <p>
 * The popup only gathers the form values. Class collection, decompilation, scheduling and writing all
 * belong to {@link BatchDecompileEngine#exportWorkspace(WorkspaceDecompileRequest, software.coley.recaf.services.decompile.batch.BatchDecompileProgressListener)}.
 *
 * @author Matt Coley
 */
@Dependent
public class DecompileAllPopup extends RecafStage {
	private static final Logger logger = Logging.get(DecompileAllPopup.class);
	private static final ExecutorService exportPool = ThreadPoolFactory.newSingleThreadExecutor("decompile-all");
	private final ObjectProperty<Path> pathProperty = new SimpleObjectProperty<>();
	private final ObservableObject<JvmDecompiler> decompilerProperty;
	private final ObjectProperty<ExportFormat> formatProperty = new SimpleObjectProperty<>(ExportFormat.DIRECTORY);
	private final BooleanProperty includeResourcesProperty = new SimpleBooleanProperty(true);
	private final BooleanProperty inProgressProperty = new SimpleBooleanProperty();
	private final StringProperty currentClassProperty = new SimpleStringProperty("");
	private final StringProperty progressTextProperty = new SimpleStringProperty("");
	private final BatchDecompileEngine decompileEngine;
	private final DecompilerPaneConfig decompilerPaneConfig;
	private final Workspace workspace;
	private JvmClassBundle targetBundle;
	private String targetPackage;

	/**
	 * Export format options.
	 */
	public enum ExportFormat {
		/** Export as directory structure with .java files */
		DIRECTORY,
		/** Export as ZIP archive */
		ZIP_ARCHIVE;

		@Override
		public String toString() {
			return switch (this) {
				case DIRECTORY -> Lang.get("dialog.export.format.dir");
				case ZIP_ARCHIVE -> Lang.get("dialog.export.format.zip");
			};
		}

		@Nonnull
		private BatchOutputFormat toOutputFormat() {
			return this == DIRECTORY ? BatchOutputFormat.DIRECTORY : BatchOutputFormat.ZIP;
		}
	}

	@Inject
	public DecompileAllPopup(@Nonnull DecompilerManager decompilerManager,
	                         @Nonnull BatchDecompileEngine decompileEngine,
	                         @Nonnull RecentFilesConfig recentFilesConfig,
	                         @Nonnull DecompilerPaneConfig decompilerPaneConfig,
	                         @Nonnull Workspace workspace) {
		this.decompileEngine = decompileEngine;
		this.decompilerPaneConfig = decompilerPaneConfig;
		this.workspace = workspace;

		// Build names for directory and ZIP export
		String baseName = buildBaseName(workspace);  // e.g., "tacz-1.20.1-1.1.4-hotfix-all"
		String zipName = baseName + ".zip";

		decompilerProperty = new ObservableObject<>(decompilerManager.getTargetJvmDecompiler());
		// Default to directory export with folder name matching the JAR file name
		pathProperty.setValue(Paths.get(recentFilesConfig.getLastWorkspaceExportDirectory().getValue()).resolve(baseName));

		// UI Components
		Label decompilerLabel = new BoundLabel(Lang.getBinding("java.decompiler"));
		Label formatLabel = new BoundLabel(Lang.getBinding("dialog.export.format"));
		Label pathLabel = new BoundLabel(Lang.getBinding("menu.file.decompileall.path"));
		Label progressLabel = new Label();
		Label currentClassLabel = new Label();

		progressLabel.textProperty().bind(progressTextProperty);
		currentClassLabel.textProperty().bind(currentClassProperty.map(s -> s.isEmpty() ? "" : Lang.get("dialog.export.current") + " " + s));
		currentClassLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888888;");

		ObservableComboBox<JvmDecompiler> decompilerCombo = new ObservableComboBox<>(decompilerProperty, decompilerManager.getJvmDecompilers());

		ComboBox<ExportFormat> formatCombo = new ComboBox<>();
		formatCombo.getItems().addAll(ExportFormat.values());
		formatCombo.setValue(ExportFormat.DIRECTORY);
		formatCombo.valueProperty().bindBidirectional(formatProperty);

		// Checkbox for including resource files (JSON, PNG, etc.)
		CheckBox includeResourcesCheckBox = new CheckBox(Lang.get("dialog.export.includeresources"));
		includeResourcesCheckBox.setSelected(true);
		includeResourcesCheckBox.selectedProperty().bindBidirectional(includeResourcesProperty);

		ProgressBar progress = new ProgressBar(0);

		Button pathButton = new ActionButton(CarbonIcons.EDIT, pathProperty.map(p -> p == null ? "" : p.toString()), () -> {
			ExportFormat format = formatProperty.get();
			if (format == ExportFormat.DIRECTORY) {
				DirectoryChooser chooser = new DirectoryChooser();
				chooser.setTitle(Lang.get("dialog.file.open"));
				String lastDir = recentFilesConfig.getLastWorkspaceExportDirectory().getValue();
				if (lastDir != null && !lastDir.isEmpty()) {
					File dir = new File(lastDir);
					if (dir.exists()) {
						chooser.setInitialDirectory(dir);
					}
				}
				File selectedDir = chooser.showDialog(getScene().getWindow());
				if (selectedDir != null) {
					recentFilesConfig.getLastWorkspaceExportDirectory().setValue(selectedDir.getAbsolutePath());
					// Append the base name as subfolder
					pathProperty.set(selectedDir.toPath().resolve(baseName));
				}
			} else {
				FileChooser chooser = new FileChooserBuilder()
						.setInitialFileName(zipName)
						.setInitialDirectory(recentFilesConfig.getLastWorkspaceExportDirectory())
						.setFileExtensionFilter("Archives", "*.zip", "*.jar")
						.setTitle(Lang.get("dialog.file.open"))
						.build();
				File file = chooser.showSaveDialog(getScene().getWindow());
				if (file != null) {
					String parent = file.getParent();
					if (parent != null) recentFilesConfig.getLastWorkspaceExportDirectory().setValue(parent);
					pathProperty.set(file.toPath());
				}
			}
		});

		// Update path when format changes
		formatProperty.addListener((obs, oldVal, newVal) -> {
			Path currentPath = pathProperty.get();
			if (currentPath != null) {
				if (newVal == ExportFormat.DIRECTORY) {
					// Switch to directory: use parent directory and append base name as subfolder
					Path parentDir = currentPath.getParent() != null ? currentPath.getParent() : currentPath;
					pathProperty.set(parentDir.resolve(baseName));
				} else {
					// Switch to ZIP: use parent directory and append ZIP filename
					Path parentDir = currentPath.getParent() != null ? currentPath.getParent() : currentPath;
					pathProperty.set(parentDir.resolve(zipName));
				}
			}
		});

		Button decompileButton = new ActionButton(CarbonIcons.SAVE_SERIES, Lang.getBinding("menu.file.decompileall"), () -> {
			startDecompilation(progress);
		});
		decompileButton.disableProperty().bind(pathProperty.isNull().or(inProgressProperty));

		// Layout
		GridPane layout = new GridPane(8, 8);
		GridPane.setFillWidth(progress, true);
		GridPane.setFillWidth(decompilerCombo, true);
		GridPane.setFillWidth(formatCombo, true);
		GridPane.setFillWidth(decompileButton, true);
		GridPane.setFillWidth(pathButton, true);
		GridPane.setFillWidth(progressLabel, true);
		GridPane.setFillWidth(currentClassLabel, true);

		pathButton.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		decompileButton.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		decompilerCombo.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		formatCombo.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		progress.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

		int row = 0;
		layout.add(decompilerLabel, 0, row);
		layout.add(decompilerCombo, 1, row++);
		layout.add(formatLabel, 0, row);
		layout.add(formatCombo, 1, row++);
		layout.add(includeResourcesCheckBox, 0, row, 2, 1);
		row++;
		layout.add(pathLabel, 0, row);
		layout.add(pathButton, 1, row++);
		layout.add(decompileButton, 1, row++);
		layout.add(progressLabel, 0, row, 2, 1);
		row++;
		layout.add(currentClassLabel, 0, row, 2, 1);
		row++;
		layout.add(progress, 0, row, 2, 1);

		layout.setPadding(new Insets(10));
		layout.setAlignment(Pos.TOP_CENTER);

		setMinWidth(500);
		setMinHeight(280);
		setTitle(Lang.get("menu.file.decompileall"));
		setScene(new RecafScene(layout, 480, 260));
	}

	/**
	 * Start the decompilation process.
	 *
	 * @param progress Progress bar to update.
	 */
	private void startDecompilation(@Nonnull ProgressBar progress) {
		JvmDecompiler decompiler = decompilerProperty.getValue();
		Path basePath = pathProperty.get();
		if (decompiler == null || basePath == null)
			return;

		inProgressProperty.setValue(true);
		progress.setProgress(0);
		currentClassProperty.set("");
		progressTextProperty.set("");

		// Snapshot the settings on the FX thread. Everything the request describes is then run off it.
		WorkspaceDecompileRequest request = WorkspaceDecompileRequest.builder(workspace, basePath)
				.targetBundle(targetBundle)
				.packageFilter(targetPackage)
				.outputFormat(formatProperty.get().toOutputFormat())
				.decompilerName(decompiler.getName())
				.includeResources(includeResourcesProperty.get())
				.timeoutPerClass(Duration.ofSeconds(decompilerPaneConfig.getTimeoutSeconds().getValue()))
				// Reuse whatever the user already has cached, but do not fill the cache with every class of
				// the workspace just because they exported it once.
				.cacheMode(DecompileCacheMode.READ_ONLY)
				.build();

		// The engine already throttles its events, but a hop onto the FX thread per event still lands
		// bursts of work on the UI whenever several of them arrive close together. Coalescing to a fixed
		// rate means the popup repaints ten times a second no matter how fast the run is going.
		FxProgressPump pump = new FxProgressPump(event -> showProgress(progress, event));
		exportPool.submit(() -> {
			try {
				BatchDecompileReport report = decompileEngine.exportWorkspace(request, pump::submit);
				// Only counts fit in the popup, so the per-item detail goes to the log.
				for (BatchDecompileFailure failure : report.failures())
					logger.warn("Export failure [{}] in '{}': {}", failure.phase(),
							failure.className() == null ? failure.jarName() : failure.className(),
							failure.message());
				pump.stop();
				FxThreadUtil.run(() -> showReport(progress, report));
			} catch (Throwable t) {
				logger.error("Failed to export all classes of the workspace", t);
				pump.stop();
				FxThreadUtil.run(() -> {
					inProgressProperty.setValue(false);
					currentClassProperty.set("");
					progress.setProgress(0);
					progressTextProperty.set(Lang.get("dialog.export.batch.failed"));
				});
			}
		});
	}

	private void showProgress(@Nonnull ProgressBar progress, @Nonnull BatchDecompileProgress event) {
		String currentClass = event.currentClass();
		currentClassProperty.set(currentClass == null ? "" : currentClass);
		progress.setProgress(event.fraction());
		progressTextProperty.set(Lang.get("dialog.export.progress")
				.replace("{0}", String.valueOf((int) (event.fraction() * 100)))
				.replace("{1}", String.valueOf(event.completedClasses()))
				.replace("{2}", String.valueOf(event.totalClasses())));
	}

	private void showReport(@Nonnull ProgressBar progress, @Nonnull BatchDecompileReport report) {
		inProgressProperty.setValue(false);
		currentClassProperty.set("");
		if (report.totalClasses() == 0 && report.outputFileCount() == 0) {
			progress.setProgress(0);
			progressTextProperty.set(Lang.get("menu.search.noresults"));
			return;
		}
		progress.setProgress(1);
		progressTextProperty.set(Lang.get("dialog.export.complete")
				+ " (" + (report.okClasses() + report.failedClasses()) + "/" + report.totalClasses() + ")");
	}

	/**
	 * Forwards at most one progress event onto the FX thread per {@link #INTERVAL_MS}, always the most
	 * recent one. Events that arrive while an update is already pending replace it instead of queueing
	 * another {@code runLater}, so a fast run cannot flood the FX queue with stale snapshots.
	 */
	private static final class FxProgressPump {
		private static final long INTERVAL_MS = 100;
		private final AtomicReference<BatchDecompileProgress> pending = new AtomicReference<>();
		private final AtomicBoolean scheduled = new AtomicBoolean();
		private final Consumer<BatchDecompileProgress> action;
		private volatile boolean stopped;

		private FxProgressPump(@Nonnull Consumer<BatchDecompileProgress> action) {
			this.action = action;
		}

		private void submit(@Nonnull BatchDecompileProgress event) {
			pending.set(event);
			if (stopped || !scheduled.compareAndSet(false, true))
				return;
			FxThreadUtil.delayedRun(INTERVAL_MS, () -> {
				scheduled.set(false);
				BatchDecompileProgress latest = pending.getAndSet(null);
				if (latest != null && !stopped)
					action.accept(latest);
			});
		}

		/**
		 * Stops delivery, so a late update cannot overwrite the final report the caller is about to show.
		 */
		private void stop() {
			stopped = true;
			pending.set(null);
		}
	}

	/**
	 * Build a base name from the workspace file name (without extension).
	 * For example: "tacz-1.20.1-1.1.4-hotfix-all.jar" -> "tacz-1.20.1-1.1.4-hotfix-all"
	 *
	 * @param workspace The workspace to get the name from.
	 * @return The base name derived from the workspace file.
	 */
	@Nonnull
	private static String buildBaseName(@Nonnull Workspace workspace) {
		if (workspace.getPrimaryResource() instanceof WorkspaceFileResource fileResource) {
			return StringUtil.removeExtension(StringUtil.shortenPath(fileResource.getFileInfo().getName()));
		}
		return "decompiled";
	}

	/**
	 * @param targetBundle Bundle to target for decompilation. When left unset the whole primary resource
	 *                     is exported, including multi-release and embedded archive classes.
	 */
	public void setTargetBundle(@Nonnull JvmClassBundle targetBundle) {
		this.targetBundle = targetBundle;
	}

	/**
	 * @param packageName Package name prefix to filter classes (e.g., "com/example/").
	 *                    Use {@code null} to include all classes.
	 */
	public void setTargetPackage(@Nullable String packageName) {
		this.targetPackage = packageName;
	}
}
