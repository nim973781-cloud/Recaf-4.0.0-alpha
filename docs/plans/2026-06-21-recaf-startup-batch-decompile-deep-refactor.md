# Recaf Startup and Batch Decompile Deep Refactor Plan

**Date:** 2026-06-21  
**Scope:** Recaf 4.0.0-alpha startup speed and batch folder/JAR decompile throughput  
**Skills used:** `qiaomu-goal-meta-skill`, `geju`, `goudi`

## 1. Executive Thesis

Recaf should stop treating batch decompilation as a JavaFX popup feature. Batch decompile should become a high-throughput `recaf-core` module with a small, testable interface; JavaFX, headless mode, and future CLI commands should be adapters over the same engine.

Startup should also stop being a single synchronous initialization block. The current path mixes CDI bootstrap, logging setup, plugin scan, eager services, config load, and JavaFX first window construction. The target model is phased startup: only correctness-critical work blocks first window; plugin scan, old log archive, heavyweight analysis services, and optional tool setup move after first paint or on demand.

The acceptance bar is not "smaller patch." The bar is:

- Accurate: same input, mapping, and decompiler produce no fewer Java outputs, resource bytes stay identical, embedded and multi-release paths are preserved, and every failed class is explicit in a report.
- Fast: startup telemetry proves first-window and interactive-ready improvements; batch telemetry proves higher classes/sec without unbounded memory or silent output loss.

## 2. Current Architecture Findings

### 2.1 Startup chain is synchronized around broad initialization

Source evidence:

- `recaf-ui/src/main/java/software/coley/recaf/Main.java:88` validates JavaFX before UI mode starts.
- `recaf-ui/src/main/java/software/coley/recaf/Main.java:96` validates JDK before bootstrap continues.
- `recaf-ui/src/main/java/software/coley/recaf/Main.java:121-132` runs `initLogging()`, `initPlugins()`, `fireInitEvent()`, `initScale()`, then launches `RecafApplication`.
- `recaf-core/src/main/java/software/coley/recaf/Bootstrap.java:41` creates the CDI container before the `Recaf` instance is usable.
- `recaf-core/src/main/java/software/coley/recaf/Bootstrap.java:63-84` registers packages and calls `weld.initialize()`.

This design makes first paint hostage to work that is not required to draw the first window. `initPlugins()` and `fireInitEvent()` happen before `RecafApplication.launch(...)`, so any plugin or eager-service work increases startup latency even when the user only needs the main shell.

### 2.2 Eager initialization has only two coarse buckets

Source evidence:

- `recaf-core/src/main/java/software/coley/recaf/cdi/EagerInitializationExtension.java:22-24` stores global eager bean lists.
- `recaf-core/src/main/java/software/coley/recaf/cdi/EagerInitializationExtension.java:82-84` creates all immediate eager beans on `InitializationEvent`.
- `recaf-core/src/main/java/software/coley/recaf/cdi/EagerInitializationExtension.java:97-99` creates all UI eager beans on `UiInitializationEvent`.

The existing seam is shallow: a bean can be "immediate" or "after UI init," but it cannot declare whether it is required for first window, required for current workspace load, safe to warm in background, or only needed when a tool opens. This encourages broad startup work and makes tuning brittle.

### 2.3 Plugin scan and log archival can delay first window

Source evidence:

- `recaf-ui/src/main/java/software/coley/recaf/Main.java:186-235` sets up logging and scans old logs via `Files.list(...)`.
- `recaf-ui/src/main/java/software/coley/recaf/Main.java:242-267` scans plugin directories and logs discovered plugins.
- `recaf-core/src/main/java/software/coley/recaf/services/plugin/PluginManagerConfig.java` exposes `scan-on-start`, but current `Main.initPlugins()` does not use the config value to skip startup scanning.

Old log compression and plugin discovery are real work, but they are not required to show the initial JavaFX shell. Plugin scan may be required before a plugin-provided launch handler, but that is a narrower contract than "always before UI launch."

### 2.4 JavaFX first window constructs multiple services before show

