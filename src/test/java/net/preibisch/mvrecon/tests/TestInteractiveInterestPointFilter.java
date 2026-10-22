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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.fiji.plugin.Filter_Detections;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint.InteractiveInterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter.Axis;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;

/**
 * The non-GUI parts of the interactive filter of the interest point explorer (the counting behind the linked histograms
 * included), the defaults it leaves for Filter_Detections, and the window itself where a display is available
 */
public class TestInteractiveInterestPointFilter
{
	@TempDir
	Path tempDir;

	protected static ArrayList< InterestPoint > points()
	{
		final ArrayList< InterestPoint > points = new ArrayList<>();

		points.add( new InterestPoint( 0, new double[] { 0, 0, 0 }, -0.5f, 2.0f, 100f ) );
		points.add( new InterestPoint( 1, new double[] { 1, 0, 0 }, 0.1f, 1.0f, 300f ) );
		points.add( new InterestPoint( 2, new double[] { 2, 0, 0 }, 0.3f, 4.0f, Float.NaN ) );
		points.add( new InterestPoint( 3, new double[] { 3, 0, 0 }, Float.NaN, Float.NaN, 200f ) );
		points.add( new InterestPoint( 4, new double[] { 4, 0, 0 }, 0.1f, 8.0f, 400f ) );

		return points;
	}

	@Test
	public void testSortedValues()
	{
		final ArrayList< InterestPoint > points = points();

		// |response|, NaN dropped, sorted
		assertArrayEquals( new float[] { 0.1f, 0.1f, 0.3f, 0.5f }, InteractiveInterestPointFilter.sortedValues( points, Axis.RESPONSE ), 0f );
		assertArrayEquals( new float[] { 1f, 2f, 4f, 8f }, InteractiveInterestPointFilter.sortedValues( points, Axis.SIGMA ), 0f );
		assertArrayEquals( new float[] { 100f, 200f, 300f, 400f }, InteractiveInterestPointFilter.sortedValues( points, Axis.INTENSITY ), 0f );
		assertEquals( 0, InteractiveInterestPointFilter.sortedValues( new ArrayList<>(), Axis.SIGMA ).length );
	}

	@Test
	public void testCount()
	{
		final float[] sigma = InteractiveInterestPointFilter.sortedValues( points(), Axis.SIGMA ); // 1 2 4 8

		assertEquals( 4, InteractiveInterestPointFilter.count( sigma, Double.NaN, Double.NaN ) );
		assertEquals( 3, InteractiveInterestPointFilter.count( sigma, 2, Double.NaN ) ); // inclusive
		assertEquals( 2, InteractiveInterestPointFilter.count( sigma, 2.5, Double.NaN ) );
		assertEquals( 3, InteractiveInterestPointFilter.count( sigma, Double.NaN, 4 ) ); // inclusive
		assertEquals( 2, InteractiveInterestPointFilter.count( sigma, 2, 4 ) );
		assertEquals( 0, InteractiveInterestPointFilter.count( sigma, 9, Double.NaN ) );
		assertEquals( 0, InteractiveInterestPointFilter.count( sigma, Double.NaN, 0.5 ) );
		assertEquals( 0, InteractiveInterestPointFilter.count( new float[ 0 ], 1, 2 ) );

		// the same counts as the filter gives
		final List< InterestPoint > points = points();
		final float[] response = InteractiveInterestPointFilter.sortedValues( points, Axis.RESPONSE );
		final InterestPointFilter filter = InterestPointFilter.of( Axis.RESPONSE, 0.1, 0.3 );
		int pass = 0;

		for ( final InterestPoint ip : points )
			if ( filter.acceptKnown( ip ) )
				++pass;

		assertEquals( pass, InteractiveInterestPointFilter.count( response, 0.1, 0.3 ) );
		assertEquals( 3, pass );
	}

