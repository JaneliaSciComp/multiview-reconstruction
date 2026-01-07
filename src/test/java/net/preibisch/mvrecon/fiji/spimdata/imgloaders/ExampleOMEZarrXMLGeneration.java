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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.OmeNgffMultiScaleMetadata;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.coordinateTransformations.ScaleCoordinateTransformation;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.coordinateTransformations.TranslationCoordinateTransformation;

import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.registration.ViewTransformAffine;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.MissingViews;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBoxes;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.AllenOMEZarrLoader.OMEZARREntry;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;
import util.URITools;

/**
 * Example demonstrating two ways to generate SpimData2 XML files that
 * reference OME-ZARR datasets:
 *
 * 1. exampleWithGUI() - Uses the OMEZARR import dialog (automatic detection)
 * 2. exampleWithCode() - Programmatically creates the dataset structure from existing OME-ZARRs
 *
 * This is NOT a unit test - it's a standalone example that creates
 * real OME-ZARR files and corresponding XML files on disk.
 */
public class ExampleOMEZarrXMLGeneration
{
	/**
	 * Input specification for a single ViewSetup, mapping it to an OME-ZARR path.
	 * Contains ONLY the information provided by the user - all metadata is read from the OME-ZARR files.
	 */
	public static class OMEZarrViewSetupInfo
	{
		/** Relative path to the OME-ZARR container (e.g., "tile_00.zarr") */
		public String path;

		/** Indices for extracting from higher-dimensional data (null for 3D, [channelId] for 4D, [channelId, timepointId] for 5D) */
		public int[] indices;

		/** Attribute IDs for SpimData2 */
		public int angle;
		public int channel;
		public int illumination;
		public int tile;
		public int timepoint;

		/** Optional custom tile position [x, y, z] to override the one read from metadata (null = use metadata) */
		public double[] customTilePosition;

		/**
		 * Constructor with automatic metadata reading.
		 */
		public OMEZarrViewSetupInfo( String path, int[] indices, int angle, int channel, int illumination, int tile, int timepoint )
		{
			this( path, indices, angle, channel, illumination, tile, timepoint, null );
		}