Source evidence:

- `recaf-ui/src/main/java/software/coley/recaf/RecafApplication.java:41` initializes navigation early.
- `recaf-ui/src/main/java/software/coley/recaf/RecafApplication.java:44-51` resolves docking, keybinding, window, workspace, menu, and logging pane beans.
- `recaf-ui/src/main/java/software/coley/recaf/RecafApplication.java:78` finally calls `stage.show()`.
- `recaf-ui/src/main/java/software/coley/recaf/RecafApplication.java:84` fires `UiInitializationEvent`.

This is better than doing everything in `Main`, but the first-window path still builds UI pieces that may not be visible immediately. The logging pane is fetched and then not attached to the layout in the current code, which is a concrete example of first-window work that should be rechecked.

### 2.5 Batch decompile is owned by UI

Source evidence:

- `recaf-ui/src/main/java/software/coley/recaf/ui/control/popup/BatchDecompileJarsRunner.java:42` defines a package-private UI runner.
- `recaf-ui/src/main/java/software/coley/recaf/ui/control/popup/BatchDecompileJarsRunner.java:73-143` owns the batch loop, mapping parse, workspace import, mapping apply, export, and callbacks.
- `recaf-ui/src/main/java/software/coley/recaf/ui/control/popup/BatchDecompileJarsPopup.java` creates the runner and adapts callback updates to JavaFX.

This is the wrong module ownership. The UI package currently owns business behavior that should be testable without JavaFX, reusable from headless/CLI, and benchmarked as a core workflow.

### 2.6 Current batch path is sequential at the JAR level

Source evidence:

- `BatchDecompileJarsRunner.java:80` lists JARs with `Files.list(...)`.
- `BatchDecompileJarsRunner.java:93` parses the mapping once.
- `BatchDecompileJarsRunner.java:100-143` loops JARs sequentially.
- `BatchDecompileJarsRunner.java:119` imports each JAR into a workspace.
- `BatchDecompileJarsRunner.java:129` applies mappings to the workspace.
- `BatchDecompileJarsRunner.java:130` exports the JAR.

The runner correctly avoids reparsing the mapping file for each JAR, but it still processes one JAR at a time and fully imports/mutates/export each workspace in sequence. Inside one JAR, class decompilation uses futures, but the outer workflow does not have a clear scheduler, backpressure, cancellation, or cross-JAR plan.

### 2.7 DecompilerManager is single-class first

Source evidence:

- `recaf-core/src/main/java/software/coley/recaf/services/decompile/DecompilerManager.java:55` owns a fixed decompile pool.
- `DecompilerManager.java:117-134` exposes single-class JVM decompile methods.
- `DecompilerManager.java:135-167` wraps each class in `CompletableFuture.supplyAsync(...)`.
- `DecompilerManager.java:140-146` checks and invalidates per-class cached output.
- `DecompilerManager.java:153` applies bytecode filters per class.
- `DecompilerManager.java:156` invokes the decompiler per class.
- `DecompilerManager.java:165` stores the per-class cache result.

This interface is fine for editor tabs but shallow for batch work. A batch engine needs a higher-level interface that can plan class order, isolate thread-unsafe decompilers, reuse immutable mapping/decompiler state, control timeout behavior, and report errors without leaking UI concerns.

### 2.8 Resource import eagerly materializes whole archives

Source evidence:

- `recaf-core/src/main/java/software/coley/recaf/services/workspace/io/BasicResourceImporter.java:176-263` handles ZIP import and builds class/file/embedded resource bundles.
- `BasicResourceImporter.java:187` maps `source.readAll()` into a `ZipArchive`.
- `BasicResourceImporter.java:203-254` iterates local files and classifies entries.
- `BasicResourceImporter.java:436-438` recursively handles embedded ZIPs.
- `BasicResourceImporter.java:275-287` walks directory files and adds them to bundles.

This is accurate and feature-rich, but batch export does not always need the full mutable `WorkspaceResource` model upfront. A fast batch path can use an indexed, lazy resource view for planning and resource-copy operations, while falling back to the full importer where exact Recaf semantics are required.

