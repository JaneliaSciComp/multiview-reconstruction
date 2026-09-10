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
package net.preibisch.mvrecon.process.fusion.blk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import net.imglib2.FinalDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RealInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.ThinplateSplineTransform;
import net.imglib2.realtransform.interval.IntervalSamplingMethod;
import net.imglib2.realtransform.inverse.WrappedIterativeInvertibleRealTransform;
import net.imglib2.util.Intervals;
import net.preibisch.mvrecon.process.fusion.blk.BlkThinPlateSplineFusion.GuessingRealTransform;

/**
 * Regression test for {@link BlkThinPlateSplineFusion#inverseTransformedBoundingBox}.
 * <p>
 * Simulates an underlying view whose render position is tens of thousands of pixels
 * away from its local pixel coordinates (a tile in a large stitched volume), with
 * landmarks in the same {@code [d][i]} layout and the same source/target convention as
 * {@code Landmarks}: source = image pixel coordinates, target = render coordinates.
 * <p>
 * imglib2's iterative inverse seeds each inversion with the query point itself. For a
 * far-away tile with only a few landmarks (e.g. the split-tile centers) the descent
 * then never reaches the true preimage. Seeding with the approximate affine fixes that.
 */
public class InverseTransformedBoundingBoxTest
{
	private static final long[] DIMS = { 4096, 4096, 512 };

	private static final FinalInterval IMAGE = new FinalInterval( DIMS );

	/** image -> render: anisotropic z, slight rotation about z, far translation */
	private static AffineTransform3D groundTruthAffine()
	{
		final AffineTransform3D a = new AffineTransform3D();
		a.set( 2.0, 2, 2 ); // z anisotropy
		a.rotate( 2, Math.toRadians( 3 ) );
		a.translate( 40000, 30000, 2000 );
		return a;
	}

	/** smooth, small non-affine displacement (render space) so the TPS is not exactly affine */
	private static void perturb( final double[] p, final double[] out )
	{
		// read first: p and out may be the same array
		final double x = p[ 0 ], y = p[ 1 ], z = p[ 2 ];
		out[ 0 ] = x + 4 * Math.sin( y / 1500.0 );
		out[ 1 ] = y + 3 * Math.cos( x / 2000.0 );
		out[ 2 ] = z + 2 * Math.sin( ( x + y ) / 3000.0 );
	}

	private static double[][][] pack( final List< double[] > src, final List< double[] > tgt )
	{
		final double[][] source = new double[ 3 ][ src.size() ];
		final double[][] target = new double[ 3 ][ src.size() ];
		for ( int i = 0; i < src.size(); ++i )
			for ( int d = 0; d < 3; ++d )
			{
				source[ d ][ i ] = src.get( i )[ d ];
				target[ d ][ i ] = tgt.get( i )[ d ];
			}
		return new double[][][] { source, target };
	}

	/**
	 * Dense landmarks: the 8 image corners plus an interior grid, smoothly perturbed.
	 * Because the TPS interpolates landmarks exactly, the true render preimage of every
	 * image corner is known exactly here (see {@link #denseExpectedBounds()}).
	 */
	private static double[][][] denseLandmarksWithCorners()
	{
		final AffineTransform3D gt = groundTruthAffine();
		final List< double[] > src = new ArrayList<>();
		final List< double[] > tgt = new ArrayList<>();
		final double[] fractions = { 0.0, 0.33, 0.66, 1.0 };
		for ( final double fx : fractions )
			for ( final double fy : fractions )
				for ( final double fz : new double[] { 0.0, 0.5, 1.0 } )
				{
					final double[] s = { fx * ( DIMS[ 0 ] - 1 ), fy * ( DIMS[ 1 ] - 1 ), fz * ( DIMS[ 2 ] - 1 ) };
					final double[] t = new double[ 3 ];
					gt.apply( s, t );
					perturb( t, t );
					src.add( s );
					tgt.add( t );
				}
		return pack( src, tgt );
	}

