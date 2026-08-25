package software.coley.recaf.services.decompile.batch.session;

import jakarta.annotation.Nonnull;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.batch.ClassExportTask;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * {@link BatchDecompileSession} that decompiles {@link JvmClassInfo} lists and maps them onto the
 * engine's {@link ClassExportTask} grouping contract.
 *
 * @author Matt Coley
 */
public abstract class AbstractBatchDecompileSession implements BatchDecompileSession {
	private final boolean chunked;

	/**
	 * @param chunked
	 *        {@code true} to split the input into pool-sized chunks,
	 *        {@code false} to emit one group per class.
	 */
	protected AbstractBatchDecompileSession(boolean chunked) {
		this.chunked = chunked;
	}

	@Nonnull
	@Override
	public List<List<ClassExportTask>> partition(@Nonnull List<ClassExportTask> tasks) {
		return chunked ? BatchSessionSupport.chunked(tasks) : BatchSessionSupport.onePerClass(tasks);
	}

	@Nonnull
	@Override
	public CompletableFuture<Map<String, SessionClassResult>> decompile(@Nonnull List<ClassExportTask> group) {
		return BatchSessionSupport.completed(() -> decompileClasses(BatchSessionSupport.infos(group)));
	}

	/**
	 * Decompiles one group of classes on the calling thread.
	 *
	 * @param classes
	 * 		Classes to decompile together.
	 *
	 * @return One result per requested class, keyed by internal name.
	 *
	 * @throws InterruptedException
	 * 		When the calling thread was interrupted. The interrupt is consumed.
	 */
	@Nonnull
	protected abstract Map<String, SessionClassResult> decompileClasses(@Nonnull List<JvmClassInfo> classes)
			throws InterruptedException;
}
