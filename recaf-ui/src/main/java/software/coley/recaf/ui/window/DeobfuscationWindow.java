package software.coley.recaf.ui.window;

import atlantafx.base.controls.Spacer;
import atlantafx.base.theme.Styles;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import me.darknet.assembler.error.Error;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import software.coley.collections.Unchecked;
import software.coley.recaf.analytics.logging.DebuggingLogger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.assembler.JvmAssemblerPipeline;
import software.coley.recaf.services.cell.CellConfigurationService;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.deobfuscation.transform.generic.CycleClassRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.DeadCodeRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.DuplicateAnnotationRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.DuplicateCatchMergingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.EnumNameRestorationTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.ExceptionCollectionTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.GotoInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.IllegalAnnotationRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.JunkCodeRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.IllegalNameMappingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.IllegalSignatureRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.IllegalVarargsRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.KotlinMetadataCollectionTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.KotlinNameRestorationTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.LongAnnotationRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.LongExceptionRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.OpaqueConstantFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.OpaquePredicateFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.RedundantTryCatchRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.SourceNameRestorationTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.StaticValueCollectionTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.StringDecryptionTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.StaticValueInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.VariableFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.VariableTableNormalizingTransformer;
import software.coley.recaf.services.info.association.FileTypeSyntaxAssociationService;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.services.transform.ClassTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformResult;
import software.coley.recaf.services.transform.TransformationApplier;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.services.transform.TransformationManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.ui.LanguageStylesheets;
import software.coley.recaf.ui.config.WorkspaceExplorerConfig;
import software.coley.recaf.ui.control.ActionButton;
import software.coley.recaf.ui.control.BoundIntSpinner;
import software.coley.recaf.ui.control.BoundLabel;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.ui.control.ReorderableListCell;
import software.coley.recaf.ui.control.popup.ClassSelectionPopup;
import software.coley.recaf.ui.control.richtext.Editor;
import software.coley.recaf.ui.control.richtext.bracket.SelectedBracketTracking;
import software.coley.recaf.ui.control.richtext.search.SearchBar;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.util.Lang;
import software.coley.recaf.util.StringUtil;
import software.coley.recaf.util.threading.Batch;
import software.coley.recaf.util.threading.ThreadPoolFactory;
import software.coley.recaf.util.threading.ThreadUtil;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Pane for previewing deobfuscation transformers.
 *
 * @author Matt Coley
 */
@Dependent
public class DeobfuscationWindow extends RecafStage {
	private static final DebuggingLogger logger = Logging.get(DeobfuscationWindow.class);

