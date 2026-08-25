package software.coley.recaf.services.decompile.batch.zip;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.lljzip.ZipIO;
import software.coley.lljzip.format.model.LocalFileHeader;
import software.coley.lljzip.format.model.ZipArchive;
import software.coley.recaf.util.io.LocalFileHeaderSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RawZipWriter} and the {@link ParallelDeflatePool} feeding it.
 * <p>
 * The writer hand-assembles the archive format instead of going through {@code ZipOutputStream}, so what
 * matters is that everything that reads a ZIP still reads it: the JDK reader, the reader Recaf itself
 * imports workspaces with, and the checksums both of them verify against.
 */
class RawZipWriterTest {
	@TempDir
	Path workDir;

	@Test
	void archiveIsReadableByTheJdkReaderWithMatchingChecksums() throws IOException {
		Map<String, byte[]> entries = sampleEntries();
		Path archive = write(workDir.resolve("out.zip"), entries);

		List<String> names = new ArrayList<>();
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			Enumeration<? extends ZipEntry> enumeration = zip.entries();
			while (enumeration.hasMoreElements()) {
				ZipEntry entry = enumeration.nextElement();
				names.add(entry.getName());

				byte[] expected = entries.get(entry.getName());
				assertNotNull(expected, "Archive holds an entry that was never written: " + entry.getName());
				assertEquals(crc32(expected), entry.getCrc(), "Checksum mismatch for " + entry.getName());
				assertEquals(expected.length, entry.getSize(), "Size mismatch for " + entry.getName());

				// Reading through ZipFile validates the checksum against the stored one for us.
				try (InputStream in = zip.getInputStream(entry)) {
					assertArrayEquals(expected, in.readAllBytes(), "Content mismatch for " + entry.getName());
				}
			}
		}
		assertEquals(List.copyOf(entries.keySet()), names, "Entries are not in append order");
	}

	@Test
	void archiveIsReadableByTheReaderWorkspacesAreImportedWith() throws IOException {
		Map<String, byte[]> entries = sampleEntries();
		Path archive = write(workDir.resolve("out.zip"), entries);

		Map<String, byte[]> read = new LinkedHashMap<>();
		try (ZipArchive zip = ZipIO.readJvm(Files.readAllBytes(archive))) {
			for (LocalFileHeader header : zip.getLocalFiles())
				read.put(header.getFileNameAsString(), new LocalFileHeaderSource(header).readAll());
		}

		assertEquals(List.copyOf(entries.keySet()), List.copyOf(read.keySet()));
		for (Map.Entry<String, byte[]> entry : entries.entrySet())
			assertArrayEquals(entry.getValue(), read.get(entry.getKey()), "Content mismatch for " + entry.getKey());
	}

	@Test
	void entryNamesAndContentMatchZipOutputStream() throws IOException {
		Map<String, byte[]> entries = sampleEntries();
		Path raw = write(workDir.resolve("raw.zip"), entries);
		Path reference = workDir.resolve("reference.zip");
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(reference), StandardCharsets.UTF_8)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				zos.putNextEntry(new ZipEntry(entry.getKey()));
				zos.write(entry.getValue());
				zos.closeEntry();
			}
		}

		Map<String, byte[]> expected = readEntries(reference);
		Map<String, byte[]> actual = readEntries(raw);
		assertEquals(List.copyOf(expected.keySet()), List.copyOf(actual.keySet()),
				"Raw writer entry names or order differ from ZipOutputStream");
		expected.forEach((name, content) -> assertArrayEquals(content, actual.get(name),
				"Raw writer content differs from ZipOutputStream for " + name));
	}

	@Test
	void concurrentProducersKeepAppendOrder() throws Exception {
		// Compression happens on the producing thread, so several threads can be inside the pool at once
		// while the writer thread appends. Appends themselves are still ordered by the calling code.
		Map<String, byte[]> entries = new LinkedHashMap<>();
		for (int i = 0; i < 200; i++)
			entries.put("pkg/Class" + i + ".java", ("class Class" + i + " { " + "int x; ".repeat(64) + "}")
					.getBytes(StandardCharsets.UTF_8));

		Path archive = workDir.resolve("concurrent.zip");
		try (ParallelDeflatePool pool = ParallelDeflatePool.shared();
		     RawZipWriter writer = new RawZipWriter(archive, 8)) {
			List<Map.Entry<String, byte[]>> ordered = List.copyOf(entries.entrySet());
			CountDownLatch start = new CountDownLatch(1);
			CountDownLatch done = new CountDownLatch(ordered.size());
			// Compress everything up front from several threads, then append in plan order.
			DeflatedBlob[] blobs = new DeflatedBlob[ordered.size()];
			List<Thread> threads = new ArrayList<>();
			for (int t = 0; t < 4; t++) {
				int offset = t;
				Thread thread = new Thread(() -> {
					try {
						start.await();
						for (int i = offset; i < ordered.size(); i += 4) {
							Map.Entry<String, byte[]> entry = ordered.get(i);
							blobs[i] = pool.compress(entry.getKey(), entry.getValue());
							done.countDown();
						}
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
				});
				thread.start();
				threads.add(thread);
			}
			start.countDown();
			assertTrue(done.await(30, TimeUnit.SECONDS), "Compression did not finish");
			for (Thread thread : threads)
				thread.join();
			for (DeflatedBlob blob : blobs)
				writer.append(blob);
		}

		assertEquals(entries.entrySet().stream().map(Map.Entry::getKey).toList(),
				List.copyOf(readEntries(archive).keySet()));
		assertEquals(entries.size(), readEntries(archive).size());
		readEntries(archive).forEach((name, content) -> assertArrayEquals(entries.get(name), content, name));
	}

	@Test
	void incompressibleContentIsStoredRatherThanGrown() throws IOException {
		// Deflating random bytes makes them bigger. Storing them keeps the archive from growing.
		byte[] random = new byte[4096];
		for (int i = 0; i < random.length; i++)
			random[i] = (byte) (i * 31 + (i >> 3));
		byte[] shuffled = random.clone();
		java.util.Random rng = new java.util.Random(1234);
		for (int i = shuffled.length - 1; i > 0; i--) {
			int j = rng.nextInt(i + 1);
			byte tmp = shuffled[i];
			shuffled[i] = shuffled[j];
			shuffled[j] = tmp;
		}

		try (ParallelDeflatePool pool = ParallelDeflatePool.shared()) {
			DeflatedBlob blob = pool.compress("random.bin", shuffled);
			assertTrue(blob.compressedSize() <= shuffled.length, "Entry grew instead of being stored");
			assertEquals(crc32(shuffled), blob.crc());
			assertEquals(shuffled.length, blob.rawSize());
		}

		Path archive = write(workDir.resolve("random.zip"), Map.of("random.bin", shuffled));
		assertArrayEquals(shuffled, readEntries(archive).get("random.bin"));
	}

	@Test
	void emptyArchiveIsStillValid() throws IOException {
		Path archive = workDir.resolve("empty.zip");
		try (RawZipWriter writer = new RawZipWriter(archive, 4)) {
			assertEquals(0, writer.entryCount());
		}
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			assertEquals(0, zip.stream().count());
		}
	}

	@Test
	void emptyEntriesRoundTrip() throws IOException {
		Path archive = write(workDir.resolve("blank.zip"), Map.of("empty.txt", new byte[0]));
		Map<String, byte[]> read = readEntries(archive);
		assertEquals(1, read.size());
		assertArrayEquals(new byte[0], read.get("empty.txt"));
	}

	@Test
	void appendAfterCloseIsRejected() throws IOException {
		Path archive = workDir.resolve("closed.zip");
		RawZipWriter writer = new RawZipWriter(archive, 4);
		try (ParallelDeflatePool pool = ParallelDeflatePool.shared()) {
			writer.append(pool.compress("a.txt", "a".getBytes(StandardCharsets.UTF_8)));
			writer.close();
			DeflatedBlob blob = pool.compress("b.txt", "b".getBytes(StandardCharsets.UTF_8));
			assertThrows(IOException.class, () -> writer.append(blob));
		}
	}

	@Nonnull
	private static Map<String, byte[]> sampleEntries() {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("sample/data/values.json", "{\"example.title\": \"Example\"}".getBytes(StandardCharsets.UTF_8));
		entries.put("sample/com/example/Foo.java", ("package com.example;\n\n"
				+ "public class Foo {\n" + "\tint field;\n".repeat(200) + "}\n").getBytes(StandardCharsets.UTF_8));
		entries.put("sample/com/example/Bar.java", "class Bar {}".getBytes(StandardCharsets.UTF_8));
		entries.put("sample/unicode/\u00e9\u4e2d\u6587.txt", "\u00e9\u4e2d\u6587".getBytes(StandardCharsets.UTF_8));
		return entries;
	}

	@Nonnull
	private static Path write(@Nonnull Path archive, @Nonnull Map<String, byte[]> entries) throws IOException {
		try (ParallelDeflatePool pool = ParallelDeflatePool.shared();
		     RawZipWriter writer = new RawZipWriter(archive, 4)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet())
				writer.append(pool.compress(entry.getKey(), entry.getValue()));
		}
		return archive;
	}

	@Nonnull
	private static Map<String, byte[]> readEntries(@Nonnull Path archive) throws IOException {
		Map<String, byte[]> read = new LinkedHashMap<>();
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				try (InputStream in = zip.getInputStream(entry)) {
					ByteArrayOutputStream out = new ByteArrayOutputStream();
					in.transferTo(out);
					read.put(entry.getName(), out.toByteArray());
				}
			}
		}
		return read;
	}

	private static long crc32(@Nonnull byte[] content) {
		CRC32 crc = new CRC32();
		crc.update(content);
		return crc.getValue();
	}
}
