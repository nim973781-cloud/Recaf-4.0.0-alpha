package software.coley.recaf.services.decompile.vineflower;

import jakarta.annotation.Nonnull;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import software.coley.recaf.services.decompile.index.WorkspaceTypeIndex;
import software.coley.recaf.workspace.model.Workspace;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Library source covering an entire {@link Workspace}, intended to be shared between multiple
 * {@link org.jetbrains.java.decompiler.main.Fernflower} instances.
 * <p/>
 * Unlike {@link LibrarySource} this source is not bound to a single target class, so one instance can be handed to
 * every chunk processed by {@link VineflowerChunkDecompiler}.
 * <p/>
 * The default mode mirrors {@link LibrarySource}: every class in the workspace is listed up-front through the shared
 * {@link WorkspaceTypeIndex}, so Vineflower only ever resolves library classes that the workspace itself declares.
 * Opting into {@link #isLazy() lazy} mode skips the listing entirely and lets Vineflower resolve classes by name on
 * demand, which is faster but also lets it reach classes that the eager listing would not offer <i>(such as runtime
 * classes reachable through {@link Workspace#findClass(String)})</i>.
 *
 * @author Matt Coley
 * @see VineflowerChunkDecompiler Batch decompiler making use of this source.
 */
public class SharedLibrarySource implements IContextSource {
	private final WorkspaceTypeIndex index;
	private final boolean lazy;

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
		this.index = workspace.getTypeIndex();
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

		// Cached on the index rather than on this instance, so the listing also survives across runs for as
		// long as the workspace class-path is unchanged.
		return index.getView(SharedLibrarySource.class, SharedLibrarySource::buildEntries);
	}

	@Override
	public InputStream getInputStream(String resource) {
		String name = resource.substring(0, resource.length() - IContextSource.CLASS_SUFFIX.length());
		byte[] bytecode = index.getBytecode(name);
		if (bytecode == null) return null; // VF wants missing data to be null here, not an IOException or empty stream.
		return new ByteArrayInputStream(bytecode);
	}

	@Override
	public byte[] getClassBytes(String className) {
		return index.getBytecode(className);
	}

	@Override
	public boolean hasClass(String className) {
		return index.getClassInfo(className) != null;
	}

	@Nonnull
	private static Entries buildEntries(@Nonnull WorkspaceTypeIndex index) {
		List<String> classNames = index.getLibraryClassNames();
		List<Entry> classes = new ArrayList<>(classNames.size());
		for (String className : classNames)
			classes.add(new Entry(className, Entry.BASE_VERSION));
		return new Entries(Collections.unmodifiableList(classes), Collections.emptyList(), Collections.emptyList());
	}
}
