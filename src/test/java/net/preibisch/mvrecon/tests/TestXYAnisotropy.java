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
package net.preibisch.mvrecon.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;

import ij.ImagePlus;
import ij.process.FloatProcessor;
import mpicbg.models.AffineModel3D;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Cursor;
import net.imglib2.FinalDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.BlockAlgoUtils;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.img.Img;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.DifferenceOfGaussianGUI;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionRecord;
import net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionToSparkCli;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoG;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGParameters;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.DoGScaleSpace;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.ScaleSpace;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.ScaleSpaceDetectionParameters;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.ScaleSpaceParameters;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;

/**
 * Stacks whose pixel size differs between x and y (voxel sizes like (1, 2, 4) or (2, 1, 4), either lateral axis can be
 * the coarser one): the fixture ({@link SimulateUtil#subsampled}), that nothing changes for equal pixel sizes in x and
 * y, that the detection does not depend on which lateral axis is the coarser one, and the chain of detection,
 * registration and fusion on such a dataset.
 */
public class TestXYAnisotropy
{
	/** a view with the given voxel size (no images) */
	static ViewDescription view( final double vx, final double vy, final double vz )
	{
		final ViewSetup setup = new ViewSetup( 0, "", new FinalDimensions( 10, 10, 10 ), new FinalVoxelDimensions( "um", vx, vy, vz ), new Tile( 0 ), new Channel( 0 ), new Angle( 0 ), new Illumination( 0 ) );
		final SequenceDescription seq = new SequenceDescription( new TimePoints( Arrays.asList( new TimePoint( 0 ) ) ), Arrays.asList( setup ), null, null );

		return seq.getViewDescription( 0, 0 );
	}

	/** the mipmap transform of an image downsampled by the given factors (pixel centers, as the pyramids have it) */
	static AffineTransform3D mipmap( final double sx, final double sy, final double sz )
	{
		final AffineTransform3D t = new AffineTransform3D();
		t.set( sx, 0, 0, 0.5 * ( sx - 1 ), 0, sy, 0, 0.5 * ( sy - 1 ), 0, 0, sz, 0.5 * ( sz - 1 ) );
		return t;
	}

	/**
	 * The fixture: a view subsampled along x or y has the voxel size, the image and the calibration of a stack that was
	 * acquired with a coarser pixel along that axis, and its calibrated coordinates are the pixels of the full view
	 */
	@Test
	public void testFixture()
	{
		final SpimData2 full = SimulateUtil.setUpRoundBeads();

		for ( final int axis : new int[] { 0, 1 } )
		{
			final SpimData2 data = SimulateUtil.subsampled( full, axis, 2 );

			for ( final ViewDescription vd : data.getSequenceDescription().getViewDescriptions().values() )
			{
				final VoxelDimensions v = vd.getViewSetup().getVoxelSize();
				assertArrayEquals( axis == 0 ? new double[] { 2, 1, 1 } : new double[] { 1, 2, 1 }, new double[] { v.dimension( 0 ), v.dimension( 1 ), v.dimension( 2 ) }, 0.0 );

				@SuppressWarnings( "unchecked" )
				final RandomAccessibleInterval< UnsignedShortType > fullImg = (RandomAccessibleInterval< UnsignedShortType >)full.getSequenceDescription().getImgLoader().getSetupImgLoader( vd.getViewSetupId() ).getImage( vd.getTimePointId() );
				@SuppressWarnings( "unchecked" )
				final RandomAccessibleInterval< UnsignedShortType > img = (RandomAccessibleInterval< UnsignedShortType >)data.getSequenceDescription().getImgLoader().getSetupImgLoader( vd.getViewSetupId() ).getImage( vd.getTimePointId() );

				// the image and the size of the setup
				for ( int d = 0; d < 3; ++d )
				{
					assertEquals( d == axis ? ( fullImg.dimension( d ) + 1 ) / 2 : fullImg.dimension( d ), img.dimension( d ), "size of dimension " + d );
					assertEquals( img.dimension( d ), vd.getViewSetup().getSize().dimension( d ), "size of the setup in dimension " + d );
				}

				final RandomAccess< UnsignedShortType > raFull = fullImg.randomAccess();
				final Cursor< UnsignedShortType > c = Views.iterable( img ).localizingCursor();
				long nonZero = 0;

				while ( c.hasNext() )
				{
					final int value = c.next().get();
					raFull.setPosition( c.getLongPosition( 0 ) * ( axis == 0 ? 2 : 1 ) + fullImg.min( 0 ), 0 );
					raFull.setPosition( c.getLongPosition( 1 ) * ( axis == 1 ? 2 : 1 ) + fullImg.min( 1 ), 1 );
					raFull.setPosition( c.getLongPosition( 2 ) + fullImg.min( 2 ), 2 );

					assertEquals( raFull.get().get(), value );

					if ( value > 0 )
						++nonZero;
				}

				assertTrue( nonZero > 1000, "the view has content: " + nonZero + " pixels above 0" );

				// the calibration of a newly defined dataset: relative to the smallest voxel dimension of all three
				final ViewRegistration vr = data.getViewRegistrations().getViewRegistration( vd );
				vr.updateModel();

				assertEquals( 1, vr.getTransformList().size() );
				assertEquals( "calibration", vr.getTransformList().get( 0 ).getName() );
				assertArrayEquals(
						axis == 0 ? new double[] { 2, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0 } : new double[] { 1, 0, 0, 0, 0, 2, 0, 0, 0, 0, 1, 0 },
						vr.getModel().getRowPackedCopy(), 0.0 );
			}
		}
	}