	@Test
	public void testHistogram()
	{
		final float[] sigma = InteractiveInterestPointFilter.sortedValues( points(), Axis.SIGMA ); // 1 2 4 8

		final double[][] linear = InteractiveInterestPointFilter.histogram( sigma, false, 0, 7 );
		assertEquals( 7, linear[ 0 ].length );
		assertEquals( 4.0, Arrays.stream( linear[ 1 ] ).sum(), 0.0 );
		assertEquals( 1.0, linear[ 1 ][ 0 ], 0.0 ); // 1
		assertEquals( 1.0, linear[ 1 ][ 1 ], 0.0 ); // 2
		assertEquals( 1.0, linear[ 1 ][ 3 ], 0.0 ); // 4
		assertEquals( 1.0, linear[ 1 ][ 6 ], 0.0 ); // 8 (the maximum falls into the last bin)
		assertEquals( 1.5, linear[ 0 ][ 0 ], 1e-9 );

		// in log space 1, 2, 4, 8 are equidistant: 3 bins, the maximum in the last, the others at the bin starts
		final double[][] log = InteractiveInterestPointFilter.histogram( sigma, true, 0, 3 );
		assertArrayEquals( new double[] { 1, 1, 2 }, log[ 1 ], 0.0 );
		assertEquals( Math.log10( 1 ) + ( Math.log10( 8 ) - Math.log10( 1 ) ) / 6, log[ 0 ][ 0 ], 1e-9 );

		final double[][] empty = InteractiveInterestPointFilter.histogram( new float[ 0 ], false, 0, 5 );
		assertEquals( 0.0, Arrays.stream( empty[ 1 ] ).sum(), 0.0 );

		// a single value does not divide by zero
		final double[][] single = InteractiveInterestPointFilter.histogram( new float[] { 3f }, true, 0, 5 );
		assertEquals( 1.0, Arrays.stream( single[ 1 ] ).sum(), 0.0 );
	}

	@Test
	public void testGlog()
	{
		// all values > 0: a plain log10
		assertEquals( 0.0, InteractiveInterestPointFilter.glogConstant( new float[ 0 ] ), 0.0 );
		assertEquals( 0.0, InteractiveInterestPointFilter.glogConstant( new float[] { 1f, 2f } ), 0.0 );
		assertEquals( Math.log10( 7 ), InteractiveInterestPointFilter.glog10( 7, 0 ), 0.0 );
		assertEquals( 7, InteractiveInterestPointFilter.glog10Inverse( Math.log10( 7 ), 0 ), 1e-12 );

		// values <= 0 (an intensity of 0 on a black voxel): c = the smallest positive value, the range if there is none
		assertEquals( 16.0, InteractiveInterestPointFilter.glogConstant( new float[] { 0f, 0f, 16f, 17f, 100f } ), 0.0 );
		assertEquals( 5.0, InteractiveInterestPointFilter.glogConstant( new float[] { -5f, -1f, 0f } ), 0.0 );

		// glog10( 0 ) = log10( c / 2 ), log10( x ) far above c, strictly increasing, invertible everywhere
		assertEquals( Math.log10( 8 ), InteractiveInterestPointFilter.glog10( 0, 16 ), 1e-12 );
		assertEquals( 6, InteractiveInterestPointFilter.glog10( 1e6, 16 ), 1e-9 );
		assertTrue( InteractiveInterestPointFilter.glog10( 16, 16 ) > Math.log10( 16 ) );

		double last = Double.NEGATIVE_INFINITY;
		for ( final double x : new double[] { -1000, -50, -1, 0, 1, 16, 17, 1000, 1e6 } )
		{
			final double s = InteractiveInterestPointFilter.glog10( x, 16 );
			assertTrue( s > last, "glog10 is strictly increasing" );
			last = s;
			assertEquals( x, InteractiveInterestPointFilter.glog10Inverse( s, 16 ), 1e-6 * Math.max( 1, Math.abs( x ) ), "inverse of glog10 at " + x );
		}

		// the two zeros and the value at c share the first of 4 bins, 200 is in the third, the maximum in the last
		final float[] values = new float[] { 0f, 0f, 20f, 200f, 2000f };
		final double c = InteractiveInterestPointFilter.glogConstant( values );
		assertEquals( 20.0, c, 0.0 );
		final double[][] h = InteractiveInterestPointFilter.histogram( values, true, c, 4 );
		assertArrayEquals( new double[] { 3, 0, 1, 1 }, h[ 1 ], 0.0 );
		final double lo = InteractiveInterestPointFilter.glog10( 0, c ), hi = InteractiveInterestPointFilter.glog10( 2000, c );
		assertEquals( 1.0, lo, 1e-12 ); // log10( 20 / 2 )
		assertEquals( lo + ( hi - lo ) / 8, h[ 0 ][ 0 ], 1e-9 );
		assertEquals( 0, InteractiveInterestPointFilter.bin( 0f, lo, hi - lo, true, c, 4 ) );
		assertEquals( 2, InteractiveInterestPointFilter.bin( 200f, lo, hi - lo, true, c, 4 ) );
		assertEquals( -1, InteractiveInterestPointFilter.bin( Float.NaN, lo, hi - lo, true, c, 4 ) );
	}

