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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

final class BatchDecompileJarsRunner {
	private static final Logger logger = Logging.get(BatchDecompileJarsRunner.class);
	private static final String EMBEDDED_DIR = "_embedded";

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
				boolean jarFailed = exportJar(workspace, decompiler, jarOutputDir, jarNumber, totalJars, okJars, skippedJars, failedJars, callbacks);
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

		callbacks.onComplete(okJars.get(), skippedJars.get(), failedJars.get());
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

		for (ClassExportTask task : targetClasses) {
			decompilerManager.decompile(decompiler, workspace, task.classInfo())
					.orTimeout(timeoutSeconds, TimeUnit.SECONDS)
					.whenComplete((result, error) -> {
						try {
							if (!writeDecompileResult(task.outputPath(), task.displayName(), result, error)) {
								jarFailed.set(true);
							}
						} finally {
							int done = completedClasses.incrementAndGet();
							int remaining = remainingClasses.decrementAndGet();
							double jarFraction = (double) done / classCount;
							callbacks.onCurrentClass(task.displayName());
							callbacks.onProgress(jarNumber, totalJars, jarFraction, okJars.get(), skippedJars.get(), failedJars.get());
							if (remaining <= 0) {
								jarFuture.complete(null);
							}
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

	private static boolean writeDecompileResult(@Nonnull Path outputPath,
	                                            @Nonnull String className,
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

		try {
			Path parent = outputPath.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(outputPath, text, StandardCharsets.UTF_8);
		} catch (IOException ex) {
			logger.error("Failed writing decompiled class '{}' to directory", className, ex);
			return false;
		}

		return success;
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
}
