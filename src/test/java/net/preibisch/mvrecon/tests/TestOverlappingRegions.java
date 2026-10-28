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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewTransformAffine;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Pair;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.DifferenceOfGaussianGUI;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.interestpointdetection.OverlappingRegions;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoG;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGParameters;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.ScaleSpace;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.ScaleSpaceDetectionParameters;

/**
 * "Only in regions overlapping other views" (OverlappingRegions): the partner rule and the geometry on the simulated
 * dataset with shifted registrations, the combine of the regions' detections, and that the DoG and the scale space
 * restricted to the regions find what a run on the whole view finds inside them.
 */
public class TestOverlappingRegions
{
	static final ViewId V0 = new ViewId( 0, 0 ), V1 = new ViewId( 0, 1 ), V2 = new ViewId( 0, 2 );
	static final long[] DS = new long[] { TestInterestPointDetection.STANDARD_DOWNSAMPLE_XY, TestInterestPointDetection.STANDARD_DOWNSAMPLE_XY, TestInterestPointDetection.STANDARD_DOWNSAMPLE_Z };

	/** shifts a view in world coordinates (= full-resolution pixels of the simulated dataset) */
	static void shift( final SpimData2 data, final ViewId view, final double dx, final double dy )
	{
		final AffineTransform3D t = new AffineTransform3D();
		t.translate( dx, dy, 0 );

		final ViewRegistration vr = data.getViewRegistrations().getViewRegistration( view );
		vr.preconcatenateTransform( new ViewTransformAffine( "shift", t ) );
		vr.updateModel();
	}

	/** the dataset with its setups re-created with the given tiles, channels and angles per setup id (the setters of ViewSetup are protected) */
	static SpimData2 withAttributes( final SpimData2 data, final int[] tiles, final int[] channels, final int[] angles )
	{
		final SequenceDescription seq = data.getSequenceDescription();
		final List< ViewSetup > setups = new ArrayList<>();

		for ( final ViewSetup vs : seq.getViewSetupsOrdered() )
			setups.add( new ViewSetup( vs.getId(), vs.getName(), vs.getSize(), vs.getVoxelSize(), new Tile( tiles[ vs.getId() ] ), new Channel( channels[ vs.getId() ] ), new Angle( angles[ vs.getId() ] ), vs.getIllumination() ) );

		return new SpimData2( data.getBasePathURI(), new SequenceDescription( seq.getTimePoints(), setups, seq.getImgLoader(), seq.getMissingViews() ), data.getViewRegistrations(), data.getViewInterestPoints(), data.getBoundingBoxes(), data.getPointSpreadFunctions(), data.getStitchingResults(), data.getIntensityAdjustments() );
	}

	/** the simulated dataset (3 views of 127x127x49 px, identity registrations) with view 1 shifted by dx1 in x and view 2 by dy2 in y */
	static SpimData2 shifted( final double dx1, final double dy2 )
	{
		final SpimData2 data = SimulateUtil.setUp();
		shift( data, V1, dx1, 0 );
		shift( data, V2, 0, dy2 );
		return data;
	}

