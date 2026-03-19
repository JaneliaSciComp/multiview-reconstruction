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
package net.preibisch.mvrecon.fiji.datasetmanager;

import java.awt.Color;
import java.awt.Label;
import java.awt.Panel;
import java.awt.TextField;
import java.awt.event.TextEvent;
import java.awt.event.TextListener;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import javax.swing.JLabel;

import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.OmeNgffMultiScaleMetadata;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.OmeNgffMultiScaleMetadata.OmeNgffDataset;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.coordinateTransformations.CoordinateTransformation;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.coordinateTransformations.ScaleCoordinateTransformation;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.coordinateTransformations.TranslationCoordinateTransformation;

import fiji.util.gui.GenericDialogPlus;
import ij.ImageJ;
import ij.gui.GenericDialog;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.MissingViews;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.datasetmanager.patterndetector.FilenamePatternDetector;
import net.preibisch.mvrecon.fiji.datasetmanager.patterndetector.NumericalFilenamePatternDetector;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.plugin.util.PluginHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBoxes;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.AllenOMEZarrLoader;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.AllenOMEZarrLoader.OMEZARREntry;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import util.URITools;

public class OMEZARR implements MultiViewDatasetDefinition
{
	public static String defaultDirectory = "";
	public static String defaultDir = "/";
	private static ArrayList<URIListChooser> uriListChoosers = new ArrayList<>();

	static
	{
		uriListChoosers.add( new WildcardDirectoryListChooser() );
		uriListChoosers.add( new MultipleOMEZARRListChooser() );
	}

	@Override
	public boolean supportsRemoteXMLLocation() { return true; }

	@Override
	public String getTitle()
	{
		return "OME-ZARR Dataset Loader";
	}

	@Override
	public String getExtendedDescription()
	{
		return "This dataset definition supports to import a (set of) OME-ZARR's.";
	}

	@Override
	public SpimData2 createDataset( final String xmlFileName )
	{
		final URIListChooser chooser;

		// only ask how we want to choose files if there are multiple ways
		if (uriListChoosers.size() > 1)
		{
			final String[] uriListChooserChoices = new String[uriListChoosers.size()];
			for (int i = 0; i< uriListChoosers.size(); i++)
				uriListChooserChoices[i] = uriListChoosers.get( i ).getDescription();

			final GenericDialog gd1 = new GenericDialog( "How to select OME-ZARRs" );
			gd1.addChoice( "OME-ZARR chooser", uriListChooserChoices, uriListChooserChoices[0] );
			gd1.showDialog();

			if (gd1.wasCanceled())
				return null;

			chooser = uriListChoosers.get( gd1.getNextChoiceIndex() );
		}
		else
		{
			chooser = uriListChoosers.get( 0 );
		}

		final Pair<URI, List<String>> list = chooser.getDatasetList();
		final URI baseDir = list.getA();
		final List<String> datasets = list.getB();

		if ( datasets == null || datasets.size() < 1)
		{
			IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): ERROR: No directories(s) found at the specified location, quitting.");
			return null;
		}

		// set up basedir as ZARR (if necessary)
		IOFunctions.println( "Trying to open base dir '" + baseDir + "' as ZARR container ... " );

		N5Reader reader;

		try
		{
			reader = URITools.instantiateN5Reader( StorageFormat.ZARR, baseDir );
		}
		catch ( Exception e )
		{
			IOFunctions.println( "Base dir '" + baseDir + "' is NOT a ZARR container, turning it into one ..." );

			try
			{
				reader = URITools.instantiateN5Writer( StorageFormat.ZARR, baseDir );
			}
			catch (Exception e2 )
			{
				IOFunctions.println( "Could not turn base dir '" + baseDir + "' into ZARR container, stopping: " + e );
				e.printStackTrace();
				return null;
			}
		}

		//
		// load metadata for all ome-zarrs
		//
		final HashMap< String, Pair< DatasetAttributes, Pair< VoxelDimensions, double[] > > > attrMap = new HashMap<>();

		int numDimensions = -1;
		long sizeC = -1;
		long sizeT = -1;

