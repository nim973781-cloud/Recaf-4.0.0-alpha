package software.coley.recaf.ui.pane;

import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Tweaks;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import software.coley.collections.Lists;
import software.coley.recaf.config.ConfigContainer;
import software.coley.recaf.config.ConfigGroups;
import software.coley.recaf.config.ConfigValue;
import software.coley.recaf.services.config.*;
import software.coley.recaf.ui.control.BoundLabel;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.util.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static software.coley.recaf.config.ConfigGroups.PACKAGE_SPLIT;
import static software.coley.recaf.config.ConfigGroups.getGroupPackages;
import static software.coley.recaf.util.Lang.getBinding;

/**
 * Pane to display all config values.
 *
 * @author Matt Coley
 * @see ConfigManager Source of values to pull from.
 * @see ConfigComponentManager Controls how to represent {@link ConfigValue} instances.
 * @see ConfigIconManager Controls which icons to show in the tree for {@link ConfigContainer} paths.
 */
@Dependent
public class ConfigPane extends SplitPane implements ManagedConfigListener {
	private final Map<String, ContainerPane> idToPage = new TreeMap<>();
	private final Map<String, TreeItem<String>> idToTree = new TreeMap<>();
	private final Map<String, ConfigContainer> idToContainer = new TreeMap<>();
	private final TreeItem<String> root = new TreeItem<>("root");
	private final TreeView<String> tree = new TreeView<>();
	private final ScrollPane content = new ScrollPane();
	private final TextField searchField = new TextField();
	private final ConfigComponentManager componentManager;
	private final ConfigIconManager iconManager;
	private String currentSearchText = "";

	@Inject
	public ConfigPane(@Nonnull ConfigManager configManager,
	                  @Nonnull ConfigComponentManager componentManager,
	                  @Nonnull ConfigIconManager iconManager) {
		this.componentManager = componentManager;
		this.iconManager = iconManager;
		configManager.addManagedConfigListener(this);

		// Setup UI
		initialize();

		// Initial state from existing containers
		for (ConfigContainer container : configManager.getContainers())
			onRegister(container);

		// Select first page
		tree.getSelectionModel().select(0);
	}

	private void initialize() {
		content.setFitToWidth(true);
		root.setExpanded(true);
		tree.setShowRoot(false);
		tree.setRoot(root);
		tree.getStyleClass().addAll(Tweaks.EDGE_TO_EDGE, Styles.DENSE);
		tree.setCellFactory(param -> new TreeCell<>() {
			@Override
			protected void updateItem(String item, boolean empty) {
				super.updateItem(item, empty);

				if (empty || item == null) {
					textProperty().unbind();
					textProperty().set(null);
					setGraphic(null);
				} else {
					Ikon icon = iconManager.getGroupIcon(item);
					if (icon == null)
						icon = iconManager.getContainerIcon(item);
					setGraphic(new FontIconView(Objects.requireNonNullElse(icon, CarbonIcons.DOT_MARK)));
					textProperty().bind(getBinding(item));
				}
			}
		});
		tree.getSelectionModel().selectedItemProperty().addListener((ob, old, cur) -> {
			if (cur != null) {
				ContainerPane page = idToPage.get(cur.getValue());
				if (page != null) {
					content.setContent(page);
				} else {
					content.setContent(new MissingPage(cur.getValue()));
				}
			}
		});

		// Search field setup
		searchField.setPromptText(Lang.get("misc.search"));
		searchField.getStyleClass().add(Styles.SMALL);
		searchField.textProperty().addListener((ob, old, newText) -> {
			currentSearchText = newText != null ? newText.toLowerCase() : "";
			performSearch();
		});

		// Layout - wrap tree with search box in VBox
		VBox leftPane = new VBox(5);
		leftPane.setPadding(new Insets(5));
		leftPane.getChildren().addAll(searchField, tree);
		VBox.setVgrow(tree, Priority.ALWAYS);

		SplitPane.setResizableWithParent(leftPane, false);
		getItems().addAll(leftPane, content);
		setDividerPositions(0.3);
	}