	/**
	 * Equal pixel sizes in x and y, equal downsampling in x and y: the anisotropy of an opened image and the sigma of a
	 * detection at full resolution are exactly the values of the formulas that were in place before the detection
	 * supported unequal pixel sizes (the same doubles, so no detection on such data changes)
	 */
	@Test
	public void testNothingChangesForEqualXY()
	{
		int cases = 0;

		for ( final double vxy : new double[] { 1.0, 0.45, 1.1, 0.1, 0.3249, 6.5 } )
			for ( final double vz : new double[] { 1.0, 2.0, 3.512, 0.2 } )
				for ( final double sxy : new double[] { 1, 2, 3, 4, 8 } )
					for ( final double sz : new double[] { 1, 2, 3 } )
						for ( final double anisotropyZ : new double[] { Double.NaN, 3.0, 0.8 * vz / vxy } )
						{
							final ViewDescription vd = view( vxy, vxy, vz );
							final AffineTransform3D t = mipmap( sxy, sxy, sz );

							// { 1, (vy * sy) / (vx * sx), (a * sz) / sx } with a = z voxel / x voxel unless given
							final double a = Double.isNaN( anisotropyZ ) ? vz / vxy : anisotropyZ;
							final double[] expected = new double[] { 1.0, ( vxy * sxy ) / ( vxy * sxy ), ( a * sz ) / sxy };

							assertArrayEquals( expected, DownsampleTools.anisotropy( vd, t, anisotropyZ ), 0.0, "voxel " + vxy + ", " + vxy + ", " + vz + " downsampled " + sxy + ", " + sxy + ", " + sz + " anisotropyZ " + anisotropyZ );

							// the sigma scaled with the geometric mean of the scaling in x and y
							final InterestPoint ip = new InterestPoint( 0, new double[] { 3, 4, 5 }, 1f, 1.8f, 1f );
							DownsampleTools.correctForDownsampling( new ArrayList<>( Arrays.asList( ip ) ), t );

							assertEquals( (float)( 1.8f * Math.sqrt( Math.abs( sxy * sxy ) ) ), ip.getSigma(), 0f );
							assertArrayEquals( new double[] { 3 * sxy + 0.5 * ( sxy - 1 ), 4 * sxy + 0.5 * ( sxy - 1 ), 5 * sz + 0.5 * ( sz - 1 ) }, ip.getL(), 0.0 );

							++cases;
						}

		IOFunctions.println( "equal pixel sizes and downsampling in x and y: " + cases + " combinations of voxel size, downsampling and z anisotropy give the legacy values" );
	}

	/**
	 * The detection core does not depend on which lateral axis is the coarser one: physically round blobs on a grid with
	 * twice the y voxel size and the same blobs on the grid with x and y swapped give the same detections with x and y
	 * swapped, for the Difference-of-Gaussian and the scale space (the anisotropy is the voxel size per dimension, any
	 * dimension may be the one the sigma refers to)
	 */
	@Test
	public void testCoreDoesNotDependOnTheCoarserAxis()
	{
		final double a = 3.0; // z voxel
		final double s = 4.0; // the blobs, in pixels of the fine lateral axis

		// the image carries 0.5 px of blur per axis by definition
		final double sCoarse = Math.sqrt( ( s * s - 0.25 ) / 4 + 0.25 );
		final double sZ = Math.sqrt( ( s * s - 0.25 ) / ( a * a ) + 0.25 );

		// y coarse: voxel (1, 2, a); x coarse: voxel (2, 1, a), the same image with x and y swapped
		final double[][] centersY = new double[][] { { 30.3, 20.2, 21.6 }, { 70.7, 44.4, 24.3 }, { 52.4, 31.1, 12.8 } };
		final double[][] centersX = new double[ centersY.length ][];
		final double[][] stdsY = new double[ centersY.length ][], stdsX = new double[ centersY.length ][];

		for ( int i = 0; i < centersY.length; ++i )
		{
			centersX[ i ] = new double[] { centersY[ i ][ 1 ], centersY[ i ][ 0 ], centersY[ i ][ 2 ] };
			stdsY[ i ] = new double[] { s, sCoarse, sZ };
			stdsX[ i ] = new double[] { sCoarse, s, sZ };
		}

		final Img< FloatType > imgY = TestDoGScaleSpace.blobs( new long[] { 104, 64, 40 }, centersY, stdsY );
		final Img< FloatType > imgX = TestDoGScaleSpace.blobs( new long[] { 64, 104, 40 }, centersX, stdsX );

		final double[] anisotropyY = new double[] { 1, 2, a }, anisotropyX = new double[] { 2, 1, a };

		final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );

		// Difference-of-Gaussian
		final double sigma = 3.0, threshold = 0.01;

		final ArrayList< InterestPoint > dogY = DoGImgLib2.computeDoG( Views.extendMirrorSingle( imgY ), null, imgY, sigma, anisotropyY, threshold, 1, false, true, 0, 1, service );
		final ArrayList< InterestPoint > dogX = DoGImgLib2.computeDoG( Views.extendMirrorSingle( imgX ), null, imgX, sigma, anisotropyX, threshold, 1, false, true, 0, 1, service );

		assertSwapped( "Difference-of-Gaussian", centersY, dogY, dogX, 0.02, 0.01 );

		for ( final InterestPoint ip : dogX )
			assertEquals( (float)sigma, ip.getSigma(), 0f );

		// scale space
		final ScaleSpaceParameters pY = new ScaleSpaceParameters( 1.6, 0.01 );
		pY.minIntensity = 0;
		pY.maxIntensity = 1;
		pY.anisotropy = anisotropyY;

		final ScaleSpaceParameters pX = new ScaleSpaceParameters( pY );
		pX.anisotropy = anisotropyX;

		final ArrayList< InterestPoint > ssY = DoGScaleSpace.computeDoGScaleSpace( Views.extendMirrorSingle( imgY ), imgY, imgY, null, pY, service );
		final ArrayList< InterestPoint > ssX = DoGScaleSpace.computeDoGScaleSpace( Views.extendMirrorSingle( imgX ), imgX, imgX, null, pX, service );

		service.shutdown();