		for ( final String dataset : datasets )
		{
			IOFunctions.println( "\nFetching metadata for " + dataset );

			//org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.OmeNgffMetadata
			// for this to work you need to register an adapter in the N5Factory class
			// final GsonBuilder builder = new GsonBuilder().registerTypeAdapter( CoordinateTransformation.class, new CoordinateTransformationAdapter() );
			final OmeNgffMultiScaleMetadata[] multiscales = reader.getAttribute( dataset, "multiscales", OmeNgffMultiScaleMetadata[].class );

			if ( multiscales == null || multiscales.length == 0 )
			{
				IOFunctions.println( "Could not parse OME-ZARR multiscales object. stopping." );
				return null;
			}

			if ( multiscales.length != 1 )
			{
				IOFunctions.println( "This dataset has " + multiscales.length + " objects, we expected 1. Picking the first one." );
				return null;
			}

			final OmeNgffDataset ds = multiscales[ 0 ].datasets[ 0 ];
			double[] scale = null;
			double[] translation = null;

			for ( final CoordinateTransformation< ? > c : ds.coordinateTransformations )
			{
				if ( c instanceof ScaleCoordinateTransformation )
					scale = ((ScaleCoordinateTransformation)c).getScale().clone();

				// TODO: do we need to scale the translations?
				if ( c instanceof TranslationCoordinateTransformation )
					translation = ((TranslationCoordinateTransformation)c).getTranslation().clone();
			}

			if ( scale == null )
				scale = new double[] { 1, 1, 1 };

			if ( translation == null )
				translation = new double[] { 0, 0, 0 };

			if ( scale.length > 3 )
				scale = new double[] { scale[ 0 ], scale[ 1 ], scale[ 2 ] };

			if ( translation.length > 3 )
				translation = new double[] { translation[ 0 ], translation[ 1 ], translation[ 2 ] };

			String unit = "unknown";
			for ( int d = 0; d < multiscales[ 0 ].axes.length; ++d )
				if ( multiscales[ 0 ].axes[ d ].getName().toLowerCase().equals( "x" ) )
					unit = multiscales[ 0 ].axes[ d ].getUnit();

			IOFunctions.println( "Scale: " + Arrays.toString( scale ) + ", unit: " + unit );
			IOFunctions.println( "Translation: " + Arrays.toString( translation ) );

			IOFunctions.println( multiscales[ 0 ].getPaths().length + " resolution steps." );

			DatasetAttributes fullScaleAttributes = null;

			for ( final String path : multiscales[ 0 ].getPaths() )
			{
				IOFunctions.println( "path '" + path + "':");

				final DatasetAttributes attr = reader.getDatasetAttributes( dataset + "/" + path );

				IOFunctions.println( "NumDimensions: " + attr.getNumDimensions() );
				IOFunctions.println( "Dimensions: " + Arrays.toString( attr.getDimensions() ) );
				IOFunctions.println( "BlockSize: " + Arrays.toString( attr.getBlockSize() ) );
				IOFunctions.println( "DataType: " + attr.getDataType() );
				IOFunctions.println( "Compression: " + attr.getCompression() );

				if ( fullScaleAttributes == null || attr.getDimensions()[ 0 ] > fullScaleAttributes.getDimensions()[ 0 ])
					fullScaleAttributes = attr;
			}

			if ( numDimensions == -1 )
			{
				numDimensions = fullScaleAttributes.getNumDimensions();

				if ( numDimensions >=4 )
				{
					sizeC = fullScaleAttributes.getDimensions()[ 3 ];
					sizeT = fullScaleAttributes.getDimensions()[ 4 ];
				}
			}
			else
			{
				if ( numDimensions != fullScaleAttributes.getNumDimensions() )
				{
					IOFunctions.println( "numDimensions mismatch. stopping" );
					return null;
				}

				if ( numDimensions >=4 && sizeC != fullScaleAttributes.getDimensions()[ 3 ] )
				{
					IOFunctions.println( "numDimensions mismatch for sizeC [3] in dataset dimensions. stopping" );
					return null;
				}

				if ( numDimensions >=5 && sizeT != fullScaleAttributes.getDimensions()[ 4 ] )
				{
					IOFunctions.println( "numDimensions mismatch for sizeT [4] in dataset dimensions. stopping" );
					return null;
				}
			}

			if ( numDimensions != 3 && numDimensions != 5 )
			{
				IOFunctions.println( "Only 3D (xyz) and 5D (xyztc) OME-ZARRs are allowed right now. stopping" );
				return null;
			}

			attrMap.put( dataset, new ValuePair<>( fullScaleAttributes, new ValuePair<>(new FinalVoxelDimensions(unit, scale), translation ) ) );
		}

		IOFunctions.println( "\nnumDimensions in OME-ZARR's: " + numDimensions );

		if ( numDimensions >=4 )
			IOFunctions.println( "numChannels in OME-ZARR's: " + sizeC );

		if ( numDimensions >=5 )
			IOFunctions.println( "numTimePoints in OME-ZARR's: " + sizeT );

		final FilenamePatternDetector patternDetector = new NumericalFilenamePatternDetector();
		patternDetector.detectPatterns( datasets );
		final int numVariables = patternDetector.getNumVariables();

		IOFunctions.println( "Found " + numVariables + " patterns in datasets." );

		//
		// Set up dialog
		//
		final GenericDialog gd = new GenericDialog( "Assign Metadata to OME-ZARR(s)" );

		final StringBuilder inDirSummarySB = new StringBuilder();
		final List<String> choices = new ArrayList<>();
		
