package software.coley.recaf.services.mapping.format;

import org.junit.jupiter.api.Test;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.Mappings;
import software.coley.recaf.services.mapping.UniqueKeyMappings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests various {@link MappingFileFormat} implementation's ability to parse input texts.
 */
public class MappingImplementationTest {
	@Test
	void testTinyV1() {
		String mappingsText = """
				v1\tintermediary\tnamed
				CLASS\ttest/Greetings\trename/Hello
				FIELD\ttest/Greetings\tLjava/lang/String;\toldField\tnewField
				METHOD\ttest/Greetings\t()V\tsay\tspeak""";
		MappingFileFormat format = new TinyV1Mappings();
		IntermediateMappings mappings = assertDoesNotThrow(() -> format.parse(mappingsText));
		assertInheritMap(mappings);
	}

	@Test
	void testTinyV1WithTwoOutputs() {
		String mappingsText = """
				v1\tintermediary\tobfuscated\tnamed
				CLASS\ttest/Greetings\ta\trename/Hello
				FIELD\ttest/Greetings\tLjava/lang/String;\toldField\tb\tnewField
				METHOD\ttest/Greetings\t()V\tsay\tc\tspeak""";
		MappingFileFormat format = new TinyV1Mappings();
		IntermediateMappings mappings = assertDoesNotThrow(() -> format.parse(mappingsText));
		assertInheritMap(mappings);

		// Extra asserts for the intermediate 'obfuscated' column
		assertEquals("rename/Hello", mappings.getMappedClassName("a"));
		assertEquals("newField", mappings.getMappedFieldName("a", "b", "Ljava/lang/String;"));
		assertEquals("speak", mappings.getMappedMethodName("a", "c", "()V"));
	}

	@Test
	void testTinyV1FabricYarnPseudoMembers() {
		String mappingsText = """
				v1\tintermediary\tnamed
				CLASS\tnet/minecraft/class_4494\tnet/minecraft/GlDebugInfo
				CLASS\tnet/minecraft/class_4494.method_22088\tnet/minecraft/GlDebugInfo.getVendor
				CLASS\tnet/minecraft/class_1017\tnet/minecraft/BlendFuncState
				CLASS\tnet/minecraft/class_1017.field_5045\tnet/minecraft/BlendFuncState.capState
				CLASS\tnet/minecraft/class_1018\tnet/minecraft/CapabilityTracker
				CLASS\tnet/minecraft/class_1018.<init>\tnet/minecraft/CapabilityTracker.(I)V
				CLASS\tnet/minecraft/class_1018.method_4469\tnet/minecraft/CapabilityTracker.disable
				""";
		MappingFileFormat format = new TinyV1Mappings();
		IntermediateMappings mappings = assertDoesNotThrow(() -> format.parse(mappingsText));

		assertEquals("net/minecraft/GlDebugInfo", mappings.getMappedClassName("net/minecraft/class_4494"));
		assertEquals("getVendor", mappings.getMappedMethodName("net/minecraft/class_4494", "method_22088", "()Ljava/lang/String;"));
		assertEquals("disable", mappings.getMappedMethodName("net/minecraft/class_1018", "method_4469", "()V"));
		assertEquals("capState", mappings.getMappedFieldName("net/minecraft/class_1017", "field_5045", "I"));
		assertNull(mappings.getMappedMethodName("net/minecraft/class_1018", "<init>", "(I)V"));
	}

	@Test
	void testTinyV1FabricYarnPseudoMembersWithUniqueKeys() {
		String mappingsText = """
				v1\tintermediary\tnamed
				CLASS\tnet/minecraft/class_4494\tnet/minecraft/GlDebugInfo
				CLASS\tnet/minecraft/class_4494.method_22088\tnet/minecraft/GlDebugInfo.getVendor
				CLASS\tnet/minecraft/class_1017.field_5045\tnet/minecraft/BlendFuncState.capState
				""";
		IntermediateMappings mappings = assertDoesNotThrow(() -> new TinyV1Mappings().parse(mappingsText));
		Mappings uniqueMappings = new UniqueKeyMappings(mappings);

		assertEquals("getVendor", uniqueMappings.getMappedMethodName("some/owner", "method_22088", "()Ljava/lang/String;"));
		assertEquals("capState", uniqueMappings.getMappedFieldName("some/owner", "field_5045", "I"));
	}

	@Test
	void testTinyV2() {
		String mappingsText = """
				tiny\t2\t0\tofficial\tobfuscated\tnamed
				c\ttest/Greetings\ta\trename/Hello
				\tf\tLjava/lang/String;\toldField\tb\tnewField
				\tm\t()V\tsay\tc\tspeak
				""";
		MappingFileFormat format = new TinyV2Mappings();
		IntermediateMappings mappings = assertDoesNotThrow(() -> format.parse(mappingsText));
		assertInheritMap(mappings);
	}

