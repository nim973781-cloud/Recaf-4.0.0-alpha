package software.coley.recaf.services.decompile.procyon;

import com.strobel.assembler.metadata.Buffer;
import com.strobel.assembler.metadata.CompositeTypeLoader;
import com.strobel.assembler.metadata.ITypeLoader;
import jakarta.annotation.Nonnull;

/**
 * Type loader serving the bytecode of a single target class.
 * <p/>
 * Used as the first loader within a {@link CompositeTypeLoader} such that it overrides any following type
 * loader that could also procure the same class info. The target is {@link #retarget(String, byte[]) swappable}
 * so one loader <i>(and the {@link com.strobel.assembler.metadata.MetadataSystem} built over it)</i> can serve
 * a whole session, one class at a time.
 *
 * @author xDark
 * @author Matt Coley
 */
public final class TargetedTypeLoader implements ITypeLoader {
	private String name;
	private byte[] data;

	/**
	 * @param name
	 * 		Internal name of the target class.
	 * @param data
	 * 		Bytecode of the target class.
	 */
	public TargetedTypeLoader(@Nonnull String name, @Nonnull byte[] data) {
		this.name = name;
		this.data = data;
	}

	/**
	 * Points the loader at a new target class. Not thread-safe; callers sequence classes on one thread.
	 *
	 * @param name
	 * 		Internal name of the new target class.
	 * @param data
	 * 		Bytecode of the new target class.
	 */
	public void retarget(@Nonnull String name, @Nonnull byte[] data) {
		this.name = name;
		this.data = data;
	}

	@Override
	public boolean tryLoadType(String internalName, Buffer buffer) {
		if (internalName.equals(name)) {
			byte[] data = this.data;
			buffer.position(0);
			buffer.putByteArray(data, 0, data.length);
			buffer.position(0);
			return true;
		}
		return false;
	}
}