		if ( numDimensions > 3 )
		{
			if ( sizeC == 1 && sizeT == 1 )
			{
				inDirSummarySB.append( "<html> <h3> OME-ZARR(s) are 5D, but c=z=1 thus we assume 3D (xyz) </h3></html>" );
				choices.add( "TimePoints" );
				choices.add( "Channels" );
			}
			else
			{
				inDirSummarySB.append( "<html> <h3> Views detected within 5D (xyzct) OME-ZARR(s) </h3>" );
				inDirSummarySB.append( "<p style=\"color:green\">" + sizeC+ " channels detected </p>" );
				inDirSummarySB.append( "<p style=\"color:green\">" + sizeT+ " timepoints detected </p>" );
			}
		}
		else
		{
			inDirSummarySB.append( "<html> <h3> OME-ZARR(s) are 3D (xyz). </h3></html>" );
		}

		FileListDatasetDefinition.addMessageAsJLabel(inDirSummarySB.toString(), gd);

		final Pair< String, String > prefixAndPattern = FileListDatasetDefinition.splitIntoPrefixAndPattern( patternDetector );
		final StringBuilder sbfilePatterns = new StringBuilder();
		sbfilePatterns.append(  "<html> <h3> Patterns in OME-ZARR directories </h3> " );
		sbfilePatterns.append( "<p style=\"color:green\"> " + numVariables + " numerical pattern" + ((numVariables > 1) ? "s": "") + " found</p>" );
		sbfilePatterns.append( "</br><p> Patterns: " + FileListDatasetDefinition.getColoredHtmlFromPattern( prefixAndPattern.getB(), false ) + "</p>" );
		sbfilePatterns.append( "</html>" );
		FileListDatasetDefinition.addMessageAsJLabel(sbfilePatterns.toString(), gd);

		choices.add( "Tiles" );
		choices.add( "Angles" );
		choices.add( "Illuminations" );
		choices.add( "-- ignore this pattern --" );

		String[] choicesAll = choices.toArray( new String[]{} );

		for (int i = 0; i < numVariables; i++)
		{
			gd.addChoice( "Pattern_" + i + " represents", choicesAll, choicesAll[ Math.min( i, choicesAll.length - 2 ) ] );

			//do not fail just due to coloring
			try
			{
				((Label) gd.getComponent( gd.getComponentCount() - 2 )).setForeground( FileListDatasetDefinition.getColorN( i ) );
			}
			catch (Exception e) {}
		}

		FileListDatasetDefinition.addMessageAsJLabel(  "<html> <h3> Voxel Size calibration </h3> </html> ", gd );
		if(!hasCommonScale( attrMap ) )
			FileListDatasetDefinition.addMessageAsJLabel(  "<html> <p style=\"color:orange\">WARNING: Voxel Sizes are not the same for all views, modify them at your own risk! </p> </html> ", gd );

		final VoxelDimensions someCalib = attrMap.values().iterator().next().getB().getA();

		gd.addCheckbox( "Modify_voxel_size?", false );
		gd.addNumericField( "Voxel_size_X", someCalib.dimension( 0 ), 4 );
		gd.addNumericField( "Voxel_size_Y", someCalib.dimension( 1 ), 4 );
		gd.addNumericField( "Voxel_size_Z", someCalib.dimension( 2 ), 4 );
		gd.addStringField( "Voxel_size_unit", someCalib.unit(), 20 );

		gd.showDialog();
		if ( gd.wasCanceled() )
			return null;

		final HashMap< Class<? extends Entity>, List<Integer>> fileVariableToUse = new HashMap<>();

		fileVariableToUse.put( TimePoint.class, new ArrayList<>() );
		fileVariableToUse.put( Channel.class, new ArrayList<>() );
		fileVariableToUse.put( Illumination.class, new ArrayList<>() );
		fileVariableToUse.put( Tile.class, new ArrayList<>() );
		fileVariableToUse.put( Angle.class, new ArrayList<>() );

		for (int i = 0; i < numVariables; i++)
		{
			final String choice = gd.getNextChoice();

			if (choice.equals( "TimePoints" ))
			{
				fileVariableToUse.get( TimePoint.class ).add( i );

				if ( fileVariableToUse.get( TimePoint.class ).size() > 1 )
				{
					IOFunctions.println( "Cannot assign more than one pattern to TimePoint. Stopping." );
					return null;
				}
			}
			else if (choice.equals( "Channels" ))
			{
				fileVariableToUse.get( Channel.class ).add( i );

				if ( fileVariableToUse.get( Channel.class ).size() > 1 )
				{
					IOFunctions.println( "Cannot assign more than one pattern to Channel. Stopping." );
					return null;
				}
			}
			else if (choice.equals( "Illuminations" ))
			{
				fileVariableToUse.get( Illumination.class ).add( i );

				if ( fileVariableToUse.get( Illumination.class ).size() > 1 )
				{
					IOFunctions.println( "Cannot assign more than one pattern to Illumination. Stopping." );
					return null;
				}
			}
			else if (choice.equals( "Tiles" ))
			{
				fileVariableToUse.get( Tile.class ).add( i );

				if ( fileVariableToUse.get( Tile.class ).size() > 1 )
				{
					IOFunctions.println( "Cannot assign more than one pattern to Tile. Stopping." );
					return null;
				}
			}
			else if (choice.equals( "Angles" ))
			{
				fileVariableToUse.get( Angle.class ).add( i );

				if ( fileVariableToUse.get( Angle.class ).size() > 1 )
				{
					IOFunctions.println( "Cannot assign more than one pattern to Angle. Stopping." );
					return null;
				}
			}
		}

