package software.coley.recaf.services.decompile.cfr;

import jakarta.annotation.Nonnull;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import software.coley.recaf.services.decompile.index.WorkspaceTypeIndex;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Collection;
import java.util.Collections;

/**
 * CFR class source. Provides access to workspace clases.
 *
 * @author Matt Coley
 */
public class ClassSource implements ClassFileSource {
	private final WorkspaceTypeIndex index;
	private final String targetClassName;
	private final byte[] targetClassBytecode;

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
		this.targetClassName = targetClassName;
		this.targetClassBytecode = targetClassBytecode;
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
		byte[] code;
		if (className.equals(targetClassName)) {
			code = targetClassBytecode;
		} else {
			code = index.getBytecode(className);
		}
		return new Pair<>(code, inputPath);
	}
}
