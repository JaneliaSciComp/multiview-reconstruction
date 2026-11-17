/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2026 Multiview Reconstruction developers.
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
package net.preibisch.mvrecon.process.fusion.blk.tps;

import static net.imglib2.algorithm.blocks.transform.Transform.Interpolation.NLINEAR;
import static net.imglib2.type.PrimitiveType.DOUBLE;
import static net.imglib2.type.PrimitiveType.FLOAT;
import static net.imglib2.util.Util.safeInt;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import net.imglib2.Interval;
import net.imglib2.RealInterval;
import net.imglib2.algorithm.blocks.AbstractBlockSupplier;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.algorithm.blocks.convert.Convert;
import net.imglib2.algorithm.blocks.dfield.DisplacementField;
import net.imglib2.algorithm.blocks.dfield.DisplacementFields.TransformedDisplacementField;
import net.imglib2.algorithm.blocks.dfield.PositionFieldFunction;
import net.imglib2.algorithm.blocks.transform.Transform.Interpolation;
import net.imglib2.blocks.BlockInterval;
import net.imglib2.blocks.TempArray;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Cast;
import net.imglib2.util.Intervals;

/**
 * The position field of one view: for every target voxel, the position in the view's image to sample.
 * <p>
 * TPS fusion needs the position field twice per view and tile, for the image and for its weight (blending, masking or
 * distance). imglib2-algorithm's {@code DisplacementFieldTransform} (image) and {@code DisplacementFieldBlockSupplier}
 * (weights) each compute it internally, so it was computed twice. This class differs in two ways:
 * <ol>
 * <li><b>Shared:</b> the position field of a tile is computed once (per thread) and used by both {@link #image} and
 * {@link #weights}. The fusion asks for the image and the weights of a view for the same tile in turn.</li>
 * <li><b>Separable:</b> the transform from target to displacement field grid maps target X lines to grid X lines (in
 * TPS fusion it is a scale and translation, the field is sampled on an axis-aligned grid), so along a target X line the
 * grid's Y and Z coordinates are constant. The displacements are interpolated in Y and Z once per grid column of a line,
 * and only in X per voxel, instead of trilinearly per voxel.</li>
 * </ol>
 * Results equal imglib2-algorithm's up to floating point rounding. The parts marked "as in imglib2-algorithm" repeat
 * package-private code there, because imglib2-algorithm has no operators that accept a precomputed position field.
 */
public class TpsPositionField
{
	/**
	 * @return the position field of {@code transformedField}, or {@code null} if the transform to the field does not map
	 *         X lines to X lines
	 */
	public static < D extends NativeType< D > & RealType< D > > TpsPositionField of( final TransformedDisplacementField< D > transformedField )
	{
        // toField: maps global to grid space
		final AffineTransform3D toField = ( ( AffineTransform3D ) transformedField.transformFromField() ).inverse();

        // can only take fast interpolation path when a step in X does not change grid Y / Z
		if ( toField.get( 1, 0 ) != 0 || toField.get( 2, 0 ) != 0 )
			return null;

		final DisplacementField< D > field = transformedField.displacementField();
		final BlockSupplier< DoubleType > displacements =
				field.getType().getNativeTypeFactory().getPrimitiveType() == DOUBLE
						? Cast.unchecked( field.displacements() )
						: field.displacements().andThen( Convert.convert( new DoubleType() ) );
		return new TpsPositionField( toField, field.scale(), field.translation(), displacements );
	}

	/** target coordinates to displacement field grid coordinates (maps X lines to X lines) */
	private final AffineTransform3D toField;

	/** (grid coordinate + displacement) * scale + translation = source coordinate */
	private final double[] scale, translation;

	/** normalized displacements (in grid units), [3, x, y, z] */
	private final BlockSupplier< DoubleType > displacements;

	/** one per thread; a WeakHashMap (ThreadLocal values would outlive this field as Spark reuses threads) */
	private final Map< Thread, Positions > perThread = Collections.synchronizedMap( new WeakHashMap<>() );

	private TpsPositionField( final AffineTransform3D toField, final double[] scale, final double[] translation, final BlockSupplier< DoubleType > displacements )
	{
		this.toField = toField;
		this.scale = scale;
		this.translation = translation;
		this.displacements = displacements;
	}

	/**
	 * The view's image: {@code input} sampled at the positions with {@code interpolation}.
	 */
	public BlockSupplier< FloatType > image( final BlockSupplier< FloatType > input, final Interpolation interpolation )
	{
		return new ImageBlocks( this, input, interpolation );
	}