	@Test
	void testParchmentJson() {
		String mappingsText = """
				{
				  "version": "1.1.0",
				  "classes": [
				    {
				      "name": "com/example/Test",
				      "methods": [
				        {
				          "name": "render",
				          "descriptor": "(Ljava/lang/String;I)V",
				          "parameters": [
				            { "index": 1, "name": "text" },
				            { "index": 2, "name": "count" }
				          ]
				        }
				      ]
				    }
				  ]
				}
				""";
		ParchmentMappings format = new ParchmentMappings();
		IntermediateMappings mappings = assertDoesNotThrow(() -> format.parse(mappingsText));

		assertEquals("text", mappings.getMappedVariableName("com/example/Test", "render", "(Ljava/lang/String;I)V", null, null, 1));
		assertEquals("count", mappings.getMappedVariableName("com/example/Test", "render", "(Ljava/lang/String;I)V", null, null, 2));
	}

	@Test
	void testParchmentZipAndCheckedZip() {
		String regularJson = """
				{
				  "version": "1.1.0",
				  "classes": [
				    {
				      "name": "com/example/Test",
				      "methods": [
				        {
				          "name": "<init>",
				          "descriptor": "(I)V",
				          "parameters": [
				            { "index": 1, "name": "source" }
				          ]
				        }
				      ]
				    }
				  ]
				}
				""";
		String checkedJson = regularJson.replace("\"source\"", "\"pSource\"");
		ParchmentMappings format = new ParchmentMappings();

		Path regularZip = assertDoesNotThrow(() -> createParchmentZip("parchment-regular", regularJson));
		Path checkedZip = assertDoesNotThrow(() -> createParchmentZip("parchment-checked", checkedJson));

		IntermediateMappings regularMappings = assertDoesNotThrow(() -> format.parse(regularZip));
		IntermediateMappings checkedMappings = assertDoesNotThrow(() -> format.parse(checkedZip));

		assertEquals("source", regularMappings.getMappedVariableName("com/example/Test", "<init>", "(I)V", null, null, 1));
		assertEquals("pSource", checkedMappings.getMappedVariableName("com/example/Test", "<init>", "(I)V", null, null, 1));
	}

	@Test
	void testSimple() {
		String mappingsText = """
				test/Greetings rename/Hello
				test/Greetings.oldField Ljava/lang/String; newField
				test/Greetings.say()V speak""";
		MappingFileFormat format = new SimpleMappings();
		IntermediateMappings mappings = assertDoesNotThrow(() -> format.parse(mappingsText));
		assertInheritMap(mappings);
	}

	@Test
	void testSrg() {
		String mappingsText = """
				CL: test/Greetings rename/Hello
				FD: test/Greetings/oldField newField
				MD: test/Greetings/say ()V speak""";
		MappingFileFormat format = new SrgMappings();
		IntermediateMappings mappings = assertDoesNotThrow(() -> format.parse(mappingsText));
		assertInheritMap(mappings);
	}

	@Test
	void testSrgWithCleanName() {
		String mappingsText = """
				CL: test/Greetings rename/Hello
				FD: test/Greetings/oldField rename/Hello/newField
				MD: test/Greetings/say ()V rename/Hello/speak""";
		MappingFileFormat format = new SrgMappings();
		IntermediateMappings mappings = assertDoesNotThrow(() -> format.parse(mappingsText));
		assertInheritMap(mappings);
	}

	@Test
	void testXSrg() {
		String mappingsText = """
				CL: test/Greetings rename/Hello
				FD: test/Greetings/oldField Ljava/lang/String; rename/Hello/newField Ljava/lang/String;
				MD: test/Greetings/say ()V rename/Hello/speak""";
		MappingFileFormat format = new SrgMappings();
		IntermediateMappings mappings = assertDoesNotThrow(() -> format.parse(mappingsText));
		assertInheritMap(mappings);
	}

	@Test
	void testTsrg2MinecraftReverse() {
		String mappingsText = """
				tsrg2 left right
				com/mojang/blaze3d/Blaze3D com/mojang/blaze3d/Blaze3D
					<init> ()V <init>
					getTime ()D m_83640_
						static
					timeSource f_83641_
				""";
		MappingFileFormat format = new TsrgMappings();
		IntermediateMappings mappings = assertDoesNotThrow(() -> format.parse(mappingsText));

		assertEquals("getTime", mappings.getMappedMethodName("com/mojang/blaze3d/Blaze3D", "m_83640_", "()D"));
		assertEquals("getTime", mappings.getMappedMethodName("some/other/Owner", "m_83640_", "()D"));
		assertEquals("timeSource", mappings.getMappedFieldName("com/mojang/blaze3d/Blaze3D", "f_83641_", "J"));
		assertEquals("timeSource", mappings.getMappedFieldName("some/other/Owner", "f_83641_", "J"));
	}

