package software.coley.recaf.services.mapping.format;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import net.fabricmc.mappingio.format.tiny.Tiny1FileReader;
import net.fabricmc.mappingio.format.tiny.Tiny1FileWriter;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.Mappings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny-V1 mappings file implementation.
 *
 * @author Matt Coley
 * @author Wolfie / win32kbase
 */
@Dependent
public class TinyV1Mappings extends AbstractMappingFileFormat {
	public static final String NAME = "Tiny-V1";

	/**
	 * New tiny v1 instance.
	 */
	public TinyV1Mappings() {
		super(NAME, true, true);
	}

	@Nonnull
	@Override
	public IntermediateMappings parse(@Nonnull String mappingText) throws InvalidMappingException {
		if (looksLikeFabricYarnPseudoMemberTiny(mappingText)) {
			return parseFabricYarnPseudoMemberTiny(mappingText);
		}
		return MappingFileFormat.parse(mappingText, Tiny1FileReader::read);
	}

	@Override
	public String exportText(@Nonnull Mappings mappings) throws InvalidMappingException {
		return MappingFileFormat.export(mappings, "intermediary", List.of("named"), Tiny1FileWriter::new);
	}

	private static boolean looksLikeFabricYarnPseudoMemberTiny(@Nonnull String mappingText) {
		for (String line : mappingText.split("\\R")) {
			if (!line.startsWith("CLASS\t")) {
				continue;
			}

			String[] parts = line.split("\t");
			if (parts.length < 3) {
				continue;
			}

			String sourceName = parts[1];
			int memberSplit = sourceName.lastIndexOf('.');
			if (memberSplit < 0) {
				continue;
			}

			String sourceMember = sourceName.substring(memberSplit + 1);
			if (sourceMember.startsWith("method_") || sourceMember.startsWith("field_") || "<init>".equals(sourceMember)) {
				return true;
			}
		}
		return false;
	}

	@Nonnull
	private static IntermediateMappings parseFabricYarnPseudoMemberTiny(@Nonnull String mappingText) throws InvalidMappingException {
		YarnTinyMappings mappings = new YarnTinyMappings();
		int lineNumber = 0;
		for (String rawLine : mappingText.split("\\R")) {
			lineNumber++;
			String line = rawLine.trim();
			if (line.isEmpty() || line.startsWith("v1\t") || line.startsWith("#")) {
				continue;
			}
			if (!line.startsWith("CLASS\t")) {
				continue;
			}

			String[] parts = line.split("\t");
			if (parts.length < 3) {
				throw new InvalidMappingException("Invalid Tiny-V1 Yarn mapping on line " + lineNumber + ": " + rawLine);
			}

			String sourceName = parts[1];
			String targetName = parts[2];
			int sourceMemberSplit = sourceName.lastIndexOf('.');
			int targetMemberSplit = targetName.lastIndexOf('.');
			if (sourceMemberSplit < 0 || targetMemberSplit < 0) {
				mappings.addClass(sourceName, targetName);
				continue;
			}

			String sourceOwner = sourceName.substring(0, sourceMemberSplit);
			String sourceMember = sourceName.substring(sourceMemberSplit + 1);
			String targetOwner = targetName.substring(0, targetMemberSplit);
			String targetMember = targetName.substring(targetMemberSplit + 1);
			mappings.addClass(sourceOwner, targetOwner);

			if (sourceMember.startsWith("method_")) {
				mappings.addPseudoMethod(sourceMember, targetMember);
			} else if (sourceMember.startsWith("field_")) {
				mappings.addPseudoField(sourceOwner, sourceMember, targetMember);
			} else if ("<init>".equals(sourceMember) || "<clinit>".equals(sourceMember)) {
				// Constructors keep their JVM names, so these entries only help recover the owning class name.
			} else {
				throw new InvalidMappingException("Unsupported Tiny-V1 Yarn member on line " + lineNumber + ": " + rawLine);
			}
		}
		return mappings;
	}

	private static final class YarnTinyMappings extends IntermediateMappings {
		private final Map<String, String> globalMethodMappings = new HashMap<>();
		private final Map<String, String> globalFieldMappings = new HashMap<>();

		void addPseudoMethod(@Nonnull String oldName, @Nonnull String newName) {
			if (!oldName.equals(newName)) {
				globalMethodMappings.put(oldName, newName);
			}
		}

		void addPseudoField(@Nonnull String ownerName, @Nonnull String oldName, @Nonnull String newName) {
			addField(ownerName, null, oldName, newName);
			if (!oldName.equals(newName)) {
				globalFieldMappings.put(oldName, newName);
			}
		}

		@Nullable
		@Override
		public String getMappedMethodName(@Nonnull String ownerName, @Nonnull String methodName, @Nonnull String methodDesc) {
			String mapped = super.getMappedMethodName(ownerName, methodName, methodDesc);
			if (mapped != null) {
				return mapped;
			}
			return globalMethodMappings.get(methodName);
		}

		@Nullable
		@Override
		public String getMappedFieldName(@Nonnull String ownerName, @Nonnull String fieldName, @Nonnull String fieldDesc) {
			String mapped = super.getMappedFieldName(ownerName, fieldName, fieldDesc);
			if (mapped != null) {
				return mapped;
			}
			return globalFieldMappings.get(fieldName);
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

		@Override
		public boolean doesSupportFieldTypeDifferentiation() {
			return false;
		}

		@Override
		public boolean doesSupportVariableTypeDifferentiation() {
			return false;
		}
	}
}
