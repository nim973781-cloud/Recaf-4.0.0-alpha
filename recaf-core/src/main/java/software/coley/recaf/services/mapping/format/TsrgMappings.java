package software.coley.recaf.services.mapping.format;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import net.fabricmc.mappingio.format.srg.TsrgFileReader;
import net.fabricmc.mappingio.format.srg.TsrgFileWriter;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.Mappings;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TSRG/TSRG2 mappings file implementation.
 * <p>
 * TSRG (Tiny SRG) is a mapping format used by Minecraft Forge.
 * TSRG2 extends TSRG with support for multiple namespaces.
 *
 * @author Matt Coley
 */
@Dependent
public class TsrgMappings extends AbstractMappingFileFormat {
	public static final String NAME = "TSRG";

	/**
	 * New TSRG instance.
	 */
	public TsrgMappings() {
		// TSRG format does not include field type descriptors
		super(NAME, false, false);
	}

	@Nonnull
	@Override
	public IntermediateMappings parse(@Nonnull String mappingText) throws InvalidMappingException {
		// Populate the mapping-io model
		MemoryMappingTree tree = new MemoryMappingTree();
		StringReader reader = new StringReader(mappingText);
		try {
			TsrgFileReader.read(reader, tree);
		} catch (IOException ex) {
			throw new InvalidMappingException(ex);
		}

		// Create our mapping model with proper field differentiation support
		TsrgIntermediateMappings mappings = new TsrgIntermediateMappings();

		// TSRG2 format: "tsrg2 left right" where left=source, right=target
		// For Forge MCP mappings: left=MCP(readable), right=SRG(obfuscated like m_12345_)
		// We need REVERSE mapping: SRG -> MCP (to convert f_12345_ to readable names)
		// So we swap: use dst as source, src as target
		int namespaceCount = tree.getDstNamespaces().size();
		int finalNamespace = namespaceCount - 1;
		for (MappingTree.ClassMapping cm : tree.getClasses()) {
			// REVERSE: SRG class name -> MCP class name
			String srgClassName = cm.getDstName(finalNamespace);
			String mcpClassName = cm.getSrcName();
			if (srgClassName != null && mcpClassName != null) {
				mappings.addClass(srgClassName, mcpClassName);
			}

			for (MappingTree.FieldMapping fm : cm.getFields()) {
				// REVERSE: SRG field -> MCP field
				String srgFieldName = fm.getDstName(finalNamespace);
				String mcpFieldName = fm.getSrcName();
				if (srgFieldName == null || mcpFieldName == null)
					continue;

				String fieldDesc = fm.getSrcDesc();
				// Map from SRG owner class to field rename
				if (srgClassName != null) {
					mappings.addField(srgClassName, fieldDesc, srgFieldName, mcpFieldName);
				}
			}

			for (MappingTree.MethodMapping mm : cm.getMethods()) {
				// REVERSE: SRG method -> MCP method
				String srgMethodName = mm.getDstName(finalNamespace);
				String mcpMethodName = mm.getSrcName();
				if (srgMethodName == null || mcpMethodName == null)
					continue;

				String methodDesc = mm.getSrcDesc();
				if (methodDesc != null && srgClassName != null) {
					mappings.addMethod(srgClassName, methodDesc, srgMethodName, mcpMethodName);
				}
			}
		}
		return mappings;
	}

	@Override
	public String exportText(@Nonnull Mappings mappings) throws InvalidMappingException {
		// Second parameter 'true' enables TSRG2 format with multiple namespaces
		return MappingFileFormat.export(mappings, "obf", List.of("srg"), writer -> new TsrgFileWriter(writer, true));
	}

	/**
	 * Extension of intermediate mappings to properly handle TSRG format.
	 * <p>
	 * Key enhancement: SRG names (like m_12345_, f_67890_) are globally unique across all Minecraft classes.
	 * This allows us to map method/field calls even when the owner class differs from where the member
	 * is originally defined (e.g., calling Entity.level() through LivingEntity reference).
	 */
	private static class TsrgIntermediateMappings extends IntermediateMappings {
		// Global lookup maps: SRG name -> MCP name (ignores owner class)
		private final Map<String, String> globalMethodMappings = new HashMap<>();
		private final Map<String, String> globalFieldMappings = new HashMap<>();

		@Override
		public void addMethod(@Nonnull String ownerName, @Nonnull String desc, @Nonnull String oldName, @Nonnull String newName) {
			super.addMethod(ownerName, desc, oldName, newName);
			// SRG method names like m_12345_ (new format) or func_12345_ (old MCP format) are globally unique
			if ((oldName.startsWith("m_") && oldName.endsWith("_")) || oldName.startsWith("func_")) {
				globalMethodMappings.put(oldName, newName);
			}
		}

		@Override
		public void addField(@Nonnull String ownerName, @Nullable String desc, @Nonnull String oldName, @Nonnull String newName) {
			super.addField(ownerName, desc, oldName, newName);
			// SRG field names like f_12345_ (new format) or field_12345_ (old MCP format) are globally unique
			if ((oldName.startsWith("f_") && oldName.endsWith("_")) || oldName.startsWith("field_")) {
				globalFieldMappings.put(oldName, newName);
			}
		}

		@Nonnull
		@Override
		public Map<String, String> getGlobalMethodMappings() {
			return globalMethodMappings;
		}

		@Nonnull
		@Override
		public Map<String, String> getGlobalFieldMappings() {
			return globalFieldMappings;
		}

		@Nullable
		@Override
		public String getMappedMethodName(@Nonnull String ownerName, @Nonnull String methodName, @Nonnull String methodDesc) {
			// First try the standard lookup by owner
			String result = super.getMappedMethodName(ownerName, methodName, methodDesc);
			if (result != null) {
				return result;
			}
			// Fallback: for SRG names, use global lookup (owner-independent)
			return globalMethodMappings.get(methodName);
		}

		@Nullable
		@Override
		public String getMappedFieldName(@Nonnull String ownerName, @Nonnull String fieldName, @Nonnull String fieldDesc) {
			// First try the standard lookup by owner
			String result = super.getMappedFieldName(ownerName, fieldName, fieldDesc);
			if (result != null) {
				return result;
			}
			// Fallback: for SRG names, use global lookup (owner-independent)
			return globalFieldMappings.get(fieldName);
		}

		@Override
		public boolean doesSupportFieldTypeDifferentiation() {
			// TSRG fields do not include type info.
			return false;
		}

		@Override
		public boolean doesSupportVariableTypeDifferentiation() {
			// TSRG variables do not include type info.
			return false;
		}
	}
}
