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
package net.preibisch.mvrecon.fiji.plugin;

import java.awt.Color;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import fiji.util.gui.GenericDialogPlus;
import ij.ImageJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.Channel;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.plugin.queryXML.GenericLoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ViewSetupExplorer;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.splitting.SplitDistributeEvenly;
import net.preibisch.mvrecon.process.splitting.SplitOctTree;
import net.preibisch.mvrecon.process.splitting.SplitView;
import net.preibisch.mvrecon.process.splitting.SplittingTools;
import util.URITools;

public class Split_Views implements PlugIn
{
	public enum InterestPointAdding { IP, CORR, NONE };

	public static double defaultDensity = 100;
	public static int defaultMinPoints = 20;
	public static int defaultMaxPoints = 500;
	public static double defaultError = 0.5;
	public static double defaultExclusionRadiusIP = 20;

	public static boolean defaultAssignIlluminations = true;
	public static boolean defaultSaveOnTheFly = false;

	public static int defaultResultChoice = 0;
	private static final String[] resultChoices = new String[] { "Display", "Save & Close" };

	public static int defaultIPChoice = 1;
	private static final String[] ipChoices = new String[] { "Add 'fake' interest points (deprecated)", "Add 'fake' corresponding points", "NO interest point adding" };

	public static int defaultMethodChoice = 1;
	private static final String[] methodChoices = new String[] { "Uniform splitting", "Oct-tree based adaptive splitting" };

	public static int defaultChannelChoice = 0;

	@Override
	public void run(String arg)
	{
		final LoadParseQueryXML xml = new LoadParseQueryXML();

		if ( !xml.queryXML( "splitting/subdiving of views", false, false, false, false, false ) )
			return;

		final SpimData2 data = xml.getData();

		split( data, xml.getXMLURI() );
	}

	public static boolean split(
			final SpimData2 data,
			final URI saveAs,
			final SplitView splitting,
			final boolean assingIlluminationsFromTileIds,
			final InterestPointAdding ipAdding,
			final double pointDensity,
			final int minPoints,
			final int maxPoints,
			final double error,
			final double excludeRadius,
			final boolean display )
	{
		return split( data, saveAs, splitting, assingIlluminationsFromTileIds, ipAdding, pointDensity, minPoints, maxPoints, error, excludeRadius, display, SplittingTools.defaultFakeLabel(), null, null );
	}

	public static boolean split(
			final SpimData2 data,
			final URI saveAs,
			final SplitView splitting,
			final boolean assingIlluminationsFromTileIds,
			final InterestPointAdding ipAdding,
			final double pointDensity,
			final int minPoints,
			final int maxPoints,
			final double error,
			final double excludeRadius,
			final boolean display,
			final String fakeLabel,
			final SplittingTools.InterestPointSaver saver,
			final SplittingTools.CorrespondenceSaver corrSaver )
	{
		return split( data, saveAs, splitting, assingIlluminationsFromTileIds, ipAdding, pointDensity, minPoints, maxPoints, error, excludeRadius, display, fakeLabel, saver, corrSaver, null );
	}

	public static boolean split(
			final SpimData2 data,
			final URI saveAs,
			final SplitView splitting,
			final boolean assingIlluminationsFromTileIds,
			final InterestPointAdding ipAdding,
			final double pointDensity,
			final int minPoints,
			final int maxPoints,
			final double error,
			final double excludeRadius,
			final boolean display,
			final String fakeLabel,
			final SplittingTools.InterestPointSaver saver,
			final SplittingTools.CorrespondenceSaver corrSaver,
			final Set< Integer > driveByChannelIds )
	{
		final SpimData2 newSD = SplittingTools.splitImages( data, splitting, assingIlluminationsFromTileIds, ipAdding, pointDensity, minPoints, maxPoints, error, excludeRadius, fakeLabel, saver, corrSaver, driveByChannelIds );

		if ( display )
		{
			final ViewSetupExplorer< SpimData2 > explorer = new ViewSetupExplorer<>( newSD, saveAs, new XmlIoSpimData2() );
			explorer.getFrame().toFront();
		}
		else
		{
			new XmlIoSpimData2().save( newSD, saveAs );
		}

		return true;
	}

