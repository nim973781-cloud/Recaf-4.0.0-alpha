package software.coley.recaf.ui.control.popup;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
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
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.decompile.batch.BatchDecompileEngine;
import software.coley.recaf.services.decompile.batch.BatchDecompileException;
import software.coley.recaf.services.decompile.batch.BatchDecompileFailure;
import software.coley.recaf.services.decompile.batch.BatchDecompileProgress;
import software.coley.recaf.services.decompile.batch.BatchDecompileReport;
import software.coley.recaf.services.mapping.format.MappingFileFormat;
import software.coley.recaf.services.mapping.format.MappingFormatManager;
import software.coley.recaf.ui.config.RecentFilesConfig;
import software.coley.recaf.ui.control.ActionButton;
import software.coley.recaf.ui.control.BoundLabel;
import software.coley.recaf.ui.control.ObservableComboBox;
import software.coley.recaf.ui.pane.editing.jvm.DecompilerPaneConfig;
import software.coley.recaf.ui.window.RecafScene;
import software.coley.recaf.ui.window.RecafStage;
import software.coley.recaf.util.ErrorDialogs;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.util.Lang;
import software.coley.recaf.util.threading.ThreadPoolFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

@Dependent
public class BatchDecompileJarsPopup extends RecafStage {
	private static final Logger logger = Logging.get(BatchDecompileJarsPopup.class);
	private static final ExecutorService batchPool = ThreadPoolFactory.newSingleThreadExecutor("batch-decompile-jars");

	private final ObjectProperty<Path> jarDirProperty = new SimpleObjectProperty<>();
	private final ObjectProperty<Path> mappingFileProperty = new SimpleObjectProperty<>();
	private final ObjectProperty<Path> outputDirProperty = new SimpleObjectProperty<>();
	private final ObjectProperty<MappingFileFormat> mappingFormatProperty = new SimpleObjectProperty<>();
	private final ObservableObject<JvmDecompiler> decompilerProperty;

	private final BooleanProperty inProgressProperty = new SimpleBooleanProperty();
	private final StringProperty progressTextProperty = new SimpleStringProperty("");
	private final StringProperty currentJarProperty = new SimpleStringProperty("");
	private final StringProperty currentClassProperty = new SimpleStringProperty("");

	private final BatchDecompileEngine batchDecompileEngine;
	private final DecompilerPaneConfig decompilerPaneConfig;
	private final RecentFilesConfig recentFilesConfig;
	private final MappingFormatManager mappingFormatManager;

