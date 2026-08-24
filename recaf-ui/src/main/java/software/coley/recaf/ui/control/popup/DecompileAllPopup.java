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
import software.coley.recaf.util.threading.ThreadPoolFactory;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.FileBundle;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Popup for initiating decompilation of all classes, saved to a specified location.
 * Supports exporting to ZIP archive or directory structure with .java files.
 *
 * @author Matt Coley
 */
@Dependent
public class DecompileAllPopup extends RecafStage {
	private static final Logger logger = Logging.get(DecompileAllPopup.class);
	/** Upper bound on threads used for writing decompiled sources to disk. */
	private static final int MAX_IO_THREADS = 4;
	/** Bound on queued write tasks. Exceeding it applies back-pressure instead of buffering every source in memory. */
	private static final int IO_QUEUE_CAPACITY = 256;
	/** Bound on ZIP entries buffered ahead of the single writer thread. */
	private static final int ZIP_QUEUE_CAPACITY = 128;
	/** Minimum delay between UI progress updates. */
	private static final long PROGRESS_INTERVAL_MS = 100;
	/** Number of classes that force a UI progress update regardless of {@link #PROGRESS_INTERVAL_MS}. */
	private static final int PROGRESS_CLASS_INTERVAL = 64;
	/** Sentinel telling the ZIP writer thread that no more entries are coming. */
	private static final ZipItem ZIP_END = new ZipItem("", new byte[0]);
	private static final ExecutorService exportPool = ThreadPoolFactory.newSingleThreadExecutor("decompile-all");
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
		inProgressProperty.setValue(true);
		progress.setProgress(0);
		currentClassProperty.set("");
		progressTextProperty.set("");

		// Snapshot the settings on the FX thread, then hand everything else off. Class collection, resource copying
		// and archive writing must not run on the FX thread.
		JvmDecompiler decompiler = decompilerProperty.getValue();
		ExportFormat format = formatProperty.get();
		Path basePath = pathProperty.get();
		boolean includeResources = includeResourcesProperty.get();
		int timeoutSeconds = decompilerPaneConfig.getTimeoutSeconds().getValue();

