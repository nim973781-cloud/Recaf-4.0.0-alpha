package software.coley.recaf.services.decompile.batch.session;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.decompile.batch.BatchAccuracyMode;
import software.coley.recaf.services.decompile.batch.ClassExportTask;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.sourcesolver.Parser;
import software.coley.sourcesolver.model.ClassModel;
import software.coley.sourcesolver.model.CompilationUnitModel;
import software.coley.sourcesolver.model.MethodModel;
import software.coley.sourcesolver.model.VariableModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared assertions for the per-backend {@code *SessionEquivalenceTest} classes.
 * <ul>
 *     <li>{@link BatchAccuracyMode#ACCURATE} sessions must match the single-class decompile path byte for
 *     byte.</li>
 *     <li>Fast sessions must cover every requested class and declare the same type and member signature
 *     sets as the accurate path; comments, whitespace, import order and local names may differ.</li>
 *     <li>Shared session state must not make output depend on chunk order: five randomized chunk orders
 *     must produce identical text per class.</li>
 *     <li>Sessions must translate a pending interrupt into {@link InterruptedException}, consuming the
 *     flag.</li>
 * </ul>
 */
public final class SessionEquivalenceTestSupport {
	private SessionEquivalenceTestSupport() {}

	/**
	 * Decompiles every class through the backend's single-class path, the reference for ACCURATE output.
	 */
	@Nonnull
	public static Map<String, String> singleClassBaseline(@Nonnull JvmDecompiler decompiler,
	                                                      @Nonnull Workspace workspace,
	                                                      @Nonnull List<JvmClassInfo> classes) {
		Map<String, String> baseline = new LinkedHashMap<>(classes.size());
		for (JvmClassInfo info : classes) {
			String text = decompiler.decompile(workspace, info).getText();
			assertNotNull(text, "Single-class path produced no text for " + info.getName());
			baseline.put(info.getName(), text);
		}
		return baseline;
	}

	/**
	 * Runs one session over the classes in chunks, asserting 100% coverage, and returns the text per class.
	 */
	@Nonnull
	public static Map<String, String> decompileInChunks(@Nonnull BatchDecompileSessionFactory factory,
	                                                    @Nonnull Workspace workspace,
	                                                    @Nonnull BatchAccuracyMode mode,
	                                                    @Nonnull List<List<JvmClassInfo>> chunks) throws InterruptedException {
		Map<String, String> texts = new LinkedHashMap<>();
		try (BatchDecompileSession session = factory.open(workspace, mode)) {
			assertNotNull(session, "Factory declined to open a session for " + mode);
			for (List<JvmClassInfo> chunk : chunks) {
				Map<String, SessionClassResult> results = await(session.decompile(BatchSessionSupport.tasks(chunk)));
				for (JvmClassInfo info : chunk) {
					SessionClassResult result = results.get(info.getName());
					assertNotNull(result, "Session dropped " + info.getName() + " from its results");
					if (result.failure() != null)
						result.failure().printStackTrace();
					assertTrue(result.isSuccess(), "Session failed to decompile " + info.getName()
							+ ": " + result.failure());
					texts.put(info.getName(), result.text());
				}
			}
		}
		return texts;
	}

	/**
	 * ACCURATE gate: session output must be byte-identical with the single-class path.
	 */
	public static void assertAccurateMatchesSingleClassPath(@Nonnull BatchDecompileSessionFactory factory,
	                                                        @Nonnull JvmDecompiler decompiler,
	                                                        @Nonnull Workspace workspace,
	                                                        @Nonnull List<JvmClassInfo> classes) throws InterruptedException {
		Map<String, String> baseline = singleClassBaseline(decompiler, workspace, classes);
		Map<String, String> session = decompileInChunks(factory, workspace, BatchAccuracyMode.ACCURATE,
				partition(classes, 3));
		for (JvmClassInfo info : classes) {
			String name = info.getName();
			assertEquals(baseline.get(name), session.get(name),
					"ACCURATE session output is not byte-identical for " + name);
		}
	}

	/**
	 * FAST gate: full coverage, and per class the declared type set and member signature set must equal
	 * the accurate path's sets.
	 */
	public static void assertFastSignaturesMatchAccurate(@Nonnull BatchDecompileSessionFactory factory,
	                                                     @Nonnull JvmDecompiler decompiler,
	                                                     @Nonnull Workspace workspace,
	                                                     @Nonnull List<JvmClassInfo> classes,
	                                                     @Nonnull Parser parser) throws InterruptedException {
		Map<String, String> baseline = singleClassBaseline(decompiler, workspace, classes);
		Map<String, String> fast = decompileInChunks(factory, workspace, BatchAccuracyMode.FAST_VERIFIED,
				partition(classes, 3));
		assertEquals(classes.size(), fast.size(), "FAST session did not cover every class");
		for (JvmClassInfo info : classes) {
			String name = info.getName();
			SignatureSet expected = signaturesOf(parser, baseline.get(name), name);
			SignatureSet actual = signaturesOf(parser, fast.get(name), name);
			assertEquals(expected.types(), actual.types(),
					"FAST session declares different types for " + name);
			assertEquals(expected.members(), actual.members(),
					"FAST session declares different member signatures for " + name);
		}
	}

	/**
	 * Shared-context gate: five randomized chunk orders must yield identical text per class.
	 */
	public static void assertChunkOrderDoesNotChangeOutput(@Nonnull BatchDecompileSessionFactory factory,
	                                                       @Nonnull Workspace workspace,
	                                                       @Nonnull List<JvmClassInfo> classes,
	                                                       @Nonnull BatchAccuracyMode mode) throws InterruptedException {
		Map<String, String> reference = null;
		for (int round = 0; round < 5; round++) {
			List<JvmClassInfo> shuffled = new ArrayList<>(classes);
			Collections.shuffle(shuffled, new Random(round * 7919L + 42));
			Map<String, String> texts = decompileInChunks(factory, workspace, mode, partition(shuffled, 3));
			if (reference == null) {
				reference = texts;
				continue;
			}
			for (JvmClassInfo info : classes) {
				String name = info.getName();
				assertEquals(reference.get(name), texts.get(name),
						"Chunk order changed the output of " + name + " (round " + round + ")");
			}
		}
	}

	/**
	 * Interrupt gate: a pending interrupt turns into {@link InterruptedException} and consumes the flag.
	 */
	public static void assertInterruptStopsSession(@Nonnull BatchDecompileSessionFactory factory,
	                                               @Nonnull Workspace workspace,
	                                               @Nonnull List<JvmClassInfo> classes,
	                                               @Nonnull BatchAccuracyMode mode) throws Exception {
		List<ClassExportTask> tasks = BatchSessionSupport.tasks(classes);
		try (BatchDecompileSession session = factory.open(workspace, mode)) {
			assertNotNull(session, "Factory declined to open a session for " + mode);
			Thread.currentThread().interrupt();
			try {
				InterruptedException interrupted = assertThrows(InterruptedException.class, () -> await(session.decompile(tasks)),
						"Session ignored a pending interrupt");
				assertNotNull(interrupted);
			} finally {
				// The session consumes the flag when it throws; clear defensively so a failed assertion
				// does not poison later tests.
				boolean leftover = Thread.interrupted();
				assertFalse(leftover, "Session left the interrupt flag set after throwing");
			}
		}
	}

	@Nonnull
	private static Map<String, SessionClassResult> await(
			@Nonnull java.util.concurrent.CompletableFuture<Map<String, SessionClassResult>> future)
			throws InterruptedException {
		try {
			return future.get();
		} catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof InterruptedException interrupted)
				throw interrupted;
			if (cause instanceof CompletionException completion && completion.getCause() instanceof InterruptedException interrupted)
				throw interrupted;
			if (cause instanceof RuntimeException runtime)
				throw runtime;
			if (cause instanceof Error error)
				throw error;
			throw new IllegalStateException(cause);
		}
	}

	/**
	 * @param classes
	 * 		Classes to split.
	 * @param chunkSize
	 * 		Maximum chunk size.
	 *
	 * @return Chunks in iteration order.
	 */
	@Nonnull
	public static List<List<JvmClassInfo>> partition(@Nonnull List<JvmClassInfo> classes, int chunkSize) {
		List<List<JvmClassInfo>> chunks = new ArrayList<>();
		for (int i = 0; i < classes.size(); i += chunkSize)
			chunks.add(classes.subList(i, Math.min(classes.size(), i + chunkSize)));
		return chunks;
	}

	/**
	 * Declared types and member signatures of one decompiled source, normalized so that package
	 * qualification, whitespace and local naming differences do not register.
	 *
	 * @param types
	 * 		Declared type names, nested as {@code Outer.Inner}.
	 * @param members
	 * 		Member signatures: {@code Type#field : FieldType} and {@code Type#method(ArgTypes) : ReturnType}.
	 */
	public record SignatureSet(@Nonnull SortedSet<String> types, @Nonnull SortedSet<String> members) {}

	/**
	 * @param parser
	 * 		Java parser (see {@code AstService#getSharedJavaParser()}).
	 * @param source
	 * 		Decompiled source of one class.
	 * @param name
	 * 		Class name, for failure messages.
	 *
	 * @return Declared type and member signature sets of the source.
	 */
	@Nonnull
	public static SignatureSet signaturesOf(@Nonnull Parser parser, @Nullable String source, @Nonnull String name) {
		assertNotNull(source, "No source to extract signatures from for " + name);
		CompilationUnitModel unit = parser.parse(source);
		assertFalse(unit.getDeclaredClasses().isEmpty(), "Source of " + name + " declares no types:\n" + source);
		SortedSet<String> types = new TreeSet<>();
		SortedSet<String> members = new TreeSet<>();
		for (ClassModel cls : unit.getDeclaredClasses())
			collectSignatures(null, cls, types, members);
		return new SignatureSet(types, members);
	}

	private static void collectSignatures(@Nullable String outerName, @Nonnull ClassModel cls,
	                                      @Nonnull SortedSet<String> types, @Nonnull SortedSet<String> members) {
		String name = outerName == null ? cls.getName() : outerName + '.' + cls.getName();
		types.add(name);
		for (VariableModel field : cls.getFields())
			members.add(name + '#' + field.getName() + " : " + normalizeType(field.getType().toString()));
		for (MethodModel method : cls.getMethods()) {
			String args = method.getParameters().stream()
					.map(parameter -> normalizeType(parameter.getType().toString()))
					.collect(Collectors.joining(","));
			String returnType = method.getReturnType() == null
					? "" : normalizeType(method.getReturnType().toString());
			members.add(name + '#' + method.getName() + '(' + args + ") : " + returnType);
		}
		for (ClassModel inner : cls.getInnerClasses())
			collectSignatures(name, inner, types, members);
	}

	/**
	 * Collapses whitespace and strips package/outer qualification so that import-order and qualification
	 * differences between two outputs of the same decompiler do not count as signature differences.
	 */
	@Nonnull
	private static String normalizeType(@Nonnull String type) {
		String collapsed = type.replaceAll("\\s+", "");
		return collapsed.replaceAll("([\\p{L}_$][\\p{L}\\p{N}_$]*\\.)+", "");
	}
}