		// query modified calibration
		if ( gd.getNextBoolean() )
		{
			// modifyCalibration
			final double calX = gd.getNextNumber();
			final double calY = gd.getNextNumber();
			final double calZ = gd.getNextNumber();
			final String calUnit = gd.getNextString();

			for ( final String key : attrMap.keySet() )
			{
				final VoxelDimensions vx = new FinalVoxelDimensions( calUnit, new double[] { calX, calY, calZ } );
				final double[] translation = attrMap.get( key ).getB().getB();
				final DatasetAttributes da = attrMap.get( key ).getA();

				attrMap.put( key, new ValuePair<>( da, new ValuePair<>( vx, translation ) ) );
			}
		}

		//
		// build the actual SpimData object
		//

		// parse the patterns
		/*
		IOFunctions.println( patternDetector.getStringRepresentation() );
		IOFunctions.println( patternDetector.getPatternAsRegex() );

		for ( int i = 0; i < numVariables; ++i )
		{
			IOFunctions.println( "Var " + i );
			IOFunctions.println( patternDetector.getInvariant(i) );
			IOFunctions.println( patternDetector.getValuesForVariable( i ) );
		}
		*/

		final ArrayList< String > timepointStrings = new ArrayList<>();
		final ArrayList< String > channelStrings = new ArrayList<>();
		final ArrayList< String > tileStrings = new ArrayList<>();
		final ArrayList< String > angleStrings = new ArrayList<>();
		final ArrayList< String > illuminationStrings = new ArrayList<>();

		if ( fileVariableToUse.get( TimePoint.class ).size() > 0 )
		{
			final int pattern = fileVariableToUse.get( TimePoint.class ).get( 0 );
			final List<String> patternList = patternDetector.getValuesForVariable( pattern );
			final List<String> uniquepatternList = patternList.stream().distinct().collect(Collectors.toList());

			IOFunctions.println( "TimePoint is pattern #" + pattern );
			IOFunctions.println( "TimePoint pattern instances: " + patternList );
			IOFunctions.println( "TimePoint unique pattern instances: " + uniquepatternList );

			timepointStrings.addAll( uniquepatternList );
		}

		if ( fileVariableToUse.get( Channel.class ).size() > 0 )
		{
			final int pattern = fileVariableToUse.get( Channel.class ).get( 0 );
			final List<String> patternList = patternDetector.getValuesForVariable( pattern );
			final List<String> uniquepatternList = patternList.stream().distinct().collect(Collectors.toList());

			IOFunctions.println( "Channel is pattern #" + pattern );
			IOFunctions.println( "Channel pattern instances: " + patternList );
			IOFunctions.println( "Channel unique pattern instances: " + uniquepatternList );

			channelStrings.addAll( uniquepatternList );
		}

		if ( fileVariableToUse.get( Tile.class ).size() > 0 )
		{
			final int pattern = fileVariableToUse.get( Tile.class ).get( 0 );
			final List<String> patternList = patternDetector.getValuesForVariable( pattern );
			final List<String> uniquepatternList = patternList.stream().distinct().collect(Collectors.toList());

			IOFunctions.println( "Tile is pattern #" + pattern );
			IOFunctions.println( "Tile pattern instances: " + patternList );
			IOFunctions.println( "Tile unique pattern instances: " + uniquepatternList );

			tileStrings.addAll( uniquepatternList );
		}

		if ( fileVariableToUse.get( Angle.class ).size() > 0 )
		{
			final int pattern = fileVariableToUse.get( Angle.class ).get( 0 );
			final List<String> patternList = patternDetector.getValuesForVariable( pattern );
			final List<String> uniquepatternList = patternList.stream().distinct().collect(Collectors.toList());

			IOFunctions.println( "Angle is pattern #" + pattern );
			IOFunctions.println( "Angle pattern instances: " + patternList );
			IOFunctions.println( "Angle unique pattern instances: " + uniquepatternList );

			angleStrings.addAll( uniquepatternList );
		}

		if ( fileVariableToUse.get( Illumination.class ).size() > 0 )
		{
			final int pattern = fileVariableToUse.get( Illumination.class ).get( 0 );
			final List<String> patternList = patternDetector.getValuesForVariable( pattern );
			final List<String> uniquepatternList = patternList.stream().distinct().collect(Collectors.toList());

			IOFunctions.println( "Illumination is pattern #" + pattern );
			IOFunctions.println( "Illumination pattern instances: " + patternList );
			IOFunctions.println( "Illumination unique pattern instances: " + uniquepatternList );

			illuminationStrings.addAll( uniquepatternList );
		}

