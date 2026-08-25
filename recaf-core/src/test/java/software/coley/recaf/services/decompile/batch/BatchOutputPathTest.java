package software.coley.recaf.services.decompile.batch;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link BatchOutputPath}.
 */
class BatchOutputPathTest {
	@Test
	void normalizeUsesForwardSlashesAndDropsRelativeSegments() {
		assertEquals("a/b/c.java", BatchOutputPath.normalize("a\\b\\c.java"));
		assertEquals("a/b/c.java", BatchOutputPath.normalize("/a//b/./c.java"));
		assertEquals("c.java", BatchOutputPath.normalize("../../c.java"));
		assertEquals("", BatchOutputPath.normalize("/"));
	}

	@Test
	void joinCombinesPrefixAndName() {
		assertEquals("sample/com/example/Foo.java", BatchOutputPath.join("sample", "com/example/Foo.java"));
		assertEquals("com/example/Foo.java", BatchOutputPath.join("", "com/example/Foo.java"));
		assertEquals("sample/META-INF/versions/17/Foo.java",
				BatchOutputPath.join("sample", "META-INF/versions/17/Foo.java"));
	}

	@Test
	void resolveStaysUnderTheRoot() {
		Path root = Path.of("out");
		assertEquals(root.resolve("a").resolve("b.java"), BatchOutputPath.resolve(root, "a/b.java"));
		assertTrue(BatchOutputPath.resolve(root, "../../escape.java").startsWith(root));
	}
}
