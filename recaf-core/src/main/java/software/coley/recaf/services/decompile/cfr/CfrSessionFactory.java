package software.coley.recaf.services.decompile.cfr;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredComment;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.batch.BatchAccuracyMode;
import software.coley.recaf.services.decompile.batch.session.BatchDecompileSession;
import software.coley.recaf.services.decompile.batch.session.BatchDecompileSessionFactory;
import software.coley.recaf.services.decompile.batch.session.SessionClassResult;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * {@link BatchDecompileSessionFactory} for CFR.
 * <h2>Sharing granularity</h2>
 * A session shares one {@link ClassSource} <i>(backed by the workspace type index)</i> and one immutable
 * options map across all chunks. In the fast modes a whole chunk goes through a single
 * {@link CfrDriver#analyse(List)} call, so CFR builds one {@code DCCommonState} per chunk instead of one per
 * class, and the per-class text is dispatched by class name through {@link SinkReturns.Decompiled}.
 * <h2>Accuracy</h2>
 * {@link BatchAccuracyMode#ACCURATE} sessions mirror {@link CfrDecompiler#decompileInternal} exactly: one
 * driver and one single-class {@code analyse} call per class, so output is byte-identical with the
 * single-class path. Both paths share {@link CfrDecompiler#stripHeader(String)}.
 * <h2>Interruption</h2>
 * The per-class sink callbacks check the interrupt flag, aborting the analyse call at the next class
 * boundary; the session loop translates the flag into an {@link InterruptedException}.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class CfrSessionFactory implements BatchDecompileSessionFactory {
	private final CfrConfig config;

	/**
	 * @param config
	 * 		Config shared with {@link CfrDecompiler}.
	 */
	@Inject
	public CfrSessionFactory(@Nonnull CfrConfig config) {
		this.config = config;
	}

	@Nonnull
	@Override
	public String decompilerName() {
		return CfrDecompiler.NAME;
	}

	@Nonnull
	@Override
	public BatchDecompileSession open(@Nonnull Workspace workspace, @Nonnull BatchAccuracyMode mode) {
		if (mode == BatchAccuracyMode.ACCURATE)
			return new AccurateSession(workspace, config.toMap());
		return new ChunkSession(workspace, config.toMap());
	}

	/**
	 * One driver and one single-class analyse call per class, byte-identical with {@link CfrDecompiler}.
	 */
	private static class AccurateSession implements BatchDecompileSession {
		private final Workspace workspace;
		private final Map<String, String> options;

		private AccurateSession(@Nonnull Workspace workspace, @Nonnull Map<String, String> options) {
			this.workspace = workspace;
			this.options = options;
		}

		@Nonnull
		@Override
		public Map<String, SessionClassResult> decompile(@Nonnull List<JvmClassInfo> classes) throws InterruptedException {
			Map<String, SessionClassResult> results = new LinkedHashMap<>(classes.size());
			for (JvmClassInfo info : classes) {
				if (Thread.interrupted())
					throw new InterruptedException("CFR session interrupted");
				results.put(info.getName(), decompileOne(info));
			}
			return results;
		}

		@Nonnull
		private SessionClassResult decompileOne(@Nonnull JvmClassInfo info) {
			// Mirrors 'CfrDecompiler#decompileInternal' step for step. Any deviation here shows up
			// as a byte-level diff in the ACCURATE equivalence gate.
			String name = info.getName();
			ClassSource source = new ClassSource(workspace, name, info.getBytecode());
			SinkFactoryImpl sink = new SinkFactoryImpl();
			CfrDriver driver = new CfrDriver.Builder()
					.withClassFileSource(source)
					.withOutputSink(sink)
					.withOptions(options)
					.build();
			try {
				driver.analyse(List.of(name));
			} catch (Throwable t) {
				return SessionClassResult.failed(t);
			}
			String decompile = sink.getDecompilation();
			if (decompile == null) {
				Throwable exception = sink.getException();
				if (exception == null) {
					exception = new IllegalStateException("CFR did not provide any output:" +
							"\n- No decompilation output\n- No error message / trace");
					exception.setStackTrace(new StackTraceElement[0]);
				}
				return SessionClassResult.failed(exception);
			}
			return SessionClassResult.ok(CfrDecompiler.stripHeader(decompile));
		}

		@Override
		public void close() {
			releaseCfrLeak();
		}
	}

	/**
	 * One analyse call <i>(one {@code DCCommonState})</i> per chunk over a session-shared class source.
	 */
	private static class ChunkSession implements BatchDecompileSession {
		private final ClassSource source;
		private final Map<String, String> options;

		private ChunkSession(@Nonnull Workspace workspace, @Nonnull Map<String, String> options) {
			this.source = new ClassSource(workspace);
			this.options = options;
		}

		@Nonnull
		@Override
		public Map<String, SessionClassResult> decompile(@Nonnull List<JvmClassInfo> classes) throws InterruptedException {
			if (Thread.interrupted())
				throw new InterruptedException("CFR session interrupted");
			if (classes.isEmpty())
				return new LinkedHashMap<>();

			// The chunk's own bytecode overrides the index for the duration of the analyse call, mirroring
			// how the single-class path hands CFR the exact bytes it was asked to decompile.
			List<String> names = new ArrayList<>(classes.size());
			Map<String, byte[]> targets = new LinkedHashMap<>(classes.size());
			for (JvmClassInfo info : classes) {
				names.add(info.getName());
				targets.putIfAbsent(info.getName(), info.getBytecode());
			}
			source.retarget(targets);

			DispatchingSinkFactory sink = new DispatchingSinkFactory(targets.keySet());
			CfrDriver driver = new CfrDriver.Builder()
					.withClassFileSource(source)
					.withOutputSink(sink)
					.withOptions(options)
					.build();
			Throwable chunkFailure = null;
			try {
				driver.analyse(names);
			} catch (Throwable t) {
				chunkFailure = t;
			}

			if (Thread.interrupted())
				throw new InterruptedException("CFR session interrupted");

			Map<String, SessionClassResult> results = new LinkedHashMap<>(classes.size());
			for (JvmClassInfo info : classes) {
				String name = info.getName();
				String text = sink.decompilations.get(name);
				if (text != null) {
					results.put(name, SessionClassResult.ok(CfrDecompiler.stripHeader(text)));
					continue;
				}
				Throwable failure = sink.failures.get(name);
				if (failure == null)
					failure = chunkFailure != null ? chunkFailure
							: new IllegalStateException("CFR did not provide any output for " + name);
				results.put(name, SessionClassResult.failed(failure));
			}
			return results;
		}

		@Override
		public void close() {
			releaseCfrLeak();
		}
	}

	/**
	 * Same leak guard as the workspace close listener in {@link CfrDecompiler}: CFR assigns a container to
	 * this constant, and it holds a reference to our {@link ClassSource} with the workspace data in it.
	 */
	private static void releaseCfrLeak() {
		StructuredComment.EMPTY_COMMENT.setContainer(null);
	}

	/**
	 * Sink collecting per-class output of one analyse call, keyed by internal class name.
	 */
	private static class DispatchingSinkFactory implements OutputSinkFactory {
		private final Map<String, String> decompilations = new LinkedHashMap<>();
		private final Map<String, Throwable> failures = new LinkedHashMap<>();
		private final Map<String, String> nameAliases;

		/**
		 * @param requested
		 * 		Internal names of the classes the chunk asked for.
		 */
		private DispatchingSinkFactory(@Nonnull Collection<String> requested) {
			// CFR reports the analysed type with dotted package and (depending on the type) '$' or '.'
			// between outer and inner names, so both spellings map back to the internal name.
			this.nameAliases = new HashMap<>(requested.size() * 2);
			for (String name : requested) {
				String dotted = name.replace('/', '.');
				nameAliases.put(dotted, name);
				nameAliases.putIfAbsent(dotted.replace('$', '.'), name);
			}
		}

		@Override
		public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
			// Order matters: CFR walks this list and uses the first entry it can serve, so the per-class
			// 'DECOMPILED' shape has to come before the plain string fallback.
			return switch (sinkType) {
				case JAVA -> List.of(SinkClass.DECOMPILED, SinkClass.STRING);
				case EXCEPTION -> List.of(SinkClass.EXCEPTION_MESSAGE, SinkClass.STRING);
				default -> List.of(SinkClass.STRING);
			};
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
			if (sinkType == SinkType.JAVA && sinkClass == SinkClass.DECOMPILED)
				return t -> acceptDecompiled((SinkReturns.Decompiled) t);
			if (sinkType == SinkType.EXCEPTION && sinkClass == SinkClass.EXCEPTION_MESSAGE)
				return t -> acceptException((SinkReturns.ExceptionMessage) t);
			return t -> {
			};
		}

		private void acceptDecompiled(@Nonnull SinkReturns.Decompiled decompiled) {
			// This callback runs once per analysed class on the analyse thread, making it the per-class
			// boundary of the hot loop. The interrupt flag stays set for the session loop to consume.
			if (Thread.currentThread().isInterrupted())
				throw new CancellationException("Decompilation interrupted");

			String packageName = decompiled.getPackageName();
			String reported = packageName == null || packageName.isEmpty()
					? decompiled.getClassName()
					: packageName + '.' + decompiled.getClassName();
			String name = resolve(reported);
			if (name != null)
				decompilations.putIfAbsent(name, decompiled.getJava());
		}

		private void acceptException(@Nonnull SinkReturns.ExceptionMessage message) {
			if (Thread.currentThread().isInterrupted())
				throw new CancellationException("Decompilation interrupted");

			String path = message.getPath();
			if (path != null) {
				String name = path.endsWith(".class") ? path.substring(0, path.length() - ".class".length()) : path;
				Throwable cause = message.getThrownException();
				failures.putIfAbsent(name, cause != null ? cause : new IllegalStateException(message.getMessage()));
			}
		}

		@Nullable
		private String resolve(@Nonnull String reportedName) {
			String name = nameAliases.get(reportedName);
			if (name != null)
				return name;
			return nameAliases.get(reportedName.replace('$', '.'));
		}
	}
}
