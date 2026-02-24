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

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;

import fiji.util.gui.GenericDialogPlus;
import ij.ImagePlus;
import mpicbg.spim.data.SpimDataIOException;
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
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBoxes;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.SmartSPIMImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;
import util.URITools;

public class SmartSPIM implements MultiViewDatasetDefinition
{
	public static String defaultMetadataFile = "";
	public static boolean defaultConfirmFiles = true;

	@Override
	public String getTitle()
	{
		return "SmartSPIM Datasets";
	}

	@Override
	public String getExtendedDescription()
	{
		return "This datset definition supports folder structures saved by SmartSPIM's.";
	}

	@Override
	public SpimData2 createDataset( final String xmlFileName )
	{
		final Pair< URI, Boolean > md = queryMetaDataFile();

		if ( md == null )
			return null;

		final SmartSPIMMetaData metadata = parseMetaDataFile( md.getA() );

		if ( metadata == null )
			return null;

		if ( !populateImageSize( metadata, md.getB() ) )
			return null;

		// assemble timepints, viewsetups, missingviews and the imgloader
		final TimePoints timepoints = this.createTimePoints( metadata );
		final ArrayList< ViewSetup > setups = this.createViewSetups( metadata, 1.0/10.0 );
		final MissingViews missingViews = new MissingViews( new ArrayList<>() );

		// instantiate the sequencedescription
		final SequenceDescription sequenceDescription = new SequenceDescription( timepoints, setups, null, missingViews );
		final ImgLoader imgLoader = new SmartSPIMImgLoader( metadata, sequenceDescription );
		sequenceDescription.setImgLoader( imgLoader );

		// get the minimal resolution of all calibrations
		final double minResolution = Math.min( metadata.xyRes, metadata.zRes );

		IOFunctions.println( "Minimal resolution in all dimensions is: " + minResolution );
		IOFunctions.println( "(The smallest resolution in any dimension; the distance between two pixels in the output image will be that wide)" );

		// create calibration + translation view registrations
		final ViewRegistrations viewRegistrations =
				DatasetCreationUtils.createViewRegistrations( sequenceDescription.getViewDescriptions(), minResolution );
		
		// create the initial view interest point object
		final ViewInterestPoints viewInterestPoints = new ViewInterestPoints();

		// finally create the SpimData itself based on the sequence description and the view registration
		final SpimData2 spimData = new SpimData2( metadata.dir, sequenceDescription, viewRegistrations, viewInterestPoints, new BoundingBoxes(), new PointSpreadFunctions(), new StitchingResults(), new IntensityAdjustments() );

		return spimData;
	}

	/*
	 * Creates the {@link TimePoints} for the {@link SpimData} object
	 */
	protected TimePoints createTimePoints( final SmartSPIMMetaData meta )
	{
		final ArrayList< TimePoint > timepoints = new ArrayList< TimePoint >();
		timepoints.add( new TimePoint( 0 ) );
		return new TimePoints( timepoints );
	}

	/*
	 * 
	 * @param meta the metadata object
	 * @param scaleFactor somehow the foldernames are not in um but 1/10 of um, so we need to scale by 1/10 to get approx positions
	 * @return
	 */
	protected ArrayList< ViewSetup > createViewSetups( final SmartSPIMMetaData meta, final double scaleFactor )
	{
		final ArrayList< Channel > channels = new ArrayList< Channel >();
		for ( int c = 0; c < meta.channels.size(); ++c )
			channels.add( new Channel( c, Integer.toString( meta.channels.get( c ).getA() ) ) );

		final ArrayList< Tile > tiles = new ArrayList<>();
		int i = 0;
		for ( int y = 0; y < meta.yTileLocations.size(); ++y )
			for ( int x = 0; x < meta.xTileLocations.size(); ++x )
			{
                if (meta.tileMap.get(meta.xTileLocations.get( x )).contains(meta.yTileLocations.get( y ))) {
                    final Tile tile = new Tile(i, "x" + x + "_y" + y);
                    tile.setLocation(new double[]{meta.xTileLocations.get(x) * scaleFactor, meta.yTileLocations.get(y) * scaleFactor, 0});

                    tiles.add(tile);
                    ++i;
                }
			}

		final ArrayList< ViewSetup > viewSetups = new ArrayList< ViewSetup >();
		for ( final Channel c : channels )
			for ( final Tile t : tiles )
			{
				final VoxelDimensions voxelSize = new FinalVoxelDimensions( "micrometer", meta.xyRes, meta.xyRes, meta.zRes );
				final Dimensions dim = new FinalDimensions( meta.dimensions );
				viewSetups.add( new ViewSetup( viewSetups.size(), null, dim, voxelSize, t, c, new Angle( 0 ), new Illumination( 0 ) ) );
			}

		return viewSetups;
	}

