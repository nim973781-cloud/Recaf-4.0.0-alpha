package software.coley.recaf.services.workspace.io;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import software.coley.cafedude.classfile.VersionConstants;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.Info;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.ArscFileInfoBuilder;
import software.coley.recaf.info.builder.AudioFileInfoBuilder;
import software.coley.recaf.info.builder.BinaryXmlFileInfoBuilder;
import software.coley.recaf.info.builder.DexFileInfoBuilder;
import software.coley.recaf.info.builder.FileInfoBuilder;
import software.coley.recaf.info.builder.ImageFileInfoBuilder;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.info.builder.ModulesFileInfoBuilder;
import software.coley.recaf.info.builder.NativeLibraryFileInfoBuilder;
import software.coley.recaf.info.builder.VideoFileInfoBuilder;
import software.coley.recaf.info.builder.ZipFileInfoBuilder;
import software.coley.recaf.info.properties.builtin.IllegalClassSuspectProperty;
import software.coley.recaf.info.properties.builtin.ZipMarkerProperty;
import software.coley.recaf.services.text.TextFormatConfig;
import software.coley.recaf.util.ByteHeaderUtil;
import software.coley.recaf.util.IOUtil;
import software.coley.recaf.util.android.AndroidXmlUtil;
import software.coley.recaf.util.io.ByteSource;

import java.io.IOException;
import java.util.Locale;

