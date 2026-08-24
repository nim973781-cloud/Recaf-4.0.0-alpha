package software.coley.recaf.services.decompile.vineflower;

import jakarta.annotation.Nonnull;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.index.WorkspaceTypeIndex;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Full library source for Vineflower.
 *
 * @author Matt Coley
 * @author therathatter
 */
public class LibrarySource extends BaseSource {
	/**
	 * @param workspace
	 * 		Workspace to pull class files from.
	 * @param targetInfo
	 * 		Target class to decompile.
	 */
	protected LibrarySource(@Nonnull Workspace workspace, @Nonnull JvmClassInfo targetInfo) {
		super(workspace, targetInfo);
	}

	@Override
	public Entries getEntries() {
		// Every class in the workspace is listed here, so building this per decompiled class dominated the
		// cost of small decompilations. Vineflower only reads the listing, which lets one instance be shared
		// by every 'Fernflower' created against the current workspace generation.
		return index.getView(LibrarySource.class, LibrarySource::buildEntries);
	}

	@Nonnull
	private static Entries buildEntries(@Nonnull WorkspaceTypeIndex index) {
		List<String> classNames = index.getLibraryClassNames();
		List<Entry> entries = new ArrayList<>(classNames.size());
		for (String className : classNames)
			entries.add(new Entry(className, Entry.BASE_VERSION));
		return new Entries(Collections.unmodifiableList(entries), Collections.emptyList(), Collections.emptyList());
	}
}
