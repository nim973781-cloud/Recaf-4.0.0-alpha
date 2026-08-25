package software.coley.recaf.workspace.model.bundle;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.behavior.Closing;
import software.coley.recaf.info.Info;

import java.util.*;
import java.util.stream.Stream;

/**
 * Base bundle type.
 *
 * @param <I>
 * 		Bundle value type.
 *
 * @author Matt Coley
 */
public interface Bundle<I extends Info> extends Map<String, I>, Iterable<I>, Closing {
	/**
	 * History stack for the given item key.
	 *
	 * @param key
	 * 		Item key.
	 *
	 * @return History of item.
	 */
	@Nullable
	Stack<I> getHistory(String key);

	/**
	 * @return Keys of items that have been modified <i>(Containing any history values)</i>.
	 */
	@Nonnull
	Set<String> getDirtyKeys();

	/**
	 * @return Keys of items that were part of the initial bundle contents but have since been removed.
	 */
	@Nonnull
	Set<String> getRemovedKeys();

	/**
	 * @param info
	 * 		Item to write.
	 *
	 * @return Prior value if any.
	 */
	@Nullable
	I put(I info);

	/**
	 * @return A copied collection of {@link #values()}, allowing modification during iteration.
	 */
	@Nonnull
	default Collection<I> valuesAsCopy() {
		return new ArrayList<>(values());
	}

	/**
	 * @return Stream of items.
	 */
	@Nonnull
	default Stream<I> stream() {
		return values().stream();
	}

	/**
	 * @param key
	 * 		Item key.
	 *
	 * @return {@code true} if the item has a history. Any such item will also be present in {@link #getDirtyKeys()}.
	 */
	boolean hasHistory(String key);

	/**
	 * If the given item isn't part of the bundle, it is added and no historical record is kept.
	 * Otherwise, the existing item's history is incremented.
	 *
	 * @param info
	 * 		Item to update.
	 */
	void incrementHistory(I info);

	/**
	 * Decrement the history of the value associated with the key.
	 * The value removed in this operation then replaces the value of the item map.
	 *
	 * @param key
	 * 		Item key.
	 */
	void decrementHistory(String key);

	/**
	 * @param listener
	 * 		Listener to add.
	 */
	void addBundleListener(BundleListener<I> listener);

	/**
	 * Adds a listener that is notified before listeners registered with {@link #addBundleListener(BundleListener)}.
	 * Used by the workspace type index so a class added to a bundle is visible to {@link software.coley.recaf.workspace.model.Workspace#findClass(String)}
	 * during other listeners that run on the same put.
	 *
	 * @param listener
	 * 		Listener to add at the front of the notification list.
	 */
	default void prependBundleListener(BundleListener<I> listener) {
		addBundleListener(listener);
	}

	/**
	 * @param listener
	 * 		Listener to remove.
	 */
	void removeBundleListener(BundleListener<I> listener);
}
