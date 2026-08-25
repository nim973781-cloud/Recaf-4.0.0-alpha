package software.coley.recaf.util.threading;

/**
 * Sizes decompile work from the machine's CPU count.
 * <p>
 * Batch decompilation is CPU-bound inside the decompiler, so the default is a fraction of
 * {@link Runtime#availableProcessors()} rather than "all cores minus two". That fraction is large
 * enough to keep a 20-core box busy, and small enough that the UI, resource IO and GC still have
 * cores left. Callers that pass an explicit worker count keep that count.
 *
 * @author Matt Coley
 */
public final class DecompileParallelism {
	/**
	 * Share of detected cores given to decompilation.
	 */
	public static final double DECOMPILE_LOAD = 0.70;
	private static final int IO_CAP = 4;

	private DecompileParallelism() {
	}

	/**
	 * @return {@link Runtime#availableProcessors()}, at least one.
	 */
	public static int availableProcessors() {
		return Math.max(1, Runtime.getRuntime().availableProcessors());
	}

	/**
	 * Threads used for class decompilation on this machine: {@value #DECOMPILE_LOAD} of
	 * {@link #availableProcessors()}.
	 *
	 * @return Decompile thread count.
	 */
	public static int decompileThreads() {
		return decompileThreads(availableProcessors());
	}

	/**
	 * @param processors
	 * 		Detected core count. Values below one are treated as one.
	 *
	 * @return {@value #DECOMPILE_LOAD} of {@code processors}, at least one, and at least two
	 * when there are three or more cores so a batch run is never accidentally serial.
	 */
	public static int decompileThreads(int processors) {
		int n = Math.max(1, processors);
		int threads = (int) Math.round(n * DECOMPILE_LOAD);
		int floor = n >= 3 ? 2 : 1;
		return Math.max(floor, threads);
	}

	/**
	 * Resource-write threads on this machine: whatever cores decompilation did not take, capped
	 * so IO does not fight the decompiler for the whole box.
	 *
	 * @return IO thread count.
	 */
	public static int ioThreads() {
		return ioThreads(availableProcessors());
	}

	/**
	 * @param processors
	 * 		Detected core count. Values below one are treated as one.
	 *
	 * @return Remainder after {@link #decompileThreads(int)}, at least one, at most {@value #IO_CAP}.
	 */
	public static int ioThreads(int processors) {
		int n = Math.max(1, processors);
		return Math.max(1, Math.min(IO_CAP, n - decompileThreads(n)));
	}
}