	@Inject
	public BatchDecompileJarsPopup(@Nonnull DecompilerManager decompilerManager,
	                               @Nonnull BatchDecompileEngine batchDecompileEngine,
	                               @Nonnull DecompilerPaneConfig decompilerPaneConfig,
	                               @Nonnull RecentFilesConfig recentFilesConfig,
	                               @Nonnull MappingFormatManager mappingFormatManager) {
		this.batchDecompileEngine = batchDecompileEngine;
		this.decompilerPaneConfig = decompilerPaneConfig;
		this.recentFilesConfig = recentFilesConfig;
		this.mappingFormatManager = mappingFormatManager;

		decompilerProperty = new ObservableObject<>(decompilerManager.getTargetJvmDecompiler());

		GridPane layout = new GridPane(8, 8);
		layout.setPadding(new Insets(10));
		layout.setAlignment(Pos.TOP_CENTER);

		Label jarDirLabel = new BoundLabel(Lang.getBinding("dialog.export.batch.jar-dir"));
		Label mappingLabel = new BoundLabel(Lang.getBinding("dialog.export.batch.mapping-file"));
		Label outputLabel = new BoundLabel(Lang.getBinding("dialog.export.batch.output-dir"));
		Label formatLabel = new BoundLabel(Lang.getBinding("dialog.export.batch.mapping-format"));
		Label decompilerLabel = new BoundLabel(Lang.getBinding("java.decompiler"));

		Button jarDirButton = new ActionButton(CarbonIcons.FOLDER,
				jarDirProperty.map(p -> p == null ? "" : p.toString()),
				this::pickJarDirectory);
		Button mappingFileButton = new ActionButton(CarbonIcons.DOCUMENT,
				mappingFileProperty.map(p -> p == null ? "" : p.toString()),
				this::pickMappingFile);
		Button outputDirButton = new ActionButton(CarbonIcons.FOLDER,
				outputDirProperty.map(p -> p == null ? "" : p.toString()),
				this::pickOutputDirectory);

		jarDirButton.disableProperty().bind(inProgressProperty);
		mappingFileButton.disableProperty().bind(inProgressProperty);
		outputDirButton.disableProperty().bind(inProgressProperty);

		ComboBox<MappingFileFormat> mappingFormatBox = new ComboBox<>();
		mappingFormatBox.setItems(FXCollections.observableArrayList(loadMappingFormats()));
		mappingFormatBox.setConverter(new javafx.util.StringConverter<>() {
			@Override
			public String toString(MappingFileFormat format) {
				return format == null ? "" : format.implementationName();
			}

			@Override
			public MappingFileFormat fromString(String s) {
				return null;
			}
		});
		mappingFormatBox.setValue(selectDefaultMappingFormat(mappingFormatBox.getItems()));
		mappingFormatProperty.bind(mappingFormatBox.valueProperty());

		ObservableComboBox<JvmDecompiler> decompilerCombo =
				new ObservableComboBox<>(decompilerProperty, decompilerManager.getJvmDecompilers());
		decompilerCombo.disableProperty().bind(inProgressProperty);

		ProgressBar progressBar = new ProgressBar(0);
		progressBar.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

		Label progressLabel = new Label();
		progressLabel.textProperty().bind(progressTextProperty);
		Label currentJarLabel = new Label();
		currentJarLabel.textProperty().bind(currentJarProperty.map(s -> s.isEmpty() ? "" : Lang.get("dialog.export.batch.current-jar") + " " + s));
		currentJarLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888888;");
		Label currentClassLabel = new Label();
		currentClassLabel.textProperty().bind(currentClassProperty.map(s -> s.isEmpty() ? "" : Lang.get("dialog.export.current") + " " + s));
		currentClassLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888888;");

		Button startButton = new ActionButton(CarbonIcons.SAVE_SERIES, Lang.getBinding("dialog.export.batch.start"), () ->
				startBatch(progressBar));
		startButton.disableProperty().bind(jarDirProperty.isNull()
				.or(mappingFileProperty.isNull())
				.or(outputDirProperty.isNull())
				.or(mappingFormatProperty.isNull())
				.or(inProgressProperty));

		jarDirButton.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		mappingFileButton.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		outputDirButton.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		mappingFormatBox.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		decompilerCombo.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		startButton.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

		int row = 0;
		layout.add(jarDirLabel, 0, row);
		layout.add(jarDirButton, 1, row++);
		layout.add(mappingLabel, 0, row);
		layout.add(mappingFileButton, 1, row++);
		layout.add(outputLabel, 0, row);
		layout.add(outputDirButton, 1, row++);
		layout.add(formatLabel, 0, row);
		layout.add(mappingFormatBox, 1, row++);
		layout.add(decompilerLabel, 0, row);
		layout.add(decompilerCombo, 1, row++);
		layout.add(startButton, 1, row++);
		layout.add(progressLabel, 0, row, 2, 1);
		row++;
		layout.add(currentJarLabel, 0, row, 2, 1);
		row++;
		layout.add(currentClassLabel, 0, row, 2, 1);
		row++;
		layout.add(progressBar, 0, row, 2, 1);

		setMinWidth(700);
		setMinHeight(360);
		setTitle(Lang.get("menu.file.export.decompiled.batch"));
		setScene(new RecafScene(layout, 700, 360));
	}

	@Nonnull
	private List<MappingFileFormat> loadMappingFormats() {
		return mappingFormatManager.getMappingFileFormats().stream()
				.map(mappingFormatManager::createFormatInstance)
				.filter(Objects::nonNull)
				.toList();
	}

