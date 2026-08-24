package software.coley.recaf.services.decompile.vineflower;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Library source covering an entire {@link Workspace}, intended to be shared between multiple
 * {@link org.jetbrains.java.decompiler.main.Fernflower} instances.
 * <p/>
 * Unlike {@link LibrarySource} this source is not bound to a single target class, so one instance can be handed to
 * every chunk processed by {@link VineflowerChunkDecompiler}. The workspace class listing is only built once and
 * then reused for each following chunk.
 * <p/>
 * The default mode mirrors {@link LibrarySource}: every class in the workspace is listed up-front, so Vineflower
 * only ever resolves library classes that the workspace itself declares. Opting into {@link #isLazy() lazy} mode
 * skips the listing entirely and lets Vineflower resolve classes by name on demand, which is faster but also lets it
 * reach classes that the eager listing would not offer <i>(such as runtime classes reachable through
 * {@link Workspace#findClass(String)})</i>.
 *
 * @author Matt Coley
 * @see VineflowerChunkDecompiler Batch decompiler making use of this source.
 */
public class SharedLibrarySource implements IContextSource {
	private final Workspace workspace;
	private final boolean lazy;
	private volatile Entries entries;

	/**
	 * New shared library source listing every class in the workspace.
	 *
	 * @param workspace
	 * 		Workspace to pull class files from.
	 */
	public SharedLibrarySource(@Nonnull Workspace workspace) {
		this(workspace, false);
	}

	/**
	 * @param workspace
	 * 		Workspace to pull class files from.
	 * @param lazy
	 *        {@code true} to let Vineflower resolve library classes by name on-demand.
	 *        {@code false} to enumerate every class in the workspace up-front.
	 */
	public SharedLibrarySource(@Nonnull Workspace workspace, boolean lazy) {
		this.workspace = workspace;
		this.lazy = lazy;
	}

	@Override
	public String getName() {
		return "Recaf-shared-library";
	}

	@Override
	public boolean isLazy() {
		return lazy;
	}

	@Override
	public Entries getEntries() {
		// Vineflower allows lazy sources to skip the listing entirely.
		if (lazy) return Entries.EMPTY;

		Entries value = entries;
		if (value == null) {
			synchronized (this) {
				value = entries;
				if (value == null) {
					value = buildEntries();
					entries = value;
				}
			}
		}
		return value;
	}

	@Override
	public InputStream getInputStream(String resource) {
		String name = resource.substring(0, resource.length() - IContextSource.CLASS_SUFFIX.length());
		byte[] bytecode = lookup(name);
		if (bytecode == null) return null; // VF wants missing data to be null here, not an IOException or empty stream.
		return new ByteArrayInputStream(bytecode);
	}

	@Override
	public byte[] getClassBytes(String className) {
		return lookup(className);
	}

	@Override
	public boolean hasClass(String className) {
		return workspace.findClass(className) != null;
	}

	@Nullable
	private byte[] lookup(@Nonnull String name) {
		ClassPathNode node = workspace.findClass(name);
		if (node == null) return null;
		return node.getValue().asJvmClass().getBytecode();
	}

	@Nonnull
	private Entries buildEntries() {
		List<Entry> classes = workspace.getAllResources(false).stream()
				.map(WorkspaceResource::getJvmClassBundle)
				.flatMap(c -> c.keySet().stream())
				.map(className -> new Entry(className, Entry.BASE_VERSION))
				.collect(Collectors.toList());
		return new Entries(classes, Collections.emptyList(), Collections.emptyList());
	}
}
