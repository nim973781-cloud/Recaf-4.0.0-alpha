package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.services.mapping.IntermediateMappings;

import java.nio.file.Path;

/**
 * Mapping input for a batch run, parsed exactly once and reused for every input JAR.
 * <p>
 * The parsed {@link IntermediateMappings} are treated as immutable. Applying them still goes through
 * {@link software.coley.recaf.services.mapping.MappingApplier}, which is the acceptance-grade path.
 *
 * @param source
 * 		File the mappings were read from, or {@code null} when there are no mappings.
 * @param formatName
 * 		Name of the mapping format used to parse, or {@code null} when there are no mappings.
 * @param mappings
 * 		Parsed mappings, or {@code null} when there are no mappings.
 *
 * @author Matt Coley
 */
public record BatchMappingPlan(
		@Nullable Path source,
		@Nullable String formatName,
		@Nullable IntermediateMappings mappings
) {
	private static final BatchMappingPlan NONE = new BatchMappingPlan(null, null, null);

	/**
	 * @return Plan representing "no mappings configured".
	 */
	@Nonnull
	public static BatchMappingPlan none() {
		return NONE;
	}

	/**
	 * @return {@code true} when there is nothing to apply.
	 */
	public boolean isEmpty() {
		return mappings == null;
	}
}