	@Test
	public void testBin()
	{
		// 10 linear bins over [0, 10]: the maximum falls into the last bin, values outside are clamped, no value = -1
		assertEquals( 1, InteractiveInterestPointFilter.bin( 1f, 0, 10, false, 0, 10 ) );
		assertEquals( 9, InteractiveInterestPointFilter.bin( 10f, 0, 10, false, 0, 10 ) );
		assertEquals( 0, InteractiveInterestPointFilter.bin( -3f, 0, 10, false, 0, 10 ) );
		assertEquals( 9, InteractiveInterestPointFilter.bin( 30f, 0, 10, false, 0, 10 ) );
		assertEquals( -1, InteractiveInterestPointFilter.bin( Float.NaN, 0, 10, false, 0, 10 ) );
		assertEquals( -1, InteractiveInterestPointFilter.bin( Float.POSITIVE_INFINITY, 0, 10, false, 0, 10 ) );

		// 4 log bins over [1, 100]
		assertEquals( 0, InteractiveInterestPointFilter.bin( 1f, 0, 2, true, 0, 4 ) );
		assertEquals( 2, InteractiveInterestPointFilter.bin( 10f, 0, 2, true, 0, 4 ) );
		assertEquals( 3, InteractiveInterestPointFilter.bin( 100f, 0, 2, true, 0, 4 ) );

		// the histogram of sorted values is the same binning
		final float[] sigma = InteractiveInterestPointFilter.sortedValues( points(), Axis.SIGMA ); // 1 2 4 8
		final double[][] h = InteractiveInterestPointFilter.histogram( sigma, false, 0, 7 );
		final int[] bin = new int[ sigma.length ];
		for ( int i = 0; i < sigma.length; ++i )
			bin[ i ] = InteractiveInterestPointFilter.bin( sigma[ i ], 1, 7, false, 0, 7 );
		final double[] counts = new double[ 7 ];
		assertEquals( 4, InteractiveInterestPointFilter.histogram( bin, new boolean[ 0 ][], null, counts ) );
		assertArrayEquals( h[ 1 ], counts, 0.0 );
	}