	/**
	 * {@code function} (e.g. {@link DistanceFunction3D}, created for {@link DoubleType}) applied to the positions.
	 */
	public < T extends NativeType< T > > BlockSupplier< T > weights( final PositionFieldFunction< DoubleType, T, double[], ? > function )
	{
		return new FunctionBlocks<>( this, function );
	}

	/**
	 * {@code function} applied to the positions; its {@link DistanceBlocks#range} bounds the distances of an interval
	 * without computing the positions.
	 */
	public DistanceBlocks distances( final DistanceFunction3D< DoubleType, double[] > function )
	{
		return new DistanceBlocks( this, function );
	}

	/**
	 * The positions of {@code interval}. Reused if the current thread asked for the same interval last time.
	 */
	private Positions positions( final Interval interval )
	{
		final Positions p = perThread.computeIfAbsent( Thread.currentThread(), t -> new Positions() );
		p.update( interval );
		p.computePositions();
		return p;
	}

	/**
	 * The displacements of {@code interval} with their {@link Positions#gridMin bounds}, positions not computed.
	 */
	private Positions bounds( final Interval interval )
	{
		final Positions p = perThread.computeIfAbsent( Thread.currentThread(), t -> new Positions() );
		p.update( interval );
		p.computeGridBounds();
		return p;
	}

	/**
	 * The positions of one target interval.
	 */
	private final class Positions
	{
		/** this thread's copy of the displacements (a BlockSupplier is not thread-safe) */
		private final BlockSupplier< DoubleType > threadDisplacements = displacements.independentCopy();

		private final TempArray< double[] > tempDisplacements = TempArray.forPrimitiveType( DOUBLE );

		private final TempArray< double[] > tempColumns = TempArray.forPrimitiveType( DOUBLE );

		private final TempArray< double[] > tempPositions = TempArray.forPrimitiveType( DOUBLE );

		/** the target interval */
		private final long[] targetMin = new long[ 3 ];

		private final int[] targetSize = { -1, -1, -1 };

		/** the block of displacements needed for the target interval, [3, x, y, z] */
		private final long[] fieldMin = new long[ 4 ];

		private final int[] fieldSize = new int[ 4 ];

		/** the displacements block */
		private double[] field;

		private boolean hasPositions, hasGridBounds;

		/** bounds of the positions of the block's grid points (without offset); every position is a blend of them */
		final double[] gridMin = new double[ 3 ], gridMax = new double[ 3 ];

		/** number of positions */
		int length;

		/** flat array of positions relative to {@link #offset}  */
		double[] positions;

		/** add to positions to get source coordinates */
		final double[] offset = new double[ 3 ];

		/** bounds of {@link #positions} (without offset) */
		private final double[] positionsMin = new double[ 3 ], positionsMax = new double[ 3 ];

		void update( final Interval interval )
		{
			final BlockInterval target = BlockInterval.asBlockInterval( interval );

            // no need to do anything if the thread already holds the positions for this corner
			if ( Arrays.equals( targetMin, target.min() ) && Arrays.equals( targetSize, target.size() ) )
				return;

			System.arraycopy( target.min(), 0, targetMin, 0, 3 );
			System.arraycopy( target.size(), 0, targetSize, 0, 3 );

			// as in imglib2-algorithm AbstractDispFieldAffineProcessor.setTargetInterval
			final RealInterval bounds = toField.estimateBounds( interval );
			fieldSize[ 0 ] = 3;
			for ( int d = 0; d < 3; ++d )
			{
				fieldMin[ d + 1 ] = ( long ) Math.floor( bounds.realMin( d ) - 0.5 );
				fieldSize[ d + 1 ] = ( int ) ( ( long ) Math.floor( bounds.realMax( d ) + 0.5 ) - fieldMin[ d + 1 ] ) + 2;
				offset[ d ] = scale[ d ] * fieldMin[ d + 1 ] + translation[ d ];
			}
			field = tempDisplacements.get( safeInt( Intervals.numElements( fieldSize ) ) );
			threadDisplacements.copy( BlockInterval.wrap( fieldMin, fieldSize ), field );
			hasPositions = hasGridBounds = false;
		}

