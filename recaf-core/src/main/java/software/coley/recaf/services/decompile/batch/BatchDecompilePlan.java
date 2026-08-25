package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;

import java.util.List;

/**
 * The full set of work a batch run will perform.
 *
 * @param jars
 * 		Input JARs in the order they will be processed.
 * @param mappingPlan
 * 		Mappings applied to every input.
 * @param outputFormat
 * 		Shape of the output.
 *
 * @author Matt Coley
 */
public record BatchDecompilePlan(
		@Nonnull List<JarDecompilePlan> jars,
		@Nonnull BatchMappingPlan mappingPlan,
		@Nonnull BatchOutputFormat outputFormat
) {
	/**
	 * @return Number of classes across all JARs. Zero for a plan whose JARs have not been imported yet.
	 */
	public int totalClassCount() {
		return jars.stream().mapToInt(JarDecompilePlan::classCount).sum();
	}

	/**
	 * @return Number of input JARs.
	 */
	public int jarCount() {
		return jars.size();
	}

	/**
	 * @return {@code true} when there is nothing to process.
	 */
	public boolean isEmpty() {
		return jars.isEmpty();
	}
}
