package software.coley.recaf.services.deobfuscation.transform.generic;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A transformer that attempts to decrypt obfuscated strings by identifying
 * and emulating common string decryption patterns.
 * <p>
 * Supports common patterns like:
 * <ul>
 *   <li>XOR-based character decryption</li>
 *   <li>Base64 + XOR combinations</li>
 *   <li>Simple character shifting</li>
 * </ul>
 *
 * @author Recaf Contributors
 */
@Dependent
public class StringDecryptionTransformer implements JvmClassTransformer {

	// Cache of decryption method signatures and their decrypted results
	private final Map<String, DecryptionMethod> detectedMethods = new HashMap<>();

	@Override
	public void transform(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                      @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle,
	                      @Nonnull JvmClassInfo initialClassState) throws TransformationException {
		boolean dirty = false;
		ClassNode node = context.getNode(bundle, initialClassState);

		// First pass: identify potential decryption methods in this class
		for (MethodNode method : node.methods) {
			if (isStaticStringDecryptionMethod(method)) {
				DecryptionMethod dm = analyzeDecryptionMethod(method);
				if (dm != null) {
					String key = node.name + "." + method.name + method.desc;
					detectedMethods.put(key, dm);
				}
			}
		}

		// Second pass: replace encrypted string calls with decrypted values
		for (MethodNode method : node.methods) {
			if (method.instructions == null)
				continue;

			dirty |= decryptStringsInMethod(node, method);
		}

		if (dirty)
			context.setNode(bundle, initialClassState, node);
	}

	/**
	 * Checks if a method looks like a static string decryption method.
	 * Pattern: private static String xxx(String) { ... }
	 */
	private boolean isStaticStringDecryptionMethod(@Nonnull MethodNode method) {
		// Must be static and private
		if ((method.access & Opcodes.ACC_STATIC) == 0)
			return false;
		if ((method.access & Opcodes.ACC_PRIVATE) == 0)
			return false;

		// Must take String and return String
		if (!method.desc.equals("(Ljava/lang/String;)Ljava/lang/String;"))
			return false;

		// Method name is usually obfuscated (long random string)
		// Skip standard method names
		if (method.name.equals("toString") || method.name.equals("valueOf"))
			return false;

		return true;
	}

	/**
	 * Analyzes a decryption method to determine its type and parameters.
	 */
	@Nullable
	private DecryptionMethod analyzeDecryptionMethod(@Nonnull MethodNode method) {
		if (method.instructions == null)
			return null;

		// Look for common patterns in the bytecode
		boolean hasBase64Decode = false;
		boolean hasXor = false;
		boolean hasToCharArray = false;
		int xorKey = -1;
		int xorKey2 = -1;

		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode methodInsn) {
				// Check for Base64.getDecoder().decode()
				if (methodInsn.owner.equals("java/util/Base64$Decoder") && methodInsn.name.equals("decode")) {
					hasBase64Decode = true;
				}
				// Check for String.toCharArray()
				if (methodInsn.name.equals("toCharArray") && methodInsn.desc.equals("()[C")) {
					hasToCharArray = true;
				}
			}

			// Check for XOR operation
			if (insn.getOpcode() == Opcodes.IXOR) {
				hasXor = true;
			}

			// Try to extract XOR key constants
			if (insn instanceof LdcInsnNode ldcInsn) {
				if (ldcInsn.cst instanceof Integer intVal) {
					if (xorKey == -1) {
						xorKey = intVal;
					} else if (xorKey2 == -1) {
						xorKey2 = intVal;
					}
				}
			}

