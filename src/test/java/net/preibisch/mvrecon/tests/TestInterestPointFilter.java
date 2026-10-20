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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter.Axis;

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
