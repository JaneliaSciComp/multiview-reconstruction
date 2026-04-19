/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2025 Multiview Reconstruction developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package net.preibisch.mvrecon.process.quality;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import ij.IJ;
import ij.process.FloatProcessor;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.KDTree;
import net.imglib2.Point;
import net.imglib2.PointSampleList;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.converter.RealFloatConverter;
import net.imglib2.interpolation.neighborsearch.InverseDistanceWeightingInterpolatorFactory;
import net.imglib2.interpolation.neighborsearch.NearestNeighborSearchInterpolatorFactory;
import net.imglib2.neighborsearch.KNearestNeighborSearch;
import net.imglib2.neighborsearch.KNearestNeighborSearchOnKDTree;
import net.imglib2.neighborsearch.NearestNeighborSearch;
import net.imglib2.neighborsearch.NearestNeighborSearchOnKDTree;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.RealSum;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.process.fusion.FusionTools;

/**
 * Computes the fourier ring correlation at specific positions and interpolates between all points
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 * @param <T> pixel type
 */
public class FRCRealRandomAccessible< T extends RealType< T > > implements RealRandomAccessible< FloatType >
{
	final PointSampleList< FloatType > qualityList;
	final Interval interval;
	final int n;

	public static int relativeFRCDist = 5;

	public FRCRealRandomAccessible(
			final RandomAccessibleInterval< T > input,
			final List< Point > locations,
			final int length,
			final boolean relative,
			final ExecutorService service )
	{
		this.n = input.numDimensions();

		this.interval = new FinalInterval( input );
		this.qualityList = new PointSampleList<>( input.numDimensions() );

		final RandomAccessible< FloatType > floatInput = Views.extendMirrorSingle( getFloatRAI( input ) );
		final ArrayList< Callable< Void > > tasks = new ArrayList< Callable< Void > >();
		final AtomicInteger progress = new AtomicInteger( 0 );

		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Computing FRC for " + locations.size()  + " locations, length=" + length + ", relative=" + relative );

		IJ.showProgress( 0.01 );

		for ( final Point l : locations )
		{
			tasks.add( new Callable< Void >()
			{
				@Override
				public Void call() throws Exception
				{
					final double quality;

					if ( relative )
						quality = computeRelativeFRC( floatInput, l, length, relativeFRCDist );
					else
						quality = computeFRC( floatInput, l, length );

					synchronized ( qualityList )
					{
						qualityList.add( l, new FloatType( (float)quality ) );
					}

					IJ.showProgress( (double)progress.incrementAndGet() / locations.size() );

					return null;
				}
			});
		}

		if ( service == null )
			FusionTools.execTasks( tasks, Threads.numThreads(), "frc" );
		else
			FusionTools.execTasks( tasks, service, "frc" );

		IJ.showProgress( 1.0 );

		/*
		for ( final Point l : locations )
		{
			final double quality = computeFRC( floatInput, l, length );
			qualityList.add( l, new FloatType( (float)quality ) );
			
			System.out.println( l + ": " + quality );
		}*/
	}

	public double getTotalAvgQuality()
	{
		final RealSum sum = new RealSum( (int)getQualityList().size() );

		for ( final FloatType q : getQualityList() )
			sum.add( q.get() );

		return sum.getSum() / getQualityList().size();
	}

	@Override
	public int numDimensions() { return qualityList.numDimensions(); }

	@Override
	public RealRandomAccess<FloatType> realRandomAccess()
	{ 
		return getRealRandomAccessible().realRandomAccess();
	}

	@Override
	public RealRandomAccess<FloatType> realRandomAccess( final RealInterval interval )
	{
		return realRandomAccess();
	}

	@Override
	public FloatType getType()
	{
		return new FloatType();
	}

	public RealRandomAccessible< FloatType > getRealRandomAccessible()
	{
		final NearestNeighborSearch< FloatType > search = new NearestNeighborSearchOnKDTree<>( new KDTree<>( qualityList ) );
		return Views.interpolate( search, new NearestNeighborSearchInterpolatorFactory< FloatType >() );
	}

	public RandomAccessibleInterval< FloatType > getRandomAccessibleInterval()
	{
		// InverseDistanceWeightingInterpolatorFactory
		final NearestNeighborSearch< FloatType > search = new NearestNeighborSearchOnKDTree<>( new KDTree<>( qualityList ) );
		final RealRandomAccessible< FloatType > realRandomAccessible = Views.interpolate( search, new NearestNeighborSearchInterpolatorFactory< FloatType >() );
		final RandomAccessible< FloatType > randomAccessible = Views.raster( realRandomAccessible );
		final RandomAccessibleInterval< FloatType > rai = Views.interval( randomAccessible, interval );

		return Views.interval( Views.extendZero( rai ), interval );
	}