	private static RealInterval denseExpectedBounds()
	{
		final AffineTransform3D gt = groundTruthAffine();
		final double[] min = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY };
		final double[] max = { Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
		for ( int c = 0; c < 8; ++c )
		{
			final double[] t = new double[ 3 ];
			gt.apply( corner( c ), t );
			perturb( t, t );
			for ( int d = 0; d < 3; ++d )
			{
				min[ d ] = Math.min( min[ d ], t[ d ] );
				max[ d ] = Math.max( max[ d ], t[ d ] );
			}
		}
		return Intervals.createMinMaxReal( min[ 0 ], min[ 1 ], min[ 2 ], max[ 0 ], max[ 1 ], max[ 2 ] );
	}

	/**
	 * Sparse landmarks: only the 8 centers of a 2x2x2 split (at 25% / 75% of each
	 * dimension), randomly perturbed by up to {@code amp} px. No landmark is anywhere
	 * near a corner, so all 8 corners extrapolate. This is the "little split point
	 * coverage" situation in which the un-seeded inverse fails.
	 */
	private static double[][][] sparseSplitCenterLandmarks( final double amp, final long seed )
	{
		final AffineTransform3D gt = groundTruthAffine();
		final Random rnd = new Random( seed );
		final List< double[] > src = new ArrayList<>();
		final List< double[] > tgt = new ArrayList<>();
		for ( final double fx : new double[] { 0.25, 0.75 } )
			for ( final double fy : new double[] { 0.25, 0.75 } )
				for ( final double fz : new double[] { 0.25, 0.75 } )
				{
					final double[] s = { fx * ( DIMS[ 0 ] - 1 ), fy * ( DIMS[ 1 ] - 1 ), fz * ( DIMS[ 2 ] - 1 ) };
					final double[] t = new double[ 3 ];
					gt.apply( s, t );
					for ( int d = 0; d < 3; ++d )
						t[ d ] += amp * ( 2 * rnd.nextDouble() - 1 );
					src.add( s );
					tgt.add( t );
				}
		return pack( src, tgt );
	}

	private static double[] corner( final int c )
	{
		final double[] p = new double[ 3 ];
		for ( int d = 0; d < 3; ++d )
			p[ d ] = ( ( c >> d ) & 1 ) == 0 ? 0 : DIMS[ d ] - 1;
		return p;
	}

	/** same construction as BlkThinPlateSplineFusion.init(): TPS render -> image */
	private static ThinplateSplineTransform tps( final double[][][] lm )
	{
		return new ThinplateSplineTransform( lm[ 1 ], lm[ 0 ] );
	}

	/** same construction as BlkThinPlateSplineFusion.init(): approximate affine image -> render */
	private static AffineTransform3D approxAffine( final double[][][] lm )
	{
		return BlkThinPlateSplineFusion.fitAffineTransform( lm[ 0 ], lm[ 1 ] );
	}

	private static double maxBoundsError( final RealInterval actual, final RealInterval expected )
	{
		double err = 0;
		for ( int d = 0; d < 3; ++d )
		{
			err = Math.max( err, Math.abs( actual.realMin( d ) - expected.realMin( d ) ) );
			err = Math.max( err, Math.abs( actual.realMax( d ) - expected.realMax( d ) ) );
		}
		return err;
	}

	private static double maxAbsDiff( final double[] a, final double[] b )
	{
		double m = 0;
		for ( int d = 0; d < 3; ++d )
			m = Math.max( m, Math.abs( a[ d ] - b[ d ] ) );
		return m;
	}

	@Test
	public void seededBoundingBoxMatchesExactGroundTruthForDenseLandmarks()
	{
		final double[][][] lm = denseLandmarksWithCorners();
		final Interval bb = BlkThinPlateSplineFusion.inverseTransformedBoundingBox(
				tps( lm ), approxAffine( lm ), new FinalDimensions( DIMS ) );

		// smallestContainingInterval rounds outward by < 1px, descent tolerance is 0.5px in image space
		final double err = maxBoundsError( bb, denseExpectedBounds() );
		assertTrue( err < 2.0, "seeded bounding box deviates from ground truth by " + err + " px: " + Intervals.toString( bb ) );
	}

