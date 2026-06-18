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
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.ExecutorService;

import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.Point;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.transformed.FusedRandomAccessibleInterval;
import net.preibisch.mvrecon.process.fusion.transformed.FusedRandomAccessibleInterval.Fusion;
import net.preibisch.mvrecon.process.fusion.transformed.TransformView;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.fusion.transformed.TransformWeight;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class FRCTools
{
	public static FRCRealRandomAccessible< FloatType > computeFRC(
			final ViewId viewId,
			final BasicImgLoader imgLoader,
			final int zStepSize,
			final int fftSize,
			final boolean relative )
	{
		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Loading view " +  Group.pvid( viewId ) + " ..." );

		final RandomAccessibleInterval input = imgLoader.getSetupImgLoader( viewId.getViewSetupId() ).getImage( viewId.getTimePointId() );
		//DisplayImage.getImagePlusInstance( input, true, "Fused, Virtual", 0, 255 ).show();

		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Computing FRC for " +  Group.pvid( viewId ) + " ..." );

		final FRCRealRandomAccessible< FloatType > frc = distributeGridFRC( input, 0.1, zStepSize, fftSize, relative, FRCRealRandomAccessible.relativeFRCDist, null );
		//DisplayImage.getImagePlusInstance( frc.getRandomAccessibleInterval(), true, "Fused, Virtual", Double.NaN, Double.NaN ).show();

		return frc;
	}

	public static RandomAccessibleInterval< FloatType > fuseRAIs(
			final Collection< Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > > data,
			final double downsampling,
			final Interval boundingBox,
			final int interpolation )
	{
		final Interval bb;

		if ( !Double.isNaN( downsampling ) )
			bb = TransformVirtual.scaleBoundingBox( boundingBox, 1.0 / downsampling );
		else
			bb = boundingBox;

		final long[] dim = new long[ bb.numDimensions() ];
		bb.dimensions( dim );

		final ArrayList< RandomAccessibleInterval< FloatType > > images = new ArrayList<>();
		final ArrayList< RandomAccessibleInterval< FloatType > > weights = new ArrayList<>();

		for ( final Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > d : data )
		{
			AffineTransform3D model = d.getB();

			if ( !Double.isNaN( downsampling ) )
			{
				model = model.copy();
				TransformVirtual.scaleTransform( model, 1.0 / downsampling );
			}

			images.add( TransformView.transformView( d.getA(), model, bb, 0, interpolation ) );

			final float[] blending = Util.getArrayFromValue( FusionTools.defaultBlendingRange, 3 );
			final float[] border = Util.getArrayFromValue( FusionTools.defaultBlendingBorder, 3 );

			// adjust both for z-scaling (anisotropy), downsampling, and registrations itself
			
			FusionTools.adjustBlending( getDimensions( d.getA() ), "", blending, border, model );

			weights.add( TransformWeight.transformBlending( d.getA(), border, blending, model, bb ) );
		}

		return new FusedRandomAccessibleInterval( new FinalInterval( dim ), Fusion.AVG, images, weights );
	}


	public static FinalDimensions getDimensions( final Interval interval )
	{
		final long[] dim = new long[ interval.numDimensions() ];

		for ( int d = 0; d < dim.length; ++d )
			dim[ d ] = interval.dimension( d );

		return new FinalDimensions( dim );
	}

	public static FRCRealRandomAccessible< FloatType > fixedGridFRC( final RandomAccessibleInterval< FloatType > input, final int distanceXY, final int distanceZ, final int fhtSqSize, final boolean relative, final int zMinDist, final ExecutorService service )
	{
		final ArrayList< Point > locations = new ArrayList<>();

		final ArrayList< Pair< Long, Long > > xyPositions = fixedGridXY( input, distanceXY );

		for ( int z = zMinDist; z < input.dimension( 2 ) - zMinDist; z += distanceZ )
			for ( final Pair< Long, Long > xy : xyPositions )
				locations.add( new Point( xy.getA(), xy.getB(), z ) );

		return new FRCRealRandomAccessible<>( input, locations, fhtSqSize, relative, service );
	}

	public static FRCRealRandomAccessible< FloatType > distributeGridFRC( final RandomAccessibleInterval< FloatType > input, final double overlapTolerance, final int distanceZ, final int fhtSqSize, final boolean relative, final int zMinDist, final ExecutorService service )
	{
		final ArrayList< Point > locations = new ArrayList<>();

		final ArrayList< Pair< Long, Long > > xyPositions = distributeSquaresXY( input, fhtSqSize, overlapTolerance );

		
		for ( int z = zMinDist; z < input.dimension( 2 ) - zMinDist; z += distanceZ )
			for ( final Pair< Long, Long > xy : xyPositions )
				locations.add( new Point( xy.getA(), xy.getB(), z ) );

		return new FRCRealRandomAccessible<>( input, locations, fhtSqSize, relative, service );
	}

	public static ArrayList< Pair< Long, Long > > fixedGridXY( final Interval interval, final long distance )
	{
		final long lx = interval.dimension( 0 );
		final long ly = interval.dimension( 1 );

		System.out.println( "lx: " + lx );
		System.out.println( "ly: " + ly );

		long sqX = Math.max( 1, lx / distance );
		if ( lx % distance > 0 ) ++sqX;

		long sqY = Math.max( 1, ly / distance );
		if ( ly % distance > 0 ) ++sqY;

		System.out.println( "SquaresX: " + sqX );
		System.out.println( "SquaresY: " + sqY );

		final ArrayList< Long > xPos = getLocations( sqX, lx );
		final ArrayList< Long > yPos = getLocations( sqY, ly );

		final ArrayList< Pair< Long, Long > > list = new ArrayList<>();

		for ( final long x : xPos )
			System.out.println( x );

		System.out.println();

		for ( final long y : yPos )
			System.out.println( y );

		for ( int y = 0; y < yPos.size(); ++y )
			for ( int x = 0; x < xPos.size(); ++x )
				list.add( new ValuePair< Long, Long >( xPos.get( x ) + interval.min( 0 ), yPos.get( y ) + interval.min( 1 ) ) );

		return list;
	}

	public static ArrayList< Pair< Long, Long > > distributeSquaresXY( final Interval interval, final long length, final double overlapTolerance )
	{
		final long lx = interval.dimension( 0 );
		final long ly = interval.dimension( 1 );

		System.out.println( "lx: " + lx );
		System.out.println( "ly: " + ly );

		long sqX = lx / length;
		final long modX = lx % length;

		long sqY = ly / length;
		final long modY = ly % length;

		System.out.println( "sqX: " + sqX + " modX: " + modX + " relX: " + (double)modX / (double)length );
		System.out.println( "sqY: " + sqY + " modY: " + modX + " relY: " + (double)modY / (double)length );

		if ( (double)modX / (double)length >= overlapTolerance || sqX == 0 )
			++sqX;

		if ( (double)modY / (double)length >= overlapTolerance || sqY == 0 )
			++sqY;

		System.out.println( "SquaresX: " + sqX );
		System.out.println( "SquaresY: " + sqY );

		final ArrayList< Long > xPos = getLocations( sqX, lx );
		final ArrayList< Long > yPos = getLocations( sqY, ly );

		for ( final long x : xPos )
			System.out.println( x );

		System.out.println();

		for ( final long y : yPos )
			System.out.println( y );

		final ArrayList< Pair< Long, Long > > list = new ArrayList<>();

		for ( int y = 0; y < yPos.size(); ++y )
			for ( int x = 0; x < xPos.size(); ++x )
				list.add( new ValuePair< Long, Long >( xPos.get( x ) + interval.min( 0 ), yPos.get( y ) + interval.min( 1 ) ) );

		return list;
	}

	protected static final ArrayList< Long > getLocations( final long numSq, final long imgSize )
	{
		final ArrayList< Long > pos = new ArrayList<>();

		final double dist = (double)imgSize / (double)numSq;
		final double inc = (double)imgSize / ( numSq * 2.0 );

		for ( int i = 0; i < numSq; ++i )
			pos.add( Math.round( inc + i * dist ) );

		return pos;
	}

}
