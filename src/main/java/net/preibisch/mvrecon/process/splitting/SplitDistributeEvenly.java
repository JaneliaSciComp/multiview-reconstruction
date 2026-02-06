package net.preibisch.mvrecon.process.splitting;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import fiji.util.gui.GenericDialogPlus;
import ij.gui.GenericDialog;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.iterator.LocalizingZeroMinIntervalIterator;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;

public class SplitDistributeEvenly implements SplitInterval
{
	public static long defaultImgX = 256;
	public static long defaultImgY = 256;
	public static long defaultImgZ = 128;

	public static long defaultOverlapX = 60;
	public static long defaultOverlapY = 60;
	public static long defaultOverlapZ = 20;

	public static boolean defaultOptimize = true;

	final long[] overlapPx;
	final long[] targetSize;
	final long[] minStepSize;
	final boolean optimize;

	public SplitDistributeEvenly( final long[] overlapPx, final long[] targetSize, final long[] minStepSize, final boolean optimize )
	{
		this.overlapPx = overlapPx.clone();
		this.targetSize = targetSize.clone();
		this.minStepSize = minStepSize.clone();
		this.optimize = optimize;
	}

	/*
	 * computes a set of overlapping intervals with desired target size and overlap. Importantly, minStepSize is computed from the multi-resolution pyramid and constrains 
	 * that intervals need to be divisible by minStepSize (except the last one) AND that the offsets where images start are divisble by minStepSize.
	 * 
	 * Otherwise one would need to recompute the multi-resolution pyramid.
	 * 
	 * @param input
	 * @param overlapPx
	 * @param targetSize
	 * @param minStepSize
	 * @param optimize - optimize targetsize to make tiles as equal as possible
	 * @return
	 */
	//public static ArrayList< Interval > distributeIntervalsFixedOverlap( final Interval input, final long[] overlapPx, final long[] targetSize, final long[] minStepSize, final boolean optimize )
	public ArrayList< Interval > split( final Interval input )
	{
		for ( int d = 0; d < input.numDimensions(); ++d )
		{
			if ( targetSize[ d ] % minStepSize[ d ] != 0 )
			{
				IOFunctions.printErr( "targetSize " + targetSize[ d ] + " not divisible by minStepSize " + minStepSize[ d ] + " for dim=" + d + ". stopping." );
				return null;
			}

			if ( overlapPx[ d ] % minStepSize[ d ] != 0 )
			{
				IOFunctions.printErr( "overlapPx " + overlapPx[ d ] + " not divisible by minStepSize " + minStepSize[ d ] + " for dim=" + d + ". stopping." );
				return null;
			}
		}

		final ArrayList< ArrayList< Pair< Long, Long > > > intervalBasis = new ArrayList<>();

		for ( int d = 0; d < input.numDimensions(); ++d )
		{
			//System.out.println( "dim="+ d);
			final ArrayList< Pair< Long, Long > > dimIntervals = new ArrayList<>();
	
			final long length = input.dimension( d );

			// can I use just 1 block?
			if ( length <= targetSize[ d ] )
			{
				final long min = input.min( d );
				final long max = input.max( d );

				dimIntervals.add( new ValuePair< Long, Long >( min, max ) );
				//System.out.println( "one block from " + min + " to " + max );
			}
			else
			{
				final long l = length;
				final long s = targetSize[ d ];
				final long o = overlapPx[ d ];

				// now we iterate the targetsize until we are as close as possible to an equal distribution (ideally 0.0 fraction)

				long lastImageSize = lastImageSize(l, s, o);// o + ( l - 2 * ( s-o ) - o ) % ( s - 2 * o + o );

				//System.out.println( "length: " + l );
				//System.out.println( "overlap: " + o );
				//System.out.println( "targetSize: " + s );
				//System.out.println( "lastImageSize: " + lastImageSize );

				final long finalSize;

				if ( optimize && lastImageSize != s )
				{
					long lastSize = s;
					long delta, currentLastImageSize;

					if ( lastImageSize <= s / 2 )
					{
						// increase image size until lastImageSize goes towards zero, then large
						//System.out.println( "small" );

						do
						{
							lastSize += minStepSize[ d ];
							currentLastImageSize = lastImageSize(l, lastSize, o);
							delta = lastImageSize - currentLastImageSize;

							lastImageSize = currentLastImageSize;
							//System.out.println( lastSize + ": " + lastImageSize + ", delta=" + delta );
						}
						while ( delta > 0 );

						finalSize = lastSize;
					}
					else
					{
						// decrease image size until lastImageSize is maximal 
						//System.out.println( "large" );

						do
						{
							lastSize -= minStepSize[ d ];
							currentLastImageSize = lastImageSize(l, lastSize, o);
							delta = lastImageSize - currentLastImageSize;

							lastImageSize = currentLastImageSize;
							//System.out.println( lastSize + ": " + lastImageSize + ", delta=" + delta );
						}
						while ( delta < 0 );

						finalSize = lastSize + minStepSize[ d ];
					}
				}
				else
				{
					finalSize = s;
				}

				//System.out.println( "finalSize: " + finalSize );
				//System.out.println( "finalLastImageSize: " + lastImageSize(l, finalSize, o) );

				dimIntervals.addAll( splitDim( input, d, finalSize, overlapPx[ d ] ) );
			}

			intervalBasis.add( dimIntervals );
		}

		final long[] numIntervals = new long[ input.numDimensions() ];

		for ( int d = 0; d < input.numDimensions(); ++d )
			numIntervals[ d ] = intervalBasis.get( d ).size();

		final LocalizingZeroMinIntervalIterator cursor = new LocalizingZeroMinIntervalIterator( numIntervals );
		final ArrayList< Interval > intervalList = new ArrayList<>();

		final int[] currentInterval = new int[ input.numDimensions() ];

		while ( cursor.hasNext() )
		{
			cursor.fwd();
			cursor.localize( currentInterval );

			final long[] min = new long[ input.numDimensions() ];
			final long[] max = new long[ input.numDimensions() ];

			for ( int d = 0; d < input.numDimensions(); ++d )
			{
				final Pair< Long, Long > minMax = intervalBasis.get( d ).get( currentInterval[ d ] );
				min[ d ] = minMax.getA();
				max[ d ] = minMax.getB();
			}

			intervalList.add( new FinalInterval( min, max ) );
		}

		return intervalList;
	}