			// Check for BIPUSH/SIPUSH as XOR key
			if (insn.getOpcode() == Opcodes.BIPUSH || insn.getOpcode() == Opcodes.SIPUSH) {
				if (insn instanceof org.objectweb.asm.tree.IntInsnNode intInsn) {
					if (xorKey == -1) {
						xorKey = intInsn.operand;
					} else if (xorKey2 == -1) {
						xorKey2 = intInsn.operand;
					}
				}
			}
		}

		// Determine decryption type
		if (hasBase64Decode && hasXor) {
			return new DecryptionMethod(DecryptionType.BASE64_XOR, xorKey, xorKey2);
		} else if (hasToCharArray && hasXor && xorKey != -1) {
			return new DecryptionMethod(DecryptionType.CHAR_XOR, xorKey, xorKey2);
		}

		return null;
	}

	/**
	 * Decrypts strings in a method by replacing decryption calls with their results.
	 */
	private boolean decryptStringsInMethod(@Nonnull ClassNode classNode, @Nonnull MethodNode method) {
		InsnList instructions = method.instructions;
		List<ReplacementInfo> replacements = new ArrayList<>();

		for (int i = 0; i < instructions.size(); i++) {
			AbstractInsnNode insn = instructions.get(i);

			// Look for: LDC "encrypted" -> INVOKESTATIC decryptMethod
			if (insn.getOpcode() == Opcodes.LDC && insn instanceof LdcInsnNode ldcInsn) {
				if (ldcInsn.cst instanceof String encryptedString) {
					AbstractInsnNode next = getNextReal(insn);
					if (next instanceof MethodInsnNode methodInsn && methodInsn.getOpcode() == Opcodes.INVOKESTATIC) {
						String methodKey = methodInsn.owner + "." + methodInsn.name + methodInsn.desc;
						DecryptionMethod dm = detectedMethods.get(methodKey);

						if (dm != null) {
							// Try to decrypt
							String decrypted = tryDecrypt(encryptedString, dm);
							if (decrypted != null && !decrypted.equals(encryptedString)) {
								replacements.add(new ReplacementInfo(ldcInsn, next, decrypted));
							}
						} else {
							// Try to detect inline decryption pattern even without prior analysis
							String decrypted = tryCommonDecryption(encryptedString, methodInsn);
							if (decrypted != null && !decrypted.equals(encryptedString)) {
								replacements.add(new ReplacementInfo(ldcInsn, next, decrypted));
							}
						}
					}
				}
			}
		}

		// Apply replacements
		for (ReplacementInfo ri : replacements) {
			// Replace LDC with decrypted string
			instructions.set(ri.ldcInsn, new LdcInsnNode(ri.decryptedString));
			// Remove the decryption method call
			instructions.remove(ri.methodInsn);
		}

		return !replacements.isEmpty();
	}

	/**
	 * Tries to decrypt a string using the identified decryption method.
	 */
	@Nullable
	private String tryDecrypt(@Nonnull String encrypted, @Nonnull DecryptionMethod dm) {
		try {
			return switch (dm.type) {
				case CHAR_XOR -> decryptCharXor(encrypted, dm.key1);
				case BASE64_XOR -> decryptBase64Xor(encrypted, dm.key1, dm.key2);
				case BASE64_SIMPLE -> decryptBase64Simple(encrypted);
			};
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Tries common decryption patterns based on method name hints.
	 */
	@Nullable
	private String tryCommonDecryption(@Nonnull String encrypted, @Nonnull MethodInsnNode methodInsn) {
		// Try various common XOR keys
		int[] commonXorKeys = {0x42, 0x5A, 0x3C, 0x2D, 0x7F, 0x55, 0xAA};

		for (int key : commonXorKeys) {
			String result = decryptCharXor(encrypted, key);
			if (isLikelyPlaintext(result)) {
				return result;
			}
		}

		// Try Base64 decode
		try {
			byte[] decoded = Base64.getDecoder().decode(encrypted);
			String result = new String(decoded, StandardCharsets.UTF_8);
			if (isLikelyPlaintext(result)) {
				return result;
			}

			// Try Base64 + XOR
			for (int key : commonXorKeys) {
				byte[] xored = new byte[decoded.length];
				for (int i = 0; i < decoded.length; i++) {
					xored[i] = (byte) (decoded[i] ^ key);
				}
				result = new String(xored, StandardCharsets.UTF_8);
				if (isLikelyPlaintext(result)) {
					return result;
				}
			}
		} catch (Exception ignored) {
		}

		return null;
	}

	private String decryptCharXor(@Nonnull String encrypted, int key) {
		char[] chars = encrypted.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			chars[i] = (char) (chars[i] ^ key);
		}
		return new String(chars);
	}

	private String decryptBase64Xor(@Nonnull String encrypted, int key1, int key2) {
		try {
			byte[] decoded = Base64.getDecoder().decode(encrypted);
			for (int i = 0; i < decoded.length; i++) {
				if (key2 != -1) {
					// Pattern: byte ^ key1 ^ (i * someValue % someOther)
					decoded[i] = (byte) (decoded[i] ^ key1 ^ (i * 3 % 23));
				} else {
					decoded[i] = (byte) (decoded[i] ^ key1);
				}
			}
			return new String(decoded, StandardCharsets.UTF_8);
		} catch (Exception e) {
			return null;
		}
	}

	private String decryptBase64Simple(@Nonnull String encrypted) {
		try {
			return new String(Base64.getDecoder().decode(encrypted), StandardCharsets.UTF_8);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Heuristic to check if a string looks like valid plaintext.
	 */
	private boolean isLikelyPlaintext(@Nonnull String str) {
		if (str.isEmpty())
			return false;

		int printableCount = 0;
		for (char c : str.toCharArray()) {
			if (c >= 32 && c < 127) {
				printableCount++;
			}
		}

		// At least 80% should be printable ASCII
		return (double) printableCount / str.length() >= 0.8;
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

	@Nonnull
	@Override
	public String name() {
		return "String decryption";
	}

	private enum DecryptionType {
		CHAR_XOR,      // Simple char[] XOR
		BASE64_XOR,    // Base64 decode + XOR
		BASE64_SIMPLE  // Just Base64
	}

	private record DecryptionMethod(DecryptionType type, int key1, int key2) {}

	private record ReplacementInfo(LdcInsnNode ldcInsn, AbstractInsnNode methodInsn, String decryptedString) {}
}