	private MappingFileFormat selectDefaultMappingFormat(@Nonnull List<MappingFileFormat> formats) {
		for (MappingFileFormat format : formats)
			if ("TSRG".equalsIgnoreCase(format.implementationName()))
				return format;
		return formats.isEmpty() ? null : formats.getFirst();
	}

	private void pickJarDirectory() {
		DirectoryChooser chooser = new DirectoryChooser();
		chooser.setTitle(Lang.get("dialog.file.open"));
		File initial = getInitialDirectoryForDirChooser(jarDirProperty.get(), recentFilesConfig.getLastWorkspaceOpenDirectory().getValue());
		if (initial != null)
			chooser.setInitialDirectory(initial);
		File selected = chooser.showDialog(getScene().getWindow());
		if (selected != null) {
			recentFilesConfig.getLastWorkspaceOpenDirectory().setValue(selected.getAbsolutePath());
			jarDirProperty.set(selected.toPath());
		}
	}

	private void pickOutputDirectory() {
		DirectoryChooser chooser = new DirectoryChooser();
		chooser.setTitle(Lang.get("dialog.file.save"));
		File initial = getInitialDirectoryForDirChooser(outputDirProperty.get(), recentFilesConfig.getLastWorkspaceExportDirectory().getValue());
		if (initial != null)
			chooser.setInitialDirectory(initial);
		File selected = chooser.showDialog(getScene().getWindow());
		if (selected != null) {
			recentFilesConfig.getLastWorkspaceExportDirectory().setValue(selected.getAbsolutePath());
			outputDirProperty.set(selected.toPath());
		}
	}

	private void pickMappingFile() {
		FileChooser chooser = new FileChooser();
		chooser.setTitle(Lang.get("dialog.file.open"));

		File initial = null;
		Path current = mappingFileProperty.get();
		if (current != null) {
			Path parent = current.getParent();
			if (parent != null)
				initial = parent.toFile();
		}

		if (initial == null) {
			Path mappingDir = Paths.get("E:\\多个测试\\Recaf\\映射");
			if (Files.isDirectory(mappingDir)) {
				initial = mappingDir.toFile();
			} else {
				String recentDir = recentFilesConfig.getLastWorkspaceOpenDirectory().getValue();
				if (recentDir != null && !recentDir.isEmpty()) {
					File candidate = new File(recentDir);
					if (candidate.isDirectory())
						initial = candidate;
				}
			}
		}

		if (initial != null)
			chooser.setInitialDirectory(initial);

		File selected = chooser.showOpenDialog(getScene().getWindow());
		if (selected != null) {
			File parent = selected.getParentFile();
			if (parent != null)
				recentFilesConfig.getLastWorkspaceOpenDirectory().setValue(parent.getAbsolutePath());
			mappingFileProperty.set(selected.toPath());
		}
	}

	private static File getInitialDirectoryForDirChooser(Path selected, String recentPath) {
		if (selected != null) {
			File file = selected.toFile();
			if (file.isDirectory())
				return file;
			File parent = file.getParentFile();
			if (parent != null && parent.isDirectory())
				return parent;
		}
		if (recentPath != null && !recentPath.isEmpty()) {
			File recent = new File(recentPath);
			if (recent.isDirectory())
				return recent;
		}
		return null;
	}

