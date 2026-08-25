package software.coley.recaf.services.decompile.fallback;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.decompile.batch.BatchAccuracyMode;
import software.coley.recaf.services.decompile.batch.session.AbstractBatchDecompileSession;
import software.coley.recaf.services.decompile.batch.session.BatchDecompileSession;
import software.coley.recaf.services.decompile.batch.session.BatchDecompileSessionFactory;
import software.coley.recaf.services.decompile.batch.session.SessionClassResult;
import software.coley.recaf.services.decompile.fallback.print.ClassPrinter;
import software.coley.recaf.services.decompile.fallback.print.TypeNameCache;
import software.coley.recaf.services.text.TextFormatConfig;
import software.coley.recaf.workspace.model.Workspace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link BatchDecompileSessionFactory} for the fallback decompiler.
 * <h2>Sharing granularity</h2>
 * The fallback printer has no decompiler context to share; the only state worth keeping per session is the
 * {@link TypeNameCache descriptor display-name cache}, which memoizes the descriptor parsing and filtering
 * the printers repeat for every field and method. The cache is a pure memoization, so every mode produces
 * byte-identical output to {@link FallbackDecompiler}.
 * <h2>Interruption</h2>
 * The interrupt flag is checked at every class boundary.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class FallbackSessionFactory implements BatchDecompileSessionFactory {
	private final TextFormatConfig formatConfig;

	/**
	 * @param formatConfig
	 * 		Format config shared with {@link FallbackDecompiler}.
	 */
	@Inject
	public FallbackSessionFactory(@Nonnull TextFormatConfig formatConfig) {
		this.formatConfig = formatConfig;
	}

	@Override
	public boolean supports(@Nonnull JvmDecompiler decompiler) {
		return FallbackDecompiler.NAME.equals(decompiler.getName());
	}

	@Nullable
	@Override
	public BatchDecompileSession open(@Nonnull Workspace workspace, @Nonnull BatchAccuracyMode mode) {
		// The printer is deterministic per class, so the accurate and fast modes share one implementation.
		return new FallbackSession();
	}

	/**
	 * Prints one class at a time, sharing the descriptor cache across the whole session.
	 */
	private class FallbackSession extends AbstractBatchDecompileSession {
		private final TypeNameCache typeNames = new TypeNameCache(formatConfig);

		private FallbackSession() {
			super(false);
		}

		@Nonnull
		@Override
		protected Map<String, SessionClassResult> decompileClasses(@Nonnull List<JvmClassInfo> classes)
				throws InterruptedException {
			Map<String, SessionClassResult> results = new LinkedHashMap<>(classes.size());
			for (JvmClassInfo info : classes) {
				if (Thread.interrupted())
					throw new InterruptedException("Fallback session interrupted");
				try {
					results.put(info.getName(), SessionClassResult.ok(
							new ClassPrinter(formatConfig, info, typeNames).print()));
				} catch (Throwable t) {
					results.put(info.getName(), SessionClassResult.failed(t));
				}
			}
			return results;
		}
	}
}