	@Test
	public void testPartnersAndGeometry()
	{
		// setups 0 and 1 are tile 0 in channels 0 and 1 (the same volume), setup 2 is tile 1; all the same angle
		final SpimData2 data = withAttributes( SimulateUtil.setUp(), new int[] { 0, 0, 1 }, new int[] { 0, 1, 0 }, new int[] { 0, 0, 0 } );
		shift( data, V2, 64, 0 );

		final List< ViewId > all = Arrays.asList( V0, V1, V2 );

		// the partner rule: another tile or angle of the same timepoint
		assertEquals( List.of( V2 ), OverlappingRegions.otherVolumes( data, V0, all ) );
		assertEquals( List.of( V2 ), OverlappingRegions.otherVolumes( data, V1, all ) );
		assertEquals( List.of( V0, V1 ), OverlappingRegions.otherVolumes( data, V2, all ) );

		// the bounding boxes: shifted by 64 px they overlap, by 364 px they do not
		assertEquals( List.of( V2 ), OverlappingRegions.overlapping( data, V0, List.of( V2 ) ) );
		shift( data, V2, 300, 0 );
		assertTrue( OverlappingRegions.overlapping( data, V0, List.of( V2 ) ).isEmpty() );
		assertNull( OverlappingRegions.intersection( data, V0, V2, new FinalInterval( 64, 64, 49 ), new AffineTransform3D() ) );
		shift( data, V2, -300, 0 );

		// the geometry at downsampling 2, 2, 1: view 2 covers x >= 64 of view 0 at full resolution, x >= 32 in the opened image
		final Interval image = new FinalInterval( new long[] { 0, 0, 0 }, new long[] { 63, 63, 48 } );
		final AffineTransform3D mipmap = new AffineTransform3D();
		mipmap.set( 2, 0, 0 );
		mipmap.set( 2, 1, 1 );

		final Interval region = OverlappingRegions.intersection( data, V0, V2, image, mipmap );
		assertNotNull( region );
		assertArrayEquals( new long[] { 32, 0, 0 }, region.minAsLongArray() );
		assertArrayEquals( new long[] { 63, 63, 48 }, region.maxAsLongArray() );

		// seen from view 2, views 0 and 1 cover the same region x <= 31: one interval
		final List< Interval > regions = OverlappingRegions.intervals( data, V2, Arrays.asList( V0, V1 ), image, mipmap );
		assertEquals( 1, regions.size() );
		assertArrayEquals( new long[] { 0, 0, 0 }, regions.get( 0 ).minAsLongArray() );
		assertArrayEquals( new long[] { 31, 63, 48 }, regions.get( 0 ).maxAsLongArray() );

		// the instance for the GUI: the partners of every view, the regions of a view without one are empty
		final OverlappingRegions forGUI = OverlappingRegions.forOtherVolumes( data, all );
		assertEquals( List.of( V2 ), forGUI.partners( V0 ) );
		assertEquals( List.of( V0, V1 ), forGUI.partners( V2 ) );
		assertEquals( 1, forGUI.intervals( V1, image, mipmap ).size() );
		assertTrue( forGUI.intervals( new ViewId( 0, 7 ), image, mipmap ).isEmpty() );
	}

	static InterestPoint ip( final int id, final double x, final double y, final double z )
	{
		return new InterestPoint( id, new double[] { x, y, z }, (float)( x * 0.01 ), 1.5f, 100f );
	}

	@Test
	public void testCombine()
	{
		final List< InterestPoint > a = List.of( ip( 0, 10, 10, 10 ), ip( 1, 20, 20, 20 ) );
		final List< InterestPoint > b = List.of( ip( 0, 10.3, 10, 10 ), ip( 1, 30, 30, 30 ), ip( 2, 20.6, 20, 20 ) );

		final ArrayList< InterestPoint > c = OverlappingRegions.combine( Arrays.asList( new ArrayList<>(), a, b ), 0.5 );

		// the first list as is, then the points of the second that are no duplicates (10.3 is one, 20.6 is not), new ids
		assertEquals( 4, c.size() );
		final double[][] expected = new double[][] { { 10, 10, 10 }, { 20, 20, 20 }, { 30, 30, 30 }, { 20.6, 20, 20 } };
		for ( int i = 0; i < expected.length; ++i )
		{
			assertEquals( i, c.get( i ).getId() );
			assertArrayEquals( expected[ i ], c.get( i ).getL(), 1e-9 );
			assertEquals( (float)( expected[ i ][ 0 ] * 0.01 ), c.get( i ).getResponse(), 1e-6f, "the attributes travel with the point" );
			assertEquals( 1.5f, c.get( i ).getSigma(), 0f );
		}
		assertEquals( 5, OverlappingRegions.size( Arrays.asList( a, b ) ) );
		assertTrue( OverlappingRegions.combine( Arrays.asList( new ArrayList< InterestPoint >() ), 0.5 ).isEmpty() );
	}

	static DoGParameters dogParameters( final SpimData2 data, final List< ViewId > views )
	{
		final DoGParameters dog = new DoGParameters();

		dog.imgloader = data.getSequenceDescription().getImgLoader();
		dog.toProcess = SpimData2.getAllViewIdsForTimePointSorted( data, views, data.getSequenceDescription().getTimePoints().getTimePointsOrdered().get( 0 ) );
		dog.localization = 1;
		dog.downsampleXY = (int)DS[ 0 ];
		dog.downsampleZ = (int)DS[ 2 ];
		dog.anisotropyZ = Double.NaN;
		dog.minIntensity = TestInterestPointDetection.GLOBAL_MIN_INTENSITY;
		dog.maxIntensity = TestInterestPointDetection.GLOBAL_MAX_INTENSITY;
		dog.sigma = TestInterestPointDetection.STANDARD_SIGMA;
		dog.threshold = TestInterestPointDetection.STANDARD_THRESHOLD;
		dog.findMin = false;
		dog.findMax = true;

		return dog;
	}

