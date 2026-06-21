package software.coley.recaf.services.deobfuscation.transform.generic;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.Dependent;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A transformer that removes junk/garbage code commonly injected by obfuscators.
 * <p>
 * This includes:
 * <ul>
 *   <li>Useless {@code new Object()} calls where the result is discarded (POP)</li>
 *   <li>Useless {@code Integer.valueOf()}, {@code Long.valueOf()}, etc. where result is discarded</li>
 *   <li>Useless array allocations where result is discarded or only stored to unused local</li>
 *   <li>Dead assertions checking {@code Class.desiredAssertionStatus()}</li>
 * </ul>
 *
 * @author Recaf Contributors
 */
@Dependent
public class JunkCodeRemovingTransformer implements JvmClassTransformer {

	// Classes whose instance creation is typically junk when result is discarded
	private static final Set<String> JUNK_NEW_TYPES = new HashSet<>();
	// Methods whose invocation is typically junk when result is discarded
	private static final Set<String> JUNK_INVOKE_METHODS = new HashSet<>();

	static {
		JUNK_NEW_TYPES.add("java/lang/Object");
		JUNK_NEW_TYPES.add("java/lang/StringBuilder");
		JUNK_NEW_TYPES.add("java/lang/StringBuffer");

		// Boxing methods - result discarded means junk
		JUNK_INVOKE_METHODS.add("java/lang/Integer.valueOf(I)Ljava/lang/Integer;");
		JUNK_INVOKE_METHODS.add("java/lang/Long.valueOf(J)Ljava/lang/Long;");
		JUNK_INVOKE_METHODS.add("java/lang/Short.valueOf(S)Ljava/lang/Short;");
		JUNK_INVOKE_METHODS.add("java/lang/Byte.valueOf(B)Ljava/lang/Byte;");
		JUNK_INVOKE_METHODS.add("java/lang/Character.valueOf(C)Ljava/lang/Character;");
		JUNK_INVOKE_METHODS.add("java/lang/Boolean.valueOf(Z)Ljava/lang/Boolean;");
		JUNK_INVOKE_METHODS.add("java/lang/Float.valueOf(F)Ljava/lang/Float;");
		JUNK_INVOKE_METHODS.add("java/lang/Double.valueOf(D)Ljava/lang/Double;");
	}

	@Override
	public void transform(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                      @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle,
	                      @Nonnull JvmClassInfo initialClassState) throws TransformationException {
		boolean dirty = false;
		ClassNode node = context.getNode(bundle, initialClassState);

		for (MethodNode method : node.methods) {
			if (method.instructions == null || method.instructions.size() == 0)
				continue;

			dirty |= removeJunkCode(method);
		}

		if (dirty)
			context.setNode(bundle, initialClassState, node);
	}

