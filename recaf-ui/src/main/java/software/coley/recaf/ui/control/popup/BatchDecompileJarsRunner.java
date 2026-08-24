package software.coley.recaf.ui.control.popup;

import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.mapping.MappingApplier;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.format.InvalidMappingException;
import software.coley.recaf.services.mapping.format.MappingFileFormat;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.ui.pane.editing.jvm.DecompilerPaneConfig;
import software.coley.recaf.util.IOUtil;
import software.coley.recaf.util.StringUtil;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.bundle.VersionedJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

final class BatchDecompileJarsRunner {
	private static final Logger logger = Logging.get(BatchDecompileJarsRunner.class);
	private static final String EMBEDDED_DIR = "_embedded";
	/** Upper bound on threads used for writing decompiled sources to disk. */
	private static final int MAX_IO_THREADS = 4;
	/** Bound on queued write tasks. Exceeding it applies back-pressure instead of buffering every source in memory. */
	private static final int IO_QUEUE_CAPACITY = 256;
	/** Minimum delay between UI progress updates. */
	private static final long PROGRESS_INTERVAL_MS = 100;
	/** Number of classes that force a UI progress update regardless of {@link #PROGRESS_INTERVAL_MS}. */
	private static final int PROGRESS_CLASS_INTERVAL = 64;

	interface Callbacks {
		void onNoJarsFound();

		void onPreparingJar(int jarNumber, int totalJars, @Nonnull String jarName);

		void onCurrentClass(@Nonnull String className);

		void onProgress(int jarNumber, int totalJars, double jarFraction, int ok, int skipped, int failed);

		void onComplete(int ok, int skipped, int failed);
	}

	private final DecompilerManager decompilerManager;
	private final DecompilerPaneConfig decompilerPaneConfig;
	private final MappingApplierService mappingApplierService;
	private final ResourceImporter resourceImporter;

	BatchDecompileJarsRunner(@Nonnull DecompilerManager decompilerManager,
	                         @Nonnull DecompilerPaneConfig decompilerPaneConfig,
	                         @Nonnull MappingApplierService mappingApplierService,
	                         @Nonnull ResourceImporter resourceImporter) {
		this.decompilerManager = decompilerManager;
		this.decompilerPaneConfig = decompilerPaneConfig;
		this.mappingApplierService = mappingApplierService;
		this.resourceImporter = resourceImporter;
	}

