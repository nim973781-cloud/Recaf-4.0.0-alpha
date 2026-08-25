package software.coley.recaf.services.decompile.vineflower;

import org.jetbrains.java.decompiler.main.extern.IContextSource;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.AccessibleFields;
import software.coley.recaf.test.dummy.AccessibleMethods;
import software.coley.recaf.test.dummy.HelloWorld;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LibrarySource}, which lists every workspace class for Vineflower.
 */
class LibrarySourceTest {
	@Test
	void listsEveryWorkspaceClass() throws IOException {
		BasicJvmClassBundle bundle = TestClassUtils.fromClasses(AccessibleFields.class, AccessibleMethods.class);
		Workspace workspace = TestClassUtils.fromBundle(bundle);
		JvmClassInfo target = bundle.get("software/coley/recaf/test/dummy/AccessibleFields");
		assertNotNull(target);

		List<String> paths = new LibrarySource(workspace, target).getEntries().classes().stream()
				.map(IContextSource.Entry::basePath)
				.sorted()
				.toList();
		assertEquals(List.of(
				"software/coley/recaf/test/dummy/AccessibleFields",
				"software/coley/recaf/test/dummy/AccessibleMethods"
		), paths);
	}

	@Test
	void reusesEntriesUntilTheWorkspaceChanges() throws IOException {
		BasicJvmClassBundle bundle = TestClassUtils.fromClasses(AccessibleFields.class);
		Workspace workspace = TestClassUtils.fromBundle(bundle);
		JvmClassInfo target = bundle.get("software/coley/recaf/test/dummy/AccessibleFields");
		assertNotNull(target);

		// Each decompiled class gets its own source, but the listing behind them is shared. Rebuilding it per
		// class is what made small decompilations pay for the size of the whole workspace.
		IContextSource.Entries first = new LibrarySource(workspace, target).getEntries();
		IContextSource.Entries second = new LibrarySource(workspace, target).getEntries();
		assertSame(first, second);

		bundle.put(TestClassUtils.createEmptyClass("com/example/Added"));
		IContextSource.Entries afterEdit = new LibrarySource(workspace, target).getEntries();
		assertNotSame(first, afterEdit);
		assertEquals(2, afterEdit.classes().size());
	}

	@Test
	void servesBytecodeForWorkspaceClasses() throws IOException {
		BasicJvmClassBundle bundle = TestClassUtils.fromClasses(AccessibleFields.class, HelloWorld.class);
		Workspace workspace = TestClassUtils.fromBundle(bundle);
		JvmClassInfo target = bundle.get("software/coley/recaf/test/dummy/AccessibleFields");
		assertNotNull(target);

		LibrarySource source = new LibrarySource(workspace, target);
		assertNotNull(source.getInputStream("software/coley/recaf/test/dummy/HelloWorld"
				+ IContextSource.CLASS_SUFFIX));
		assertNotNull(source.getInputStream("java/lang/String" + IContextSource.CLASS_SUFFIX));
		assertNull(source.getInputStream("does/not/Exist" + IContextSource.CLASS_SUFFIX));
	}
}