		void computeGridBounds()
		{
			if ( hasGridBounds )
				return;
			hasGridBounds = true;
			Arrays.fill( gridMin, Double.POSITIVE_INFINITY );
			Arrays.fill( gridMax, Double.NEGATIVE_INFINITY );
			final int[] g = new int[ 3 ];
			int i = 0;
			for ( g[ 2 ] = 0; g[ 2 ] < fieldSize[ 3 ]; ++g[ 2 ] )
				for ( g[ 1 ] = 0; g[ 1 ] < fieldSize[ 2 ]; ++g[ 1 ] )
					for ( g[ 0 ] = 0; g[ 0 ] < fieldSize[ 1 ]; ++g[ 0 ] )
						for ( int d = 0; d < 3; ++d )
						{
							final double p = ( field[ i++ ] + g[ d ] ) * scale[ d ];
							gridMin[ d ] = Math.min( gridMin[ d ], p );
							gridMax[ d ] = Math.max( gridMax[ d ], p );
						}
		}

		void computePositions()
		{
			if ( hasPositions )
				return;
			hasPositions = true;
			length = safeInt( Intervals.numElements( targetSize ) );
			positions = tempPositions.get( 3 * length );
			computePositions( field );
		}

		/**
		 * Position = (grid coordinate + displacement) * scale, with the displacement interpolated linearly, as in
		 * imglib2-algorithm {@code DispFieldAffine3D}, but separably.
		 */
		private void computePositions( final double[] field )
		{
			final float step0 = ( float ) toField.get( 0, 0 ); // grid X step per target X step
			final int lineLength = 3 * fieldSize[ 1 ]; // flat values of one grid line
			final int planeLength = lineLength * fieldSize[ 2 ];
			final double scale0 = scale[ 0 ], scale1 = scale[ 1 ], scale2 = scale[ 2 ];
			final double[] columns = tempColumns.get( lineLength ); // displacements of the current line, interpolated in Y and Z
			final double[] target = new double[ 3 ], grid = new double[ 3 ];

			double min0 = Double.POSITIVE_INFINITY, min1 = min0, min2 = min0;
			double max0 = Double.NEGATIVE_INFINITY, max1 = max0, max2 = max0;

			target[ 0 ] = targetMin[ 0 ];
			int i = 0;
			for ( int z = 0; z < targetSize[ 2 ]; ++z )
			{
				target[ 2 ] = targetMin[ 2 ] + z;
				for ( int y = 0; y < targetSize[ 1 ]; ++y )
				{
					target[ 1 ] = targetMin[ 1 ] + y;
					toField.apply( target, grid );
					// grid coordinates relative to the displacements block, in float as in imglib2-algorithm
					float g0 = ( float ) ( grid[ 0 ] - fieldMin[ 1 ] );
					final float g1 = ( float ) ( grid[ 1 ] - fieldMin[ 2 ] );
					final float g2 = ( float ) ( grid[ 2 ] - fieldMin[ 3 ] );
					final int i1 = ( int ) g1, i2 = ( int ) g2;
					final float r1 = g1 - i1, r2 = g2 - i2;

					// interpolate in Y and Z, once per grid column
					for ( int c = 0, o = i2 * planeLength + i1 * lineLength; c < lineLength; ++c, ++o )
					{
						final double a00 = field[ o ];
						final double a01 = field[ o + lineLength ];
						final double a10 = field[ o + planeLength ];
						final double a11 = field[ o + planeLength + lineLength ];
						columns[ c ] = a00 + r1 * ( -a00 + a01 ) + r2 * ( ( -a00 + a10 ) + r1 * ( a00 - a01 - a10 + a11 ) );
					}

					// interpolate in X, per voxel
					for ( int x = 0; x < targetSize[ 0 ]; ++x )
					{
						final int i0 = ( int ) g0;
						final float r0 = g0 - i0;
						final int o = 3 * i0;
						final double p0 = ( columns[ o ] + r0 * ( columns[ o + 3 ] - columns[ o ] ) + g0 ) * scale0;
						final double p1 = ( columns[ o + 1 ] + r0 * ( columns[ o + 4 ] - columns[ o + 1 ] ) + g1 ) * scale1;
						final double p2 = ( columns[ o + 2 ] + r0 * ( columns[ o + 5 ] - columns[ o + 2 ] ) + g2 ) * scale2;
						positions[ i++ ] = p0;
						positions[ i++ ] = p1;
						positions[ i++ ] = p2;
						min0 = Math.min( min0, p0 ); max0 = Math.max( max0, p0 );
						min1 = Math.min( min1, p1 ); max1 = Math.max( max1, p1 );
						min2 = Math.min( min2, p2 ); max2 = Math.max( max2, p2 );
						g0 += step0;
					}
				}
			}
			positionsMin[ 0 ] = min0; positionsMin[ 1 ] = min1; positionsMin[ 2 ] = min2;
			positionsMax[ 0 ] = max0; positionsMax[ 1 ] = max1; positionsMax[ 2 ] = max2;
		}

