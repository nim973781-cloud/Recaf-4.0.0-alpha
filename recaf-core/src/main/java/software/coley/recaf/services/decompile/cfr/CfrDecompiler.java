package software.coley.recaf.services.decompile.cfr;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredComment;
import org.benf.cfr.reader.util.CfrVersionInfo;
import org.benf.cfr.reader.util.DecompilerComment;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.AbstractJvmDecompiler;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.util.ReflectUtil;
import software.coley.recaf.workspace.model.Workspace;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * CFR decompiler implementation.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class CfrDecompiler extends AbstractJvmDecompiler {
	public static final String NAME = "CFR";
	private final CfrConfig config;
	private volatile int optionsHash;
	private volatile Map<String, String> options;

	/**
	 * New CFR decompiler instance.
	 *
	 * @param workspaceManager
	 * 		Workspace manager.
	 * @param config
	 * 		Config instance.
	 */
	@Inject
	public CfrDecompiler(@Nonnull WorkspaceManager workspaceManager, @Nonnull CfrConfig config) {
		super(NAME, CfrVersionInfo.VERSION, config);
		this.config = config;

		workspaceManager.addWorkspaceCloseListener(workspace -> cleanup());

		// TODO: Update CFR when https://github.com/leibnitz27/cfr/issues/361 is fixed
	}

	@Nonnull
	@Override
	protected DecompileResult decompileInternal(@Nonnull Workspace workspace, @Nonnull JvmClassInfo classInfo) {
		String name = classInfo.getName();
		byte[] bytecode = classInfo.getBytecode();
		int configHash = getConfig().getHash();
		ClassSource source = new ClassSource(workspace, name, bytecode);
		SinkFactoryImpl sink = new SinkFactoryImpl();

		// A driver is never shared between classes, but the options behind it only change when the config does.
		CfrDriver driver = new CfrDriver.Builder()
				.withClassFileSource(source)
				.withOutputSink(sink)
				.withOptions(options(configHash))
				.build();
		driver.analyse(Collections.singletonList(name));
		String decompile = sink.getDecompilation();
		if (decompile == null) {
			Throwable exception = Objects.requireNonNullElseGet(sink.getException(), () -> {
				Throwable err = new IllegalStateException("CFR did not provide any output:\n- No decompilation output\n- No error message / trace");
				err.setStackTrace(new StackTraceElement[0]);
				return err;
			});
			return new DecompileResult(exception, configHash);
		}
		return new DecompileResult(stripHeader(decompile), configHash);
	}

	@Nonnull
	@Override
	public CfrConfig getConfig() {
		return (CfrConfig) super.getConfig();
	}

	/**
	 * @param configHash
	 * 		Current {@link CfrConfig#getHash() config hash}.
	 *
	 * @return CFR options for the config. CFR only reads from the map, so one instance can back any number
	 * of drivers as long as the config behind it is unchanged.
	 */
	@Nonnull
	private Map<String, String> options(int configHash) {
		Map<String, String> current = options;
		if (current == null || optionsHash != configHash) {
			current = config.toMap();
			options = current;
			optionsHash = configHash;
		}
		return current;
	}

	private static void cleanup() {
		// Some CFR code through a chain of events assigns a container to this constant, and it
		// holds a reference to our ClassSource which has the workspace data in it.
		// That causes a memory leak, so we clear the container here after each decomp.
		StructuredComment.EMPTY_COMMENT.setContainer(null);
	}

	/**
	 * @param decompile
	 * 		Raw CFR output.
	 *
	 * @return Output with the leading 'Decompiled with CFR' comment header removed.
	 * Shared with {@link CfrSessionFactory} so batch output matches this path byte for byte.
	 */
	@Nonnull
	static String stripHeader(@Nonnull String decompile) {
		// CFR emits a 'Decompiled with CFR' header, which is annoying, so we'll remove that.
		int commentStart = decompile.indexOf("/*\n");
		int commentEnd = decompile.indexOf(" */\n");
		if (commentStart >= 0 && commentEnd > commentStart)
			decompile = decompile.substring(0, commentStart) + decompile.substring(commentEnd + 4);
		return decompile;
	}

	static {
		try {
			// Rewrite CFR comments to not say "use --option" since this is not a command line context.
			Field field = ReflectUtil.getDeclaredField(DecompilerComment.class, "comment");
			ReflectUtil.quietSet(DecompilerComment.RENAME_MEMBERS, field, "Duplicate member names detected");
			ReflectUtil.quietSet(DecompilerComment.ILLEGAL_IDENTIFIERS, field, "Illegal identifiers detected");
			ReflectUtil.quietSet(DecompilerComment.MALFORMED_SWITCH, field, "Recovered potentially malformed switches");
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}
}