	@Test
	public void testInRange()
	{
		final float[] values = new float[] { 1f, Float.NaN, 3f, 5f, 0.7f };
		final boolean[] flags = new boolean[ values.length ];

		// no bounds: everything passes, the missing value included (acceptKnown)
		InteractiveInterestPointFilter.inRange( values, Double.NaN, Double.NaN, flags );
		assertArrayEquals( new boolean[] { true, true, true, true, true }, flags );

		// bounds: the missing value does not pass, the bounds are inclusive and compared as floats
		InteractiveInterestPointFilter.inRange( values, 2, Double.NaN, flags );
		assertArrayEquals( new boolean[] { false, false, true, true, false }, flags );
		InteractiveInterestPointFilter.inRange( values, Double.NaN, 3, flags );
		assertArrayEquals( new boolean[] { true, false, true, false, true }, flags );
		InteractiveInterestPointFilter.inRange( values, 3, 3, flags );
		assertArrayEquals( new boolean[] { false, false, true, false, false }, flags );
		InteractiveInterestPointFilter.inRange( values, 0.7, 1, flags );
		assertArrayEquals( new boolean[] { true, false, false, false, true }, flags );

		// the same as the filter decides
		final List< InterestPoint > points = points();
		final float[] response = InteractiveInterestPointFilter.values( points, Axis.RESPONSE );
		final boolean[] pass = new boolean[ points.size() ];
		InteractiveInterestPointFilter.inRange( response, 0.1, 0.3, pass );
		final InterestPointFilter filter = InterestPointFilter.of( Axis.RESPONSE, 0.1, 0.3 );
		for ( int i = 0; i < points.size(); ++i )
			assertEquals( filter.acceptKnown( points.get( i ) ), pass[ i ], "point " + i );
	}

	@Test
	public void testLinkedCounts()
	{
		// the sigma histogram (7 linear bins over [1, 8]) of the points that pass bounds on the other axes
		final List< InterestPoint > points = points();
		final int n = points.size();
		final float[] sigma = InteractiveInterestPointFilter.values( points, Axis.SIGMA );
		final float[] response = InteractiveInterestPointFilter.values( points, Axis.RESPONSE );
		final float[] intensity = InteractiveInterestPointFilter.values( points, Axis.INTENSITY );

		final int[] bin = new int[ n ];
		for ( int i = 0; i < n; ++i )
			bin[ i ] = InteractiveInterestPointFilter.bin( sigma[ i ], 1, 7, false, 0, 7 );
		assertArrayEquals( new int[] { 1, 0, 3, -1, 6 }, bin );

		final boolean[] passR = new boolean[ n ], passI = new boolean[ n ], passS = new boolean[ n ];
		InteractiveInterestPointFilter.inRange( response, 0.2, Double.NaN, passR ); // points 0 and 2
		InteractiveInterestPointFilter.inRange( intensity, Double.NaN, 150, passI ); // point 0
		InteractiveInterestPointFilter.inRange( sigma, Double.NaN, Double.NaN, passS ); // all

		// no flags: all points with a sigma, all in range
		double[] counts = new double[ 7 ];
		assertEquals( 4, InteractiveInterestPointFilter.histogram( bin, new boolean[ 0 ][], passS, counts ) );
		assertArrayEquals( new double[] { 1, 1, 0, 1, 0, 0, 1 }, counts, 0.0 );
		assertEquals( 4, InteractiveInterestPointFilter.countPassing( bin, new boolean[ 0 ][], n ) );
		assertEquals( 5, InteractiveInterestPointFilter.countPassing( null, new boolean[ 0 ][], n ) );

		// the own flags thin the count in range, not the histogram
		counts = new double[ 7 ];
		assertEquals( 2, InteractiveInterestPointFilter.histogram( bin, new boolean[ 0 ][], passR, counts ) );
		assertArrayEquals( new double[] { 1, 1, 0, 1, 0, 0, 1 }, counts, 0.0 );

		// linked to the response bound: points 0 (sigma 2 -> bin 1) and 2 (sigma 4 -> bin 3)
		counts = new double[ 7 ];
		assertEquals( 2, InteractiveInterestPointFilter.histogram( bin, new boolean[][] { passR }, passS, counts ) );
		assertArrayEquals( new double[] { 0, 1, 0, 1, 0, 0, 0 }, counts, 0.0 );
		assertEquals( 2, InteractiveInterestPointFilter.countPassing( bin, new boolean[][] { passR, passS }, n ) );

		// linked to both: point 0 only
		counts = new double[ 7 ];
		assertEquals( 1, InteractiveInterestPointFilter.histogram( bin, new boolean[][] { passR, passI }, passS, counts ) );
		assertArrayEquals( new double[] { 0, 1, 0, 0, 0, 0, 0 }, counts, 0.0 );
		assertEquals( 1, InteractiveInterestPointFilter.countPassing( bin, new boolean[][] { passR, passI, passS }, n ) );

		// the same as the combined filter decides
		final InterestPointFilter both = new InterestPointFilter( 0.2, Double.NaN, Double.NaN, 150, Double.NaN, Double.NaN );
		int pass = 0;
		for ( final InterestPoint ip : points )
			if ( both.acceptKnown( ip ) )
				++pass;
		assertEquals( pass, InteractiveInterestPointFilter.countPassing( null, new boolean[][] { passR, passI, passS }, n ) );
	}