	public static boolean populateImageSize( final SmartSPIMMetaData metadata, final boolean confirmAllImages )
	{
		metadata.dimensions = null;
		metadata.sortedFileNames = null;

		for ( int channel = 0; channel < metadata.channels.size(); ++ channel )
			for ( int xTile = 0; xTile < metadata.xTileLocations.size(); ++ xTile)
				for ( int yTile = 0; yTile < metadata.yTileLocations.size(); ++ yTile)
				{
                    if (metadata.tileMap.get(metadata.xTileLocations.get( xTile ))
                            .contains(metadata.yTileLocations.get( yTile ))) {
                        final URI imageDir = metadata.folderFor(
                                metadata.channels.get( channel ),
                                metadata.xTileLocations.get( xTile ),
                                metadata.yTileLocations.get( yTile ) );

                        IOFunctions.println( "Directory: " + imageDir );

                        final Pair< long[], List< String > > stackData =
                                metadata.loadImageSize( channel, xTile, yTile );

                        final long[] dimensions = stackData.getA();

                        IOFunctions.println( "dimensions: " + Util.printCoordinates( dimensions ) );

                        if ( dimensions == null )
                            return false;

                        if ( metadata.dimensions == null )
                        {
                            metadata.dimensions = dimensions;
                            metadata.sortedFileNames = stackData.getB();
                        }

                        if ( !confirmAllImages )
                            return true;

                        if ( !Arrays.equals( metadata.dimensions, dimensions ) )
                        {
                            IOFunctions.println( "dimensions are not equal. Stopping: " + Util.printCoordinates( dimensions ) + ", " + Util.printCoordinates( metadata.dimensions ) );
                            return false;
                        }

                        if ( !areListsEqual( metadata.sortedFileNames, stackData.getB() ) )
                        {
                            IOFunctions.println( "file names are not equal. Stopping." );
                            return false;
                        }

                    }
                }

		return true;
	}

	public static SmartSPIMMetaData parseMetaDataFile( final URI md )
	{
		IOFunctions.println( "Parsing: " + md + " ... ");

		final SmartSPIMMetaData metadata;

		try
		{
			metadata = new SmartSPIMMetaData( md );
		}
		catch (SpimDataIOException e)
		{
			IOFunctions.println( "Cannot extract directory for '" + md + "': " + e );
			e.printStackTrace();
			return null;
		}

		if ( !URITools.isFile( md ) )
		{
			IOFunctions.println( "So far only local file systems are supported.");
			return null;
		}

		final File file = new File( md );

		if ( !file.exists() )
		{
			IOFunctions.println( "Error: " + file + " does not exist.");
			return null;
		}

		// Open the file using a GsonReader
		try (JsonReader reader = new JsonReader( new FileReader( file ) ) )
		{
			final Gson gson = new Gson();

			final JsonElement e = gson.fromJson(reader, (JsonElement.class ));
			final JsonElement session_config = e.getAsJsonObject().get("session_config");
			final JsonElement tile_config = e.getAsJsonObject().get("tile_config");

			// we cannot directly de-serialize the session_config because it contains special characters in the field names
			final Map<String, JsonElement> sessionMap = session_config.getAsJsonObject().asMap();

			for ( final Entry< String, JsonElement > entry : sessionMap.entrySet() )
			{
				if ( entry.getKey().contains( "Z step" ) )
					metadata.zRes = Double.parseDouble( entry.getValue().getAsString() );

				if ( entry.getKey().contains( "m/pix" ) )
					metadata.xyRes = Double.parseDouble( entry.getValue().getAsString() );
			}

			IOFunctions.println( "resolution: " + metadata.xyRes + " x " + metadata.xyRes + " x " + metadata.zRes + " um/px." );

			final Type typeTileConfig = new TypeToken<HashMap<String, SmartSPIM_Tile>>() {}.getType();
			HashMap<String, SmartSPIM_Tile> tiles = gson.fromJson(tile_config, typeTileConfig);

			IOFunctions.println( "number of tiles: " + tiles.size() );

			metadata.channels = SmartSPIM_Tile.channels( tiles.values() );
            metadata.tileMap = SmartSPIM_Tile.tileMap( tiles.values() );
			metadata.xTileLocations = SmartSPIM_Tile.xTileLocations( tiles.values() );
			metadata.yTileLocations = SmartSPIM_Tile.yTileLocations( tiles.values() );
			metadata.zOffsets = SmartSPIM_Tile.zOffsets( tiles.values() );

			IOFunctions.println( "channels: " );
			metadata.channels.forEach( p -> IOFunctions.println( "\t" + SmartSPIM_Tile.channelToFolderName( p ) ) );

			IOFunctions.println( "x tile locations: " );
			metadata.xTileLocations.forEach( x -> IOFunctions.println( "\t" + x ) );

			IOFunctions.println( "y tile locations: " );
			metadata.yTileLocations.forEach( y -> IOFunctions.println( "\t" + y ) );

			IOFunctions.println( "z offset(s): " );
			metadata.zOffsets.forEach( y -> IOFunctions.println( "\t" + y ) );

			if ( metadata.zOffsets.size() != 1 )
			{
				IOFunctions.println( "multiple z offsets not supported yet. please contact developers." );
				return null;
			}
		}
		catch (IOException e)
		{
			IOFunctions.println("Error reading the file: " + e.getMessage());
			return null;
		}

		return metadata;
	}

