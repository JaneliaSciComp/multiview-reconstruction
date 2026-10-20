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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import net.preibisch.mvrecon.fiji.plugin.Limit_Detections;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter.Axis;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter.Limit;

/**
 * The filter of a filtered label (InterestPointFilter): the grammar of the dialog entries, the canonical string of the XML,
 * the label suffixes, the descriptions and what passes
 */
public class TestInterestPointFilter
{
	@Test
	public void testParseRange()
	{
		assertArrayEquals( new double[] { 0.004, Double.NaN }, InterestPointFilter.parseRange( "0.004" ), 0.0 );
		assertArrayEquals( new double[] { 0.004, 0.02 }, InterestPointFilter.parseRange( " 0.004 - 0.02 " ), 0.0 );
		assertArrayEquals( new double[] { 1e-4, 1e-3 }, InterestPointFilter.parseRange( "1e-4-1e-3" ), 0.0 );
		assertArrayEquals( new double[] { 1.5, 3 }, InterestPointFilter.parseRange( "1.5-3" ), 0.0 );
		assertArrayEquals( new double[] { 0, 500 }, InterestPointFilter.parseRange( "0-500" ), 0.0 );

		assertThrows( IllegalArgumentException.class, () -> InterestPointFilter.parseRange( "abc" ) );
		assertThrows( IllegalArgumentException.class, () -> InterestPointFilter.parseRange( "-1" ) );
		assertThrows( IllegalArgumentException.class, () -> InterestPointFilter.parseRange( "3-1.5" ) );
		assertThrows( IllegalArgumentException.class, () -> InterestPointFilter.parseRange( "1-2-3" ) );
		assertThrows( IllegalArgumentException.class, () -> InterestPointFilter.parseRange( "" ) );
	}

	@Test
	public void testParseEntries()
	{
		// one filter per entry, sorted by the lower then the upper bound (unbounded last), duplicates dropped
		final List< InterestPointFilter > filters = InterestPointFilter.parseEntries( "0.01, 0.004-0.02; 0.004", Axis.RESPONSE );

		assertEquals( 3, filters.size() );
		assertEquals( InterestPointFilter.of( Axis.RESPONSE, 0.004, 0.02 ), filters.get( 0 ) );
		assertEquals( InterestPointFilter.of( Axis.RESPONSE, 0.004, Double.NaN ), filters.get( 1 ) );
		assertEquals( InterestPointFilter.of( Axis.RESPONSE, 0.01, Double.NaN ), filters.get( 2 ) );

		assertEquals( 1, InterestPointFilter.parseEntries( "200, 200", Axis.INTENSITY ).size() );
		assertEquals( InterestPointFilter.of( Axis.SIGMA, 1.5, 3 ), InterestPointFilter.parseEntries( "1.5-3", Axis.SIGMA ).get( 0 ) );

		// empty = none
		assertTrue( InterestPointFilter.parseEntries( "", Axis.RESPONSE ).isEmpty() );
		assertTrue( InterestPointFilter.parseEntries( "  ", Axis.RESPONSE ).isEmpty() );
		assertTrue( InterestPointFilter.parseEntries( null, Axis.RESPONSE ).isEmpty() );

		// a malformed entry: null (and a log line)
		assertNull( InterestPointFilter.parseEntries( "0.004, abc", Axis.RESPONSE ) );
		assertNull( InterestPointFilter.parseEntries( "0.02-0.004", Axis.RESPONSE ) );
	}

