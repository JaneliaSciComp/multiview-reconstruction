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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jdom2.Element;
import org.junit.jupiter.api.Test;
import org.scijava.listeners.Listeners;

import bdv.tools.brightness.ConverterSetup;
import bdv.tools.brightness.MinMaxGroup;
import bdv.tools.brightness.SetupAssignments;
import net.imglib2.type.numeric.ARGBType;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.displaysettings.DisplaySettings;
import net.preibisch.mvrecon.fiji.spimdata.displaysettings.DisplaySettings.DisplayRange;
import net.preibisch.mvrecon.fiji.spimdata.displaysettings.XmlIoDisplaySettings;
import net.preibisch.mvrecon.fiji.spimdata.explorer.bdv.BDVDisplaySettings;

/**
 * The brightness settings of BigDataViewer in the XML (DisplaySettings): the XML round trip, and taking them from /
 * setting them in BigDataViewer's brightness groups (BDVDisplaySettings, on a SetupAssignments without a viewer)
 */
public class TestDisplaySettings
{
	@Test
	public void testSetupLists()
	{
		assertEquals( "0-3,7,9-10", DisplaySettings.formatSetups( Arrays.asList( 10, 0, 1, 2, 3, 7, 9 ) ) );
		assertEquals( "5", DisplaySettings.formatSetups( Arrays.asList( 5 ) ) );
		assertEquals( "", DisplaySettings.formatSetups( Arrays.asList() ) );
		assertEquals( Arrays.asList( 0, 1, 2, 3, 7, 9, 10 ), DisplaySettings.parseSetups( "0-3,7,9-10" ) );
		assertEquals( Arrays.asList( 4, 6 ), DisplaySettings.parseSetups( " 4, 6 " ) );
		assertTrue( DisplaySettings.parseSetups( "" ).isEmpty() );
		assertTrue( DisplaySettings.parseSetups( null ).isEmpty() );
		assertThrows( IllegalArgumentException.class, () -> DisplaySettings.parseSetups( "1,x" ) );
		assertThrows( IllegalArgumentException.class, () -> DisplaySettings.parseSetups( "5-2" ) );

		// limits that do not contain the range, or are missing
		assertEquals( new DisplayRange( 10, 500, 0, 500 ), new DisplayRange( 10, 500, 0, 100 ).valid() );
		assertEquals( new DisplayRange( 10, 500, 10, 500 ), new DisplayRange( 500, 10, Double.NaN, Double.NaN ).valid() );
		assertEquals( new DisplayRange( 7, 7, 7, 8 ), new DisplayRange( 7, 7, 7, 7 ).valid() );
	}

	@Test
	public void testXml() throws Exception
	{
		final SpimData2 data = SimulateUtil.setUp();
		final XmlIoSpimData2 io = new XmlIoSpimData2();
		final java.io.File dir = new java.io.File( data.getBasePathURI() );

		// nothing stored: no element, and a dataset without one loads with empty settings
		assertTrue( data.getDisplaySettings().isEmpty() );
		Element root = io.toXml( data, dir );
		assertNull( root.getChild( XmlIoDisplaySettings.DISPLAYSETTINGS_TAG ) );
		assertTrue( io.fromXml( root, dir.toURI() ).getDisplaySettings().isEmpty() );

		final List< Integer > setups = new ArrayList<>( data.getSequenceDescription().getViewSetups().keySet() );
		assertTrue( setups.size() >= 3, "the simulated dataset has several view setups" );

		final DisplayRange a = new DisplayRange( 90.5, 1200, 0, 65535 ), b = new DisplayRange( 0, 255, -10, 4000.25 );
		for ( int i = 0; i < setups.size(); ++i )
			data.getDisplaySettings().set( setups.get( i ), i == 1 ? b : a );

		root = io.toXml( data, dir );
		final Element elem = root.getChild( XmlIoDisplaySettings.DISPLAYSETTINGS_TAG );
		assertEquals( 2, elem.getChildren( XmlIoDisplaySettings.DISPLAYRANGE_TAG ).size(), "one element per distinct range" );
		assertEquals( "90.5", elem.getChildren().get( 0 ).getAttributeValue( "min" ) );
		assertEquals( "65535.0", elem.getChildren().get( 0 ).getAttributeValue( "rangeMax" ) );
		assertEquals( String.valueOf( setups.get( 1 ) ), elem.getChildren().get( 1 ).getAttributeValue( "setups" ) );

		final SpimData2 loaded = io.fromXml( root, dir.toURI() );
		assertEquals( data.getDisplaySettings().getRanges(), loaded.getDisplaySettings().getRanges() );

		// an element without limits is read (they are derived when applied), one without min is refused
		elem.getChildren().get( 1 ).removeAttribute( "rangeMin" );
		assertTrue( Double.isNaN( io.fromXml( root, dir.toURI() ).getDisplaySettings().get( setups.get( 1 ) ).rangeMin() ) );
		elem.getChildren().get( 1 ).removeAttribute( "min" );
		final Element broken = root;
		assertThrows( mpicbg.spim.data.SpimDataException.class, () -> io.fromXml( broken, dir.toURI() ) );
	}

