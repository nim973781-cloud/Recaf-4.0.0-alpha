package software.coley.recaf.services.decompile;

import jakarta.annotation.Nonnull;
import software.coley.recaf.config.BasicConfigContainer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static software.coley.recaf.config.ConfigGroups.SERVICE_DECOMPILE_IMPL;

/**
 * Base class for fields needed by all decompiler configurations
 *
 * @author therathatter
 */
public class BaseDecompilerConfig extends BasicConfigContainer implements DecompilerConfig {
	private final AtomicInteger generation = new AtomicInteger();
	private volatile int hash = 0;

	/**
	 * @param id
	 * 		Container ID.
	 */
	public BaseDecompilerConfig(@Nonnull String id) {
		super(SERVICE_DECOMPILE_IMPL, id);
	}

	@Override
	public int getHash() {
		return hash;
	}

	@Override
	public void setHash(int hash) {
		this.hash = hash;
		generation.incrementAndGet();
	}

	/**
	 * Decompiler backends generally want their settings as a plain map or settings object. Building that
	 * representation for every single class is wasted work since the config only changes when the user edits it.
	 * The returned supplier builds the value once and reuses it until any config value changes.
	 *
	 * @param builder
	 * 		Supplier building an immutable representation of the current config state.
	 * @param <T>
	 * 		Snapshot type.
	 *
	 * @return Supplier yielding a cached snapshot of the config state.
	 */
	@Nonnull
	protected <T> Supplier<T> newConfigSnapshot(@Nonnull Supplier<T> builder) {
		return new ConfigSnapshot<>(builder);
	}

	private class ConfigSnapshot<T> implements Supplier<T> {
		private final Supplier<T> builder;
		private volatile T value;
		private volatile int valueGeneration = -1;

		private ConfigSnapshot(@Nonnull Supplier<T> builder) {
			this.builder = builder;
		}

		@Override
		public T get() {
			int currentGeneration = generation.get();
			T value = this.value;
			if (value == null || valueGeneration != currentGeneration) {
				value = builder.get();
				this.value = value;
				this.valueGeneration = currentGeneration;
			}
			return value;
		}
	}
}