	@Test
	public void testSuffix()
	{
		assertEquals( "_r0.004", InterestPointFilter.of( Axis.RESPONSE, 0.004, Double.NaN ).suffix() );
		assertEquals( "_r0.004-0.02", InterestPointFilter.of( Axis.RESPONSE, 0.004, 0.02 ).suffix() );
		assertEquals( "_r0.0001", InterestPointFilter.of( Axis.RESPONSE, 1e-4, Double.NaN ).suffix() );
		assertEquals( "_r1", InterestPointFilter.of( Axis.RESPONSE, 1.0, Double.NaN ).suffix() );
		assertEquals( "_i200", InterestPointFilter.of( Axis.INTENSITY, 200, Double.NaN ).suffix() );
		assertEquals( "_i0-500", InterestPointFilter.of( Axis.INTENSITY, 0, 500 ).suffix() );
		assertEquals( "_i0-500", InterestPointFilter.of( Axis.INTENSITY, Double.NaN, 500 ).suffix() );
		assertEquals( "_s1.5-3", InterestPointFilter.of( Axis.SIGMA, 1.5, 3 ).suffix() );
		assertEquals( "_r0.004_s1.5-3", new InterestPointFilter( 0.004, Double.NaN, Double.NaN, Double.NaN, 1.5, 3 ).suffix() );
		assertEquals( "", new InterestPointFilter( Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN ).suffix() );
	}

	@Test
	public void testCanonicalString()
	{
		final InterestPointFilter f = new InterestPointFilter( 0.004, Double.NaN, Double.NaN, 500, 1.5, 3 );

		assertEquals( "response=0.004..;intensity=..500;sigma=1.5..3", f.toString() );
		assertEquals( f, InterestPointFilter.fromString( f.toString() ) );
		assertEquals( f.hashCode(), InterestPointFilter.fromString( f.toString() ).hashCode() );

		assertEquals( "response=0.004..", InterestPointFilter.of( Axis.RESPONSE, 0.004, Double.NaN ).toString() );
		assertEquals( InterestPointFilter.of( Axis.SIGMA, 1.5, 3 ), InterestPointFilter.fromString( "sigma=1.5..3" ) );
		assertEquals( InterestPointFilter.of( Axis.SIGMA, 1.5, 3 ), InterestPointFilter.fromString( " sigma = 1.5 .. 3 " ) );
		assertTrue( InterestPointFilter.fromString( "" ).isEmpty() );
		assertTrue( InterestPointFilter.fromString( null ).isEmpty() );

		assertThrows( IllegalArgumentException.class, () -> InterestPointFilter.fromString( "foo=1..2" ) );
		assertThrows( IllegalArgumentException.class, () -> InterestPointFilter.fromString( "response=1-2" ) );
		assertThrows( IllegalArgumentException.class, () -> InterestPointFilter.fromString( "response=a..b" ) );
		assertThrows( IllegalArgumentException.class, () -> InterestPointFilter.fromString( "response=3..1" ) );
		assertThrows( IllegalArgumentException.class, () -> new InterestPointFilter( -1, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN ) );
	}

	@Test
	public void testDescribe()
	{
		assertEquals( "filtered from 'beads': |response| >= 0.004", InterestPointFilter.of( Axis.RESPONSE, 0.004, Double.NaN ).describe( "beads" ) );
		assertEquals( "filtered from 'beads': intensity <= 500", InterestPointFilter.of( Axis.INTENSITY, Double.NaN, 500 ).describe( "beads" ) );
		assertEquals( "filtered from 'beads': |response| >= 0.004, sigma in [1.5, 3]", new InterestPointFilter( 0.004, Double.NaN, Double.NaN, Double.NaN, 1.5, 3 ).describe( "beads" ) );

		final Map< String, String > p = new InterestPointFilter( 0.004, Double.NaN, Double.NaN, Double.NaN, 1.5, 3 ).describeParameters();
		assertEquals( Arrays.asList( "response", "sigma" ), Arrays.asList( p.keySet().toArray() ) );
		assertEquals( "0.004..", p.get( "response" ) );
		assertEquals( "1.5..3", p.get( "sigma" ) );
	}

