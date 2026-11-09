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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint.InterestPointTableModel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint.InterestPointTableModel.LabelAttributes;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.VirtualFilteredInterestPoints;

/**
 * The "Attributes" column of the interest point explorer: which of DoG response, intensity and sigma the points of a
 * label store in the selected views (an old label stores none or some, a label detected again for some views differs
 * between them), and after a "+" the per-point arrays (embeddings) of the label with whether every selected view has one
 * row per point. The lists of the attribute tests are in memory only (never saved), the base path is a placeholder; the
 * array test writes a store.
 */
public class TestInterestPointTableModel
{
	@TempDir
	Path tmp;

	static final URI base = URI.create( "file:///nonexistent/dataset/" );
	static final ViewId v0 = new ViewId( 0, 0 ), v1 = new ViewId( 0, 1 );
	static final List< ViewId > both = Arrays.asList( v0, v1 );

	static InterestPoints list( final ViewId v, final String label, final float response, final float sigma, final float intensity, final int n )
	{
		final ArrayList< InterestPoint > points = new ArrayList<>();

		for ( int i = 0; i < n; ++i )
			points.add( new InterestPoint( i, new double[] { i, 2 * i, 3 * i }, response, sigma, intensity ) );

		final InterestPoints ips = InterestPoints.newInstance( base, v, label );
		ips.setInterestPoints( points );
		ips.setCorrespondingInterestPoints( new ArrayList<>() );

		return ips;
	}

	static ViewInterestPoints dataset()
	{
		final ViewInterestPointLists l0 = new ViewInterestPointLists( v0.getTimePointId(), v0.getViewSetupId() );
		final ViewInterestPointLists l1 = new ViewInterestPointLists( v1.getTimePointId(), v1.getViewSetupId() );

		final float nan = Float.NaN;

		// a current label, an old one, one with the intensity only (9.0.x), one with the response and the sigma
		for ( final ViewInterestPointLists l : Arrays.asList( l0, l1 ) )
		{
			final ViewId v = new ViewId( l.getTimePointId(), l.getViewSetupId() );
			l.addInterestPointList( "new", list( v, "new", 0.01f, 2f, 100f, 10 ) );
			l.addInterestPointList( "old", list( v, "old", nan, nan, nan, 10 ) );
			l.addInterestPointList( "intensity", list( v, "intensity", nan, nan, 100f, 10 ) );
			l.addInterestPointList( "nointensity", list( v, "nointensity", 0.01f, 2f, nan, 10 ) );
			l.addInterestPointList( "empty", list( v, "empty", 0.01f, 2f, 100f, 0 ) );
		}

		// detected again for the first view only
		l0.addInterestPointList( "mixed", list( v0, "mixed", 0.01f, 2f, 100f, 10 ) );
		l1.addInterestPointList( "mixed", list( v1, "mixed", nan, nan, nan, 10 ) );

		// one point of many without an attribute makes the view lack it
		final InterestPoints onePoint = list( v0, "onepoint", 0.01f, 2f, 100f, 10 );
		final ArrayList< InterestPoint > points = new ArrayList<>( onePoint.getInterestPointsCopy().values() );
		points.add( new InterestPoint( 99, new double[] { 1, 2, 3 }, 0.01f, nan, 100f ) );
		onePoint.setInterestPoints( points );
		l0.addInterestPointList( "onepoint", onePoint );

		// only in the second view
		l1.addInterestPointList( "second", list( v1, "second", 0.01f, 2f, 100f, 10 ) );

		return new ViewInterestPoints( Arrays.asList( l0, l1 ) );
	}

	@Test
	public void testAttributes()
	{
		final ViewInterestPoints vip = dataset();

		final LabelAttributes current = InterestPointTableModel.computeAttributes( vip, both, "new" );
		assertEquals( "resp, int, sigma", current.text );
		assertTrue( current.complete );

		final LabelAttributes old = InterestPointTableModel.computeAttributes( vip, both, "old" );
		assertEquals( "none (old)", old.text );
		assertFalse( old.complete );
		assertTrue( old.tooltip.startsWith( "Old label" ) );

		final LabelAttributes intensity = InterestPointTableModel.computeAttributes( vip, both, "intensity" );
		assertEquals( "int only", intensity.text );
		assertFalse( intensity.complete );
		assertTrue( intensity.tooltip.contains( "store no response, sigma" ), intensity.tooltip );

		final LabelAttributes noIntensity = InterestPointTableModel.computeAttributes( vip, both, "nointensity" );
		assertEquals( "resp, sigma only", noIntensity.text );
		assertFalse( noIntensity.complete );

		// no points: nothing to say
		final LabelAttributes empty = InterestPointTableModel.computeAttributes( vip, both, "empty" );
		assertEquals( "", empty.text );
		assertNull( empty.tooltip );
		assertTrue( empty.complete );

		final LabelAttributes mixed = InterestPointTableModel.computeAttributes( vip, both, "mixed" );
		assertEquals( "mixed", mixed.text );
		assertFalse( mixed.complete );
		assertTrue( mixed.tooltip.contains( "the response is stored in 1 of 2 views, the intensity in 1 of 2, the sigma in 1 of 2" ), mixed.tooltip );

		// the same label for one view only is uniform
		assertEquals( "resp, int, sigma", InterestPointTableModel.computeAttributes( vip, Arrays.asList( v0 ), "mixed" ).text );
		assertEquals( "none (old)", InterestPointTableModel.computeAttributes( vip, Arrays.asList( v1 ), "mixed" ).text );

		// one point without the sigma: the view lacks it
		final LabelAttributes onePoint = InterestPointTableModel.computeAttributes( vip, both, "onepoint" );
		assertEquals( "resp, int only", onePoint.text );
		assertFalse( onePoint.complete );

		// views without the label are skipped
		assertEquals( "resp, int, sigma", InterestPointTableModel.computeAttributes( vip, both, "second" ).text );
	}

