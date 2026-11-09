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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;

import org.junit.jupiter.api.Test;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.statistics.RegistrationStatistics;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.statistics.TimeLapseDisplay;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.PairwiseResult;

import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

/**
 * The time-lapse registration chart: a timepoint without a valid pair has no errors (NaN, no longer Double.MAX_VALUE and -1),
 * is a gap in the chart instead of blowing up its axis ("Must be finite"), and is never the suggested reference timepoint.
 */
public class TestRegistrationStatistics
{
	static RegistrationStatistics invalid( final int timepoint )
	{
		return new RegistrationStatistics( timepoint, new ArrayList< Pair< Pair< ViewId, ViewId >, PairwiseResult< InterestPoint > > >() );
	}

	@Test
	public void timepointWithoutValidPairs()
	{
		final RegistrationStatistics rs = invalid( 3 );
		assertFalse( rs.hasValidPairs() );
		assertEquals( 0, rs.getNumValidPairs() );
		for ( final double v : new double[] { rs.getMinError(), rs.getAvgError(), rs.getMaxError(), rs.getMinRatio(), rs.getAvgRatio(), rs.getMaxRatio() } )
			assertTrue( Double.isNaN( v ) );
	}

	@Test
	public void chartDataIsFiniteWithGaps()
	{
		final ArrayList< RegistrationStatistics > data = TimeLapseDisplay.defaultData(); // has timepoints without statistics itself
		final int first = data.get( 0 ).getTimePoint(), last = data.get( data.size() - 1 ).getTimePoint();
		data.add( invalid( last + 1 ) );       // no valid pair
		data.add( data.remove( 0 ) );          // unsorted
		data.add( invalid( last + 3 ) );       // and a timepoint without statistics in between (last + 2)

		final HashSet< Integer > valid = new HashSet<>();
		for ( final RegistrationStatistics rs : data )
			if ( rs.hasValidPairs() )
				valid.add( rs.getTimePoint() );
		assertTrue( valid.size() < last - first ); // the demo data has gaps, they are covered too

		final XYSeriesCollection[] datasets = TimeLapseDisplay.createDatasets( data );
		assertEquals( 3, datasets[ 0 ].getSeriesCount() );
		assertEquals( 3, datasets[ 1 ].getSeriesCount() );
		for ( final XYSeriesCollection dataset : datasets )
			for ( int s = 0; s < 3; ++s )
			{
				final XYSeries series = dataset.getSeries( s );
				assertEquals( last + 3 - first + 1, series.getItemCount(), "one item per timepoint from first to last" );
				for ( int i = 0; i < series.getItemCount(); ++i )
				{
					final int tp = series.getX( i ).intValue();
					assertEquals( first + i, tp );
					if ( !valid.contains( tp ) )
						assertNull( series.getY( i ), "timepoint " + tp + " must be a gap" );
					else
						assertTrue( Double.isFinite( series.getY( i ).doubleValue() ) && Math.abs( series.getY( i ).doubleValue() ) < 1000, "timepoint " + tp );
				}
			}
	}

	@Test
	public void optimalTimepointHasValidPairs()
	{
		final ArrayList< RegistrationStatistics > data = TimeLapseDisplay.defaultData();
		final int withoutInvalid = TimeLapseDisplay.getOptimalTimePoint( data );
		data.add( 0, invalid( 100 ) ); // maxError NaN, minRatio NaN: must not win
		assertEquals( withoutInvalid, TimeLapseDisplay.getOptimalTimePoint( data ) );
		assertNotEquals( 100, withoutInvalid );

		final ArrayList< RegistrationStatistics > none = new ArrayList<>();
		none.add( invalid( 7 ) );
		none.add( invalid( 8 ) );
		assertEquals( 7, TimeLapseDisplay.getOptimalTimePoint( none ) );
		assertEquals( -1, TimeLapseDisplay.getOptimalTimePoint( new ArrayList<>() ) );
	}
}
