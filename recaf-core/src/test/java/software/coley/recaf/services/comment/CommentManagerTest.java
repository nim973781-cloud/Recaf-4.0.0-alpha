package software.coley.recaf.services.comment;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.filter.OutputTextFilter;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.ClassWithFieldsAndMethods;
import software.coley.recaf.util.ReflectUtil;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CommentManager}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommentManagerTest extends TestBase {
	static CommentManager commentManager;
	static CommentManagerConfig commentManagerConfig;
	static DecompilerManager decompilerManager;
	static MappingApplierService mappingApplierService;
	static JvmClassInfo classToDecompile;
	static Workspace workspace;

	@BeforeAll
	static void setup() throws IOException {
		// Setup workspace
		classToDecompile = TestClassUtils.fromRuntimeClass(ClassWithFieldsAndMethods.class);
		workspace = TestClassUtils.fromBundle(TestClassUtils.fromClasses(classToDecompile));
		workspaceManager.setCurrent(workspace);

		// Grab services
		commentManager = recaf.get(CommentManager.class);
		commentManagerConfig = recaf.get(CommentManagerConfig.class);
		decompilerManager = recaf.get(DecompilerManager.class);
		mappingApplierService = recaf.get(MappingApplierService.class);
	}

	@Test
	@Order(0)
	void testOutputFilterDoesNotLookupClassesWithoutComments() {
		// Resolve the CDI proxy so the manager is constructed, registering its decompiler filters.
		assertNotNull(unwrapProxy(commentManager), "Comment manager was not initialized");
		OutputTextFilter filter = findCommentOutputFilter();

		// Any class lookup on this workspace blows up. When the decompilation has no comment markers in it
		// the filter has nothing to do, so it must not go looking for the class.
		Workspace hostileWorkspace = mock(Workspace.class);
		when(hostileWorkspace.findClass(anyString()))
				.thenThrow(new AssertionError("Output filter looked up a class for comment-free decompilation"));
		String code = "public class Example {}";
		assertSame(code, filter.filter(hostileWorkspace, classToDecompile, code),
				"Expected the original text back, unmodified");

		// Comment markers in the output are also left alone when the workspace has no comments for the class.
		String markedCode = "@RecafComment_00000000_00000000\npublic class Example {}";
		assertSame(markedCode, filter.filter(workspace, classToDecompile, markedCode),
				"Expected the original text back, unmodified");
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	private static OutputTextFilter findCommentOutputFilter() {
		List<OutputTextFilter> filters = assertDoesNotThrow(() -> (List<OutputTextFilter>) ReflectUtil.quietGet(
				unwrapProxy(decompilerManager), DecompilerManager.class.getDeclaredField("outputTextFilters")));
		return filters.stream()
				.filter(f -> f.getClass().getName().startsWith(CommentManager.class.getName()))
				.findFirst()
				.orElseGet(() -> fail("Comment manager did not register an output text filter, found: " + filters));
	}

	@Test
	@Order(1)
	void testCommentsInsertedIntoDecompilation() {
		ClassPathNode path = workspace.findClass(classToDecompile.getName());
		assertNotNull(path, "Failed to find class in workspace");

		WorkspaceComments workspaceComments = commentManager.getOrCreateWorkspaceComments(workspace);
		ClassComments classComments = workspaceComments.getOrCreateClassComments(path);
		classComments.setClassComment("Class comment with too many words to be put on a single line, which should " +
				"trigger the automatic word wrapping so that the full contents of this message are legible " +
				"without having to scroll to the right, which is annoying");
		classComments.setFieldComment("CONST_INT", "I", "Field comment\nThis is a constant value.");
		classComments.setMethodComment("methodWithLocalVariables", "()V", "Method comment");

		// Validate that decompiling the class inserts the comments
		try {
			commentManagerConfig.getWordWrappingLimit().setValue(100);
			DecompileResult result = decompilerManager.decompile(workspace, classToDecompile).get();
			String text = result.getText();
			assertNotNull(text, "Decompile failed");
			assertTrue(text.contains("""
					/**
					 * Class comment with too many words to be put on a single line, which should trigger the automatic
					 * word wrapping so that the full contents of this message are legible without having to scroll to the
					 * right, which is annoying
					 */
					"""), "Expected class comment to exist and be line wrapped (100)");
			assertTrue(text.contains("/** Method comment */"), "Expected single line method comment");
			assertTrue(text.contains("""
					    /**
					     * Field comment
					     * This is a constant value.
					     */
					"""), "Expected multi-line indented field comment");
		} catch (Exception ex) {
			fail(ex);
		}
	}

	@Test
	@Order(2)
	void testCommentsGetMigratedAfterRemapping() {
		ClassPathNode preMappingPath = workspace.findJvmClass(classToDecompile.getName());
		assertNotNull(preMappingPath);

		// Generate some mappings for the documented class (applied in the first test)
		String mappedClassName = "Foo";
		IntermediateMappings mappings = new IntermediateMappings();
		mappings.addClass(classToDecompile.getName(), mappedClassName);
		mappings.addField(classToDecompile.getName(), "I", "CONST_INT", "BAR");
		mappings.addMethod(classToDecompile.getName(), "()V", "methodWithLocalVariables", "fizz");

		// Apply the mappings
		MappingResults results = mappingApplierService.inCurrentWorkspace().applyToPrimaryResource(mappings);
		ClassPathNode postMappingPath = results.getPostMappingPath(classToDecompile.getName());
		assertNotNull(postMappingPath, "Post-mapping path does not exist in mapping results");
		results.apply();

		// Validate the old mappings are migrated.
		WorkspaceComments workspaceComments = commentManager.getOrCreateWorkspaceComments(workspace);
		assertNull(workspaceComments.getClassComments(preMappingPath), "Old comment container still exists");
		ClassComments newClassComments = workspaceComments.getClassComments(postMappingPath);
		assertNotNull(newClassComments, "New comment container does not exist");
		assertNotNull(newClassComments.getClassComment(), "Missing class comment");
		assertNotNull(newClassComments.getFieldComment("BAR", "I"), "Missing field comment");
		assertNotNull(newClassComments.getMethodComment("fizz", "()V"), "Missing method comment");
	}
}