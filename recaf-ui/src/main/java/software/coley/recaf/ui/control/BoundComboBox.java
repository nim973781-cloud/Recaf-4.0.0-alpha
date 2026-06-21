package software.coley.recaf.ui.control;

import jakarta.annotation.Nonnull;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.Property;
import javafx.scene.control.ComboBox;
import javafx.scene.control.SingleSelectionModel;
import javafx.util.StringConverter;

import java.util.List;

/**
 * Combo box with one-way binding to a {@link ObjectProperty}.
 *
 * @param <T>
 * 		Property value type.
 *
 * @author Matt Coley
 * @see BoundBiDiComboBox Two-way bound combo-box.
 */
public class BoundComboBox<T> extends ComboBox<T> implements Tooltipable {
	/**
	 * @param value
	 * 		property.
	 * @param values
	 * 		Available options.
	 * @param converter
	 * 		Value to string conversion.
	 */
	public BoundComboBox(@Nonnull Property<T> value, @Nonnull List<T> values, @Nonnull StringConverter<T> converter) {
		// Populate combo-model and select the initial value.
		getItems().addAll(values);
		SingleSelectionModel<T> selectionModel = getSelectionModel();
		selectionModel.select(value.getValue());

		// Bind the property to the given selected item.
		value.bind(selectionModel.selectedItemProperty());

		// Allow horizontal expansion.
		setMaxWidth(Double.MAX_VALUE);

		// T to String mapping.
		setConverter(converter);
	}
}