	@Test
	public void testArrays()
	{
		final URI baseURI = tmp.toUri();
		final String label = "beads";
		TestInterestPointsZarr.saveAll( InterestPointsZarrStore.get( baseURI ), baseURI, 2, label );

		final ViewInterestPointLists l0 = new ViewInterestPointLists( 0, 0 ), l1 = new ViewInterestPointLists( 0, 1 );
		l0.addInterestPointList( label, TestInterestPointsZarr.list( baseURI, 0, label ) );
		l1.addInterestPointList( label, TestInterestPointsZarr.list( baseURI, 1, label ) );
		final ViewInterestPoints vip = new ViewInterestPoints( Arrays.asList( l0, l1 ) );
		final List< InterestPoint > p0 = TestInterestPointsZarr.points( 0, label ), p1 = TestInterestPointsZarr.points( 1, label );
		final int d = TestInterestPointArrays.COLUMNS;

		// no array
		LabelAttributes a = InterestPointTableModel.computeAttributes( vip, both, label );
		assertEquals( "resp, int, sigma", a.text );
		assertTrue( a.complete );

		// rows for the first view only
		TestInterestPointArrays.writeArray( baseURI, label, 0, "embedding", 0, TestInterestPointArrays.ids( p0 ), TestInterestPointArrays.values( p0, false ), d, "fp" );
		a = InterestPointTableModel.computeAttributes( vip, both, label );
		assertEquals( "resp, int, sigma + embedding " + d + "-D (1/2 views)", a.text );
		assertFalse( a.complete );
		assertTrue( a.tooltip.contains( "1 view has no rows" ), a.tooltip );
		assertTrue( a.tooltip.contains( "run pcmatch embed again for label '" + label + "'" ), a.tooltip );
		assertEquals( "resp, int, sigma + embedding " + d + "-D", InterestPointTableModel.computeAttributes( vip, Arrays.asList( v0 ), label ).text );

		// the second view with rows for half of its points: detected again since
		final List< InterestPoint > half = p1.subList( 0, p1.size() / 2 );
		TestInterestPointArrays.writeArray( baseURI, label, 0, "embedding", 1, TestInterestPointArrays.ids( half ), TestInterestPointArrays.values( half, false ), d, "fp" );
		a = InterestPointTableModel.computeAttributes( vip, both, label );
		assertEquals( "resp, int, sigma + embedding " + d + "-D (1/2 views)", a.text );
		assertFalse( a.complete );
		assertTrue( a.tooltip.contains( "1 view has another number of rows than points (e.g. tpId=0 setupId=1: " + half.size() + " rows, " + p1.size() + " points)" ), a.tooltip );

		// both views complete
		TestInterestPointArrays.writeArray( baseURI, label, 0, "embedding", 1, TestInterestPointArrays.ids( p1 ), TestInterestPointArrays.values( p1, false ), d, "fp" );
		a = InterestPointTableModel.computeAttributes( vip, both, label );
		assertEquals( "resp, int, sigma + embedding " + d + "-D", a.text );
		assertTrue( a.complete );
		assertTrue( a.tooltip.contains( "'embedding' (" + d + " values per point, written by TestInterestPointArrays with the model test.pt)" ), a.tooltip );

		// a second array, two arrays listed by name
		TestInterestPointArrays.writeArray( baseURI, label, 0, "another", 0, TestInterestPointArrays.ids( p0 ), new float[ 2 * p0.size() ], 2, "fp" );
		assertEquals( "resp, int, sigma + embedding 'another' 2-D (1/2 views), embedding " + d + "-D", InterestPointTableModel.computeAttributes( vip, both, label ).text );

		// a filtered label shows its base label's arrays, compared with the base's points
		final String filtered = label + "_r0.05";
		final InterestPointFilter filter = InterestPointFilter.fromString( "response=0.05.." );
		for ( final ViewInterestPointLists l : Arrays.asList( l0, l1 ) )
			l.addInterestPointList( filtered, VirtualFilteredInterestPoints.create( baseURI, new ViewId( l.getTimePointId(), l.getViewSetupId() ), filtered, label, filter ) );
		a = InterestPointTableModel.computeAttributes( vip, Arrays.asList( v1 ), filtered );
		assertTrue( l1.getInterestPointList( filtered ).getInterestPointsCopy().size() < p1.size() );
		assertEquals( "resp, int, sigma + embedding 'another' 2-D (0/1 views), embedding " + d + "-D", a.text ); // 'another' has no rows for this view
		assertFalse( a.complete );
		assertTrue( a.tooltip.contains( "'embedding' (" + d + " values per point, written by TestInterestPointArrays with the model test.pt) cover every point" ), a.tooltip );
		assertTrue( a.tooltip.contains( "stored under the base label '" + label + "'" ), a.tooltip );
	}
}