		exportPool.submit(() -> {
			try {
				List<JvmClassInfo> targetClasses = collectTargetClasses();
				if (targetClasses.isEmpty()) {
					FxThreadUtil.run(() -> {
						inProgressProperty.setValue(false);
						progressTextProperty.set(Lang.get("menu.search.noresults"));
					});
					return;
				}

				if (format == ExportFormat.DIRECTORY)
					exportToDirectory(targetClasses, decompiler, basePath, includeResources, timeoutSeconds, progress);
				else
					exportToZip(targetClasses, decompiler, basePath, includeResources, timeoutSeconds, progress);
			} catch (Throwable t) {
				logger.error("Failed to schedule all classes for decompilation", t);
				FxThreadUtil.run(() -> inProgressProperty.setValue(false));
			}
		});
	}

	/**
	 * @return Classes of {@link #targetBundle} that should be written out.
	 */
	@Nonnull
	private List<JvmClassInfo> collectTargetClasses() {
		return targetBundle.stream().filter(cls -> {
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
	}

	/**
	 * Export decompiled classes to a directory structure.
	 */
	private void exportToDirectory(@Nonnull List<JvmClassInfo> targetClasses,
	                               @Nonnull JvmDecompiler decompiler,
	                               @Nonnull Path basePath,
	                               boolean includeResources,
	                               int timeoutSeconds,
	                               @Nonnull ProgressBar progress) {
		int targetCount = targetClasses.size();
		ProgressThrottle throttle = new ProgressThrottle(progress, targetCount);
		ExecutorService ioExecutor = newIoExecutor();
		try {
			if (includeResources)
				exportResourceFilesToDirectory(basePath);

			AtomicInteger completedClasses = new AtomicInteger();
			awaitDecompilations(targetClasses, decompiler, timeoutSeconds, (name, text) -> {
				Runnable write = () -> {
					try {
						if (text != null) {
							Path outputPath = basePath.resolve(name + ".java");
							Files.createDirectories(outputPath.getParent());
							Files.writeString(outputPath, text, StandardCharsets.UTF_8);
						}
					} catch (IOException ex) {
						logger.error("Failed to write decompiled class '{}' to directory", name, ex);
					} finally {
						throttle.onClassDone(name, completedClasses.incrementAndGet());
					}
				};
				try {
					ioExecutor.execute(write);
				} catch (RejectedExecutionException ex) {
					logger.error("Write executor rejected output of '{}'", name, ex);
					write.run();
				}
			});
			completeOnFxThread(progress, completedClasses.get(), targetCount);
		} finally {
			ioExecutor.shutdown();
		}
	}

	/**
	 * Export decompiled classes to a ZIP archive.
	 * <p/>
	 * Entries are streamed straight into the archive by a single writer thread; the whole archive is never held in
	 * memory, and no decompiler worker ever touches the {@link ZipOutputStream}.
	 */
	private void exportToZip(@Nonnull List<JvmClassInfo> targetClasses,
	                         @Nonnull JvmDecompiler decompiler,
	                         @Nonnull Path zipPath,
	                         boolean includeResources,
	                         int timeoutSeconds,
	                         @Nonnull ProgressBar progress) {
		int targetCount = targetClasses.size();
		ProgressThrottle throttle = new ProgressThrottle(progress, targetCount);
		BlockingQueue<ZipItem> queue = new ArrayBlockingQueue<>(ZIP_QUEUE_CAPACITY);
		Thread writer = new Thread(() -> writeZipEntries(zipPath, queue), "Recaf-decompile-all-zip-writer");
		writer.setDaemon(true);
		writer.start();

		AtomicInteger completedClasses = new AtomicInteger();
		try {
			if (includeResources)
				for (ZipItem item : collectResourceFiles())
					queue.put(item);

			awaitDecompilations(targetClasses, decompiler, timeoutSeconds, (name, text) -> {
				try {
					if (text != null)
						queue.put(new ZipItem(name + ".java", text.getBytes(StandardCharsets.UTF_8)));
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				} finally {
					throttle.onClassDone(name, completedClasses.incrementAndGet());
				}
			});
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		} finally {
			try {
				queue.put(ZIP_END);
				writer.join();
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}

		completeOnFxThread(progress, completedClasses.get(), targetCount);
	}

	/**
	 * Consumes decompiled sources from a queue and streams them into the target archive.
	 * Runs on a single dedicated thread so the archive is only ever written by one thread at a time.
	 */
	private static void writeZipEntries(@Nonnull Path zipPath, @Nonnull BlockingQueue<ZipItem> queue) {
		boolean sawEnd = false;
		try (OutputStream fileOut = Files.newOutputStream(zipPath);
		     ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fileOut))) {
			Set<String> writtenNames = new HashSet<>();
			while (true) {
				ZipItem item = queue.take();
				if (item == ZIP_END) {
					sawEnd = true;
					break;
				}
				if (!writtenNames.add(item.name())) {
					logger.warn("Skipping duplicate archive entry '{}'", item.name());
					continue;
				}
				try {
					zos.putNextEntry(new ZipEntry(item.name()));
					zos.write(item.content());
					zos.closeEntry();
				} catch (IOException ex) {
					logger.error("Failed to write archive entry '{}'", item.name(), ex);
				}
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return;
		} catch (IOException ex) {
			logger.error("Failed to write archive of decompiled classes to '{}'", zipPath, ex);
		}

		// Keep consuming after a fatal archive error so that producers are never stranded on a full queue.
		if (!sawEnd)
			discardUntilEnd(queue);
	}

	private static void discardUntilEnd(@Nonnull BlockingQueue<ZipItem> queue) {
		try {
			while (queue.take() != ZIP_END) {
				// Discard.
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Schedules a decompilation for every class and blocks until every result has been handed to {@code sink}.
	 * The sink is invoked on the decompiler worker thread and must not perform blocking disk work itself.
	 */
	private void awaitDecompilations(@Nonnull List<JvmClassInfo> targetClasses,
	                                 @Nonnull JvmDecompiler decompiler,
	                                 int timeoutSeconds,
	                                 @Nonnull DecompiledSink sink) {
		AtomicInteger remaining = new AtomicInteger(targetClasses.size());
		CompletableFuture<Void> allDone = new CompletableFuture<>();
		for (JvmClassInfo cls : targetClasses) {
			String name = cls.getName();
			decompilerManager.decompile(decompiler, workspace, cls)
					.orTimeout(timeoutSeconds, TimeUnit.SECONDS)
					.whenComplete((result, error) -> {
						try {
							String text = null;
							if (result == null) {
								logger.error("Failed to decompile '{}'", name, error);
							} else if (result.getException() != null) {
								logger.error("Failed to decompile '{}'", name, result.getException());
							} else {
								text = result.getText();
							}
							sink.accept(name, text);
						} catch (Throwable t) {
							logger.error("Failed to handle decompilation of '{}'", name, t);
						} finally {
							if (remaining.decrementAndGet() <= 0)
								allDone.complete(null);
						}
					});
		}
		allDone.join();
	}

	private void completeOnFxThread(@Nonnull ProgressBar progress, int completed, int targetCount) {
		FxThreadUtil.run(() -> {
			inProgressProperty.setValue(false);
			progress.setProgress(1);
			progressTextProperty.set(Lang.get("dialog.export.complete") + " (" + completed + "/" + targetCount + ")");
			currentClassProperty.set("");
		});
	}

	/**
	 * @return Bounded pool used for writing decompiled sources so that decompiler workers are never blocked on disk.
	 */
	@Nonnull
	private static ExecutorService newIoExecutor() {
		int threads = Math.max(1, Math.min(MAX_IO_THREADS, Runtime.getRuntime().availableProcessors() / 2));
		return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(IO_QUEUE_CAPACITY),
				runnable -> {
					Thread thread = new Thread(runnable, "Recaf-decompile-all-io");
					thread.setDaemon(true);
					return thread;
				},
				// Once the queue is saturated the submitting thread does the write itself. This keeps the amount of
				// buffered source text bounded rather than letting decompilation run arbitrarily far ahead of the disk.
				new ThreadPoolExecutor.CallerRunsPolicy());
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
	 * @return All resource files (JSON, PNG, etc.) to place in the archive.
	 */
	@Nonnull
	private List<ZipItem> collectResourceFiles() {
		WorkspaceResource resource = workspace.getPrimaryResource();
		FileBundle fileBundle = resource.getFileBundle();

		List<ZipItem> items = new ArrayList<>();
		for (FileInfo fileInfo : fileBundle) {
			String fileName = fileInfo.getName();

			// Filter by package if specified
			if (targetPackage != null && !fileName.startsWith(targetPackage)) {
				continue;
			}

			items.add(new ZipItem(fileName, fileInfo.getRawContent()));
		}
		return items;
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

	/**
	 * Receives the outcome of a single class decompilation.
	 */
	private interface DecompiledSink {
		/**
		 * @param className
		 * 		Name of the decompiled class.
		 * @param text
		 * 		Decompiled source, or {@code null} when decompilation failed.
		 */
		void accept(@Nonnull String className, @Nullable String text);
	}

	private record ZipItem(@Nonnull String name, @Nonnull byte[] content) {
	}

	/**
	 * Rate limits progress updates so that exporting thousands of classes does not flood the FX thread with one
	 * update per class.
	 */
	private final class ProgressThrottle {
		private final ProgressBar progress;
		private final int targetCount;
		private long lastEmitMs = System.currentTimeMillis();
		private int lastEmitCount;

		private ProgressThrottle(@Nonnull ProgressBar progress, int targetCount) {
			this.progress = progress;
			this.targetCount = targetCount;
		}

		private synchronized void onClassDone(@Nonnull String className, int done) {
			long now = System.currentTimeMillis();
			if (done - lastEmitCount < PROGRESS_CLASS_INTERVAL && now - lastEmitMs < PROGRESS_INTERVAL_MS)
				return;
			lastEmitMs = now;
			lastEmitCount = done;

			double progressValue = targetCount == 0 ? 1 : (double) done / targetCount;
			int percent = (int) (progressValue * 100);
			String text = Lang.get("dialog.export.progress")
					.replace("{0}", String.valueOf(percent))
					.replace("{1}", String.valueOf(done))
					.replace("{2}", String.valueOf(targetCount));
			FxThreadUtil.run(() -> {
				progress.setProgress(progressValue);
				progressTextProperty.set(text);
				currentClassProperty.set(className);
			});
		}
	}
}