		assertSwapped( "scale space", centersY, ssY, ssX, 0.05, 0.02 );
	}

	/** the points of the y-coarse grid and those of the x-coarse grid are the same with x and y swapped; one per blob */
	static void assertSwapped( final String what, final double[][] centersY, final List< InterestPoint > pointsY, final List< InterestPoint > pointsX, final double positionDelta, final double relativeDelta )
	{
		for ( final InterestPoint ip : pointsY )
			IOFunctions.println( what + ", y coarse: " + Util.printCoordinates( ip.getL() ) + " response=" + ip.getResponse() + " sigma=" + ip.getSigma() );
		for ( final InterestPoint ip : pointsX )
			IOFunctions.println( what + ", x coarse: " + Util.printCoordinates( ip.getL() ) + " response=" + ip.getResponse() + " sigma=" + ip.getSigma() );

		assertEquals( centersY.length, pointsY.size(), what + ": one detection per blob on the y-coarse grid" );
		assertEquals( centersY.length, pointsX.size(), what + ": one detection per blob on the x-coarse grid" );

		final List< InterestPoint > sortedY = new ArrayList<>( pointsY ), sortedX = new ArrayList<>( pointsX );
		sortedY.sort( Comparator.comparingDouble( ip -> ip.getL()[ 0 ] ) );
		sortedX.sort( Comparator.comparingDouble( ip -> ip.getL()[ 1 ] ) );

		final double[][] sortedCenters = centersY.clone();
		Arrays.sort( sortedCenters, Comparator.comparingDouble( c -> c[ 0 ] ) );

		for ( int i = 0; i < sortedY.size(); ++i )
		{
			final InterestPoint py = sortedY.get( i ), px = sortedX.get( i );

			// at the blob (within half a pixel of the coarse axes)
			assertEquals( sortedCenters[ i ][ 0 ], py.getL()[ 0 ], 0.5, what + ": x of blob " + i );
			assertEquals( sortedCenters[ i ][ 1 ], py.getL()[ 1 ], 0.5, what + ": y of blob " + i );
			assertEquals( sortedCenters[ i ][ 2 ], py.getL()[ 2 ], 0.5, what + ": z of blob " + i );

			assertEquals( py.getL()[ 0 ], px.getL()[ 1 ], positionDelta, what + ": fine axis of blob " + i );
			assertEquals( py.getL()[ 1 ], px.getL()[ 0 ], positionDelta, what + ": coarse axis of blob " + i );
			assertEquals( py.getL()[ 2 ], px.getL()[ 2 ], positionDelta, what + ": z of blob " + i );
			assertEquals( py.getResponse(), px.getResponse(), relativeDelta * Math.abs( py.getResponse() ), what + ": response of blob " + i );
			assertEquals( py.getSigma(), px.getSigma(), relativeDelta * py.getSigma(), what + ": sigma of blob " + i );
		}
	}

	/** the detections of the views by the Difference-of-Gaussian driver at full resolution, in pixels of the views */
	static HashMap< ViewId, List< InterestPoint > > detect( final SpimData2 data, final double sigma, final double threshold, final double anisotropyZ, final double maxIntensity )
	{
		return detect( data, new long[] { 1, 1, 1 }, sigma, threshold, anisotropyZ, maxIntensity );
	}

	/** the detections of the views by the Difference-of-Gaussian driver at a downsampling in x, y, z, in full-resolution pixels of the views */
	static HashMap< ViewId, List< InterestPoint > > detect( final SpimData2 data, final long[] downsampling, final double sigma, final double threshold, final double anisotropyZ, final double maxIntensity )
	{
		final List< ViewDescription > views = new ArrayList<>( data.getSequenceDescription().getViewDescriptions().values() );
		Collections.sort( views );

		final DoGParameters dog = new DoGParameters( views, data.getSequenceDescription().getImgLoader(), sigma, threshold );
		dog.localization = 1;
		dog.downsampling = downsampling;
		dog.anisotropyZ = anisotropyZ;
		dog.minIntensity = 0;
		dog.maxIntensity = maxIntensity;

		return DoG.findInterestPoints( dog );
	}

	/** the highest intensity of the views */
	static double maxIntensity( final SpimData2 data )
	{
		double max = 0;

		for ( final ViewDescription vd : data.getSequenceDescription().getViewDescriptions().values() )
			for ( final Object t : Views.iterable( (RandomAccessibleInterval< ? >)data.getSequenceDescription().getImgLoader().getSetupImgLoader( vd.getViewSetupId() ).getImage( vd.getTimePointId() ) ) )
				max = Math.max( max, ( (UnsignedShortType)t ).get() );

		return max;
	}

	/** the fraction of the points of a that have a point of b within the distance, both in the same coordinates */
	static double matched( final List< double[] > a, final List< double[] > b, final double distance )
	{
		int n = 0;

		for ( final double[] p : a )
			for ( final double[] q : b )
				if ( ( p[ 0 ] - q[ 0 ] ) * ( p[ 0 ] - q[ 0 ] ) + ( p[ 1 ] - q[ 1 ] ) * ( p[ 1 ] - q[ 1 ] ) + ( p[ 2 ] - q[ 2 ] ) * ( p[ 2 ] - q[ 2 ] ) <= distance * distance )
				{
					++n;
					break;
				}

		return a.isEmpty() ? 0 : (double)n / a.size();
	}

	/** the positions of the points under the registration of their view */
	static List< double[] > global( final SpimData2 data, final ViewId view, final List< InterestPoint > points )
	{
		final ViewRegistration vr = data.getViewRegistrations().getViewRegistration( view );
		vr.updateModel();

		final List< double[] > global = new ArrayList<>();

		for ( final InterestPoint ip : points )
		{
			final double[] g = new double[ 3 ];
			vr.getModel().apply( ip.getL(), g );
			global.add( g );
		}

		return global;
	}

	/** the fused image of the views inside the interval */
	static RandomAccessibleInterval< UnsignedShortType > fuse( final SpimData2 data, final Interval boundingBox )
	{
		final Pair< BlockSupplier< UnsignedShortType >, Interval > blks = TestFusion.testFusion( data, boundingBox, FusionType.AVG_BLEND, 1.0, 1.0 );

		return Views.translate( BlockAlgoUtils.cellImg( blks.getA(), blks.getB().dimensionsAsLongArray(), new int[] { 32, 32, 32 } ), blks.getB().minAsLongArray() );
	}

	/** the normalized cross correlation of two images over an interval */
	static double correlation( final RandomAccessibleInterval< UnsignedShortType > a, final RandomAccessibleInterval< UnsignedShortType > b, final Interval interval )
	{
		final Cursor< UnsignedShortType > ca = Views.flatIterable( Views.interval( a, interval ) ).cursor();
		final Cursor< UnsignedShortType > cb = Views.flatIterable( Views.interval( b, interval ) ).cursor();

		double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
		long n = 0;

		while ( ca.hasNext() )
		{
			final double va = ca.next().get(), vb = cb.next().get();
			sa += va; sb += vb; saa += va * va; sbb += vb * vb; sab += va * vb;
			++n;
		}

		final double cov = sab / n - ( sa / n ) * ( sb / n );

		return cov / Math.sqrt( ( saa / n - ( sa / n ) * ( sa / n ) ) * ( sbb / n - ( sb / n ) * ( sb / n ) ) );
	}

	/**
	 * Detection, registration and fusion of a dataset whose y pixels are twice as large as its x pixels, against the
	 * same beads at full resolution: the detections are at the same places once calibrated, the registration finds the
	 * same transformations and the fused images agree
	 */
	@Test
	public void testDetectRegisterFuseCoarseY()
	{
		detectRegisterFuse( 1 );
	}

	/**
	 * The same with x pixels twice as large as the y pixels: the sigma refers to the finer lateral axis, so the same
	 * sigma finds the same beads whichever axis is the coarser one
	 */
	@Test
	public void testDetectRegisterFuseCoarseX()
	{
		detectRegisterFuse( 0 );
	}

	static void detectRegisterFuse( final int coarseAxis )
	{
		final String coarse = "with " + ( coarseAxis == 0 ? "x" : "y" ) + " pixels twice as large";

		final SpimData2 full = SimulateUtil.setUpRoundBeads();
		final SpimData2 subsampled = SimulateUtil.subsampled( full, coarseAxis, 2 );

		final double sigma = 1.8, threshold = 0.008;
		final double maxIntensity = maxIntensity( full );

		// z voxel / finer lateral voxel is 1 in both datasets; with it the Gaussians follow the calibration, also laterally
		final HashMap< ViewId, List< InterestPoint > > pointsFull = detect( full, sigma, threshold, 1.0, maxIntensity );
		final HashMap< ViewId, List< InterestPoint > > pointsCoarse = detect( subsampled, sigma, threshold, 1.0, maxIntensity );

		for ( final ViewId view : pointsFull.keySet() )
		{
			final List< double[] > gFull = global( full, view, pointsFull.get( view ) );
			final List< double[] > gCoarse = global( subsampled, view, pointsCoarse.get( view ) );

			final double foundAgain = matched( gFull, gCoarse, 1.0 ), notInvented = matched( gCoarse, gFull, 1.0 );

			IOFunctions.println( "view " + view.getViewSetupId() + ": " + gFull.size() + " detections at full resolution, " + gCoarse.size() + " " + coarse + "; " +
					Math.round( 100 * foundAgain ) + "% of the full-resolution ones found again within 1 px, " + Math.round( 100 * notInvented ) + "% of the coarse ones are at a full-resolution one" );

			assertTrue( gFull.size() > 50, "detections at full resolution: " + gFull.size() );
			assertTrue( foundAgain > 0.95, coarse + ", found again: " + foundAgain );
			assertTrue( notInvented > 0.95, coarse + ", at a full-resolution detection: " + notInvented );

			// the stored sigma: pixels of the finer lateral axis at full resolution in both datasets
			for ( final InterestPoint ip : pointsCoarse.get( view ) )
				assertEquals( (float)sigma, ip.getSigma(), 0f );
		}

		// registration
		InterestPointTools.addInterestPoints( full, "beads", pointsFull, "test" );
		InterestPointTools.addInterestPoints( subsampled, "beads", pointsCoarse, "test" );

		final HashMap< ViewId, AffineModel3D > modelsFull = TestRegistration.testRegistration( full, "beads", false );
		final HashMap< ViewId, AffineModel3D > modelsCoarse = TestRegistration.testRegistration( subsampled, "beads", false );

		for ( final ViewId view : modelsFull.keySet() )
		{
			final double[] mFull = TransformationTools.getAffineTransform( modelsFull.get( view ) ).getRowPackedCopy();
			final double[] mCoarse = TransformationTools.getAffineTransform( modelsCoarse.get( view ) ).getRowPackedCopy();

			IOFunctions.println( "view " + view.getViewSetupId() + " full resolution:   " + Util.printCoordinates( mFull ) );
			IOFunctions.println( "view " + view.getViewSetupId() + " " + coarse + ": " + Util.printCoordinates( mCoarse ) );

			// the linear part within 0.005, the translation within a quarter of a pixel
			for ( int i = 0; i < 12; ++i )
				assertEquals( mFull[ i ], mCoarse[ i ], i % 4 == 3 ? 0.25 : 0.005, coarse + ", entry " + i + " of the model of view " + view.getViewSetupId() );
		}

		// fusion: the same volume
		final Interval bbFull = TestBoundingBox.testBoundingBox( full, false );
		final Interval bbCoarse = TestBoundingBox.testBoundingBox( subsampled, false );

		IOFunctions.println( "bounding box full resolution: " + Util.printInterval( bbFull ) + ", " + coarse + ": " + Util.printInterval( bbCoarse ) );

		final Interval common = Intervals.intersect( bbFull, bbCoarse );
		final double correlation = correlation( fuse( full, new FinalInterval( common ) ), fuse( subsampled, new FinalInterval( common ) ), common );

		IOFunctions.println( "correlation of the fused images over " + Util.printInterval( common ) + ": " + correlation );

		assertTrue( correlation > 0.98, coarse + ", correlation of the fused images: " + correlation );
	}

	/**
	 * The convention: the sigma of a detection refers to the finer lateral axis of the opened image (x when both are
	 * equal), the anisotropy is the voxel size of the opened image relative to that axis, the stored sigma is in
	 * full-resolution pixels of the finer lateral axis of the view. Either lateral axis can be the coarser one, and x
	 * and y can be downsampled differently; a voxel size and a downsampling with x and y exchanged give the same
	 * values with x and y exchanged.
	 */
	@Test
	public void testConvention()
	{
		final ViewDescription coarseY = view( 1, 2, 4 ), coarseX = view( 2, 1, 4 );
		final double nan = Double.NaN;

		// full resolution: relative to the finer lateral axis, z voxel / finer lateral voxel = 4 from the calibration
		assertArrayEquals( new double[] { 1, 2, 4 }, DownsampleTools.anisotropy( coarseY, mipmap( 1, 1, 1 ), nan ), 0.0 );
		assertArrayEquals( new double[] { 2, 1, 4 }, DownsampleTools.anisotropy( coarseX, mipmap( 1, 1, 1 ), nan ), 0.0 );

		// the user's z anisotropy replaces the ratio of the calibration
		assertArrayEquals( new double[] { 1, 2, 3 }, DownsampleTools.anisotropy( coarseY, mipmap( 1, 1, 1 ), 3.0 ), 0.0 );
		assertArrayEquals( new double[] { 2, 1, 3 }, DownsampleTools.anisotropy( coarseX, mipmap( 1, 1, 1 ), 3.0 ), 0.0 );

		// x and y downsampled by 2: the z voxel is half as large relative to the reference
		assertArrayEquals( new double[] { 1, 2, 2 }, DownsampleTools.anisotropy( coarseY, mipmap( 2, 2, 1 ), nan ), 0.0 );
		assertArrayEquals( new double[] { 2, 1, 2 }, DownsampleTools.anisotropy( coarseX, mipmap( 2, 2, 1 ), nan ), 0.0 );

		// only the finer lateral axis downsampled by 2: square pixels of twice the size
		assertArrayEquals( new double[] { 1, 1, 2 }, DownsampleTools.anisotropy( coarseY, mipmap( 2, 1, 1 ), nan ), 0.0 );
		assertArrayEquals( new double[] { 1, 1, 2 }, DownsampleTools.anisotropy( coarseX, mipmap( 1, 2, 1 ), nan ), 0.0 );

		// only the coarser lateral axis downsampled by 2
		assertArrayEquals( new double[] { 1, 4, 4 }, DownsampleTools.anisotropy( coarseY, mipmap( 1, 2, 1 ), nan ), 0.0 );
		assertArrayEquals( new double[] { 4, 1, 4 }, DownsampleTools.anisotropy( coarseX, mipmap( 2, 1, 1 ), nan ), 0.0 );

		// square pixels, x downsampled by 2: y is the reference; the z voxel relative to y as at full resolution
		assertArrayEquals( new double[] { 2, 1, 3 }, DownsampleTools.anisotropy( view( 1, 1, 3 ), mipmap( 2, 1, 1 ), nan ), 0.0 );
		assertArrayEquals( new double[] { 1, 2, 3 }, DownsampleTools.anisotropy( view( 1, 1, 3 ), mipmap( 1, 2, 1 ), nan ), 0.0 );

		// the reference axis and the factor of the stored sigma (full-resolution pixels of the finer lateral axis per pixel of the reference axis)
		final double[] vY = new double[] { 1, 2, 4 }, vX = new double[] { 2, 1, 4 };

		assertEquals( 0, DownsampleTools.referenceAxis( vY, new double[] { 1, 1, 1 } ) );
		assertEquals( 1, DownsampleTools.referenceAxis( vX, new double[] { 1, 1, 1 } ) );
		assertEquals( 0, DownsampleTools.referenceAxis( vX, new double[] { 1, 2, 1 } ) ); // equal: x
		assertEquals( 1, DownsampleTools.referenceAxis( new double[] { 1, 1, 1 }, new double[] { 2, 1, 1 } ) );

		for ( final double[][] vs : new double[][][] { // voxel size, downsampling, { expected factor }
				{ vY, { 1, 1, 1 }, { 1 } }, { vX, { 1, 1, 1 }, { 1 } },
				{ vY, { 2, 2, 1 }, { 2 } }, { vX, { 2, 2, 1 }, { 2 } },
				{ vY, { 2, 1, 1 }, { 2 } }, { vX, { 1, 2, 1 }, { 2 } }, // square pixels of twice the size of the finer axis
				{ vY, { 1, 2, 1 }, { 1 } }, { vX, { 2, 1, 1 }, { 1 } }, // the finer axis untouched
				{ vY, { 4, 1, 1 }, { 2 } }, { vX, { 1, 4, 1 }, { 2 } }, // the reference changes to the other axis: its pixel is twice the finer one
				{ { 1, 1, 1 }, { 2, 1, 1 }, { 1 } }, { { 1, 1, 1 }, { 1, 2, 1 }, { 1 } }, { { 1, 1, 1 }, { 4, 4, 2 }, { 4 } } } )
			assertEquals( vs[ 2 ][ 0 ], DownsampleTools.sigmaScale( vs[ 0 ], vs[ 1 ] ), 0.0, "voxel " + Util.printCoordinates( vs[ 0 ] ) + " downsampled " + Util.printCoordinates( vs[ 1 ] ) );

		// the detections mapped to full resolution: positions per axis, the sigma with that factor
		final InterestPoint ip = new InterestPoint( 0, new double[] { 3, 4, 5 }, 1f, 1.5f, 1f );
		DownsampleTools.correctForDownsampling( new ArrayList<>( Arrays.asList( ip ) ), mipmap( 1, 2, 1 ), coarseX.getViewSetup().getVoxelSize() );
		assertArrayEquals( new double[] { 3, 8.5, 5 }, ip.getL(), 0.0 );
		assertEquals( 3.0f, ip.getSigma(), 0f );

		// voxel size unknown: square pixels
		final InterestPoint iq = new InterestPoint( 0, new double[] { 3, 4, 5 }, 1f, 1.5f, 1f );
		DownsampleTools.correctForDownsampling( new ArrayList<>( Arrays.asList( iq ) ), mipmap( 1, 2, 1 ) );
		assertEquals( 1.5f, iq.getSigma(), 0f );

		// the Difference-of-Gaussian without a z anisotropy: isotropic in pixels (null) for equal pixels in x and y,
		// otherwise the lateral ratio of the calibration and z as wide in pixels as the reference axis
		assertNull( DownsampleTools.anisotropyDoG( view( 1, 1, 4 ), mipmap( 1, 1, 1 ), nan ) );
		assertNull( DownsampleTools.anisotropyDoG( view( 0.45, 0.45, 2 ), mipmap( 4, 4, 2 ), nan ) );
		assertNull( DownsampleTools.anisotropyDoG( coarseY, mipmap( 2, 1, 1 ), nan ) );
		assertArrayEquals( new double[] { 1, 2, 1 }, DownsampleTools.anisotropyDoG( coarseY, mipmap( 1, 1, 1 ), nan ), 0.0 );
		assertArrayEquals( new double[] { 2, 1, 1 }, DownsampleTools.anisotropyDoG( coarseX, mipmap( 2, 2, 1 ), nan ), 0.0 );
		assertArrayEquals( new double[] { 2, 1, 1 }, DownsampleTools.anisotropyDoG( view( 1, 1, 4 ), mipmap( 2, 1, 1 ), nan ), 0.0 );
		assertArrayEquals( new double[] { 2, 1, 3 }, DownsampleTools.anisotropyDoG( coarseX, mipmap( 1, 1, 1 ), 3.0 ), 0.0 );
		assertArrayEquals( new double[] { 1, 1, 4 }, DownsampleTools.anisotropyDoG( view( 1, 1, 4 ), mipmap( 1, 1, 1 ), 4.0 ), 0.0 );
	}

	/**
	 * The merge of near-identical scale-space detections that were mapped to full resolution: the octave of a point
	 * follows from its sigma, which was scaled with the factor of the stored sigma, not with the downsampling in x
	 */
	@Test
	public void testMergeAfterUnequalDownsampling()
	{
		final double sigmaMin = 1.6;

		// in pixels of the opened image: 1 duplicates 0, 2 has the other sign, 3 (octave 2) duplicates the stronger 4, 5 (octave 2) is far enough from both
		final ArrayList< InterestPoint > points = new ArrayList<>();
		points.add( new InterestPoint( 0, new double[] { 10, 10, 10 }, 0.5f, (float)sigmaMin, 1f ) );
		points.add( new InterestPoint( 1, new double[] { 10.3, 10, 10 }, 0.4f, (float)sigmaMin, 1f ) );
		points.add( new InterestPoint( 2, new double[] { 10, 10.4, 10 }, -0.45f, (float)sigmaMin, 1f ) );
		points.add( new InterestPoint( 3, new double[] { 50, 50, 50 }, 0.2f, (float)( sigmaMin * 4 ), 1f ) );
		points.add( new InterestPoint( 4, new double[] { 51.5, 50, 50 }, 0.3f, (float)sigmaMin, 1f ) );
		points.add( new InterestPoint( 5, new double[] { 50, 52.5, 50 }, 0.1f, (float)( sigmaMin * 4 ), 1f ) );

		final List< Integer > expected = Arrays.asList( 0, 2, 4, 5 );
		assertEquals( expected, ids( DoGScaleSpace.mergeDuplicates( points, new double[] { 1, 1, 1 }, sigmaMin, 0.5 ) ) );

		// a view with x pixels twice as large as its y pixels, opened with y downsampled by 2 (square pixels): the positions
		// scale by (1, 2, 1), the stored sigma by 2 (full-resolution pixels of y per pixel of the opened image)
		final double[] voxelSize = new double[] { 2, 1, 1 }, scale = new double[] { 1, 2, 1 };
		final double sigmaScale = DownsampleTools.sigmaScale( voxelSize, scale );
		assertEquals( 2.0, sigmaScale, 0.0 );

		final ArrayList< InterestPoint > scaled = new ArrayList<>();
		for ( final InterestPoint ip : points )
			scaled.add( new InterestPoint( ip.getId(), new double[] { ip.getL()[ 0 ] * scale[ 0 ], ip.getL()[ 1 ] * scale[ 1 ], ip.getL()[ 2 ] * scale[ 2 ] }, ip.getResponse(), (float)( ip.getSigma() * sigmaScale ), ip.getIntensity() ) );

		assertEquals( expected, ids( DoGScaleSpace.mergeDuplicates( scaled, scale, sigmaScale, sigmaMin, 0.5 ) ) );

		// with the downsampling in x (1) as the factor of the sigmas every point is taken for one octave higher and 5 is merged into 3
		assertTrue( !ids( DoGScaleSpace.mergeDuplicates( scaled, scale, sigmaMin, 0.5 ) ).equals( expected ) );
	}

	static List< Integer > ids( final List< InterestPoint > points )
	{
		final List< Integer > ids = new ArrayList<>();

		for ( final InterestPoint ip : points )
			ids.add( ip.getId() );

		return ids;
	}

	/**
	 * A Difference-of-Gaussian called without a z anisotropy (from code, BigStitcher-Spark by default) still follows
	 * the calibration laterally: on views with unequal pixel sizes in x and y it finds what it finds with the z
	 * anisotropy of the calibration (1 here)
	 */
	@Test
	public void testDoGWithoutZAnisotropy()
	{
		final SpimData2 full = SimulateUtil.setUpRoundBeads();
		final double maxIntensity = maxIntensity( full );

		for ( final int axis : new int[] { 0, 1 } )
		{
			final SpimData2 data = SimulateUtil.subsampled( full, axis, 2 );

			final HashMap< ViewId, List< InterestPoint > > without = detect( data, 1.8, 0.008, Double.NaN, maxIntensity );
			final HashMap< ViewId, List< InterestPoint > > with = detect( data, 1.8, 0.008, 1.0, maxIntensity );

			for ( final ViewId view : with.keySet() )
			{
				assertTrue( with.get( view ).size() > 50 );
				assertEquals( with.get( view ).size(), without.get( view ).size() );

				for ( int i = 0; i < with.get( view ).size(); ++i )
				{
					assertArrayEquals( with.get( view ).get( i ).getL(), without.get( view ).get( i ).getL(), 0.0 );
					assertEquals( with.get( view ).get( i ).getResponse(), without.get( view ).get( i ).getResponse(), 0f );
				}
			}
		}
	}

	/** asserts that two sets of detections of the same views are at the same places in global coordinates */
	static void assertSameDetections( final String what, final SpimData2 dataA, final HashMap< ViewId, List< InterestPoint > > a, final SpimData2 dataB, final HashMap< ViewId, List< InterestPoint > > b, final double distance, final double minFraction )
	{
		for ( final ViewId view : a.keySet() )
		{
			final List< double[] > gA = global( dataA, view, a.get( view ) );
			final List< double[] > gB = global( dataB, view, b.get( view ) );

			final double ab = matched( gA, gB, distance ), ba = matched( gB, gA, distance );

			IOFunctions.println( what + ", view " + view.getViewSetupId() + ": " + gA.size() + " and " + gB.size() + " detections, " + Math.round( 100 * ab ) + "% / " + Math.round( 100 * ba ) + "% within " + distance + " px of one of the other set" );

			assertTrue( gA.size() > 50, what + ": detections " + gA.size() );
			assertTrue( ab > minFraction, what + ", view " + view.getViewSetupId() + ": " + ab + " of the first set found in the second" );
			assertTrue( ba > minFraction, what + ", view " + view.getViewSetupId() + ": " + ba + " of the second set found in the first" );
		}
	}

	static void assertSigma( final HashMap< ViewId, List< InterestPoint > > points, final double sigma )
	{
		for ( final List< InterestPoint > list : points.values() )
			for ( final InterestPoint ip : list )
				assertEquals( (float)sigma, ip.getSigma(), 0f );
	}

	/**
	 * Different downsampling in x and y. A view with unequal pixel sizes opened with only its finer axis downsampled
	 * has square pixels: the Difference-of-Gaussian finds there what it finds in the full view downsampled by 2 in x
	 * and y. A view with square pixels opened with only one axis downsampled is detected with the sigma of the axis
	 * that was not downsampled: it finds what the detection at full resolution finds. The stored sigma is in
	 * full-resolution pixels of the finer lateral axis in all cases.
	 */
	@Test
	public void testUnequalDownsampling()
	{
		final SpimData2 full = SimulateUtil.setUpRoundBeads();
		final double maxIntensity = maxIntensity( full );

		// square pixels of twice the size
		final double sigma = 1.1, threshold = 0.008;
		final HashMap< ViewId, List< InterestPoint > > reference = detect( full, new long[] { 2, 2, 1 }, sigma, threshold, 1.0, maxIntensity );
		assertSigma( reference, sigma * 2 );

		for ( final int axis : new int[] { 0, 1 } )
		{
			final SpimData2 data = SimulateUtil.subsampled( full, axis, 2 );
			final HashMap< ViewId, List< InterestPoint > > points = detect( data, axis == 0 ? new long[] { 1, 2, 1 } : new long[] { 2, 1, 1 }, sigma, threshold, 1.0, maxIntensity );

			assertSameDetections( ( axis == 0 ? "x" : "y" ) + " pixels twice as large, the other axis downsampled by 2", full, reference, data, points, 1.5, 0.9 );
			assertSigma( points, sigma * 2 );
		}

		// square pixels, one axis downsampled: the sigma refers to the other one
		final HashMap< ViewId, List< InterestPoint > > atFullResolution = detect( full, new long[] { 1, 1, 1 }, 1.8, threshold, 1.0, maxIntensity );
		assertSigma( atFullResolution, 1.8 );

		for ( final long[] downsampling : new long[][] { { 2, 1, 1 }, { 1, 2, 1 } } )
		{
			final HashMap< ViewId, List< InterestPoint > > points = detect( full, downsampling, 1.8, threshold, 1.0, maxIntensity );

			assertSameDetections( "square pixels downsampled by " + Util.printCoordinates( downsampling ), full, atFullResolution, full, points, 1.5, 0.9 );
			assertSigma( points, 1.8 );
		}
	}

	/** the detections of the views by the scale-space driver at full resolution, in pixels of the views */
	static HashMap< ViewId, List< InterestPoint > > detectScaleSpace( final SpimData2 data, final double sigmaMin, final double threshold, final double maxIntensity )
	{
		final ScaleSpaceDetectionParameters p = new ScaleSpaceDetectionParameters();
		p.imgloader = data.getSequenceDescription().getImgLoader();
		p.toProcess = new ArrayList<>( data.getSequenceDescription().getViewDescriptions().values() );
		Collections.sort( (List< ViewDescription >)p.toProcess );
		p.downsampling = new long[] { 1, 1, 1 };
		p.minIntensity = 0;
		p.maxIntensity = maxIntensity;
		p.scaleSpace.sigmaMin = sigmaMin;
		p.scaleSpace.threshold = threshold;
		p.scaleSpace.steps = 3;
		p.scaleSpace.octaves = -1;

		return ScaleSpace.findInterestPoints( p );
	}

	/**
	 * The scale space on views with unequal pixel sizes in x and y, either axis the coarser one: the detections of the
	 * full views are found again at the same places with the same sizes (the stored sigma is in full-resolution pixels
	 * of the finer lateral axis, which are the pixels of the full views)
	 */
	@Test
	public void testScaleSpaceDriver()
	{
		final SpimData2 full = SimulateUtil.setUpRoundBeads();
		final double maxIntensity = maxIntensity( full );

		final HashMap< ViewId, List< InterestPoint > > reference = detectScaleSpace( full, 1.5, 0.01, maxIntensity );

		for ( final int axis : new int[] { 0, 1 } )
		{
			final String what = "scale space, " + ( axis == 0 ? "x" : "y" ) + " pixels twice as large";
			final SpimData2 data = SimulateUtil.subsampled( full, axis, 2 );
			final HashMap< ViewId, List< InterestPoint > > points = detectScaleSpace( data, 1.5, 0.01, maxIntensity );

			assertSameDetections( what, full, reference, data, points, 1.0, 0.9 );

			// the sizes of the detections that were found again
			for ( final ViewId view : reference.keySet() )
			{
				final List< double[] > gFull = global( full, view, reference.get( view ) );
				final List< double[] > gCoarse = global( data, view, points.get( view ) );

				int compared = 0, similar = 0;

				for ( int i = 0; i < gFull.size(); ++i )
					for ( int j = 0; j < gCoarse.size(); ++j )
						if ( Math.abs( gFull.get( i )[ 0 ] - gCoarse.get( j )[ 0 ] ) < 0.5 && Math.abs( gFull.get( i )[ 1 ] - gCoarse.get( j )[ 1 ] ) < 0.5 && Math.abs( gFull.get( i )[ 2 ] - gCoarse.get( j )[ 2 ] ) < 0.5 )
						{
							++compared;

							if ( Math.abs( reference.get( view ).get( i ).getSigma() - points.get( view ).get( j ).getSigma() ) < 0.25 * reference.get( view ).get( i ).getSigma() )
								++similar;
						}

				IOFunctions.println( what + ", view " + view.getViewSetupId() + ": " + similar + " of " + compared + " detections at the same place have a sigma within 25%" );

				assertTrue( compared > 40, what + ": compared " + compared );
				assertTrue( similar > 0.9 * compared, what + ": " + similar + " of " + compared + " sigmas agree" );
			}
		}
	}

	/**
	 * The dialog (headless) with different downsampling in x and y on views with x pixels twice as large as the y
	 * pixels: the detection is the driver's (the Difference-of-Gaussian used the x factor for y before), the parameter
	 * string and the recorded parameters carry both factors, and the BigStitcher-Spark command gets them as -dsx / -dsy
	 */
	@Test
	public void testDialogWithUnequalDownsampling()
	{
		final SpimData2 data = SimulateUtil.subsampled( SimulateUtil.setUpRoundBeads(), 0, 2 );
		final ArrayList< ViewId > views = new ArrayList<>( data.getSequenceDescription().getViewDescriptions().keySet() );
		Collections.sort( views );
		final TimePoint tp = data.getSequenceDescription().getTimePoints().getTimePointsOrdered().get( 0 );
		final double max = maxIntensity( data );

		final DifferenceOfGaussianGUI gui = new DifferenceOfGaussianGUI( data, views )
		{{
			localization = 1;
			downsampling = new long[] { 1, 2, 1 };
			anisotropyZ = 1.0;
			minIntensity = 0.0;
			maxIntensity = max;
			sigma = 1.1;
			threshold = 0.008;
			findMin = false;
			findMax = true;
		}};

		assertTrue( gui.getParameters().contains( " downsampleX=1 downsampleY=2 downsampleZ=1 anisotropy=1.0 " ), gui.getParameters() );

		final Map< String, String > d = gui.describeParameters();
		assertNull( d.get( "downsampleXY" ) );
		assertEquals( "1", d.get( "downsampleX" ) );
		assertEquals( "2", d.get( "downsampleY" ) );
		assertEquals( "1", d.get( "downsampleZ" ) );

		final HashMap< ViewId, List< InterestPoint > > viaGUI = gui.findInterestPoints( tp );
		final HashMap< ViewId, List< InterestPoint > > viaDriver = detect( data, new long[] { 1, 2, 1 }, 1.1, 0.008, 1.0, max );

		for ( final ViewId view : views )
		{
			final List< InterestPoint > g = viaGUI.get( view ), r = viaDriver.get( view );

			assertTrue( g.size() > 50, "view " + view.getViewSetupId() + ": " + g.size() + " points" );
			assertEquals( r.size(), g.size() );

			for ( int i = 0; i < g.size(); ++i )
			{
				assertArrayEquals( r.get( i ).getL(), g.get( i ).getL(), 0.0 );

				// pixels of y at full resolution: the opened pixels are twice as large
				assertEquals( 2.2f, g.get( i ).getSigma(), 0f );
			}
		}

		final Map< String, String > params = new LinkedHashMap<>( d );
		params.put( "label", "beads" );
		final List< String > lines = ActionToSparkCli.render( new ActionRecord( "detect-interestpoints", System.currentTimeMillis(), DifferenceOfGaussianGUI.class.getName(), params, views, "interestpoints:beads" ), "dataset.xml" );

		for ( final String line : lines )
			IOFunctions.println( line );

		assertTrue( lines.stream().anyMatch( l -> !l.startsWith( "#" ) && l.contains( "-dsx 1" ) && l.contains( "-dsy 2" ) && l.contains( "-dsz 1" ) && !l.contains( "-dsxy" ) ) );
	}

	/**
	 * Outside the detection: the anisotropy factor of a dataset is the z voxel over the finer lateral voxel (what the
	 * calibration scales z by relative to the calibrated xy plane; it was z over the mean of x and y), the splitting gets
	 * the voxel size per dimension, and the calibration of a displayed image is set per axis
	 */
	@Test
	public void testAnisotropyFactorAndCalibration()
	{
		final List< ViewSetup > setups = new ArrayList<>();
		final double[][] voxelSizes = new double[][] { { 1, 2, 4 }, { 2, 1, 4 }, { 0.45, 0.45, 2.0 }, { 1, 1, 1 } };

		for ( int i = 0; i < voxelSizes.length; ++i )
			setups.add( new ViewSetup( i, "", new FinalDimensions( 10, 10, 10 ), new FinalVoxelDimensions( "um", voxelSizes[ i ] ), new Tile( 0 ), new Channel( 0 ), new Angle( i ), new Illumination( 0 ) ) );

		final SequenceDescription seq = new SequenceDescription( new TimePoints( Arrays.asList( new TimePoint( 0 ) ) ), setups, null, null );

		// either lateral axis the coarser one: 4, where z / mean( x, y ) was 2.67
		assertEquals( 4.0, TransformationTools.getAverageAnisotropyFactor( seq, Arrays.asList( new ViewId( 0, 0 ) ) ), 0.0 );
		assertEquals( 4.0, TransformationTools.getAverageAnisotropyFactor( seq, Arrays.asList( new ViewId( 0, 1 ) ) ), 0.0 );
		assertArrayEquals( new double[] { 1, 2, 4 }, TransformationTools.getAverageVoxelRatios( seq, Arrays.asList( new ViewId( 0, 0 ) ) ), 0.0 );
		assertArrayEquals( new double[] { 2, 1, 4 }, TransformationTools.getAverageVoxelRatios( seq, Arrays.asList( new ViewId( 0, 1 ) ) ), 0.0 );

		// equal pixel sizes in x and y: the value it always had, z / ( ( x + y ) / 2 ), and its average over the views
		assertEquals( 2.0 / ( ( 0.45 + 0.45 ) / 2 ), TransformationTools.getAverageAnisotropyFactor( seq, Arrays.asList( new ViewId( 0, 2 ) ) ), 0.0 );
		assertEquals( ( 2.0 / ( ( 0.45 + 0.45 ) / 2 ) + 1.0 ) / 2.0, TransformationTools.getAverageAnisotropyFactor( seq, Arrays.asList( new ViewId( 0, 2 ), new ViewId( 0, 3 ) ) ), 0.0 );
		assertArrayEquals( new double[] { 1, 1, 2.0 / 0.45 }, TransformationTools.getAverageVoxelRatios( seq, Arrays.asList( new ViewId( 0, 2 ) ) ), 0.0 );
		assertArrayEquals( new double[] { 1, 1, 1 }, TransformationTools.getAverageVoxelRatios( seq, new ArrayList< ViewId >() ), 0.0 );

		// a displayed image: pixel width and height (and the origins) per axis
		final ImagePlus imp = new ImagePlus( "fused", new FloatProcessor( 4, 4 ) );
		DisplayImage.setCalibration( imp, new FinalInterval( new long[] { -10, -20, -30 }, new long[] { 9, 19, 29 } ), 2.0, 3.0, new double[] { 0.5, 1.0, 2.0 }, "mm" );

		assertEquals( 1.0, imp.getCalibration().pixelWidth, 0.0 );
		assertEquals( 2.0, imp.getCalibration().pixelHeight, 0.0 );
		assertEquals( 12.0, imp.getCalibration().pixelDepth, 0.0 );
		assertEquals( 2.5, imp.getCalibration().xOrigin, 0.0 );
		assertEquals( 10.0, imp.getCalibration().yOrigin, 0.0 );
		assertEquals( 30.0, imp.getCalibration().zOrigin, 0.0 );
		assertEquals( "mm", imp.getCalibration().getUnit() );

		// square pixels: what the mean of x and y gave
		DisplayImage.setCalibration( imp, new FinalInterval( new long[] { -10, -20, -30 }, new long[] { 9, 19, 29 } ), Double.NaN, Double.NaN, new double[] { 0.45, 0.45, 2.0 }, "um" );

		assertEquals( ( 0.45 + 0.45 ) / 2.0, imp.getCalibration().pixelWidth, 0.0 );
		assertEquals( ( 0.45 + 0.45 ) / 2.0, imp.getCalibration().pixelHeight, 0.0 );
		assertEquals( 2.0, imp.getCalibration().pixelDepth, 0.0 );
	}
}