		//
		// create timepoints
		//
		final ArrayList< TimePoint > tps = new ArrayList< TimePoint >();
		if ( timepointStrings.size() == 0 )
		{
			// timepoints within the 5D OME-ZARR
			for ( int i = 0; i < sizeT; ++i )
				tps.add( new TimePoint( i ) );
		}
		else
		{
			// timepoints as pattern in 3D OME-ZARR
			for ( int i = 0; i < timepointStrings.size(); ++i )
				tps.add( new TimePoint( i ) );
		}

		final TimePoints timepoints = new TimePoints( tps );

		//
		// create ViewSetups
		//
		final ArrayList< Channel > channels = new ArrayList< Channel >();
		if ( channelStrings.size() == 0 )
		{
			// channels within the 5D OME-ZARR
			for ( int i = 0; i < sizeC; ++i )
				channels.add( new Channel( i ) );
		}
		else
		{
			// channels as pattern in 3D OME-ZARR
			for ( int i = 0; i < channelStrings.size(); ++i )
				channels.add( new Channel( i, channelStrings.get( i ) ) );
		}

		final ArrayList< Tile > tiles = new ArrayList<>();
		if ( tileStrings.size() > 0 )
			for ( int i = 0; i < tileStrings.size(); ++i )
				tiles.add( new Tile( i, tileStrings.get( i ) ) ); // we set TileLocations later
		else
			tiles.add( new Tile( 0 ) );

		final ArrayList< Angle > angles = new ArrayList<>();
		if ( angleStrings.size() > 0 )
			for ( int i = 0; i < angleStrings.size(); ++i )
				angles.add( new Angle( i, angleStrings.get( i ) ) );
		else
			angles.add( new Angle( 0 ) );

		final ArrayList< Illumination > illums = new ArrayList<>();
		if ( illuminationStrings.size() > 0 )
			for ( int i = 0; i < illuminationStrings.size(); ++i )
				illums.add( new Illumination( i, illuminationStrings.get( i ) ) );
		else
			illums.add( new Illumination( 0 ) );

		final ArrayList< ViewSetup > viewSetups = new ArrayList< ViewSetup >();
		for ( final Channel c : channels )
			for ( final Tile t : tiles )
				for ( final Angle a : angles )
					for ( final Illumination i : illums )
					{
						// we set VoxelSize, Size, TileLocations later once we iterate through the datasets
						viewSetups.add( new ViewSetup( viewSetups.size(), null, /*dim*/null, /*voxelSize*/null, t, c, a, i ) );
					}

		//
		// build all ViewIds for OMEZARR-Reader
		//
		final HashMap< ViewId, OMEZARREntry > viewIdToPath = new HashMap<>();
		/*
		<zgroup setup="0" tp="0" path="s0-t0.zarr" />
		<zgroup setup="1" tp="0" path="s1-t0.zarr" />
		<zgroup setup="2" tp="0" path="s2-t0.zarr" />
		*/
		IOFunctions.println( "\n" );

		for ( final String dataset : datasets )
		{
			IOFunctions.println( "Dataset: " + dataset );

			final Matcher m = patternDetector.getPatternAsRegex().matcher( dataset );

			if ( !m.matches() )
			{
				IOFunctions.println( "Error: dataset name does not match regex: " + patternDetector.getPatternAsRegex() );
				return null;
			}

			final int groupCount = m.groupCount();

			if ( groupCount != numVariables )
			{
				IOFunctions.println( "Error: numVariables (" + numVariables + ") does not match groupCount ("+ groupCount + ") for regex: " + patternDetector.getPatternAsRegex() );
				return null;
			}

			int tpId = -1, chId = -1, tileId = -1, angleId = -1, illumId = -1;

			for ( int i = 0; i < numVariables; ++i )
			{
				final String groupValue = m.group( i + 1 );

				if ( fileVariableToUse.get( TimePoint.class ).size() > 0 && fileVariableToUse.get( TimePoint.class ).get( 0 ) == i )
					tpId = timepointStrings.indexOf( groupValue );

				if ( fileVariableToUse.get( Channel.class ).size() > 0 && fileVariableToUse.get( Channel.class ).get( 0 ) == i )
					chId = channelStrings.indexOf( groupValue );

				if ( fileVariableToUse.get( Tile.class ).size() > 0 && fileVariableToUse.get( Tile.class ).get( 0 ) == i )
					tileId = tileStrings.indexOf( groupValue );

				if ( fileVariableToUse.get( Angle.class ).size() > 0 && fileVariableToUse.get( Angle.class ).get( 0 ) == i )
					angleId = angleStrings.indexOf( groupValue );

				if ( fileVariableToUse.get( Illumination.class ).size() > 0 && fileVariableToUse.get( Illumination.class ).get( 0 ) == i )
					illumId = illuminationStrings.indexOf( groupValue );
			}

			//IOFunctions.println( tpId + ", " + chId + ", " + tileId + ", " + angleId + ", " + illumId );

			// we need the metadata
			final Pair<DatasetAttributes, Pair<VoxelDimensions, double[]>> meta = attrMap.get( dataset );

			// has a pattern, so we need to load and update the metadata
			if ( tileId >= 0 && meta.getB().getB() != null )
			{
				tiles.get( tileId ).setLocation( meta.getB().getB() );
			}

			// the ones that do not have a pattern are just 0
			tileId = Math.max( 0, tileId );
			angleId = Math.max( 0, angleId );
			illumId = Math.max( 0, illumId );

			if ( chId >= 0 )
			{
				// channel had a pattern, so it's a single channel for this dataset, still possibly multiple timepoints
				viewIdToPath.putAll( updateViewSetup(dataset, viewSetups, meta, angleId, chId, illumId, tileId, tpId, sizeT ) );
			}
			else
			{
				// if the channels are inside the 5D OME-ZARR, this dataset could contain more than one ViewSetup
				for ( int c = 0; c < sizeC; ++c )
					viewIdToPath.putAll( updateViewSetup( dataset, viewSetups, meta, angleId, c, illumId, tileId, tpId, sizeT ) );
			}
		}