	@Test
	public void testAccept()
	{
		final InterestPoint weakMax = new InterestPoint( 0, new double[] { 1, 2, 3 }, 0.003f, 2.0f, 150f );
		final InterestPoint strongMin = new InterestPoint( 1, new double[] { 1, 2, 3 }, -0.01f, 2.5f, 800f );
		final InterestPoint strongMax = new InterestPoint( 2, new double[] { 1, 2, 3 }, 0.05f, 5.0f, 300f );

		// |response|
		final InterestPointFilter response = InterestPointFilter.of( Axis.RESPONSE, 0.004, Double.NaN );
		assertFalse( response.accept( weakMax ) );
		assertTrue( response.accept( strongMin ) );
		assertTrue( response.accept( strongMax ) );
		assertEquals( Arrays.asList( strongMin, strongMax ), response.filter( Arrays.asList( weakMax, strongMin, strongMax ) ) );

		// a band, both bounds inclusive at float precision (0.7 as a float is below 0.7, a bound of 0.7 still keeps it)
		final InterestPointFilter band = InterestPointFilter.of( Axis.RESPONSE, 0.003, 0.01 );
		assertTrue( band.accept( weakMax ) );
		assertTrue( band.accept( strongMin ) );
		assertFalse( band.accept( strongMax ) );
		assertTrue( InterestPointFilter.of( Axis.RESPONSE, 0.7, Double.NaN ).accept( new InterestPoint( 4, new double[] { 1, 2, 3 }, 0.7f, 1, 1 ) ) );
		assertTrue( InterestPointFilter.of( Axis.RESPONSE, Double.NaN, 0.1 ).accept( new InterestPoint( 5, new double[] { 1, 2, 3 }, 0.1f, 1, 1 ) ) );

		assertEquals( Arrays.asList( strongMin ), InterestPointFilter.of( Axis.INTENSITY, 500, Double.NaN ).filter( Arrays.asList( weakMax, strongMin, strongMax ) ) );
		assertEquals( Arrays.asList( weakMax, strongMin ), InterestPointFilter.of( Axis.SIGMA, 1.5, 3 ).filter( Arrays.asList( weakMax, strongMin, strongMax ) ) );

		// combined
		assertEquals( Arrays.asList( strongMin ), new InterestPointFilter( 0.004, Double.NaN, Double.NaN, Double.NaN, 1.5, 3 ).filter( Arrays.asList( weakMax, strongMin, strongMax ) ) );

		// an attribute the filter needs but the point lacks (a label stored before the attributes existed): an error, not a silent drop
		final InterestPoint unknown = new InterestPoint( 3, new double[] { 1, 2, 3 } );
		assertThrows( IllegalArgumentException.class, () -> response.accept( unknown ) );
		assertTrue( new InterestPointFilter( Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN ).accept( unknown ) );
	}

