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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.fiji.plugin.Filter_Detections;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter.Axis;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter.Limit;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.VirtualFilteredInterestPointsN5;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;

/**
 * The dialog-free parts of the range dialog (Filter_Detections): the combinations of the entries of its fields, the
 * threshold ramp that prefills the response field, and the detection threshold read from a label
 */
public class TestFilterDetections
{
	@TempDir
	Path tempDir;

	@Test
	public void testFiltersFor()
	{
		// one entry per field: one filter with all three ranges
		final List< InterestPointFilter > one = Filter_Detections.filtersFor( "0.004", "", "1.5-3" );
		assertEquals( 1, one.size() );
		assertEquals( new InterestPointFilter( 0.004, Double.NaN, Double.NaN, Double.NaN, 1.5, 3 ), one.get( 0 ) );

		// several entries: one filter per combination, the response entries sorted, the other fields fixed
		final List< InterestPointFilter > ramp = Filter_Detections.filtersFor( "0.016, 0.008", "", "1-2" );
		assertEquals( 2, ramp.size() );
		assertEquals( new InterestPointFilter( 0.008, Double.NaN, Double.NaN, Double.NaN, 1, 2 ), ramp.get( 0 ) );
		assertEquals( new InterestPointFilter( 0.016, Double.NaN, Double.NaN, Double.NaN, 1, 2 ), ramp.get( 1 ) );
		assertEquals( "_r0.008_s1-2", ramp.get( 0 ).suffix() );

		final List< InterestPointFilter > grid = Filter_Detections.filtersFor( "0.008, 0.016", "100, 200", "1-2, 2-4" );
		assertEquals( 8, grid.size() );
		assertEquals( "_r0.008_i100_s1-2", grid.get( 0 ).suffix() );
		assertEquals( "_r0.016_i200_s2-4", grid.get( 7 ).suffix() );

		// all empty = nothing, a malformed entry = null
		assertTrue( Filter_Detections.filtersFor( "", " ", null ).isEmpty() );
		assertNull( Filter_Detections.filtersFor( "0.004, abc", "", "" ) );
		assertNull( Filter_Detections.filtersFor( "", "", "3-1" ) );
	}

	@Test
	public void testRampEntries()
	{
		assertEquals( "0.008, 0.016, 0.032, 0.064", Filter_Detections.rampEntries( 0.004, 2, 4 ) );
		assertEquals( "0.0108, 0.0162, 0.0243", Filter_Detections.rampEntries( 0.0072, 1.5, 3 ) );
		assertEquals( "0.1", Filter_Detections.rampEntries( 0.1, 1.0001, 3 ) ); // steps that round onto each other collapse
		assertEquals( "0.008", Filter_Detections.rampEntries( 0.004, 2, 1 ) );

		// the ramp goes on until it reaches minEnd: a low threshold gets more steps, a high one its steps
		assertEquals( "0.001, 0.002, 0.004, 0.008, 0.016", Filter_Detections.rampEntries( 0.0005, 2, 4, 0.01 ) );
		assertEquals( "0.0002, 0.0004, 0.0008, 0.0016, 0.0032, 0.0064, 0.0128", Filter_Detections.rampEntries( 0.0001, 2, 4, 0.01 ) );
		assertEquals( "0.008, 0.016, 0.032, 0.064", Filter_Detections.rampEntries( 0.004, 2, 4, 0.01 ) );
		assertEquals( "0.04, 0.08, 0.16, 0.32", Filter_Detections.rampEntries( 0.02, 2, 4, 0.01 ) );
		assertEquals( 0.1, Filter_Detections.rampMinEnd, 0.0 );
		assertEquals( 6, Filter_Detections.rampSteps );
		assertEquals( "0.008, 0.016, 0.032, 0.064, 0.128, 0.256", Filter_Detections.rampEntries( 0.004, Filter_Detections.rampFactor, Filter_Detections.rampSteps, Filter_Detections.rampMinEnd ) );
		assertEquals( "0.00002, 0.00004, 0.00008, 0.00016, 0.00032, 0.00064, 0.00128, 0.00256, 0.00512, 0.0102, 0.0205, 0.041, 0.0819, 0.164", Filter_Detections.rampEntries( 0.00001, Filter_Detections.rampFactor, Filter_Detections.rampSteps, Filter_Detections.rampMinEnd ) );
		assertEquals( Filter_Detections.rampMaxSteps, Filter_Detections.rampEntries( 1e-15, 2, 4, 0.01 ).split( ", " ).length ); // capped
		assertEquals( 0.00432, Filter_Detections.round3( 0.0043217 ), 0.0 );
		assertEquals( 1230, Filter_Detections.round3( 1234.5 ), 0.0 );

		// what the field parses to
		final List< InterestPointFilter > filters = Filter_Detections.filtersFor( Filter_Detections.rampEntries( 0.004, 2, 4 ), "", "" );
		assertEquals( 4, filters.size() );
		assertEquals( InterestPointFilter.of( Axis.RESPONSE, 0.008, Double.NaN ), filters.get( 0 ) );
		assertEquals( InterestPointFilter.of( Axis.RESPONSE, 0.064, Double.NaN ), filters.get( 3 ) );
	}

