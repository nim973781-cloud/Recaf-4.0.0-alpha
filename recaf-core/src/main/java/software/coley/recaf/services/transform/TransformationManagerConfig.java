package software.coley.recaf.services.transform;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.observables.ObservableBoolean;
import software.coley.recaf.config.BasicConfigContainer;
import software.coley.recaf.config.BasicConfigValue;
import software.coley.recaf.config.ConfigGroups;
import software.coley.recaf.services.ServiceConfig;

/**
 * Config for {@link TransformationManager}.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class TransformationManagerConfig extends BasicConfigContainer implements ServiceConfig {
	private final ObservableBoolean continueOnTransformerFailure = new ObservableBoolean(true);
	private final ObservableBoolean continueOnSerializationFailure = new ObservableBoolean(true);

	@Inject
	public TransformationManagerConfig() {
		super(ConfigGroups.SERVICE_TRANSFORM, TransformationManager.SERVICE_ID + CONFIG_SUFFIX);
		// Add values
		addValue(new BasicConfigValue<>("continue-on-transformer-failure", boolean.class, continueOnTransformerFailure));
		addValue(new BasicConfigValue<>("continue-on-serialization-failure", boolean.class, continueOnSerializationFailure));
	}

	/**
	 * @return {@code true} to continue processing other classes when a transformer fails on a specific class.
	 * When {@code false}, a single transformer failure will abort the entire transformation process.
	 */
	@Nonnull
	public ObservableBoolean getContinueOnTransformerFailure() {
		return continueOnTransformerFailure;
	}

	/**
	 * @return {@code true} to skip classes that fail during final bytecode serialization (keeping original bytecode).
	 * When {@code false}, a serialization failure will abort the entire transformation process.
	 */
	@Nonnull
	public ObservableBoolean getContinueOnSerializationFailure() {
		return continueOnSerializationFailure;
	}
}