	@Test
	public void seededBoundingBoxIsCorrectForSparseLandmarks()
	{
		for ( final double amp : new double[] { 5, 50, 200 } )
		{
			final double[][][] lm = sparseSplitCenterLandmarks( amp, 1 );
			final ThinplateSplineTransform tps = tps( lm );
			final AffineTransform3D affine = approxAffine( lm );

			// every corner: converged, no fallback, and the result really is a preimage of the corner
			final GuessingRealTransform inv = new GuessingRealTransform(
					new WrappedIterativeInvertibleRealTransform<>( tps ), affine, DIMS[ 0 ] );
			for ( int c = 0; c < 8; ++c )
			{
				final double[] p = corner( c );
				final double[] x = new double[ 3 ];
				final double[] back = new double[ 3 ];
				inv.apply( p, x );
				tps.apply( x, back );
				assertTrue( maxAbsDiff( back, p ) < GuessingRealTransform.DEFAULT_TOLERANCE,
						"amp=" + amp + ", corner " + c + ": tps(inverse(corner)) is " + maxAbsDiff( back, p ) + " px off" );
			}
			assertEquals( 8, inv.numApplied() );
			assertEquals( 0, inv.numFallbacks(), "amp=" + amp + ": no corner should need the affine fallback" );

			// the box is close to the affine prediction (the TPS is nearly affine; the un-seeded
			// inverse is off by ~100,000 px here, see unseededInverseFailsForSparseLandmarks)
			final Interval bb = BlkThinPlateSplineFusion.inverseTransformedBoundingBox( tps, affine, new FinalDimensions( DIMS ) );
			final double err = maxBoundsError( bb, affine.estimateBounds( IMAGE ) );
			assertTrue( err < 500, "amp=" + amp + ": seeded bounding box deviates from affine prediction by " + err + " px: " + Intervals.toString( bb ) );
		}
	}

	/**
	 * Documents why the seed is needed. The plain imglib2 inverse seeds each corner with
	 * itself (an image coordinate ~50,000 px from the answer) and, for sparse landmarks,
	 * never converges. If this test ever fails, imglib2 has started seeding properly and
	 * {@link GuessingRealTransform} may no longer be needed.
	 */
	@Test
	public void unseededInverseFailsForSparseLandmarks()
	{
		final double[][][] lm = sparseSplitCenterLandmarks( 5, 1 );
		final ThinplateSplineTransform tps = tps( lm );
		final RealTransform unseeded = new WrappedIterativeInvertibleRealTransform<>( tps ).inverse();
		final RealInterval bb = unseeded.boundingInterval( IMAGE, IntervalSamplingMethod.CORNERS );
		final RealInterval predicted = approxAffine( lm ).estimateBounds( IMAGE );
		final double err = maxBoundsError( bb, predicted );
		System.out.println( "[InverseTransformedBoundingBoxTest] un-seeded imglib2 inverse: " + bb
				+ "\n  affine prediction: " + predicted + "\n  max deviation = " + err + " px" );
		assertTrue( err > 1000, "un-seeded inverse unexpectedly converged (deviation " + err + " px); is the seeding workaround still needed?" );
	}

	@Test
	public void applyDoesNotModifySourceAndSupportsAliasing()
	{
		final double[][][] lm = sparseSplitCenterLandmarks( 50, 1 );
		final ThinplateSplineTransform tps = tps( lm );
		final GuessingRealTransform inv = new GuessingRealTransform(
				new WrappedIterativeInvertibleRealTransform<>( tps ), approxAffine( lm ), DIMS[ 0 ] );

		final double[] corner = corner( 5 );
		final double[] source = corner.clone();
		final double[] result = new double[ 3 ];
		inv.apply( source, result );
		for ( int d = 0; d < 3; ++d )
			assertEquals( corner[ d ], source[ d ], 0.0, "apply must not modify source" );

		// Corners.bounds calls apply(pt, pt)
		final double[] aliased = corner.clone();
		inv.apply( aliased, aliased );
		for ( int d = 0; d < 3; ++d )
			assertEquals( result[ d ], aliased[ d ], 1e-9, "aliased apply(pt, pt) must give the same result" );

		// copy() must be independent (own optimizer state) and give the same answer
		final double[] fromCopy = new double[ 3 ];
		inv.copy().apply( corner, fromCopy );
		for ( int d = 0; d < 3; ++d )
			assertEquals( result[ d ], fromCopy[ d ], 1e-9, "copy() must give the same result" );
	}
}