	static ScaleSpaceDetectionParameters scaleSpaceParameters( final SpimData2 data, final List< ViewId > views )
	{
		final ScaleSpaceDetectionParameters p = new ScaleSpaceDetectionParameters();

		p.imgloader = data.getSequenceDescription().getImgLoader();
		p.toProcess = SpimData2.getAllViewIdsForTimePointSorted( data, views, data.getSequenceDescription().getTimePoints().getTimePointsOrdered().get( 0 ) );
		p.downsampling = DS.clone();
		p.downsampleXY = (int)DS[ 0 ];
		p.downsampleZ = (int)DS[ 2 ];
		p.minIntensity = TestInterestPointDetection.GLOBAL_MIN_INTENSITY;
		p.maxIntensity = TestInterestPointDetection.GLOBAL_MAX_INTENSITY;
		p.scaleSpace.sigmaMin = TestInterestPointDetection.STANDARD_SIGMA;
		p.scaleSpace.threshold = TestInterestPointDetection.STANDARD_THRESHOLD;
		p.scaleSpace.steps = 4;
		p.scaleSpace.octaves = 2; // the same octaves for the regions and the whole view (the regions are too small for more)
		p.scaleSpace.detectFinestLevel = true;

		return p;
	}

	/** the DoG in the regions finds what the DoG on the whole view finds inside them, and nothing outside */
	@Test
	public void testDoGInRegions()
	{
		final SpimData2 data = shifted( 64, 64 );
		final List< ViewId > views = Arrays.asList( V0, V1, V2 );

		final DoGParameters inRegions = dogParameters( data, views );
		inRegions.regions = OverlappingRegions.forOtherVolumes( data, views );

		compare( data, views, DoG.findInterestPoints( dogParameters( data, views ) ), DoG.findInterestPoints( inRegions ), inRegions.regions, 2, 0.01 );
	}

	/** the scale space in the regions finds what the scale space on the whole view finds inside them, and nothing outside */
	@Test
	public void testScaleSpaceInRegions()
	{
		final SpimData2 data = shifted( 64, 64 );
		final List< ViewId > views = Arrays.asList( V0, V1, V2 );

		final ScaleSpaceDetectionParameters inRegions = scaleSpaceParameters( data, views );
		inRegions.regions = OverlappingRegions.forOtherVolumes( data, views );

		compare( data, views, ScaleSpace.findInterestPoints( scaleSpaceParameters( data, views ) ), ScaleSpace.findInterestPoints( inRegions ), inRegions.regions, 4, 0.05 );
	}

	/** the dialog class without its dialog: the checkbox reaches the parameters, the string, the history record and the detection */
	@Test
	public void testGUIWiring()
	{
		final SpimData2 data = shifted( 64, 64 );
		final List< ViewId > views = Arrays.asList( V0, V1, V2 );
		final TimePoint tp = data.getSequenceDescription().getTimePoints().getTimePointsOrdered().get( 0 );
		final DoGParameters[] params = new DoGParameters[ 2 ];

		final DifferenceOfGaussianGUI on = new DifferenceOfGaussianGUI( data, views )
		{{
			overlappingOnly = true;
			localization = 1;
			downsampling = DS.clone();
			anisotropyZ = Double.NaN;
			minIntensity = TestInterestPointDetection.GLOBAL_MIN_INTENSITY;
			maxIntensity = TestInterestPointDetection.GLOBAL_MAX_INTENSITY;
			sigma = TestInterestPointDetection.STANDARD_SIGMA;
			threshold = TestInterestPointDetection.STANDARD_THRESHOLD;
			findMin = false;
			findMax = true;
			params[ 0 ] = detectionParameters();
		}};
		final DifferenceOfGaussianGUI off = new DifferenceOfGaussianGUI( data, views )
		{{
			overlappingOnly = false;
			localization = 1;
			downsampling = DS.clone();
			anisotropyZ = Double.NaN;
			minIntensity = 0;
			maxIntensity = 1137;
			sigma = 1.8;
			threshold = 0.008;
			params[ 1 ] = detectionParameters();
		}};

		assertNotNull( params[ 0 ].regions );
		assertEquals( List.of( V1, V2 ), params[ 0 ].regions.partners( V0 ), "three angles: every other view is another volume" );
		assertNull( params[ 1 ].regions );
		assertTrue( on.getParameters().contains( " overlappingOnly=true " ), on.getParameters() );
		assertTrue( off.getParameters().contains( " overlappingOnly=false " ), off.getParameters() );
		assertEquals( "true", on.describeParameters().get( "overlappingOnly" ) );
		assertEquals( "true", on.describeParameters().get( "onlyCompareOverlapTiles" ) );
		assertNull( off.describeParameters().get( "overlappingOnly" ) );
		assertNull( off.describeParameters().get( "onlyCompareOverlapTiles" ) );

		// the detection through the dialog class is the one of the parameters
		final DoGParameters direct = dogParameters( data, views );
		direct.regions = OverlappingRegions.forOtherVolumes( data, views );
		final HashMap< ViewId, List< InterestPoint > > fromGUI = on.findInterestPoints( tp ), fromParameters = DoG.findInterestPoints( direct );
		for ( final ViewId view : views )
			assertEquals( fromParameters.get( view ).size(), fromGUI.get( view ).size(), view.toString() );
	}

