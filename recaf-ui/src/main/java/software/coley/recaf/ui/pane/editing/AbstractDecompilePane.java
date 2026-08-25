package software.coley.recaf.ui.pane.editing;

import atlantafx.base.controls.Spacer;
import atlantafx.base.theme.Styles;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import javafx.animation.PauseTransition;
import javafx.animation.Transition;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.util.Duration;
import org.fxmisc.richtext.CodeArea;
import org.slf4j.Logger;
import software.coley.observables.ObservableBoolean;
import software.coley.observables.ObservableObject;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.info.properties.builtin.RemapOriginTaskProperty;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.decompile.NoopJvmDecompiler;
import software.coley.recaf.services.info.association.FileTypeSyntaxAssociationService;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.services.mapping.Mappings;
import software.coley.recaf.services.navigation.ClassNavigable;
import software.coley.recaf.services.navigation.Navigable;
import software.coley.recaf.services.navigation.UpdatableNavigable;
import software.coley.recaf.services.source.AstMapper;
import software.coley.recaf.services.source.AstService;
import software.coley.recaf.services.source.ResolverAdapter;
import software.coley.recaf.ui.control.BoundLabel;
import software.coley.recaf.ui.control.richtext.Editor;
import software.coley.recaf.ui.control.richtext.bracket.SelectedBracketTracking;
import software.coley.recaf.ui.control.richtext.problem.ProblemTracking;
import software.coley.recaf.ui.control.richtext.search.SearchBar;
import software.coley.recaf.ui.control.richtext.source.JavaContextActionSupport;
import software.coley.recaf.ui.pane.editing.android.AndroidDecompilerPane;
import software.coley.recaf.ui.pane.editing.jvm.DecompilerPaneConfig;
import software.coley.recaf.ui.pane.editing.jvm.JvmDecompilerPane;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.util.Lang;
import software.coley.recaf.util.StringDiff;
import software.coley.recaf.util.StringUtil;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.sourcesolver.model.CompilationUnitModel;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Common outline for decompiler panes utilizing {@link JvmDecompiler}.
 * <br>
 * For Android content it is assumed {@link AndroidClassInfo#asJvmClass()} is implemented as a conversion process.
 *
 * @author Matt Coley
 * @see JvmDecompilerPane
 * @see AndroidDecompilerPane
 */
public class AbstractDecompilePane extends BorderPane implements ClassNavigable, UpdatableNavigable {
	private static final Logger logger = Logging.get(AbstractDecompilePane.class);
	protected final ObservableObject<JvmDecompiler> decompiler = new ObservableObject<>(NoopJvmDecompiler.getInstance());
	protected final ObservableBoolean decompileOutputErrored = new ObservableBoolean(false);
	protected final ObservableBoolean decompileInProgress = new ObservableBoolean(false);
	protected final AtomicBoolean updateLock = new AtomicBoolean();
	/** Incremented per decompilation request so that only the newest result is applied to the {@link #editor}. */
	protected final AtomicInteger decompileRequestCounter = new AtomicInteger();
	private final PauseTransition decompileConfigDebounce = new PauseTransition(Duration.millis(150));
	protected final ProblemTracking problemTracking = new ProblemTracking();
	protected final AstService astService;
	protected final JavaContextActionSupport contextActionSupport;
	protected final DecompilerManager decompilerManager;
	protected final DecompilerPaneConfig config;
	protected final Editor editor;
	protected ClassPathNode path;

	protected AbstractDecompilePane(@Nonnull DecompilerPaneConfig config,
	                                @Nonnull SearchBar searchBar,
	                                @Nonnull AstService astService,
	                                @Nonnull JavaContextActionSupport contextActionSupport,
	                                @Nonnull FileTypeSyntaxAssociationService languageAssociation,
	                                @Nonnull DecompilerManager decompilerManager) {
		this.astService = astService;
		this.contextActionSupport = contextActionSupport;
		this.decompilerManager = decompilerManager;
		this.config = config;

		decompiler.setValue(decompilerManager.getTargetJvmDecompiler());
		decompileConfigDebounce.setOnFinished(event -> {
			if (path != null)
				decompile();
		});
		decompiler.addChangeListener((ob, old, cur) -> scheduleConfigDecompile());
		for (JvmDecompiler implementation : decompilerManager.getJvmDecompilers())
			implementation.getConfig().getValues().values().forEach(value ->
					value.getObservable().addChangeListener((ob, old, cur) -> {
						if (decompiler.getValue() == implementation)
							scheduleConfigDecompile();
					}));

		// Configure the editor
		editor = new Editor();
		languageAssociation.configureEditorSyntax("java", editor);
		editor.setSelectedBracketTracking(new SelectedBracketTracking());
		editor.setProblemTracking(problemTracking);
		editor.getRootLineGraphicFactory().addDefaultCodeGraphicFactories();
		contextActionSupport.install(editor);
		searchBar.install(editor);

		// Add overlay for when decompilation is in-progress
		DecompileProgressOverlay overlay = new DecompileProgressOverlay();
		decompileInProgress.addAsyncChangeListener((ob, old, cur) -> {
			ObservableList<Node> children = editor.getPrimaryStack().getChildren();
			if (cur) children.add(overlay);
			else children.remove(overlay);
		}, FxThreadUtil.executor());

		// Layout
		setCenter(editor);
	}

	@Nonnull
	@Override
	public ClassPathNode getPath() {
		return path;
	}

	@Nonnull
	@Override
	public ClassPathNode getClassPath() {
		return path;
	}

	@Override
	public void requestFocus(@Nonnull ClassMember member) {
		contextActionSupport.select(member);
	}

	@Nonnull
	@Override
	public Collection<Navigable> getNavigableChildren() {
		return Collections.singleton(contextActionSupport);
	}

	@Override
	public void disable() {
		decompileConfigDebounce.stop();
		setDisable(true);
		setOnKeyPressed(null);
		editor.close();
		contextActionSupport.close();
	}

	@Override
	public void onUpdatePath(@Nonnull PathNode<?> path) {
		// Pass to children
		for (Navigable navigableChild : getNavigableChildren())
			if (navigableChild instanceof UpdatableNavigable updatableNavigable)
				updatableNavigable.onUpdatePath(path);

		// Handle updates to the decompiled code.
		if (!updateLock.get() && path instanceof ClassPathNode classPathNode) {
			this.path = classPathNode;
			ClassInfo classInfo = classPathNode.getValue();

			// Check if the class is an Android class and can be mapped.
			if (!classInfo.isJvmClass() && classInfo.isAndroidClass()) {
				AndroidClassInfo androidInfo = classInfo.asAndroidClass();
				if (androidInfo.canMapToJvmClass())
					classInfo = androidInfo.asJvmClass();
				else
					throw new IllegalStateException("Decompiler component received non-convertible Android class");
			}

			// Check if we can update the text efficiently with a remapper.
			// If not, then schedule a decompilation instead.
			if (!config.getUseMappingAcceleration().getValue() || !handleRemapUpdate(classInfo))
				decompile();
		}
	}

	/**
	 * Associates the given {@link ToolsContainerComponent} with this decompile pane's {@link #editor}.
	 *
	 * @param toolsContainer
	 * 		Tool container to install.
	 */
	protected void installToolsContainer(@Nonnull ToolsContainerComponent toolsContainer) {
		DecompileFailureButton failureButton = new DecompileFailureButton();
		decompileOutputErrored.addChangeListener((ob, old, cur) -> {
			failureButton.setVisible(cur);
			if (cur) failureButton.animate();
		});

		toolsContainer.install(editor);
		toolsContainer.add(contextActionSupport.getAvailabilityButton());
		toolsContainer.addLast(failureButton);
	}

	/**
	 * Attempts to update the {@link #editor}'s text with intent-specific AST operations.
	 * This includes:
	 * <ul>
	 *     <li>{@link AstMapper} when handling classes marked with {@link RemapOriginTaskProperty}</li>
	 * </ul>
	 *
	 * @param classInfo
	 * 		Modified class.
	 *
	 * @return {@code true} when we were able to handle it with an AST visitors.
	 */
	private boolean handleRemapUpdate(@Nonnull ClassInfo classInfo) {
		// Attempt to handle change with an AST mapping visitor if the change is originating
		// from a mapping application job.
		String currentText = editor.getText();
		if (!currentText.isBlank()) {
			MappingResults mappingOrigin = classInfo.getPropertyValueOrNull(RemapOriginTaskProperty.KEY);
			if (mappingOrigin != null) {
				Mappings mappings = mappingOrigin.getMappings();

				// We can handle the update with AST mapping instead of decompiling the class again.
				CompilationUnitModel unit = contextActionSupport.getUnit();
				Workspace workspace = path.getValueOfType(Workspace.class);
				if (unit != null && workspace != null) {
					ResolverAdapter resolver = astService.newJavaResolver(workspace, unit);
					resolver.setClassContext(getPath().getValue());
					String modifiedSource = astService.applyMappings(unit, resolver, mappings);

					// If there were no changes made then the AST service failed to make dynamic changes.
					// This can happen when the classes being mapped are not in the workspace, but instead
					// belong to 3rd party libraries not present in the workspace.
					if (currentText.equals(modifiedSource))
						return false;

					// We want to get the difference between the current and modified text and update only
					// the areas of the text that are modified. In most situations this will be much faster
					// than re-assigning the whole text (which will require restyling the entire document)
					List<StringDiff.Diff> diffs = StringDiff.diff(currentText, modifiedSource);
					FxThreadUtil.run(() -> {
						// Track where caret was.
						CodeArea area = editor.getCodeArea();
						int currentParagraph = area.getCurrentParagraph();
						int currentColumn = area.getCaretColumn();

						// Apply diffs.
						for (int i = diffs.size() - 1; i >= 0; i--) {
							StringDiff.Diff diff = diffs.get(i);
							if (diff.type() == StringDiff.DiffType.CHANGE)
								area.replaceText(diff.startA(), diff.endA(), diff.textB());
							else if (diff.type() == StringDiff.DiffType.INSERT)
								area.insertText(diff.startA(), diff.textB());
							else if (diff.type() == StringDiff.DiffType.REMOVE)
								area.replaceText(diff.startA(), diff.endA(), "");
						}

						// Reset caret.
						area.moveTo(currentParagraph, currentColumn);
					});
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Decompiles the class contained by the current {@link #path} and updates the {@link #editor}'s text
	 * with the decompilation results.
	 */
	public void decompile() {
		ClassPathNode requestPath = path;
		if (requestPath == null)
			return;
		Workspace workspace = requestPath.getValueOfType(Workspace.class);
		JvmClassInfo classInfo = requestPath.getValue().asJvmClass();
		JvmDecompiler requestDecompiler = decompiler.getValue();
		int timeoutSeconds = config.getTimeoutSeconds().getValue();

		// Tag the request so that results arriving after the user has moved on can be discarded.
		int requestId = decompileRequestCounter.incrementAndGet();

		// Schedule decompilation task, update the editor's text asynchronously on the JavaFX UI thread when complete.
		decompileInProgress.setValue(true);
		editor.setMouseTransparent(true);
		decompilerManager.decompile(requestDecompiler, workspace, classInfo)
				.orTimeout(timeoutSeconds, TimeUnit.SECONDS)
				.whenCompleteAsync((result, throwable) -> {
					// A newer request is still running, so it owns the in-progress state and the editor content.
					if (decompileRequestCounter.get() != requestId)
						return;

					editor.setMouseTransparent(false);
					decompileInProgress.setValue(false);

					// Drop results belonging to a class the pane no longer shows. Without this a slow decompilation
					// of an old class can overwrite the editor content of the current one.
					if (!isCurrentClass(requestPath))
						return;

					// The timeout is turned into a result here rather than being pre-computed, so the message always
					// describes the class this request was actually made for.
					if (isTimeout(throwable)) {
						decompilerManager.cancelHard(requestDecompiler, workspace, classInfo);
						result = timeoutResult(classInfo, requestDecompiler, timeoutSeconds);
						throwable = null;
					}

					// Handle uncaught exceptions
					if (throwable != null) {
						String trace = StringUtil.traceToString(throwable);
						editor.setText("/*\nUncaught exception when decompiling:\n" + trace + "\n*/");
						return;
					}

					// Handle decompilation result
					String text = result.getText();
					if (Objects.equals(text, editor.getText()))
						return; // Skip if existing text is the same
					DecompileResult.ResultType resultType = result.getType();
					decompileOutputErrored.setValue(resultType == DecompileResult.ResultType.FAILURE);
					switch (resultType) {
						case SUCCESS -> editor.setText(text);
						case SKIPPED -> editor.setText(text == null ? "// Decompilation skipped" : text);
						case FAILURE -> {
							Throwable exception = result.getException();
							if (exception != null) {
								String trace = StringUtil.traceToString(exception);
								editor.setText("/*\nDecompile failed:\n" + trace + "\n*/");
							} else {
								editor.setText("/*\nDecompile failed, but no trace was attached.\n*/");
							}
						}
					}

					// Schedule AST parsing for context action support.
					contextActionSupport.scheduleAstParse();

					// Prevent undo from reverting to empty state.
					editor.getCodeArea().getUndoManager().forgetHistory();
				}, FxThreadUtil.executor());
	}

	private void scheduleConfigDecompile() {
		FxThreadUtil.run(() -> {
			decompileConfigDebounce.stop();
			decompileConfigDebounce.playFromStart();
		});
	}

	private static boolean isTimeout(@Nullable Throwable throwable) {
		if (throwable instanceof CompletionException completion)
			throwable = completion.getCause();
		return throwable instanceof TimeoutException;
	}

	/**
	 * @param requestPath
	 * 		Path a decompilation request was made for.
	 *
	 * @return {@code true} when the pane still shows the class the request was made for.
	 */
	private boolean isCurrentClass(@Nonnull ClassPathNode requestPath) {
		ClassPathNode currentPath = path;
		return currentPath == null || Objects.equals(currentPath.getValue().getName(), requestPath.getValue().getName());
	}

	/**
	 * @param info
	 * 		Class that timed out.
	 * @param jvmDecompiler
	 * 		Decompiler that was used.
	 * @param timeoutSeconds
	 * 		Configured timeout in seconds.
	 *
	 * @return Result made for timed out decompilations.
	 */
	@Nonnull
	private static DecompileResult timeoutResult(@Nonnull JvmClassInfo info,
	                                             @Nonnull JvmDecompiler jvmDecompiler,
	                                             int timeoutSeconds) {
		return new DecompileResult("""
				// Decompilation timed out.
				//  - Class name: %s
				//  - Class size: %d bytes
				//  - Decompiler: %s - %s
				//  - Timeout: %d seconds
				//
				// Suggestions:
				//  - Increase timeout
				//  - Change decompilers in 'config' or bottom right (i)
				//  - Deobfuscate heavily obfuscated code and try again
				//
				// Reminder:
				//  - Class information is still available on the side panels ==>
				""".formatted(info.getName(),
				info.getBytecode().length,
				jvmDecompiler.getName(), jvmDecompiler.getVersion(),
				timeoutSeconds
		));
	}

	/**
	 * And overlay shown while a class is being decompiled.
	 */
	private class DecompileProgressOverlay extends VBox {
		private DecompileProgressOverlay() {
			Label title = new BoundLabel(Lang.getBinding("java.decompiling"));
			Label text = new Label();
			title.getStyleClass().add(Styles.TITLE_3);
			text.getStyleClass().add(Styles.TEXT_SUBTLE);
			text.setFont(new Font("JetBrains Mono", 12)); // Pulling from CSS applied to the editor.

			// Layout
			getChildren().addAll(new Spacer(Orientation.VERTICAL), title, text, new Spacer(Orientation.VERTICAL));
			getStyleClass().addAll("background");
			setFillWidth(true);
			setAlignment(Pos.CENTER);

			// Setup transition to play whenever decompilation is in progress.
			BytecodeTransition transition = new BytecodeTransition(text);
			decompileInProgress.addAsyncChangeListener((ob, old, cur) -> {
				setVisible(cur);
				if (cur) {
					transition.update(path.getValue().asJvmClass());
					transition.play();
				} else
					transition.stop();
			}, FxThreadUtil.executor());
		}

		private static class BytecodeTransition extends Transition {
			private final Labeled labeled;
			private byte[] bytecode;

			/**
			 * @param labeled
			 * 		Target label.
			 */
			public BytecodeTransition(@Nonnull Labeled labeled) {
				this.labeled = labeled;
			}

			/**
			 * @param info
			 * 		Class to show bytecode of.
			 */
			public void update(@Nonnull JvmClassInfo info) {
				this.bytecode = info.getBytecode();
				setCycleDuration(Duration.millis(bytecode.length));
			}

			@Override
			protected void interpolate(double fraction) {
				int bytecodeSize = bytecode.length;
				int textLength = 18;
				int middle = (int) (fraction * bytecodeSize);
				int start = middle - (textLength / 2);
				int end = middle + (textLength / 2);

				// We have two rows, top for hex, bottom for text.
				StringBuilder sbHex = new StringBuilder();
				StringBuilder sbText = new StringBuilder();
				for (int i = start; i < end; i++) {
					if (i < 0) {
						sbHex.append("   ");
						sbText.append("   ");
					} else if (i >= bytecodeSize) {
						sbHex.append(" ..");
						sbText.append(" ..");
					} else {
						short b = (short) (bytecode[i] & 0xFF);
						char c = (char) b;
						if (Character.isWhitespace(c)) c = ' ';
						else if (c < 32) c = '?';
						String hex = StringUtil.limit(Integer.toHexString(b).toUpperCase(), 2);
						if (hex.length() == 1) hex = "0" + hex;
						sbHex.append(StringUtil.fillLeft(3, " ", hex));
						sbText.append(StringUtil.fillLeft(3, " ", String.valueOf(c)));
					}
				}
				labeled.setText(sbHex + "\n" + sbText);
			}
		}
	}
}