	/**
	 * The window itself (built, never shown): the linked histograms follow the bounds of the other axes; skipped without a display
	 */
	@Test
	public void testWindowLinked() throws Exception
	{
		assumeFalse( GraphicsEnvironment.isHeadless() );

		final SpimData2 spimData = SimulateUtil.setUp();
		spimData.setBasePathURI( tempDir.toUri() );

		final List< ViewId > all = new ArrayList<>( spimData.getSequenceDescription().getViewDescriptions().keySet() );
		final HashMap< ViewId, List< InterestPoint > > map = new HashMap<>();
		map.put( all.get( 0 ), points() );
		InterestPointTools.addInterestPoints( spimData, "beads", map, "test" );

		final boolean oldLinked = InteractiveInterestPointFilter.defaultLinked;
		final String oldBase = Filter_Detections.defaultBaseLabel, oldR = Filter_Detections.defaultResponse, oldI = Filter_Detections.defaultIntensity, oldS = Filter_Detections.defaultSigma;
		final InteractiveInterestPointFilter[] window = new InteractiveInterestPointFilter[ 1 ];

		try
		{
			InteractiveInterestPointFilter.defaultLinked = true;
			SwingUtilities.invokeAndWait( () -> window[ 0 ] = new InteractiveInterestPointFilter( spimData, "beads", all, null, null ) );
			final InteractiveInterestPointFilter w = window[ 0 ];

			assertEquals( 5, w.getNumPoints() );
			assertTrue( w.isLinked() );

			final double[][] full = new double[ 3 ][];
			for ( final Axis axis : Axis.values() )
			{
				full[ axis.ordinal() ] = w.getHistogramCounts( axis );
				assertEquals( 4.0, Arrays.stream( full[ axis.ordinal() ] ).sum(), 0.0, axis.key ); // four points have each attribute
			}

			// sigma in [1, 4] keeps points 0, 1, 2: the response histogram shows their three responses, the intensity histogram the two that are stored, its own histogram is not thinned by its own bounds
			SwingUtilities.invokeAndWait( () -> w.setRange( Axis.SIGMA, 1, 4 ) );
			assertEquals( "sigma=1..4", w.getFilter().toString() );
			assertEquals( 3.0, Arrays.stream( w.getHistogramCounts( Axis.RESPONSE ) ).sum(), 0.0 );
			assertEquals( 2.0, Arrays.stream( w.getHistogramCounts( Axis.INTENSITY ) ).sum(), 0.0 );
			assertEquals( 4.0, Arrays.stream( w.getHistogramCounts( Axis.SIGMA ) ).sum(), 0.0 );
			assertEquals( "3 of 3 in range (3 of 4 pass the other bounds) (1 without value)", w.getCountText( Axis.RESPONSE ) );
			assertEquals( "2 of 2 in range (2 of 4 pass the other bounds) (1 without value)", w.getCountText( Axis.INTENSITY ) );
			assertEquals( "3 of 4 in range (1 without value)", w.getCountText( Axis.SIGMA ) );

			// a response bound on top: |response| >= 0.2 keeps points 0 and 2 (of 0, 1, 2), the sigma histogram follows, the intensity histogram shows point 0 only
			SwingUtilities.invokeAndWait( () -> w.setRange( Axis.RESPONSE, 0.2, Double.NaN ) );
			assertEquals( "response=0.2..;sigma=1..4", w.getFilter().toString() );
			assertEquals( 2.0, Arrays.stream( w.getHistogramCounts( Axis.SIGMA ) ).sum(), 0.0 );
			assertEquals( 1.0, Arrays.stream( w.getHistogramCounts( Axis.INTENSITY ) ).sum(), 0.0 );
			assertEquals( 3.0, Arrays.stream( w.getHistogramCounts( Axis.RESPONSE ) ).sum(), 0.0 );
			assertTrue( w.getSummaryText().startsWith( "2 of 5 points pass" ), w.getSummaryText() );

			// unlinked: the full histograms again, the counts stay
			SwingUtilities.invokeAndWait( () -> w.setLinked( false ) );
			assertFalse( w.isLinked() );
			assertFalse( InteractiveInterestPointFilter.defaultLinked );
			for ( final Axis axis : Axis.values() )
				assertArrayEquals( full[ axis.ordinal() ], w.getHistogramCounts( axis ), 0.0, axis.key );
			assertEquals( "2 of 4 in range (1 without value)", w.getCountText( Axis.RESPONSE ) );
			assertTrue( w.getSummaryText().startsWith( "2 of 5 points pass" ), w.getSummaryText() );

			// linked again
			SwingUtilities.invokeAndWait( () -> w.setLinked( true ) );
			assertEquals( 2.0, Arrays.stream( w.getHistogramCounts( Axis.SIGMA ) ).sum(), 0.0 );

			SwingUtilities.invokeAndWait( () -> w.dispose() );
		}
		finally
		{
			InteractiveInterestPointFilter.defaultLinked = oldLinked;
			Filter_Detections.defaultBaseLabel = oldBase;
			Filter_Detections.defaultResponse = oldR;
			Filter_Detections.defaultIntensity = oldI;
			Filter_Detections.defaultSigma = oldS;
		}
	}