## 3. Target Architecture

### 3.1 Startup target

Introduce a startup module that makes initialization order explicit and measurable.

Core types:

- `StartupOrchestrator`
- `StartupPhase`
  - `BLOCKING_BEFORE_UI`
  - `FIRST_WINDOW`
  - `BACKGROUND_AFTER_UI`
  - `ON_DEMAND`
- `StartupTask`
- `StartupTelemetry`
- `StartupTaskRegistry`

Target behavior:

- `BLOCKING_BEFORE_UI` contains only argument parse, JDK/JFX validation, minimal directory/config setup, language setup needed by UI labels, and required launch-mode decisions.
- `FIRST_WINDOW` builds the main shell and shows the stage as early as possible.
- `BACKGROUND_AFTER_UI` handles plugin scanning, old log archive, non-critical UI panes, analysis graph warmups, and optional service initialization.
- `ON_DEMAND` is used by heavyweight tools like deobfuscation, phantom generation, call graph, inheritance graph, and script infrastructure unless a launch argument requires them.

The orchestrator must log phase timings on every run:

```text
startup.process-start.ms=...
startup.cdi-created.ms=...
startup.first-window-show.ms=...
startup.interactive-ready.ms=...
startup.background-complete.ms=...
```

The first migration target is not to delete CDI or all eager annotations. The first target is to put a real seam in front of eager initialization:

- Keep `@EagerInitialization` as a compatibility adapter.
- Add `StartupTask` as the new deep interface.
- Move services gradually from eager annotations into explicit startup phases.
- Treat any first-window blocking task as guilty until it names a concrete contract.

### 3.2 Batch decompile target

Move batch decompile to `recaf-core`.

Recommended package:

```text
recaf-core/src/main/java/software/coley/recaf/services/decompile/batch/
```

Core types:

- `BatchDecompileRequest`
- `BatchDecompileEngine`
- `DefaultBatchDecompileEngine`
- `BatchDecompilePlan`
- `BatchDecompileReport`
- `BatchDecompileSink`
- `DirectoryDecompileSink`
- `StreamingZipDecompileSink`
- `BatchDecompileProgressListener`
- `BatchDecompileException`
- `BatchAccuracyMode`
- `BatchDecompileScheduler`
- `BatchMappingPlan`
- `ClassExportTask`
- `ResourceExportTask`
- `BatchFailureStub`

Target behavior:

- UI constructs a `BatchDecompileRequest` and subscribes to progress.
- CLI/headless constructs the same request and writes a machine-readable report.
- Mapping file is parsed once into an immutable `BatchMappingPlan`.
- Input JARs are scanned into a `BatchDecompilePlan`.
- Class decompile work is bounded by decompiler worker count.
- Resource copy/write work is bounded by IO worker count.
- Progress is throttled by time or count, not one FX update per class.
- Directory output writes files directly.
- ZIP output uses deterministic order and a sink-owned writer, not a shared mutable builder touched by arbitrary completion callbacks.

The clean target is:

```text
JavaFX popup  -> BatchDecompileEngine -> Sink -> Report
Headless CLI  -> BatchDecompileEngine -> Sink -> Report
Tests/bench   -> BatchDecompileEngine -> Sink -> Report
```

`BatchDecompileJarsRunner` should be deleted after migration. During transition it can become a thin adapter that delegates to `BatchDecompileEngine`, but it must not continue owning import/mapping/export logic.

## 4. Public Interfaces / Types

These are interface drafts, not final implementation code.

### 4.1 Batch request

```java
package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.nio.file.Path;
import java.time.Duration;

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
	public int normalizedDecompileWorkers() {
		return decompileWorkers <= 0
				? Math.max(2, Runtime.getRuntime().availableProcessors() - 2)
				: decompileWorkers;
	}

	public int normalizedIoWorkers() {
		return ioWorkers <= 0
				? Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2))
				: ioWorkers;
	}
}
```

```java
public enum BatchOutputFormat {
	DIRECTORY,
	ZIP
}
```

