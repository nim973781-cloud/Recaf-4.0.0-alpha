package software.coley.recaf.services.decompile.fallback.print;

import jakarta.annotation.Nonnull;
import org.objectweb.asm.Type;
import software.coley.recaf.services.text.TextFormatConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Session-level cache for descriptor to display-name conversions used by the fallback printers.
 * <p/>
 * The conversions are pure functions of the descriptor and the {@link TextFormatConfig} the cache was built
 * with, so memoizing them cannot change output; it only removes repeated descriptor parsing and string
 * filtering when many classes of one workspace share the same field and method shapes. A cache instance is
 * bound to one format config and is <b>not</b> thread-safe; sessions use one instance per run.
 *
 * @author Matt Coley
 * @see software.coley.recaf.services.decompile.fallback.FallbackSessionFactory Session holding one cache per run.
 */
public class TypeNameCache {
	private final Map<String, Type> methodTypes = new HashMap<>();
	private final Map<String, String> filteredFieldNames = new HashMap<>();
	private final Map<String, String> escapedTypeNames = new HashMap<>();
	private final TextFormatConfig format;

	/**
	 * @param format
	 * 		Format config the cached conversions are computed with.
	 */
	public TypeNameCache(@Nonnull TextFormatConfig format) {
		this.format = format;
	}

	/**
	 * @param descriptor
	 * 		Method descriptor.
	 *
	 * @return Parsed method type.
	 */
	@Nonnull
	public Type getMethodType(@Nonnull String descriptor) {
		return methodTypes.computeIfAbsent(descriptor, Type::getMethodType);
	}

	/**
	 * @param descriptor
	 * 		Field descriptor.
	 *
	 * @return Short display name as printed by {@link ClassPrinter} field declarations,
	 * computed with {@link TextFormatConfig#filter(String)}.
	 */
	@Nonnull
	public String getFilteredFieldName(@Nonnull String descriptor) {
		return filteredFieldNames.computeIfAbsent(descriptor, desc ->
				shorten(format.filter(Type.getType(desc).getClassName())));
	}

	/**
	 * @param type
	 * 		Return or argument type of a method.
	 *
	 * @return Short display name as printed by {@link MethodPrinter} declarations,
	 * computed with {@link TextFormatConfig#filterEscape(String)}.
	 */
	@Nonnull
	public String getEscapedTypeName(@Nonnull Type type) {
		return escapedTypeNames.computeIfAbsent(type.getDescriptor(), desc ->
				shorten(format.filterEscape(type.getClassName())));
	}

	@Nonnull
	private static String shorten(@Nonnull String typeName) {
		if (typeName.contains("."))
			return typeName.substring(typeName.lastIndexOf('.') + 1);
		return typeName;
	}
}
