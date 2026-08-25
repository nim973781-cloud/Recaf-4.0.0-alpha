package software.coley.recaf.services.decompile.procyon;

import com.strobel.assembler.metadata.Buffer;
import com.strobel.assembler.metadata.ITypeLoader;
import jakarta.annotation.Nonnull;
import software.coley.recaf.services.decompile.index.WorkspaceTypeIndex;
import software.coley.recaf.workspace.model.Workspace;

/**
 * Type loader that pulls classes from a {@link Workspace}.
 *
 * @author xDark
 */
public final class WorkspaceTypeLoader implements ITypeLoader {
	private final WorkspaceTypeIndex index;

	/**
	 * @param workspace
	 * 		Active workspace.
	 */
	public WorkspaceTypeLoader(@Nonnull Workspace workspace) {
		this.index = workspace.getTypeIndex();
	}

	@Override
	public boolean tryLoadType(String internalName, Buffer buffer) {
		byte[] data = index.getBytecode(internalName);
		if (data == null)
			return false;
		buffer.position(0);
		buffer.putByteArray(data, 0, data.length);
		buffer.position(0);
		return true;
	}
}