```java
public enum BatchAccuracyMode {
	/**
	 * Match the current single-class decompile path first. No fast adapter may be used unless
	 * it proves output equivalence for the configured decompiler.
	 */
	ACCURATE,

	/**
	 * Allow decompiler-specific batch adapters after equivalence checks pass.
	 */
	FAST_VERIFIED,

	/**
	 * Use fastest available adapter and report that output is not acceptance-grade.
	 */
	FAST_UNSAFE
}
```

### 4.2 Batch engine

```java
public interface BatchDecompileEngine {
	@Nonnull
	BatchDecompileReport run(@Nonnull BatchDecompileRequest request,
	                         @Nonnull BatchDecompileProgressListener listener)
			throws BatchDecompileException;
}
```

```java
public interface BatchDecompileProgressListener {
	BatchDecompileProgressListener NOOP = event -> {};

	void onProgress(@Nonnull BatchDecompileProgress event);
}
```

```java
public record BatchDecompileProgress(
		int totalJars,
		int completedJars,
		int totalClasses,
		int completedClasses,
		int okClasses,
		int skippedClasses,
		int failedClasses,
		@Nullable String currentJar,
		@Nullable String currentClass,
		double fraction
) {}
```

### 4.3 Plan and report

```java
public record BatchDecompilePlan(
		@Nonnull List<JarDecompilePlan> jars,
		@Nonnull BatchMappingPlan mappingPlan,
		@Nonnull BatchOutputFormat outputFormat
) {
	public int totalClassCount() {
		return jars.stream().mapToInt(JarDecompilePlan::classCount).sum();
	}
}
```

```java
public record JarDecompilePlan(
		@Nonnull Path inputJar,
		@Nonnull String outputName,
		@Nonnull List<ClassExportTask> classes,
		@Nonnull List<ResourceExportTask> resources
) {
	public int classCount() {
		return classes.size();
	}
}
```

```java
public record BatchDecompileReport(
		@Nonnull Instant startedAt,
		@Nonnull Instant endedAt,
		int totalJars,
		int okJars,
		int skippedJars,
		int failedJars,
		int totalClasses,
		int okClasses,
		int skippedClasses,
		int failedClasses,
		long outputFileCount,
		@Nonnull Map<String, String> resourceSha256,
		@Nonnull List<BatchDecompileFailure> failures
) {
	public Duration duration() {
		return Duration.between(startedAt, endedAt);
	}
}
```

```java
public record BatchDecompileFailure(
		@Nonnull String jarName,
		@Nullable String className,
		@Nonnull String phase,
		@Nonnull String message,
		@Nullable String trace
) {}
```

### 4.4 Sinks

```java
public interface BatchDecompileSink extends AutoCloseable {
	void writeClass(@Nonnull ClassExportTask task, @Nonnull String text) throws IOException;

	void writeResource(@Nonnull ResourceExportTask task, @Nonnull byte[] content) throws IOException;

	void writeFailure(@Nonnull ClassExportTask task, @Nonnull String failureStub) throws IOException;

	@Nonnull
	BatchSinkStats stats();

	@Override
	void close() throws IOException;
}
```

`DirectoryDecompileSink` writes `Path` targets directly with parent directory creation.

`StreamingZipDecompileSink` owns one writer and deterministic entry order. It must not expose a shared `ZipBuilder` to worker threads. Workers should return `CompletedExport` values; the sink serializes them in plan order or uses per-JAR temporary zip fragments and merges deterministically.

### 4.5 DecompilerManager additions

The manager should keep existing single-class methods and add batch-facing adapters:

```java
public interface BatchCapableJvmDecompiler {
	@Nonnull
	String getName();

	boolean supports(@Nonnull BatchAccuracyMode mode);

	@Nonnull
	BatchClassDecompileResult decompile(@Nonnull BatchClassDecompileRequest request);
}
```

```java
public record BatchClassDecompileRequest(
		@Nonnull Workspace workspace,
		@Nonnull JvmClassInfo classInfo,
		@Nonnull JvmDecompiler decompiler,
		@Nonnull BatchAccuracyMode accuracyMode
) {}
```

