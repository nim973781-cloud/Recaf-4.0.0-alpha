package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import software.coley.recaf.util.IOUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sink writing each output file directly onto the file system.
 * <p>
 * Writes are independent, so this sink accepts concurrent calls and does not require ordering.
 * Each JAR gets its own sub-directory, which is emptied on {@link #beginJar(JarDecompilePlan)} so a
 * rerun does not leave stale output behind.
 *
 * @author Matt Coley
 */
public class DirectoryDecompileSink implements BatchDecompileSink {
	private final AtomicLong fileCount = new AtomicLong();
	private final AtomicLong byteCount = new AtomicLong();
	private final Path root;

	/**
	 * @param root
	 * 		Directory to write output into.
	 *
	 * @throws IOException
	 * 		When the directory cannot be created.
	 */
	public DirectoryDecompileSink(@Nonnull Path root) throws IOException {
		this.root = root;
		Files.createDirectories(root);
	}

	/**
	 * @return Directory output is written into.
	 */
	@Nonnull
	public Path root() {
		return root;
	}

	@Override
	public void beginJar(@Nonnull JarDecompilePlan plan) throws IOException {
		Path jarRoot = BatchOutputPath.resolve(root, plan.outputName());
		if (Files.isDirectory(jarRoot))
			IOUtil.cleanDirectory(jarRoot);
		else
			Files.deleteIfExists(jarRoot);
		Files.createDirectories(jarRoot);
	}

	@Override
	public void writeClass(@Nonnull ClassExportTask task, @Nonnull String text) throws IOException {
		write(task.outputPath(), text.getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public void writeResource(@Nonnull ResourceExportTask task, @Nonnull byte[] content) throws IOException {
		write(task.outputPath(), content);
	}

	@Override
	public void writeFailure(@Nonnull ClassExportTask task, @Nonnull String failureStub) throws IOException {
		write(task.outputPath(), failureStub.getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public boolean requiresOrderedWrites() {
		return false;
	}

	@Nonnull
	@Override
	public BatchSinkStats stats() {
		return new BatchSinkStats(fileCount.get(), byteCount.get(), 0);
	}

	@Override
	public void close() {
		// no-op, files are written eagerly
	}

	private void write(@Nonnull String outputPath, @Nonnull byte[] content) throws IOException {
		Path target = BatchOutputPath.resolve(root, outputPath);
		Path parent = target.getParent();
		if (parent != null) {
			try {
				Files.createDirectories(parent);
			} catch (FileAlreadyExistsException ignored) {
				// Concurrent writers can race here. The directory exists either way.
			}
		}
		Files.write(target, content);
		fileCount.incrementAndGet();
		byteCount.addAndGet(content.length);
	}
}