/**
 * Basic implementation of the info importer.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class BasicInfoImporter implements InfoImporter {
	private static final Logger logger = Logging.get(BasicInfoImporter.class);
	private final ClassPatcher classPatcher;
	private final InfoImporterConfig config;
	private final TextFormatConfig formatConfig;

	@Inject
	public BasicInfoImporter(@Nonnull InfoImporterConfig config, @Nonnull TextFormatConfig formatConfig, @Nonnull ClassPatcher classPatcher) {
		this.config = config;
		this.formatConfig = formatConfig;
		this.classPatcher = classPatcher;
	}

	@Nonnull
	@Override
	public Info readInfo(@Nonnull String name, @Nonnull ByteSource source) throws IOException {
		// Most type checks only need a small prefix. In particular, avoid scanning every non-class entry
		// in a large archive for an incidental ZIP marker.
		byte[] header = source.peek(64);

		// Check for Java classes
		if (matchesClass(header)) {
			byte[] data = source.readAll();
			try {
				return readClass(name, data);
			} catch (Throwable t) {
				// Invalid class. There are a few possibilities here:
				// - The user has disabled patching in their settings and opened an obfuscated file that kills ASM.
				// - There is a pattern in the file very similar to a class file, but it is not actually a class file.
				// - There is an edge case we need to add to CafeDude to allow complete patching.
				return new FileInfoBuilder<>()
						.withRawContent(data)
						.withName(name)
						.withProperty(IllegalClassSuspectProperty.INSTANCE)
						.build();
			}
		}

		byte[] data = source.readAll();

		// Comparing against known file types.
		FileInfo info = readAsSpecializedFile(name, header, data);
		boolean hasZipMarker = hasZipPrefix(header);
		if (!hasZipMarker && shouldScanForZip(name, info))
			hasZipMarker = ByteHeaderUtil.matchAtAnyOffset(data, ByteHeaderUtil.ZIP);
		if (info != null) {
			if (hasZipMarker)
				ZipMarkerProperty.set(info);
			return info;
		}

		// Check for ZIP containers (For ZIP/JAR/JMod/WAR)
		//  - While this is more common, some of the known file types may match 'ZIP' with
		//    our 'any-offset' condition we have here.
		//  - We need 'any-offset' to catch all ZIP cases, but it can match some of the file types
		//    above in some conditions, which means we have to check for it last.
		if (hasZipMarker) {
			ZipFileInfoBuilder builder = new ZipFileInfoBuilder()
					.withProperty(new ZipMarkerProperty())
					.withRawContent(data)
					.withName(name);

			// Record name, handle extension to determine info-type
			String extension = IOUtil.getExtension(name);
			if (extension == null) return builder.build();
			return switch (extension.toUpperCase()) {
				case "JAR" -> builder.asJar().build();
				case "APK" -> builder.asApk().build();
				case "WAR" -> builder.asWar().build();
				case "JMOD" -> builder.asJMod().build();
				default -> builder.build();
			};
		}

		// No special case known for file, treat as generic file
		// Will be automatically mapped to a text file if the contents are all mappable characters.
		return new FileInfoBuilder<>()
				.withRawContent(data)
				.withName(name)
				.build();
	}

	/**
	 * @param name
	 * 		Name of file.
	 * @param data
	 * 		File content.
	 *
	 * @return The {@link FileInfo} subtype of matched special cases <i>(Media, executables, etc.)</i>
	 * or {@code null} if no special case is matched.
	 */
	@Nullable
	private static FileInfo readAsSpecializedFile(@Nonnull String name, byte[] header, byte[] data) {
		if (ByteHeaderUtil.match(header, ByteHeaderUtil.DEX)) {
			return new DexFileInfoBuilder()
					.withRawContent(data)
					.withName(name)
					.build();
		} else if (ByteHeaderUtil.match(header, ByteHeaderUtil.MODULES)) {
			return new ModulesFileInfoBuilder()
					.withRawContent(data)
					.withName(name)
					.build();
		} else if (name.toUpperCase().endsWith(".ARSC") &&
				ByteHeaderUtil.match(header, ByteHeaderUtil.ARSC)) {
			return new ArscFileInfoBuilder()
					.withRawContent(data)
					.withName(name)
					.build();
		} else if (name.toUpperCase().endsWith(".XML") &&
				(ByteHeaderUtil.match(header, ByteHeaderUtil.BINARY_XML) || AndroidXmlUtil.hasXmlIndicators(data))) {
			return new BinaryXmlFileInfoBuilder()
					.withRawContent(data)
					.withName(name)
					.build();
		} else if (ByteHeaderUtil.matchAny(header, ByteHeaderUtil.IMAGE_HEADERS)) {
			return new ImageFileInfoBuilder()
					.withRawContent(data)
					.withName(name)
					.build();
		} else if (ByteHeaderUtil.matchAny(header, ByteHeaderUtil.AUDIO_HEADERS)) {
			return new AudioFileInfoBuilder()
					.withRawContent(data)
					.withName(name)
					.build();
		} else if (ByteHeaderUtil.matchAny(header, ByteHeaderUtil.VIDEO_HEADERS)) {
			return new VideoFileInfoBuilder()
					.withRawContent(data)
					.withName(name)
					.build();
		} else if (ByteHeaderUtil.matchAny(header, ByteHeaderUtil.PROGRAM_HEADERS)) {
			return new NativeLibraryFileInfoBuilder()
					.withRawContent(data)
					.withName(name)
					.build();
		}
		return null;
	}

	private static boolean hasZipPrefix(@Nonnull byte[] header) {
		return ByteHeaderUtil.match(header, ByteHeaderUtil.ZIP)
				|| ByteHeaderUtil.match(header, ByteHeaderUtil.ZIP_EMPTY)
				|| ByteHeaderUtil.match(header, ByteHeaderUtil.ZIP_SPANNED);
	}

	private static boolean shouldScanForZip(@Nonnull String name, @Nullable FileInfo info) {
		String extension = IOUtil.getExtension(name);
		if (extension != null) {
			switch (extension.toUpperCase(Locale.ROOT)) {
				case "ZIP", "JAR", "WAR", "APK", "JMOD" -> {
					return true;
				}
			}
		}

		// Self-extracting archives commonly present as native executables with a ZIP appended to them.
		return info != null && info.isNativeLibraryFile();
	}

	@Nonnull
	private Info readClass(@Nonnull String name, @Nonnull byte[] data) throws Throwable {
		var patchingMode = config.getClassPatchMode();

		// If we're skipping validation just parse the class file as-is and don't run validation checks.
		// Because the validation steps are skipped problems that would otherwise be caught and patched with
		// higher tier patch modes will occur when opening the class later. Users must accept this responsibility
		// if they want the boost in workspace load speeds.
		if (patchingMode == InfoImporterConfig.ClassPatchMode.SKIP_FILTER)
			return withReferencedClasses(new JvmClassInfoBuilder(data, ClassReader.SKIP_CODE).build());

		// If we're always validating, patch the class and try and parse the patched output.
		// Any ASM parse failures imply patching has failed, and the class will be treated as a file instead (see catch block in calling methods)
		if (patchingMode == InfoImporterConfig.ClassPatchMode.ALWAYS_FILTER) {
			byte[] patched = classPatcher.patch(name, data);
			return withReferencedClasses(new JvmClassInfoBuilder(patched, 0)
					.skipValidationChecks(false)
					.build());
		}

		// We're doing a check-then-filter. If ASM reads the class as-is without issue, keep the result.
		// Otherwise, patch when we encounter parse problems and try again.
		int readerFlags = patchingMode == InfoImporterConfig.ClassPatchMode.CHECK_ADVANCED_THEN_FILTER ? ClassReader.SKIP_CODE : 0;
		try {
			return withReferencedClasses(new JvmClassInfoBuilder()
					.skipValidationChecks(false)
					.adaptFrom(data, readerFlags)
					.build());
		} catch (Throwable t) {
			// Patch if not compatible with ASM
			byte[] patched = classPatcher.patch(name, data);
			try {
				JvmClassInfo patchedClassInfo = withReferencedClasses(new JvmClassInfoBuilder(patched, readerFlags)
						.skipValidationChecks(false)
						.build());
				logger.debug("CafeDude patched class: {}", name);
				return patchedClassInfo;
			} catch (Throwable t1) {
				logger.error("CafeDude patching output is still non-compliant with ASM for file: {}", formatConfig.filter(name));
				throw t1;
			}
		}
	}

	@Nonnull
	private static JvmClassInfo withReferencedClasses(@Nonnull JvmClassInfo info) {
		// Mapping application uses this constant-pool view as a cheap prefilter. Compute it while import work
		// is already distributed across ZIP-entry workers so later mapping passes do not pay this cost serially.
		info.getReferencedClasses();
		return info;
	}


	/**
	 * Check if the byte array is prefixed by the class file magic header.
	 *
	 * @param content
	 * 		File content.
	 *
	 * @return If the content seems to be a class at a first glance.
	 */
	private static boolean matchesClass(byte[] content) {
		// Null and size check
		// The smallest valid class possible that is verifiable is 37 bytes AFAIK, but we'll be generous here.
		if (content == null || content.length <= 16)
			return false;

		// We want to make sure the 'magic' is correct.
		if (!ByteHeaderUtil.match(content, ByteHeaderUtil.CLASS))
			return false;

		// 'dylib' files can also have CAFEBABE as a magic header... Gee, thanks Apple :/
		// Because of this we'll employ some more sanity checks.
		// Version number must be non-zero
		int version = ((content[6] & 0xFF) << 8) + (content[7] & 0xFF);
		if (version < VersionConstants.JAVA1)
			return false;

		// Must include some constant pool entries.
		// The smallest number includes:
		//  - utf8  - name of current class
		//  - class - wrapper of prior
		//  - utf8  - name of object class
		//  - class - wrapper of prior`
		int cpSize = ((content[8] & 0xFF) << 8) + (content[9] & 0xFF);
		return cpSize >= 4;
	}

	@Nonnull
	@Override
	public String getServiceId() {
		return SERVICE_ID;
	}

	@Nonnull
	@Override
	public InfoImporterConfig getServiceConfig() {
		return config;
	}
}