	/** Mapping from transformer class to translation key for localized names */
	private static final Map<Class<? extends ClassTransformer>, String> TRANSFORMER_TRANSLATION_KEYS = new HashMap<>();
	static {
		TRANSFORMER_TRANSLATION_KEYS.put(CycleClassRemovingTransformer.class, "deobf.transformer.cycleclass");
		TRANSFORMER_TRANSLATION_KEYS.put(DeadCodeRemovingTransformer.class, "deobf.transformer.deadcode");
		TRANSFORMER_TRANSLATION_KEYS.put(DuplicateAnnotationRemovingTransformer.class, "deobf.transformer.duplicateannotation");
		TRANSFORMER_TRANSLATION_KEYS.put(DuplicateCatchMergingTransformer.class, "deobf.transformer.duplicatecatch");
		TRANSFORMER_TRANSLATION_KEYS.put(EnumNameRestorationTransformer.class, "deobf.transformer.enumname");
		TRANSFORMER_TRANSLATION_KEYS.put(ExceptionCollectionTransformer.class, "deobf.transformer.exceptioncollection");
		TRANSFORMER_TRANSLATION_KEYS.put(GotoInliningTransformer.class, "deobf.transformer.gotoinlining");
		TRANSFORMER_TRANSLATION_KEYS.put(IllegalAnnotationRemovingTransformer.class, "deobf.transformer.illegalannotation");
		TRANSFORMER_TRANSLATION_KEYS.put(IllegalNameMappingTransformer.class, "deobf.transformer.illegalname");
		TRANSFORMER_TRANSLATION_KEYS.put(IllegalSignatureRemovingTransformer.class, "deobf.transformer.illegalsignature");
		TRANSFORMER_TRANSLATION_KEYS.put(IllegalVarargsRemovingTransformer.class, "deobf.transformer.illegalvarargs");
		TRANSFORMER_TRANSLATION_KEYS.put(KotlinMetadataCollectionTransformer.class, "deobf.transformer.kotlinmetadata");
		TRANSFORMER_TRANSLATION_KEYS.put(KotlinNameRestorationTransformer.class, "deobf.transformer.kotlinname");
		TRANSFORMER_TRANSLATION_KEYS.put(LongAnnotationRemovingTransformer.class, "deobf.transformer.longannotation");
		TRANSFORMER_TRANSLATION_KEYS.put(LongExceptionRemovingTransformer.class, "deobf.transformer.longexception");
		TRANSFORMER_TRANSLATION_KEYS.put(OpaqueConstantFoldingTransformer.class, "deobf.transformer.opaqueconstant");
		TRANSFORMER_TRANSLATION_KEYS.put(OpaquePredicateFoldingTransformer.class, "deobf.transformer.opaquepredicate");
		TRANSFORMER_TRANSLATION_KEYS.put(RedundantTryCatchRemovingTransformer.class, "deobf.transformer.redundanttrycatch");
		TRANSFORMER_TRANSLATION_KEYS.put(SourceNameRestorationTransformer.class, "deobf.transformer.sourcename");
		TRANSFORMER_TRANSLATION_KEYS.put(StaticValueCollectionTransformer.class, "deobf.transformer.staticvaluecollection");
		TRANSFORMER_TRANSLATION_KEYS.put(StaticValueInliningTransformer.class, "deobf.transformer.staticvalueinlining");
		TRANSFORMER_TRANSLATION_KEYS.put(VariableFoldingTransformer.class, "deobf.transformer.variablefolding");
		TRANSFORMER_TRANSLATION_KEYS.put(VariableTableNormalizingTransformer.class, "deobf.transformer.variabletable");
		TRANSFORMER_TRANSLATION_KEYS.put(JunkCodeRemovingTransformer.class, "deobf.transformer.junkcode");
		TRANSFORMER_TRANSLATION_KEYS.put(StringDecryptionTransformer.class, "deobf.transformer.stringdecrypt");
	}

	/**
	 * Gets the localized name for a transformer.
	 * @param transformer The transformer to get the name for.
	 * @return The localized name, or the transformer's default name if no translation is available.
	 */
	private static String getTransformerName(@Nonnull ClassTransformer transformer) {
		String key = TRANSFORMER_TRANSLATION_KEYS.get(transformer.getClass());
		if (key != null) {
			return Lang.get(key);
		}
		return transformer.name();
	}
	private final TransformationManager transformationManager;
	private final TransformationApplierService transformationApplierService;
	private final DecompilerManager decompilerManager;
	private final AssemblerPipelineManager assemblerPipelineManager;
	private final ObservableList<CategorizedTransformer> transformerOrder = FXCollections.observableArrayList();
	private final BooleanProperty hasSelection = new SimpleBooleanProperty();
	private final IntegerProperty maxPasses = new SimpleIntegerProperty(5);
	private final WorkspaceManager workspaceManager;

