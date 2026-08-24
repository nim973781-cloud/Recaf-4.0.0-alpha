package software.coley.recaf.services.decompile.batch;

/**
 * Output shapes supported by {@link BatchDecompileEngine}.
 *
 * @author Matt Coley
 */
public enum BatchOutputFormat {
	/** Write each output file directly onto the file system, rooted at the request output path. */
	DIRECTORY,
	/** Write all output files as entries of a single archive located at the request output path. */
	ZIP
}