	/**
	 * Perform search and show matching results.
	 */
	private void performSearch() {
		if (currentSearchText.isEmpty()) {
			// Show all items when search is empty
			for (Map.Entry<String, TreeItem<String>> entry : idToTree.entrySet()) {
				ContainerPane page = idToPage.get(entry.getKey());
				if (page != null) {
					page.filterBySearch("");
				}
			}
			// Refresh the current page
			TreeItem<String> selected = tree.getSelectionModel().getSelectedItem();
			if (selected != null) {
				ContainerPane page = idToPage.get(selected.getValue());
				if (page != null) {
					content.setContent(page);
				}
			}
			return;
		}

		// Search through all containers and their values
		List<SearchResult> results = new ArrayList<>();
		for (Map.Entry<String, ConfigContainer> entry : idToContainer.entrySet()) {
			String pageKey = entry.getKey();
			ConfigContainer container = entry.getValue();
			for (Map.Entry<String, ConfigValue<?>> valueEntry : container.getValues().entrySet()) {
				ConfigValue<?> value = valueEntry.getValue();
				if (value.isHidden()) continue;

				String valueId = value.getId();
				String scopedId = container.getScopedId(value);
				String translatedName = Lang.has(scopedId) ? Lang.get(scopedId) : valueId;

				// Check if search text matches the value ID or translated name
				if (valueId.toLowerCase().contains(currentSearchText) ||
						translatedName.toLowerCase().contains(currentSearchText)) {
					results.add(new SearchResult(pageKey, valueId, translatedName));
				}
			}
		}

		// Show search results
		if (!results.isEmpty()) {
			content.setContent(new SearchResultsPane(results));
		} else {
			// Filter current page by search text
			TreeItem<String> selected = tree.getSelectionModel().getSelectedItem();
			if (selected != null) {
				ContainerPane page = idToPage.get(selected.getValue());
				if (page != null) {
					page.filterBySearch(currentSearchText);
					content.setContent(page);
				}
			}
		}
	}

	/**
	 * Record for search result.
	 */
	private record SearchResult(String pageKey, String valueId, String translatedName) {}

	/**
	 * Navigate to a specific config value and scroll to it.
	 *
	 * @param pageKey
	 * 		The page key to navigate to.
	 * @param valueId
	 * 		The value ID to scroll to.
	 */
	private void navigateToValue(@Nonnull String pageKey, @Nonnull String valueId) {
		TreeItem<String> item = idToTree.get(pageKey);
		if (item != null) {
			searchField.clear();
			tree.getSelectionModel().select(item);

			// Scroll to the specific value after a short delay to allow UI to update
			ContainerPane page = idToPage.get(pageKey);
			if (page != null) {
				page.scrollToAndHighlight(valueId);
			}
		}
	}

	@Override
	public void onRegister(@Nonnull ConfigContainer container) {
		// Skip empty containers
		if (container.getValues().isEmpty())
			return;

		// Setup tree structure.
		TreeItem<String> item = getItem(container, true);
		if (item != null) {
			String pageKey = item.getValue() + PACKAGE_SPLIT + container.getId();
			TreeItem<String> treeItem = new TreeItem<>(pageKey);
			item.getChildren().add(treeItem);

			// Register page.
			idToPage.put(pageKey, new ContainerPane(container));
			idToTree.put(pageKey, treeItem);
			idToContainer.put(pageKey, container);
		}
	}

	@Override
	public void onUnregister(@Nonnull ConfigContainer container) {
		TreeItem<String> item = getItem(container, false);
		while (item != null) {
			TreeItem<String> parent = item.getParent();
			List<TreeItem<String>> children = parent.getChildren();
			children.remove(item);

			// Determine if the parent also needs to be pruned when it is empty.
			if (children.isEmpty()) item = parent;
			else break;
		}
	}

	/**
	 * @param item
	 * 		Tree item to look in.
	 * @param name
	 * 		Name to match.
	 *
	 * @return Child with {@link TreeItem#getValue()} matching the given name value.
	 */
	@Nullable
	private TreeItem<String> getChildTreeItemByName(@Nonnull TreeItem<String> item, @Nonnull String name) {
		for (TreeItem<String> child : item.getChildren())
			if (name.equals(child.getValue()))
				return child;
		return null;
	}