	@Test
	void testTsrg2MinecraftReverseWithUniqueKeys() {
		String mappingsText = """
				tsrg2 left right
				com/mojang/blaze3d/Blaze3D com/mojang/blaze3d/Blaze3D
					getTime ()D m_83640_
					timeSource f_83641_
				""";
		IntermediateMappings mappings = assertDoesNotThrow(() -> new TsrgMappings().parse(mappingsText));
		Mappings uniqueMappings = new UniqueKeyMappings(mappings);

		assertEquals("getTime", uniqueMappings.getMappedMethodName("other/Owner", "m_83640_", "()D"));
		assertEquals("timeSource", uniqueMappings.getMappedFieldName("other/Owner", "f_83641_", "J"));
	}

	@Test
	void testSrgPackageMapping() {
		String mappingsText = """
				PK: test rename
				""";
		MappingFileFormat format = new SrgMappings();
		IntermediateMappings mappings = assertDoesNotThrow(() -> format.parse(mappingsText));
		assertEquals("rename/Greetings", mappings.getMappedClassName("test/Greetings"));
	}

	@Test
	void testProguard() {
		String mappingsText = """
				# Backwards format because proguard mappings are intended to be undone, not applied
				rename.Hello -> test.Greetings:
				    java.lang.String newField -> oldField
				    void speak() -> say""";
		MappingFileFormat format = new ProguardMappings();
		IntermediateMappings mappings = assertDoesNotThrow(() -> format.parse(mappingsText));
		assertInheritMap(mappings);
	}

	@Test
	void testEnigma() {
		String mappingsText = """
				COMMENT foo
				CLASS test/Greetings rename/Hello
				\tFIELD oldField newField Ljava/lang/String;
				\tMETHOD say speak ()V
				\tCLASS Inner RenamedInner""";
		String mappingsTextWithTrailingNewline = mappingsText + "\n";

		// The mapped names are optional, so we should be able to parse a sample with no
		// actual target names, and get an empty result.
		String mappingsTextWithNoDestinationNames = """
				CLASS test/Greetings
				\tFIELD oldField Ljava/lang/String;
				\tMETHOD say ()V""";
		MappingFileFormat format = new EnigmaMappings();
		IntermediateMappings mappings = assertDoesNotThrow(() -> format.parse(mappingsTextWithNoDestinationNames));
		assertTrue(mappings.getClasses().isEmpty());
		assertTrue(mappings.getFields().isEmpty());
		assertTrue(mappings.getMethods().isEmpty());

		// The format spec says there should be a trailing newline, but we'll support both cases
		mappings = assertDoesNotThrow(() -> format.parse(mappingsText));
		assertInheritMap(mappings);
		mappings = assertDoesNotThrow(() -> format.parse(mappingsTextWithTrailingNewline));
		assertInheritMap(mappings);
		assertEquals("rename/Hello$RenamedInner", mappings.getMappedClassName("test/Greetings$Inner"));

		// This is an extreme edge case of comments and newlines spread out randomly
		String sampleWithCommentsAndNewlines = """
				COMMENT class comment # ignored
				# also ignored
							\t
				CLASS test/Greetings rename/Hello
				\t# field comment up next
							\t
				# not indented but still ignored
				\tCOMMENT This is a comment on the field
							\t
				\tFIELD oldField newField Ljava/lang/String;
							\t
				\tMETHOD say speak ()V
							\t
				\t\t# There are no args
				\t\tARG noArgsHere""";
		mappings = assertDoesNotThrow(() -> format.parse(sampleWithCommentsAndNewlines));
		assertInheritMap(mappings);
	}

	@Test
	void testJadx() {
		String mappingsText = """
				c test.Greetings = Hello
				f test.Greetings.oldField:Ljava/lang/String; = newField
				m test.Greetings.say()V = speak""";
		MappingFileFormat format = new JadxMappings();
		IntermediateMappings mappings = assertDoesNotThrow(() -> format.parse(mappingsText));

		// Cannot use same 'assertInheritMap(...)' because Jadx format doesn't allow package renaming
		assertEquals("test/Hello", mappings.getMappedClassName("test/Greetings"));
		assertEquals("newField", mappings.getMappedFieldName("test/Greetings", "oldField", "Ljava/lang/String;"));
		assertEquals("speak", mappings.getMappedMethodName("test/Greetings", "say", "()V"));
	}

	/**
	 * @param mappings
	 * 		Mappings to check.
	 */
	private void assertInheritMap(Mappings mappings) {
		assertEquals("rename/Hello", mappings.getMappedClassName("test/Greetings"));
		assertEquals("newField", mappings.getMappedFieldName("test/Greetings", "oldField", "Ljava/lang/String;"));
		assertEquals("speak", mappings.getMappedMethodName("test/Greetings", "say", "()V"));
	}

	private static Path createParchmentZip(String prefix, String json) throws IOException {
		Path path = Files.createTempFile(prefix, ".zip");
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
			zos.putNextEntry(new ZipEntry("parchment.json"));
			zos.write(json.getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();
		}
		path.toFile().deleteOnExit();
		return path;
	}
}
