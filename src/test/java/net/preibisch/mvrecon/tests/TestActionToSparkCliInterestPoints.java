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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.plugin.Filter_Detections;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.ScaleSpaceGUI;
import net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionRecord;
import net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionToSparkCli;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;

/**
 * The BigStitcher-Spark commands rendered for the interest point records of the scale space and of filtered labels
 */
public class TestActionToSparkCliInterestPoints
{
	protected static String command( final List< String > lines )
	{
		String command = null;

		for ( final String line : lines )
			if ( !line.startsWith( "#" ) )
			{
				assertEquals( null, command, "one command expected" );
				command = line;
			}

		return command;
	}

	@Test
	public void testScaleSpaceDetection()
	{
		final List< ViewId > views = Arrays.asList( new ViewId( 0, 0 ), new ViewId( 0, 1 ) );
		final LinkedHashMap< String, String > params = new LinkedHashMap<>();
		params.put( "label", "beads" );
		params.put( "detectionMethod", "SCALE_SPACE" );
		params.put( "sigma", "1.6" );
		params.put( "threshold", "0.008" );
		params.put( "steps", "4" );
		params.put( "octaves", "-1" );
		params.put( "finestLevel", "true" );
		params.put( "type", "MAX" );
		params.put( "localization", "QUADRATIC" );
		params.put( "downsampleXY", "2" );
		params.put( "downsampleZ", "1" );
		params.put( "anisotropy", "2.5" );

		List< String > lines = ActionToSparkCli.render( new ActionRecord( "detect-interestpoints", 0, ScaleSpaceGUI.class.getName(), params, views, "interestpoints:beads" ), "dataset.xml" );
		String command = command( lines );

		assertTrue( lines.stream().noneMatch( l -> l.startsWith( "#" ) ), "no warnings: " + lines );
		assertTrue( command.startsWith( "./detect-interestpoints -x dataset.xml" ), command );
		assertTrue( command.contains( " -l beads " ) && command.contains( " -s 1.6 " ) && command.contains( " -t 0.008 " ), command );
		assertTrue( command.contains( " --method SCALE_SPACE" ) && command.contains( " --steps 4" ) && command.contains( " --octaves -1" ), command );
		assertTrue( command.contains( " --anisotropy 2.5" ), command );
		assertFalse( command.contains( "finestLevel" ), "the default finest level is not rendered: " + command );

		// the finest level off, no anisotropy (NaN = none is omitted)
		params.put( "finestLevel", "false" );
		params.put( "anisotropy", "NaN" );
		lines = ActionToSparkCli.render( new ActionRecord( "detect-interestpoints", 0, ScaleSpaceGUI.class.getName(), params, views, "interestpoints:beads" ), "dataset.xml" );
		command = command( lines );

		assertTrue( command.contains( " --no-finestLevel" ), command );
		assertFalse( command.contains( "anisotropy" ), command );
	}

	@Test
	public void testFilteredLabel()
	{
		final List< ViewId > views = Arrays.asList( new ViewId( 0, 0 ) );
		final InterestPointFilter filter = InterestPointFilter.fromString( "response=0.004..;sigma=1.5..3;limit=intensity:highest:3000" );

		final LinkedHashMap< String, String > params = new LinkedHashMap<>();
		params.put( "label", "beads" + filter.suffix() );
		params.put( "base", "beads" );
		params.putAll( filter.describeParameters() );

		List< String > lines = ActionToSparkCli.render( new ActionRecord( "filter-interestpoints", 0, Filter_Detections.class.getName(), params, views, "interestpoints:" + params.get( "label" ) ), "dataset.xml" );
		String command = command( lines );

		assertTrue( lines.stream().noneMatch( l -> l.startsWith( "#" ) ), "no warnings: " + lines );
		assertTrue( command.startsWith( "./filter-interestpoints -x dataset.xml" ), command );
		assertTrue( command.contains( " -l beads " ), command );
		assertTrue( command.contains( " --newLabel " + params.get( "label" ) ), command );
		assertTrue( command.contains( " --response 0.004.." ) && command.contains( " --sigma 1.5..3" ) && !command.contains( "--intensity" ), command );
		assertTrue( command.contains( " --keepPoints 3000" ) && command.contains( " --keepBy intensity" ) && command.contains( " --keepMode highest" ), command );
		assertFalse( command.contains( "--copy" ), command );

		// a plain copy
		params.put( "copy", "true" );
		command = command( ActionToSparkCli.render( new ActionRecord( "filter-interestpoints", 0, Filter_Detections.class.getName(), params, views, "interestpoints:x" ), "dataset.xml" ) );
		assertTrue( command.endsWith( " --copy" ) || command.contains( " --copy " ), command );

		// the lenient range of the command line parses what the recipe renders
		for ( final InterestPointFilter.Axis axis : InterestPointFilter.Axis.values() )
			if ( filter.filters( axis ) )
			{
				final double[] range = InterestPointFilter.parseAnyRange( params.get( axis.key ) );
				assertEquals( filter.min( axis ), range[ 0 ], 0.0 );
				assertEquals( filter.max( axis ), range[ 1 ], 0.0 );
			}
	}
}
