package net.preibisch.mvrecon.process.splitting;

import java.util.List;

import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;

/**
 * Interface for splitting views into sub-intervals.
 * Implementations look up the view's dimensions from SpimData internally.
 */
public interface SplitView
{
	/**
	 * Split a single view into sub-intervals.
	 *
	 * @param viewId the view to split
	 * @return split result containing intervals and statistics, or null on error
	 */
	SplitResult split( ViewId viewId );

	/**
	 * @return human-readable description of this splitter's configuration
	 */
	String description();

	/**
	 * Aggregate statistics from multiple split results into a summary string.
	 * Called after parallel splitting to produce a single log message.
	 *
	 * @param results list of per-view split results
	 * @return formatted summary string
	 */
	String aggregateStatistics( List< ? extends SplitResult > results );

	/**
	 * Compute the maximum number of intervals any single view could produce.
	 * Used to determine tile ID spacing.
	 *
	 * @param oldSetups the view setups to consider
	 * @return upper bound on interval count per view
	 */
	int maxIntervalSpread( List< ViewSetup > oldSetups );
}