		final HashSet< ViewId > missing = new HashSet<>();

		for ( final TimePoint t : timepoints.getTimePointsOrdered() )
			for ( final ViewSetup vs : viewSetups )
				if ( !viewIdToPath.containsKey( new ViewId( t.getId(), vs.getId() ) ) )
					missing.add( new ViewId( t.getId(), vs.getId() ) );

		IOFunctions.println( "Missing views: " + ( missing.size() == 0 ? " none " : "" ) );
		missing.forEach( m -> IOFunctions.println( "   " + Group.pvid( m ) ) );

		final MissingViews missingViews = new MissingViews( missing );

		final SequenceDescription sequenceDescription = new SequenceDescription( timepoints, viewSetups, null, missingViews );
		final ImgLoader imgLoader = new AllenOMEZarrLoader(baseDir, sequenceDescription, viewIdToPath );
		sequenceDescription.setImgLoader( imgLoader );

		// get the minimal resolution of all calibrations
		final double minResolution = attrMap.values().stream()
				.map(p -> Math.min(Math.min(p.getB().getA().dimension(0), p.getB().getA().dimension(1)), p.getB().getA().dimension(2)))
				.min(Comparator.naturalOrder()).get();

		IOFunctions.println( "Minimal resolution in all dimensions is: " + minResolution );
		IOFunctions.println( "(The smallest resolution in any dimension; the distance between two pixels in the output image will be that wide)" );

		// create calibration + translation view registrations
		final ViewRegistrations viewRegistrations =
				DatasetCreationUtils.createViewRegistrations( sequenceDescription.getViewDescriptions(), minResolution );
		
		// create the initial view interest point object
		final ViewInterestPoints viewInterestPoints = new ViewInterestPoints();

		// finally create the SpimData itself based on the sequence description and the view registration
		final SpimData2 spimData = new SpimData2( baseDir, sequenceDescription, viewRegistrations, viewInterestPoints, new BoundingBoxes(), new PointSpreadFunctions(), new StitchingResults(), new IntensityAdjustments() );