	protected Pair< URI, Boolean > queryMetaDataFile()
	{
		GenericDialogPlus gd = new GenericDialogPlus( "Define SmartSPIM Dataset" );

		gd.addFileField( "SmartSPIM_metadata.json file", defaultMetadataFile, 50 );
		gd.addCheckbox( "Confirm_presence of all folders & files", defaultConfirmFiles );
		gd.addMessage( "Note: for now we assume the same dimensions of all tiles & channels", GUIHelper.smallStatusFont, GUIHelper.neutral );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return null;

		final URI metaDataFile = URITools.toURI( defaultMetadataFile = gd.getNextString() );
		final boolean confirmFiles = defaultConfirmFiles = gd.getNextBoolean();

		return new ValuePair<>( metaDataFile, confirmFiles );
	}

	@Override
	public MultiViewDatasetDefinition newInstance()
	{
		return new SmartSPIM();
	}


	public static boolean areListsEqual( final List< String > array1, final List< String > array2 )
	{
		if (array1 == null || array2 == null)
			return false;

		if (array1.size() != array2.size() )
			return false;

		for ( int i = 0; i < array1.size(); ++i )
			if ( !array1.get( i ).equals(array2.get( i )) )
				return false;

		return true;
	}

	public static class SmartSPIMMetaData
	{
		final public URI metadataFile, dir;

		public long[] dimensions;

		public List< String > sortedFileNames;

		public double xyRes = -1;
		public double zRes = -1;

		public List<Pair<Integer, Integer>> channels;
		public List<Long> xTileLocations;
		public List<Long> yTileLocations;
		public List<Long> zOffsets;
        public HashMap<Long, Set<Long>> tileMap;

		public SmartSPIMMetaData( final URI metadataFile ) throws SpimDataIOException
		{
			this.metadataFile = metadataFile;
			this.dir = URITools.getParentURI( this.metadataFile );
		}

		public URI folderFor( final Pair<Integer, Integer> channel, final long xTile, final long yTile )
		{
			return folderFor( dir, channel, xTile, yTile );
		}

		public List< String > sortedSlicesFor( final Pair<Integer, Integer> channel, final long xTile, final long yTile )
		{
			final URI dir = folderFor( channel, xTile, yTile );

			return sortedSlicesFor( dir );
		}

		public ImagePlus loadImage( final Pair<Integer, Integer> channel, final long xTile, final long yTile, final String fileName )
		{
			return loadImage( dir, channel, xTile, yTile, fileName );
		}

		protected Pair< long[], List< String > > loadImageSize( final int channel, final int xTile, final int yTile )
		{
			return loadImageSize( this, channel, xTile, yTile );
		}