	/**
	 * @param container
	 * 		Container to get item of.
	 * @param createIfMissing
	 * 		Flag to create item if it does not exist.
	 *
	 * @return Tree item if it exists. Otherwise {@code null}.
	 */
	@Nullable
	private TreeItem<String> getItem(@Nonnull ConfigContainer container, boolean createIfMissing) {
		TreeItem<String> currentItem = root;
		String currentPackage = null;
		String[] packages = getGroupPackages(container);
		for (String packageName : packages) {
			if (currentPackage == null)
				currentPackage = packageName;
			else
				currentPackage += PACKAGE_SPLIT + packageName;

			// Get or setup tree item.
			TreeItem<String> child = getChildTreeItemByName(currentItem, currentPackage);
			if (child == null) {
				if (createIfMissing) {
					// No existing tree item, create one.
					child = new TreeItem<>(currentPackage);
					child.setExpanded(true);

					// Insert child in sorted order.
					List<String> childrenNames = currentItem.getChildren().stream()
							.map(TreeItem::getValue)
							.toList();
					int insertIndex = Lists.sortedInsertIndex(childrenNames, currentPackage);
					currentItem.getChildren().add(insertIndex, child);
				} else {
					// Exit, cannot find item
					return null;
				}
			}
			currentItem = child;
		}
		return currentItem;
	}

	/**
	 * Page for a single {@link ConfigContainer}.
	 */
	private class ContainerPane extends GridPane {
		private final Map<String, List<Node>> valueIdToNodes = new TreeMap<>();
		private final ConfigContainer container;

		@SuppressWarnings({"rawtypes", "unchecked"})
		private ContainerPane(@Nonnull ConfigContainer container) {
			this.container = container;

			// Plugin configs are given special treatment.
			// They are not expected to install additional translations, so their ID's will be used as literal names.
			boolean isThirdPartyConfig = ConfigGroups.EXTERNAL.equals(container.getGroup());

			// Title
			Label title = isThirdPartyConfig ? new Label(container.getId()) :
					new BoundLabel(getBinding(container.getGroupAndId()));
			title.getStyleClass().add(Styles.TITLE_4);
			add(title, 0, 0, 2, 1);
			add(new Separator(), 0, 1, 2, 1);

			// Values
			Map<String, ConfigValue<?>> values = container.getValues();
			int row = 2;
			for (Map.Entry<String, ConfigValue<?>> entry : values.entrySet()) {
				ConfigValue<?> value = entry.getValue();
				if (value.isHidden()) continue;
				ConfigComponentFactory componentFactory = componentManager.getFactory(container, value);
				List<Node> rowNodes = new ArrayList<>();
				if (componentFactory.isStandAlone()) {
					Node node = componentFactory.create(container, value);
					add(node, 0, row, 2, 1);
					rowNodes.add(node);
				} else {
					String key = container.getScopedId(value);
					Node labelNode;
					if (isThirdPartyConfig) {
						labelNode = new Label(value.getId());
					} else {
						labelNode = new BoundLabel(getBinding(key));
					}
					Node editorNode = componentFactory.create(container, value);
					add(labelNode, 0, row);
					add(editorNode, 1, row);
					rowNodes.add(labelNode);
					rowNodes.add(editorNode);
				}
				valueIdToNodes.put(value.getId(), rowNodes);
				row++;
			}

			// Layout
			setPadding(new Insets(10));
			setVgap(5);
			setHgap(5);
			ColumnConstraints columnLabel = new ColumnConstraints();
			ColumnConstraints columnEditor = new ColumnConstraints();
			columnEditor.setHgrow(Priority.ALWAYS);
			getColumnConstraints().addAll(columnLabel, columnEditor);
		}

		/**
		 * Filter visible rows by search text.
		 *
		 * @param searchText
		 * 		Text to filter by (empty to show all).
		 */
		public void filterBySearch(@Nonnull String searchText) {
			for (Map.Entry<String, List<Node>> entry : valueIdToNodes.entrySet()) {
				String valueId = entry.getKey();
				List<Node> nodes = entry.getValue();

				if (searchText.isEmpty()) {
					// Show all
					nodes.forEach(n -> n.setVisible(true));
					nodes.forEach(n -> n.setManaged(true));
				} else {
					// Check if matches
					ConfigValue<?> value = container.getValues().get(valueId);
					if (value != null) {
						String scopedId = container.getScopedId(value);
						String translatedName = Lang.has(scopedId) ? Lang.get(scopedId) : valueId;
						boolean matches = valueId.toLowerCase().contains(searchText) ||
								translatedName.toLowerCase().contains(searchText);
						nodes.forEach(n -> n.setVisible(matches));
						nodes.forEach(n -> n.setManaged(matches));
					}
				}
			}
		}

