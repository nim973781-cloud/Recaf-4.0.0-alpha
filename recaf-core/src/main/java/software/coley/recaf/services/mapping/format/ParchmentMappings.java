package software.coley.recaf.services.mapping.format;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.Dependent;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.Mappings;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.stream.Stream;

/**
 * Parchment mappings zip implementation.
 *
 * @author Matt Coley
 */
@Dependent
public class ParchmentMappings extends AbstractMappingFileFormat {
	public static final String NAME = "Parchment";
	private static final String ENTRY_NAME = "parchment.json";
	private static final Gson GSON = new Gson();

	public ParchmentMappings() {
		super(NAME, true, false);
	}

	@Nonnull
	@Override
	public IntermediateMappings parse(@Nonnull String mappingText) throws InvalidMappingException {
		try {
			ParchmentRoot root = GSON.fromJson(mappingText, ParchmentRoot.class);
			if (root == null) {
				throw new InvalidMappingException("Parchment mappings were empty");
			}
			return parseRoot(root);
		} catch (JsonParseException ex) {
			throw new InvalidMappingException("Invalid Parchment mappings JSON", ex);
		}
	}

	@Nonnull
	@Override
	public IntermediateMappings parse(@Nonnull Path path) throws InvalidMappingException {
		if (Files.isDirectory(path)) {
			Path candidate = resolveDirectoryCandidate(path);
			if (candidate == null) {
				throw new InvalidMappingException("Parchment directory did not contain any usable zip/json: " + path);
			}
			return parse(candidate);
		}

		String fileName = path.getFileName().toString().toLowerCase();
		if (fileName.endsWith(".json")) {
			try {
				return parse(Files.readString(path));
			} catch (IOException ex) {
				throw new InvalidMappingException("Failed reading Parchment mappings JSON", ex);
			}
		}

		try (ZipFile zipFile = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
			ZipEntry entry = zipFile.getEntry(ENTRY_NAME);
			if (entry == null) {
				throw new InvalidMappingException("Parchment zip missing " + ENTRY_NAME);
			}

			try (Reader reader = new InputStreamReader(zipFile.getInputStream(entry), StandardCharsets.UTF_8)) {
				ParchmentRoot root = GSON.fromJson(reader, ParchmentRoot.class);
				if (root == null) {
					throw new InvalidMappingException("Parchment zip contained empty " + ENTRY_NAME);
				}
				return parseRoot(root);
			}
		} catch (IOException ex) {
			throw new InvalidMappingException("Failed reading Parchment zip", ex);
		} catch (JsonParseException ex) {
			throw new InvalidMappingException("Invalid Parchment mappings JSON in zip", ex);
		}
	}

	@Override
	public boolean supportsExportText() {
		return false;
	}

	@Override
	public boolean supportsDirectoryLoading() {
		return true;
	}

	@Override
	public String exportText(@Nonnull Mappings mappings) throws InvalidMappingException {
		throw new InvalidMappingException("Parchment export is not supported");
	}

	@Nonnull
	private static IntermediateMappings parseRoot(@Nonnull ParchmentRoot root) {
		IntermediateMappings mappings = new ParchmentIntermediateMappings();
		if (root.classes == null) {
			return mappings;
		}

		for (ParchmentClass parchmentClass : root.classes) {
			if (parchmentClass == null || parchmentClass.name == null || parchmentClass.methods == null) {
				continue;
			}

			for (ParchmentMethod parchmentMethod : parchmentClass.methods) {
				if (parchmentMethod == null || parchmentMethod.name == null ||
						parchmentMethod.descriptor == null || parchmentMethod.parameters == null) {
					continue;
				}

				for (ParchmentParameter parchmentParameter : parchmentMethod.parameters) {
					if (parchmentParameter == null || parchmentParameter.name == null) {
						continue;
					}
					mappings.addVariable(
							parchmentClass.name,
							parchmentMethod.name,
							parchmentMethod.descriptor,
							null,
							null,
							parchmentParameter.index,
							parchmentParameter.name
					);
				}
			}
		}

		return mappings;
	}

	private static Path resolveDirectoryCandidate(@Nonnull Path directory) throws InvalidMappingException {
		try (Stream<Path> files = Files.list(directory).filter(Files::isRegularFile)) {
			List<Path> candidates = files.filter(ParchmentMappings::isSupportedFile)
					.sorted(ParchmentMappings::compareCandidates)
					.toList();
			return candidates.isEmpty() ? null : candidates.getFirst();
		} catch (Exception ex) {
			throw new InvalidMappingException("Failed reading Parchment directory: " + directory, ex);
		}
	}

	private static boolean isSupportedFile(@Nonnull Path path) {
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		return name.endsWith(".json") || name.endsWith(".zip");
	}

	private static int compareCandidates(@Nonnull Path left, @Nonnull Path right) {
		String leftName = left.getFileName().toString().toLowerCase(Locale.ROOT);
		String rightName = right.getFileName().toString().toLowerCase(Locale.ROOT);

		int typePriority = Integer.compare(candidatePriority(leftName), candidatePriority(rightName));
		if (typePriority != 0) {
			return typePriority;
		}

		return rightName.compareTo(leftName);
	}

	private static int candidatePriority(@Nonnull String name) {
		if (name.endsWith(".json")) {
			return 2;
		}
		if (name.endsWith("-checked.zip")) {
			return 1;
		}
		if (name.endsWith(".zip")) {
			return 0;
		}
		return 3;
	}

	private static final class ParchmentIntermediateMappings extends IntermediateMappings {
		@Override
		public boolean doesSupportVariableTypeDifferentiation() {
			return false;
		}
	}

	private static final class ParchmentRoot {
		List<ParchmentClass> classes;
	}

	private static final class ParchmentClass {
		String name;
		List<ParchmentMethod> methods;
	}

	private static final class ParchmentMethod {
		String name;
		String descriptor;
		List<ParchmentParameter> parameters;
	}

	private static final class ParchmentParameter {
		int index;
		String name;
	}
}