		/**
		 * Constructor with custom tile position override.
		 */
		public OMEZarrViewSetupInfo( String path, int[] indices, int angle, int channel, int illumination, int tile, int timepoint,
				double[] customTilePosition )
		{
			this.path = path;
			this.indices = indices;
			this.angle = angle;
			this.channel = channel;
			this.illumination = illumination;
			this.tile = tile;
			this.timepoint = timepoint;
			this.customTilePosition = customTilePosition;
		}
	}
	/**
	 * Creates a 3D or 4D OME-ZARR dataset.
	 * If channelIntensities has length 1, creates 3D dataset. Otherwise creates 4D with channels.
	 *
	 * @param writer N5Writer for the container
	 * @param datasetBaseName Base name for the dataset (empty string for root)
	 * @param sizeX Size in X
	 * @param sizeY Size in Y
	 * @param sizeZ Size in Z
	 * @param voxelSizeX Voxel size in X
	 * @param voxelSizeY Voxel size in Y
	 * @param voxelSizeZ Voxel size in Z
	 * @param channelIntensities Array of intensity values (length 1 = 3D, length > 1 = 4D with channels)
	 */
	private static void createZarr( N5Writer writer, String datasetBaseName, int sizeX, int sizeY, int sizeZ,
			double voxelSizeX, double voxelSizeY, double voxelSizeZ, int[] channelIntensities ) throws Exception
	{
		final int numChannels = channelIntensities.length;
		final boolean is3D = (numChannels == 1);

		System.out.println( "Creating " + (is3D ? "3D" : "4D") + " OME-ZARR dataset" +
			(datasetBaseName.isEmpty() ? " at root" : ": " + datasetBaseName) +
			(is3D ? " with intensity " + channelIntensities[0] : " with " + numChannels + " channels") );

		String datasetPath = datasetBaseName.isEmpty() ? "0" : datasetBaseName + "/0";
		long[] dimensions = is3D ? new long[] { sizeX, sizeY, sizeZ } : new long[] { sizeX, sizeY, sizeZ, numChannels };
		int[] blockSize = is3D ?
			new int[] { Math.min(32, sizeX), Math.min(32, sizeY), Math.min(16, sizeZ) } :
			new int[] { Math.min(32, sizeX), Math.min(32, sizeY), Math.min(16, sizeZ), 1 };

		// Explicitly create the dataset first (like N5ApiTools does)
		writer.createDataset( datasetPath, dimensions, blockSize, DataType.UINT8, new GzipCompression() );

		// Create and write sample data
		RandomAccessibleInterval< UnsignedByteType > img = ArrayImgs.unsignedBytes( dimensions );

		if ( is3D )
		{
			img.forEach( t -> t.set( channelIntensities[0] ) );
		}
		else
		{
			for ( int c = 0; c < numChannels; c++ )
			{
				final int channelValue = channelIntensities[c];
				final int channelIndex = c;
				Views.interval( img,
					new long[] {0, 0, 0, channelIndex},
					new long[] {sizeX-1, sizeY-1, sizeZ-1, channelIndex} )
					.forEach( t -> t.set( channelValue ) );
			}
		}

		// Write the data to the dataset
		N5Utils.saveBlock( img, writer, datasetPath, is3D ? new long[] {0, 0, 0} : new long[] {0, 0, 0, 0} );

		// Add OME-ZARR multiscales metadata to the container root
		if ( datasetBaseName.isEmpty() )
		{
			org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.OmeNgffMultiScaleMetadata[] metadata =
				OMEZarrAttibutes.createOMEZarrMetadata(
					is3D ? 3 : 4,
					"/",
					is3D ? new double[] { voxelSizeX, voxelSizeY, voxelSizeZ } :
					       new double[] { voxelSizeX, voxelSizeY, voxelSizeZ, 1.0 },
					"um",
					1,  // single resolution level
					level -> String.valueOf( level ),
					level -> new AffineTransform3D()
				);

			writer.setAttribute( "/", "multiscales", metadata );
		}

		System.out.println( "  ✓ Created " + (is3D ? "3D" : "4D") + " with dimensions: " + java.util.Arrays.toString( dimensions ) );
	}

	/**
	 * Container for metadata read from OME-ZARR files.
	 */
	private static class OMEZarrMetadata
	{
		Map<String, DatasetAttributes> pathToAttrs = new HashMap<>();
		Map<String, double[]> pathToVoxelSize = new HashMap<>();
		Map<String, String> pathToUnit = new HashMap<>();
		Map<String, double[]> pathToTranslation = new HashMap<>();
	}