		/**
		 * The source interval that {@code interpolation} reads for these positions, as in imglib2-algorithm
		 * {@code DispFieldAffine3D.sourceBounds}.
		 */
		void sourceBounds( final Interpolation interpolation, final long[] boundsMin, final int[] boundsSize )
		{
			for ( int d = 0; d < 3; ++d )
			{
				final double min = positionsMin[ d ] + offset[ d ];
				final double max = positionsMax[ d ] + offset[ d ];
				if ( interpolation == NLINEAR )
				{
					boundsMin[ d ] = ( long ) Math.floor( min - 0.5f );
					boundsSize[ d ] = ( int ) ( ( long ) Math.floor( max + 0.5f ) - boundsMin[ d ] ) + 2;
				}
				else
				{
					boundsMin[ d ] = Math.round( min - 0.5f );
					boundsSize[ d ] = ( int ) ( Math.round( max + 0.5f ) - boundsMin[ d ] ) + 1;
				}
			}
		}
	}

	/**
	 * Samples an image at the positions.
	 */
	private static class ImageBlocks extends AbstractBlockSupplier< FloatType >
	{
		private final TpsPositionField field;

		private final BlockSupplier< FloatType > input;

		private final Interpolation interpolation;

		private final TempArray< float[] > tempInput = TempArray.forPrimitiveType( FLOAT );

		private final long[] boundsMin = new long[ 3 ];

		private final int[] boundsSize = new int[ 3 ];

		ImageBlocks( final TpsPositionField field, final BlockSupplier< FloatType > input, final Interpolation interpolation )
		{
			this.field = field;
			this.input = input;
			this.interpolation = interpolation;
		}

		@Override
		public void copy( final Interval interval, final Object dest )
		{
			final Positions p = field.positions( interval );
			p.sourceBounds( interpolation, boundsMin, boundsSize );
			final float[] src = tempInput.get( safeInt( Intervals.numElements( boundsSize ) ) );
			input.copy( BlockInterval.wrap( boundsMin, boundsSize ), src );

			// offset from positions to coordinates in src
			final double d0 = p.offset[ 0 ] - boundsMin[ 0 ];
			final double d1 = p.offset[ 1 ] - boundsMin[ 1 ];
			final double d2 = p.offset[ 2 ] - boundsMin[ 2 ];
			final int ss0 = boundsSize[ 0 ];
			final int ss1 = boundsSize[ 1 ] * ss0;
			if ( interpolation == NLINEAR )
				linear( p.positions, d0, d1, d2, src, Cast.unchecked( dest ), p.length, ss0, ss1 );
			else
				nearestNeighbor( p.positions, d0, d1, d2, src, Cast.unchecked( dest ), p.length, ss0, ss1 );
		}

		/**
		 * As in imglib2-algorithm {@code Lookup3D.NLinear_doubleField_float}.
		 */
		private static void linear( final double[] positions, final double d0, final double d1, final double d2,
				final float[] src, final float[] dest, final int length, final int ss0, final int ss1 )
		{
			for ( int x = 0; x < length; ++x )
			{
				final double sf0 = positions[ 3 * x ] + d0;
				final double sf1 = positions[ 3 * x + 1 ] + d1;
				final double sf2 = positions[ 3 * x + 2 ] + d2;
				final int s0 = ( int ) sf0;
				final int s1 = ( int ) sf1;
				final int s2 = ( int ) sf2;
				final float r0 = ( float ) ( sf0 - s0 );
				final float r1 = ( float ) ( sf1 - s1 );
				final float r2 = ( float ) ( sf2 - s2 );
				final int o = s2 * ss1 + s1 * ss0 + s0;
				final float a000 = src[ o ];
				final float a001 = src[ o + 1 ];
				final float a010 = src[ o + ss0 ];
				final float a011 = src[ o + ss0 + 1 ];
				final float a100 = src[ o + ss1 ];
				final float a101 = src[ o + ss1 + 1 ];
				final float a110 = src[ o + ss1 + ss0 ];
				final float a111 = src[ o + ss1 + ss0 + 1 ];
				dest[ x ] = a000 +
						r0 * ( -a000 + a001 ) +
						r1 * ( ( -a000 + a010 ) +
								r0 * ( a000 - a001 - a010 + a011 ) ) +
						r2 * ( ( -a000 + a100 ) +
								r0 * ( a000 - a001 - a100 + a101 ) +
								r1 * ( ( a000 - a010 - a100 + a110 ) +
										r0 * ( -a000 + a001 + a010 - a011 + a100 - a101 - a110 + a111 ) ) );
			}
		}