	private void startBatch(@Nonnull ProgressBar progressBar) {
		Path jarDir = jarDirProperty.get();
		Path mappingFile = mappingFileProperty.get();
		Path outputRoot = outputDirProperty.get();
		MappingFileFormat mappingFormat = mappingFormatProperty.get();
		JvmDecompiler decompiler = decompilerProperty.getValue();

		if (jarDir == null || mappingFile == null || outputRoot == null || mappingFormat == null || decompiler == null) {
			return;
		}

		inProgressProperty.set(true);
		progressBar.setProgress(0);
		progressTextProperty.set("");
		currentJarProperty.set("");
		currentClassProperty.set("");

		BatchDecompileJarsRunner runner = new BatchDecompileJarsRunner(batchDecompileEngine, decompilerPaneConfig);
		batchPool.submit(() -> {
			try {
				runner.run(jarDir, mappingFile, outputRoot, mappingFormat.implementationName(), decompiler.getName(),
						new BatchDecompileJarsRunner.Callbacks() {
							@Override
							public void onNoJarsFound() {
								FxThreadUtil.run(() -> {
									inProgressProperty.set(false);
									progressTextProperty.set(Lang.get("dialog.export.batch.no-jars"));
									currentJarProperty.set("");
									currentClassProperty.set("");
									progressBar.setProgress(0);
								});
							}

							@Override
							public void onProgress(@Nonnull BatchDecompileProgress progress) {
								// The engine already throttles these, so they can go straight to the FX thread.
								FxThreadUtil.run(() -> showProgress(progressBar, progress));
							}

							@Override
							public void onComplete(@Nonnull BatchDecompileReport report) {
								// Only counts fit in the popup, so the per-item detail goes to the log.
								for (BatchDecompileFailure failure : report.failures())
									logger.warn("Batch export failure [{}] in '{}': {}",
											failure.phase(),
											failure.className() == null ? failure.jarName() : failure.className(),
											failure.message());
								FxThreadUtil.run(() -> showReport(progressBar, report));
							}
						});
			} catch (BatchDecompileException ex) {
				logger.error("Batch export failed", ex);
				String message = ex.getMessage();
				completeWithError(progressBar, message == null
						? Lang.get("dialog.export.batch.failed")
						: Lang.get("dialog.export.batch.failed") + "\n" + message, ex);
			} catch (Throwable t) {
				logger.error("Batch export failed", t);
				completeWithError(progressBar, Lang.get("dialog.export.batch.failed"), t);
			}
		});
	}

	private void showProgress(@Nonnull ProgressBar progressBar, @Nonnull BatchDecompileProgress progress) {
		String currentJar = progress.currentJar();
		String currentClass = progress.currentClass();
		currentJarProperty.set(currentJar == null ? "" : currentJar);
		currentClassProperty.set(currentClass == null ? "" : currentClass);
		progressBar.setProgress(progress.fraction());
		progressTextProperty.set(Lang.get("dialog.export.batch.progress")
				.replace("{0}", String.valueOf(Math.min(progress.totalJars(), progress.completedJars() + 1)))
				.replace("{1}", String.valueOf(progress.totalJars()))
				.replace("{2}", String.valueOf((int) (progress.fraction() * 100)))
				.replace("{3}", String.valueOf(progress.okClasses()))
				.replace("{4}", String.valueOf(progress.skippedClasses()))
				.replace("{5}", String.valueOf(progress.failedClasses())));
	}

	private void showReport(@Nonnull ProgressBar progressBar, @Nonnull BatchDecompileReport report) {
		inProgressProperty.set(false);
		currentJarProperty.set("");
		currentClassProperty.set("");
		progressBar.setProgress(1);
		progressTextProperty.set(Lang.get("dialog.export.batch.complete")
				.replace("{0}", String.valueOf(report.okJars()))
				.replace("{1}", String.valueOf(report.skippedJars()))
				.replace("{2}", String.valueOf(report.failedJars()))
				.replace("{3}", String.valueOf(report.okClasses()))
				.replace("{4}", String.valueOf(report.failedClasses())));
	}

	private void completeWithError(@Nonnull ProgressBar progressBar, @Nonnull String content, @Nonnull Throwable t) {
		FxThreadUtil.run(() -> {
			inProgressProperty.set(false);
			progressBar.setProgress(0);
			progressTextProperty.set(Lang.get("dialog.export.batch.failed"));
			currentJarProperty.set("");
			currentClassProperty.set("");
		});
		ErrorDialogs.show(Lang.get("dialog.export.batch.failed-title"),
				Lang.get("dialog.export.batch.failed-header"),
				content,
				t);
	}
}