	/**
	 * A label with intensities of 0 (detections on black voxels): the intensity histogram opens in glog10 space and shows them,
	 * a lower bound cuts them, and the all-positive response stays a plain log10
	 */
	@Test
	public void testWindowGlog() throws Exception
	{
		assumeFalse( GraphicsEnvironment.isHeadless() );

		final SpimData2 spimData = SimulateUtil.setUp();
		spimData.setBasePathURI( tempDir.toUri() );

		final ArrayList< InterestPoint > points = new ArrayList<>();
		final float[] intensities = new float[] { 0f, 0f, 16f, 20f, 50f, 100f, 400f, 2000f };
		for ( int i = 0; i < intensities.length; ++i )
			points.add( new InterestPoint( i, new double[] { i, 0, 0 }, 0.01f * ( i + 1 ), 2.0f, intensities[ i ] ) );

		final List< ViewId > all = new ArrayList<>( spimData.getSequenceDescription().getViewDescriptions().keySet() );
		final HashMap< ViewId, List< InterestPoint > > map = new HashMap<>();
		map.put( all.get( 0 ), points );
		InterestPointTools.addInterestPoints( spimData, "beads", map, "test" );

		final boolean[] oldLog = InteractiveInterestPointFilter.defaultLog.clone();
		final String oldBase = Filter_Detections.defaultBaseLabel, oldR = Filter_Detections.defaultResponse, oldI = Filter_Detections.defaultIntensity, oldS = Filter_Detections.defaultSigma;
		final InteractiveInterestPointFilter[] window = new InteractiveInterestPointFilter[ 1 ];

		try
		{
			InteractiveInterestPointFilter.defaultLog[ Axis.INTENSITY.ordinal() ] = true;
			InteractiveInterestPointFilter.defaultLog[ Axis.RESPONSE.ordinal() ] = true;
			SwingUtilities.invokeAndWait( () -> window[ 0 ] = new InteractiveInterestPointFilter( spimData, "beads", all, null, null ) );
			final InteractiveInterestPointFilter w = window[ 0 ];

			assertTrue( w.isLog( Axis.INTENSITY ) );
			assertEquals( "glog10 intensity (c = 16)", w.getAxisLabel( Axis.INTENSITY ) );
			assertTrue( w.isLog( Axis.RESPONSE ) );
			assertEquals( "log10 |response|", w.getAxisLabel( Axis.RESPONSE ) );

			// all 8 points are binned, the two zeros in the first bin
			final double[] counts = w.getHistogramCounts( Axis.INTENSITY );
			assertEquals( 8.0, Arrays.stream( counts ).sum(), 0.0 );
			assertEquals( 2.0, counts[ 0 ], 0.0 );
			assertEquals( "8 of 8 in range", w.getCountText( Axis.INTENSITY ) );

			// a lower bound of 1 cuts exactly the zeros
			SwingUtilities.invokeAndWait( () -> w.setRange( Axis.INTENSITY, 1, Double.NaN ) );
			assertEquals( "intensity=1..", w.getFilter().toString() );
			assertEquals( "6 of 8 in range", w.getCountText( Axis.INTENSITY ) );
			assertTrue( w.getSummaryText().startsWith( "6 of 8 points pass" ), w.getSummaryText() );

			SwingUtilities.invokeAndWait( () -> w.dispose() );
		}
		finally
		{
			System.arraycopy( oldLog, 0, InteractiveInterestPointFilter.defaultLog, 0, oldLog.length );
			Filter_Detections.defaultBaseLabel = oldBase;
			Filter_Detections.defaultResponse = oldR;
			Filter_Detections.defaultIntensity = oldI;
			Filter_Detections.defaultSigma = oldS;
		}
	}

