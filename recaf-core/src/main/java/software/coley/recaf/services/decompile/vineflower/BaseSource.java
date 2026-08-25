package software.coley.recaf.services.decompile.vineflower;

import jakarta.annotation.Nonnull;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.index.WorkspaceTypeIndex;
import software.coley.recaf.workspace.model.Workspace;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Base Vineflower class/library source.
 *
 * @author therathatter
 */
public abstract class BaseSource implements IContextSource {
	protected final JvmClassInfo targetInfo;
	protected final Workspace workspace;
	protected final WorkspaceTypeIndex index;

	/**
	 * @param workspace
	 * 		Workspace to pull class files from.
	 * @param targetInfo
	 * 		Target class to decompile.
	 */
	protected BaseSource(@Nonnull Workspace workspace, @Nonnull JvmClassInfo targetInfo) {
		this.workspace = workspace;
		this.index = workspace.getTypeIndex();
		this.targetInfo = targetInfo;
	}

	@Override
	public String getName() {
		return "Recaf";
	}

	@Override
	public InputStream getInputStream(String resource) {
		byte[] bytecode = getClassBytes(resource.substring(0, resource.length() - IContextSource.CLASS_SUFFIX.length()));
		if (bytecode == null) return null; // VF wants missing data to be null here, not an IOException or empty stream.
		return new ByteArrayInputStream(bytecode);
	}

	@Override
	public byte[] getClassBytes(String className) {
		if (className.equals(targetInfo.getName()))
			return targetInfo.getBytecode();
		return index.getBytecode(className);
	}

	@Override
	public boolean hasClass(String className) {
		return className.equals(targetInfo.getName()) || index.getClassInfo(className) != null;
	}
}
