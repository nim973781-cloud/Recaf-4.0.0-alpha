package software.coley.recaf.services.decompile.fallback.print;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;
import software.coley.recaf.RecafConstants;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.LocalVariable;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.text.TextFormatConfig;
import software.coley.recaf.util.AccessFlag;
import software.coley.recaf.util.StringUtil;
import software.coley.recaf.util.visitors.MemberFilteringVisitor;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utility for printing method bodies.
 *
 * @author Matt Coley
 */
public class MethodPrinter {
	private final TextFormatConfig format;
	private final JvmClassInfo classInfo;
	private final MethodMember method;
	private final Map<String, Textifier> sharedBodies;
	private final TypeNameCache typeNames;

	/**
	 * @param format
	 * 		Format config.
	 * @param classInfo
	 * 		Class containing the method.
	 * @param method
	 * 		Method to print.
	 */
	public MethodPrinter(@Nonnull TextFormatConfig format, @Nonnull JvmClassInfo classInfo, @Nonnull MethodMember method) {
		this(format, classInfo, method, null);
	}

	/**
	 * @param format
	 * 		Format config.
	 * @param classInfo
	 * 		Class containing the method.
	 * @param method
	 * 		Method to print.
	 * @param sharedBodies
	 * 		Pre-computed method body dumps, as created by {@link #textifyMethodBodies(JvmClassInfo)}.
	 * 		When {@code null}, or when it does not hold an entry for the target method, the method body is
	 * 		textified on-demand instead.
	 */
	public MethodPrinter(@Nonnull TextFormatConfig format, @Nonnull JvmClassInfo classInfo, @Nonnull MethodMember method,
	                     @Nullable Map<String, Textifier> sharedBodies) {
		this(format, classInfo, method, sharedBodies, null);
	}

	/**
	 * @param format
	 * 		Format config.
	 * @param classInfo
	 * 		Class containing the method.
	 * @param method
	 * 		Method to print.
	 * @param sharedBodies
	 * 		Pre-computed method body dumps, as created by {@link #textifyMethodBodies(JvmClassInfo)}.
	 * 		When {@code null}, or when it does not hold an entry for the target method, the method body is
	 * 		textified on-demand instead.
	 * @param typeNames
	 * 		Cache of descriptor display-name conversions shared across a batch session,
	 * 		or {@code null} to convert on demand. Caching never changes output.
	 */
	public MethodPrinter(@Nonnull TextFormatConfig format, @Nonnull JvmClassInfo classInfo, @Nonnull MethodMember method,
	                     @Nullable Map<String, Textifier> sharedBodies, @Nullable TypeNameCache typeNames) {
		this.format = format;
		this.classInfo = classInfo;
		this.method = method;
		this.sharedBodies = sharedBodies;
		this.typeNames = typeNames;
	}