Default adapter:

- Uses `DecompilerManager.decompile(decompiler, workspace, classInfo)`.
- This is the acceptance-grade path.
- It may be slower, but it defines correctness until decompiler-specific adapters prove equivalence.

Decompiler-specific adapters:

- CFR adapter may reuse source/sink structures only if thread safety is proven.
- Vineflower adapter must be treated cautiously because `Fernflower` context construction and logging behavior may not be safe to share blindly.
- Procyon adapter may reuse immutable type loader/index data only if class resolution output stays equivalent.

### 4.6 UI adapter

`BatchDecompileJarsPopup` should:

- Gather form state.
- Resolve mapping format ID and decompiler name.
- Build `BatchDecompileRequest`.
- Submit to `BatchDecompileEngine` on a background executor.
- Translate throttled `BatchDecompileProgress` events into JavaFX properties.
- Display `BatchDecompileReport` summary.

It should not:

- Parse mapping files.
- Import resources directly.
- Apply mappings directly.
- Decide class export filtering rules.
- Write Java/resource files.

### 4.7 CLI/headless adapter

Add a subcommand to launch args:

```text
recaf batch-decompile \
  --jar-dir <dir> \
  --mapping <file> \
  --mapping-format TSRG \
  --output <dir-or-zip> \
  --output-format directory \
  --decompiler Vineflower \
  --workers auto \
  --io-workers auto \
  --accuracy accurate \
  --include-resources true \
  --report <report.json>
```

CLI should run through the same CDI-managed `BatchDecompileEngine` and exit non-zero if:

- input directory is invalid,
- mapping parse fails,
- configured decompiler is missing,
- output sink cannot write,
- report has failed classes and `--fail-on-class-error true`.

## 5. Accuracy Contract

The acceptance contract is stronger than "it ran faster."

### 5.1 Output equivalence

For the same:

- input JAR folder,
- mapping file,
- mapping format,
- decompiler,
- resource inclusion setting,
- timeout policy,

the new engine must satisfy:

- `.java` output count is not lower than old path.
- Every class that old path exported is either exported by new path or represented by a failure stub with an explicit report entry.
- Resource file byte hashes match old path.
- Embedded JAR resource paths match old path.
- Multi-release paths under `META-INF/versions/<n>/...` match old path.
- Mapping-applied class names and output paths match old path.
- Output path separators are normalized to `/` in archives and platform paths on disk.
- Duplicate class/resource behavior is documented and matched to current importer/exporter semantics.

### 5.2 Failure behavior

Failure must be visible and replayable:

- Failed classes write a deterministic failure stub when `writeFailureStubs=true`.
- Failure stubs include class name, phase, result type if present, and trace/message.
- Report includes per-class and per-JAR failure counts.
- Timeout is counted separately from decompiler exception.
- A failed class must not silently decrement only progress.

### 5.3 Mapping behavior

Mapping must be acceptance-grade:

- Parse mapping once into an immutable `BatchMappingPlan`.
- Do not use UI `mapping acceleration` as the default accurate path.
- If lazy mapping is introduced, compare its output names and bytecode-visible changes against current `MappingApplier` behavior.
- Any mismatch forces fallback to current `MappingApplier` workspace mutation for `ACCURATE`.

### 5.4 Minecraft / Forge compatibility

When writing Minecraft mod resources, `.lang`, config, or generated metadata, preserve encoding behavior. If a mod name or translated text requires Unicode escape conversion, do it explicitly and test the resource output hash or text equivalence.

## 6. Performance Contract

### 6.1 Startup benchmark

Add instrumentation points:

- `process-start -> CDI created`
- `CDI created -> first JavaFX window show`
- `first show -> interactive ready`
- `interactive ready -> background complete`

Run method:

- Use JDK 25 for local measurements.
- Run 10 warm launches after one cold launch.
- Collect median, p90, and max.
- Include JVM args in the report.
- Capture whether plugin scan is enabled.