	@Inject
	@SuppressWarnings("unchecked")
	public DeobfuscationWindow(@Nonnull TransformationManager transformationManager,
	                           @Nonnull TransformationApplierService transformationApplierService,
	                           @Nonnull WorkspaceManager workspaceManager,
	                           @Nonnull DecompilerManager decompilerManager,
	                           @Nonnull AssemblerPipelineManager assemblerPipelineManager,
	                           @Nonnull FileTypeSyntaxAssociationService languageAssociation,
	                           @Nonnull CellConfigurationService configurationService,
	                           @Nonnull Actions actions,
	                           @Nonnull WorkspaceExplorerConfig explorerConfig,
	                           @Nonnull Instance<SearchBar> searchBarProvider) {
		this.transformationManager = transformationManager;
		this.transformationApplierService = transformationApplierService;
		this.workspaceManager = workspaceManager;
		this.decompilerManager = decompilerManager;
		this.assemblerPipelineManager = assemblerPipelineManager;

		BorderPane transformerTreePane = new BorderPane();
		TreeView<Selection> transformerTree = new TreeView<>();
		{
			BoundLabel title = new BoundLabel(Lang.getBinding("deobf.selection.title"));
			title.getStyleClass().add(Styles.TITLE_4);
			title.setPadding(new Insets(0, 0, 5, 5));

			// Select All / Deselect All buttons
			Button selectAllBtn = new ActionButton(CarbonIcons.CHECKBOX_CHECKED, Lang.getBinding("deobf.selection.selectall"), () -> {
				selectAllTransformers(transformerTree.getRoot(), true);
				transformerTree.refresh();
			});
			Button deselectAllBtn = new ActionButton(CarbonIcons.CHECKBOX, Lang.getBinding("deobf.selection.deselectall"), () -> {
				transformerOrder.clear();
				hasSelection.set(false);
				transformerTree.refresh();
			});
			selectAllBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.SMALL);
			deselectAllBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.SMALL);
			HBox titleBar = new HBox(title, new Spacer(), selectAllBtn, deselectAllBtn);
			titleBar.setAlignment(Pos.CENTER_LEFT);
			titleBar.setSpacing(5);
			titleBar.setPadding(new Insets(0, 5, 5, 0));

			// TODO: It would be nice to have the tree be auto-generated in some fashion.
			//  But we may not want a flat category system, so an enum probably is too short-sighted.
			//  It also shouldn't be annoying to configure for each transformer.
			TreeItem<Selection> root = new TreeItem<>(new Selection.Root());
			TreeItem<Selection> generic = new TreeItem<>(new Selection.Category("deobf.tree.generic", CarbonIcons.CIRCLE_DASH));
			TreeItem<Selection> anti = new TreeItem<>(new Selection.Category("deobf.tree.generic.anticrasher", CarbonIcons.DEBUG));
			anti.getChildren().addAll(of(
					CycleClassRemovingTransformer.class,
					DuplicateAnnotationRemovingTransformer.class,
					LongAnnotationRemovingTransformer.class,
					LongExceptionRemovingTransformer.class,
					IllegalAnnotationRemovingTransformer.class,
					IllegalSignatureRemovingTransformer.class,
					IllegalVarargsRemovingTransformer.class
			));
			TreeItem<Selection> optimize = new TreeItem<>(new Selection.Category("deobf.tree.generic.optimize", CarbonIcons.CLEAN));
			optimize.getChildren().addAll(of(
					DeadCodeRemovingTransformer.class,
					DuplicateCatchMergingTransformer.class,
					GotoInliningTransformer.class,
					OpaqueConstantFoldingTransformer.class,
					OpaquePredicateFoldingTransformer.class,
					RedundantTryCatchRemovingTransformer.class,
					StaticValueInliningTransformer.class,
					VariableFoldingTransformer.class,
					VariableTableNormalizingTransformer.class
			));
			TreeItem<Selection> restoration = new TreeItem<>(new Selection.Category("deobf.tree.generic.restoration", CarbonIcons.AI_RESULTS));
			restoration.getChildren().addAll(of(
					EnumNameRestorationTransformer.class,
					KotlinNameRestorationTransformer.class,
					SourceNameRestorationTransformer.class
			));
			generic.getChildren().addAll(
					anti,
					optimize,
					restoration
			);
			TreeItem<Selection> specific = new TreeItem<>(new Selection.Category("deobf.tree.specific", CarbonIcons.CENTER_CIRCLE));
			root.getChildren().addAll(
					generic,
					specific
			);
			generic.setExpanded(true);
			specific.setExpanded(true);