	public static boolean split( final SpimData2 data, final URI filePath )
	{
		// Only datasets with more than one channel can drive the split from a single channel.
		final List< Channel > channels = data.getSequenceDescription().getAllChannelsOrdered();
		final boolean multipleChannels = channels.size() > 1;

		final String[] channelChoices = new String[ channels.size() + 1 ];
		channelChoices[ 0 ] = "All channels (split each independently)";
		for ( int i = 0; i < channels.size(); ++i )
			channelChoices[ i + 1 ] = "Channel " + channels.get( i ).getName();

		if ( defaultChannelChoice >= channelChoices.length )
			defaultChannelChoice = 0;

		final GenericDialog gdInit = new GenericDialogPlus( "Dataset splitting/subdividing method" );
		gdInit.addChoice("splitting_method", methodChoices, methodChoices[ defaultMethodChoice ] );
		if ( multipleChannels )
		{
			gdInit.addChoice( "Drive_split_by_channel", channelChoices, channelChoices[ defaultChannelChoice ] );
			gdInit.addMessage(
					"The selected channel will be split according to its own interest points.\n" +
					"All other channels will be virtually split to match the tiling of the selected channel\n",
					GUIHelper.smallStatusFont, Color.DARK_GRAY );
		}
		gdInit.showDialog();
		if ( gdInit.wasCanceled() )
			return false;
		final int method = defaultMethodChoice = gdInit.getNextChoiceIndex();

		// channel that drives the split geometry; all other channels mirror it
		// (null = split every setup independently, the original behaviour)
		final Set< Integer > driveByChannelIds;
		if ( multipleChannels )
		{
			final int channelChoice = defaultChannelChoice = gdInit.getNextChoiceIndex();
			driveByChannelIds = ( channelChoice == 0 ) ? null : Collections.singleton( channels.get( channelChoice - 1 ).getId() );
		}
		else
		{
			driveByChannelIds = null;
		}

		final long[] minStepSize = SplittingTools.findMinStepSize( data );

		final GenericDialogPlus gd = new GenericDialogPlus( "Dataset splitting/subdividing" );

		if ( method == 0 )
			SplitDistributeEvenly.setupGUI( gd, data, minStepSize );
		else if ( method == 1 )
		{
			if ( !SplitOctTree.setupGUI( gd, data, minStepSize, driveByChannelIds ) )
				return false;
		}
		else
			throw new RuntimeException( "Unknown splitting method: " + method );

		gd.addChoice( "Interest_points", ipChoices, ipChoices[ defaultIPChoice ] );

		gd.addMessage( "" );

		if ( data.getSequenceDescription().getAllIlluminationsOrdered().size() == 1 )
			gd.addCheckbox( "Assign_old_tiles_as_illuminations (great for visualization)", defaultAssignIlluminations );

		IOFunctions.println( filePath );

		final String suggestion;

		final int index = filePath.toString().indexOf( ".xml");
		if ( index > 0 )
			suggestion = filePath.toString().substring( 0, index ) + ".split.xml";
		else
			suggestion = filePath.toString() + ".split.xml";

		gd.addFileField("New_XML_File", suggestion, 30);
		gd.addChoice( "Split_Result", resultChoices, resultChoices[ defaultResultChoice ] );
		gd.addCheckbox( "Save_interest_points_on-the-fly (recommended for large datasets)", defaultSaveOnTheFly );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return false;

		final SplitView splittingMethod;

		if ( method == 0 )
			splittingMethod = SplitDistributeEvenly.queryGUI( gd, data, minStepSize );
		else if ( method == 1 )
			splittingMethod = SplitOctTree.queryGUI( gd, data, minStepSize, driveByChannelIds );
		else
			throw new RuntimeException( "Unknown splitting method: " + method );

		final int ipChoice = defaultIPChoice = gd.getNextChoiceIndex();

		final boolean assignIllum;
		if ( data.getSequenceDescription().getAllIlluminationsOrdered().size() == 1 )
			assignIllum = defaultAssignIlluminations = gd.getNextBoolean();
		else
			assignIllum = false;

		final String saveAs = gd.getNextString();
		final int choice = defaultResultChoice = gd.getNextChoiceIndex();
		final boolean saveOnTheFly = defaultSaveOnTheFly = gd.getNextBoolean();

		double density = defaultDensity;
		int minPoints = defaultMinPoints;
		int maxPoints = defaultMaxPoints;
		double error = defaultError;
		double exclusionRadius = Double.NaN;

		InterestPointAdding ipAdding = InterestPointAdding.NONE;

		String fakeLabel = SplittingTools.defaultFakeLabel();

		if ( ipChoice < 2 )
		{
			final GenericDialogPlus gd2 = new GenericDialogPlus( (ipChoice == 0) ? "Add fake interest points (DEPRECATED)" : "Add fake CORRESPONDING interest points" );

			gd2.addStringField( "Interest_point_label", fakeLabel, 20 );
			gd2.addNumericField( "Density (# per 100x100x100 px)", defaultDensity, 2 );
			gd2.addNumericField( "Min_total number of points", defaultMinPoints, 0 );
			gd2.addNumericField( "Max_total number of points", defaultMaxPoints, 0 );
			gd2.addNumericField( "Artificial error (px)", defaultError, 2 );
			if (ipChoice == 0)
				gd2.addNumericField( "Exclusion_radius (px)", defaultExclusionRadiusIP, 2 );

			gd2.showDialog();

			if ( gd2.wasCanceled() )
				return false;

			fakeLabel = gd2.getNextString();
			density = defaultDensity = gd2.getNextNumber();
			minPoints = defaultMinPoints = (int)Math.round(gd2.getNextNumber());
			maxPoints = defaultMaxPoints = (int)Math.round(gd2.getNextNumber());
			error = defaultError = gd2.getNextNumber();

			if (ipChoice == 0 )
			{
				exclusionRadius = defaultExclusionRadiusIP = gd2.getNextNumber();
				ipAdding = InterestPointAdding.IP;
			}
			else
			{
				ipAdding = InterestPointAdding.CORR;
			}
		}

		final SplittingTools.InterestPointSaver saver = saveOnTheFly ? vipl -> {
			for ( final ViewInterestPointLists v : vipl.values() )
				for ( final InterestPoints ips : v.getHashMap().values() )
				{
					ips.saveInterestPoints( false );
					ips.saveCorrespondingInterestPoints( false );
				}
		} : null;

		final SplittingTools.CorrespondenceSaver corrSaver = saveOnTheFly ? vipl -> {
			for ( final InterestPoints ips : vipl.getHashMap().values() )
				ips.saveCorrespondingInterestPoints( false );
		} : null;

		return split( data, URITools.toURI( saveAs ), splittingMethod, assignIllum, ipAdding, density, minPoints, maxPoints, error, exclusionRadius, choice == 0, fakeLabel, saver, corrSaver, driveByChannelIds );
	}

	public static void main( String[] args ) throws SpimDataException
	{
		new ImageJ();

		GenericLoadParseQueryXML.defaultXMLURI = "/Users/preibischs/SparkTest/Stitching/dataset.xml";

		new Split_Views().run( null );
		//SpimData2 data = new XmlIoSpimData2("").load( GenericLoadParseQueryXML.defaultXMLfilename );
		//findMinStepSize(data);
	}
}
