package software.coley.recaf.services.decompile;


import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.util.Textifier;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.decompile.fallback.FallbackDecompiler;
import software.coley.recaf.services.decompile.fallback.print.ClassPrinter;
import software.coley.recaf.services.decompile.fallback.print.MethodPrinter;
import software.coley.recaf.services.text.TextFormatConfig;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.AccessibleFields;
import software.coley.recaf.test.dummy.ClassWithAnnotation;
import software.coley.recaf.test.dummy.ClassWithConstructor;
import software.coley.recaf.test.dummy.ClassWithExceptions;
import software.coley.recaf.test.dummy.ClassWithMultipleMethods;
import software.coley.recaf.test.dummy.ClassWithStaticInit;
import software.coley.recaf.test.dummy.DummyEmptyMap;
import software.coley.recaf.test.dummy.DummyEnum;
import software.coley.recaf.test.dummy.HelloWorld;
import software.coley.recaf.test.dummy.InvisAnnotationImpl;
import software.coley.recaf.test.dummy.StringConsumer;
import software.coley.recaf.test.dummy.VariedModifierMethods;
import software.coley.recaf.util.AccessFlag;
import software.coley.recaf.util.ClasspathUtil;
import software.coley.recaf.util.StringUtil;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FallbackDecompiler}
 */
class FallbackDecompilerTest {
	static TextFormatConfig textConfig = new TextFormatConfig();

	public static Stream<String> penis() {
		return ClasspathUtil.getSystemClassSet().stream();
	}

	@Test
	void fieldModifiers() {
		String decompile = decompile(AccessibleFields.class);
		assertTrue(decompile.contains("public static final int CONSTANT_FIELD = 16;"));
		assertTrue(decompile.contains("private final int privateFinalField = 8;"));
		assertTrue(decompile.contains("protected final int protectedField = 4;"));
		assertTrue(decompile.contains("public final int publicField = 2;"));
		assertTrue(decompile.contains("final int packageField = 1;"));
	}

	@Test
	void classAnnotation() {
		String decompile = decompile(ClassWithAnnotation.class);
		assertTrue(decompile.contains("@AnnotationImpl(value = \"Hello\", policy = @Retention(RetentionPolicy.CLASS))"));
	}

	@Test
	void annotationClass() {
		String decompile = decompile(InvisAnnotationImpl.class);
		assertTrue(decompile.contains("""
				@Retention(RetentionPolicy.CLASS)
				@Target({ ElementType.TYPE, ElementType.FIELD, ElementType.METHOD })
				public @interface InvisAnnotationImpl
				"""));
	}

	@Test
	void throwsException() {
		String decompile = decompile(ClassWithExceptions.class);
		assertTrue(decompile.contains("static int readInt(Object input) throws NumberFormatException"));
	}

	@Test
	void clinit() {
		String decompile = decompile(ClassWithStaticInit.class);
		assertTrue(decompile.contains("\n    static {\n"));
	}

	@Test
	void enumFields() {
		String decompile = decompile(DummyEnum.class);
		assertTrue(decompile.contains(" ONE,"));
		assertTrue(decompile.contains(" TWO,"));
		assertTrue(decompile.contains(" THREE;"));
		assertTrue(decompile.contains("private static final /* synthetic */ DummyEnum[] $VALUES;"));
	}


	@Test
	@Disabled("Need to implement signature parsing in the fallback decompiler")
	void genericClassArgs() {
		String decompile = decompile(DummyEmptyMap.class);
		assertTrue(decompile.contains("class DummyEmptyMap<K, V> implements Map<K, V> {"));
	}

	/**
	 * The printers used to scan the whole class once per method that needed a body.
	 * They now share a single scan, which must yield the exact same text.
	 */
	@ParameterizedTest
	@ValueSource(classes = {
			AccessibleFields.class,          // Fields only
			ClassWithStaticInit.class,       // Static initializer
			ClassWithConstructor.class,      // Constructor
			ClassWithMultipleMethods.class,  // Multiple regular methods
			ClassWithExceptions.class,       // Methods with 'throws'
			VariedModifierMethods.class,     // Abstract and native methods mixed with regular ones
			DummyEnum.class,                 // Enum with a static initializer and synthetic members
			StringConsumer.class,            // Interface
			HelloWorld.class
	})
	void sharedScanMatchesPerMethodScan(Class<?> cls) {
		JvmClassInfo classInfo = assertDoesNotThrow(() -> {
			BasicJvmClassBundle bundle = TestClassUtils.fromClasses(cls);
			return bundle.get(cls.getName().replace('.', '/'));
		});
		Map<String, Textifier> shared = MethodPrinter.textifyMethodBodies(classInfo);

		boolean checkedAnyBody = false;
		for (MethodMember method : classInfo.getMethods()) {
			String legacy = new MethodPrinter(textConfig, classInfo, method).print();
			String reused = new MethodPrinter(textConfig, classInfo, method, shared).print();

			// Compare line by line first so a failure points at the offending line rather than dumping
			// the whole method body.
			String[] legacyLines = StringUtil.splitNewline(legacy);
			String[] reusedLines = StringUtil.splitNewline(reused);
			String label = classInfo.getName() + "." + method.getName() + method.getDescriptor();
			assertEquals(legacyLines.length, reusedLines.length, "Line count changed for " + label);
			for (int i = 0; i < legacyLines.length; i++)
				assertEquals(legacyLines[i], reusedLines[i], "Line " + (i + 1) + " changed for " + label);

			// And then the raw text, so that whitespace at the very end is covered too.
			assertEquals(legacy, reused, "Output changed for " + label);

			boolean hasBody = !AccessFlag.isAbstract(method.getAccess()) && !AccessFlag.isNative(method.getAccess());
			if (hasBody) {
				checkedAnyBody = true;
				assertTrue(shared.containsKey(MethodPrinter.bodyKey(method.getName(), method.getDescriptor())),
						"Shared scan is missing a body for " + label);
			} else {
				assertFalse(shared.containsKey(MethodPrinter.bodyKey(method.getName(), method.getDescriptor())),
						"Shared scan holds a body for the body-less method " + label);
			}
		}
		assertTrue(checkedAnyBody, "Expected at least one method with a body in " + classInfo.getName());
	}

	@Nonnull
	private static String decompile(@Nonnull Class<?> cls) {
		return assertDoesNotThrow(() -> {
			BasicJvmClassBundle bundle = TestClassUtils.fromClasses(cls);
			return new ClassPrinter(textConfig, bundle.get(cls.getName().replace('.', '/'))).print();
		});
	}
}