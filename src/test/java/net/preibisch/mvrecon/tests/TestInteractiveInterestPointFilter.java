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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

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
 * The non-GUI parts of the interactive filter of the interest point explorer and the defaults it leaves for Filter_Detections
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

		final double[][] linear = InteractiveInterestPointFilter.histogram( sigma, false, 7 );
		assertEquals( 7, linear[ 0 ].length );
		assertEquals( 4.0, Arrays.stream( linear[ 1 ] ).sum(), 0.0 );
		assertEquals( 1.0, linear[ 1 ][ 0 ], 0.0 ); // 1
		assertEquals( 1.0, linear[ 1 ][ 1 ], 0.0 ); // 2
		assertEquals( 1.0, linear[ 1 ][ 3 ], 0.0 ); // 4
		assertEquals( 1.0, linear[ 1 ][ 6 ], 0.0 ); // 8 (the maximum falls into the last bin)
		assertEquals( 1.5, linear[ 0 ][ 0 ], 1e-9 );

		// in log space 1, 2, 4, 8 are equidistant: 3 bins, the maximum in the last, the others at the bin starts
		final double[][] log = InteractiveInterestPointFilter.histogram( sigma, true, 3 );
		assertArrayEquals( new double[] { 1, 1, 2 }, log[ 1 ], 0.0 );
		assertEquals( Math.log10( 1 ) + ( Math.log10( 8 ) - Math.log10( 1 ) ) / 6, log[ 0 ][ 0 ], 1e-9 );

		final double[][] empty = InteractiveInterestPointFilter.histogram( new float[ 0 ], false, 5 );
		assertEquals( 0.0, Arrays.stream( empty[ 1 ] ).sum(), 0.0 );

		// a single value does not divide by zero
		final double[][] single = InteractiveInterestPointFilter.histogram( new float[] { 3f }, true, 5 );
		assertEquals( 1.0, Arrays.stream( single[ 1 ] ).sum(), 0.0 );
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