	/**
	 * Textifies the code of every method in the given class that has a body, in a single pass over the class.
	 * <p/>
	 * Printing methods one at a time re-reads the whole class per method, which scales quadratically with the
	 * number of methods. The results of this single pass can be handed to
	 * {@link #MethodPrinter(TextFormatConfig, JvmClassInfo, MethodMember, Map)} so each method printer reuses the
	 * dump instead of re-reading the class.
	 *
	 * @param classInfo
	 * 		Class to scan.
	 *
	 * @return Map of {@link #bodyKey(String, String) method keys} to the textified code of that method.
	 * Methods without a body <i>(abstract/native)</i> are not included.
	 */
	@Nonnull
	public static Map<String, Textifier> textifyMethodBodies(@Nonnull JvmClassInfo classInfo) {
		Map<String, Textifier> bodies = new HashMap<>();
		ClassVisitor printVisitor = new ClassVisitor(RecafConstants.getAsmVersion()) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				// Skip methods that will never have their body printed.
				if (AccessFlag.isNative(access) || AccessFlag.isAbstract(access))
					return null;

				// Each method gets its own textifier so that the text of one method does not bleed into the next.
				Textifier textifier = new Textifier();
				bodies.put(bodyKey(name, descriptor), textifier);
				return new TraceMethodVisitor(textifier);
			}
		};
		classInfo.getClassReader().accept(printVisitor, 0);
		return bodies;
	}

	/**
	 * @param name
	 * 		Method name.
	 * @param descriptor
	 * 		Method descriptor.
	 *
	 * @return Key used by {@link #textifyMethodBodies(JvmClassInfo)}.
	 */
	@Nonnull
	public static String bodyKey(@Nonnull String name, @Nonnull String descriptor) {
		return name + descriptor;
	}

	/**
	 * @return Method string representation.
	 */
	@Nonnull
	public String print() {
		StringBuilder sb = new StringBuilder();
		appendAnnotations(sb);
		appendDeclaration(sb);
		if (AccessFlag.isNative(method.getAccess()) || AccessFlag.isAbstract(method.getAccess())) {
			appendAbstractBody(sb);
		} else {
			appendBody(sb);
		}
		return sb.toString();
	}

	/**
	 * Appends annotations on the method declaration to the printer.
	 *
	 * @param sb
	 * 		Builder to add to.
	 */
	protected void appendAnnotations(@Nonnull StringBuilder sb) {
		String annotations = PrintUtils.annotationsToString(format, method);
		if (!annotations.isBlank()) sb.append(annotations).append('\n');
	}

	/**
	 * Appends the method declaration to the printer.
	 * <ol>
	 *     <li>{@link #buildDeclarationFlags(StringBuilder)}</li>
	 *     <li>{@link #buildDeclarationReturnType(StringBuilder)}</li>
	 *     <li>{@link #buildDeclarationName(StringBuilder)}</li>
	 *     <li>{@link #buildDeclarationArgs(StringBuilder)}</li>
	 * </ol>
	 *
	 * @param sb
	 * 		Builder to add to.
	 */
	protected void appendDeclaration(@Nonnull StringBuilder sb) {
		buildDeclarationFlags(sb);
		buildDeclarationReturnType(sb);
		buildDeclarationName(sb);
		buildDeclarationArgs(sb);
		buildDeclarationThrows(sb);
	}

	/**
	 * Appends the following pattern to the builder:
	 * <pre>
	 * public static abstract...
	 * </pre>
	 *
	 * @param sb
	 * 		Builder to add to.
	 */
	protected void buildDeclarationFlags(@Nonnull StringBuilder sb) {
		Collection<AccessFlag> flags = AccessFlag.getApplicableFlags(AccessFlag.Type.METHOD, method.getAccess());
		flags = AccessFlag.sort(AccessFlag.Type.METHOD, flags);
		if (!flags.isEmpty()) {
			sb.append(AccessFlag.toString(flags)).append(' ');
		}
	}

	/**
	 * Appends the following pattern to the builder:
	 * <pre>
	 * ReturnType
	 * </pre>
	 *
	 * @param sb
	 * 		Builder to add to.
	 */
	protected void buildDeclarationReturnType(@Nonnull StringBuilder sb) {
		String returnTypeName;
		if (typeNames != null) {
			returnTypeName = typeNames.getEscapedTypeName(typeNames.getMethodType(method.getDescriptor()).getReturnType());
		} else {
			Type methodType = Type.getMethodType(method.getDescriptor());
			returnTypeName = format.filterEscape(methodType.getReturnType().getClassName());
			if (returnTypeName.contains("."))
				returnTypeName = returnTypeName.substring(returnTypeName.lastIndexOf(".") + 1);
		}
		sb.append(returnTypeName).append(' ');
	}

	/**
	 * Appends the following pattern to the builder:
	 * <pre>
	 * methodName
	 * </pre>
	 *
	 * @param sb
	 * 		Builder to add to.
	 */
	protected void buildDeclarationName(@Nonnull StringBuilder sb) {
		sb.append(format.filter(method.getName()));
	}

	/**
	 * Appends the following pattern to the builder:
	 * <pre>
	 * (Type argName, Type argName)
	 * </pre>
	 *
	 * @param sb
	 * 		Builder to add to.
	 */
	protected void buildDeclarationArgs(@Nonnull StringBuilder sb) {
		sb.append('(');
		boolean isVarargs = AccessFlag.isVarargs(method.getAccess());
		int varIndex = AccessFlag.isStatic(method.getAccess()) ? 0 : 1;
		Type methodType = typeNames != null
				? typeNames.getMethodType(method.getDescriptor())
				: Type.getMethodType(method.getDescriptor());
		Type[] argTypes = methodType.getArgumentTypes();
		for (int param = 0; param < argTypes.length; param++) {
			// Get arg type text
			Type argType = argTypes[param];
			String argTypeName;
			if (typeNames != null) {
				argTypeName = typeNames.getEscapedTypeName(argType);
			} else {
				argTypeName = format.filterEscape(argType.getClassName());
				if (argTypeName.contains("."))
					argTypeName = argTypeName.substring(argTypeName.lastIndexOf(".") + 1);
			}
			boolean isLast = param == argTypes.length - 1;
			if (isVarargs && isLast && argType.getSort() == Type.ARRAY) {
				argTypeName = StringUtil.replaceLast(argTypeName, "[]", "...");
			}

			// Get arg name
			String name = "p" + varIndex;
			LocalVariable variable = method.getLocalVariable(varIndex);
			if (variable != null) {
				name = format.filter(variable.getName());
			}

			// Append to arg list
			sb.append(argTypeName).append(' ').append(name);
			if (!isLast) {
				sb.append(", ");
			}

			// Increment for next var
			varIndex += argType.getSize();
		}
		sb.append(')');
	}

	/**
	 * Appends the following pattern to the builder:
	 * <pre>
	 * throws Item1, Item2, ...
	 * </pre>
	 *
	 * @param sb
	 * 		Builder to add to.
	 */
	protected void buildDeclarationThrows(@Nonnull StringBuilder sb) {
		List<String> thrownTypes = method.getThrownTypes();
		if (thrownTypes.isEmpty())
			return;
		String shortNames = thrownTypes.stream()
				.map(t -> format.filterEscape(StringUtil.shortenPath(t)))
				.collect(Collectors.joining(", "));
		sb.append(" throws ").append(shortNames);
	}

	/**
	 * Appends the abstract method body to the printer.
	 *
	 * @param sb
	 * 		Builder to add to.
	 */
	protected void appendAbstractBody(@Nonnull StringBuilder sb) {
		sb.append(';');
	}

	/**
	 * Appends the method body to the printer.
	 *
	 * @param sb
	 * 		Builder to add to.
	 */
	protected void appendBody(@Nonnull StringBuilder sb) {
		Textifier textifier = sharedBodies == null ? null : sharedBodies.get(bodyKey(method.getName(), method.getDescriptor()));
		if (textifier == null) textifier = textifyMethodBody();

		sb.append(" {\n");
		if (!textifier.getText().isEmpty()) {
			// Pipe ASM's text line model to an output.
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintWriter writer = new PrintWriter(baos);
			textifier.print(writer);
			writer.close();

			// Cleanup the output text.
			String asmDump = baos.toString(StandardCharsets.UTF_8);

			// Indent it just a bit with our printer and append to the string builder.
			Printer codePrinter = new Printer();
			codePrinter.setIndent(" ");
			codePrinter.appendMultiLine(asmDump);

			sb.append("    /*\n");
			sb.append(codePrinter);
			sb.append("    */\n");
		}
		sb.append("    throw new RuntimeException(\"Stub method\");\n");
		sb.append("}\n");
	}

	/**
	 * @return Textified code of only {@link #method the target method}, read directly from {@link #classInfo}.
	 */
	@Nonnull
	private Textifier textifyMethodBody() {
		Textifier textifier = new Textifier();
		ClassVisitor printVisitor = new ClassVisitor(RecafConstants.getAsmVersion()) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				return new TraceMethodVisitor(textifier);
			}
		};
		classInfo.getClassReader().accept(new MemberFilteringVisitor(printVisitor, method), 0);
		return textifier;
	}
}
