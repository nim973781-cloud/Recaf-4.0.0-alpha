package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.HelloWorld;
import software.coley.recaf.test.dummy.StringConsumer;
import software.coley.recaf.test.dummy.StringSupplier;
import software.coley.recaf.util.ZipCreationUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the small archives the batch decompile tests run against.
 */
final class BatchTestJars {
	static final String HELLO_WORLD = "software/coley/recaf/test/dummy/HelloWorld";
	static final String STRING_SUPPLIER = "software/coley/recaf/test/dummy/StringSupplier";
	static final String STRING_CONSUMER = "software/coley/recaf/test/dummy/StringConsumer";
	static final String RESOURCE_NAME = "assets/example/lang/en_us.json";
	static final byte[] RESOURCE_CONTENT = "{\"example.title\": \"Example\"}".getBytes(StandardCharsets.UTF_8);

	private BatchTestJars() {}

	/**
	 * @param directory
	 * 		Directory to write into.
	 * @param fileName
	 * 		Archive file name.
	 *
	 * @return Path of an archive holding three classes and one resource file.
	 *
	 * @throws IOException
	 * 		When the archive cannot be written.
	 */
	@Nonnull
	static Path writeSampleJar(@Nonnull Path directory, @Nonnull String fileName) throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put(HELLO_WORLD + ".class", bytecode(HelloWorld.class));
		entries.put(STRING_SUPPLIER + ".class", bytecode(StringSupplier.class));
		entries.put(STRING_CONSUMER + ".class", bytecode(StringConsumer.class));
		entries.put(RESOURCE_NAME, RESOURCE_CONTENT);
		return write(directory, fileName, entries);
	}

	/**
	 * @param directory
	 * 		Directory to write into.
	 * @param fileName
	 * 		Archive file name.
	 *
	 * @return Path of an archive holding one class, one multi-release class and one embedded archive.
	 *
	 * @throws IOException
	 * 		When the archive cannot be written.
	 */
	@Nonnull
	static Path writeNestedJar(@Nonnull Path directory, @Nonnull String fileName) throws IOException {
		Map<String, byte[]> inner = new LinkedHashMap<>();
		inner.put(STRING_SUPPLIER + ".class", bytecode(StringSupplier.class));
		inner.put(RESOURCE_NAME, RESOURCE_CONTENT);

		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put(HELLO_WORLD + ".class", bytecode(HelloWorld.class));
		entries.put("META-INF/versions/17/" + STRING_CONSUMER + ".class", bytecode(StringConsumer.class));
		entries.put("META-INF/jars/inner.jar", ZipCreationUtils.createZip(inner));
		return write(directory, fileName, entries);
	}

	@Nonnull
	private static Path write(@Nonnull Path directory, @Nonnull String fileName,
	                          @Nonnull Map<String, byte[]> entries) throws IOException {
		Files.createDirectories(directory);
		Path path = directory.resolve(fileName);
		Files.write(path, ZipCreationUtils.createZip(entries));
		return path;
	}

	@Nonnull
	private static byte[] bytecode(@Nonnull Class<?> type) throws IOException {
		return TestClassUtils.fromRuntimeClass(type).getBytecode();
	}
}