	@Test
	public void testCaptureAndApply()
	{
		// BigDataViewer's start: one group for all setups, range 0..65535
		final ArrayList< ConverterSetup > setups = new ArrayList<>();
		for ( int id = 0; id < 6; ++id )
			setups.add( new Setup( id, 100, 2000 ) );
		setups.add( new Setup( 1000, 5, 6 ) ); // a preview, no view setup of the dataset
		final SetupAssignments assignments = assignments( setups );
		assertEquals( 1, assignments.getMinMaxGroups().size() );

		final List< Integer > viewSetups = Arrays.asList( 0, 1, 2, 3, 4, 5 );
		final DisplaySettings settings = new DisplaySettings();
		settings.set( 77, new DisplayRange( 1, 2, 0, 3 ) ); // a setup BigDataViewer does not show keeps its range

		// the user: channel "odd" in its own group with other limits and another range; '[' ']' change the setup directly
		final MinMaxGroup first = assignments.getMinMaxGroups().get( 0 );
		assignments.removeSetupFromGroup( setups.get( 1 ), first );
		final MinMaxGroup odd = assignments.getMinMaxGroup( setups.get( 1 ) );
		assignments.moveSetupToGroup( setups.get( 3 ), odd );
		assignments.moveSetupToGroup( setups.get( 5 ), odd );
		odd.setRange( 0, 1000 );
		odd.getMaxBoundedValue().setCurrentValue( 300 );
		odd.getMinBoundedValue().setCurrentValue( 20 );
		setups.get( 0 ).setDisplayRange( 100, 3000 );

		assertEquals( 6, BDVDisplaySettings.capture( settings, assignments, viewSetups ) );
		assertEquals( 0, BDVDisplaySettings.capture( settings, assignments, viewSetups ), "nothing changed since" );
		assertEquals( 7, settings.size() );
		assertEquals( new DisplayRange( 100, 3000, 0, 65535 ), settings.get( 0 ) );
		assertEquals( new DisplayRange( 100, 2000, 0, 65535 ), settings.get( 2 ) );
		assertEquals( new DisplayRange( 20, 300, 0, 1000 ), settings.get( 1 ) );
		assertEquals( new DisplayRange( 20, 300, 0, 1000 ), settings.get( 5 ) );
		assertEquals( new DisplayRange( 1, 2, 0, 3 ), settings.get( 77 ) );
		assertNull( settings.get( 1000 ) );
		assertEquals( 4, settings.setupsPerRange().size() );

		// a fresh BigDataViewer (estimated brightness, one group): the stored ranges with their groups and limits
		final ArrayList< ConverterSetup > fresh = new ArrayList<>();
		for ( int id = 0; id < 7; ++id ) // setup 6 is new, nothing stored for it
			fresh.add( new Setup( id, 50, 900 ) );
		final SetupAssignments opened = assignments( fresh );

		assertEquals( 6, BDVDisplaySettings.apply( settings, opened ) );

		for ( final int id : viewSetups )
		{
			assertEquals( settings.get( id ).min(), fresh.get( id ).getDisplayRangeMin(), 0.0, "setup " + id );
			assertEquals( settings.get( id ).max(), fresh.get( id ).getDisplayRangeMax(), 0.0, "setup " + id );
			assertEquals( settings.get( id ), BDVDisplaySettings.range( fresh.get( id ), opened ), "setup " + id );
		}
		assertEquals( 50, fresh.get( 6 ).getDisplayRangeMin(), 0.0, "not stored: as estimated" );
		assertEquals( 900, fresh.get( 6 ).getDisplayRangeMax(), 0.0 );

		// groups: {0}, {2, 4}, {1, 3, 5}, {6}
		assertEquals( 4, opened.getMinMaxGroups().size() );
		assertSame( opened.getMinMaxGroup( fresh.get( 2 ) ), opened.getMinMaxGroup( fresh.get( 4 ) ) );
		assertSame( opened.getMinMaxGroup( fresh.get( 1 ) ), opened.getMinMaxGroup( fresh.get( 3 ) ) );
		assertSame( opened.getMinMaxGroup( fresh.get( 1 ) ), opened.getMinMaxGroup( fresh.get( 5 ) ) );
		assertNotSame( opened.getMinMaxGroup( fresh.get( 0 ) ), opened.getMinMaxGroup( fresh.get( 2 ) ) );
		assertNotSame( opened.getMinMaxGroup( fresh.get( 6 ) ), opened.getMinMaxGroup( fresh.get( 2 ) ) );
		assertNotSame( opened.getMinMaxGroup( fresh.get( 6 ) ), opened.getMinMaxGroup( fresh.get( 1 ) ) );
		assertEquals( 1000, opened.getMinMaxGroup( fresh.get( 3 ) ).getRangeMax(), 0.0 );
		assertEquals( 20, opened.getMinMaxGroup( fresh.get( 3 ) ).getMinBoundedValue().getCurrentValue(), 0.0 );
		assertEquals( 300, opened.getMinMaxGroup( fresh.get( 3 ) ).getMaxBoundedValue().getCurrentValue(), 0.0 );

		// moving the group's slider still moves its setups, and only them
		opened.getMinMaxGroup( fresh.get( 3 ) ).getMaxBoundedValue().setCurrentValue( 400 );
		assertEquals( 400, fresh.get( 5 ).getDisplayRangeMax(), 0.0 );
		assertEquals( 2000, fresh.get( 4 ).getDisplayRangeMax(), 0.0 );

		// applying what is there already moves nothing
		final MinMaxGroup before = opened.getMinMaxGroup( fresh.get( 2 ) );
		BDVDisplaySettings.capture( settings, opened, viewSetups );
		assertEquals( 6, BDVDisplaySettings.apply( settings, opened ) );
		assertSame( before, opened.getMinMaxGroup( fresh.get( 2 ) ) );
		assertEquals( 4, opened.getMinMaxGroups().size() );

		// the common case: one range for all, one group, no regrouping
		final DisplaySettings one = new DisplaySettings();
		for ( int id = 0; id < 7; ++id )
			one.set( id, new DisplayRange( 10, 70000, 0, 65535 ) ); // beyond the limits: they are widened
		final ArrayList< ConverterSetup > all = new ArrayList<>();
		for ( int id = 0; id < 7; ++id )
			all.add( new Setup( id, 50, 900 ) );
		final SetupAssignments single = assignments( all );
		final MinMaxGroup group = single.getMinMaxGroups().get( 0 );
		assertEquals( 7, BDVDisplaySettings.apply( one, single ) );
		assertEquals( 1, single.getMinMaxGroups().size() );
		assertSame( group, single.getMinMaxGroup( all.get( 6 ) ) );
		assertEquals( 70000, group.getRangeMax(), 0.0 );
		assertEquals( 70000, all.get( 3 ).getDisplayRangeMax(), 0.0 );
		assertEquals( 10, all.get( 3 ).getDisplayRangeMin(), 0.0 );
		assertEquals( new DisplayRange( 10, 70000, 0, 70000 ), BDVDisplaySettings.range( all.get( 3 ), single ), "captured with the widened limits" );
		assertFalse( settings.set( 2, BDVDisplaySettings.range( fresh.get( 2 ), opened ) ), "what was applied is what is captured" );
	}