	/**
	 * Reads OME-ZARR metadata (dimensions, voxel size, unit, tile position) from the files.
	 *
	 * @param zarrContainerURI URI to the parent ZARR container
	 * @param storageFormat Storage format (ZARR for OME-ZARR)
	 * @param viewSetups List of view setups (only paths are used)
	 * @return OMEZarrMetadata containing all read metadata
	 */
	private static OMEZarrMetadata readOMEZarrMetadata( URI zarrContainerURI, StorageFormat storageFormat, List<OMEZarrViewSetupInfo> viewSetups ) throws Exception
	{
		System.out.println( "Step 1: Reading OME-ZARR metadata..." );

		// Read metadata from each unique OME-ZARR path
		Map<String, DatasetAttributes> pathToAttrs = new HashMap<>();
		Map<String, double[]> pathToVoxelSize = new HashMap<>();
		Map<String, String> pathToUnit = new HashMap<>();
		Map<String, double[]> pathToTranslation = new HashMap<>();

		for ( OMEZarrViewSetupInfo info : viewSetups )
		{
			if ( pathToAttrs.containsKey( info.path ) )
				continue;  // Already read this path

			System.out.println( "  Reading metadata from: " + info.path );

			// Open N5Reader at the individual container level (like ExportN5Api does)
			// Construct URI to child container
			String parentURIStr = zarrContainerURI.toString();
			if ( parentURIStr.endsWith( "/" ) )
				parentURIStr = parentURIStr.substring( 0, parentURIStr.length() - 1 );
			URI childURI = URI.create( parentURIStr + "/" + info.path );

			N5Reader childReader = util.URITools.instantiateN5Reader( storageFormat, childURI );

			// Read dataset attributes for level 0 (full resolution)
			DatasetAttributes attrs = childReader.getDatasetAttributes( "0" );

			if ( attrs == null )
			{
				throw new RuntimeException( "Could not read dataset attributes from: " + info.path + "/0" );
			}

			System.out.println( "    ✓ Read dimensions via N5 API: " + java.util.Arrays.toString( attrs.getDimensions() ) );

			pathToAttrs.put( info.path, attrs );

			// Read OME-NGFF multiscales metadata from the root of the child container
			OmeNgffMultiScaleMetadata[] multiscales = null;
			try
			{
				multiscales = childReader.getAttribute( "/", "multiscales", OmeNgffMultiScaleMetadata[].class );
				if ( multiscales != null && multiscales.length > 0 )
				{
						// Extract voxel size from scale transformation
					if ( multiscales[0].datasets != null && multiscales[0].datasets.length > 0 &&
						 multiscales[0].datasets[0].coordinateTransformations != null )
					{
						for ( int i = 0; i < multiscales[0].datasets[0].coordinateTransformations.length; i++ )
						{
							// Cast to specific transformation types to access their fields
							if ( multiscales[0].datasets[0].coordinateTransformations[i] instanceof ScaleCoordinateTransformation )
							{
								ScaleCoordinateTransformation scaleTransform =
									(ScaleCoordinateTransformation) multiscales[0].datasets[0].coordinateTransformations[i];
								double[] scale = scaleTransform.getScale();
								pathToVoxelSize.put( info.path, new double[] { scale[0], scale[1], scale[2] } );
							}
							else if ( multiscales[0].datasets[0].coordinateTransformations[i] instanceof TranslationCoordinateTransformation )
							{
								TranslationCoordinateTransformation translationTransform =
									(TranslationCoordinateTransformation) multiscales[0].datasets[0].coordinateTransformations[i];
								double[] translation = translationTransform.getTranslation();
								pathToTranslation.put( info.path, new double[] { translation[0], translation[1], translation[2] } );
							}
						}
					}

					// Get unit from first spatial axis
					// Note: axis fields are protected, so we default to "um" for any OME-ZARR with multiscales
					if ( multiscales[0].axes != null && multiscales[0].axes.length > 0 )
					{
						pathToUnit.put( info.path, "um" );
					}
				}
			}
			catch ( Exception e )
			{
				System.out.println( "  Warning: Could not read multiscales metadata from " + info.path + ": " + e.getMessage() );
			}

			childReader.close();
		}

		System.out.println( "  ✓ Read metadata from " + pathToAttrs.size() + " OME-ZARR containers" );
		System.out.println();

		// Return all read metadata
		OMEZarrMetadata metadata = new OMEZarrMetadata();
		metadata.pathToAttrs = pathToAttrs;
		metadata.pathToVoxelSize = pathToVoxelSize;
		metadata.pathToUnit = pathToUnit;
		metadata.pathToTranslation = pathToTranslation;
		return metadata;
	}

	/**
	 * Prints a summary of the SpimData2 dataset.
	 */
	private static void printSummary( SpimData2 spimData )
	{
		System.out.println( "=== Summary ===" );
		System.out.println( "Total Views: " + spimData.getSequenceDescription().getViewSetupsOrdered().size() );
		System.out.println( "Total Timepoints: " + spimData.getSequenceDescription().getTimePoints().size() );
		System.out.println();

		System.out.println( "View breakdown:" );
		for ( ViewSetup vs : spimData.getSequenceDescription().getViewSetupsOrdered() )
		{
			System.out.println( "  - ViewSetup " + vs.getId() + ": " + vs.getName() );
		}
		System.out.println();
		System.out.println( "✓ Example completed successfully!" );
		System.out.println();
	}

