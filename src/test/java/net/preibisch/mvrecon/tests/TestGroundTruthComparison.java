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
package net.preibisch.mvrecon.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;

import mpicbg.models.AffineModel3D;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Pair;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.downsampling.Downsample;
import net.preibisch.mvrecon.process.downsampling.lazy.LazyDownsample2x;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import net.preibisch.simulation.SimulateMultiViewDataset;
import net.imglib2.type.numeric.real.FloatType;

/**
 * Compares DoG detection with virtual vs non-virtual downsampling against ground truth.
 */
public class TestGroundTruthComparison
{
	// Same parameters as SimulateUtil.setUp()
	static final int[] angles = new int[]{ 0, 45, 90 };
	static final int axis = 0;
	static final int numPoints = 200;
	static final double[] sigma = new double[]{ 1, 1, 3 };
	static final FinalInterval range = new FinalInterval( 128, 128, 50 );
	static final long seed = 535; // Same seed as SimulateBeads

	public static void main( String[] args )
	{
		// 1. Generate ground truth points (same as SimulateBeads)
		final Random rnd = new Random( seed );
		final ArrayList< double[] > groundTruthWorld = randomPoints( numPoints, range, rnd );

		// Transform to each view's coordinate system
		final ArrayList< ArrayList< double[] > > groundTruthPerView = transformPoints( groundTruthWorld, angles, axis, range );

		// 2. Create simulated dataset
		final SpimData2 spimData = SimulateUtil.setUp();

		// 3. Run DoG with non-virtual downsampling (like multiview-reconstruction)
		System.out.println( "=== Non-virtual downsampling (Downsample.simple2x) ===" );
		final List< List< InterestPoint > > pointsNonVirtual = runDoG( spimData, false );

		// 4. Run DoG with virtual downsampling (like Spark)
		final SpimData2 spimData2 = SimulateUtil.setUp();
		System.out.println( "\n=== Virtual downsampling (LazyDownsample2x) ===" );
		final List< List< InterestPoint > > pointsVirtual = runDoG( spimData2, true );

		// 5. Compare both to ground truth
		System.out.println( "\n=== Comparison to Ground Truth ===" );
		for ( int v = 0; v < angles.length; v++ )
		{
			final ArrayList< double[] > gt = groundTruthPerView.get( v );
			final List< InterestPoint > nonVirt = pointsNonVirtual.get( v );
			final List< InterestPoint > virt = pointsVirtual.get( v );

			System.out.println( "\nView " + v + " (angle " + angles[v] + "):" );
			System.out.println( "  Ground truth points: " + gt.size() );
			System.out.println( "  Non-virtual detected: " + nonVirt.size() );
			System.out.println( "  Virtual detected: " + virt.size() );

			// Find matching points and compute errors
			final double[] errorsNonVirt = computeErrors( gt, nonVirt );
			final double[] errorsVirt = computeErrors( gt, virt );

			System.out.println( "  Non-virtual - matched: " + (int)errorsNonVirt[0] +
					", mean error: " + String.format("%.4f", errorsNonVirt[1]) +
					", std: " + String.format("%.4f", errorsNonVirt[2]) +
					", bias: " + String.format("%.4f", errorsNonVirt[3]) );
			System.out.println( "  Virtual     - matched: " + (int)errorsVirt[0] +
					", mean error: " + String.format("%.4f", errorsVirt[1]) +
					", std: " + String.format("%.4f", errorsVirt[2]) +
					", bias: " + String.format("%.4f", errorsVirt[3]) );
		}
	}

