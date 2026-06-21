package software.coley.recaf.services.config.factories;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.util.StringConverter;
import software.coley.recaf.config.ConfigContainer;
import software.coley.recaf.config.ConfigValue;
import software.coley.recaf.services.config.TypedConfigComponentFactory;
import software.coley.recaf.ui.control.ObservableComboBox;
import software.coley.recaf.util.Lang;

import java.util.Arrays;

/**
 * Factory for general {@link Enum} values.
 *
 * @author Matt Coley
 */
@ApplicationScoped
@SuppressWarnings("rawtypes")
public class EnumComponentFactory extends TypedConfigComponentFactory<Enum> {
	@Inject
	public EnumComponentFactory() {
		super(false, Enum.class);
	}

	@Nonnull
	@Override
	@SuppressWarnings("unchecked")
	public Node create(@Nonnull ConfigContainer container, @Nonnull ConfigValue<Enum> value) {
		Enum[] enumConstants = value.getType().getEnumConstants();
		ObservableComboBox<Enum> comboBox = new ObservableComboBox<>(value.getObservable(), Arrays.asList(enumConstants));

		// Add StringConverter for translation support
		StringConverter<Enum> converter = new StringConverter<>() {
			@Override
			public String toString(Enum object) {
				if (object == null) return "";
				String key = "enum." + object.getClass().getSimpleName() + "." + object.name();
				if (Lang.has(key)) {
					return Lang.get(key);
				}
				return object.name();
			}

			@Override
			public Enum fromString(String string) {
				// Not needed for display-only purposes
				return null;
			}
		};
		comboBox.setConverter(converter);

		// Set button cell to display translated text for selected item
		comboBox.setButtonCell(new ListCell<>() {
			@Override
			protected void updateItem(Enum item, boolean empty) {
				super.updateItem(item, empty);
				if (empty || item == null) {
					setText(null);
				} else {
					String key = "enum." + item.getClass().getSimpleName() + "." + item.name();
					if (Lang.has(key)) {
						setText(Lang.get(key));
					} else {
						setText(item.name());
					}
				}
			}
		});

		// Also set cell factory to use translations in the dropdown
		comboBox.setCellFactory(listView -> new ListCell<>() {
			@Override
			protected void updateItem(Enum item, boolean empty) {
				super.updateItem(item, empty);
				if (empty || item == null) {
					setText(null);
				} else {
					String key = "enum." + item.getClass().getSimpleName() + "." + item.name();
					if (Lang.has(key)) {
						setText(Lang.get(key));
					} else {
						setText(item.name());
					}
				}
			}
		});

		return comboBox;
	}
}