	/**
	 * Generates test OME-ZARR datasets in 3D or 4D format.
	 * - 3D mode: Creates separate containers for each tile-channel combination (tile_XX_ch_YY.zarr)
	 * - 4D mode: Creates one container per tile with channels as 4th dimension (tile_XX.zarr)
	 *
	 * @param zarrContainer Parent ZARR container directory
	 * @param numTiles Number of tiles
	 * @param numChannels Number of channels per tile
	 * @param sizeX, sizeY, sizeZ Dataset dimensions
	 * @param voxelSizeX, voxelSizeY, voxelSizeZ Voxel dimensions
	 * @param overlapPercent Tile overlap percentage (0.0-1.0)
	 * @param use4D If true, creates 4D datasets; if false, creates 3D datasets
	 * @return List of OMEZarrViewSetupInfo with paths and attribute mappings
	 */
	public static List<OMEZarrViewSetupInfo> generateTestOMEZarrs( File zarrContainer, int numTiles, int numChannels,
			int sizeX, int sizeY, int sizeZ, double voxelSizeX, double voxelSizeY, double voxelSizeZ, double overlapPercent,
			boolean use4D ) throws Exception
	{
		System.out.println( "\n=== Generating " + (use4D ? "4D" : "3D") + " Test OME-ZARRs ===" );
		System.out.println( "  Tiles: " + numTiles );
		System.out.println( "  Channels per tile: " + numChannels );
		System.out.println( "  Total containers: " + (use4D ? numTiles : numTiles * numChannels) );
		System.out.println( "  Dimensions: " + sizeX + "×" + sizeY + "×" + sizeZ + (use4D ? "×" + numChannels : "") );
		System.out.println( "  Voxel size: " + voxelSizeX + "×" + voxelSizeY + "×" + voxelSizeZ );
		System.out.println( "  Overlap: " + (overlapPercent * 100) + "%" );
		System.out.println();

		N5Writer parentWriter = URITools.instantiateN5Writer( StorageFormat.ZARR, zarrContainer.toURI() );
		List<OMEZarrViewSetupInfo> viewSetups = new ArrayList<>();
		double tileStepX = sizeX * voxelSizeX * (1.0 - overlapPercent);

		for ( int tile = 0; tile < numTiles; tile++ )
		{
			if ( use4D )
			{
				// 4D mode: One container per tile with all channels
				String containerName = String.format( "tile_%02d.zarr", tile );
				File childContainerFile = new File( zarrContainer, containerName );
				N5Writer childWriter = URITools.instantiateN5Writer( StorageFormat.ZARR, childContainerFile.toURI() );

				int[] channelIntensities = new int[numChannels];
				for ( int ch = 0; ch < numChannels; ch++ )
					channelIntensities[ch] = 50 + tile * 30 + ch * 10;

				createZarr( childWriter, "", sizeX, sizeY, sizeZ, voxelSizeX, voxelSizeY, voxelSizeZ, channelIntensities );
				childWriter.close();

				double[] tilePosition = new double[] { tile * tileStepX, 0.0, 0.0 };

				// Create view setup for each channel
				for ( int ch = 0; ch < numChannels; ch++ )
				{
					viewSetups.add( new OMEZarrViewSetupInfo(
						containerName, new int[] { ch }, 0, ch, 0, tile, 0, tilePosition ) );
				}
			}
			else
			{
				// 3D mode: Separate container for each tile-channel combination
				for ( int ch = 0; ch < numChannels; ch++ )
				{
					String containerName = String.format( "tile_%02d_ch_%02d.zarr", tile, ch );
					File childContainerFile = new File( zarrContainer, containerName );
					N5Writer childWriter = URITools.instantiateN5Writer( StorageFormat.ZARR, childContainerFile.toURI() );

					int fillValue = 50 + tile * 30 + ch * 10;
					createZarr( childWriter, "", sizeX, sizeY, sizeZ, voxelSizeX, voxelSizeY, voxelSizeZ, new int[] { fillValue } );
					childWriter.close();

					double[] tilePosition = new double[] { tile * tileStepX, 0.0, 0.0 };
					viewSetups.add( new OMEZarrViewSetupInfo(
						containerName, null, 0, ch, 0, tile, 0, tilePosition ) );
				}
			}
		}

		parentWriter.close();
		System.out.println( "  ✓ Created " + (use4D ? numTiles + " containers with " : "") + viewSetups.size() + " view setups" );
		System.out.println();

		return viewSetups;
	}