	@Test
	public void testParseThreshold()
	{
		assertEquals( 0.004, Filter_Detections.parseThreshold( "DOG s=1.8 t=0.004 min=false max=true downsampleX=2 downsampleY=2 downsampleZ=1 anisotropy=2.55 minIntensity=NaN maxIntensity=NaN" ), 0.0 );
		assertEquals( 0.008, Filter_Detections.parseThreshold( "DOG-SS s=1.6 steps=4 octaves=-1 finestLevel=true t=0.008 min=false max=true downsampleX=2 downsampleY=2 downsampleZ=1 anisotropy=1.0 minIntensity=0.0 maxIntensity=1137.0" ), 0.0 );
		assertEquals( 1e-4, Filter_Detections.parseThreshold( "DOG s=1.8 t=1.0E-4 min=false" ), 0.0 );
		assertTrue( Double.isNaN( Filter_Detections.parseThreshold( "filtered from 'beads': |response| >= 0.004" ) ) );
		assertTrue( Double.isNaN( Filter_Detections.parseThreshold( "no parameters reported." ) ) );
		assertTrue( Double.isNaN( Filter_Detections.parseThreshold( "DOG s=1.8 t=abc" ) ) );
		assertTrue( Double.isNaN( Filter_Detections.parseThreshold( null ) ) );
		// "t=" must be a whole key (not the end of another one)
		assertTrue( Double.isNaN( Filter_Detections.parseThreshold( "DOG start=5 min=false" ) ) );
	}

	/**
	 * The ramp of a detected label as the dialog would create it: the threshold comes from the parameters of the label, the
	 * labels are filtered labels with decreasing counts, a ramp label passes its lower response bound on as the threshold, the
	 * existing labels block a second virtual ramp
	 */
	@Test
	public void testRamp()
	{
		final SpimData2 spimData = SimulateUtil.setUp();
		spimData.setBasePathURI( tempDir.toUri() );

		final List< ViewId > views = new ArrayList<>( spimData.getSequenceDescription().getViewDescriptions().keySet() );
		Collections.sort( views );
		final HashMap< ViewId, List< InterestPoint > > map = new HashMap<>();
		int seed = 0;

		for ( final ViewId viewId : views )
			map.put( viewId, TestInterestPointStorage.points( 200 + 10 * seed, 700 + seed++ ) );

		InterestPointTools.addInterestPoints( spimData, "beads", map, "DOG s=1.8 t=0.1 min=false max=true downsampleX=2 downsampleY=2 downsampleZ=1 anisotropy=2.5 minIntensity=NaN maxIntensity=NaN" );

		assertEquals( 0.1, Filter_Detections.thresholdOf( spimData, views, "beads" ), 0.0 );
		assertTrue( Double.isNaN( Filter_Detections.thresholdOf( spimData, views, "missing" ) ) );

		// the preset of the response field and the labels it gives
		final String preset = Filter_Detections.rampEntries( 0.1, 2, 3 );
		assertEquals( "0.2, 0.4, 0.8", preset );

		final List< InterestPointFilter > filters = Filter_Detections.filtersFor( preset, "", "" );

		for ( final InterestPointFilter filter : filters )
		{
			assertTrue( Filter_Detections.checkNewLabel( spimData, views, "beads", "beads" + filter.suffix(), false ) );
			assertTrue( Filter_Detections.create( spimData, views, "beads", "beads" + filter.suffix(), filter, false ) );
		}

		for ( final ViewId viewId : views )
		{
			int last = Integer.MAX_VALUE;

			for ( final String label : new String[] { "beads_r0.2", "beads_r0.4", "beads_r0.8" } )
			{
				final InterestPoints ips = spimData.getViewInterestPoints().getViewInterestPointLists( viewId ).getInterestPointList( label );
				assertTrue( ips instanceof VirtualFilteredInterestPointsN5, label );
				assertEquals( "beads", ( (VirtualFilteredInterestPointsN5)ips ).getBaseLabel() );

				final int n = ips.getInterestPointsCopy().size();
				assertTrue( n < last && n > 0, label + ": " + n );
				last = n;

				// the same points as the filter gives on the base
				assertEquals( ( (VirtualFilteredInterestPointsN5)ips ).getFilter().filter( map.get( viewId ) ).size(), n );
			}
		}

		// a ramp label passes its own lower response bound on as the threshold of a ramp on top of it
		assertEquals( 0.4, Filter_Detections.thresholdOf( spimData, views, "beads_r0.4" ), 0.0 );

		// the labels exist now and are overwritten by a second ramp (as a re-run of the detection does), their old records replaced
		assertTrue( Filter_Detections.checkNewLabel( spimData, views, "beads", "beads_r0.2", false ) );
		assertTrue( Filter_Detections.checkNewLabel( spimData, views, "beads", "beads_r0.2", true ) );

		for ( final InterestPointFilter filter : filters )
			assertTrue( Filter_Detections.create( spimData, views, "beads", "beads" + filter.suffix(), filter, false ) );

		assertEquals( 3, spimData.getActionHistory().getRecords().stream().filter( r -> "filter-interestpoints".equals( r.getActionId() ) ).count() );

		// what cannot be: a filtered label of a ramp label named like the label the ramp label is filtered from
		assertFalse( Filter_Detections.checkNewLabel( spimData, views, "beads_r0.2", "beads", false ) );
		assertThrows( IllegalArgumentException.class, () -> Filter_Detections.create( spimData, views, "beads_r0.2", "beads", filters.get( 0 ), false ) );
		assertTrue( Filter_Detections.checkNewLabel( spimData, views, "beads_r0.2", "beads", true ) );
	}