	public static ArrayList< Pair< Long, Long > > splitDim(
			final Interval input,
			final int d,
			final long s,
			final long o )
	{
		//System.out.println( "min=" + input.min( d ) + ", max=" + input.max( d ) );

		final ArrayList< Pair< Long, Long > > dimIntervals = new ArrayList<>();

		long from = input.min( d );
		long to;

		do
		{
			to = Math.min( input.max( d ), from + s - 1 );
			dimIntervals.add( new ValuePair<>( from, to ) );

			//System.out.println( "block " + (dimIntervals.size() - 1) + ": " + from + " " + to + " (size=" + (to-from+1) + ")" );

			//SimpleMultiThreading.threadWait( 100 );
			from = to - o + 1;
		}
		while ( to < input.max( d ) );

		return dimIntervals;
	}

	public static long lastImageSize( final long l, final long s, final long o)
	{
		long size = o + ( l - 2 * ( s-o ) - o ) % ( s - 2 * o + o );

		// this happens when it is only two overlapping images
		if ( size < 0 )
			size = l + size;

		return size;
	}

	@Override
	public String description() {
		return "overlapPx=" + Arrays.toString( overlapPx ) +
				", targetSize=" + Arrays.toString( targetSize ) +
				", minStepSize=" + Arrays.toString( minStepSize ) +
				", optimize=" + optimize;
	}