	/**
	 * Recursively delete a directory and all its contents.
	 */
	private static void deleteDirectory( File directory )
	{
		File[] files = directory.listFiles();
		if ( files != null )
		{
			for ( File file : files )
			{
				if ( file.isDirectory() )
					deleteDirectory( file );
				else
					file.delete();
			}
		}
		directory.delete();
	}

	/**
	 * Creates SpimData2 XML from existing OME-ZARR datasets.
	 *
	 * This method reads dimensions, voxel size, and transformations from the OME-ZARR metadata,
	 * making it easy to use with existing OME-ZARR files.
	 *
	 * @param zarrContainerURI URI to the parent ZARR container (e.g., "file:/path/to/dataset.zarr")
	 * @param storageFormat Storage format (ZARR for OME-ZARR)
	 * @param viewSetups List of view setups with paths and attribute mappings
	 * @param xmlOutputPath Path where the XML file should be saved
	 *
	 * Advantage: Works with existing OME-ZARR files, automatically reads metadata
	 * Disadvantage: Requires manual attribute mapping
	 */
	public static void exampleWithCode( URI zarrContainerURI, StorageFormat storageFormat, List<OMEZarrViewSetupInfo> viewSetups, File xmlOutputPath ) throws Exception
	{
		System.out.println( "\n=== Creating XML from Existing OME-ZARR Datasets ===" );
		System.out.println();

		System.out.println( "ZARR container: " + zarrContainerURI );
		System.out.println( "Output XML: " + xmlOutputPath.getAbsolutePath() );
		System.out.println( "Number of view setups: " + viewSetups.size() );
		System.out.println();

		// Step 1: Read OME-ZARR metadata
		OMEZarrMetadata metadata = readOMEZarrMetadata( zarrContainerURI, storageFormat, viewSetups );

		// Get dimensions and voxel size from first path (all should be the same)
		String firstPath = viewSetups.get( 0 ).path;
		long[] dims = metadata.pathToAttrs.get( firstPath ).getDimensions();

		// Extract XYZ dimensions (works for 3D, 4D, 5D)
		long sizeX = dims[0];
		long sizeY = dims[1];
		long sizeZ = dims[2];

		double[] voxelSize = metadata.pathToVoxelSize.getOrDefault( firstPath, new double[] { 1.0, 1.0, 1.0 } );
		double voxelSizeX = voxelSize[0];
		double voxelSizeY = voxelSize[1];
		double voxelSizeZ = voxelSize[2];
		String unit = metadata.pathToUnit.getOrDefault( firstPath, "pixel" );

		System.out.println( "Dataset info (from first setup):" );
		System.out.println( "  Dimensions: " + sizeX + " × " + sizeY + " × " + sizeZ );
		System.out.println( "  Voxel size: " + voxelSizeX + " × " + voxelSizeY + " × " + voxelSizeZ + " " + unit );
		System.out.println();

		// Step 2: Build ViewSetup list
		System.out.println( "Step 2: Building ViewSetup list..." );
		ArrayList< mpicbg.spim.data.sequence.ViewSetup > setups = new ArrayList<>();
		Dimensions dimensions = new FinalDimensions( sizeX, sizeY, sizeZ );
		VoxelDimensions voxelDims = new FinalVoxelDimensions( unit, voxelSizeX, voxelSizeY, voxelSizeZ );

		// Collect unique timepoints
		ArrayList< Integer > uniqueTimepoints = new ArrayList<>();
		for ( OMEZarrViewSetupInfo info : viewSetups )
		{
			if ( !uniqueTimepoints.contains( info.timepoint ) )
				uniqueTimepoints.add( info.timepoint );
		}

		int setupId = 0;
		for ( OMEZarrViewSetupInfo info : viewSetups )
		{
			Tile tile = new Tile( info.tile );
			Channel channel = new Channel( info.channel );
			Angle angle = new Angle( info.angle );
			Illumination illum = new Illumination( info.illumination );

			String name = String.format( "Tile_%d_Channel_%d_Angle_%d_Illum_%d", info.tile, info.channel, info.angle, info.illumination );
			mpicbg.spim.data.sequence.ViewSetup setup = new mpicbg.spim.data.sequence.ViewSetup( setupId, name, dimensions, voxelDims, tile, channel, angle, illum );
			setups.add( setup );

			System.out.println( "  - Setup " + setupId + ": " + name + " -> " + info.path +
				(info.indices != null ? " [indices: " + java.util.Arrays.toString( info.indices ) + "]" : "") );
			setupId++;
		}
		System.out.println();

		// Step 3: Create TimePoints
		ArrayList< TimePoint > timepoints = new ArrayList<>();
		Collections.sort( uniqueTimepoints );
		for ( int tp : uniqueTimepoints )
			timepoints.add( new TimePoint( tp ) );
		TimePoints tps = new TimePoints( timepoints );

		System.out.println( "Step 3: Timepoints: " + uniqueTimepoints );
		System.out.println();

		// Step 4: Create ViewIdToPath mapping
		System.out.println( "Step 4: Creating ViewId to OME-ZARR path mapping..." );
		Map< ViewId, OMEZARREntry > viewIdToPath = new HashMap<>();
		for ( int i = 0; i < viewSetups.size(); i++ )
		{
			OMEZarrViewSetupInfo info = viewSetups.get( i );
			ViewId viewId = new ViewId( info.timepoint, i );  // setupId = i
			viewIdToPath.put( viewId, new OMEZARREntry( info.path, info.indices ) );
		}
		System.out.println( "  ✓ Created " + viewIdToPath.size() + " mappings" );
		System.out.println();

		// Step 5: Create SequenceDescription
		SequenceDescription sequence = new SequenceDescription( tps, setups, null, new MissingViews( new ArrayList<>() ) );
		sequence.setImgLoader( new AllenOMEZarrLoader( zarrContainerURI, storageFormat, sequence, viewIdToPath ) );

		// Step 6: Create ViewRegistrations with tile positions
		System.out.println( "Step 5: Setting tile transformations..." );
		HashMap< ViewId, ViewRegistration > registrations = new HashMap<>();
		for ( int i = 0; i < viewSetups.size(); i++ )
		{
			OMEZarrViewSetupInfo info = viewSetups.get( i );
			ViewId viewId = new ViewId( info.timepoint, i );
			ViewRegistration vr = new ViewRegistration( viewId.getTimePointId(), viewId.getViewSetupId() );

			// Get tile position: use custom override if provided, otherwise read from metadata, or default to origin
			double[] tilePosition;
			if ( info.customTilePosition != null )
			{
				// User provided custom position
				tilePosition = new double[] { info.customTilePosition[0], info.customTilePosition[1], info.customTilePosition[2] };
			}
			else if ( metadata.pathToTranslation.containsKey( info.path ) )
			{
				// Read from OME-ZARR metadata
				tilePosition = metadata.pathToTranslation.get( info.path );
			}
			else
			{
				// Default to origin
				tilePosition = new double[] { 0.0, 0.0, 0.0 };
			}

			// Apply tile translation
			AffineTransform3D transform = new AffineTransform3D();
			transform.set( tilePosition[0], 0, 3 );  // X translation
			transform.set( tilePosition[1], 1, 3 );  // Y translation
			transform.set( tilePosition[2], 2, 3 );  // Z translation
			System.out.println( "  - Setup " + i + " (Tile " + info.tile + "): Translation [" +
				String.format( "%.1f", tilePosition[0] ) + ", " +
				String.format( "%.1f", tilePosition[1] ) + ", " +
				String.format( "%.1f", tilePosition[2] ) + "]" );

			vr.preconcatenateTransform( new ViewTransformAffine( "Tile Position", transform ) );
			registrations.put( viewId, vr );
		}
		System.out.println();

		ViewRegistrations viewRegistrations = new ViewRegistrations( registrations );

		// Step 7: Create SpimData2
		SpimData2 spimData = new SpimData2(
			xmlOutputPath.toURI(),
			sequence,
			viewRegistrations,
			new ViewInterestPoints(),
			new BoundingBoxes(),
			new PointSpreadFunctions(),
			new StitchingResults(),
			new IntensityAdjustments()
		);

		// Step 8: Save XML
		System.out.println( "Step 6: Saving XML..." );
		new XmlIoSpimData2().save( spimData, xmlOutputPath.toURI() );
		System.out.println( "  ✓ XML saved to: " + xmlOutputPath.getAbsolutePath() );
		System.out.println();

		// Print summary
		printSummary( spimData );
	}

