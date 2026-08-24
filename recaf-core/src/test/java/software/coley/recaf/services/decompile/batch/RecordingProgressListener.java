package software.coley.recaf.services.decompile.batch;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects every progress event a run emits.
 */
final class RecordingProgressListener implements BatchDecompileProgressListener {
	private final List<BatchDecompileProgress> events = new CopyOnWriteArrayList<>();

	@Override
	public void onProgress(@Nonnull BatchDecompileProgress event) {
		events.add(event);
	}

	@Nonnull
	List<BatchDecompileProgress> events() {
		return events;
	}

	@Nullable
	BatchDecompileProgress last() {
		return events.isEmpty() ? null : events.get(events.size() - 1);
	}
}
