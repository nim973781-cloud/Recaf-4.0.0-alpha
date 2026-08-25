package software.coley.recaf.services.decompile.cfr;

import jakarta.annotation.Nonnull;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import software.coley.recaf.services.decompile.index.WorkspaceTypeIndex;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * CFR class source. Provides access to workspace clases.
 * <p/>
 * One or more target classes may override what the workspace index would serve for their name, mirroring how
 * the single-class path hands CFR the exact bytecode it was asked to decompile. Session use
 * <i>(see {@link CfrSessionFactory})</i> swaps the override set between chunks through {@link #retarget(Map)}
 * so a single source instance can back a whole run.
 *
 * @author Matt Coley
 */
public class ClassSource implements ClassFileSource {
	private final WorkspaceTypeIndex index;
	private Map<String, byte[]> targets;

	/**
	 * Constructs a CFR class source without target overrides.
	 * Intended for session use, where {@link #retarget(Map)} supplies the overrides per chunk.
	 *
	 * @param workspace
	 * 		Workspace to pull classes from.
	 */
	public ClassSource(@Nonnull Workspace workspace) {
		this.index = workspace.getTypeIndex();
		this.targets = Collections.emptyMap();
	}

	/**
	 * Constructs a CFR class source.
	 *
	 * @param workspace
	 * 		Workspace to pull classes from.
	 * @param targetClassName
	 * 		Name to override.
	 * @param targetClassBytecode
	 * 		Bytecode to override.
	 */
	public ClassSource(@Nonnull Workspace workspace, @Nonnull String targetClassName,
	                   @Nonnull byte[] targetClassBytecode) {
		this.index = workspace.getTypeIndex();
		this.targets = Collections.singletonMap(targetClassName, targetClassBytecode);
	}

	/**
	 * Replaces the target overrides. Not thread-safe; callers sequence chunks on one thread.
	 *
	 * @param targets
	 * 		Map of internal class names to the bytecode overriding the workspace index for that name.
	 */
	public void retarget(@Nonnull Map<String, byte[]> targets) {
		this.targets = targets;
	}

	@Override
	public void informAnalysisRelativePathDetail(String usePath, String specPath) {
	}

	@Override
	public Collection<String> addJar(String jarPath) {
		return Collections.emptySet();
	}

	@Override
	public String getPossiblyRenamedPath(String path) {
		return path;
	}

	@Override
	public Pair<byte[], String> getClassFileContent(String inputPath) {
		String className = inputPath.substring(0, inputPath.indexOf(".class"));
		byte[] code = targets.get(className);
		if (code == null)
			code = index.getBytecode(className);
		return new Pair<>(code, inputPath);
	}
}
