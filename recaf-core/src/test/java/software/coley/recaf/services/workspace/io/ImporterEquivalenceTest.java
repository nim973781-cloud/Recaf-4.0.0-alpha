package software.coley.recaf.services.workspace.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.recaf.info.Info;
import software.coley.recaf.info.JarFileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.properties.builtin.ZipCommentProperty;
import software.coley.recaf.info.properties.builtin.ZipCompressionProperty;
import software.coley.recaf.services.text.TextFormatConfig;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.HelloWorld;
import software.coley.recaf.util.ZipCreationUtils;
import software.coley.recaf.util.io.ByteSources;
import software.coley.recaf.util.io.RawZipEntryData;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ImporterEquivalenceTest {
	private static final String COMMENT = "entry-comment";

	@Test
	void mmapAndMemoryImportsPreserveContentPropertiesAndOrdering(@TempDir Path tempDir) throws Exception {
		String className = HelloWorld.class.getName().replace('.', '/');
		byte[] classBytes = TestClassUtils.fromRuntimeClass(HelloWorld.class).getBytecode();
		byte[] nestedBytes = ZipCreationUtils.builder()
				.add("inside.txt", "inside".getBytes(StandardCharsets.UTF_8), true, COMMENT, -1, -1, -1)
				.bytes();
		byte[] archiveBytes = ZipCreationUtils.builder()
				.add("plain.txt", "plain".getBytes(StandardCharsets.UTF_8), true, COMMENT, -1, -1, -1)
				.add(className + ".class", classBytes)
				.add(JarFileInfo.MULTI_RELEASE_PREFIX + "17/" + className + ".class", classBytes)
				.add("nested.zip", nestedBytes)
				.bytes();

		Path archivePath = tempDir.resolve("input.jar");
		Files.write(archivePath, archiveBytes);
		ResourceImporter importer = new BasicResourceImporter(
				new BasicInfoImporter(new InfoImporterConfig(), new TextFormatConfig(), new BasicClassPatcher()),
				new ResourceImporterConfig());

		WorkspaceResource memory = importer.importResource(ByteSources.wrap(archiveBytes));
		WorkspaceResource mapped = importer.importResource(archivePath);
		assertEquals(memory, mapped, "mmap import changed resource contents or insertion semantics");

		assertEquivalentEntry(memory.getFileBundle().get("plain.txt"), mapped.getFileBundle().get("plain.txt"));
		assertEquivalentEntry(memory.getJvmClassBundle().get(className), mapped.getJvmClassBundle().get(className));
		assertEquivalentEntry(memory.getVersionedJvmClassBundles().get(17).get(className),
				mapped.getVersionedJvmClassBundles().get(17).get(className));

		WorkspaceFileResource memoryNested = memory.getEmbeddedResources().get("nested.zip");
		WorkspaceFileResource mappedNested = mapped.getEmbeddedResources().get("nested.zip");
		assertNotNull(memoryNested);
		assertNotNull(mappedNested);
		assertEquivalentEntry(memoryNested.getFileInfo(), mappedNested.getFileInfo());
		assertEquivalentEntry(memoryNested.getFileBundle().get("inside.txt"),
				mappedNested.getFileBundle().get("inside.txt"));
	}

	private static void assertEquivalentEntry(Info expected, Info actual) {
		assertNotNull(expected);
		assertNotNull(actual);
		assertEquals(ZipCompressionProperty.get(expected), ZipCompressionProperty.get(actual));
		assertEquals(ZipCommentProperty.get(expected), ZipCommentProperty.get(actual));

		RawZipEntryData expectedRaw = expected.getPropertyValueOrNull(RawZipEntryData.KEY);
		RawZipEntryData actualRaw = actual.getPropertyValueOrNull(RawZipEntryData.KEY);
		assertNotNull(expectedRaw, "Missing original compressed entry data");
		assertEquals(expectedRaw, actualRaw);
		assertArrayEquals(expectedRaw.compressedBytes(), actualRaw.compressedBytes());

		if (expected instanceof JvmClassInfo expectedClass && actual instanceof JvmClassInfo actualClass)
			assertArrayEquals(expectedClass.getBytecode(), actualClass.getBytecode());
		else
			assertArrayEquals(expected.asFile().getRawContent(), actual.asFile().getRawContent());
	}
}