	/**
	 * The count limit: the canonical string, the suffix, the description, the entries of the detection dialog and the choices
	 * of the explorer dialog, and what filter( Collection ) keeps (the n highest / around the median / lowest by one attribute
	 * of the points that pass the ranges, per call = per view)
	 */
	@Test
	public void testLimit()
	{
		final InterestPointFilter highest = InterestPointFilter.of( Axis.RESPONSE, Limit.HIGHEST, 3 );

		assertTrue( highest.hasLimit() );
		assertFalse( highest.isEmpty() );
		assertEquals( 3, highest.limitCount );
		assertEquals( Axis.RESPONSE, highest.limitAxis );
		assertEquals( "limit=response:highest:3", highest.toString() );
		assertEquals( highest, InterestPointFilter.fromString( "limit=response:highest:3" ) );
		assertEquals( "_rh3", highest.suffix() );
		assertEquals( "filtered from 'beads': the 3 highest by |response| per view", highest.describe( "beads" ) );
		assertEquals( "response:highest:3", highest.describeParameters().get( "limit" ) );
		assertEquals( 1, highest.describeParameters().size() );

		final InterestPointFilter combined = InterestPointFilter.of( Axis.RESPONSE, 0.2, Double.NaN ).withLimit( Axis.INTENSITY, Limit.AROUND_MEDIAN, 2 );

		assertEquals( "response=0.2..;limit=intensity:median:2", combined.toString() );
		assertEquals( combined, InterestPointFilter.fromString( combined.toString() ) );
		assertEquals( combined.hashCode(), InterestPointFilter.fromString( combined.toString() ).hashCode() );
		assertEquals( "_r0.2_im2", combined.suffix() );
		assertEquals( "filtered from 'beads': |response| >= 0.2, the 2 around the median by intensity per view", combined.describe( "beads" ) );
		assertEquals( Arrays.asList( "response", "limit" ), Arrays.asList( combined.describeParameters().keySet().toArray() ) );
		assertNotEquals( combined, InterestPointFilter.of( Axis.RESPONSE, 0.2, Double.NaN ) );
		assertNotEquals( highest, InterestPointFilter.of( Axis.RESPONSE, Limit.LOWEST, 3 ) );
		assertNotEquals( highest, InterestPointFilter.of( Axis.RESPONSE, Limit.HIGHEST, 4 ) );
		assertNotEquals( highest, InterestPointFilter.of( Axis.SIGMA, Limit.HIGHEST, 3 ) );
		assertEquals( InterestPointFilter.of( Axis.SIGMA, Limit.LOWEST, 3 ), highest.withLimit( Axis.SIGMA, Limit.LOWEST, 3 ) );
		assertTrue( highest.withLimit( null, null, 0 ).isEmpty() );

		// the detection dialog takes counts only (the n highest by |response|), the explorer dialog has the attribute and the mode
		assertEquals( Arrays.asList( highest, InterestPointFilter.of( Axis.RESPONSE, Limit.HIGHEST, 10 ) ), InterestPointFilter.parseLimitEntries( "3, 10; 3" ) );
		assertEquals( 0, InterestPointFilter.parseLimitEntries( " " ).size() );
		assertNull( InterestPointFilter.parseLimitEntries( "3, median 10" ) );
		assertNull( InterestPointFilter.parseLimitEntries( "0" ) );
		assertEquals( InterestPointFilter.of( Axis.RESPONSE, Limit.HIGHEST, 3000 ), Limit_Detections.filterFor( 3000, 1, 0 ) );
		assertEquals( InterestPointFilter.of( Axis.INTENSITY, Limit.LOWEST, 10 ), Limit_Detections.filterFor( 10, 0, 2 ) );
		assertEquals( InterestPointFilter.of( Axis.SIGMA, Limit.AROUND_MEDIAN, 5 ), Limit_Detections.filterFor( 5, 2, 1 ) );
		assertEquals( "_il10", Limit_Detections.filterFor( 10, 0, 2 ).suffix() );
		assertThrows( IllegalArgumentException.class, () -> Limit_Detections.filterFor( 0, 1, 0 ) );
		assertThrows( IllegalArgumentException.class, () -> InterestPointFilter.fromString( "limit=highest:3" ) );
		assertThrows( IllegalArgumentException.class, () -> InterestPointFilter.fromString( "limit=response:brightest:3" ) );
		assertThrows( IllegalArgumentException.class, () -> InterestPointFilter.fromString( "limit=response:highest:0" ) );
		assertThrows( IllegalArgumentException.class, () -> InterestPointFilter.fromString( "limit=response:highest:x" ) );
		assertThrows( IllegalArgumentException.class, () -> InterestPointFilter.of( Axis.RESPONSE, Limit.HIGHEST, 0 ) );
		assertThrows( IllegalArgumentException.class, () -> new InterestPointFilter( Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, null, Limit.HIGHEST, 3 ) );

		// what is kept: |response| 0.9, 0.1, 0.5, 0.3, 0.7; sigma 2, 1, 4, 3, 8; intensity 10, 50, 30, 20, 40 (ids 0..4); the result in the order of the input
		final List< InterestPoint > points = Arrays.asList( p( 0, 0.9f, 2f, 10f ), p( 1, -0.1f, 1f, 50f ), p( 2, 0.5f, 4f, 30f ), p( 3, -0.3f, 3f, 20f ), p( 4, 0.7f, 8f, 40f ) );

		assertEquals( Arrays.asList( 0, 2, 4 ), ids( highest.filter( points ) ) );
		assertEquals( Arrays.asList( 1, 2, 3 ), ids( InterestPointFilter.of( Axis.RESPONSE, Limit.LOWEST, 3 ).filter( points ) ) );
		assertEquals( Arrays.asList( 2 ), ids( InterestPointFilter.of( Axis.RESPONSE, Limit.AROUND_MEDIAN, 1 ).filter( points ) ) );
		assertEquals( Arrays.asList( 2, 4 ), ids( InterestPointFilter.of( Axis.RESPONSE, Limit.AROUND_MEDIAN, 2 ).filter( points ) ) );
		assertEquals( Arrays.asList( 0, 1, 2, 3, 4 ), ids( InterestPointFilter.of( Axis.RESPONSE, Limit.HIGHEST, 10 ).filter( points ) ) );
		assertEquals( Arrays.asList( 1, 4 ), ids( InterestPointFilter.of( Axis.INTENSITY, Limit.HIGHEST, 2 ).filter( points ) ) );
		assertEquals( Arrays.asList( 0, 3 ), ids( InterestPointFilter.of( Axis.INTENSITY, Limit.LOWEST, 2 ).filter( points ) ) );
		assertEquals( Arrays.asList( 4 ), ids( InterestPointFilter.of( Axis.SIGMA, Limit.HIGHEST, 1 ).filter( points ) ) );
		assertEquals( Arrays.asList( 3 ), ids( InterestPointFilter.of( Axis.SIGMA, Limit.AROUND_MEDIAN, 1 ).filter( points ) ) );

		// after the ranges: |response| >= 0.2 leaves ids 0, 2, 3, 4
		assertEquals( Arrays.asList( 0, 4 ), ids( InterestPointFilter.of( Axis.RESPONSE, 0.2, Double.NaN ).withLimit( Axis.RESPONSE, Limit.HIGHEST, 2 ).filter( points ) ) );
		assertEquals( Arrays.asList( 2, 3 ), ids( InterestPointFilter.of( Axis.RESPONSE, 0.2, Double.NaN ).withLimit( Axis.RESPONSE, Limit.LOWEST, 2 ).filter( points ) ) );
		assertEquals( Arrays.asList( 0, 3 ), ids( InterestPointFilter.of( Axis.RESPONSE, 0.2, Double.NaN ).withLimit( Axis.INTENSITY, Limit.LOWEST, 2 ).filter( points ) ) );
		assertEquals( Arrays.asList( 0, 2, 3, 4 ), ids( InterestPointFilter.of( Axis.RESPONSE, 0.2, Double.NaN ).filter( points ) ) );

		// ties keep the order of the input
		final List< InterestPoint > ties = Arrays.asList( p( 0, 0.5f, 1f, 1f ), p( 1, 0.5f, 1f, 1f ), p( 2, 0.5f, 1f, 1f ) );
		assertEquals( Arrays.asList( 0, 1 ), ids( InterestPointFilter.of( Axis.RESPONSE, Limit.HIGHEST, 2 ).filter( ties ) ) );
		assertEquals( Arrays.asList( 1, 2 ), ids( InterestPointFilter.of( Axis.RESPONSE, Limit.LOWEST, 2 ).filter( ties ) ) );

		assertArrayEquals( new int[] { 0, 3 }, InterestPointFilter.keepRange( 10, Limit.HIGHEST, 3 ) );
		assertArrayEquals( new int[] { 7, 10 }, InterestPointFilter.keepRange( 10, Limit.LOWEST, 3 ) );
		assertArrayEquals( new int[] { 3, 6 }, InterestPointFilter.keepRange( 10, Limit.AROUND_MEDIAN, 3 ) );
		assertArrayEquals( new int[] { 0, 2 }, InterestPointFilter.keepRange( 2, Limit.HIGHEST, 5 ) );
		assertArrayEquals( new int[] { 0, 0 }, InterestPointFilter.keepRange( 0, Limit.LOWEST, 5 ) );

		// a point without the attribute: filter throws, filterKnown drops it; the per-point methods refuse a count limit
		final List< InterestPoint > withUnknown = new ArrayList<>( points );
		withUnknown.add( new InterestPoint( 5, new double[] { 5, 0, 0 } ) );

		assertThrows( IllegalArgumentException.class, () -> highest.filter( withUnknown ) );
		assertEquals( Arrays.asList( 0, 2, 4 ), ids( highest.filterKnown( withUnknown ) ) );

		final List< InterestPoint > noIntensity = new ArrayList<>( points );
		noIntensity.add( new InterestPoint( 6, new double[] { 6, 0, 0 }, 0.95f, 1f, Float.NaN ) );

		assertEquals( Arrays.asList( 0, 4, 6 ), ids( highest.filterKnown( noIntensity ) ) );
		assertEquals( Arrays.asList( 1, 4 ), ids( InterestPointFilter.of( Axis.INTENSITY, Limit.HIGHEST, 2 ).filterKnown( noIntensity ) ) );
		assertThrows( IllegalArgumentException.class, () -> InterestPointFilter.of( Axis.INTENSITY, Limit.HIGHEST, 2 ).filter( noIntensity ) );
		assertThrows( IllegalStateException.class, () -> highest.accept( points.get( 0 ) ) );
		assertThrows( IllegalStateException.class, () -> highest.acceptKnown( points.get( 0 ) ) );
		assertTrue( InterestPointFilter.of( Axis.RESPONSE, 0.2, Double.NaN ).accept( points.get( 0 ) ) );
	}

