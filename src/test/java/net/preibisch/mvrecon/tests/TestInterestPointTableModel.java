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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint.InterestPointTableModel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint.InterestPointTableModel.LabelAttributes;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;

/**
 * The "Attributes" column of the interest point explorer: which of DoG response, intensity and sigma the points of a
 * label store in the selected views (an old label stores none or some, a label detected again for some views differs
 * between them). The lists are in memory only (never saved), the base path is a placeholder.
 */
public class TestInterestPointTableModel
{
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
		assertEquals( "response, intensity, sigma", current.text );
		assertTrue( current.complete );

		final LabelAttributes old = InterestPointTableModel.computeAttributes( vip, both, "old" );
		assertEquals( "none (old label)", old.text );
		assertFalse( old.complete );
		assertTrue( old.tooltip.startsWith( "Old label" ) );

		final LabelAttributes intensity = InterestPointTableModel.computeAttributes( vip, both, "intensity" );
		assertEquals( "intensity only", intensity.text );
		assertFalse( intensity.complete );
		assertTrue( intensity.tooltip.contains( "store no response, sigma" ), intensity.tooltip );

		final LabelAttributes noIntensity = InterestPointTableModel.computeAttributes( vip, both, "nointensity" );
		assertEquals( "response, sigma only", noIntensity.text );
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
		assertEquals( "response, intensity, sigma", InterestPointTableModel.computeAttributes( vip, Arrays.asList( v0 ), "mixed" ).text );
		assertEquals( "none (old label)", InterestPointTableModel.computeAttributes( vip, Arrays.asList( v1 ), "mixed" ).text );

		// one point without the sigma: the view lacks it
		final LabelAttributes onePoint = InterestPointTableModel.computeAttributes( vip, both, "onepoint" );
		assertEquals( "response, intensity only", onePoint.text );
		assertFalse( onePoint.complete );

		// views without the label are skipped
		assertEquals( "response, intensity, sigma", InterestPointTableModel.computeAttributes( vip, both, "second" ).text );
	}
}
