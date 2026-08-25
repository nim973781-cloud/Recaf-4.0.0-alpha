package software.coley.recaf.services.decompile.batch;

/**
 * Counters describing what a {@link BatchDecompileSink} has written.
 *
 * @param fileCount
 * 		Number of files/entries written.
 * @param byteCount
 * 		Total uncompressed bytes written.
 * @param duplicateCount
 * 		Number of writes rejected because an entry with the same output path already existed.
 *
 * @author Matt Coley
 */
public record BatchSinkStats(long fileCount, long byteCount, long duplicateCount) {}