		public List< String > sortedSlicesFor( final URI dir )
		{
			return Arrays
				.asList(new File(dir).list((directory, name) -> name.toLowerCase().matches("\\d+\\.tif{1,2}")))
				.stream().sorted()
				.collect(Collectors.toList());
		}

		protected static Pair< long[], List< String > > loadImageSize( final SmartSPIMMetaData metadata, final int channel, final int xTile, final int yTile )
		{
			final URI imageDir = metadata.folderFor(
					metadata.channels.get( channel ),
					metadata.xTileLocations.get( xTile ),
					metadata.yTileLocations.get( yTile ) );

			final List<String> files = metadata.sortedSlicesFor( imageDir );
			final ImagePlus imp = SmartSPIMMetaData.loadImage( imageDir, files.get( 0 ) );

			if ( imp.getProcessor() == null )
			{
				IOFunctions.println( "Failed to load image. Stopping." );
				return null;
			}

			final long[] dim =  new long[] { imp.getWidth(), imp.getHeight(), files.size() };

			imp.close();

			return new ValuePair<>( dim, files );
		}

		public static URI folderFor( final URI dir, final Pair<Integer, Integer> channel, final long xTile, final long yTile )
		{
			return dir.resolve( SmartSPIM_Tile.channelToFolderName( channel ) + "/" + xTile + "/" + xTile + "_" + yTile + "/" );
		}

		public static ImagePlus loadImage( final URI dir, final Pair<Integer, Integer> channel, final long xTile, final long yTile, final String fileName )
		{
			final URI imageDir = SmartSPIMMetaData.folderFor( dir, channel, xTile, yTile );
			
			return loadImage( imageDir, fileName );
		}

		public static ImagePlus loadImage( final URI imageDir, final String fileName )
		{
			final File file = new File( imageDir.resolve( fileName ) );
			final ImagePlus imp = new ImagePlus( file.getAbsolutePath() );

			return imp;
		}
	}

	private static class SmartSPIM_Tile
	{
		public long X;
		public long Y;
		public long Z;
		public int Laser;
		public int Side;
		public int Exposure;
		public int Skip;
		public int Filter;

		public static HashMap<Long, Set<Long>> tileMap( final Collection< SmartSPIM_Tile > tiles )
		{
            HashMap<Long, Set<Long>> getTiles = new HashMap<Long, Set<Long>>(xTileLocations(tiles).size());
            for (SmartSPIM_Tile t : tiles) {
                if (t.Skip == 1) {
                    getTiles.computeIfAbsent(t.X, k -> new HashSet<>()).add(t.Y);
                }
            }

            System.out.println("Valid X/Y Combos:");
            getTiles.forEach((key, value) -> {
                System.out.println("X: " + key + ", Ys: " + value);
            });
            return getTiles;
		}

        public static List< Long > xTileLocations( final Collection< SmartSPIM_Tile > tiles )
        {
            return tiles.stream().map( t -> t.X ).distinct().sorted().collect(Collectors.toList());
        }

		public static List< Long > yTileLocations( final Collection< SmartSPIM_Tile > tiles )
		{
			return tiles.stream().map( t -> t.Y ).distinct().sorted().collect(Collectors.toList());
		}

		public static List< Long > zOffsets( final Collection< SmartSPIM_Tile > tiles )
		{
			return tiles.stream().map( t -> t.Z ).distinct().sorted().collect(Collectors.toList());
		}

		public static List< Pair< Integer, Integer > > channels( final Collection< SmartSPIM_Tile > tiles )
		{
			return tiles.stream().map( t -> new ValuePair<>( t.Laser, t.Filter ) ).distinct().sorted( (o1,o2) -> o1.a.compareTo( o2.a ) ).collect(Collectors.toList());
		}

		public static String channelToFolderName( final Pair< Integer, Integer > channel )
		{
			return "Ex_" + channel.getA() + "_Ch" + channel.getB();
		}
	}

	public static void main( String[] args )
	{
		//parseMetaDataFile( URITools.toURI("/Users/preibischs/Documents/Janelia/Projects/BigStitcher/SmartSPIM/metadata.json"));
		SmartSPIMMetaData metadata =
				parseMetaDataFile( URITools.toURI( "/Volumes/johnsonlab/LM/20240826_15_33_26_RJ_mouse_1_anterior_ventral_Destripe_DONE/metadata.json") );

		//populateImageSize( metadata, true );
	}
}