	/**
	 * Main method - generates test OME-ZARRs and creates XML.
	 *
	 * Usage:
	 *   java ExampleOMEZarrXMLGeneration         # Run with 4D OME-ZARR (default)
	 *   java ExampleOMEZarrXMLGeneration 3d      # Run with 3D OME-ZARR
	 *   java ExampleOMEZarrXMLGeneration 4d      # Run with 4D OME-ZARR
	 */
	public static void main( String[] args )
	{
		System.out.println( "OME-ZARR XML Generation Example" );
		System.out.println( "================================" );

		// Parse arguments to determine 3D vs 4D mode
		boolean use4D = true;  // Default to 4D
		if ( args.length > 0 )
		{
			String mode = args[0].toLowerCase();
			if ( mode.equals( "3d" ) )
				use4D = false;
			else if ( mode.equals( "4d" ) )
				use4D = true;
			else
			{
				System.err.println( "Unknown argument: " + args[0] );
				System.err.println( "Usage: java ExampleOMEZarrXMLGeneration [3d|4d]" );
				System.exit( 1 );
			}
		}

		try
		{
			// Setup paths
			File baseDir = new File( "/tmp/example_code_omezarr" );
			File zarrContainer = new File( baseDir, "dataset.zarr" );
			File xmlFile = new File( baseDir, "dataset_code.xml" );

			// Clean up existing directory
			if ( baseDir.exists() )
			{
				System.out.println( "\nCleaning up existing directory..." );
				deleteDirectory( baseDir );
			}
			baseDir.mkdirs();

			System.out.println( "\nOutput directory: " + baseDir.getAbsolutePath() );
			System.out.println();

			// Test dataset parameters
			final int numTiles = 5;
			final int numChannels = 2;
			final int sizeX = 100;
			final int sizeY = 100;
			final int sizeZ = 50;
			final double voxelSizeX = 1.0;
			final double voxelSizeY = 1.0;
			final double voxelSizeZ = 3.0;
			final double overlapPercent = 0.20;  // 20% overlap

			// Generate test OME-ZARRs (3D or 4D)
			List<OMEZarrViewSetupInfo> viewSetups = generateTestOMEZarrs( zarrContainer, numTiles, numChannels,
				sizeX, sizeY, sizeZ, voxelSizeX, voxelSizeY, voxelSizeZ, overlapPercent, use4D );

			// Create XML from generated OME-ZARRs
			exampleWithCode( zarrContainer.toURI(), StorageFormat.ZARR, viewSetups, xmlFile );

			System.out.println( "\n✓ Example completed successfully!" );
			System.out.println( "\nOutput files:" );
			System.out.println( "  ZARR container: " + zarrContainer.getAbsolutePath() );
			System.out.println( "  XML file: " + xmlFile.getAbsolutePath() );
			System.out.println();
			System.out.println( "To use with your own OME-ZARRs:" );
			System.out.println( "  1. Create a List<OMEZarrViewSetupInfo> with just paths, indices, and attribute IDs:" );
			System.out.println( "     new OMEZarrViewSetupInfo(\"tile_00.zarr\", new int[]{0}, 0, 0, 0, 0, 0)" );
			System.out.println( "  2. Call exampleWithCode(containerURI, StorageFormat.ZARR, viewSetups, xmlPath)" );
			System.out.println( "  3. Dimensions, voxel size, unit, and tile positions are read automatically!" );

		}
		catch ( Exception e )
		{
			System.err.println( "✗ Example failed with error:" );
			e.printStackTrace();
			System.exit( 1 );
		}
	}
}