				transformerTree.setCellFactory(view -> new TreeCell<>() {
				protected void updateItem(Selection item, boolean isEmpty) {
					super.updateItem(item, isEmpty);

					if (item == null || isEmpty) {
						setText(null);
						setGraphic(null);
						return;
					}

					switch (item) {
						case Selection.Root ir -> {
							// Not intended to be shown.
							setText(null);
							setGraphic(null);
						}
						case Selection.Category ic -> {
							setText(Lang.get(ic.key));
							setGraphic(new FontIconView(ic.icon));
						}
						case Selection.Transformer it -> {
							ClassTransformer transformer = it.transformer();
							Class<? extends ClassTransformer> transformerClass = transformer.getClass();
							CheckBox toggle = new CheckBox();
							toggle.setSelected(transformerOrder.stream().anyMatch(i -> i.matches(transformerClass)));
							toggle.selectedProperty().addListener((ob, old, cur) -> {
								if (cur) {
									Selection.Category parentCategory = Unchecked.cast(getTreeItem().getParent().getValue());
									transformerOrder.add(new CategorizedTransformer(it, parentCategory));
								} else {
									transformerOrder.removeIf(i -> i.matches(transformerClass));
								}
								hasSelection.set(!transformerOrder.isEmpty());
							});
							setText(getTransformerName(transformer));
							setGraphic(toggle);
						}
					}
				}
			});
			transformerTree.setRoot(root);
			transformerTree.setShowRoot(false);
			transformerTreePane.setTop(titleBar);
			transformerTreePane.setCenter(transformerTree);