	private boolean removeJunkCode(@Nonnull MethodNode method) {
		InsnList instructions = method.instructions;
		List<AbstractInsnNode> toRemove = new ArrayList<>();

		for (int i = 0; i < instructions.size(); i++) {
			AbstractInsnNode insn = instructions.get(i);

			// Pattern 1: NEW Object -> DUP -> INVOKESPECIAL <init> -> POP (junk new Object())
			if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode typeInsn) {
				if (JUNK_NEW_TYPES.contains(typeInsn.desc)) {
					AbstractInsnNode next1 = getNextReal(insn);
					if (next1 != null && next1.getOpcode() == Opcodes.DUP) {
						AbstractInsnNode next2 = getNextReal(next1);
						if (next2 != null && next2.getOpcode() == Opcodes.INVOKESPECIAL && next2 instanceof MethodInsnNode initCall) {
							if (initCall.name.equals("<init>") && initCall.owner.equals(typeInsn.desc)) {
								AbstractInsnNode next3 = getNextReal(next2);
								if (next3 != null && next3.getOpcode() == Opcodes.POP) {
									// This is junk: NEW -> DUP -> <init> -> POP
									toRemove.add(insn);
									toRemove.add(next1);
									toRemove.add(next2);
									toRemove.add(next3);
								}
							}
						}
					}
				}
			}

			// Pattern 2: Push constant -> INVOKESTATIC valueOf -> POP (junk boxing)
			if (insn.getOpcode() == Opcodes.INVOKESTATIC && insn instanceof MethodInsnNode methodInsn) {
				String methodKey = methodInsn.owner + "." + methodInsn.name + methodInsn.desc;
				if (JUNK_INVOKE_METHODS.contains(methodKey)) {
					AbstractInsnNode next = getNextReal(insn);
					if (next != null && (next.getOpcode() == Opcodes.POP || next.getOpcode() == Opcodes.POP2)) {
						// Find the argument push instruction
						AbstractInsnNode prev = getPrevReal(insn);
						if (prev != null && isPushConstant(prev)) {
							// This is junk: push constant -> valueOf -> POP
							toRemove.add(prev);
							toRemove.add(insn);
							toRemove.add(next);
						}
					}
				}
			}

			// Pattern 3: NEWARRAY/ANEWARRAY -> ASTORE (unused array allocation)
			// We detect: push size -> NEWARRAY/ANEWARRAY -> ASTORE to local that is never used
			if ((insn.getOpcode() == Opcodes.NEWARRAY || insn.getOpcode() == Opcodes.ANEWARRAY)
					|| (insn.getOpcode() == Opcodes.MULTIANEWARRAY)) {
				AbstractInsnNode prev = getPrevReal(insn);
				AbstractInsnNode next = getNextReal(insn);

				// Check if it's stored to a local and that local is never read
				if (next != null && next.getOpcode() == Opcodes.ASTORE && next instanceof VarInsnNode storeInsn) {
					int localIndex = storeInsn.var;
					if (!isLocalUsedAfter(instructions, next, localIndex)) {
						// Array is created and stored but never used - junk code
						if (prev != null && isPushConstant(prev)) {
							toRemove.add(prev);
							toRemove.add(insn);
							toRemove.add(next);
						}
					}
				}

				// Also handle: NEWARRAY -> POP (even more obvious junk)
				if (next != null && next.getOpcode() == Opcodes.POP) {
					if (prev != null && isPushConstant(prev)) {
						toRemove.add(prev);
						toRemove.add(insn);
						toRemove.add(next);
					}
				}
			}

			// Pattern 4: desiredAssertionStatus check that leads to empty if block
			// INVOKEVIRTUAL Class.desiredAssertionStatus -> IFNE (skip) -> ...
			// This pattern is inserted by obfuscators as junk
			if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL && insn instanceof MethodInsnNode methodInsn) {
				if (methodInsn.owner.equals("java/lang/Class") &&
						methodInsn.name.equals("desiredAssertionStatus") &&
						methodInsn.desc.equals("()Z")) {
					// Check if preceded by LDC class constant
					AbstractInsnNode prev = getPrevReal(insn);
					if (prev != null && prev.getOpcode() == Opcodes.LDC && prev instanceof LdcInsnNode ldcInsn) {
						// Check for IFNE/IFEQ that skips an empty block
						AbstractInsnNode next = getNextReal(insn);
						if (next != null && (next.getOpcode() == Opcodes.IFNE || next.getOpcode() == Opcodes.IFEQ)) {
							// This is likely junk assertion status check
							toRemove.add(prev);
							toRemove.add(insn);
							toRemove.add(next);
						}
					}
				}
			}
		}

		// Remove all identified junk instructions
		for (AbstractInsnNode insn : toRemove) {
			instructions.remove(insn);
		}

		return !toRemove.isEmpty();
	}

	/**
	 * Gets the next real instruction (skipping labels, line numbers, frames).
	 */
	private AbstractInsnNode getNextReal(AbstractInsnNode insn) {
		AbstractInsnNode next = insn.getNext();
		while (next != null && (next.getType() == AbstractInsnNode.LABEL ||
				next.getType() == AbstractInsnNode.LINE ||
				next.getType() == AbstractInsnNode.FRAME)) {
			next = next.getNext();
		}
		return next;
	}

	/**
	 * Gets the previous real instruction (skipping labels, line numbers, frames).
	 */
	private AbstractInsnNode getPrevReal(AbstractInsnNode insn) {
		AbstractInsnNode prev = insn.getPrevious();
		while (prev != null && (prev.getType() == AbstractInsnNode.LABEL ||
				prev.getType() == AbstractInsnNode.LINE ||
				prev.getType() == AbstractInsnNode.FRAME)) {
			prev = prev.getPrevious();
		}
		return prev;
	}

	/**
	 * Checks if the instruction pushes a constant onto the stack.
	 */
	private boolean isPushConstant(AbstractInsnNode insn) {
		int opcode = insn.getOpcode();
		// ICONST_M1 to ICONST_5, LCONST_0/1, FCONST_0/1/2, DCONST_0/1
		if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.DCONST_1)
			return true;
		// BIPUSH, SIPUSH
		if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)
			return true;
		// LDC
		if (opcode == Opcodes.LDC)
			return true;
		return false;
	}

	/**
	 * Checks if a local variable is loaded (used) after the given instruction.
	 */
	private boolean isLocalUsedAfter(InsnList instructions, AbstractInsnNode startInsn, int localIndex) {
		AbstractInsnNode insn = startInsn.getNext();
		while (insn != null) {
			if (insn instanceof VarInsnNode varInsn) {
				if (varInsn.var == localIndex) {
					// Check if it's a load operation (ILOAD, LLOAD, FLOAD, DLOAD, ALOAD)
					int opcode = varInsn.getOpcode();
					if (opcode == Opcodes.ILOAD || opcode == Opcodes.LLOAD ||
							opcode == Opcodes.FLOAD || opcode == Opcodes.DLOAD ||
							opcode == Opcodes.ALOAD) {
						return true;
					}
				}
			}
			insn = insn.getNext();
		}
		return false;
	}

	@Nonnull
	@Override
	public String name() {
		return "Junk code removal";
	}
}