		/**
		 * Scroll to and highlight a specific config value.
		 *
		 * @param valueId
		 * 		The value ID to scroll to.
		 */
		public void scrollToAndHighlight(@Nonnull String valueId) {
			List<Node> nodes = valueIdToNodes.get(valueId);
			if (nodes != null && !nodes.isEmpty()) {
				Node targetNode = nodes.get(0);

				// Use Platform.runLater to ensure the UI has been updated
				javafx.application.Platform.runLater(() -> {
					// Scroll to the node
					targetNode.requestFocus();

					// Calculate scroll position
					double nodeY = targetNode.getBoundsInParent().getMinY();
					double paneHeight = getHeight();
					double contentHeight = content.getContent() != null ?
							content.getContent().getBoundsInLocal().getHeight() : paneHeight;

					if (contentHeight > paneHeight) {
						double vvalue = nodeY / (contentHeight - paneHeight);
						content.setVvalue(Math.min(1.0, Math.max(0.0, vvalue)));
					}

					// Highlight effect - flash the background
					highlightNodes(nodes);
				});
			}
		}

		/**
		 * Temporarily highlight nodes to draw attention.
		 *
		 * @param nodes
		 * 		Nodes to highlight.
		 */
		private void highlightNodes(@Nonnull List<Node> nodes) {
			String highlightStyle = "-fx-background-color: derive(-fx-accent, 80%); -fx-background-radius: 3;";
			for (Node node : nodes) {
				String originalStyle = node.getStyle();
				node.setStyle(highlightStyle);

				// Remove highlight after a short delay
				javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
				pause.setOnFinished(e -> node.setStyle(originalStyle));
				pause.play();
			}
		}
	}

	/**
	 * Page to show search results with inline editing.
	 */
	private class SearchResultsPane extends GridPane {
		@SuppressWarnings({"rawtypes", "unchecked"})
		public SearchResultsPane(@Nonnull List<SearchResult> results) {
			// Title
			Label title = new Label(Lang.get("misc.search") + " (" + results.size() + ")");
			title.getStyleClass().add(Styles.TITLE_4);
			add(title, 0, 0, 2, 1);
			add(new Separator(), 0, 1, 2, 1);

			// Results with inline editors
			int row = 2;
			for (SearchResult result : results) {
				ConfigContainer container = idToContainer.get(result.pageKey());
				if (container == null) continue;

				ConfigValue<?> value = container.getValues().get(result.valueId());
				if (value == null || value.isHidden()) continue;

				// Label with link to navigate
				Hyperlink label = new Hyperlink(result.translatedName());
				label.setOnAction(e -> navigateToValue(result.pageKey(), result.valueId()));
				add(label, 0, row);

				// Editor control
				ConfigComponentFactory factory = componentManager.getFactory(container, value);
				if (!factory.isStandAlone()) {
					add(factory.create(container, value), 1, row);
				} else {
					add(factory.create(container, value), 0, row + 1, 2, 1);
					row++;
				}
				row++;
			}

			// Layout
			setPadding(new Insets(10));
			setVgap(5);
			setHgap(10);
			ColumnConstraints columnLabel = new ColumnConstraints();
			columnLabel.setMinWidth(150);
			ColumnConstraints columnEditor = new ColumnConstraints();
			columnEditor.setHgrow(Priority.ALWAYS);
			getColumnConstraints().addAll(columnLabel, columnEditor);
		}
	}

	/**
	 * Page to show child-pages when the group itself does not have content.
	 */
	private class MissingPage extends VBox {
		public MissingPage(@Nonnull String id) {
			// Title
			ObservableList<Node> children = getChildren();
			Label title = new BoundLabel(getBinding(id));
			title.getStyleClass().add(Styles.TITLE_4);
			children.add(title);
			children.add(new Separator());

			// Sub-menus
			for (String key : idToPage.keySet()) {
				if (key.startsWith(id)) {
					Hyperlink child = new Hyperlink();
					child.textProperty().bind(getBinding(key));
					child.setOnAction(e -> {
						TreeItem<String> item = idToTree.get(key);
						tree.getSelectionModel().select(item);
					});
					children.add(child);
				}
			}

			// Layout
			setPadding(new Insets(10));
			setSpacing(5);
			setFillWidth(true);
		}
	}
}