	public RandomAccessibleInterval< FloatType > getRandomAccessibleInterval( final int numPoints, final double power )
	{
		final KNearestNeighborSearch< FloatType > search = new KNearestNeighborSearchOnKDTree<>( new KDTree<>( qualityList ), numPoints );
		final RealRandomAccessible< FloatType > realRandomAccessible = Views.interpolate( search, new InverseDistanceWeightingInterpolatorFactory< FloatType >( power ) );
		final RandomAccessible< FloatType > randomAccessible = Views.raster( realRandomAccessible );
		final RandomAccessibleInterval< FloatType > rai = Views.interval( randomAccessible, interval );

		return Views.interval( Views.extendZero( rai ), interval );
	}

	public PointSampleList< FloatType > getQualityList() { return qualityList; }

	public static double computeRelativeFRC(
			final RandomAccessible< FloatType > input,
			final Point location,
			final int length,
			final int relativeFRCDist )
	{
		final FRC frc = new FRC();

		final double[][] frcCurve;

		final FloatProcessor fp1 = getFloatProcessor( input, location.getIntPosition( 0 ), location.getIntPosition( 1 ), location.getIntPosition( 2 ) - 1, length );
		final FloatProcessor fp2 = getFloatProcessor( input, location.getIntPosition( 0 ), location.getIntPosition( 1 ), location.getIntPosition( 2 ) + 1, length );

		frcCurve = frc.calculateFrcCurve( fp1, fp2 );

		final FloatProcessor fpD0 = getFloatProcessor( input, location.getIntPosition( 0 ), location.getIntPosition( 1 ), location.getIntPosition( 2 ) - relativeFRCDist, length );
		final FloatProcessor fpD1 = getFloatProcessor( input, location.getIntPosition( 0 ), location.getIntPosition( 1 ), location.getIntPosition( 2 ) + relativeFRCDist, length );

		try
		{
			final double[][] frcCurveDist =  frc.getSmoothedCurve( frc.calculateFrcCurve( fpD0, fpD1 ) );

			//if ( location.getIntPosition(2 ) == 139 || location.getIntPosition(2 ) == 239 )
			//	new FRC().doPlot(frcCurve, frcCurveDist, ThresholdMethod.FIXED_1_OVER_7, 1, "frc-frcDist"+location.getLongPosition(2) ).show();

			for ( int i = 0; i < frcCurve.length; ++i )
			{
				if ( !Double.isFinite( frcCurveDist[ i ][ 1 ] ) || !Double.isFinite( frcCurve[ i ][ 1 ] ) )
					return 0;
 
				frcCurve[ i ][ 1 ] = /*Math.max( 0, */frcCurve[ i ][ 1 ] - frcCurveDist[ i ][ 1 ];// );
			}

			final double integral = FRCRealRandomAccessible.integral( frcCurve );

			return integral;
		}
		catch ( Exception e )
		{
			// is thrown if everything is zero
			return 0;
		}
	}

	public static double computeFRC(
			final RandomAccessible< FloatType > input,
			final Point location,
			final int length )

	{
		final FloatProcessor fp1 = getFloatProcessor( input, location.getIntPosition( 0 ), location.getIntPosition( 1 ), location.getIntPosition( 2 ) - 1, length );
		final FloatProcessor fp2 = getFloatProcessor( input, location.getIntPosition( 0 ), location.getIntPosition( 1 ), location.getIntPosition( 2 ) + 1, length );

		final double[][] frcCurve = new FRC().calculateFrcCurve( fp1, fp2 );

		final double integral = integral( frcCurve );

		return integral;
	}

	public static double integral( final double[][] frcCurve )
	{
		double integral = 0;

		for ( int i = 0; i < frcCurve.length; ++i )
			integral += frcCurve[ i ][ 1 ];

		return integral;
	}

	public static FloatProcessor getFloatProcessor( final RandomAccessible< FloatType > img, final int x, final int y, final int z, final int length )
	{
		final RandomAccessible< FloatType > s = Views.hyperSlice( img, 2, z );
		final FloatProcessor fp = new FloatProcessor( length, length );

		final int minX = x - length/2;
		final int minY = y - length/2;

		final Cursor< FloatType > c0 = Views.iterable( Views.interval( s, new long[]{ minX, minY }, new long[]{ x + length/2 - 1, y + length/2 - 1 } ) ).localizingCursor();

		while ( c0.hasNext() )
		{
			iterate( c0, fp, minX, minY );
		}

		return fp;
	}

	private static final void iterate( final Cursor< FloatType > c0, final FloatProcessor fp, final int minX, final int minY )
	{
		c0.fwd();
		fp.setf( c0.getIntPosition( 0 ) - minX, c0.getIntPosition( 1 ) - minY, c0.get().get() );
	}

	@SuppressWarnings("unchecked")
	public static < T extends RealType< T > > RandomAccessibleInterval< FloatType > getFloatRAI( final RandomAccessibleInterval< T > input )
	{
		if ( FloatType.class.isInstance( Views.iterable( input ).cursor().next() ) )
			return (RandomAccessibleInterval< FloatType >)input;
		else
			return Converters.convert( input, new RealFloatConverter< T >(),  new FloatType() );
	}
}