	/**
	 * A label stored before the attributes existed (NaN everywhere) has a threshold for the ramp but nothing to filter by:
	 * the dialogs refuse it before anything is created, create throws and leaves nothing behind
	 */
	@Test
	public void testMissingAttributes()
	{
		final SpimData2 spimData = SimulateUtil.setUp();
		spimData.setBasePathURI( tempDir.toUri() );

		final List< ViewId > views = new ArrayList<>( spimData.getSequenceDescription().getViewDescriptions().keySet() );
		Collections.sort( views );
		final HashMap< ViewId, List< InterestPoint > > old = new HashMap<>();
		final HashMap< ViewId, List< InterestPoint > > fresh = new HashMap<>();

		for ( final ViewId viewId : views )
		{
			final ArrayList< InterestPoint > points = new ArrayList<>();

			for ( int i = 0; i < 50; ++i )
				points.add( new InterestPoint( i, new double[] { i, 2 * i, 3 * i } ) );

			old.put( viewId, points );
			fresh.put( viewId, TestInterestPointStorage.points( 50, 123 ) );
		}

		InterestPointTools.addInterestPoints( spimData, "old", old, "DOG s=1.8 t=0.1 min=false max=true" );
		InterestPointTools.addInterestPoints( spimData, "fresh", fresh, "DOG s=1.8 t=0.1 min=false max=true" );

		// the threshold alone would offer the ramp
		assertEquals( 0.1, Filter_Detections.thresholdOf( spimData, views, "old" ), 0.0 );

		// what a filter needs
		final List< InterestPointFilter > ramp = Filter_Detections.filtersFor( Filter_Detections.rampEntries( 0.1, 2, 2 ), "", "" );
		assertEquals( EnumSet.of( Axis.RESPONSE ), ramp.get( 0 ).axes() );
		assertEquals( EnumSet.of( Axis.INTENSITY, Axis.SIGMA ), InterestPointFilter.of( Axis.INTENSITY, 10, Double.NaN ).withLimit( Axis.SIGMA, Limit.HIGHEST, 5 ).axes() );
		assertTrue( InterestPointFilter.fromString( "" ).axes().isEmpty() );

		// what the points lack
		assertEquals( EnumSet.of( Axis.RESPONSE ), ramp.get( 0 ).missingAttributes( old.get( views.get( 0 ) ) ) );
		assertTrue( ramp.get( 0 ).missingAttributes( fresh.get( views.get( 0 ) ) ).isEmpty() );
		assertEquals( EnumSet.of( Axis.RESPONSE ), InterestPointTools.missingAttributes( spimData, views, "old", EnumSet.of( Axis.RESPONSE ) ) );
		assertEquals( EnumSet.allOf( Axis.class ), InterestPointTools.missingAttributes( spimData, views, "old", EnumSet.allOf( Axis.class ) ) );
		assertTrue( InterestPointTools.missingAttributes( spimData, views, "fresh", EnumSet.of( Axis.RESPONSE ) ).isEmpty() );
		assertTrue( InterestPointTools.missingAttributes( spimData, views, "missing", EnumSet.allOf( Axis.class ) ).isEmpty() );

		assertFalse( Filter_Detections.checkAttributes( spimData, views, "old", ramp ) );
		assertTrue( Filter_Detections.checkAttributes( spimData, views, "fresh", ramp ) );
		// a label that does not exist yet (a detection about to run) passes
		assertTrue( Filter_Detections.checkAttributes( spimData, views, "nuclei", ramp ) );

		// nothing is created, as a virtual label or as a copy
		assertThrows( IllegalArgumentException.class, () -> Filter_Detections.create( spimData, views, "old", "old_r0.2", ramp.get( 0 ), false ) );
		assertThrows( IllegalArgumentException.class, () -> Filter_Detections.create( spimData, views, "old", "old_r0.2", ramp.get( 0 ), true ) );
		assertTrue( InterestPointTools.viewsWithLabel( spimData, "old_r0.2", views ).isEmpty() );
		assertTrue( Filter_Detections.create( spimData, views, "fresh", "fresh_r0.2", ramp.get( 0 ), false ) );
	}

