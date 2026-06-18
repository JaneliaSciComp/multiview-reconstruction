/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2025 Multiview Reconstruction developers.
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
package net.preibisch.mvrecon.headless.definedataset;

import java.io.File;
import java.net.URI;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import fiji.util.gui.GenericDialogPlus;
import ij.plugin.PlugIn;
import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.XmlIoSpimData;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.ViewDescription;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import util.URITools;

public class Rewrite_LuxendoXML implements Callable<Void>, PlugIn
{
	@Option(names = {"-i", "--input"}, required = true, description = "input XML written by Luxendo MuviSPIM, e.g. -i /home/dataset.xml")
	private String input = null;

	@Option(names = {"-o", "--output"}, required = false, description = "optional output file path for the Luxendo MuviSPIM XML containing BigStitcher attributes (input is overwritten if left empty, .xml~1 is backup created), e.g. -i /home/dataset_bigstitcher.xml")
	private String output = null;

	// e.g. stack_0-x00-y00_channel_1_obj_right_cam_long
	// patterns: stack_0-x{xx}-y{yy}_channel_{c}_obj_right_cam_long
	final static String searchX = "stack_0-x";
	final static String searchY = "-y";
	final static String searchCh = "_channel_";
	final static String searchObj = "_obj_";

	public static String defaultInput = "";

	@Override
	public Void call() throws Exception
	{
		if ( !new File( input ).exists() )
		{
			IOFunctions.println( "XML file '" + new File( input ).getAbsolutePath() + "' does not exist. Stopping.");
			return null;
		}

		// angle and illumination are constant (for now)
		final Angle angle = new Angle( 0 );
		final Illumination illum = new Illumination( 0 );

		try
		{
			IOFunctions.println( "Loading '" + input + "' ... ");

			final XmlIoSpimData io = new XmlIoSpimData();
			final SpimData spimData = io.load( input );
	
			final SpimData2 spimDataOut = SpimData2.convert( spimData );
			final SequenceDescription s = spimDataOut.getSequenceDescription();
	
			final HashMap< Pair<Integer, Integer>, Integer > tileIds = new HashMap<>();
			final AtomicInteger countTileId = new AtomicInteger(0);

			for ( final ViewDescription vd : s.getViewDescriptions().values() )
			{
				final String name = vd.getViewSetup().getName();

				IOFunctions.println( "Parsing ViewSetup: " + name + " ... " );

				final int[] p = parseName(name); //x,y,ch
				tileIds.computeIfAbsent( new ValuePair<>(p[0], p[1]), k -> countTileId.getAndIncrement() );

				IOFunctions.println( "x=" + p[0] + ", y=" + p[1] + ", ch=" + p[2] );
			}

			for ( final ViewDescription vd : s.getViewDescriptions().values() )
			{
				final String name = vd.getViewSetup().getName();

				final int[] p = parseName(name); //x,y,ch
				final int tileId = tileIds.get( new ValuePair<>(p[0], p[1]) );
				final int channelId = p[2];

				IOFunctions.println( "Adding Attributes to ViewSetup: " + name +": x=" + p[0] + ", y=" + p[1] + ", tileId=" + tileId + ", ch=" + p[2] + ", channelId=" + channelId );

				// set missing attributes
				vd.getViewSetup().setAttribute( angle );
				vd.getViewSetup().setAttribute( illum );
				vd.getViewSetup().setAttribute( new Channel( channelId ) );
				vd.getViewSetup().setAttribute( new Tile(tileId, "x=" + p[0] + ", y=" + p[1] ) );
			}

			output = (output == null || output.trim().length() == 0 ) ? input : output;

			IOFunctions.println( "Saving '" + output + "' ... ");

			XmlIoSpimData2.initN5Writing = false;

			final XmlIoSpimData2 io2 = new XmlIoSpimData2();
			io2.save(spimDataOut, URITools.toURI( output ) );

			IOFunctions.println( "done.");
		}
		catch ( Exception e )
		{
			System.err.println( "Error rewriting '" + input + "': " + e );
			e.printStackTrace();
		}

		return null;
	}

	public static int[] parseName( final String name )
	{
		final int x_i = name.indexOf( searchX );
		final int y_i = name.indexOf( searchY, x_i);
		final int ch_i = name.indexOf( searchCh, y_i);
		final int obj_i = name.indexOf( searchObj, ch_i );

		final String x_string = name.substring(x_i+searchX.length(), y_i);
		final String y_string = name.substring(y_i+searchY.length(), ch_i);
		final String ch_string = name.substring(ch_i+searchCh.length(), obj_i);

		final int x = Integer.parseInt( x_string );
		final int y = Integer.parseInt( y_string );
		final int ch = Integer.parseInt( ch_string );

		return new int[] {x,y,ch};
	}

	public static final void main(final String... args)
	{
		IOFunctions.printIJLog = false;
		new CommandLine( new Rewrite_LuxendoXML() ).execute( args );
	}

	@Override
	public void run(String arg)
	{
		GenericDialogPlus gd = new GenericDialogPlus( "Rewrite Luxendo XML for BigStitcher" );
		gd.addFileField( "Input_XML", defaultInput );
		gd.addMessage(
				"Note: the new XML will contain the attributes necessary to run BigStitcher\n" +
				"(multi-tile, multi-channel is supported for now, please contact Stephan Preibisch if you have other examples)", GUIHelper.smallStatusFont, GUIHelper.neutral );
		gd.addMessage("Note: XML will be overwritten, a backup is stored as '*.xml~1'", GUIHelper.smallStatusFont, GUIHelper.neutral );

		gd.showDialog();

		if (gd.wasCanceled())
			return;

		this.input = defaultInput = gd.getNextString();
		this.output = null;

		IOFunctions.printIJLog = true;

		try
		{
			call();
		} catch (Exception e) { e.printStackTrace(); }
	}
}