	public static void setupGUI( final GenericDialog gd, final SpimData2 data, final long[] minStepSize )
	{
		final Pair< HashMap< String, Integer >, long[] > imgSizes = SplittingTools.collectImageSizes( data );

		IOFunctions.println( "Current image sizes of dataset :");

		for ( final String size : imgSizes.getA().keySet() )
			IOFunctions.println( imgSizes.getA().get( size ) + "x: " + size );

		defaultImgX = closestLargerLongDivisableBy( defaultImgX, minStepSize[ 0 ] );
		defaultImgY = closestLargerLongDivisableBy( defaultImgY, minStepSize[ 1 ] );
		defaultImgZ = closestLargerLongDivisableBy( defaultImgZ, minStepSize[ 2 ] );

		defaultOverlapX = closestLargerLongDivisableBy( defaultOverlapX, minStepSize[ 0 ] );
		defaultOverlapY = closestLargerLongDivisableBy( defaultOverlapY, minStepSize[ 1 ] );
		defaultOverlapZ = closestLargerLongDivisableBy( defaultOverlapZ, minStepSize[ 2 ] );

		gd.addSlider( "Target_Image_Size_X", 100, 2000, defaultImgX, minStepSize[ 0 ] );
		gd.addSlider( "Target_Image_Size_Y", 100, 2000, defaultImgY, minStepSize[ 1 ] );
		gd.addSlider( "Target_Image_Size_Z", 100, 2000, defaultImgZ, minStepSize[ 2 ] );

		gd.addCheckbox( "Optimize_image_sizes per view", defaultOptimize );

		gd.addMessage( "Note: new sizes will be adjusted to be divisible by " + Arrays.toString( minStepSize ), GUIHelper.mediumstatusfont, Color.RED );
		gd.addMessage( "" );

		gd.addSlider( "Overlap_X", 10, 200, defaultOverlapX, minStepSize[ 0 ] );
		gd.addSlider( "Overlap_Y", 10, 200, defaultOverlapY, minStepSize[ 1 ] );
		gd.addSlider( "Overlap_Z", 10, 200, defaultOverlapZ, minStepSize[ 2 ] );

		gd.addMessage( "Note: overlap will be adjusted to be divisible by " + Arrays.toString( minStepSize ), GUIHelper.mediumstatusfont, Color.RED );
		gd.addMessage( "Minimal image sizes per dimension: " + Util.printCoordinates( imgSizes.getB() ), GUIHelper.mediumstatusfont, Color.DARK_GRAY );
	}

	public static SplitDistributeEvenly queryGUI( final GenericDialog gd, final long[] minStepSize )
	{
		final long sx = defaultImgX = closestLargerLongDivisableBy( Math.round( gd.getNextNumber() ), minStepSize[ 0 ] );
		final long sy = defaultImgY = closestLargerLongDivisableBy( Math.round( gd.getNextNumber() ), minStepSize[ 1 ] );
		final long sz = defaultImgZ = closestLargerLongDivisableBy( Math.round( gd.getNextNumber() ), minStepSize[ 2 ] );

		final boolean optimize = defaultOptimize = gd.getNextBoolean();

		final long ox = defaultOverlapX = closestLargerLongDivisableBy( Math.round( gd.getNextNumber() ), minStepSize[ 0 ] );
		final long oy = defaultOverlapY = closestLargerLongDivisableBy( Math.round( gd.getNextNumber() ), minStepSize[ 1 ] );
		final long oz = defaultOverlapZ = closestLargerLongDivisableBy( Math.round( gd.getNextNumber() ), minStepSize[ 2 ] );

		System.out.println( sx + ", " + sy + ", " + sz + ", " + ox  + ", " + oy  + ", " + oz );

		if ( ox > sx || oy > sy || oz > sz )
		{
			IOFunctions.println( "overlap cannot be bigger than size" );

			return null;
		}

		return new SplitDistributeEvenly( new long[] { ox, oy, oz }, new long[] { sx, sy, sz }, minStepSize, optimize );
	}

	public static long closestSmallerLongDivisableBy( final long a, final long b )
	{
		if ( a == b || a == 0 || a % b == 0  )
			return a;
		else
			return a - (a % b);
	}

	public static long closestLargerLongDivisableBy( final long a, final long b )
	{
		if ( a == b || a == 0 || a % b == 0 )
			return a;
		else
			return (a + b) - (a % b);
	}

	public static long closestLongDivisableBy( final long a, final long b)
	{
		final long c1 = closestSmallerLongDivisableBy( a, b );//a - (a % b);
		final long c2 = closestLargerLongDivisableBy( a, b ); //(a + b) - (a % b);

		if (a - c1 > c2 - a)
			return c2;
		else
			return c1;
	}

	public static void main( String[] args )
	{
		Interval input = new FinalInterval( new long[]{ 0 }, new long[] { 14192 - 1 } );

		long[] overlapPx = new long[] { 128 };
		long[] targetSize = new long[] { 6000 };
		long[] minStepSize = new long[] { 64 };

		targetSize[ 0 ] = SplitDistributeEvenly.closestLongDivisableBy( targetSize[ 0 ], minStepSize[ 0 ] );
		overlapPx[ 0 ] = SplitDistributeEvenly.closestLargerLongDivisableBy( overlapPx[ 0 ], minStepSize[ 0 ] );

		boolean optimize = true;

		ArrayList< Interval > intervals = new SplitDistributeEvenly( overlapPx, targetSize, minStepSize, optimize).split( input );

		System.out.println();

		for ( final Interval interval : intervals )
			System.out.println( Util.printInterval( interval ) );
	}
}
