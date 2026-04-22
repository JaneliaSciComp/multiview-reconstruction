package net.preibisch.mvrecon.process.splitting;

import java.io.Serializable;
import java.util.ArrayList;

import net.imglib2.FinalInterval;
import net.imglib2.Interval;

/**
 * Base result of splitting a single view.
 * Serializable for Spark transport. Stores intervals as long[][] internally.
 * Subclasses can add splitter-specific statistics (e.g. oct-tree depth, consensus sets).
 */
public class SplitResult implements Serializable
{
	private static final long serialVersionUID = 1L;

	private final ArrayList< long[][] > serializedIntervals;
	public final int numIntervals;

	public SplitResult( final ArrayList< Interval > intervals )
	{
		this.numIntervals = intervals.size();
		this.serializedIntervals = new ArrayList<>( numIntervals );
		for ( final Interval interval : intervals )
			serializedIntervals.add( new long[][]{ interval.minAsLongArray(), interval.maxAsLongArray() } );
	}

	public ArrayList< Interval > getIntervals()
	{
		final ArrayList< Interval > intervals = new ArrayList<>( numIntervals );
		for ( final long[][] minMax : serializedIntervals )
			intervals.add( new FinalInterval( minMax[ 0 ], minMax[ 1 ] ) );
		return intervals;
	}
}
