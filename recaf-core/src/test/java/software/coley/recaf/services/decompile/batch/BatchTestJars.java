package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.HelloWorld;
import software.coley.recaf.test.dummy.StringConsumer;
import software.coley.recaf.test.dummy.StringSupplier;
import software.coley.recaf.util.ZipCreationUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the small archives the batch decompile tests run against.
 */
final class BatchTestJars {
	static final String HELLO_WORLD = "software/coley/recaf/test/dummy/HelloWorld";
	static final String STRING_SUPPLIER = "software/coley/recaf/test/dummy/StringSupplier";
	static final String STRING_CONSUMER = "software/coley/recaf/test/dummy/StringConsumer";
	static final String GENERATED_PACKAGE = "software/coley/recaf/test/generated";
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

	/**
	 * Writes an archive holding {@code classCount} generated outer classes, each with a handful of methods
	 * carrying loops, branches, exception handlers and a switch. That is enough decompiler work per class to
	 * make throughput measurable, while staying small enough that generating the archive is negligible.
	 *
	 * @param directory
	 * 		Directory to write into.
	 * @param fileName
	 * 		Archive file name.
	 * @param classCount
	 * 		Number of outer classes to generate.
	 * @param methodsPerClass
	 * 		Number of generated methods per class.
	 *
	 * @return Names of the generated classes, in generation order.
	 *
	 * @throws IOException
	 * 		When the archive cannot be written.
	 */
	@Nonnull
	static List<String> writeGeneratedJar(@Nonnull Path directory, @Nonnull String fileName,
	                                      int classCount, int methodsPerClass) throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		List<String> names = new ArrayList<>(classCount);
		for (int i = 0; i < classCount; i++) {
			String name = GENERATED_PACKAGE + "/pkg" + (i % 8) + "/Generated" + i;
			names.add(name);
			entries.put(name + ".class", generateClass(name, methodsPerClass, i));
		}
		entries.put(RESOURCE_NAME, RESOURCE_CONTENT);
		write(directory, fileName, entries);
		return names;
	}

	/**
	 * Emits one class with {@code methodCount} generated methods. Every method has the same shape but a
	 * different seed constant, so the decompiler cannot short-circuit on identical bodies.
	 */
	@Nonnull
	private static byte[] generateClass(@Nonnull String internalName, int methodCount, int seed) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE, "counter", "I", null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "label", "Ljava/lang/String;", null, null).visitEnd();

		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitLdcInsn(Type.getObjectType(internalName).getClassName());
		init.visitFieldInsn(Opcodes.PUTFIELD, internalName, "label", "Ljava/lang/String;");
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitLdcInsn(seed);
		init.visitFieldInsn(Opcodes.PUTFIELD, internalName, "counter", "I");
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();

		for (int i = 0; i < methodCount; i++)
			generateMethod(cw, internalName, "compute" + i, seed + i + 1);
		generateStringMethod(cw, internalName);

		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * {@code int name(int a, int b)} holding a counting loop with a branch inside it, a divide guarded by an
	 * exception handler, and a switch over the result.
	 */
	private static void generateMethod(@Nonnull ClassWriter cw, @Nonnull String owner,
	                                   @Nonnull String name, int constant) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, "(II)I", null, null);
		mv.visitCode();
		Label tryStart = new Label();
		Label tryEnd = new Label();
		Label handler = new Label();
		mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/ArithmeticException");

		// int r = constant + this.counter;
		mv.visitLdcInsn(constant);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, owner, "counter", "I");
		mv.visitInsn(Opcodes.IADD);
		mv.visitVarInsn(Opcodes.ISTORE, 3);

		// for (int i = 0; i < a; i++)
		mv.visitInsn(Opcodes.ICONST_0);
		mv.visitVarInsn(Opcodes.ISTORE, 4);
		Label loop = new Label();
		Label loopEnd = new Label();
		mv.visitLabel(loop);
		mv.visitVarInsn(Opcodes.ILOAD, 4);
		mv.visitVarInsn(Opcodes.ILOAD, 1);
		mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd);

		// if ((i & 1) == 0) r += i * b; else r -= i;
		Label odd = new Label();
		Label merged = new Label();
		mv.visitVarInsn(Opcodes.ILOAD, 4);
		mv.visitInsn(Opcodes.ICONST_1);
		mv.visitInsn(Opcodes.IAND);
		mv.visitJumpInsn(Opcodes.IFNE, odd);
		mv.visitVarInsn(Opcodes.ILOAD, 3);
		mv.visitVarInsn(Opcodes.ILOAD, 4);
		mv.visitVarInsn(Opcodes.ILOAD, 2);
		mv.visitInsn(Opcodes.IMUL);
		mv.visitInsn(Opcodes.IADD);
		mv.visitVarInsn(Opcodes.ISTORE, 3);
		mv.visitJumpInsn(Opcodes.GOTO, merged);
		mv.visitLabel(odd);
		mv.visitVarInsn(Opcodes.ILOAD, 3);
		mv.visitVarInsn(Opcodes.ILOAD, 4);
		mv.visitInsn(Opcodes.ISUB);
		mv.visitVarInsn(Opcodes.ISTORE, 3);
		mv.visitLabel(merged);

		// if (r > 1000) r %= 97;
		Label noWrap = new Label();
		mv.visitVarInsn(Opcodes.ILOAD, 3);
		mv.visitIntInsn(Opcodes.SIPUSH, 1000);
		mv.visitJumpInsn(Opcodes.IF_ICMPLE, noWrap);
		mv.visitVarInsn(Opcodes.ILOAD, 3);
		mv.visitIntInsn(Opcodes.BIPUSH, 97);
		mv.visitInsn(Opcodes.IREM);
		mv.visitVarInsn(Opcodes.ISTORE, 3);
		mv.visitLabel(noWrap);

		mv.visitIincInsn(4, 1);
		mv.visitJumpInsn(Opcodes.GOTO, loop);
		mv.visitLabel(loopEnd);

		// try { r /= b - constant; } catch (ArithmeticException ex) { r = -1; }
		Label afterCatch = new Label();
		mv.visitLabel(tryStart);
		mv.visitVarInsn(Opcodes.ILOAD, 3);
		mv.visitVarInsn(Opcodes.ILOAD, 2);
		mv.visitLdcInsn(constant);
		mv.visitInsn(Opcodes.ISUB);
		mv.visitInsn(Opcodes.IDIV);
		mv.visitVarInsn(Opcodes.ISTORE, 3);
		mv.visitLabel(tryEnd);
		mv.visitJumpInsn(Opcodes.GOTO, afterCatch);
		mv.visitLabel(handler);
		mv.visitVarInsn(Opcodes.ASTORE, 5);
		mv.visitInsn(Opcodes.ICONST_M1);
		mv.visitVarInsn(Opcodes.ISTORE, 3);
		mv.visitLabel(afterCatch);

		// switch (r & 3)
		Label case0 = new Label();
		Label case1 = new Label();
		Label case2 = new Label();
		Label caseDefault = new Label();
		Label switchEnd = new Label();
		mv.visitVarInsn(Opcodes.ILOAD, 3);
		mv.visitInsn(Opcodes.ICONST_3);
		mv.visitInsn(Opcodes.IAND);
		mv.visitTableSwitchInsn(0, 2, caseDefault, case0, case1, case2);
		int[] cases = {1, 2, 3};
		Label[] caseLabels = {case0, case1, case2};
		for (int i = 0; i < caseLabels.length; i++) {
			mv.visitLabel(caseLabels[i]);
			mv.visitVarInsn(Opcodes.ILOAD, 3);
			mv.visitIntInsn(Opcodes.BIPUSH, cases[i]);
			mv.visitInsn(Opcodes.IADD);
			mv.visitVarInsn(Opcodes.ISTORE, 3);
			mv.visitJumpInsn(Opcodes.GOTO, switchEnd);
		}
		mv.visitLabel(caseDefault);
		mv.visitVarInsn(Opcodes.ILOAD, 3);
		mv.visitIntInsn(Opcodes.BIPUSH, 4);
		mv.visitInsn(Opcodes.IADD);
		mv.visitVarInsn(Opcodes.ISTORE, 3);
		mv.visitLabel(switchEnd);

		mv.visitVarInsn(Opcodes.ILOAD, 3);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	/**
	 * {@code String describe(String text)} building a string in a loop, which exercises the string-builder
	 * and char-handling parts of the decompiler rather than only integer arithmetic.
	 */
	private static void generateStringMethod(@Nonnull ClassWriter cw, @Nonnull String owner) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "describe",
				"(Ljava/lang/String;)Ljava/lang/String;", null, null);
		mv.visitCode();
		mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
		mv.visitInsn(Opcodes.DUP);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, owner, "label", "Ljava/lang/String;");
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
				"(Ljava/lang/String;)V", false);
		mv.visitVarInsn(Opcodes.ASTORE, 2);

		mv.visitInsn(Opcodes.ICONST_0);
		mv.visitVarInsn(Opcodes.ISTORE, 3);
		Label loop = new Label();
		Label loopEnd = new Label();
		mv.visitLabel(loop);
		mv.visitVarInsn(Opcodes.ILOAD, 3);
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
		mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd);

		Label notA = new Label();
		Label merged = new Label();
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitVarInsn(Opcodes.ILOAD, 3);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
		mv.visitVarInsn(Opcodes.ISTORE, 4);
		mv.visitVarInsn(Opcodes.ILOAD, 4);
		mv.visitIntInsn(Opcodes.BIPUSH, 'a');
		mv.visitJumpInsn(Opcodes.IF_ICMPNE, notA);
		mv.visitVarInsn(Opcodes.ALOAD, 2);
		mv.visitVarInsn(Opcodes.ILOAD, 3);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
				"(I)Ljava/lang/StringBuilder;", false);
		mv.visitInsn(Opcodes.POP);
		mv.visitJumpInsn(Opcodes.GOTO, merged);
		mv.visitLabel(notA);
		mv.visitVarInsn(Opcodes.ALOAD, 2);
		mv.visitVarInsn(Opcodes.ILOAD, 4);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
				"(C)Ljava/lang/StringBuilder;", false);
		mv.visitInsn(Opcodes.POP);
		mv.visitLabel(merged);

		mv.visitIincInsn(3, 1);
		mv.visitJumpInsn(Opcodes.GOTO, loop);
		mv.visitLabel(loopEnd);

		mv.visitVarInsn(Opcodes.ALOAD, 2);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
				"()Ljava/lang/String;", false);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
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