	/**
	 * The only overwrite that cannot work: a filtered label that would filter itself, i.e. its name is the base or one of
	 * the labels the base is filtered from
	 */
	@Test
	public void testWouldCycle()
	{
		final SpimData2 spimData = SimulateUtil.setUp();
		spimData.setBasePathURI( tempDir.toUri() );

		final List< ViewId > views = new ArrayList<>( spimData.getSequenceDescription().getViewDescriptions().keySet() );
		final HashMap< ViewId, List< InterestPoint > > map = new HashMap<>();

		for ( final ViewId viewId : views )
			map.put( viewId, TestInterestPointStorage.points( 100, 900 ) );

		InterestPointTools.addInterestPoints( spimData, "beads", map, "DOG s=1.8 t=0.1" );
		assertTrue( InterestPointTools.addFilteredInterestPoints( spimData, "beads_r0.5", "beads", InterestPointFilter.of( Axis.RESPONSE, 0.5, Double.NaN ), views ) );
		assertTrue( InterestPointTools.addFilteredInterestPoints( spimData, "beads_r0.5_s2-4", "beads_r0.5", InterestPointFilter.of( Axis.SIGMA, 2, 4 ), views ) );

		final ViewInterestPointLists vipl = spimData.getViewInterestPoints().getViewInterestPointLists( views.get( 0 ) );

		// the chain beads_r0.5_s2-4 -> beads_r0.5 -> beads
		assertTrue( InterestPointTools.wouldCycle( vipl, "beads_r0.5_s2-4", "beads_r0.5_s2-4" ) );
		assertTrue( InterestPointTools.wouldCycle( vipl, "beads_r0.5", "beads_r0.5_s2-4" ) );
		assertTrue( InterestPointTools.wouldCycle( vipl, "beads", "beads_r0.5_s2-4" ) );
		assertTrue( InterestPointTools.wouldCycle( vipl, "beads", "beads_r0.5" ) );
		assertTrue( InterestPointTools.wouldCycle( vipl, "beads", "beads" ) );

		// downwards or sideways is fine: a descendant or an unrelated label can be redefined
		assertFalse( InterestPointTools.wouldCycle( vipl, "beads_r0.5_s2-4", "beads" ) );
		assertFalse( InterestPointTools.wouldCycle( vipl, "beads_r0.5", "beads" ) );
		assertFalse( InterestPointTools.wouldCycle( vipl, "other", "beads_r0.5_s2-4" ) );

		// a base that does not exist yet (a detection about to run) ends the chain
		assertTrue( InterestPointTools.wouldCycle( vipl, "nuclei", "nuclei" ) );
		assertFalse( InterestPointTools.wouldCycle( vipl, "nuclei_r0.01", "nuclei" ) );
		assertFalse( InterestPointTools.wouldCycle( null, "x", "y" ) );

		assertEquals( views.size(), InterestPointTools.cyclingViews( spimData, views, "beads", "beads_r0.5" ).size() );
		assertEquals( 0, InterestPointTools.cyclingViews( spimData, views, "beads_r0.5", "beads" ).size() );
	}
}