	void run(@Nonnull Path jarDir,
	         @Nonnull Path mappingFile,
	         @Nonnull Path outputRoot,
	         @Nonnull MappingFileFormat mappingFormat,
	         @Nonnull JvmDecompiler decompiler,
	         @Nonnull Callbacks callbacks) throws IOException, InvalidMappingException {
		List<Path> jarPaths;
		try (Stream<Path> stream = Files.list(jarDir)) {
			jarPaths = stream
					.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
					.sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
					.toList();
		}

		if (jarPaths.isEmpty()) {
			callbacks.onNoJarsFound();
			return;
		}

		IntermediateMappings mappings = mappingFormat.parse(mappingFile);

		int totalJars = jarPaths.size();
		AtomicInteger okJars = new AtomicInteger(0);
		AtomicInteger skippedJars = new AtomicInteger(0);
		AtomicInteger failedJars = new AtomicInteger(0);

		ExecutorService ioExecutor = newIoExecutor();
		try {
			for (int jarIndex = 0; jarIndex < totalJars; jarIndex++) {
				int jarNumber = jarIndex + 1;
				Path jarPath = jarPaths.get(jarIndex);
				String jarName = jarPath.getFileName().toString();
				callbacks.onPreparingJar(jarNumber, totalJars, jarName);

				Path jarOutputDir = outputRoot.resolve(StringUtil.removeExtension(jarName));
				try {
					deleteExistingOutput(jarOutputDir);
					Files.createDirectories(jarOutputDir);
				} catch (IOException ex) {
					logger.error("Failed creating output directory {}", jarOutputDir, ex);
					failedJars.incrementAndGet();
					callbacks.onProgress(jarNumber, totalJars, 1.0, okJars.get(), skippedJars.get(), failedJars.get());
					continue;
				}

				Workspace workspace;
				try {
					WorkspaceResource primaryResource = resourceImporter.importResource(jarPath);
					workspace = new BasicWorkspace(primaryResource, List.of());
				} catch (Throwable t) {
					logger.error("Failed importing jar {}", jarPath, t);
					failedJars.incrementAndGet();
					callbacks.onProgress(jarNumber, totalJars, 1.0, okJars.get(), skippedJars.get(), failedJars.get());
					continue;
				}

				try {
					applyMappingsToWorkspace(workspace, mappings);
					boolean jarFailed = exportJar(workspace, decompiler, jarOutputDir, jarNumber, totalJars, okJars, skippedJars, failedJars, ioExecutor, callbacks);
					if (jarFailed) {
						failedJars.incrementAndGet();
					} else {
						okJars.incrementAndGet();
					}
					callbacks.onProgress(jarNumber, totalJars, 1.0, okJars.get(), skippedJars.get(), failedJars.get());
				} catch (Throwable t) {
					logger.error("Failed processing jar {}", jarPath, t);
					failedJars.incrementAndGet();
					callbacks.onProgress(jarNumber, totalJars, 1.0, okJars.get(), skippedJars.get(), failedJars.get());
				} finally {
					workspace.close();
				}
			}
		} finally {
			ioExecutor.shutdown();
		}

		callbacks.onComplete(okJars.get(), skippedJars.get(), failedJars.get());
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
					Thread thread = new Thread(runnable, "Recaf-batch-decompile-io");
					thread.setDaemon(true);
					return thread;
				},
				// Once the queue is saturated the submitting thread does the write itself. This keeps the amount of
				// buffered source text bounded rather than letting decompilation run arbitrarily far ahead of the disk.
				new ThreadPoolExecutor.CallerRunsPolicy());
	}

	private void applyMappingsToWorkspace(@Nonnull Workspace workspace, @Nonnull IntermediateMappings mappings) {
		MappingApplier applier = mappingApplierService.inWorkspace(workspace);
		applyMappingsToResource(applier, workspace.getPrimaryResource(), mappings);
	}

	private void applyMappingsToResource(@Nonnull MappingApplier applier,
	                                     @Nonnull WorkspaceResource resource,
	                                     @Nonnull IntermediateMappings mappings) {
		applyMappingsToBundle(applier, mappings, resource, resource.getJvmClassBundle());
		resource.getVersionedJvmClassBundles().values()
				.forEach(bundle -> applyMappingsToBundle(applier, mappings, resource, bundle));
		resource.getEmbeddedResources().values()
				.forEach(embedded -> applyMappingsToResource(applier, embedded, mappings));
	}

	private void applyMappingsToBundle(@Nonnull MappingApplier applier,
	                                   @Nonnull IntermediateMappings mappings,
	                                   @Nonnull WorkspaceResource resource,
	                                   @Nonnull JvmClassBundle bundle) {
		List<JvmClassInfo> classes = bundle.stream().toList();
		if (classes.isEmpty()) return;

		MappingResults mappingResults = applier.applyToClasses(mappings, resource, bundle, classes);
		mappingResults.apply();
	}

	private boolean exportJar(@Nonnull Workspace workspace,
	                          @Nonnull JvmDecompiler decompiler,
	                          @Nonnull Path jarOutputDir,
	                          int jarNumber,
	                          int totalJars,
	                          @Nonnull AtomicInteger okJars,
	                          @Nonnull AtomicInteger skippedJars,
	                          @Nonnull AtomicInteger failedJars,
	                          @Nonnull ExecutorService ioExecutor,
	                          @Nonnull Callbacks callbacks) {
		AtomicBoolean jarFailed = new AtomicBoolean(false);
		List<ClassExportTask> targetClasses = new ArrayList<>();

		exportResourceFilesRecursively(workspace.getPrimaryResource(), jarOutputDir, jarFailed);
		collectClassExports(workspace.getPrimaryResource(), jarOutputDir, targetClasses);

		if (targetClasses.isEmpty()) {
			return jarFailed.get();
		}

		int classCount = targetClasses.size();
		AtomicInteger completedClasses = new AtomicInteger(0);
		AtomicInteger remainingClasses = new AtomicInteger(classCount);
		CompletableFuture<Void> jarFuture = new CompletableFuture<>();
		int timeoutSeconds = decompilerPaneConfig.getTimeoutSeconds().getValue();
		ProgressThrottle throttle = new ProgressThrottle(callbacks, jarNumber, totalJars, classCount);

		for (ClassExportTask task : targetClasses) {
			decompilerManager.decompile(decompiler, workspace, task.classInfo())
					.orTimeout(timeoutSeconds, TimeUnit.SECONDS)
					.whenComplete((result, error) -> {
						// The decompiler worker only turns the result into text. Writing it is handed off so that the
						// worker is immediately free to pick up the next class instead of waiting on the disk.
						DecompiledSource source;
						try {
							source = renderDecompileResult(task.displayName(), result, error);
						} catch (Throwable t) {
							logger.error("Failed preparing decompiled source of '{}'", task.displayName(), t);
							jarFailed.set(true);
							source = null;
						}

						DecompiledSource pendingSource = source;
						Runnable write = () -> {
							try {
								if (pendingSource != null && !writeSource(task.outputPath(), task.displayName(), pendingSource)) {
									jarFailed.set(true);
								}
							} finally {
								int done = completedClasses.incrementAndGet();
								int remaining = remainingClasses.decrementAndGet();
								throttle.onClassDone(task.displayName(), done, okJars.get(), skippedJars.get(), failedJars.get());
								if (remaining <= 0) {
									jarFuture.complete(null);
								}
							}
						};

						// Never let a rejected write strand the jar future.
						try {
							ioExecutor.execute(write);
						} catch (RejectedExecutionException ex) {
							logger.error("Write executor rejected output of '{}'", task.displayName(), ex);
							write.run();
						}
					});
		}

		jarFuture.join();
		return jarFailed.get();
	}

	private void exportResourceFilesRecursively(@Nonnull WorkspaceResource resource,
	                                            @Nonnull Path resourceOutputDir,
	                                            @Nonnull AtomicBoolean jarFailed) {
		for (FileInfo fileInfo : resource.getFileBundle()) {
			Path outputPath = resourceOutputDir.resolve(fileInfo.getName());
			try {
				Path parent = outputPath.getParent();
				if (parent != null) {
					Files.createDirectories(parent);
				}
				Files.write(outputPath, fileInfo.getRawContent());
			} catch (IOException ex) {
				logger.error("Failed exporting resource file '{}' to '{}'", fileInfo.getName(), outputPath, ex);
				jarFailed.set(true);
			}
		}

		for (WorkspaceFileResource embedded : resource.getEmbeddedResources().values()) {
			exportResourceFilesRecursively(embedded, getEmbeddedOutputDir(resourceOutputDir, embedded), jarFailed);
		}
	}

	private void collectClassExports(@Nonnull WorkspaceResource resource,
	                                 @Nonnull Path resourceOutputDir,
	                                 @Nonnull List<ClassExportTask> targetClasses) {
		resource.getJvmClassBundle().stream()
				.filter(cls -> isExportableClass(cls.getName(), cls.isInnerClass(), cls.getSuperName()))
				.map(cls -> new ClassExportTask(cls, cls.getName(), resourceOutputDir.resolve(cls.getName() + ".java")))
				.forEach(targetClasses::add);

		for (VersionedJvmClassBundle bundle : resource.getVersionedJvmClassBundles().values()) {
			Path versionedRoot = resourceOutputDir.resolve("META-INF")
					.resolve("versions")
					.resolve(Integer.toString(bundle.version()));
			bundle.stream()
					.filter(cls -> isExportableClass(cls.getName(), cls.isInnerClass(), cls.getSuperName()))
					.map(cls -> new ClassExportTask(cls, cls.getName(), versionedRoot.resolve(cls.getName() + ".java")))
					.forEach(targetClasses::add);
		}

		for (WorkspaceFileResource embedded : resource.getEmbeddedResources().values()) {
			collectClassExports(embedded, getEmbeddedOutputDir(resourceOutputDir, embedded), targetClasses);
		}
	}

	private static boolean isExportableClass(@Nonnull String name, boolean isInnerClass, String superName) {
		if (isInnerClass) return false;
		return superName != null || (!name.equals("module-info") && !name.endsWith("package-info"));
	}

	private static void deleteExistingOutput(@Nonnull Path path) throws IOException {
		if (!Files.exists(path)) return;
		if (Files.isDirectory(path)) {
			IOUtil.cleanDirectory(path);
		} else {
			Files.delete(path);
		}
	}

	@Nonnull
	private static Path getEmbeddedOutputDir(@Nonnull Path resourceOutputDir, @Nonnull WorkspaceFileResource embedded) {
		return resourceOutputDir.resolve(EMBEDDED_DIR)
				.resolve(StringUtil.removeExtension(embedded.getFileInfo().getName()));
	}

	/**
	 * Turns a decompilation outcome into the text to write out. Contains no disk access so it can be run directly on
	 * the decompiler worker thread.
	 *
	 * @param className
	 * 		Name of the decompiled class.
	 * @param result
	 * 		Decompilation result, may be {@code null} when the future failed.
	 * @param error
	 * 		Error thrown by the future, may be {@code null}.
	 *
	 * @return Source text plus whether the class decompiled cleanly.
	 */
	@Nonnull
	private static DecompiledSource renderDecompileResult(@Nonnull String className,
	                                                      DecompileResult result,
	                                                      Throwable error) {
		boolean success = true;
		String text;

		if (result == null) {
			logger.error("Failed to decompile '{}'", className, error);
			text = buildFailureStub(className, error, null);
			success = false;
		} else {
			if (error != null) {
				logger.error("Failed to decompile '{}'", className, error);
				success = false;
			}
			if (result.getException() != null) {
				logger.error("Failed to decompile '{}'", className, result.getException());
				success = false;
			}
			if (result.getType() != DecompileResult.ResultType.SUCCESS) {
				success = false;
			}

			text = result.getText();
			if (text == null) {
				text = buildFailureStub(className, error != null ? error : result.getException(), result.getType());
				success = false;
			}
		}

		return new DecompiledSource(text, success);
	}

	private static boolean writeSource(@Nonnull Path outputPath,
	                                   @Nonnull String className,
	                                   @Nonnull DecompiledSource source) {
		try {
			Path parent = outputPath.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(outputPath, source.text(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			logger.error("Failed writing decompiled class '{}' to directory", className, ex);
			return false;
		}

		return source.success();
	}

	@Nonnull
	private static String buildFailureStub(@Nonnull String className,
	                                       Throwable error,
	                                       DecompileResult.ResultType resultType) {
		StringBuilder builder = new StringBuilder();
		builder.append("// Failed to decompile '").append(className).append('\'').append('\n');
		if (resultType != null) {
			builder.append("// Result type: ").append(resultType).append('\n');
		}
		if (error != null) {
			builder.append("// ")
					.append(StringUtil.traceToString(error).replace("\n", "\n// "));
		} else {
			builder.append("// No additional error details were reported.");
		}
		return builder.toString();
	}

	private record ClassExportTask(@Nonnull JvmClassInfo classInfo,
	                               @Nonnull String displayName,
	                               @Nonnull Path outputPath) {
	}

	private record DecompiledSource(@Nonnull String text, boolean success) {
	}

	/**
	 * Rate limits {@link Callbacks} notifications so that exporting thousands of classes does not flood the UI thread
	 * with one update per class.
	 */
	private static final class ProgressThrottle {
		private final Callbacks callbacks;
		private final int jarNumber;
		private final int totalJars;
		private final int classCount;
		private long lastEmitMs = System.currentTimeMillis();
		private int lastEmitCount;

		private ProgressThrottle(@Nonnull Callbacks callbacks, int jarNumber, int totalJars, int classCount) {
			this.callbacks = callbacks;
			this.jarNumber = jarNumber;
			this.totalJars = totalJars;
			this.classCount = classCount;
		}

		private synchronized void onClassDone(@Nonnull String className, int done, int ok, int skipped, int failed) {
			long now = System.currentTimeMillis();
			if (done - lastEmitCount < PROGRESS_CLASS_INTERVAL && now - lastEmitMs < PROGRESS_INTERVAL_MS)
				return;
			lastEmitMs = now;
			lastEmitCount = done;
			emit(className, done, ok, skipped, failed);
		}

		private void emit(@Nonnull String className, int done, int ok, int skipped, int failed) {
			callbacks.onCurrentClass(className);
			callbacks.onProgress(jarNumber, totalJars, classCount == 0 ? 1.0 : (double) done / classCount, ok, skipped, failed);
		}
	}
}