Acceptance:

- `process-start -> first-window-show` median is at least 30% faster than current baseline.
- `interactive-ready` must not regress.
- Background work must complete without blocking UI interactions.
- Startup log must clearly identify the slowest phase and slowest task.

### 6.2 Batch benchmark

Benchmark inputs:

- One small single-JAR fixture.
- One medium folder of multiple JARs.
- One large Minecraft/Forge-oriented folder with mappings.
- A fixture containing embedded JARs and multi-release classes.

Metrics:

- total wall-clock,
- classes/sec,
- resources/sec,
- failed/skipped/ok class counts,
- failed/skipped/ok JAR counts,
- output file count,
- resource SHA-256 manifest,
- report JSON size,
- peak memory,
- worker counts,
- timeout count.

Acceptance:

- Multi-JAR folder median wall-clock improves by at least 2x.
- Single-JAR path is not slower than old runner by more than 5%.
- Output equivalence contract passes.
- Peak memory does not scale unbounded with total classes.
- FX progress updates do not exceed a configured throttle, default 100ms or every 64 completed classes.

### 6.3 Suggested commands

Build and tests:

```bat
set TARGET_VERSION=25
build_fast.bat :recaf-core:test :recaf-ui:test --tests *BatchDecompile*
```

CLI smoke target after implementation:

```bat
C:\Java\jdk-25\bin\java.exe ^
  --enable-native-access=ALL-UNNAMED ^
  -jar recaf-ui\build\libs\recaf-ui-4.0.0-SNAPSHOT-all.jar ^
  batch-decompile ^
  --jar-dir E:\多个测试\Recaf\samples\jars ^
  --mapping E:\多个测试\Recaf\映射\sample.tsrg ^
  --mapping-format TSRG ^
  --output build\tmp\batch-decompile-out ^
  --output-format directory ^
  --decompiler Vineflower ^
  --accuracy accurate ^
  --report build\tmp\batch-decompile-report.json
```

## 7. Implementation Phases

### Phase 1: Baseline and telemetry only

Modules:

- `recaf-ui/src/main/java/software/coley/recaf/Main.java`
- `recaf-ui/src/main/java/software/coley/recaf/RecafApplication.java`
- new `recaf-core/.../startup/StartupTelemetry.java`
- current `BatchDecompileJarsRunner`

Work:

- Add timestamp capture for startup milestones.
- Add batch runner timing/report logging without behavior change.
- Add a small benchmark harness or test utility that runs current batch path and emits a manifest.

Verification:

- Recaf still launches.
- Existing `BatchDecompileJarsRunnerTest` passes.
- Startup log includes phase timings.
- Batch log includes total classes, failures, and elapsed time.

Rollback/shrink signal:

- If telemetry requires invasive changes, keep it as static utility calls only and defer orchestrator changes.

### Phase 2: Extract core batch interfaces

Modules:

- new `recaf-core/src/main/java/software/coley/recaf/services/decompile/batch/*`
- new `recaf-core/src/test/java/software/coley/recaf/services/decompile/batch/*`

Work:

- Add request, engine, report, progress, sink interfaces.
- Implement a fake/in-memory engine test with no Recaf UI dependency.
- Do not replace UI yet.

Verification:

- Core tests compile and pass.
- Interfaces are CDI-friendly.
- No JavaFX imports in core batch package.

Rollback/shrink signal:

- If core depends on UI types, stop and move those data types down or introduce adapters.

### Phase 3: Move UI runner logic into core engine

Modules:

- `BatchDecompileJarsRunner`
- `BatchDecompileJarsPopup`
- new `DefaultBatchDecompileEngine`

Work:

- Port the existing runner behavior into `DefaultBatchDecompileEngine`.
- Keep the old runner as a delegating adapter temporarily.
- Preserve current output layout exactly.
- Preserve current failure stub behavior.

Verification:

- Existing `BatchDecompileJarsRunnerTest` still passes through adapter.
- New core tests assert resources, embedded resources, multi-release classes, and failure stubs.

