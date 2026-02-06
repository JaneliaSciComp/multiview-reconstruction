package net.preibisch.mvrecon.process.splitting;

import java.util.ArrayList;
import java.util.List;

import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;

public interface SplitInterval
{
	public ArrayList< Interval > split( final Interval input );
	public String description();

	public default int maxIntervalSpread( final List< ViewSetup > oldSetups/*, final long[] overlapPx, final long[] targetSize, final long[] minStepSize, final boolean optimize */ )
	{
		int max = 1;

		//SplitDistributeEvenly split = new SplitDistributeEvenly( overlapPx, targetSize, minStepSize, optimize );

		for ( final ViewSetup oldSetup : oldSetups )
		{
			final Interval input = new FinalInterval( oldSetup.getSize() );
			final ArrayList< Interval > intervals = split( input );

			max = Math.max( max, intervals.size() );
		}

		return max;
	}

}