			// TODO: Having the ability to load/save presets would be nice.
			//  - We should offer a basic preset with *everything* turned on but with the most optimal order pre-defined
		}
		BorderPane transformerOrderPane = new BorderPane();
		{
			BoundLabel title = new BoundLabel(Lang.getBinding("deobf.order.title"));
			title.getStyleClass().add(Styles.TITLE_4);
			title.setPadding(new Insets(0, 0, 5, 5));

			BoundLabel hint = new BoundLabel(Lang.getBinding("deobf.order.hint"));
			hint.setTextAlignment(TextAlignment.CENTER);
			ListView<CategorizedTransformer> transformerOrderList = new ListView<>(transformerOrder);
			transformerOrderList.setCellFactory(view -> new ReorderableListCell<>() {
				@Override
				public void updateIndex(int newIndex) {
					super.updateIndex(newIndex);

					// Refresh the UI so recommendation labels are updated.
					updateItem(getItem(), isEmpty());
				}

				@Override
				protected void updateItem(CategorizedTransformer item, boolean isEmpty) {
					super.updateItem(item, isEmpty);

					if (item == null || isEmpty) {
						setText(null);
						setGraphic(null);
					} else {
						// TODO: If we add "CompositeTransformer" to our system (a transformer that wraps several others in a specific order)
						//  then this logic will need to be updated to "unroll" those wrapped transformers.
						//   - However, if they transformers have a proper dependencies ordering (or dont care about order) then we can
						//     just have a composite add each in their own dependencies
						int index = getIndex();
						List<? extends Class<? extends ClassTransformer>> transformerClasses = transformerOrder.stream()
								.map(t -> t.transformer().transformer().getClass())
								.toList();
						ClassTransformer transformer = item.transformer().transformer();
						List<ClassTransformer> missingPredecessors = Unchecked.cast(transformer.recommendedPredecessors().stream()
								.filter(pre -> {
									int i = transformerClasses.indexOf(pre);
									return i < 0 || i > index;
								})
								.map(pre -> {
									try {
										if (JvmClassTransformer.class.isAssignableFrom(pre))
											return transformationManager.newJvmTransformer(Unchecked.cast(pre));
									} catch (TransformationException e) {
										logger.error("Failed to initialize instance of {}", pre, e);
									}
									return null;
								})
								.filter(Objects::nonNull)
								.toList());
						List<ClassTransformer> missingSuccessors = Unchecked.cast(transformer.recommendedSuccessors().stream()
								.filter(post -> {
									int i = transformerClasses.indexOf(post);
									return i < 0 || i < index;
								})
								.map(post -> {
									try {
										if (JvmClassTransformer.class.isAssignableFrom(post))
											return transformationManager.newJvmTransformer(Unchecked.cast(post));
									} catch (TransformationException e) {
										logger.error("Failed to initialize instance of {}", post, e);
									}
									return null;
								})
								.filter(Objects::nonNull)
								.toList());

						Label name = new Label(getTransformerName(item.transformer().transformer()), new FontIconView(item.category().icon));
						BorderPane wrapper = new BorderPane();
						VBox missing = new VBox(name);
						missing.setPadding(new Insets(10));
						missing.setSpacing(10);
						missing.setAlignment(Pos.CENTER_LEFT);
						if (!missingPredecessors.isEmpty()) {
							String missingTransformers = Lang.get("deobf.order.pre") + ":\n - " + missingPredecessors.stream().map(DeobfuscationWindow::getTransformerName).collect(Collectors.joining("\n - "));
							Label missingLabel = new Label(missingTransformers);
							missingLabel.setPadding(new Insets(0, 0, 0, 20));
							missingLabel.getStyleClass().add(Styles.TEXT_SUBTLE);
							missing.getChildren().add(missingLabel);
						}
						if (!missingSuccessors.isEmpty()) {
							String missingTransformers = Lang.get("deobf.order.suc") + ":\n - " + missingSuccessors.stream().map(DeobfuscationWindow::getTransformerName).collect(Collectors.joining("\n - "));
							Label missingLabel = new Label(missingTransformers);
							missingLabel.setPadding(new Insets(0, 0, 0, 20));
							missingLabel.getStyleClass().add(Styles.TEXT_SUBTLE);
							missing.getChildren().add(missingLabel);
						}
						wrapper.setBottom(missing);
						setGraphic(wrapper);
					}
				}
			});
			StackPane wrapper = new StackPane(transformerOrderList, hint);
			wrapper.setAlignment(Pos.CENTER);
			transformerOrderPane.setTop(title);
			transformerOrderPane.setCenter(wrapper);
			hint.visibleProperty().bind(hasSelection.not());
		}
		BorderPane transformPreviewPane = new BorderPane();
		{
			BoundLabel title = new BoundLabel(Lang.getBinding("deobf.preview.title"));
			title.getStyleClass().add(Styles.TITLE_4);
			title.setPadding(new Insets(0, 0, 5, 5));

			Tab beforeTab = new Tab();
			Tab afterTab = new Tab();
			beforeTab.setClosable(false);
			afterTab.setClosable(false);
			TransformPreview beforePreview = new TransformPreview(languageAssociation, searchBarProvider, false);
			TransformPreview afterPreview = new TransformPreview(languageAssociation, searchBarProvider, true);
			beforeTab.setContent(beforePreview);
			afterTab.setContent(afterPreview);
			beforeTab.textProperty().bind(Lang.getBinding("misc.before"));
			afterTab.textProperty().bind(Lang.getBinding("misc.after"));
			beforeTab.setGraphic(new FontIconView(CarbonIcons.LICENSE));
			afterTab.setGraphic(new FontIconView(CarbonIcons.LICENSE_MAINTENANCE));
			TabPane tabs = new TabPane(beforeTab, afterTab);
			tabs.getSelectionModel().select(afterTab);

			BoundIntSpinner maxPassesSpinner = new BoundIntSpinner(maxPasses);
			Button pickClass = new ActionButton(CarbonIcons.ADD, Lang.getBinding("deobf.preview.pick"), () -> {
				new ClassSelectionPopup(actions, configurationService, explorerConfig, workspaceManager.getCurrent(), path -> {
					ClassInfo selection = path.getValue();

					beforePreview.setClassInfo(selection);
					afterPreview.setClassInfo(selection);

					beforePreview.updatePreview();
					afterPreview.updatePreview();
				}).showAndWait();
			});
			Button togglePreview = new ActionButton(CarbonIcons.ARROWS_HORIZONTAL, Lang.getBinding("deobf.preview.toggle-mode"), () -> {
				beforePreview.togglePreviewMode();
				afterPreview.togglePreviewMode();
			});
			BooleanProperty working = new SimpleBooleanProperty();
			Button applyToWorkspace = new ActionButton(CarbonIcons.PLAY, Lang.getBinding("deobf.apply"), () -> {
				TransformationApplier applier = transformationApplierService.newApplierForCurrentWorkspace();
				if (applier == null)
					return;

				List<Class<? extends JvmClassTransformer>> list = Unchecked.cast(transformerOrder.stream()
						.map(c -> c.transformer().type())
						.filter(JvmClassTransformer.class::isAssignableFrom)
						.toList());
				try {
					working.set(true);
					applier.setMaxPasses(maxPasses.get());
					JvmTransformResult result = applier.transformJvm(list);
					result.apply();
					FxThreadUtil.run(this::hide);
				} catch (TransformationException e) {
					// TODO: A tooltip or something showing would also be nice to have here since this is in a separate
					//  window which could mean the user cannot see the logging pane output.
					logger.error("Failed applying transformations to workspace", e);
				}
				working.set(false);
			}).async();
			applyToWorkspace.disableProperty().bind(hasSelection.not().or(working));
			transformerOrder.addListener((ListChangeListener<CategorizedTransformer>) change -> {
				beforePreview.updatePreview();
				afterPreview.updatePreview();
			});
			maxPasses.addListener((ob, old, cur) -> {
				beforePreview.updatePreview();
				afterPreview.updatePreview();
			});

			BoundLabel passesLabel = new BoundLabel(Lang.getBinding("deobf.max-passes"));
			passesLabel.setTextAlignment(TextAlignment.CENTER);
			passesLabel.setPadding(new Insets(5));
			HBox tools = new HBox(pickClass, new Spacer(), togglePreview, new Spacer(),
					maxPassesSpinner, passesLabel, applyToWorkspace);
			tools.setAlignment(Pos.CENTER);
			StackPane wrapper = new StackPane(tabs, tools);
			StackPane.setMargin(tools, new Insets(20));
			wrapper.setAlignment(Pos.BOTTOM_RIGHT);
			transformPreviewPane.setTop(title);
			transformPreviewPane.setCenter(wrapper);
			transformPreviewPane.setBottom(tools);
		}

		SplitPane split = new SplitPane(transformerTreePane, transformerOrderPane, transformPreviewPane);
		SplitPane.setResizableWithParent(transformerTreePane, false);
		SplitPane.setResizableWithParent(transformerOrderPane, false);
		split.setDividerPositions(0.3, 0.6);
		split.setPadding(new Insets(4));
		transformerTreePane.setPadding(new Insets(4));
		transformerOrderPane.setPadding(new Insets(4));
		transformPreviewPane.setPadding(new Insets(4));

		// Window setup
		titleProperty().bind(Lang.getBinding("deobf"));
		setScene(new RecafScene(new BorderPane(split)));
		setWidth(900);
		setHeight(600);
	}

	@SuppressWarnings("unchecked")
	private List<TreeItem<Selection>> of(Class<? extends ClassTransformer>... transformerClasses) {
		List<TreeItem<Selection>> results = new ArrayList<>(transformerClasses.length);
		for (Class<? extends ClassTransformer> transformerClass : transformerClasses) {
			try {
				if (JvmClassTransformer.class.isAssignableFrom(transformerClass)) {
					Class<JvmClassTransformer> jvmTransformerClass = (Class<JvmClassTransformer>) transformerClass;
					JvmClassTransformer transformer = transformationManager.newJvmTransformer(jvmTransformerClass);
					results.add(new TreeItem<>(new Selection.Transformer(transformer)));
				}
				// TODO: Android transformer case when we get to implementing that system
			} catch (TransformationException e) {
				logger.error("Failed to initialize instance of {}", transformerClass, e);
			}
		}
		return results;
	}

	/**
	 * Recursively selects all transformers in the tree.
	 *
	 * @param item The tree item to process.
	 * @param select {@code true} to select, {@code false} to deselect.
	 */
	private void selectAllTransformers(@Nonnull TreeItem<Selection> item, boolean select) {
		Selection value = item.getValue();
		if (value instanceof Selection.Transformer transformer) {
			Class<? extends ClassTransformer> transformerClass = transformer.type();
			boolean alreadySelected = transformerOrder.stream().anyMatch(i -> i.matches(transformerClass));
			if (select && !alreadySelected) {
				// Find the parent category
				TreeItem<Selection> parent = item.getParent();
				if (parent != null && parent.getValue() instanceof Selection.Category category) {
					transformerOrder.add(new CategorizedTransformer(transformer, category));
				}
			} else if (!select) {
				transformerOrder.removeIf(i -> i.matches(transformerClass));
			}
		}
		for (TreeItem<Selection> child : item.getChildren()) {
			selectAllTransformers(child, select);
		}
		hasSelection.set(!transformerOrder.isEmpty());
	}

	private class TransformPreview extends BorderPane {
		private final Batch deobfuscationBatch = ThreadUtil.batch(ThreadPoolFactory.newSingleThreadExecutor("deobfuscation-preview"));
		/** Incremented per preview request so that superseded previews neither block the chain nor update the UI. */
		private final AtomicInteger previewGeneration = new AtomicInteger();
		private final boolean andApply;
		private final Editor editorDecompile;
		private final Editor editorAssembly;
		private ClassInfo classInfo;

		private TransformPreview(@Nonnull FileTypeSyntaxAssociationService languageAssociation,
		                         @Nonnull Instance<SearchBar> searchBarProvider, boolean andApply) {
			this.andApply = andApply;

			editorDecompile = new Editor();
			editorDecompile.setSelectedBracketTracking(new SelectedBracketTracking());
			editorDecompile.getRootLineGraphicFactory().addDefaultCodeGraphicFactories();
			editorDecompile.getCodeArea().setEditable(false);
			languageAssociation.configureEditorSyntax("java", editorDecompile);

			// TODO: Control flow lines would also be nice to have here
			editorAssembly = new Editor();
			editorAssembly.getCodeArea().getStylesheets().add(LanguageStylesheets.getJasmStylesheet());
			editorAssembly.getRootLineGraphicFactory().addDefaultCodeGraphicFactories();
			editorAssembly.setSelectedBracketTracking(new SelectedBracketTracking());
			editorAssembly.getCodeArea().setEditable(false);
			languageAssociation.configureEditorSyntax("jasm", editorAssembly);

			SearchBar searchBar1 = searchBarProvider.get();
			SearchBar searchBar2 = searchBarProvider.get();
			searchBar1.install(editorDecompile);
			searchBar2.install(editorAssembly);

			// TODO: Make it a preference which is shown first
			setCenter(editorDecompile);

			updatePreview();
		}

		public void setClassInfo(@Nonnull ClassInfo classInfo) {
			this.classInfo = classInfo;
		}

		public void togglePreviewMode() {
			setCenter(isDecompilePreview() ? editorAssembly : editorDecompile);
			updatePreview();
		}

		public boolean isDecompilePreview() {
			return getCenter() == editorDecompile;
		}

		private void updatePreview() {
			// TODO: We can't really stop expensive decompilations, they don't check for interrupts on the current thread
			//  and we have no good way of force killing them (rip Thread.stop) so the best we can really do is only
			//  allow one of these tasks to run at a time, with one next 'pending' task.
			//  While a task is executing we update what is the next 'pending' task when we call this method.
			//  Once the current task is done, or there is nothing running we make the 'pending' the current task and run it.
			int generation = previewGeneration.incrementAndGet();
			deobfuscationBatch.add(() -> {
				deobfuscationBatch.clear();
				if (isStale(generation))
					return;
				if (isDecompilePreview())
					decompile(generation);
				else
					disassemble(generation);
			});
			deobfuscationBatch.executeNewest();
		}

		/**
		 * @param generation
		 * 		Generation of the preview request being processed.
		 *
		 * @return {@code true} when a newer preview request has been made since.
		 */
		private boolean isStale(int generation) {
			return previewGeneration.get() != generation;
		}

		private void setPreviewText(int generation, @Nonnull Editor editor, @Nonnull String text) {
			FxThreadUtil.run(() -> {
				if (isStale(generation))
					return;
				editor.setText(text);
			});
		}

		private void disassemble(int generation) {
			if (classInfo == null) {
				setPreviewText(generation, editorAssembly, "// Preview: Disassembly\n" + Lang.get("deobf.preview.noselection"));
				return;
			}

			JvmClassInfo jvmClass = getProcessedClass(generation);
			if (jvmClass == null) return;

			Workspace workspace = workspaceManager.getCurrent();
			String className = jvmClass.getName();
			ClassPathNode path = workspace.findClass(className);
			if (path == null) {
				logger.error("Couldn't find class {} in workspace for deobfuscation preview", className);
				return;
			}
			path = Objects.requireNonNull(path.getParent()).child(jvmClass);
			JvmAssemblerPipeline pipeline = assemblerPipelineManager.newJvmAssemblerPipeline(workspace);
			pipeline.disassemble(path)
					.ifOk(disassembly -> setPreviewText(generation, editorAssembly, disassembly))
					.ifErr((errors) -> {
						String errorListStr = errors.stream().map(Error::toString).collect(Collectors.joining("\n - "));
						logger.warn("Errors processing {} for deobfuscation preview:\n - {}", className, errorListStr);
					});
		}

		private void decompile(int generation) {
			if (classInfo == null) {
				setPreviewText(generation, editorDecompile, "// Preview: Decompile\n" + Lang.get("deobf.preview.noselection"));
				return;
			}

			JvmClassInfo jvmClass = getProcessedClass(generation);
			if (jvmClass == null) return;

			// TODO: Pull out common decompile code with "AbstractDecompilePane" to do this more aesthetically
			//  - And by that I mean include the animation
			//  - And the error handling for decompilation failures
			//  - But not the rest of the unrelated "editing" capabilities of the "AbstractDecompilePane"
			//
			// Decompilation is asynchronous, so the preview thread waits for it here. Otherwise the batch would move
			// straight on to the next queued preview and several decompilations would pile up in parallel.
			CompletableFuture<DecompileResult> future = decompilerManager.decompile(workspaceManager.getCurrent(), jvmClass);
			try {
				DecompileResult result = future.join();
				String text = result.getText();
				setPreviewText(generation, editorDecompile, text == null ? "// No decompilation output" : text);
			} catch (CancellationException ex) {
				// Nothing to show.
			} catch (CompletionException ex) {
				Throwable cause = ex.getCause() == null ? ex : ex.getCause();
				setPreviewText(generation, editorDecompile, "/*\nDecompilation failure\n" + StringUtil.traceToString(cause) + "\n*/");
			}
		}

		/**
		 * @param generation
		 * 		Generation of the preview request being processed.
		 *
		 * @return Processed output class to preview.
		 */
		@Nullable
		private JvmClassInfo getProcessedClass(int generation) {
			JvmClassInfo jvmClass = classInfo.asJvmClass();

			if (andApply) {
				List<Class<? extends JvmClassTransformer>> transformers = Unchecked.cast(transformerOrder.stream()
						.map(ct -> ct.transformer().type())
						.filter(JvmClassTransformer.class::isAssignableFrom)
						.toList());

				try {
					TransformationApplier applier = transformationApplierService.newApplierForCurrentWorkspace();
					if (applier == null)
						throw new TransformationException("No workspace is open");
					applier.setMaxPasses(maxPasses.get());
					JvmTransformResult result = applier
							.transformJvm(transformers, (_, _, _, targetClass) -> targetClass.getName().equals(classInfo.getName()));
					result.getTransformerFailures().forEach((_, map) -> map.forEach((transformer, error) -> {
						logger.debugging(l -> l.warn("Transformer '{}' failure: ", transformer.getSimpleName(), error));
					}));
					if (!result.getTransformedClasses().isEmpty())
						jvmClass = result.getTransformedClasses().values().iterator().next();
				} catch (TransformationException e) {
					setPreviewText(generation, editorDecompile, "// Failed to transform: " + e.getMessage() + "\n"
							+ "// " + StringUtil.traceToString(e).replace("\n", "\n// "));
					return null;
				}
			}

			return jvmClass;
		}
	}

	private sealed interface Selection {
		record Root() implements Selection {}

		record Category(@Nonnull String key, @Nonnull Ikon icon) implements Selection {}

		record Transformer(@Nonnull ClassTransformer transformer) implements Selection {
			@Nonnull
			public String name() {
				return transformer().name();
			}

			@Nonnull
			public Class<? extends ClassTransformer> type() {
				return transformer.getClass();
			}
		}
	}

	private record CategorizedTransformer(@Nonnull Selection.Transformer transformer,
	                                      @Nonnull Selection.Category category) {
		public boolean matches(@Nonnull Class<? extends ClassTransformer> cls) {
			return transformer.type() == cls;
		}

		@Override
		public String toString() {
			return category().key() + ':' + transformer().type().getName();
		}
	}
}