	@Test
	public void testRound3()
	{
		assertEquals( 0.00432, InteractiveInterestPointFilter.round3( 0.0043217 ), 0.0 );
		assertEquals( 1230, InteractiveInterestPointFilter.round3( 1234.5 ), 0.0 );
		assertEquals( 2.0, InteractiveInterestPointFilter.round3( 2.0 ), 0.0 );
		assertEquals( 0.0, InteractiveInterestPointFilter.round3( 0.0 ), 0.0 );
		assertTrue( Double.isNaN( InteractiveInterestPointFilter.round3( Double.NaN ) ) );
		assertEquals( "0.00432", InterestPointFilter.plain( InteractiveInterestPointFilter.round3( 0.0043217 ) ) );
	}

	@Test
	public void testAcceptKnown()
	{
		final InterestPoint unknown = new InterestPoint( 7, new double[] { 0, 0, 0 } );
		final InterestPoint known = new InterestPoint( 8, new double[] { 0, 0, 0 }, 0.2f, 2f, 150f );

		final InterestPointFilter bySigma = InterestPointFilter.of( Axis.SIGMA, 1, 3 );
		final InterestPointFilter none = InterestPointFilter.fromString( "" );

		assertThrows( IllegalArgumentException.class, () -> bySigma.accept( unknown ) );
		assertFalse( bySigma.acceptKnown( unknown ) );
		assertTrue( bySigma.acceptKnown( known ) );
		assertTrue( bySigma.accept( known ) );
		assertTrue( none.acceptKnown( unknown ) );
		assertFalse( InterestPointFilter.of( Axis.SIGMA, 3, Double.NaN ).acceptKnown( known ) );
	}

