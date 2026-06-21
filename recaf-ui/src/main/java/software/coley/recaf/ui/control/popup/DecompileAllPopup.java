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
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.ui.config.RecentFilesConfig;
import software.coley.recaf.ui.control.ActionButton;
import software.coley.recaf.ui.control.BoundLabel;
import software.coley.recaf.ui.control.ObservableComboBox;
import software.coley.recaf.ui.pane.editing.jvm.DecompilerPaneConfig;
import software.coley.recaf.ui.window.RecafScene;
import software.coley.recaf.ui.window.RecafStage;
import software.coley.recaf.util.*;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.FileBundle;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Popup for initiating decompilation of all classes, saved to a specified location.
 * Supports exporting to ZIP archive or directory structure with .java files.
 *
 * @author Matt Coley
 */
@Dependent
public class DecompileAllPopup extends RecafStage {
	private static final Logger logger = Logging.get(DecompileAllPopup.class);
	private final ObjectProperty<Path> pathProperty = new SimpleObjectProperty<>();
	private final ObservableObject<JvmDecompiler> decompilerProperty;
	private final ObjectProperty<ExportFormat> formatProperty = new SimpleObjectProperty<>(ExportFormat.DIRECTORY);
	private final BooleanProperty includeResourcesProperty = new SimpleBooleanProperty(true);
	private final BooleanProperty inProgressProperty = new SimpleBooleanProperty();
	private final StringProperty currentClassProperty = new SimpleStringProperty("");
	private final StringProperty progressTextProperty = new SimpleStringProperty("");
	private final DecompilerManager decompilerManager;
	private final DecompilerPaneConfig decompilerPaneConfig;
	private final RecentFilesConfig recentFilesConfig;
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
	}

	@Inject
	public DecompileAllPopup(@Nonnull DecompilerManager decompilerManager,
	                         @Nonnull RecentFilesConfig recentFilesConfig,
	                         @Nonnull DecompilerPaneConfig decompilerPaneConfig,
	                         @Nonnull Workspace workspace) {
		this.decompilerManager = decompilerManager;
		this.recentFilesConfig = recentFilesConfig;
		this.decompilerPaneConfig = decompilerPaneConfig;
		this.workspace = workspace;

		// Build names for directory and ZIP export
		String baseName = buildBaseName(workspace);  // e.g., "tacz-1.20.1-1.1.4-hotfix-all"
		String zipName = baseName + ".zip";

		targetBundle = workspace.getPrimaryResource().getJvmClassBundle();
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
		try {
			inProgressProperty.setValue(true);
			progress.setProgress(0);
			currentClassProperty.set("");
			progressTextProperty.set("");

			// Determine which classes to decompile
			List<JvmClassInfo> targetClasses = targetBundle.stream().filter(cls -> {
				// Skip inner classes
				if (cls.isInnerClass())
					return false;

				// Skip special case classes like 'module-info' and 'package-info'
				String name = cls.getName();
				if (cls.getSuperName() == null && (name.equals("module-info") || name.endsWith("package-info")))
					return false;

				// Filter by package if specified
				if (targetPackage != null && !name.startsWith(targetPackage))
					return false;

				return true;
			}).toList();

			if (targetClasses.isEmpty()) {
				inProgressProperty.setValue(false);
				progressTextProperty.set(Lang.get("menu.search.noresults"));
				return;
			}

			// Determine delta of each decompilation
			int targetCount = targetClasses.size();
			AtomicInteger actionedClasses = new AtomicInteger(targetCount);
			AtomicInteger completedClasses = new AtomicInteger(0);

			// Decompile all classes
			JvmDecompiler decompiler = decompilerProperty.getValue();
			ExportFormat format = formatProperty.get();
			Path basePath = pathProperty.get();

			if (format == ExportFormat.DIRECTORY) {
				// Export to directory structure
				exportToDirectory(targetClasses, decompiler, basePath, targetCount, actionedClasses, completedClasses, progress);
			} else {
				// Export to ZIP archive
				exportToZip(targetClasses, decompiler, basePath, targetCount, actionedClasses, completedClasses, progress);
			}
		} catch (Throwable t) {
			logger.error("Failed to schedule all classes for decompilation", t);
			inProgressProperty.setValue(false);
		}
	}

	/**
	 * Export decompiled classes to a directory structure.
	 */
	private void exportToDirectory(@Nonnull List<JvmClassInfo> targetClasses,
	                               @Nonnull JvmDecompiler decompiler,
	                               @Nonnull Path basePath,
	                               int targetCount,
	                               @Nonnull AtomicInteger actionedClasses,
	                               @Nonnull AtomicInteger completedClasses,
	                               @Nonnull ProgressBar progress) {
		// Export resource files first if enabled
		if (includeResourcesProperty.get()) {
			exportResourceFilesToDirectory(basePath);
		}

		targetClasses.forEach(cls -> {
			String name = cls.getName();
			decompilerManager.decompile(decompiler, workspace, cls)
					.orTimeout(decompilerPaneConfig.getTimeoutSeconds().getValue(), TimeUnit.SECONDS)
					.whenComplete((result, error) -> {
						int remaining = actionedClasses.decrementAndGet();
						int completed = completedClasses.incrementAndGet();

						if (result != null) {
							// Handle errors
							if (result.getException() != null) {
								logger.error("Failed to decompile '{}'", name, result.getException());
							} else {
								// Write decompilation output
								String text = result.getText();
								if (text != null) {
									try {
										Path outputPath = basePath.resolve(name + ".java");
										Files.createDirectories(outputPath.getParent());
										Files.writeString(outputPath, text, StandardCharsets.UTF_8);
									} catch (IOException ex) {
										logger.error("Failed to write decompiled class '{}' to directory", name, ex);
									}
								}
							}
						} else {
							logger.error("Failed to decompile '{}'", name, error);
						}

						// If done
						if (remaining <= 0) {
							FxThreadUtil.run(() -> {
								inProgressProperty.setValue(false);
								progressTextProperty.set(Lang.get("dialog.export.complete") + " (" + completed + "/" + targetCount + ")");
								currentClassProperty.set("");
							});
						}
					}).thenRunAsync(() -> {
						double progressValue = (double) completedClasses.get() / targetCount;
						progress.setProgress(progressValue);
						int percent = (int) (progressValue * 100);
						progressTextProperty.set(Lang.get("dialog.export.progress")
								.replace("{0}", String.valueOf(percent))
								.replace("{1}", String.valueOf(completedClasses.get()))
								.replace("{2}", String.valueOf(targetCount)));
						currentClassProperty.set(name);
					}, FxThreadUtil.executor());
		});
	}

	/**
	 * Export all resource files (JSON, PNG, etc.) to directory.
	 */
	private void exportResourceFilesToDirectory(@Nonnull Path basePath) {
		WorkspaceResource resource = workspace.getPrimaryResource();
		FileBundle fileBundle = resource.getFileBundle();

		for (FileInfo fileInfo : fileBundle) {
			String fileName = fileInfo.getName();

			// Filter by package if specified
			if (targetPackage != null && !fileName.startsWith(targetPackage)) {
				continue;
			}

			try {
				Path outputPath = basePath.resolve(fileName);
				Files.createDirectories(outputPath.getParent());
				Files.write(outputPath, fileInfo.getRawContent());
				logger.debug("Exported resource: {}", fileName);
			} catch (IOException ex) {
				logger.error("Failed to export resource file '{}'", fileName, ex);
			}
		}
	}

	/**
	 * Export decompiled classes to a ZIP archive.
	 */
	private void exportToZip(@Nonnull List<JvmClassInfo> targetClasses,
	                         @Nonnull JvmDecompiler decompiler,
	                         @Nonnull Path zipPath,
	                         int targetCount,
	                         @Nonnull AtomicInteger actionedClasses,
	                         @Nonnull AtomicInteger completedClasses,
	                         @Nonnull ProgressBar progress) {
		ZipCreationUtils.ZipBuilder builder = ZipCreationUtils.builder();

		// Add resource files first if enabled
		if (includeResourcesProperty.get()) {
			addResourceFilesToZip(builder);
		}

		targetClasses.forEach(cls -> {
			String name = cls.getName();
			decompilerManager.decompile(decompiler, workspace, cls)
					.orTimeout(decompilerPaneConfig.getTimeoutSeconds().getValue(), TimeUnit.SECONDS)
					.whenComplete((result, error) -> {
						int remaining = actionedClasses.decrementAndGet();
						int completed = completedClasses.incrementAndGet();

						if (result != null) {
							// Handle errors
							if (result.getException() != null) {
								logger.error("Failed to decompile '{}'", name, result.getException());
							} else {
								// Write decompilation output
								String text = result.getText();
								if (text != null)
									builder.add(name + ".java", text.getBytes(StandardCharsets.UTF_8));
							}
						} else {
							logger.error("Failed to decompile '{}'", name, error);
						}

						// If done, write the zip file
						if (remaining <= 0) {
							try {
								Files.write(zipPath, builder.bytes());
							} catch (IOException ex) {
								logger.error("Failed to write archive of decompiled classes to '{}'", zipPath, ex);
							}
							FxThreadUtil.run(() -> {
								inProgressProperty.setValue(false);
								progressTextProperty.set(Lang.get("dialog.export.complete") + " (" + completed + "/" + targetCount + ")");
								currentClassProperty.set("");
							});
						}
					}).thenRunAsync(() -> {
						double progressValue = (double) completedClasses.get() / targetCount;
						progress.setProgress(progressValue);
						int percent = (int) (progressValue * 100);
						progressTextProperty.set(Lang.get("dialog.export.progress")
								.replace("{0}", String.valueOf(percent))
								.replace("{1}", String.valueOf(completedClasses.get()))
								.replace("{2}", String.valueOf(targetCount)));
						currentClassProperty.set(name);
					}, FxThreadUtil.executor());
		});
	}

	/**
	 * Add all resource files (JSON, PNG, etc.) to ZIP builder.
	 */
	private void addResourceFilesToZip(@Nonnull ZipCreationUtils.ZipBuilder builder) {
		WorkspaceResource resource = workspace.getPrimaryResource();
		FileBundle fileBundle = resource.getFileBundle();

		for (FileInfo fileInfo : fileBundle) {
			String fileName = fileInfo.getName();

			// Filter by package if specified
			if (targetPackage != null && !fileName.startsWith(targetPackage)) {
				continue;
			}

			builder.add(fileName, fileInfo.getRawContent());
			logger.debug("Added resource to ZIP: {}", fileName);
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
	 * Build the ZIP file name from the workspace.
	 *
	 * @param workspace The workspace to get the name from.
	 * @return The ZIP file name (e.g., "tacz-1.20.1-1.1.4-hotfix-all.zip").
	 */
	@Nonnull
	private static String buildZipName(@Nonnull Workspace workspace) {
		return buildBaseName(workspace) + ".zip";
	}

	/**
	 * @param targetBundle Bundle to target for decompilation.
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
