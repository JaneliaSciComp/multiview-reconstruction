package net.preibisch.mvrecon.process.splitting;

import java.util.ArrayList;

import net.imglib2.Interval;

/**
 * Base result of splitting a single view.
 * Subclasses can add splitter-specific statistics (e.g. oct-tree depth, consensus sets).
 */
public class SplitResult
{
	public final ArrayList< Interval > intervals;
	public final int numIntervals;

	public SplitResult( final ArrayList< Interval > intervals )
	{
		this.intervals = intervals;
		this.numIntervals = intervals.size();
	}
}
