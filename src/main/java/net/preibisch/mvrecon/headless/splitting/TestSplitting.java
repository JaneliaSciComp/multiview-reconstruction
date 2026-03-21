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
package net.preibisch.mvrecon.headless.splitting;

import java.net.URI;

import ij.ImageJ;
import mpicbg.spim.data.SpimDataException;
import net.preibisch.mvrecon.fiji.plugin.Split_Views;
import net.preibisch.mvrecon.fiji.plugin.Split_Views.InterestPointAdding;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ViewSetupExplorer;
import net.preibisch.mvrecon.process.splitting.SplittingTools;
import util.URITools;

public class TestSplitting
{
	public static void main( String[] args ) throws SpimDataException
	{
		new ImageJ();

		SpimData2 spimData;

		// generate 4 views with 1000 corresponding beads, single timepoint
		//spimData = SpimData2.convert( SimulatedBeadsImgLoader.spimdataExample( new int[]{ 0, 90, 135 } ) );

		/*
			<size>2048 2048 900</size>
			<size>0.40625 0.40625 0.8125</size>
		 */

		//final String file = "/Volumes/home/Data/Expansion Microscopy/dataset.xml";
		final URI uri = URITools.toURI( "/Users/spreibi/Documents/Microscopy/SPIM/HisYFP-SPIM/dataset.xml" );

		final URI uriOut = URITools.toURI( uri.toString().replace( ".xml", ".split.xml" ) );

		System.out.println( "in: " + uri );
		System.out.println( "out: " + uriOut );

		// load drosophila
		spimData = new XmlIoSpimData2().load( uri );

		final long[] minStepSize = Split_Views.findMinStepSize( spimData );

		//SpimData2 newSD = SplittingTools.splitImages( spimData, new long[] { 30, 30, 15 }, new long[] { 600, 600, 300 } );
		SpimData2 newSD =
				SplittingTools.splitImages(
						spimData,
						new long[] { 30, 30, 10 },
						new long[] {
								Split_Views.closestLongDivisableBy( 200, minStepSize[ 0 ] ),
								Split_Views.closestLongDivisableBy( 200, minStepSize[ 1 ] ),
								Split_Views.closestLongDivisableBy( 40, minStepSize[ 2 ] ) },
						minStepSize,
						true, // assingIlluminationsFromTileIds
						true, // optimize
						InterestPointAdding.CORR, // addIPs
						100,
						20,
						50,
						0.5,
						20.0 );
		// drosophila with 1000 views

		final ViewSetupExplorer< SpimData2 > explorer = new ViewSetupExplorer<>( newSD, uriOut, new XmlIoSpimData2() );
		explorer.getFrame().toFront();
	}

}