		/**
		 * As in imglib2-algorithm {@code Lookup3D.NearestNeighbor_doubleField_float}.
		 */
		private static void nearestNeighbor( final double[] positions, final double d0, final double d1, final double d2,
				final float[] src, final float[] dest, final int length, final int ss0, final int ss1 )
		{
			// +0.5, then truncate: the nearest voxel
			final double e0 = d0 + 0.5, e1 = d1 + 0.5, e2 = d2 + 0.5;
			for ( int x = 0; x < length; ++x )
			{
				final int s0 = ( int ) ( positions[ 3 * x ] + e0 );
				final int s1 = ( int ) ( positions[ 3 * x + 1 ] + e1 );
				final int s2 = ( int ) ( positions[ 3 * x + 2 ] + e2 );
				dest[ x ] = src[ s2 * ss1 + s1 * ss0 + s0 ];
			}
		}

		@Override
		public BlockSupplier< FloatType > independentCopy()
		{
			return new ImageBlocks( field, input.independentCopy(), interpolation );
		}

		@Override
		public int numDimensions()
		{
			return 3;
		}

		@Override
		public FloatType getType()
		{
			return input.getType();
		}
	}

	/**
	 * The distances of a view, with bounds per interval.
	 */
	public static class DistanceBlocks extends FunctionBlocks< FloatType >
	{
		/** more than the rounding of the positions */
		private static final double margin = 1e-3;

		private final DistanceFunction3D< DoubleType, double[] > distance;

		private final double[] min = new double[ 3 ], max = new double[ 3 ];

		DistanceBlocks( final TpsPositionField field, final DistanceFunction3D< DoubleType, double[] > distance )
		{
			super( field, distance );
			this.distance = distance;
		}

		/**
		 * Bounds of the distances of {@code interval}, from the grid points of its displacements only.
		 *
		 * @param minMax
		 *            receives { min, max }, see {@link DistanceFunction3D#range}
		 */
		public void range( final Interval interval, final double[] minMax )
		{
			final Positions p = field.bounds( interval );
			for ( int d = 0; d < 3; ++d )
			{
				min[ d ] = p.gridMin[ d ] + p.offset[ d ] - margin;
				max[ d ] = p.gridMax[ d ] + p.offset[ d ] + margin;
			}
			distance.range( min, max, minMax );
		}

		@Override
		public BlockSupplier< FloatType > independentCopy()
		{
			return new DistanceBlocks( field, distance );
		}
	}

	/**
	 * Applies a {@link PositionFieldFunction} to the positions.
	 */
	private static class FunctionBlocks< T extends NativeType< T > > extends AbstractBlockSupplier< T >
	{
		final TpsPositionField field;

		private final PositionFieldFunction< DoubleType, T, double[], ? > function;

		private final long[] boundsMin = new long[ 3 ];

		private final int[] boundsSize = new int[ 3 ];

		private final double[] offset = new double[ 3 ];

		FunctionBlocks( final TpsPositionField field, final PositionFieldFunction< DoubleType, T, double[], ? > function )
		{
			this.field = field;
			this.function = function;
		}

		@Override
		@SuppressWarnings( { "unchecked", "rawtypes" } )
		public void copy( final Interval interval, final Object dest )
		{
			final Positions p = field.positions( interval );
			// as in imglib2-algorithm DisplacementFieldBlockSupplier, where the offset is (offset - boundsMin) + boundsMin;
			// the same rounding keeps the results bit-identical
			p.sourceBounds( NLINEAR, boundsMin, boundsSize );
			for ( int d = 0; d < 3; ++d )
				offset[ d ] = ( p.offset[ d ] - boundsMin[ d ] ) + boundsMin[ d ];
			( ( PositionFieldFunction ) function ).compute( dest, p.length, p.positions, offset );
		}

		@Override
		public BlockSupplier< T > independentCopy()
		{
			return new FunctionBlocks<>( field, function.independentCopy() );
		}

		@Override
		public int numDimensions()
		{
			return 3;
		}

		@Override
		public T getType()
		{
			return function.getType();
		}
	}
}