Rollback/shrink signal:

- If behavior diverges, freeze new engine behind tests and keep UI runner until diff is understood.

### Phase 4: Add bounded scheduling and sink abstraction

Modules:

- `DefaultBatchDecompileEngine`
- `BatchDecompileScheduler`
- `DirectoryDecompileSink`
- `StreamingZipDecompileSink`

Work:

- Replace ad hoc `CompletableFuture` fan-out with bounded queues.
- Separate class decompile work from IO write work.
- Add deterministic completion aggregation.
- Throttle progress events.

Verification:

- Small fixture output exactly matches phase 3.
- Large fixture shows improved classes/sec.
- No shared mutable `ZipBuilder` is touched by parallel callbacks.

Rollback/shrink signal:

- If decompiler concurrency causes nondeterminism, set per-decompiler worker limit to 1 while keeping cross-JAR planning and sink improvements.

### Phase 5: Add CLI/headless batch command

Modules:

- `recaf-core/src/main/java/software/coley/recaf/launch/LaunchCommand.java`
- `recaf-ui/src/main/java/software/coley/recaf/Main.java`
- new CLI adapter package if needed

Work:

- Add `batch-decompile` subcommand.
- Wire it through CDI to `BatchDecompileEngine`.
- Produce JSON report.
- Exit with meaningful status codes.

Verification:

- CLI runs without JavaFX initialization.
- CLI output equals UI output for same request.
- Invalid paths/mapping/decompiler fail fast with readable errors.

Rollback/shrink signal:

- If picocli subcommand integration gets too invasive, add a hidden/headless option first and preserve the future subcommand design.

### Phase 6: Add lazy resource / indexed workspace path

Modules:

- `BasicResourceImporter`
- new `ResourceIndex`
- new `IndexedWorkspaceView`

Work:

- Build a ZIP/directory index for batch planning.
- Copy resources directly from indexed byte sources where safe.
- Defer full `WorkspaceResource` materialization until decompile/mapping requires it.
- Preserve embedded and multi-release behavior.

Verification:

- Hash manifest matches full importer path.
- Embedded JAR fixture matches current output.
- Memory peak decreases on large folder.

Rollback/shrink signal:

- If lazy index breaks edge cases, keep it behind `FAST_VERIFIED`; use full importer for `ACCURATE`.

### Phase 7: Add batch-capable decompiler adapters

Modules:

- `DecompilerManager`
- `services/decompile/cfr`
- `services/decompile/vineflower`
- `services/decompile/procyon`
- new batch adapter package

Work:

- Add default accurate adapter over current single-class path.
- Add one decompiler-specific fast adapter at a time.
- Require fixture equivalence before enabling each adapter for `FAST_VERIFIED`.

Verification:

- Per-decompiler output diff against accurate adapter.
- Thread-safety stress test with repeated parallel runs.
- Timeout and exception reporting remains deterministic.

Rollback/shrink signal:

- Any nondeterministic output or shared-state exception disables that adapter by default.

### Phase 8: Split startup phases and migrate eager services

Modules:

- `Bootstrap`
- `Main`
- `RecafApplication`
- `EagerInitializationExtension`
- plugin manager/config
- analysis service eager annotations

Work:

- Add `StartupOrchestrator` and `StartupTask`.
- Move plugin scan after first window unless launch mode requires it.
- Move old log archival to background.
- Move analysis services to background/on-demand.
- Make `scan-on-start` actually respected.

Verification:

- First-window telemetry improves.
- Plugin-dependent workflows still work after background completion.
- If plugin scan disabled, app still starts and plugin UI communicates disabled state.

Rollback/shrink signal:

- If plugin lifecycle requires pre-init scanning, preserve plugin scan for plugin-enabled mode but add config to default it off for fast startup.

### Phase 9: Remove old UI-owned runner and redundant startup work

Modules:

- delete or shrink `BatchDecompileJarsRunner`
- simplify `BatchDecompileJarsPopup`
- remove migrated eager annotations
- update docs/tests

Work:

