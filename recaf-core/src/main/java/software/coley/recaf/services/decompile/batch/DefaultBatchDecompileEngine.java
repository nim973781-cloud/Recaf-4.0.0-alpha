package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.properties.builtin.CachedDecompileProperty;
import software.coley.recaf.services.decompile.DecompileCacheMode;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.decompile.batch.pipeline.BatchPipeline;
import software.coley.recaf.services.decompile.batch.pipeline.PipelineStage;
import software.coley.recaf.services.decompile.batch.pipeline.TimeoutBudget;
import software.coley.recaf.services.decompile.batch.session.BatchDecompileSession;
import software.coley.recaf.services.decompile.batch.session.BatchDecompileSessionFactory;
import software.coley.recaf.services.decompile.batch.session.SessionClassResult;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.MappingApplier;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.RecursiveMappingOptions;
import software.coley.recaf.services.mapping.format.InvalidMappingException;
import software.coley.recaf.services.mapping.format.MappingFileFormat;
import software.coley.recaf.services.mapping.format.MappingFormatManager;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.util.StringUtil;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.bundle.VersionedJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Default {@link BatchDecompileEngine}, built as a pipeline over the services the UI batch runner used.
 * <h2>Pipeline</h2>
 * A run is three stages with very different appetites. Importing an archive is single threaded IO,
 * decompiling it wants every core, and writing the output is one sequential append. Run back to back
 * they spend most of the wall clock waiting for each other, so {@link #run} drives them as a
 * {@link BatchPipeline}: the next archive is imported, mapped and collected on its own thread while the
 * current one is still being decompiled, class work saturates the
 * {@link BatchDecompileScheduler decompile pool}, and the archive sink compresses on the producing
 * thread so its writer thread only ever appends.
 * <h2>Accuracy</h2>
 * Class decompilation goes through a {@link BatchDecompileSession}, opened once per input by the first
 * {@link BatchDecompileSessionFactory} that supports the run's decompiler. The accuracy mode is a
 * parameter of that session rather than a branch in the engine. When no factory claims the decompiler,
 * or the one that does declines the requested accuracy, every class goes through
 * {@link DecompilerManager#decompile(JvmDecompiler, Workspace, JvmClassInfo, DecompileCacheMode)}, the
 * single-class path that defines correctness. Mappings always go through
 * {@link MappingApplier#applyToResourceRecursive(software.coley.recaf.services.mapping.Mappings, WorkspaceResource, RecursiveMappingOptions)}.
 * <h2>Timeouts</h2>
 * {@link BatchDecompileRequest#timeoutPerClass()} starts when a decompiler picks the class up, not when
 * it is queued, see {@link BatchDecompileScheduler#runBudgeted}. A class can no longer be reported as
 * timed out because other classes were ahead of it.
 * <h2>Memory</h2>
 * At most one archive is imported ahead of the one being decompiled, and each is closed as soon as its
 * classes are written. Decompiled source is handed straight to the sink and classes are decompiled with
 * {@link DecompileCacheMode#NONE}, so the manager's {@link CachedDecompileProperty per-class cache}
 * never holds the sources of an archive alive.
 * <h2>Workspace exports</h2>
 * {@link #exportWorkspace(WorkspaceDecompileRequest, BatchDecompileProgressListener)} runs the same
 * collection, scheduling and sink code against a workspace the user already has open, so no temporary
 * archive is written and unsaved edits are exported as they are. That path owns neither the workspace
 * nor its cached decompilations, so its cache mode comes from the request rather than being forced to
 * {@link DecompileCacheMode#NONE}.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class DefaultBatchDecompileEngine implements BatchDecompileEngine {
	private static final Logger logger = Logging.get(DefaultBatchDecompileEngine.class);
	private static final String EMBEDDED_DIR = "_embedded";
	private static final char[] HEX = "0123456789abcdef".toCharArray();
	/**
	 * How many archives may be imported ahead of the one being decompiled. One is enough to cover the
	 * import of the next archive with the decompilation of the current one, and holding more would scale
	 * peak memory with the prefetch depth for no extra overlap.
	 */
	private static final int IMPORT_PREFETCH = 1;

	private final DecompilerManager decompilerManager;
	private final MappingApplierService mappingApplierService;
	private final MappingFormatManager mappingFormatManager;
	private final ResourceImporter resourceImporter;
	private final Instance<BatchDecompileSessionFactory> sessionFactories;
	private volatile boolean pipelinedImports = true;

	/**
	 * @param decompilerManager
	 * 		Manager to pull the target decompiler from, and to decompile single classes with.
	 * @param mappingApplierService
	 * 		Service creating mapping appliers for the temporary batch workspaces.
	 * @param mappingFormatManager
	 * 		Manager to resolve the requested mapping format with.
	 * @param resourceImporter
	 * 		Importer creating a workspace resource per input archive.
	 * @param sessionFactories
	 * 		Factories able to decompile a whole workspace worth of classes in one session.
	 */
	@Inject
	public DefaultBatchDecompileEngine(@Nonnull DecompilerManager decompilerManager,
	                                   @Nonnull MappingApplierService mappingApplierService,
	                                   @Nonnull MappingFormatManager mappingFormatManager,
	                                   @Nonnull ResourceImporter resourceImporter,
	                                   @Nonnull Instance<BatchDecompileSessionFactory> sessionFactories) {
		this.decompilerManager = decompilerManager;
		this.mappingApplierService = mappingApplierService;
		this.mappingFormatManager = mappingFormatManager;
		this.resourceImporter = resourceImporter;
		this.sessionFactories = sessionFactories;
	}

	@Nonnull
	@Override
	public BatchDecompileReport run(@Nonnull BatchDecompileRequest request,
	                                @Nonnull BatchDecompileProgressListener listener) throws BatchDecompileException {
		Instant startedAt = Instant.now();
		JvmDecompiler decompiler = resolveDecompiler(request.decompilerName());
		BatchDecompileSessionFactory factory = resolveSessionFactory(decompiler);
		logAccuracyMode(request.accuracyMode(), decompiler, factory);

		BatchDecompilePlan plan = plan(request);
		RunState state = new RunState(plan.jarCount(), request.reportPath() != null);
		ThrottledProgressListener throttle = new ThrottledProgressListener(listener);
		throttle.flush(state.snapshot());

		CollectOptions collectOptions = CollectOptions.of(request);
		ClassExportOptions classOptions = ClassExportOptions.of(request);
		PipelineStage<JarDecompilePlan, ImportedJar> importStage =
				scanned -> importJar(collectOptions, plan.mappingPlan(), scanned);
		try (BatchDecompileSink sink = createSink(request.outputFormat(), request.outputPath());
		     BatchDecompileScheduler scheduler = new BatchDecompileScheduler(
				     request.normalizedDecompileWorkers(), request.normalizedIoWorkers());
		     JarSource imports = openImports(plan.jars(), importStage)) {
			BatchPipeline.Item<JarDecompilePlan, ImportedJar> item;
			while ((item = imports.next()) != null)
				processJar(decompiler, factory, classOptions, item, sink, scheduler, state, throttle);
			state.outputFileCount = sink.stats().fileCount();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new BatchDecompileException("Batch decompile was interrupted", ex);
		} catch (IOException ex) {
			throw new BatchDecompileException("Failed writing batch decompile output to " + request.outputPath(), ex);
		}

		state.currentJar = null;
		state.currentClass = null;
		throttle.flush(state.snapshot());

		return writeReport(state.toReport(startedAt, Instant.now()), request.reportPath());
	}

	@Nonnull
	@Override
	public BatchDecompileReport exportWorkspace(@Nonnull WorkspaceDecompileRequest request,
	                                            @Nonnull BatchDecompileProgressListener listener) throws BatchDecompileException {
		Instant startedAt = Instant.now();
		JvmDecompiler decompiler = resolveDecompiler(request.decompilerName());
		BatchDecompileSessionFactory factory = resolveSessionFactory(decompiler);
		WorkspaceBatchExport plan = planWorkspace(request);

		// A workspace export is a single unit of work, so it is reported as one "jar" and its progress
		// fraction comes entirely from class completion within it.
		RunState state = new RunState(1, request.reportPath() != null);
		ThrottledProgressListener throttle = new ThrottledProgressListener(listener);
		throttle.flush(state.snapshot());
		state.beginJar(plan.workspaceName());
		state.skippedClasses += plan.skippedClasses();
		state.beginJarContents(plan.classCount());
		throttle.flush(state.snapshot());

		if (plan.isEmpty()) {
			// Nothing matched, so no output file or directory is created at all.
			state.skippedJars++;
		} else {
			// Unlike the JAR path there is no BatchDecompileSink#beginJar call here. That call wipes the
			// output sub-directory of a rerun, which is safe for a per-JAR sub-directory but not for a
			// user-chosen export directory that may hold unrelated files.
			try (BatchDecompileSink sink = createSink(request.outputFormat(), request.outputPath());
			     BatchDecompileScheduler scheduler = new BatchDecompileScheduler(
					     request.normalizedDecompileWorkers(), request.normalizedIoWorkers())) {
				boolean failed = exportResources(plan.resources(), sink, scheduler, state);
				failed |= exportClasses(decompiler, factory, request.workspace(), plan.classes(),
						ClassExportOptions.of(request), sink, scheduler, state, throttle);
				if (failed)
					state.failedJars++;
				else
					state.okJars++;
				state.outputFileCount = sink.stats().fileCount();
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new BatchDecompileException("Workspace decompile export was interrupted", ex);
			} catch (IOException ex) {
				throw new BatchDecompileException("Failed writing workspace decompile output to "
						+ request.outputPath(), ex);
			}
		}

		state.endJar();
		state.currentJar = null;
		state.currentClass = null;
		throttle.flush(state.snapshot());

		return writeReport(state.toReport(startedAt, Instant.now()), request.reportPath());
	}

	/**
	 * Resolves which classes and files of the workspace the request covers, without decompiling anything.
	 *
	 * @param request
	 * 		Description of the export.
	 *
	 * @return Plan of the contents the export will write.
	 */
	@Nonnull
	public WorkspaceBatchExport planWorkspace(@Nonnull WorkspaceDecompileRequest request) {
		WorkspaceResource resource = request.workspace().getPrimaryResource();
		String workspaceName = workspaceName(request.workspace());
		String prefix = BatchOutputPath.normalize(request.outputName());
		CollectOptions options = CollectOptions.of(request);
		Collected collected = new Collected();

		JvmClassBundle targetBundle = request.targetBundle();
		if (targetBundle == null) {
			collectFromResource(options, workspaceName, resource, prefix, collected);
		} else {
			// An explicitly targeted bundle is exported on its own. Files still come from the primary
			// resource, matching what the bundle and package context menus have always exported.
			collectFromBundle(options, workspaceName, targetBundle, prefix, collected);
			if (options.includeResources())
				collectFiles(options, workspaceName, resource, prefix, collected);
		}

		collected.sort();
		return new WorkspaceBatchExport(workspaceName, List.copyOf(collected.classes),
				List.copyOf(collected.resources), collected.skipped);
	}

	@Nonnull
	private static BatchDecompileReport writeReport(@Nonnull BatchDecompileReport report,
	                                                @Nullable Path reportPath) throws BatchDecompileException {
		if (reportPath != null) {
			try {
				BatchDecompileReportWriter.write(report, reportPath);
			} catch (IOException ex) {
				throw new BatchDecompileException("Failed writing batch decompile report to " + reportPath, ex);
			}
		}
		return report;
	}

	@Nonnull
	private static String workspaceName(@Nonnull Workspace workspace) {
		if (workspace.getPrimaryResource() instanceof WorkspaceFileResource fileResource)
			return StringUtil.shortenPath(fileResource.getFileInfo().getName());
		return "workspace";
	}

	/**
	 * Scans the request inputs and parses its mappings, without decompiling anything.
	 *
	 * @param request
	 * 		Description of the run.
	 *
	 * @return Plan of the inputs the run will process.
	 *
	 * @throws BatchDecompileException
	 * 		When the input directory cannot be listed, or the mappings cannot be parsed.
	 */
	@Nonnull
	public BatchDecompilePlan plan(@Nonnull BatchDecompileRequest request) throws BatchDecompileException {
		Path inputDirectory = request.inputDirectory();
		if (!Files.isDirectory(inputDirectory))
			throw new BatchDecompileException("Batch input is not a directory: " + inputDirectory);

		List<JarDecompilePlan> jars;
		try (Stream<Path> stream = Files.list(inputDirectory)) {
			jars = stream.filter(Files::isRegularFile)
					.filter(DefaultBatchDecompileEngine::isSupportedArchive)
					.sorted(Comparator.comparing(path -> fileName(path).toLowerCase(Locale.ROOT)))
					.map(path -> JarDecompilePlan.scanned(path, StringUtil.removeExtension(fileName(path))))
					.toList();
		} catch (IOException ex) {
			throw new BatchDecompileException("Failed listing batch inputs in " + inputDirectory, ex);
		}
		return new BatchDecompilePlan(jars, parseMappings(request), request.outputFormat());
	}

	/**
	 * Parses the request mapping file exactly once.
	 *
	 * @param request
	 * 		Description of the run.
	 *
	 * @return Parsed mappings, or {@link BatchMappingPlan#none()} when the request has no mapping file.
	 *
	 * @throws BatchDecompileException
	 * 		When the format is unknown or the file cannot be parsed.
	 */
	@Nonnull
	public BatchMappingPlan parseMappings(@Nonnull BatchDecompileRequest request) throws BatchDecompileException {
		Path mappingFile = request.mappingFile();
		if (mappingFile == null)
			return BatchMappingPlan.none();

		String formatName = request.mappingFormat();
		if (formatName == null || formatName.isBlank())
			throw new BatchDecompileException("Mapping file '" + mappingFile + "' was given without a mapping format");

		MappingFileFormat format = mappingFormatManager.createFormatInstance(formatName);
		if (format == null)
			throw new BatchDecompileException("Unknown mapping format '" + formatName + "'");

		try {
			return new BatchMappingPlan(mappingFile, formatName, format.parse(mappingFile));
		} catch (InvalidMappingException ex) {
			throw new BatchDecompileException("Failed parsing mappings from " + mappingFile, ex);
		}
	}

	@Nonnull
	private JvmDecompiler resolveDecompiler(@Nonnull String name) throws BatchDecompileException {
		if (name.isBlank())
			return decompilerManager.getTargetJvmDecompiler();
		JvmDecompiler decompiler = decompilerManager.getJvmDecompiler(name);
		if (decompiler == null)
			throw new BatchDecompileException("No JVM decompiler is registered under the name '" + name + "'");
		return decompiler;
	}

	/**
	 * @param decompiler
	 * 		Decompiler of the run.
	 *
	 * @return First registered factory claiming the decompiler, or {@code null} when none does and the run
	 * has to decompile one class at a time.
	 */
	@Nullable
	private BatchDecompileSessionFactory resolveSessionFactory(@Nonnull JvmDecompiler decompiler) {
		if (sessionFactories.isUnsatisfied())
			return null;
		for (BatchDecompileSessionFactory factory : sessionFactories)
			if (factory.supports(decompiler))
				return factory;
		return null;
	}

	private void logAccuracyMode(@Nonnull BatchAccuracyMode mode, @Nonnull JvmDecompiler decompiler,
	                             @Nullable BatchDecompileSessionFactory factory) {
		if (mode == BatchAccuracyMode.ACCURATE)
			return;
		if (factory == null)
			logger.warn("Accuracy mode {} has no batch session for decompiler '{}', using the single-class path",
					mode, decompiler.getName());
		else if (mode == BatchAccuracyMode.FAST_UNSAFE)
			logger.warn("Accuracy mode {} output is not acceptance-grade, " +
					"it may differ from the single-class decompile path", mode);
	}

	@Nonnull
	private static BatchDecompileSink createSink(@Nonnull BatchOutputFormat format,
	                                             @Nonnull Path outputPath) throws BatchDecompileException {
		try {
			return switch (format) {
				case DIRECTORY -> new DirectoryDecompileSink(outputPath);
				case ZIP -> new StreamingZipDecompileSink(outputPath);
			};
		} catch (IOException ex) {
			throw new BatchDecompileException("Failed opening batch output at " + outputPath, ex);
		}
	}

	/**
	 * Runs the import stage inline instead of ahead of the decompile stage.
	 * <p>
	 * The pipeline must not change what a run produces, only when the work happens. This switch exists so
	 * a test can hold the sequential output next to the pipelined output and pin them to each other.
	 *
	 * @param pipelined
	 * 		{@code true} to import the next archive while the current one is decompiling.
	 */
	void setPipelinedImports(boolean pipelined) {
		this.pipelinedImports = pipelined;
	}

	@Nonnull
	private JarSource openImports(@Nonnull List<JarDecompilePlan> jars,
	                              @Nonnull PipelineStage<JarDecompilePlan, ImportedJar> stage) {
		if (!pipelinedImports)
			return new InlineJarSource(jars, stage);
		BatchPipeline<JarDecompilePlan, ImportedJar> pipeline =
				BatchPipeline.prefetch("batch-import", jars, stage, IMPORT_PREFETCH, ImportedJar::close);
		return new JarSource() {
			@Nullable
			@Override
			public BatchPipeline.Item<JarDecompilePlan, ImportedJar> next() throws InterruptedException {
				return pipeline.next();
			}

			@Override
			public void close() {
				pipeline.close();
			}
		};
	}

	/**
	 * Import stage of the pipeline. Runs off the driving thread, so the next archive is opened, mapped and
	 * collected while the current one is still being decompiled.
	 *
	 * @param options
	 * 		Content selection of the run.
	 * @param mappingPlan
	 * 		Mappings to apply, if any.
	 * @param scanned
	 * 		Input-only plan of the archive.
	 *
	 * @return Imported archive, ready to decompile.
	 *
	 * @throws Exception
	 * 		When the archive cannot be imported at all. The pipeline records it against this input.
	 */
	@Nonnull
	private ImportedJar importJar(@Nonnull CollectOptions options,
	                              @Nonnull BatchMappingPlan mappingPlan,
	                              @Nonnull JarDecompilePlan scanned) throws Exception {
		WorkspaceResource primaryResource = resourceImporter.importResource(scanned.inputJar());
		Workspace workspace = new BasicWorkspace(primaryResource, List.of());
		try {
			Throwable mappingError = null;
			IntermediateMappings mappings = mappingPlan.mappings();
			if (mappings != null) {
				try {
					applyMappings(workspace, mappings);
				} catch (Throwable t) {
					logger.error("Failed applying mappings to '{}'", scanned.jarName(), t);
					mappingError = t;
				}
			}

			Collected collected = new Collected();
			collectFromResource(options, scanned.jarName(), workspace.getPrimaryResource(),
					BatchOutputPath.normalize(scanned.outputName()), collected);
			collected.sort();
			return new ImportedJar(scanned.withContents(collected.classes, collected.resources),
					workspace, mappingError, collected.skipped);
		} catch (Throwable t) {
			workspace.close();
			throw t;
		}
	}

	private void processJar(@Nonnull JvmDecompiler decompiler,
	                        @Nullable BatchDecompileSessionFactory factory,
	                        @Nonnull ClassExportOptions options,
	                        @Nonnull BatchPipeline.Item<JarDecompilePlan, ImportedJar> item,
	                        @Nonnull BatchDecompileSink sink,
	                        @Nonnull BatchDecompileScheduler scheduler,
	                        @Nonnull RunState state,
	                        @Nonnull ThrottledProgressListener throttle) throws InterruptedException {
		JarDecompilePlan scanned = item.input();
		String jarName = scanned.jarName();
		state.beginJar(jarName);
		throttle.flush(state.snapshot());

		ImportedJar imported = item.value();
		boolean jarFailed = false;
		try {
			if (imported == null) {
				Throwable error = item.error() == null
						? new IllegalStateException("Import produced no workspace for " + jarName)
						: item.error();
				logger.error("Failed importing '{}'", scanned.inputJar(), error);
				state.addFailure(BatchDecompileFailure.of(jarName, null, BatchDecompileFailure.PHASE_IMPORT, error));
				state.failedJars++;
				return;
			}

			try {
				sink.beginJar(scanned);
			} catch (IOException ex) {
				logger.error("Failed preparing output for '{}'", jarName, ex);
				state.addFailure(BatchDecompileFailure.of(jarName, null, BatchDecompileFailure.PHASE_PREPARE, ex));
				state.failedJars++;
				return;
			}

			if (imported.mappingError() != null) {
				state.addFailure(BatchDecompileFailure.of(jarName, null, BatchDecompileFailure.PHASE_MAPPING,
						imported.mappingError()));
				jarFailed = true;
			}

			JarDecompilePlan populated = imported.plan();
			state.skippedClasses += imported.skippedClasses();
			state.beginJarContents(populated.classCount());

			if (populated.classCount() == 0 && populated.resourceCount() == 0) {
				state.skippedJars++;
				return;
			}

			jarFailed |= exportResources(populated.resources(), sink, scheduler, state);
			jarFailed |= exportClasses(decompiler, factory, imported.workspace(), populated.classes(), options,
					sink, scheduler, state, throttle);

			try {
				sink.endJar(populated);
			} catch (IOException ex) {
				logger.error("Failed finalizing output for '{}'", jarName, ex);
				state.addFailure(BatchDecompileFailure.of(jarName, null, BatchDecompileFailure.PHASE_WRITE, ex));
				jarFailed = true;
			}

			if (jarFailed)
				state.failedJars++;
			else
				state.okJars++;
		} finally {
			if (imported != null)
				imported.close();
			state.endJar();
			throttle.flush(state.snapshot());
		}
	}

	private void applyMappings(@Nonnull Workspace workspace, @Nonnull IntermediateMappings mappings) {
		// The recursive path enriches the mappings once for the whole resource tree and clears cached
		// decompilations once at the end, rather than doing both per bundle.
		MappingApplier applier = mappingApplierService.inWorkspace(workspace);
		applier.applyToResourceRecursive(mappings, workspace.getPrimaryResource(),
				RecursiveMappingOptions.defaults()).apply();
	}

	private void collectFromResource(@Nonnull CollectOptions options,
	                                 @Nonnull String sourceName,
	                                 @Nonnull WorkspaceResource resource,
	                                 @Nonnull String prefix,
	                                 @Nonnull Collected collected) {
		if (options.includeResources())
			collectFiles(options, sourceName, resource, prefix, collected);

		collectFromBundle(options, sourceName, resource.getJvmClassBundle(), prefix, collected);

		if (options.includeMultiRelease()) {
			for (VersionedJvmClassBundle bundle : resource.getVersionedJvmClassBundles().values()) {
				String versionedPrefix = BatchOutputPath.join(prefix, "META-INF/versions/" + bundle.version());
				collectFromBundle(options, sourceName, bundle, versionedPrefix, collected);
			}
		}

		if (options.includeEmbedded()) {
			for (WorkspaceFileResource embedded : resource.getEmbeddedResources().values()) {
				String embeddedName = StringUtil.removeExtension(embedded.getFileInfo().getName());
				String embeddedPrefix = BatchOutputPath.join(prefix, EMBEDDED_DIR + '/' + embeddedName);
				collectFromResource(options, sourceName, embedded, embeddedPrefix, collected);
			}
		}
	}

	private void collectFiles(@Nonnull CollectOptions options,
	                          @Nonnull String sourceName,
	                          @Nonnull WorkspaceResource resource,
	                          @Nonnull String prefix,
	                          @Nonnull Collected collected) {
		for (FileInfo fileInfo : resource.getFileBundle()) {
			String name = fileInfo.getName();
			if (!options.matches(name))
				continue;
			collected.resources.add(new ResourceExportTask(sourceName, name,
					BatchOutputPath.join(prefix, name), fileInfo::getRawContent));
		}
	}

	private void collectFromBundle(@Nonnull CollectOptions options,
	                               @Nonnull String sourceName,
	                               @Nonnull JvmClassBundle bundle,
	                               @Nonnull String prefix,
	                               @Nonnull Collected collected) {
		for (JvmClassInfo cls : bundle.values()) {
			String name = cls.getName();
			if (!isExportableClass(cls) || !options.matches(name)) {
				collected.skipped++;
				continue;
			}
			collected.classes.add(new ClassExportTask(sourceName, name,
					BatchOutputPath.join(prefix, name + ".java"), cls));
		}
	}

	private boolean exportResources(@Nonnull List<ResourceExportTask> resources,
	                                @Nonnull BatchDecompileSink sink,
	                                @Nonnull BatchDecompileScheduler scheduler,
	                                @Nonnull RunState state) throws InterruptedException {
		if (resources.isEmpty()) return false;

		AtomicBoolean failed = new AtomicBoolean();
		if (sink.requiresOrderedWrites()) {
			for (ResourceExportTask task : resources) {
				try {
					writeResource(sink, task, state);
				} catch (Throwable t) {
					recordResourceFailure(state, failed, task, t);
				}
			}
		} else {
			scheduler.runIo(resources,
					task -> writeResource(sink, task, state),
					(task, error) -> recordResourceFailure(state, failed, task, error));
		}
		return failed.get();
	}

	private static void writeResource(@Nonnull BatchDecompileSink sink,
	                                  @Nonnull ResourceExportTask task,
	                                  @Nonnull RunState state) throws IOException {
		byte[] content = task.content();
		sink.writeResource(task, content);
		state.recordResourceHash(task.outputPath(), content);
	}

	private static void recordResourceFailure(@Nonnull RunState state, @Nonnull AtomicBoolean failed,
	                                          @Nonnull ResourceExportTask task, @Nonnull Throwable error) {
		logger.error("Failed exporting resource '{}' of '{}'", task.resourceName(), task.jarName(), error);
		state.addFailure(BatchDecompileFailure.of(task.jarName(), null, BatchDecompileFailure.PHASE_RESOURCE, error));
		failed.set(true);
	}

	private boolean exportClasses(@Nonnull JvmDecompiler decompiler,
	                              @Nullable BatchDecompileSessionFactory factory,
	                              @Nonnull Workspace workspace,
	                              @Nonnull List<ClassExportTask> classes,
	                              @Nonnull ClassExportOptions options,
	                              @Nonnull BatchDecompileSink sink,
	                              @Nonnull BatchDecompileScheduler scheduler,
	                              @Nonnull RunState state,
	                              @Nonnull ThrottledProgressListener throttle) throws InterruptedException {
		if (classes.isEmpty()) return false;

		BatchDecompileSession session = openSession(factory, workspace, options.accuracyMode());
		if (session == null)
			return exportClassesOneByOne(decompiler, workspace, classes, options, sink, scheduler, state, throttle);
		try {
			return exportClassesBySession(session, classes, options, sink, scheduler, state, throttle);
		} finally {
			session.close();
		}
	}

	@Nullable
	private BatchDecompileSession openSession(@Nullable BatchDecompileSessionFactory factory,
	                                          @Nonnull Workspace workspace,
	                                          @Nonnull BatchAccuracyMode mode) {
		if (factory == null)
			return null;
		try {
			return factory.open(workspace, mode);
		} catch (Throwable t) {
			logger.warn("Batch decompile session factory {} failed to open, using the single-class path",
					factory.getClass().getSimpleName(), t);
			return null;
		}
	}

	/**
	 * Single-class path, which is what defines correct output. One class per scheduled task, one call into
	 * {@link DecompilerManager} per class.
	 */
	private boolean exportClassesOneByOne(@Nonnull JvmDecompiler decompiler,
	                                      @Nonnull Workspace workspace,
	                                      @Nonnull List<ClassExportTask> classes,
	                                      @Nonnull ClassExportOptions options,
	                                      @Nonnull BatchDecompileSink sink,
	                                      @Nonnull BatchDecompileScheduler scheduler,
	                                      @Nonnull RunState state,
	                                      @Nonnull ThrottledProgressListener throttle) throws InterruptedException {
		AtomicBoolean failed = new AtomicBoolean();
		scheduler.runBudgeted(classes, options.timeoutMillis(),
				(task, budget) -> await(decompilerManager.decompile(decompiler, workspace,
						task.classInfo(), options.cacheMode()), budget),
				(task, result, error) -> {
					if (!writeClassOutcome(options.writeFailureStubs(), sink, state, task, result, error))
						failed.set(true);
					state.completeClass(task.className());
					throttle.onProgress(state::snapshot);
				});
		return failed.get();
	}

	/**
	 * Session path, decompiling whole groups of classes per call instead of one class at a time.
	 * <p>
	 * Groups are formed over the plan-ordered class list and completions are drained in submission order,
	 * so output stays as deterministic as the single-class path. Classes that yield no output get the same
	 * failure stub treatment as a failed single-class decompilation; one broken class never discards the
	 * rest of its group. The per-class timeout budget is pooled per group, since the classes of a group
	 * are decompiled together.
	 */
	private boolean exportClassesBySession(@Nonnull BatchDecompileSession session,
	                                       @Nonnull List<ClassExportTask> classes,
	                                       @Nonnull ClassExportOptions options,
	                                       @Nonnull BatchDecompileSink sink,
	                                       @Nonnull BatchDecompileScheduler scheduler,
	                                       @Nonnull RunState state,
	                                       @Nonnull ThrottledProgressListener throttle) throws InterruptedException {
		List<List<ClassExportTask>> groups = session.partition(classes);
		AtomicBoolean failed = new AtomicBoolean();

		scheduler.runBudgeted(groups, group -> options.timeoutMillis() * Math.max(1, group.size()),
				(group, budget) -> await(session.decompile(group), budget),
				(group, outcomes, error) -> {
					for (ClassExportTask task : group) {
						SessionClassResult outcome = outcomes == null ? null : outcomes.get(task.className());
						DecompileResult result = null;
						Throwable classError = error;
						if (outcome != null && outcome.text() != null)
							// The result never enters the manager's decompile cache, so no config hash is needed.
							result = new DecompileResult(outcome.text(), 0);
						else if (classError == null)
							classError = outcome == null
									? new IllegalStateException("Session produced no outcome for " + task.className())
									: outcome.failure();
						if (!writeClassOutcome(options.writeFailureStubs(), sink, state, task, result, classError))
							failed.set(true);
						state.completeClass(task.className());
						throttle.onProgress(state::snapshot);
					}
				});
		return failed.get();
	}

	/**
	 * Waits for asynchronous decompilation within the budget it was given, and cancels the work when the
	 * budget runs out so a hung class cannot keep burning a core for the rest of the run.
	 */
	private static <T> T await(@Nonnull CompletableFuture<T> future, @Nonnull TimeoutBudget budget)
			throws Exception {
		try {
			return future.get(budget.remainingMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException ex) {
			future.cancel(true);
			throw ex;
		} catch (InterruptedException ex) {
			future.cancel(true);
			throw ex;
		}
	}

	private static boolean writeClassOutcome(boolean writeFailureStubs,
	                                         @Nonnull BatchDecompileSink sink,
	                                         @Nonnull RunState state,
	                                         @Nonnull ClassExportTask task,
	                                         @Nullable DecompileResult result,
	                                         @Nullable Throwable error) {
		String className = task.className();
		Throwable cause = error;
		DecompileResult.ResultType resultType = null;
		boolean success = true;
		String text;

		if (result == null) {
			text = null;
			success = false;
		} else {
			resultType = result.getType();
			if (cause == null)
				cause = result.getException();
			if (error != null || result.getException() != null || resultType != DecompileResult.ResultType.SUCCESS)
				success = false;
			text = result.getText();
		}

		boolean stub = text == null;
		if (stub) {
			success = false;
			text = BatchFailureStub.build(className, cause, resultType);
		}

		if (!success) {
			String phase = cause instanceof TimeoutException
					? BatchDecompileFailure.PHASE_TIMEOUT
					: BatchDecompileFailure.PHASE_DECOMPILE;
			logger.error("Failed to decompile '{}'", className, cause);
			state.addFailure(cause == null
					? new BatchDecompileFailure(task.jarName(), className, phase,
					"Decompiler reported " + resultType, null)
					: BatchDecompileFailure.of(task.jarName(), className, phase, cause));
		}

		if (stub && !writeFailureStubs) {
			state.failedClasses++;
			return false;
		}

		try {
			if (stub)
				sink.writeFailure(task, text);
			else
				sink.writeClass(task, text);
		} catch (IOException ex) {
			logger.error("Failed writing decompilation of '{}'", className, ex);
			state.addFailure(BatchDecompileFailure.of(task.jarName(), className, BatchDecompileFailure.PHASE_WRITE, ex));
			state.failedClasses++;
			return false;
		}

		if (success)
			state.okClasses++;
		else
			state.failedClasses++;
		return success;
	}

	private static boolean isExportableClass(@Nonnull JvmClassInfo cls) {
		if (cls.isInnerClass()) return false;
		String name = cls.getName();
		return cls.getSuperName() != null || (!name.equals("module-info") && !name.endsWith("package-info"));
	}

	private static boolean isSupportedArchive(@Nonnull Path path) {
		String name = fileName(path).toLowerCase(Locale.ROOT);
		return name.endsWith(".jar") || name.endsWith(".zip");
	}

	@Nonnull
	private static String fileName(@Nonnull Path path) {
		Path name = path.getFileName();
		return name == null ? path.toString() : name.toString();
	}

	/**
	 * Where {@link #run} pulls imported archives from, either the prefetching pipeline or the sequential
	 * path it must stay equivalent to.
	 */
	private interface JarSource extends AutoCloseable {
		/**
		 * @return Next imported archive in plan order, or {@code null} once every input was handed out.
		 *
		 * @throws InterruptedException
		 * 		When the caller is interrupted while waiting for an import.
		 */
		@Nullable
		BatchPipeline.Item<JarDecompilePlan, ImportedJar> next() throws InterruptedException;

		@Override
		void close();
	}

	/**
	 * Imports each archive on the calling thread, one at a time.
	 */
	private static final class InlineJarSource implements JarSource {
		private final PipelineStage<JarDecompilePlan, ImportedJar> stage;
		private final List<JarDecompilePlan> jars;
		private int index;

		private InlineJarSource(@Nonnull List<JarDecompilePlan> jars,
		                        @Nonnull PipelineStage<JarDecompilePlan, ImportedJar> stage) {
			this.jars = jars;
			this.stage = stage;
		}

		@Nullable
		@Override
		public BatchPipeline.Item<JarDecompilePlan, ImportedJar> next() {
			if (index >= jars.size())
				return null;
			JarDecompilePlan scanned = jars.get(index++);
			try {
				return new BatchPipeline.Item<>(scanned, stage.apply(scanned), null);
			} catch (Throwable t) {
				return new BatchPipeline.Item<>(scanned, null, t);
			}
		}

		@Override
		public void close() {
			// Nothing is imported ahead, so there is nothing left to release.
		}
	}

	/**
	 * One archive that the import stage already opened, mapped and collected.
	 *
	 * @param plan
	 * 		Plan of the archive with its contents resolved.
	 * @param workspace
	 * 		Workspace holding the imported archive. Closed once the archive has been written.
	 * @param mappingError
	 * 		Error applying mappings, or {@code null}. Recorded against the archive without stopping it.
	 * @param skippedClasses
	 * 		Classes the collection pass excluded.
	 */
	private record ImportedJar(@Nonnull JarDecompilePlan plan, @Nonnull Workspace workspace,
	                           @Nullable Throwable mappingError, int skippedClasses) {
		private void close() {
			workspace.close();
		}
	}

	/**
	 * What a collection pass gathered from one resource tree.
	 */
	private static final class Collected {
		private final List<ClassExportTask> classes = new ArrayList<>();
		private final List<ResourceExportTask> resources = new ArrayList<>();
		private int skipped;

		private void sort() {
			classes.sort(Comparator.comparing(ClassExportTask::outputPath));
			resources.sort(Comparator.comparing(ResourceExportTask::outputPath));
		}
	}

	/**
	 * Content selection shared by the JAR and workspace paths.
	 *
	 * @param includeResources
	 * 		Copy non-class files into the output.
	 * @param includeEmbedded
	 * 		Descend into embedded archives.
	 * @param includeMultiRelease
	 * 		Export classes found under {@code META-INF/versions/<n>/}.
	 * @param packageFilter
	 * 		Internal package prefix limiting what is exported, or {@code null} for everything.
	 */
	private record CollectOptions(boolean includeResources, boolean includeEmbedded, boolean includeMultiRelease,
	                              @Nullable String packageFilter) {
		@Nonnull
		private static CollectOptions of(@Nonnull BatchDecompileRequest request) {
			return new CollectOptions(request.includeResources(), request.includeEmbeddedResources(),
					request.includeMultiReleaseClasses(), null);
		}

		@Nonnull
		private static CollectOptions of(@Nonnull WorkspaceDecompileRequest request) {
			return new CollectOptions(request.includeResources(), request.includeEmbeddedResources(),
					request.includeMultiReleaseClasses(), request.normalizedPackageFilter());
		}

		private boolean matches(@Nonnull String name) {
			return packageFilter == null || name.startsWith(packageFilter);
		}
	}

	/**
	 * Per-class decompilation settings shared by the JAR and workspace paths.
	 *
	 * @param timeoutMillis
	 * 		How long a single class may spend in the decompiler.
	 * @param cacheMode
	 * 		How the run interacts with the per-class decompilation cache.
	 * @param writeFailureStubs
	 * 		Write a {@link BatchFailureStub} for classes that fail to decompile.
	 * @param accuracyMode
	 * 		Accuracy the session is opened with.
	 */
	private record ClassExportOptions(long timeoutMillis, @Nonnull DecompileCacheMode cacheMode,
	                                  boolean writeFailureStubs, @Nonnull BatchAccuracyMode accuracyMode) {
		@Nonnull
		private static ClassExportOptions of(@Nonnull BatchDecompileRequest request) {
			// A batch run never reads a class twice, so caching results would only hold every decompiled
			// source of the JAR alive until the workspace is closed.
			return new ClassExportOptions(timeoutMillis(request.timeoutPerClass()), DecompileCacheMode.NONE,
					request.writeFailureStubs(), request.accuracyMode());
		}

		@Nonnull
		private static ClassExportOptions of(@Nonnull WorkspaceDecompileRequest request) {
			return new ClassExportOptions(timeoutMillis(request.timeoutPerClass()), request.cacheMode(),
					request.writeFailureStubs(), BatchAccuracyMode.ACCURATE);
		}

		private static long timeoutMillis(@Nonnull Duration timeout) {
			return Math.max(1L, timeout.toMillis());
		}
	}

	/**
	 * Mutable counters for one run.
	 * <p>
	 * Class counters and the JAR cursor are only touched by the thread driving the run. Failures and
	 * resource hashes can also be written by IO workers, so those two collections are concurrent.
	 */
	private static final class RunState {
		private final List<BatchDecompileFailure> failures = new ArrayList<>();
		private final Map<String, String> resourceSha256 = new ConcurrentHashMap<>();
		private final boolean hashResources;
		private final int totalJars;
		private int completedJars;
		private int okJars;
		private int skippedJars;
		private int failedJars;
		private int totalClasses;
		private int completedClasses;
		private int okClasses;
		private int skippedClasses;
		private int failedClasses;
		private int currentJarClasses;
		private int currentJarCompleted;
		private long outputFileCount;
		private String currentJar;
		private String currentClass;

		private RunState(int totalJars, boolean hashResources) {
			this.totalJars = totalJars;
			this.hashResources = hashResources;
		}

		private void beginJar(@Nonnull String jarName) {
			currentJar = jarName;
			currentClass = null;
			currentJarClasses = 0;
			currentJarCompleted = 0;
		}

		private void beginJarContents(int classCount) {
			currentJarClasses = classCount;
			totalClasses += classCount;
		}

		private void completeClass(@Nonnull String className) {
			currentClass = className;
			completedClasses++;
			currentJarCompleted++;
		}

		private void endJar() {
			completedJars++;
			currentJarClasses = 0;
			currentJarCompleted = 0;
		}

		private void addFailure(@Nonnull BatchDecompileFailure failure) {
			synchronized (failures) {
				failures.add(failure);
			}
		}

		/**
		 * Hashes are only read back from the run report, so a run that writes no report does not pay for
		 * digesting every resource it copies.
		 */
		private void recordResourceHash(@Nonnull String outputPath, @Nonnull byte[] content) {
			if (!hashResources)
				return;
			resourceSha256.put(outputPath, sha256(content));
		}

		@Nonnull
		private BatchDecompileProgress snapshot() {
			double fraction;
			if (totalJars <= 0) {
				fraction = 1;
			} else {
				double jarFraction = currentJarClasses <= 0 ? 0 : (double) currentJarCompleted / currentJarClasses;
				fraction = Math.min(1, (completedJars + jarFraction) / totalJars);
			}
			return new BatchDecompileProgress(totalJars, completedJars, totalClasses, completedClasses,
					okClasses, skippedClasses, failedClasses, currentJar, currentClass, fraction);
		}

		@Nonnull
		private BatchDecompileReport toReport(@Nonnull Instant startedAt, @Nonnull Instant endedAt) {
			List<BatchDecompileFailure> copiedFailures;
			synchronized (failures) {
				copiedFailures = List.copyOf(failures);
			}
			return new BatchDecompileReport(startedAt, endedAt, totalJars, okJars, skippedJars, failedJars,
					totalClasses, okClasses, skippedClasses, failedClasses, outputFileCount,
					Map.copyOf(resourceSha256), copiedFailures);
		}

		@Nonnull
		private static String sha256(@Nonnull byte[] content) {
			try {
				byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
				char[] hex = new char[digest.length * 2];
				for (int i = 0; i < digest.length; i++) {
					int value = digest[i] & 0xFF;
					hex[i * 2] = HEX[value >>> 4];
					hex[i * 2 + 1] = HEX[value & 0x0F];
				}
				return new String(hex);
			} catch (NoSuchAlgorithmException ex) {
				throw new IllegalStateException("SHA-256 is required by the Java platform", ex);
			}
		}
	}
}