	/** as BigDataViewer's constructor: all setups in the first group */
	static SetupAssignments assignments( final ArrayList< ConverterSetup > setups )
	{
		final SetupAssignments assignments = new SetupAssignments( setups, 0, 65535 );
		final MinMaxGroup group = assignments.getMinMaxGroups().get( 0 );
		for ( final ConverterSetup setup : assignments.getConverterSetups() )
			assignments.moveSetupToGroup( setup, group );
		return assignments;
	}

	static class Setup implements ConverterSetup
	{
		final int id;
		double min, max;
		final Listeners.List< SetupChangeListener > listeners = new Listeners.SynchronizedList<>();

		Setup( final int id, final double min, final double max )
		{
			this.id = id;
			this.min = min;
			this.max = max;
		}

		@Override public Listeners< SetupChangeListener > setupChangeListeners() { return listeners; }
		@Override public int getSetupId() { return id; }
		@Override public void setDisplayRange( final double min, final double max ) { this.min = min; this.max = max; }
		@Override public void setColor( final ARGBType color ) {}
		@Override public boolean supportsColor() { return false; }
		@Override public double getDisplayRangeMin() { return min; }
		@Override public double getDisplayRangeMax() { return max; }
		@Override public ARGBType getColor() { return new ARGBType(); }
	}
}