	@Test
	public void testViewsWithLabel()
	{
		final SpimData2 spimData = SimulateUtil.setUp();
		spimData.setBasePathURI( tempDir.toUri() );

		final List< ViewId > all = new ArrayList<>( spimData.getSequenceDescription().getViewDescriptions().keySet() );
		assertTrue( all.size() >= 3 );

		// the label in the first two views only
		final HashMap< ViewId, List< InterestPoint > > map = new HashMap<>();
		map.put( all.get( 0 ), points() );
		map.put( all.get( 1 ), points() );
		InterestPointTools.addInterestPoints( spimData, "beads", map, "test" );

		final List< ViewId > with = InteractiveInterestPointFilter.viewsWithLabel( spimData, "beads", all );
		assertEquals( 2, with.size() );
		assertTrue( with.contains( all.get( 0 ) ) && with.contains( all.get( 1 ) ) );
		assertTrue( with.get( 0 ).compareTo( with.get( 1 ) ) < 0 );

		// a subset, an unknown label, an unknown view
		assertEquals( 1, InteractiveInterestPointFilter.viewsWithLabel( spimData, "beads", Arrays.asList( all.get( 1 ), all.get( 2 ) ) ).size() );
		assertEquals( 0, InteractiveInterestPointFilter.viewsWithLabel( spimData, "nuclei", all ).size() );
		assertEquals( 0, InteractiveInterestPointFilter.viewsWithLabel( spimData, "beads", Arrays.asList( new ViewId( 99, 99 ) ) ).size() );
	}

	@Test
	public void testRememberedDefaults()
	{
		final InterestPointFilter filter = new InterestPointFilter( 0.004, Double.NaN, Double.NaN, 500, 1.5, 3 );

		assertEquals( "0.004", Filter_Detections.rangeString( filter, Axis.RESPONSE ) );
		assertEquals( "0-500", Filter_Detections.rangeString( filter, Axis.INTENSITY ) );
		assertEquals( "1.5-3", Filter_Detections.rangeString( filter, Axis.SIGMA ) );
		assertEquals( "", Filter_Detections.rangeString( InterestPointFilter.fromString( "" ), Axis.SIGMA ) );

		// what the dialog parses back is the same filter (the open lower bound of the intensity becomes 0)
		final double[] r = InterestPointFilter.parseRange( Filter_Detections.rangeString( filter, Axis.RESPONSE ) );
		final double[] i = InterestPointFilter.parseRange( Filter_Detections.rangeString( filter, Axis.INTENSITY ) );
		final double[] s = InterestPointFilter.parseRange( Filter_Detections.rangeString( filter, Axis.SIGMA ) );
		assertEquals( new InterestPointFilter( 0.004, Double.NaN, 0, 500, 1.5, 3 ), new InterestPointFilter( r[ 0 ], r[ 1 ], i[ 0 ], i[ 1 ], s[ 0 ], s[ 1 ] ) );

		final String oldBase = Filter_Detections.defaultBaseLabel, oldR = Filter_Detections.defaultResponse, oldI = Filter_Detections.defaultIntensity, oldS = Filter_Detections.defaultSigma;

		try
		{
			Filter_Detections.remember( "beads", filter );
			assertEquals( "beads", Filter_Detections.defaultBaseLabel );
			assertEquals( "0.004", Filter_Detections.defaultResponse );
			assertEquals( "0-500", Filter_Detections.defaultIntensity );
			assertEquals( "1.5-3", Filter_Detections.defaultSigma );

			final String[] labels = new String[] { "beads" + InterestPointTools.warningLabel + "1/2 Views!)", "nuclei", "beads_r0.004" };
			assertEquals( 0, Filter_Detections.defaultLabelIndex( labels, "beads" ) );
			assertEquals( 2, Filter_Detections.defaultLabelIndex( labels, "beads_r0.004" ) );
			assertEquals( -1, Filter_Detections.defaultLabelIndex( labels, "missing" ) );
			assertEquals( -1, Filter_Detections.defaultLabelIndex( labels, null ) );
		}
		finally
		{
			Filter_Detections.defaultBaseLabel = oldBase;
			Filter_Detections.defaultResponse = oldR;
			Filter_Detections.defaultIntensity = oldI;
			Filter_Detections.defaultSigma = oldS;
		}
	}
}