	/**
	 * Computes errors between ground truth and detected points.
	 * @return [numMatched, meanError, stdError, meanSignedError (bias)]
	 */
	static double[] computeErrors( ArrayList< double[] > groundTruth, List< InterestPoint > detected )
	{
		final double maxDist = 3.0; // Max distance to consider a match
		int matched = 0;
		double sumError = 0;
		double sumErrorSq = 0;
		double sumSignedErrorX = 0;
		double sumSignedErrorY = 0;
		double sumSignedErrorZ = 0;

		for ( final double[] gt : groundTruth )
		{
			// Find closest detected point
			double minDist = Double.MAX_VALUE;
			InterestPoint closest = null;
			for ( final InterestPoint ip : detected )
			{
				final double[] det = ip.getL();
				final double dist = Math.sqrt(
						(gt[0] - det[0]) * (gt[0] - det[0]) +
						(gt[1] - det[1]) * (gt[1] - det[1]) +
						(gt[2] - det[2]) * (gt[2] - det[2]) );
				if ( dist < minDist )
				{
					minDist = dist;
					closest = ip;
				}
			}

			if ( minDist < maxDist && closest != null )
			{
				matched++;
				sumError += minDist;
				sumErrorSq += minDist * minDist;
				final double[] det = closest.getL();
				sumSignedErrorX += (det[0] - gt[0]);
				sumSignedErrorY += (det[1] - gt[1]);
				sumSignedErrorZ += (det[2] - gt[2]);
			}
		}

		if ( matched == 0 )
			return new double[] { 0, 0, 0, 0 };

		final double meanError = sumError / matched;
		final double variance = (sumErrorSq / matched) - (meanError * meanError);
		final double stdError = Math.sqrt( Math.max( 0, variance ) );
		final double biasX = sumSignedErrorX / matched;
		final double biasY = sumSignedErrorY / matched;
		final double biasZ = sumSignedErrorZ / matched;
		final double biasMagnitude = Math.sqrt( biasX*biasX + biasY*biasY + biasZ*biasZ );

		return new double[] { matched, meanError, stdError, biasMagnitude };
	}

	static List< List< InterestPoint > > runDoG( SpimData2 spimData, boolean virtualDownsampling )
	{
		final List< List< InterestPoint > > allPoints = new ArrayList<>();

		for ( int v = 0; v < angles.length; v++ )
		{
			final ViewId viewId = new ViewId( 0, v );

			final ExecutorService service = Threads.createFixedExecutorService( 1 );

			// Open and downsample
			RandomAccessibleInterval input = spimData.getSequenceDescription().getImgLoader()
					.getSetupImgLoader( v ).getImage( 0 );

			final AffineTransform3D transform = new AffineTransform3D();
			transform.set( 2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 1, 0 ); // downsample XY by 2

			// Downsample XY by 2
			if ( virtualDownsampling )
			{
				input = LazyDownsample2x.init( Views.extendBorder( input ), input, new FloatType(), DoGImgLib2.blockSize, 0 );
				input = LazyDownsample2x.init( Views.extendBorder( input ), input, new FloatType(), DoGImgLib2.blockSize, 1 );
			}
			else
			{
				input = Downsample.simple2x( input, new boolean[]{ true, false, false } );
				input = Downsample.simple2x( input, new boolean[]{ false, true, false } );
			}

			@SuppressWarnings("unchecked")
			List< InterestPoint > ips = DoGImgLib2.computeDoG(
					(RandomAccessible) Views.extendMirrorSingle( input ),
					null,
					new FinalInterval( input ),
					TestInterestPointDetection.STANDARD_SIGMA,
					TestInterestPointDetection.STANDARD_THRESHOLD,
					1, // quadratic localization
					false, // findMin
					true, // findMax
					TestInterestPointDetection.GLOBAL_MIN_INTENSITY,
					TestInterestPointDetection.GLOBAL_MAX_INTENSITY,
					DoGImgLib2.blockSize,
					service,
					null, null, false, 0.0 );

			service.shutdown();

			// Correct for downsampling
			DownsampleTools.correctForDownsampling( ips, transform );

			System.out.println( "View " + v + ": " + ips.size() + " points" );
			allPoints.add( ips );
		}

		return allPoints;
	}

	// Copied from SimulateBeads to generate same ground truth
	static ArrayList< double[] > randomPoints( final int numPoints, final Interval range, final Random rnd )
	{
		final ArrayList< double[] > points = new ArrayList<>();
		for ( int i = 0; i < numPoints; ++i )
		{
			final double[] p = new double[ range.numDimensions() ];
			for ( int d = 0; d < range.numDimensions(); ++d )
				p[ d ] = rnd.nextDouble() * ( range.max( d ) - range.min( d ) ) + range.min( d );
			points.add( p );
		}
		return points;
	}

	static ArrayList< ArrayList< double[] > > transformPoints( final ArrayList< double[] > points, final int[] angles, final int axis, final Interval range )
	{
		final ArrayList< ArrayList< double[] > > transformedPoints = new ArrayList<>();
		for ( final int angle : angles )
		{
			final AffineModel3D t = SimulateMultiViewDataset.axisRotation( range, axis, angle );
			final ArrayList< double[] > transformed = new ArrayList<>();
			for ( final double[] p : points )
				transformed.add( t.apply( p ) );
			transformedPoints.add( transformed );
		}
		return transformedPoints;
	}
}
