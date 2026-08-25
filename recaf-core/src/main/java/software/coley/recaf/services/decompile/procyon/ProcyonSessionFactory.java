package software.coley.recaf.services.decompile.procyon;

import com.strobel.assembler.metadata.CompositeTypeLoader;
import com.strobel.assembler.metadata.ITypeLoader;
import com.strobel.assembler.metadata.MetadataSystem;
import com.strobel.assembler.metadata.TypeReference;
import com.strobel.decompiler.DecompilationOptions;
import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.PlainTextOutput;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.batch.BatchAccuracyMode;
import software.coley.recaf.services.decompile.batch.session.BatchDecompileSession;
import software.coley.recaf.services.decompile.batch.session.BatchDecompileSessionFactory;
import software.coley.recaf.services.decompile.batch.session.SessionClassResult;
import software.coley.recaf.workspace.model.Workspace;

import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link BatchDecompileSessionFactory} for Procyon.
 * <h2>Sharing granularity</h2>
 * Fast sessions build one {@link MetadataSystem} over one {@link CompositeTypeLoader}
 * <i>(a {@link TargetedTypeLoader retargetable target loader} in front of the workspace loader)</i> and keep
 * both alive for the whole session, so supporting types are resolved once per workspace instead of once per
 * class. The target loader is {@link TargetedTypeLoader#retarget(String, byte[]) repointed} at each class in
 * turn.
 * <h2>Accuracy</h2>
 * {@link BatchAccuracyMode#ACCURATE} sessions mirror {@link ProcyonDecompiler#decompileInternal} exactly,
 * building fresh loaders and a fresh {@link MetadataSystem} per class, so output is byte-identical with the
 * single-class path.
 * <h2>Interruption</h2>
 * The interrupt flag is checked before every
 * {@link com.strobel.decompiler.languages.Language#decompileType decompileType} call.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class ProcyonSessionFactory implements BatchDecompileSessionFactory {
	private final ProcyonConfig config;

	/**
	 * @param config
	 * 		Config shared with {@link ProcyonDecompiler}.
	 */
	@Inject
	public ProcyonSessionFactory(@Nonnull ProcyonConfig config) {
		this.config = config;
	}

	@Nonnull
	@Override
	public String decompilerName() {
		return ProcyonDecompiler.NAME;
	}

	@Nonnull
	@Override
	public BatchDecompileSession open(@Nonnull Workspace workspace, @Nonnull BatchAccuracyMode mode) {
		if (mode == BatchAccuracyMode.ACCURATE)
			return new AccurateSession(workspace);
		return new SharedMetadataSession(workspace);
	}

	/**
	 * Fresh loaders and metadata system per class, byte-identical with {@link ProcyonDecompiler}.
	 */
	private class AccurateSession implements BatchDecompileSession {
		private final Workspace workspace;

		private AccurateSession(@Nonnull Workspace workspace) {
			this.workspace = workspace;
		}

		@Nonnull
		@Override
		public Map<String, SessionClassResult> decompile(@Nonnull List<JvmClassInfo> classes) throws InterruptedException {
			Map<String, SessionClassResult> results = new LinkedHashMap<>(classes.size());
			for (JvmClassInfo info : classes) {
				if (Thread.interrupted())
					throw new InterruptedException("Procyon session interrupted");
				results.put(info.getName(), decompileOne(info));
			}
			return results;
		}

		@Nonnull
		private SessionClassResult decompileOne(@Nonnull JvmClassInfo info) {
			// Mirrors 'ProcyonDecompiler#decompileInternal' step for step. Any deviation here shows up
			// as a byte-level diff in the ACCURATE equivalence gate.
			try {
				String name = info.getName();
				ITypeLoader loader = new CompositeTypeLoader(
						new TargetedTypeLoader(name, info.getBytecode()),
						new WorkspaceTypeLoader(workspace)
				);
				DecompilerSettings settings = config.toSettings();
				settings.setTypeLoader(loader);
				MetadataSystem system = new MetadataSystem(loader);
				TypeReference ref = system.lookupType(name);
				DecompilationOptions decompilationOptions = new DecompilationOptions();
				decompilationOptions.setSettings(settings);
				StringWriter writer = new StringWriter();
				settings.getLanguage().decompileType(ref.resolve(), new PlainTextOutput(writer), decompilationOptions);
				String decompile = writer.toString();
				if (decompile.isEmpty())
					return SessionClassResult.failed(new IllegalStateException("Missing decompilation output"));
				return SessionClassResult.ok(decompile);
			} catch (Throwable t) {
				return SessionClassResult.failed(t);
			}
		}
	}

	/**
	 * One metadata system for the whole session, retargeting the target loader per class.
	 */
	private class SharedMetadataSession implements BatchDecompileSession {
		private final TargetedTypeLoader targetLoader = new TargetedTypeLoader("", new byte[0]);
		private final DecompilerSettings settings;
		private final DecompilationOptions options;
		private final MetadataSystem metadataSystem;

		private SharedMetadataSession(@Nonnull Workspace workspace) {
			ITypeLoader loader = new CompositeTypeLoader(targetLoader, new WorkspaceTypeLoader(workspace));
			settings = config.toSettings();
			settings.setTypeLoader(loader);
			metadataSystem = new MetadataSystem(loader);
			options = new DecompilationOptions();
			options.setSettings(settings);
		}

		@Nonnull
		@Override
		public Map<String, SessionClassResult> decompile(@Nonnull List<JvmClassInfo> classes) throws InterruptedException {
			Map<String, SessionClassResult> results = new LinkedHashMap<>(classes.size());
			for (JvmClassInfo info : classes) {
				if (Thread.interrupted())
					throw new InterruptedException("Procyon session interrupted");
				results.put(info.getName(), decompileOne(info));
			}
			return results;
		}

		@Nonnull
		private SessionClassResult decompileOne(@Nonnull JvmClassInfo info) {
			try {
				String name = info.getName();
				targetLoader.retarget(name, info.getBytecode());
				TypeReference ref = metadataSystem.lookupType(name);
				if (ref == null)
					return SessionClassResult.failed(new IllegalStateException("Type not found: " + name));
				StringWriter writer = new StringWriter();
				settings.getLanguage().decompileType(ref.resolve(), new PlainTextOutput(writer), options);
				String decompile = writer.toString();
				if (decompile.isEmpty())
					return SessionClassResult.failed(new IllegalStateException("Missing decompilation output"));
				return SessionClassResult.ok(decompile);
			} catch (Throwable t) {
				return SessionClassResult.failed(t);
			}
		}
	}
}