		return spimData;
	}

	protected static HashMap< ViewId, OMEZARREntry > updateViewSetup(
			final String dataset,
			final Collection< ViewSetup > viewSetups,
			final Pair<DatasetAttributes, Pair<VoxelDimensions, double[]>> meta,
			final int angleId,
			final int channelId,
			final int illumId,
			final int tileId,
			final int tpId,
			final long sizeT )
	{
		final HashMap< ViewId, OMEZARREntry > entries = new HashMap<>();

		for ( final ViewSetup vs : viewSetups )
		{
			if ( vs.getAngle().getId() == angleId && vs.getChannel().getId() == channelId && vs.getTile().getId() == tileId && vs.getIllumination().getId() == illumId )
			{
				IOFunctions.println( "ViewSetupId: " + vs.getId() );

				vs.setSize( new FinalDimensions( meta.getA().getDimensions() ) );
				vs.setVoxelSize( meta.getB().getA() );

				if ( tpId >= 0 )
				{
					final ViewId viewId = new ViewId( tpId, vs.getId() );
					final OMEZARREntry entry = new OMEZARREntry( dataset, new int[] { channelId, 0 } );

					IOFunctions.println( "ViewId: " + Group.pvid( viewId ) + " @ " + entry );
					entries.put( viewId, entry );
				}
				else
				{
					// if the timepoints are inside the 5D OME-ZARR, this dataset could contain more than one ViewId
					for ( int t = 0; t < sizeT; ++t )
					{
						final ViewId viewId = new ViewId( t, vs.getId() );
						final OMEZARREntry entry = new OMEZARREntry( dataset, new int[] { channelId, t } );

						IOFunctions.println( "ViewId: " + Group.pvid( viewId ) + " @ " + entry );
						entries.put( viewId, entry );
					}
				}
			}
		}

		return entries;
	}

	protected static boolean hasCommonScale( final Map< String, Pair< DatasetAttributes, Pair< VoxelDimensions, double[] > > > attr )
	{
		double[] firstScale = null;

		for ( final Pair< DatasetAttributes, Pair< VoxelDimensions, double[] > > pair : attr.values() )
		{
			if ( firstScale == null )
			{
				firstScale = pair.getB().getA().dimensionsAsDoubleArray();
			}
			else
			{
				for ( int d = 0; d < firstScale.length; ++d )
					if ( firstScale[ d ] != pair.getB().getA().dimension( d ) )
						return false;
			}
		}

		return true;
	}

	@Override
	public OMEZARR newInstance()
	{
		return new OMEZARR();
	}

	protected static interface URIListChooser
	{
		public Pair< URI, List<String> > getDatasetList();
		public String getDescription();
		public URIListChooser getNewInstance();
	}

	protected static class MultipleOMEZARRListChooser implements URIListChooser
	{
		protected static int defaultNumDirs = 1;
		protected static String[] defaultLocations = null;
		protected static String defaultBasePath = "";

		@Override
		public Pair< URI, List<String> > getDatasetList()
		{
			final GenericDialog gd1 = new GenericDialog( "Number of datasets" );
			gd1.addNumericField( "Number of OME-ZARR's to choose", defaultNumDirs, 0);
			gd1.addMessage( "Note: All OME-ZARRs must have the same dimensionality (3D/4D/5D)", GUIHelper.smallStatusFont );
			gd1.addMessage( "Note: Datasets must be in one parent folder (local, cloud)", GUIHelper.smallStatusFont );

			gd1.showDialog();
			if ( gd1.wasCanceled() )
				return null;

			final int num = (int)Math.round( gd1.getNextNumber() );
			if ( num > 0 )
			{
				defaultNumDirs = num;
			}
			else
			{
				IOFunctions.println( "number must be >0." );
				return null;
			}

			if ( defaultLocations == null || defaultLocations.length != num )
				defaultLocations = new String[ num ];

			final GenericDialog gd2 = new GenericDialog( "Select datasets" );

			gd2.addDirectoryField( "Base_path", defaultBasePath, 80 );
			for ( int n = 0; n < num; ++n )
				gd2.addStringField( "OMEZARR_dataset_" + (n+1), defaultLocations[ n ], 40 );

			GUIHelper.addScrollBars( gd2 );

			gd2.showDialog();
			if ( gd2.wasCanceled() )
				return null;

			final ArrayList< String > datasetList = new ArrayList<>();

			try
			{
				final URI basePath = URITools.toURI( defaultBasePath = gd2.getNextString() );

				IOFunctions.println( "Base path = '" + defaultBasePath + "' (URI='" + basePath + "'" );

				for ( int n = 0; n < num; ++n )
				{
					datasetList.add( defaultLocations[ n ] = gd2.getNextString() );

					IOFunctions.println( "Including dataset '" + defaultLocations[ n ]  + "'"  );
				}

				return new ValuePair<>( basePath, datasetList );
			}
			catch ( Exception e )
			{
				IOFunctions.println( "Couldn't create URI: " + e );
				e.printStackTrace();
				return null;
			}
		}

		@Override
		public String getDescription() { return "Select OME-ZARR directorie(s) manually (works with local & cloud data)"; }

		@Override
		public URIListChooser getNewInstance() { return new MultipleOMEZARRListChooser(); }
	}

	protected static class WildcardDirectoryListChooser implements URIListChooser
	{
		private static int minNumLines = 10;
		private static String info = "<html> <h2>Select OME-ZARR directories via wildcard expression</h2> <br /> "
				+ "Specify a directory (the <b><u>parent dir</u></b> containing OME-ZARR directories) or click 'Browse...' <br /> <br />"
				+ "Wildcard (*) expressions are allowed, nothing equals '*', you can drag & drop directories, e.g. <br /><i>"
				+ "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;/Users/spim/data/spim_TL*_Angle*.ome.zarr <br />"
				+ "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;/Users/spim/data/ <br /><br />"
				+ "</i></html>";

		private static String previewFiles(List<File> files){
			StringBuilder sb = new StringBuilder();
			sb.append("<html><h2> selected directories </h2>");
			for (File f : files)
				sb.append( "<br />" + f.getAbsolutePath() );
			for (int i = 0; i < minNumLines - files.size(); i++)
				sb.append( "<br />"  );
			sb.append( "</html>" );
			return sb.toString();
		}

		public static Pair< String, List<File>> getDirectoriesFromPattern( final String pattern )
		{
			final Pair< String, String > pAndp =
					FileListDatasetDefinition.splitIntoPathAndPattern( pattern, FileListDatasetDefinition.GLOB_SPECIAL_CHARS );
			String path = pAndp.getA();
			String justPattern = pAndp.getB();

			List<File> paths = new ArrayList<>();
			PathMatcher pm;

			try
			{
				pm = FileSystems.getDefault().getPathMatcher( "glob:" + 
					((justPattern.length() == 0) ? path : String.join("/", path, justPattern )) );
			}
			catch (PatternSyntaxException e) {
				// malformed pattern, return empty list (for now)
				// if we do not catch this, we will keep logging exceptions e.g. while user is typing something like [0-9]
				return null;
			}

			if (!new File( path ).exists())
				return null;

			int numLevels = justPattern.split( "/" ).length;

			try
			{
				Files.walk( Paths.get( path ), numLevels ).filter( p -> pm.matches( p ) ).filter( new Predicate< Path >()
				{
					@Override
					public boolean test( final Path t )
					{
						try
						{
							// only directories
							return Files.isDirectory( t );
						}
						catch ( Exception e )
						{
							e.printStackTrace();
							return false;
						}
					}
				} )
				.forEach( p -> paths.add( new File( p.toString() )) );
			}
			catch ( IOException e ) { e.printStackTrace(); }

			Collections.sort( paths );

			return new ValuePair<>( path, paths );
		}

		@Override
		public Pair< URI, List<String> > getDatasetList()
		{
			final GenericDialogPlus gdp = new GenericDialogPlus("Pick OME-ZARR directories to include");

			FileListDatasetDefinition.addMessageAsJLabel(info, gdp);

			gdp.addDirectoryField( "path", "/", 65);

			// preview selected files - not possible in headless
			if (!PluginHelper.isHeadless())
			{
				// add empty preview
				FileListDatasetDefinition.addMessageAsJLabel(previewFiles( new ArrayList<>()), gdp,  GUIHelper.smallStatusFont);

				JLabel lab = (JLabel)gdp.getComponent( 3 );
				Panel pan = (Panel)gdp.getComponent( 2 );

				final AtomicBoolean autoset = new AtomicBoolean( false );

				((TextField)pan.getComponent( 0 )).addTextListener( new TextListener()
				{
					@Override
					public void textValueChanged(TextEvent e)
					{
						if ( autoset.get() == true )
						{
							autoset.set( false );

							return;
						}

						String path = ((TextField)pan.getComponent( 0 )).getText();

						// if macro recorder is running and we are on windows
						if ( FileListDatasetDefinition.windowsHack && ij.plugin.frame.Recorder.record && System.getProperty("os.name").toLowerCase().contains( "win" ) )
						{
							while( path.contains( "\\" ))
								path = path.replace( "\\", "/" );

							autoset.set( true );
							((TextField)pan.getComponent( 0 )).setText( path ); // will lead to a recursive call of textValueChanged(TextEvent e)
						}

						if (path.endsWith( File.separator ))
							path = path.substring( 0, path.length() - File.separator.length() );

						if(new File(path).isDirectory())
							path = String.join( File.separator, path, "*" );

						lab.setText( previewFiles( getDirectoriesFromPattern(path).getB()));
						lab.setSize( lab.getPreferredSize() );
						gdp.setSize( gdp.getPreferredSize() );
						gdp.validate();
					}
				} );
			}

			if ( FileListDatasetDefinition.windowsHack && ij.plugin.frame.Recorder.record && System.getProperty("os.name").toLowerCase().contains( "win" ) )
			{
				gdp.addMessage( "Warning: we are on Windows and the Macro Recorder is on, replacing all instances of '\\' with '/'\n"
						+ "   Disable it by opening the script editor, language beanshell, call:\n"
						+ "   net.preibisch.mvrecon.fiji.datasetmanager.FileListDatasetDefinition.windowsHack = false;", GUIHelper.smallStatusFont, Color.RED );
			}

			GUIHelper.addScrollBars( gdp );

			gdp.showDialog();

			if (gdp.wasCanceled())
				return null;

			String dirInput = defaultDir = gdp.getNextString();

			if (dirInput.endsWith( File.separator ))
				dirInput = dirInput.substring( 0, dirInput.length() - File.separator.length() );

			if (new File(dirInput).isDirectory())
				dirInput = String.join( File.separator, dirInput, "*" );

			final ArrayList< String > datasets = new ArrayList<>(); 
			final Pair< String, List<File>> files = getDirectoriesFromPattern( dirInput );

			final String base = files.getA().endsWith( File.separator ) ? files.getA() : files.getA() + File.separator;
			final URI baseDir = URITools.toURI( base );

			IOFunctions.println( "Base path = '" + base + "' (URI='" + baseDir + "'" );

			files.getB().forEach( f ->
			{
				if ( f.getAbsolutePath().indexOf( base ) != 0 )
				{
					IOFunctions.println( "Error, cannot find basepath '" + base + "' in '" + f.getAbsolutePath() + "'." );
				}
				else
				{
					String dataset = f.getAbsolutePath().substring( base.length() );
					datasets.add( dataset );

					IOFunctions.println( "Including dataset '" + dataset  + "'"  );
				}
			});

			return new ValuePair<>( baseDir, datasets );
		}

		@Override
		public String getDescription(){ return "Choose via wildcard expression (works with local/mounted data)"; }

		@Override
		public URIListChooser getNewInstance() { return new WildcardDirectoryListChooser(); }
		
	}

	public static void main( String[] args )
	{
		defaultDir = "/Users/preibischs/SparkTest/Stitching/dataset.ome.zarr";
		new ImageJ();
		new OMEZARR().createDataset( "test.xml" );
	}
}
