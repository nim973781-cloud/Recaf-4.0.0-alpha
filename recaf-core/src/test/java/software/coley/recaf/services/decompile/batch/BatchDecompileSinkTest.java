package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.recaf.test.TestClassUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DirectoryDecompileSink} and {@link StreamingZipDecompileSink}.
 */
class BatchDecompileSinkTest {
	private static final byte[] RESOURCE = "resource-bytes".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path workDir;

	@Test
	void directorySinkWritesFilesAndCleansPerJarRoots() throws IOException {
		Path root = workDir.resolve("out");
		try (DirectoryDecompileSink sink = new DirectoryDecompileSink(root)) {
			assertFalse(sink.requiresOrderedWrites(), "Directory writes are independent");

			JarDecompilePlan plan = JarDecompilePlan.scanned(workDir.resolve("sample.jar"), "sample");
			Path stale = root.resolve("sample").resolve("stale.java");
			Files.createDirectories(stale.getParent());
			Files.writeString(stale, "old");

			sink.beginJar(plan);
			assertFalse(Files.exists(stale), "Stale output from a previous run was not removed");

			sink.writeClass(classTask("sample/com/example/Foo.java"), "class Foo {}");
			sink.writeResource(resourceTask("sample/data/values.json"), RESOURCE);
			sink.writeFailure(classTask("sample/com/example/Bad.java"), "// failed");

			assertEquals("class Foo {}", Files.readString(root.resolve("sample/com/example/Foo.java")));
			assertArrayEquals(RESOURCE, Files.readAllBytes(root.resolve("sample/data/values.json")));
			assertEquals("// failed", Files.readString(root.resolve("sample/com/example/Bad.java")));

			BatchSinkStats stats = sink.stats();
			assertEquals(3, stats.fileCount());
			assertEquals(12 + RESOURCE.length + 9, stats.byteCount());
		}
	}

	@Test
	void directorySinkKeepsWritesInsideItsRoot() throws IOException {
		Path root = workDir.resolve("out");
		try (DirectoryDecompileSink sink = new DirectoryDecompileSink(root)) {
			sink.writeResource(resourceTask("../escaped.txt"), RESOURCE);
			assertFalse(Files.exists(workDir.resolve("escaped.txt")), "Write escaped the sink root");
			assertTrue(Files.isRegularFile(root.resolve("escaped.txt")));
		}
	}

	@Test
	void zipSinkStreamsEntriesInWriteOrder() throws IOException {
		Path archive = workDir.resolve("nested").resolve("out.zip");
		try (StreamingZipDecompileSink sink = new StreamingZipDecompileSink(archive)) {
			assertTrue(sink.requiresOrderedWrites(), "Archive order is part of the sink contract");
			sink.writeResource(resourceTask("sample/data/values.json"), RESOURCE);
			sink.writeClass(classTask("sample/com/example/Foo.java"), "class Foo {}");
			sink.writeFailure(classTask("sample/com/example/Bad.java"), "// failed");
		}

		List<String> names = new ArrayList<>();
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements())
				names.add(entries.nextElement().getName());

			ZipEntry resource = zip.getEntry("sample/data/values.json");
			assertNotNull(resource);
			try (var in = zip.getInputStream(resource)) {
				assertArrayEquals(RESOURCE, in.readAllBytes());
			}
		}
		assertEquals(List.of("sample/data/values.json", "sample/com/example/Foo.java", "sample/com/example/Bad.java"),
				names);
	}

	@Test
	void zipSinkRejectsDuplicateEntries() throws IOException {
		Path archive = workDir.resolve("out.zip");
		BatchSinkStats stats;
		try (StreamingZipDecompileSink sink = new StreamingZipDecompileSink(archive)) {
			sink.writeClass(classTask("sample/com/example/Foo.java"), "class Foo {}");
			sink.writeClass(classTask("sample/com/example/Foo.java"), "class Foo {} // second");
			stats = sink.stats();
		}
		assertEquals(1, stats.fileCount());
		assertEquals(1, stats.duplicateCount());

		try (ZipFile zip = new ZipFile(archive.toFile())) {
			assertEquals(1, zip.stream().count());
		}
	}

	@Test
	void zipSinkRefusesWritesAfterClose() throws IOException {
		Path archive = workDir.resolve("out.zip");
		StreamingZipDecompileSink sink = new StreamingZipDecompileSink(archive);
		sink.writeClass(classTask("sample/com/example/Foo.java"), "class Foo {}");
		sink.close();
		assertThrows(IOException.class, () -> sink.writeClass(classTask("sample/com/example/Bar.java"), "class Bar {}"));
	}

	@Nonnull
	private static ClassExportTask classTask(@Nonnull String outputPath) {
		return new ClassExportTask("sample.jar", "com/example/Foo", outputPath,
				TestClassUtils.createEmptyClass("com/example/Foo"));
	}

	@Nonnull
	private static ResourceExportTask resourceTask(@Nonnull String outputPath) {
		return new ResourceExportTask("sample.jar", "data/values.json", outputPath, () -> RESOURCE);
	}
}