	/**
	 * @param margin - how far (in pixels of the opened image) a point has to be inside a region to be compared between the two runs
	 * @param tolerance - the distance (full-resolution pixels) within which a point of one run matches a point of the other
	 */
	@SuppressWarnings("rawtypes")
	static void compare( final SpimData2 data, final List< ViewId > views, final HashMap< ViewId, List< InterestPoint > > whole, final HashMap< ViewId, List< InterestPoint > > inRegions, final OverlappingRegions regions, final double margin, final double tolerance )
	{
		for ( final ViewId view : views )
		{
			final ViewDescription vd = data.getSequenceDescription().getViewDescription( view );
			final Pair< RandomAccessibleInterval, AffineTransform3D > input = DownsampleTools.openAndDownsample( data.getSequenceDescription().getImgLoader(), vd, DS, false );
			final List< Interval > intervals = regions.intervals( view, new FinalInterval( input.getA() ), input.getB() );
			final List< InterestPoint > all = whole.get( view ), restricted = inRegions.get( view );

			assertTrue( intervals.size() >= 1 && intervals.size() <= 2, view + ": " + intervals.size() + " regions" );
			assertTrue( restricted.size() > 10, view + ": " + restricted.size() + " points in the regions" );
			assertTrue( restricted.size() < all.size(), view + ": " + restricted.size() + " points in the regions, " + all.size() + " in the whole view" );

			// every point of the regions lies inside one of them (within a pixel: the subpixel refinement)
			for ( final InterestPoint p : restricted )
				assertTrue( inside( p, intervals, input.getB(), -1 ), view + ": " + Arrays.toString( p.getL() ) + " outside the regions " + intervals );

			// a point well inside a region is found by both runs at the same position
			int compared = 0;
			for ( final InterestPoint p : all )
				if ( inside( p, intervals, input.getB(), margin ) )
				{
					assertTrue( near( p, restricted, tolerance ), view + ": " + Arrays.toString( p.getL() ) + " of the whole view not found in the regions" );
					++compared;
				}
			for ( final InterestPoint p : restricted )
				if ( inside( p, intervals, input.getB(), margin ) )
					assertTrue( near( p, all, tolerance ), view + ": " + Arrays.toString( p.getL() ) + " of the regions not found in the whole view" );
			assertTrue( compared > 10, view + ": only " + compared + " points compared" );

			// no duplicates where the regions overlap each other
			for ( int i = 0; i < restricted.size(); ++i )
				for ( int j = i + 1; j < restricted.size(); ++j )
					assertTrue( distance( restricted.get( i ), restricted.get( j ) ) > 0.4, view + ": duplicate " + Arrays.toString( restricted.get( i ).getL() ) );

			// ids 0..n-1
			for ( int i = 0; i < restricted.size(); ++i )
				assertEquals( i, restricted.get( i ).getId() );
		}
	}

	/** whether a full-resolution point lies at least margin pixels (negative: at most -margin outside) inside one of the intervals of the opened image */
	static boolean inside( final InterestPoint p, final List< Interval > intervals, final AffineTransform3D mipmap, final double margin )
	{
		final double[] q = new double[ 3 ];
		mipmap.applyInverse( q, p.getL() );

		for ( final Interval interval : intervals )
		{
			boolean in = true;
			for ( int d = 0; d < 3; ++d )
				if ( q[ d ] < interval.min( d ) + margin || q[ d ] > interval.max( d ) - margin )
					in = false;
			if ( in )
				return true;
		}

		return false;
	}

	static boolean near( final InterestPoint p, final List< InterestPoint > points, final double tolerance )
	{
		for ( final InterestPoint q : points )
			if ( distance( p, q ) <= tolerance )
				return true;

		return false;
	}

	static double distance( final InterestPoint a, final InterestPoint b )
	{
		double sum = 0;
		for ( int d = 0; d < 3; ++d )
			sum += ( a.getL()[ d ] - b.getL()[ d ] ) * ( a.getL()[ d ] - b.getL()[ d ] );
		return Math.sqrt( sum );
	}
}