- Remove duplicated batch business logic from UI.
- Keep only request construction and progress display.
- Document CLI usage and benchmark procedure.

Verification:

- No JavaFX imports in core batch logic.
- UI and CLI use same engine.
- Full batch regression suite passes.
- Startup benchmark report is attached to the plan or release notes.

Rollback/shrink signal:

- If deleting adapter breaks tests, keep a deprecated adapter for one release but make it call the core engine only.

## 8. Risks and Falsifiers

### 8.1 Decompiler thread safety

Risk:

- CFR, Vineflower, and Procyon may hold shared internal state, static caches, or output sinks that are unsafe under parallel reuse.

Falsifier:

- Parallel repeated runs produce different text output, missing classes, exceptions, or corrupted reports.

Response:

- Use default accurate adapter.
- Limit that decompiler to worker count 1.
- Re-enable fast adapter only after a stress test proves determinism.

### 8.2 Mapping changes workspace state

Risk:

- Current `MappingApplier` mutates workspace/resource/class bundles. A lazy mapping path may not reproduce side effects such as cache invalidation, inner/outer naming, or reference updates.

Falsifier:

- Output paths or decompiled text differ from current runner for mapped classes.

Response:

- `ACCURATE` uses current workspace mutation.
- Lazy mapping remains experimental until equivalence passes.

### 8.3 ZIP output order and duplicate entries

Risk:

- Current ZIP utilities allow duplicate entries by resetting name tracking. A streaming sink may accidentally change ordering or duplicate behavior.

Falsifier:

- Archive listing, duplicate handling, or extracted hash manifest differs from current output.

Response:

- Use deterministic plan order.
- Add explicit duplicate-entry tests.
- Use a sink implementation that intentionally supports or rejects duplicates according to the documented output contract.

### 8.4 Lazy workspace breaks embedded resources

Risk:

- Embedded ZIP and multi-release class handling in `BasicResourceImporter` is nuanced. A fast index path can miss recursive embedded resources or versioned class placement.

Falsifier:

- Fixture with `META-INF/jars/*.jar` or `META-INF/versions/*` differs from full importer output.

Response:

- Keep full importer for `ACCURATE`.
- Allow lazy path only for `FAST_VERIFIED` after fixture coverage.

### 8.5 Startup phase split hides required dependencies

Risk:

- Some service currently relies on eager side effects before UI or workspace load.

Falsifier:

- First use of a feature fails because a listener, filter, config adapter, or URL handler was not installed.

Response:

- Move only measured tasks.
- Give each startup task an owning contract.
- Promote a task back to `BLOCKING_BEFORE_UI` only when a failing test proves it is required.

### 8.6 UI progress overload

Risk:

- Even if core engine gets faster, per-class JavaFX updates can dominate UI responsiveness.

Falsifier:

- Batch throughput improves in CLI but UI remains sluggish or visibly stalls.

Response:

- Throttle progress events before they reach JavaFX.
- Aggregate class names and counts into periodic UI updates.

## 9. Final Recommendation

Recommend the staged clean path:

1. Measure current startup and batch behavior first.
2. Extract core batch interfaces.
3. Move existing batch behavior into core without changing output.
4. Add bounded scheduling and sink abstraction.
5. Add CLI/headless entrypoint.
6. Introduce lazy/indexed paths and batch decompiler adapters only behind equivalence gates.
7. Split startup into explicit phases and migrate eager work based on telemetry.
8. Delete UI-owned batch business logic after UI and CLI both use the core engine.

Do not choose these paths:

- Do not only modify `BatchDecompileJarsRunner`; that preserves the wrong module ownership.
- Do not only add more threads; that risks decompiler nondeterminism, memory spikes, and JavaFX progress overload.
- Do not preserve duplicate UI/core business logic for compatibility unless it names a real public contract.
- Do not use faster but non-equivalent mapping or decompiler shortcuts in the default accurate mode.

The clean target is worth the refactor cost because it creates one deep module for batch decompilation, one explicit startup scheduler, and one verification contract shared by UI, CLI, tests, and benchmarks.