	protected static InterestPoint p( final int id, final float response, final float sigma, final float intensity )
	{
		return new InterestPoint( id, new double[] { id, 0, 0 }, response, sigma, intensity );
	}

	protected static List< Integer > ids( final List< ? extends InterestPoint > points )
	{
		return points.stream().map( InterestPoint::getId ).collect( Collectors.toList() );
	}

	@Test
	public void testInterestPoint()
	{
		final InterestPoint p = new InterestPoint( 7, new double[] { 1, 2, 3 }, -0.02f, 1.8f, 400f );

		// the attributes are floats and survive a clone and a derived copy, a fresh instance has none
		assertEquals( -0.02f, p.getResponse(), 0.0f );
		assertEquals( -0.02f, p.clone().getResponse(), 0.0f );
		assertEquals( 1.8f, p.clone().getSigma(), 0.0f );
		assertEquals( 400f, p.clone().getIntensity(), 0.0f );

		final InterestPoint derived = p.derive( 9, new double[] { 4, 5, 6 } );
		assertEquals( 9, derived.getId() );
		assertArrayEquals( new double[] { 4, 5, 6 }, derived.getL(), 0.0 );
		assertEquals( -0.02f, derived.getResponse(), 0.0f );
		assertEquals( 1.8f, derived.getSigma(), 0.0f );
		assertEquals( 400f, derived.getIntensity(), 0.0f );

		final InterestPoint fresh = p.newInstance( 9, new double[] { 4, 5, 6 } );
		assertTrue( Double.isNaN( fresh.getResponse() ) );
		assertTrue( Double.isNaN( fresh.getSigma() ) );
		assertTrue( Double.isNaN( fresh.getIntensity() ) );

		// equality is id and position only
		assertEquals( p, new InterestPoint( 7, new double[] { 1, 2, 3 } ) );
	}
}
