package software.coley.recaf.workspace.model.resource;

import jakarta.annotation.Nonnull;
import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.info.properties.BasicPropertyContainer;
import software.coley.recaf.util.ClasspathUtil;
import software.coley.recaf.util.IOUtil;
import software.coley.recaf.util.threading.ThreadPoolFactory;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.BasicFileBundle;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.bundle.FileBundle;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.bundle.VersionedJvmClassBundle;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Implementation of a workspace resource sourced from runtime classes.
 * This is a special case of resource which is automatically added to all workspaces.
 * Listeners and such are not implemented and are ignored by design.
 *
 * @author Matt Coley
 */
public class RuntimeWorkspaceResource extends BasicPropertyContainer implements WorkspaceResource {
	private static final Logger logger = Logging.get(RuntimeWorkspaceResource.class);
	private static final Map<String, Optional<JvmClassInfo>> cache = new ConcurrentHashMap<>();
	private static final ThreadLocal<byte[]> loadBuffer = ThreadLocal.withInitial(IOUtil::newByteBuffer);
	private final JvmClassBundle classes;
	private final FileBundle files;

	/**
	 * @return Instance of runtime workspace resource.
	 */
	public static RuntimeWorkspaceResource getInstance() {
		return InstanceHolder.INSTANCE;
	}

	private RuntimeWorkspaceResource() {
		classes = new BasicJvmClassBundle() {
			@Override
			public JvmClassInfo get(@Nonnull Object name) {
				String key = name.toString();
				if (key.indexOf('.') >= 0)
					key = key.replace('.', '/');
				String normalizedKey = key;
				return cache.computeIfAbsent(normalizedKey, this::load).orElse(null);
			}

			@Nonnull
			private Optional<JvmClassInfo> load(@Nonnull String key) {
				byte[] value = null;
				try (InputStream in = ClassLoader.getSystemResourceAsStream(key + ".class")) {
					if (in != null)
						value = IOUtil.toByteArray(in, loadBuffer.get());
				} catch (IOException ex) {
					logger.error("Failed to fetch runtime bytecode of class: " + key, ex);
				}
				if (value == null)
					return Optional.empty();

				try {
					return Optional.of(new JvmClassInfoBuilder(value, ClassReader.SKIP_CODE).build());
				} catch (Throwable t) {
					// Some generated VM classes have malformed constant pools. Memoize the failure too.
					return Optional.empty();
				}
			}

			@Override
			public Set<String> keySet() {
				return cache.entrySet().stream()
						.filter(entry -> entry.getValue().isPresent())
						.map(Entry::getKey)
						.collect(java.util.stream.Collectors.toUnmodifiableSet());
			}

			@Override
			public Collection<JvmClassInfo> values() {
				return cache.values().stream()
						.flatMap(Optional::stream)
						.toList();
			}

			@Override
			public Set<Entry<String, JvmClassInfo>> entrySet() {
				return cache.entrySet().stream()
						.filter(entry -> entry.getValue().isPresent())
						.collect(java.util.stream.Collectors.toUnmodifiableMap(
								Entry::getKey, entry -> entry.getValue().orElseThrow()))
						.entrySet();
			}

			@Override
			public void incrementHistory(@Nonnull JvmClassInfo info) {
				// no-op
			}

			@Override
			public void decrementHistory(@Nonnull String key) {
				// no-op
			}

			@Override
			public JvmClassInfo remove(@Nonnull Object key) {
				return get(key);
			}

			@Override
			public boolean containsKey(@Nonnull Object key) {
				return get(key) != null;
			}

			@Override
			public boolean isEmpty() {
				return false;
			}

			@Override
			public int size() {
				return (int) cache.values().stream().filter(Optional::isPresent).count();
			}

			@Override
			public void clear() {
				// no-op
			}
		};
		files = new BasicFileBundle();

		// Populate the system classes in the background.
		try (ExecutorService ex = ThreadPoolFactory.newSingleThreadExecutor("runtime-class-population")) {
			CompletableFuture.runAsync(() -> {
				for (String name : ClasspathUtil.getSystemClassSet())
					classes.get(name);
			}, ex);
		}
	}

	@Nonnull
	@Override
	public JvmClassBundle getJvmClassBundle() {
		return classes;
	}

	@Nonnull
	@Override
	public FileBundle getFileBundle() {
		return files;
	}

	@Nonnull
	@Override
	public NavigableMap<Integer, VersionedJvmClassBundle> getVersionedJvmClassBundles() {
		return Collections.emptyNavigableMap();
	}

	@Nonnull
	@Override
	public Map<String, AndroidClassBundle> getAndroidClassBundles() {
		return Collections.emptyMap();
	}

	@Nonnull
	@Override
	public Map<String, WorkspaceFileResource> getEmbeddedResources() {
		return Collections.emptyMap();
	}

	@Override
	public WorkspaceResource getContainingResource() {
		// Not applicable
		return null;
	}

	@Override
	public void setContainingResource(WorkspaceResource resource) {
		// no-op
	}

	@Override
	public void addResourceJvmClassListener(ResourceJvmClassListener listener) {
		// no-op
	}

	@Override
	public void removeResourceJvmClassListener(ResourceJvmClassListener listener) {
		// no-op
	}

	@Override
	public void addResourceAndroidClassListener(ResourceAndroidClassListener listener) {
		// no-op
	}

	@Override
	public void removeResourceAndroidClassListener(ResourceAndroidClassListener listener) {
		// no-op
	}

	@Override
	public void addResourceFileListener(ResourceFileListener listener) {
		// no-op
	}

	@Override
	public void removeResourceFileListener(ResourceFileListener listener) {
		// no-op
	}

	@Override
	public void close() {
		// no-op
	}

	@Override
	public boolean isInternal() {
		return true;
	}

	/** Statically initialized inner class used as a lightweight alternative to double-sync block. */
	private static final class InstanceHolder {
		private static final RuntimeWorkspaceResource INSTANCE = new RuntimeWorkspaceResource();
	}
}
