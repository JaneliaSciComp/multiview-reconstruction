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
package net.preibisch.mvrecon.process.export;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.janelia.saalfeldlab.n5.Compression;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.OmeNgffMetadata;

import bdv.export.ExportMipmapInfo;
import bdv.export.ProposeMipmaps;
import bdv.util.MipmapTransforms;
import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.gui.GenericDialog;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionExportInterface;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.plugin.util.PluginHelper;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.AllenOMEZarrLoader.OMEZARREntry;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.OMEZarrAttributes;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.n5api.N5ApiTools;
import net.preibisch.mvrecon.process.n5api.N5ApiTools.MultiResolutionLevelInfo;
import net.preibisch.mvrecon.process.n5api.SpimData2Tools;
import net.preibisch.mvrecon.process.n5api.SpimData2Tools.InstantiateViewSetupBigStitcher;
import util.BlockSupplierUtils;
import util.Grid;
import util.URITools;

public class ExportN5Api implements ImgExport, Calibrateable
{
	public static String defaultPathURI = null;
	public static int defaultOption = 0;
	public static String defaultDatasetName = "fused";
	//public static String defaultBaseDataset = "/";
	//public static String defaultDatasetExtension = "/s0";

	//public static String[] omeZarrDimChoice = new String[] { "3D (ZYX)", "4D (CZYX)", "5D (TCZYX)" };
	public static int defaultOmeZarrDim = 2;
	public static boolean defaultOmeZarrOneContainer = true;
	public static boolean defaultBDV = false;
	public static boolean defaultMultiRes = true;
	public static String defaultXMLOutURI = null;
	public static boolean defaultManuallyAssignViewId = false;
	public static int defaultTpId = 0;
	public static int defaultVSId = 0;

	public static int defaultBlocksizeX_N5 = 128;
	public static int defaultBlocksizeY_N5 = 128;
	public static int defaultBlocksizeZ_N5 = 64;
	public static int defaultBlocksizeX_H5 = 64;
	public static int defaultBlocksizeY_H5 = 64;
	public static int defaultBlocksizeZ_H5 = 32;

	public static boolean defaultAdvancedBlockSize = false;

	public static int defaultBlocksizeFactorX_N5 = 2;
	public static int defaultBlocksizeFactorY_N5 = 2;
	public static int defaultBlocksizeFactorZ_N5 = 2;
	public static int defaultBlocksizeFactorX_H5 = 4;
	public static int defaultBlocksizeFactorY_H5 = 4;
	public static int defaultBlocksizeFactorZ_H5 = 4;

	public static boolean defaultUseSharding = true;

	String unit = "px";
	double[] cal = new double[] { 1.0, 1.0, 1.0 };

	StorageFormat storageType = StorageFormat.values()[ defaultOption ];
	URI path = (defaultPathURI != null && defaultPathURI.trim().length() > 0 ) ? URITools.toURI( defaultPathURI ) : null;
	//String baseDataset = defaultBaseDataset;
	//String datasetExtension = defaultDatasetExtension;

	//int omeZarrDim = defaultOmeZarrDim;
	boolean omeZarrOneContainer = defaultOmeZarrOneContainer;

	boolean bdv = defaultBDV;
	URI xmlOut;
	boolean manuallyAssignViewId = false;
	int tpId = defaultTpId;
	int vsId = defaultVSId;
	int splittingType;
	Map<ViewId, ViewDescription> vdMap;

	int[][] downsampling = null; //if downsampling is desired

	int bsX = defaultBlocksizeX_N5;
	int bsY = defaultBlocksizeY_N5;
	int bsZ = defaultBlocksizeZ_N5;

	int bsFactorX = defaultBlocksizeFactorX_N5;
	int bsFactorY = defaultBlocksizeFactorY_N5;
	int bsFactorZ = defaultBlocksizeFactorZ_N5;

	boolean useSharding = defaultUseSharding;
	int[] shardSize = null;

	Compression compression = null;
	N5Writer driverVolumeWriter = null;

	InstantiateViewSetupBigStitcher instantiate;
	final HashMap<Integer, Integer> countViewIds = new HashMap<>();

	@Override
	public boolean finish()
	{
		driverVolumeWriter.close();
		return true;
	}

	@Override
	public int[] blocksize() { return new int[] { bsX, bsY, bsZ }; }

	public int[] computeBlocksizeFactor() { return new int[] { bsFactorX, bsFactorY, bsFactorZ }; }

	@Override
	public ImgExport newInstance() { return new ExportN5Api(); }

	@Override
	public String getDescription() { return "OME-ZARR/N5/HDF5 export using N5-API"; }

	@Override
	public java.util.Map<String,String> describeParameters()
	{
		final java.util.LinkedHashMap<String,String> p = new java.util.LinkedHashMap<>();
		if ( path != null ) p.put( "n5Path", path.toString() );
		if ( storageType != null ) p.put( "storage", storageType.toString() );
		p.put( "blockSize", bsX + "," + bsY + "," + bsZ );
		p.put( "blockScale", bsFactorX + "," + bsFactorY + "," + bsFactorZ );
		if ( compression != null ) p.put( "compression", compression.getClass().getSimpleName() );
		p.put( "useSharding", Boolean.toString( useSharding ) );
		if ( bdv && xmlOut != null ) p.put( "xmlOut", xmlOut.toString() );
		p.put( "multiRes", Boolean.toString( downsampling != null ) );
		return p;
	}

	private MultiResolutionLevelInfo[] mrInfoZarr = null;
	private ArrayList<TimePoint> timepoints;
	private ArrayList<Channel> channels;

	@Override
	public <T extends RealType<T> & NativeType<T>> boolean exportImage(
			final BlockSupplier<T> blockSupplierIn,
			final Interval bb,
			final double downsamplingF,
			final double anisoF,
			final String title,
			final Group<? extends ViewDescription> fusionGroup )
	{
		final BlockSupplier<T> blockSupplier = blockSupplierIn.threadSafe();

		final T type = blockSupplier.getType();
		final DataType dataType = N5Utils.dataType( type );
		final EnumSet< DataType > supportedDataTypes = EnumSet.of( DataType.UINT8, DataType.UINT16, DataType.FLOAT32 );
		if ( !supportedDataTypes.contains( dataType ) )
			throw new RuntimeException( "dataType " + type.getClass().getSimpleName() + " not supported." );

		System.out.println( "Datatype: " + dataType );

		// the container is created the first time this method is called, usually it is called many times, for each channel and timepoint (but depends on the settings)
		if ( driverVolumeWriter == null )
		{
			IOFunctions.println( "Creating " + storageType + " container '" + path + "' (assuming it doesn't already exist) ... " );

			try
			{
				if ( storageType == StorageFormat.HDF5 )
				{
					final File dir = new File( URITools.fromURI( path ) ).getParentFile();
					if ( !dir.exists() )
						dir.mkdirs();
					driverVolumeWriter = new N5HDF5Writer( URITools.fromURI( path ) );
				}
				else if ( storageType == StorageFormat.N5 || storageType == StorageFormat.ZARR || storageType == StorageFormat.ZARR2 )
				{
					driverVolumeWriter = URITools.instantiateN5Writer( storageType, path );

					// OME-ZARR single container:
					// if we store all fused data in one container, we create the dataset here
					if ( (storageType == StorageFormat.ZARR || storageType == StorageFormat.ZARR2) && omeZarrOneContainer )
					{
						// TODO: this code is very similar to N5APITools.setupBdvDatasetsOMEZARR
						IOFunctions.println( "Creating 5D OME-ZARR metadata for '" + path + "' ... " );

						final long[] dim3d = bb.dimensionsAsLongArray();

						final long[] dim = new long[] { dim3d[ 0 ], dim3d[ 1 ], dim3d[ 2 ], channels.size(), timepoints.size() };
						final int[] blockSize = new int[] { blocksize()[ 0 ], blocksize()[ 1 ], blocksize()[ 2 ], 1, 1 };
						final int[] shardSize = (useSharding) ? new int[] { this.shardSize[ 0 ], this.shardSize[ 1 ], this.shardSize[ 2 ], 1, 1 } : null;
						final int[][] ds = new int[ this.downsampling.length ][];
						for ( int d = 0; d < ds.length; ++d )
							ds[ d ] = new int[] { this.downsampling[ d ][ 0 ], this.downsampling[ d ][ 1 ], this.downsampling[ d ][ 2 ], 1, 1 };

						final Function<Integer, String> levelToName = (level) -> "/" + level;

						// all is 5d now
						mrInfoZarr = N5ApiTools.setupMultiResolutionPyramid(
								driverVolumeWriter,
								levelToName,
								dataType,
								dim, //5d
								compression,
								blockSize, //5d
								ds, // 5d
								this.useSharding,
								shardSize ); // 5d

						final Function<Integer, AffineTransform3D> levelToMipmapTransform =
								(level) -> MipmapTransforms.getMipmapTransformDefault( mrInfoZarr[level].absoluteDownsamplingDouble() );

						// at this point the pixel size has already been set
						// - here we adjust the S0 resolution based on selected downsampling and anisotropy before exporting to N5
						double[] resolutionS0 = OMEZarrAttributes.getResolutionS0( cal, anisoF, downsamplingF );

						IOFunctions.println( "Calibration: " + Util.printCoordinates( cal ) + " micrometer; resolution at S0: " + Util.printCoordinates( resolutionS0 ) + " " + unit);

						// create metadata
						if (storageType == StorageFormat.ZARR2) {
							final OmeNgffMetadata meta = OMEZarrAttributes.createOMEZarrMetadata(
									5, // int n
									"/", // String name, I also saw "/"
									"0.4",
									resolutionS0, // double[] resolutionS0,
									unit, //"micrometer", //vx.unit() might not be OME-ZARR compatible // String unitXYZ, // e.g micrometer
									mrInfoZarr.length, // int numResolutionLevels,
									levelToName,
									levelToMipmapTransform );

							// save metadata
							driverVolumeWriter.setAttribute( "/", "multiscales", meta.multiscales );
						} else {
							// ZARR3
							final OmeNgffMetadata meta = OMEZarrAttributes.createOMEZarrMetadata(
									5, // int n
									"", // String name, I also saw "/"
									"0.5",
									resolutionS0, // double[] resolutionS0,
									unit, //"micrometer", //vx.unit() might not be OME-ZARR compatible // String unitXYZ, // e.g micrometer
									mrInfoZarr.length, // int numResolutionLevels,
									levelToName,
									levelToMipmapTransform );
							// save metadata
							driverVolumeWriter.setAttribute( "/", "ome", meta );
							// this is hacky until OmeNgffMetadata gets fixed to output version
							driverVolumeWriter.setAttribute( "/", "ome/version", meta.version );
						}
					}
				}
				else
				{
					throw new RuntimeException( "storageType " + storageType + " not supported." );
				}
			}
			catch ( Exception e )
			{
				IOFunctions.println( "Couldn't create " + storageType + " container '" + path + "': " + e );
				return false;
			}
		}

		//final RandomAccessibleInterval< T > img = Views.zeroMin( imgInterval );
		final Interval imgInterval = new FinalInterval( bb.dimensionsAsLongArray() );

		final MultiResolutionLevelInfo[] mrInfo;
		final long currentChannelIndex, currentTPIndex;

		final ViewId viewId;
		OMEZARREntry omeZarrEntry = null;

		if ( bdv )
		{
			if ( manuallyAssignViewId )
				viewId = new ViewId( tpId, vsId );
			else
				viewId = getViewIdForGroup( fusionGroup, splittingType );

			IOFunctions.println( "Assigning ViewId " + Group.pvid( viewId ) );
		}
		else
		{
			viewId = null;
		}
			
		if ( bdv && (storageType == StorageFormat.N5 || storageType == StorageFormat.HDF5 ) )
		{
			try
			{
				// create or extend XML, setup s0 and multiresolution pyramid
				mrInfo = SpimData2Tools.writeBDVMetaData(
						driverVolumeWriter,
						storageType,
						dataType,
						bb.dimensionsAsLongArray(),
						compression,
						blocksize(),
						this.downsampling == null ? new int[][] {{1,1,1}} : this.downsampling,
						viewId,
						path,
						xmlOut,
						instantiate );

				if ( mrInfo == null )
					return false;
			}
			catch (SpimDataException | IOException e)
			{
				e.printStackTrace();
				IOFunctions.println( "Failed to write metadata for '"  + "': " + e );
				return false;
			}

			currentChannelIndex = -1;
			currentTPIndex = -1;
		}
		else if ( (storageType == StorageFormat.ZARR || storageType == StorageFormat.ZARR2) && omeZarrOneContainer ) // OME-Zarr export into a single container
		{
			currentChannelIndex = N5ApiTools.channelIndex( fusionGroup, channels );
			currentTPIndex = N5ApiTools.timepointIndex( fusionGroup, timepoints );

			IOFunctions.println( "Prcoessing OME-ZARR sub-volume '" + title + "'. channel index=" + currentChannelIndex + ", timepoint index=" + currentTPIndex );

			omeZarrEntry = new OMEZARREntry(
					mrInfoZarr[ 0 ].dataset.substring(0, mrInfoZarr[ 0 ].dataset.lastIndexOf( "/" ) ),
					new int[] { (int)currentChannelIndex, (int)currentTPIndex } );

			mrInfo = mrInfoZarr;
		}
		else if ( storageType == StorageFormat.ZARR || storageType == StorageFormat.ZARR2 ) // OME-Zarr export
		{
			final String omeZarrSubContainer = title + ".zarr";
			IOFunctions.println( "Creating 3D OME-ZARR sub-container '" + omeZarrSubContainer + "' and metadata in '" + path + "' ... " );

			// all is 3d
			mrInfo = N5ApiTools.setupMultiResolutionPyramid(
					driverVolumeWriter,
					(level) -> omeZarrSubContainer + "/" + level,
					dataType,
					bb.dimensionsAsLongArray(), //3d
					compression,
					blocksize(), //3d
					this.downsampling, // 3d
					this.useSharding,
					this.shardSize ); // 3d

			final Function<Integer, AffineTransform3D> levelToMipmapTransform =
					(level) -> MipmapTransforms.getMipmapTransformDefault( mrInfo[level].absoluteDownsamplingDouble() );

			double[] resolutionS0 = OMEZarrAttributes.getResolutionS0( cal, anisoF, downsamplingF );

			IOFunctions.println( "Calibration: " + Util.printCoordinates( cal ) + " micrometer; resolution at S0: " + Util.printCoordinates( resolutionS0 ) + " " + unit);

			// create metadata
			final OmeNgffMetadata meta = OMEZarrAttributes.createOMEZarrMetadata(
					3, // int n
					omeZarrSubContainer, // String name, I also saw "/"
					storageType == StorageFormat.ZARR2 ? "0.4" : "0.5",
					resolutionS0, // double[] resolutionS0,
					unit, // might not be OME-ZARR compatible // String unitXYZ, // e.g micrometer
					mrInfo.length, // int numResolutionLevels,
					(level) -> "/" + level,
					levelToMipmapTransform );

			// save metadata
			if (storageType == StorageFormat.ZARR2)
				driverVolumeWriter.setAttribute( omeZarrSubContainer, "multiscales", meta.multiscales );
			else {
				driverVolumeWriter.setAttribute( omeZarrSubContainer, "ome", meta );
				driverVolumeWriter.setAttribute( omeZarrSubContainer, "ome/version", meta.version );
			}

			omeZarrEntry = new OMEZARREntry(
					mrInfo[ 0 ].dataset.substring(0, mrInfo[ 0 ].dataset.lastIndexOf( "/" ) ),
					null );

			currentChannelIndex = -1;
			currentTPIndex = -1;
		}
		else
		{
			// this is the relative path to the dataset inside the N5/HDF5 container, thus using File here seems fine
			//final String dataset = new File( new File( baseDataset , title ).toString(), datasetExtension ).toString();

			// e.g. in Windows this will change it to '\s0'
			//final String datasetExtensionOS = new File( datasetExtension ).toString(); 

			//IOFunctions.println( "datasetExtensionOS: " +datasetExtensionOS );
			IOFunctions.println( "Creating 3D N5 sub-container '" + title + "' in '" + path + "' ... " );

			// setup multi-resolution pyramid
			mrInfo = N5ApiTools.setupMultiResolutionPyramid(
					driverVolumeWriter,
					(level) -> title + "/s" + level,
					dataType,
					bb.dimensionsAsLongArray(),
					compression,
					blocksize(),
					this.downsampling,
				this.useSharding,
				this.shardSize );

			currentChannelIndex = -1;
			currentTPIndex = -1;
		}

		if ( bdv && (storageType == StorageFormat.ZARR || storageType == StorageFormat.ZARR2) )
		{
			// TODO: create/update the XML
			try
			{
				SpimData2Tools.writeSpimData(
						viewId,
						storageType,
						bb.dimensionsAsLongArray(),
						path,
						xmlOut,
						omeZarrEntry,
						instantiate );
			}
			catch (SpimDataException e)
			{
				IOFunctions.println("Failed to write XML: " + e );
				e.printStackTrace();
			}
		}

		// we need to run explicitly in 3D because for OME-ZARR, dimensions are 5D
		final List<long[][]> grid = N5ApiTools.assembleJobs(
				null, // no need to go across ViewIds (for now)
				new long[] { mrInfo[ 0 ].dimensions[ 0 ], mrInfo[ 0 ].dimensions[ 1 ], mrInfo[ 0 ].dimensions[ 2 ] },
				blocksize(),
				new int[] {
						blocksize()[0] * computeBlocksizeFactor()[ 0 ],
						blocksize()[1] * computeBlocksizeFactor()[ 1 ],
						blocksize()[2] * computeBlocksizeFactor()[ 2 ] }
				);

		IOFunctions.println( "num blocks = " + Grid.create( bb.dimensionsAsLongArray(), blocksize() ).size() + ", size = " + bsX + "x" + bsY + "x" + bsZ );
		IOFunctions.println( "num compute blocks = " + grid.size() + ", size = " + bsX*bsFactorX + "x" + bsY*bsFactorY + "x" + bsZ*bsFactorZ );

		final AtomicInteger progress = new AtomicInteger( 0 );
		IJ.showProgress( progress.get(), grid.size() );

		//
		// save full-resolution data (s0)
		//

		IOFunctions.println( "#threads=" + Threads.numThreads() );

		//final ForkJoinPool poolFullRes = new ForkJoinPool( Threads.numThreads() );
		final ExecutorService poolFullRes = Executors.newFixedThreadPool( Threads.numThreads() );

		long time = System.currentTimeMillis();

		try
		{
			final RetryTracker<long[][]> retryTracker = RetryTracker.forGridBlocks("s0 block processing", grid.size());

			do
			{
				if (!retryTracker.beginAttempt())
					return false;

				final ArrayList< Callable< long[][] > > tasks = new ArrayList<>();

				for ( final long[][] gridBlock : grid )
				{
					tasks.add( () ->
					{
						final long[] /*blockOffset, blockSize,*/ gridOffset;
	
						final long[] blockMin = gridBlock[0].clone();
						final long[] blockMax = new long[ blockMin.length ];

						for ( int d = 0; d < blockMin.length; ++d )
							blockMax[ d ] = Math.min( imgInterval.max( d ), blockMin[ d ] + gridBlock[1][ d ] - 1 );

						final RandomAccessibleInterval< T > image;
						final RandomAccessibleInterval< T > img = BlockSupplierUtils.arrayImg( blockSupplier, new FinalInterval( blockMin, blockMax ) );

						// 5D OME-ZARR CONTAINER
						if ( (storageType == StorageFormat.ZARR || storageType == StorageFormat.ZARR2) && omeZarrOneContainer )
						{
							// gridBlock is 3d, make it 5d
							//blockOffset = new long[] { gridBlock[0][0], gridBlock[0][1], gridBlock[0][2], currentChannelIndex, currentTPIndex };
							//blockSize = new long[] { gridBlock[1][0], gridBlock[1][1], gridBlock[1][2], 1, 1 };
							gridOffset = new long[] { gridBlock[2][0], gridBlock[2][1], gridBlock[2][2], currentChannelIndex, currentTPIndex }; // because blocksize in C & T is 1
	
							// img is 3d, make it 5d
							// the same information is returned no matter which index is queried in C and T
							image = Views.interval(
										Views.addDimension( Views.addDimension( img ) ),
										new FinalInterval( new long[] { gridBlock[1][0], gridBlock[1][1], gridBlock[1][2], currentChannelIndex+1, currentTPIndex+1 } ) );
						}
						else
						{
							//blockOffset = gridBlock[0];
							//blockSize = gridBlock[1];
							gridOffset = gridBlock[2];
	
							image = img;
						}
	
						/*
						final Interval block =
								Intervals.translate(
										new FinalInterval( blockSize ),
										blockOffset );
	
						final RandomAccessibleInterval< T > source =
								Views.interval( image, block );
	
						final RandomAccessibleInterval< T > sourceGridBlock =
								Views.offsetInterval(source, blockOffset, blockSize);
						*/
						N5Utils.saveBlock( /*sourceGridBlock*/ image, driverVolumeWriter, mrInfo[ 0 ].dataset, gridOffset );
	
						IJ.showProgress( progress.incrementAndGet(), grid.size() );
	
						return gridBlock.clone();
					} );
				}
	
				final List<Future<long[][]>> futures = poolFullRes.invokeAll( tasks );

				// extract all blocks that failed
				final Set<long[][]> failedBlocksSet = retryTracker.processWithFutures( futures, grid );

				// Use RetryTracker to handle retry counting and removal
				if (!retryTracker.processFailures(failedBlocksSet))
					return false;

				// Update grid for next iteration with remaining failed blocks
				grid.clear();
				grid.addAll(failedBlocksSet);
			}
			while ( grid.size() > 0 );

			poolFullRes.shutdown();
			poolFullRes.awaitTermination(Long.MAX_VALUE, TimeUnit.HOURS);
		}
		catch ( Exception e )
		{
			IOFunctions.println( "Failed to write HDF5/N5/ZARR dataset '" + mrInfo[ 0 ].dataset + "'. Error: " + e );
			e.printStackTrace();
			return false;
		}


		//System.out.println( "Saved, e.g. view with './n5-view -i " + n5Path + " -d " + n5Dataset );
		IJ.showProgress( progress.getAndSet( 0 ), grid.size() );
		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Saved full resolution, took: " + (System.currentTimeMillis() - time ) + " ms." );

		//
		// save multiresolution pyramid (s1 ... sN)
		//
		for ( int level = 1; level < mrInfo.length; ++level )
		{
			//final ForkJoinPool myPool = new ForkJoinPool( Threads.numThreads() );
			final ExecutorService myPool = Executors.newFixedThreadPool( Threads.numThreads() );
			final int s = level;

			// we need to run explicitly in 3D because for OME-ZARR, dimensions are 5D
			// For sharded datasets, use shard size as computeBlockSize
			final int[] fusionComputeBlockSize = (useSharding && shardSize != null)
					? shardSize
					: new int[] {
							blocksize()[0] * computeBlocksizeFactor()[ 0 ],
							blocksize()[1] * computeBlocksizeFactor()[ 1 ],
							blocksize()[2] * computeBlocksizeFactor()[ 2 ] };

			final List<long[][]> allBlocks =
					N5ApiTools.assembleJobs(
							null, // no need to go across ViewIds (for now)
							new long[] { mrInfo[ level ].dimensions[ 0 ], mrInfo[ level ].dimensions[ 1 ], mrInfo[ level ].dimensions[ 2 ] },
							blocksize(),
							fusionComputeBlockSize );

			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Downsampling: " + Util.printCoordinates( mrInfo[ level ].absoluteDownsampling ) + " with relative downsampling of " + Util.printCoordinates( mrInfo[ level ].relativeDownsampling ));
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": s" + level + " num blocks=" + allBlocks.size() );
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Loading '" + mrInfo[ level - 1 ].dataset + "', downsampled will be written as '" + mrInfo[ level ].dataset + "'." );
			IJ.showProgress( progress.get(), allBlocks.size() );

			time = System.currentTimeMillis();

			try
			{
				final RetryTracker<long[][]> retryTracker = RetryTracker.forGridBlocks("s" + level + " block processing", allBlocks.size());

				do
				{
					if (!retryTracker.beginAttempt())
						return false;

					final ArrayList< Callable< long[][] > > tasks = new ArrayList<>();
					
					for ( final long[][] gridBlock : allBlocks )
					{
						tasks.add( () -> 
						{
							// 5D OME-ZARR CONTAINER
							if ( (storageType == StorageFormat.ZARR || storageType == StorageFormat.ZARR2) && omeZarrOneContainer )
							{
								N5ApiTools.writeDownsampledBlock5dOMEZARR(
										driverVolumeWriter,
										mrInfo[ s ],
										mrInfo[ s - 1 ],
										gridBlock,
										currentChannelIndex,
										currentTPIndex );
							}
							else
							{
								N5ApiTools.writeDownsampledBlock(
										driverVolumeWriter,
										mrInfo[ s ],
										mrInfo[ s - 1 ],
										gridBlock );
							}

							IJ.showProgress( progress.incrementAndGet(), allBlocks.size() );

							return gridBlock.clone();
						});
					}

					final List<Future<long[][]>> futures = myPool.invokeAll( tasks );

					// extract all blocks that failed
					final Set<long[][]> failedBlocksSet = retryTracker.processWithFutures( futures, allBlocks );

					// Use RetryTracker to handle retry counting and removal
					if (!retryTracker.processFailures(failedBlocksSet))
						return false;

					// Update allBlocks for next iteration with remaining failed blocks
					allBlocks.clear();
					allBlocks.addAll(failedBlocksSet);
				}
				while ( allBlocks.size() > 0 );

				myPool.shutdown();
				myPool.awaitTermination( Long.MAX_VALUE, TimeUnit.HOURS);
			}
			catch ( Exception e )
			{
				IOFunctions.println( "Failed to write HDF5/N5/ZARR dataset '" + mrInfo[ level ].dataset + "'. Error: " + e );
				e.printStackTrace();
				return false;
			}

			IJ.showProgress( progress.getAndSet( 0 ), allBlocks.size() );
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Saved level s" + level + ", took: " + (System.currentTimeMillis() - time ) + " ms." );
		}

		return true;
	}

	@Override
	public boolean queryParameters( final FusionExportInterface fusion)
	{
		//
		// Initial dialog
		//
		final GenericDialogPlus gdInit = new GenericDialogPlus( "Save fused images as ZARR/N5/HDF5 using N5-API" );

		final String[] options = N5ApiTools.exportOptions();
		//Arrays.asList( StorageFormat.values() ).stream().map( s -> s.name().equals( "ZARR" ) ? "OME-ZARR" : s.name() ).toArray(String[]::new);

		gdInit.addChoice( "Export as ...", options, options[ defaultOption ] );

		gdInit.addMessage(
				"For cluster/cloud - distributed fusion please check out BigStitcher-Spark.", GUIHelper.smallStatusFont, GUIHelper.neutral );

		gdInit.addMessage(
				"Note: you can always add new datasets to an existing HDF5/N5/ZARR container, so you can specify\n"
				+ "existing N5/ZARR-directories or HDF5-files. If a dataset inside a container already exists\n"
				+ "export will stop and NOT overwrite existing datasets.", GUIHelper.smallStatusFont, GUIHelper.neutral );

		PluginHelper.addCompression( gdInit, false );

		gdInit.addCheckbox( "Create_a_BDV/BigStitcher compatible export", defaultBDV );

		gdInit.addMessage(
				"HDF5/BDV currently only supports 8-bit, 16-bit\n"
				+ "N5/BDV & OME-ZARR/BDV supports 8-bit, 16-bit & 32-bit",
				GUIHelper.smallStatusFont, GUIHelper.warning );

		gdInit.addMessage(
				"Note: if you selected a single image to fuse (e.g. all tiles of one channel), you can manually\n"
				+ "specify the ViewId it will be assigned in the following dialog. This is useful if you want to\n"
				+ "add the fused image to an existing dataset so you can specify a ViewId that does not exist yet.",
				GUIHelper.smallStatusFont, GUIHelper.neutral );

		gdInit.addCheckbox( "Create_multi-resolution pyramid", defaultMultiRes );

		gdInit.showDialog();
		if ( gdInit.wasCanceled() )
			return false;

		this.storageType = StorageFormat.values()[ defaultOption = gdInit.getNextChoiceIndex() ];
		this.compression = PluginHelper.parseCompression( gdInit );
		this.bdv = defaultBDV = gdInit.getNextBoolean();
		final boolean multiRes = defaultMultiRes = gdInit.getNextBoolean();
		this.splittingType = fusion.getSplittingType();
		this.instantiate = new InstantiateViewSetupBigStitcher( splittingType );

		// Check if HDF5 was selected
		if ( storageType == StorageFormat.HDF5 )
		{
			IOFunctions.println( "HDF5 export is currently unavailable due to library incompatibilities with n5 4.0.0-alpha-6." );
			IOFunctions.println( "Please use N5 or ZARR format instead." );
			return false;
		}

		final String name = storageType.name();
		final String ext;

		if ( storageType == StorageFormat.HDF5 )
			ext = ".h5";
		else if ( storageType == StorageFormat.N5 )
			ext = ".n5";
		else
			ext = ".zarr";

		if ( bdv && storageType == StorageFormat.HDF5 && fusion.getPixelType() == 0 )
		{
			IOFunctions.println( "BDV-compatible HDF5 @ 32-bit not (yet) supported." );
			return false;
		}

		//
		// OME-ZARR dialog
		//
		if ( storageType == StorageFormat.ZARR || storageType == StorageFormat.ZARR2 )
		{
			final GenericDialog gdZarr1 = new GenericDialog( (storageType == StorageFormat.ZARR) ? "OME-Zarr v3 & Sharding Options" : "OME-Zarr v2 Options" );

			if ( fusion.getSplittingType() == 0 )
			{
				this.channels = N5ApiTools.channels( fusion.getFusionGroups() );
				this.timepoints = N5ApiTools.timepoints( fusion.getFusionGroups() );

				IOFunctions.println( "Channels to be added to the OME-ZARR:" );
				for ( final Channel c : this.channels )
					IOFunctions.println( "\tChannel " + c.getId() + ": " + c.getName() );

				IOFunctions.println( "Timepoints to be added to the OME-ZARR:" );
				for ( final TimePoint t : this.timepoints )
					IOFunctions.println( "\tTimepoint " + t.getId() );

				gdZarr1.addCheckbox( "Store channels and timepoints into a single OME-ZARR container", defaultOmeZarrOneContainer );
				gdZarr1.addMessage(
						"Note: " + this.channels.size() + " channels and " + this.timepoints.size() + " timepoints selected for fusion.\n" + 
						"If you do not select a single OME-ZARR, a 3D OME-ZARR will be created for each fused volume.", GUIHelper.smallStatusFont );
			}

			// Zarr v3 sharding dialog
			if ( storageType == StorageFormat.ZARR ) // v3
			{
				gdZarr1.addCheckbox( "Enable_sharding", defaultUseSharding );
				gdZarr1.addMessage(
						"Sharding groups multiple blocks [a.k.a. chunks] into larger files, reducing the\n" +
						"number of files significantly. Recommended for large datasets (>1TB) or cloud export.",
						GUIHelper.smallStatusFont );

				gdZarr1.addMessage(
						"Note: Shard size is a multiple of block size and defined by the\n" +
						"Compute block size factor in the advanced block size options dialog.",
						GUIHelper.smallStatusFont );
			}

			gdZarr1.showDialog();
			if ( gdZarr1.wasCanceled() )
				return false;

			if ( fusion.getSplittingType() == 0 )
				omeZarrOneContainer = defaultOmeZarrOneContainer = gdZarr1.getNextBoolean();
			else
				omeZarrOneContainer = false;

			if ( storageType == StorageFormat.ZARR ) // v3
				this.useSharding = defaultUseSharding = gdZarr1.getNextBoolean();
			else
				this.useSharding = false;
		}

		//
		// next dialog
		//
		final GenericDialogPlus gd = new GenericDialogPlus( "Export " + name +" using N5-API" );

		if ( defaultPathURI == null || defaultPathURI.toString().trim().length() == 0 )
			defaultPathURI = URITools.appendName( fusion.getSpimData().getBasePathURI(), defaultDatasetName + "/" + defaultDatasetName+ext );

		if ( storageType == StorageFormat.HDF5 )
			PluginHelper.addSaveAsFileField( gd, name + "_file (local only, end with "+ext+")", defaultPathURI, 80 );
		else
			PluginHelper.addSaveAsDirectoryField( gd, name + "_dataset_path (local or cloud, end with "+ext+")", defaultPathURI, 80 );

		if ( bdv )
		{
			if ( defaultXMLOutURI == null )
				defaultXMLOutURI = URITools.appendName( fusion.getSpimData().getBasePathURI(), defaultDatasetName + "/dataset.xml" );

			if ( storageType == StorageFormat.HDF5 )
				PluginHelper.addSaveAsFileField( gd, "XML_output_file (local)", defaultXMLOutURI, 80 );
			else
				PluginHelper.addSaveAsFileField( gd, "XML_output_file (local or cloud)", defaultXMLOutURI, 80 );

			if ( fusion.getFusionGroups().size() == 1 )
			{
				gd.addCheckbox( "Manually_define_ViewId", defaultManuallyAssignViewId );
				gd.addNumericField( "ViewId_TimepointId", defaultTpId);
				gd.addNumericField( "ViewId_SetupId", defaultVSId);
				gd.addMessage( "" );
			}
		}

		if ( storageType == StorageFormat.HDF5 )
		{
			gd.addMessage(
					"Default blocksize for HDF5: "+defaultBlocksizeX_H5+"x"+defaultBlocksizeY_H5+"x"+defaultBlocksizeZ_H5+"\n" +
					"Default compute blocksize for HDF5: " +(defaultBlocksizeX_H5*defaultBlocksizeFactorX_H5)+"x"+(defaultBlocksizeY_H5*defaultBlocksizeFactorY_H5)+"x"+(defaultBlocksizeZ_H5*defaultBlocksizeFactorZ_H5) +
					" (factor: "+defaultBlocksizeFactorX_H5+"x"+defaultBlocksizeFactorY_H5+"x"+defaultBlocksizeFactorZ_H5+")", GUIHelper.mediumstatusNonItalicfont, GUIHelper.neutral );
		}
		else if ( storageType == StorageFormat.ZARR && useSharding )
		{
			gd.addMessage(
					"Default blocksize for ZARR v3: "+defaultBlocksizeX_N5+"x"+defaultBlocksizeY_N5+"x"+defaultBlocksizeZ_N5+"\n" +
					"Default compute blocksize & shardsize for ZARR v3: " +(defaultBlocksizeX_N5*defaultBlocksizeFactorX_N5)+"x"+(defaultBlocksizeY_N5*defaultBlocksizeFactorY_N5)+"x"+(defaultBlocksizeZ_N5*defaultBlocksizeFactorZ_N5) +
					" (factor: "+defaultBlocksizeFactorX_N5+"x"+defaultBlocksizeFactorY_N5+"x"+defaultBlocksizeFactorZ_N5+")", GUIHelper.mediumstatusNonItalicfont, GUIHelper.neutral );
		}
		else
		{
			gd.addMessage(
					"Default blocksize for ZARR/N5: "+defaultBlocksizeX_N5+"x"+defaultBlocksizeY_N5+"x"+defaultBlocksizeZ_N5+"\n" +
					"Default compute blocksize for " + options[ defaultOption ] + ": " +(defaultBlocksizeX_N5*defaultBlocksizeFactorX_N5)+"x"+(defaultBlocksizeY_N5*defaultBlocksizeFactorY_N5)+"x"+(defaultBlocksizeZ_N5*defaultBlocksizeFactorZ_N5) +
					" (factor: "+defaultBlocksizeFactorX_N5+"x"+defaultBlocksizeFactorY_N5+"x"+defaultBlocksizeFactorZ_N5+")", GUIHelper.mediumstatusNonItalicfont, GUIHelper.neutral );
		}

		gd.addCheckbox( "Show_advanced_block_size_options (in a new dialog, current values above)", defaultAdvancedBlockSize );

		gd.showDialog();
		if ( gd.wasCanceled() )
			return false;

		try
		{
			this.path = URITools.toURI( defaultPathURI = gd.getNextString().trim() );
		}
		catch ( Exception e )
		{
			IOFunctions.println( "Could not create URI from provided path '" + defaultPathURI+ "'. Stopping." );
			return false;
		}

		if ( !URITools.isKnownScheme( this.path ) )
		{
			IOFunctions.println( "You provided an unkown scheme ('" + this.path+ "'). Stopping." );
			return false;
		}

		if ( storageType == StorageFormat.HDF5 && !URITools.isFile( this.path ))
		{
			IOFunctions.println( "When storing as HDF5, only local paths are supported; you specified '" + this.path+ "', which appears to not be local. Stopping." );
			return false;
		}

		if ( bdv )
		{
			try
			{
				this.xmlOut = URITools.toURI( defaultXMLOutURI = gd.getNextString().trim() );//defaultXMLOut = gd.getNextString();
			}
			catch ( Exception e )
			{
				IOFunctions.println( "Could not create URI from provided path '" + defaultXMLOutURI+ "'. Stopping." );
				return false;
			}

			if ( !URITools.isKnownScheme( this.xmlOut ) )
			{
				IOFunctions.println( "You provided an unkown scheme ('" + this.xmlOut+ "'). Stopping." );
				return false;
			}

			if ( storageType == StorageFormat.HDF5 && !URITools.isFile( this.xmlOut ))
			{
				IOFunctions.println( "When storing as HDF5, only local paths are supported; you specified '" + this.xmlOut+ "', which appears to not be local. Stopping." );
				return false;
			}

			if ( fusion.getFusionGroups().size() == 1 )
			{
				this.manuallyAssignViewId = gd.getNextBoolean();
				this.tpId = (int)Math.round( gd.getNextNumber() );
				this.vsId = (int)Math.round( gd.getNextNumber() );
			}
			else
			{
				// depends on fusion group, defined during export
				// later calling getViewIdForGroup( fusionGroup, splittingType );
			}
		}
		else if ( storageType == StorageFormat.ZARR || storageType == StorageFormat.ZARR2 )// && omeZarrOneContainer )
		{
			// nothing to get for OME-ZARR's
		}
		else if ( storageType == StorageFormat.N5 )
		{
			// nothing to get for N5's
			//this.baseDataset = defaultBaseDataset  = gd.getNextString().trim();
			//this.datasetExtension = defaultDatasetExtension = gd.getNextString().trim();
		}

		if ( defaultAdvancedBlockSize = gd.getNextBoolean() )
		{
			final GenericDialog gd2 = new GenericDialog( "Compute block sizes" );

			gd2.addNumericField( "block_size_x", ( storageType == StorageFormat.HDF5 ) ? defaultBlocksizeX_H5 : defaultBlocksizeX_N5, 0);
			gd2.addNumericField( "block_size_y", ( storageType == StorageFormat.HDF5 ) ? defaultBlocksizeY_H5 : defaultBlocksizeY_N5, 0);
			gd2.addNumericField( "block_size_z", ( storageType == StorageFormat.HDF5 ) ? defaultBlocksizeZ_H5 : defaultBlocksizeZ_N5, 0);

			gd2.addNumericField( "block_size_factor_x", ( storageType == StorageFormat.HDF5 ) ? defaultBlocksizeFactorX_H5 : defaultBlocksizeFactorX_N5, 0);
			gd2.addNumericField( "block_size_factor_y", ( storageType == StorageFormat.HDF5 ) ? defaultBlocksizeFactorY_H5 : defaultBlocksizeFactorY_N5, 0);
			gd2.addNumericField( "block_size_factor_z", ( storageType == StorageFormat.HDF5 ) ? defaultBlocksizeFactorZ_H5 : defaultBlocksizeFactorZ_N5, 0);

			gd2.addMessage(
					"For smaller blocksizes (or very large images) you can define compute block sizes\n"
					+ "as a factor of the file block sizes to achieve a better parallelization.\n"
					+ "For example, if you chose a blocksize of 32x32x16 for saving, and you choose factors of 4x4x2,\n"
					+ "the compute will be performed in blocksizes of 128x128x64, which creates less tasks.", GUIHelper.smallStatusFont, GUIHelper.neutral );

			gd2.showDialog();
			if ( gd2.wasCanceled() )
				return false;

			bsX = (int)Math.round( gd2.getNextNumber() );
			bsY = (int)Math.round( gd2.getNextNumber() );
			bsZ = (int)Math.round( gd2.getNextNumber() );

			bsFactorX = (int)Math.round( gd2.getNextNumber() );
			bsFactorY = (int)Math.round( gd2.getNextNumber() );
			bsFactorZ = (int)Math.round( gd2.getNextNumber() );

			if ( storageType == StorageFormat.HDF5 )
			{
				defaultBlocksizeX_H5 = bsX; defaultBlocksizeY_H5 = bsY; defaultBlocksizeZ_H5 = bsZ;
				defaultBlocksizeFactorX_H5 = bsFactorX; defaultBlocksizeFactorY_H5 = bsFactorY; defaultBlocksizeFactorZ_H5 = bsFactorZ;
			}
			else
			{
				defaultBlocksizeX_N5 = bsX; defaultBlocksizeY_N5 = bsY; defaultBlocksizeZ_N5 = bsZ;
				defaultBlocksizeFactorX_N5 = bsFactorX; defaultBlocksizeFactorY_N5 = bsFactorY; defaultBlocksizeFactorZ_N5 = bsFactorZ;
			}
		}
		else
		{
			if ( storageType == StorageFormat.HDF5 )
			{
				bsX = defaultBlocksizeX_H5; bsY = defaultBlocksizeY_H5; bsZ = defaultBlocksizeZ_H5;
				bsFactorX = defaultBlocksizeFactorX_H5; bsFactorY = defaultBlocksizeFactorY_H5; bsFactorZ = defaultBlocksizeFactorZ_H5;
			}
			else
			{
				bsX = defaultBlocksizeX_N5; bsY = defaultBlocksizeY_N5; bsZ = defaultBlocksizeZ_N5;
				bsFactorX = defaultBlocksizeFactorX_N5; bsFactorY = defaultBlocksizeFactorY_N5; bsFactorZ = defaultBlocksizeFactorZ_N5;
			}
		}

		if ( useSharding )
		{
			this.shardSize = new int[] { bsX * bsFactorX, bsY * bsFactorY, bsZ * bsFactorZ };
			IOFunctions.println( "ZARR v3 shard size: " + Arrays.toString( this.shardSize ));
		}
		else if ( storageType == StorageFormat.ZARR ) // v3
		{
			IOFunctions.println( "ZARR v3 sharding: DISABLED." );
		}

		if ( multiRes )
		{
			final double aniso = fusion.getAnisotropyFactor();
			final Interval bb = fusion.getDownsampledBoundingBox();
			final int[][] proposedDownsampling = estimateMultiResPyramid( new FinalDimensions( bb.dimensionsAsLongArray() ), aniso );

			final GenericDialog gdp = new GenericDialog( "Adjust downsampling options" );

			gdp.addStringField( "Subsampling_factors (downsampling)", ProposeMipmaps.getArrayString( proposedDownsampling ), 40 );
			gdp.addMessage( "Blocksize: "+bsX+"x"+bsY+"x"+bsZ, GUIHelper.mediumstatusNonItalicfont, GUIHelper.neutral );

			gdp.showDialog();
			if ( gdp.wasCanceled() )
				return false;

			final String subsampling = gdp.getNextString();
			this.downsampling = PluginHelper.parseResolutionsString( subsampling );

			if ( this.downsampling == null || downsampling.length == 0 || downsampling[0] == null || downsampling[0].length == 0)
			{
				IOFunctions.println( "Could not parse resolution level string '" + subsampling + "'. Stopping." );
				return false;
			}

			String mres = "[" + Util.printCoordinates( downsampling[ 0 ] );
			for ( int i = 1; i < downsampling.length; ++i )
				mres += ", " + Util.printCoordinates( downsampling[ i ] );
			mres += "]";
			IOFunctions.println( "Multi-resolution steps: " + mres);
		}
		else
		{
			this.downsampling = new int[][] {{1,1,1}}; // no downsampling
		}

		return true;
	}

	@Override
	public void setCalibration( final double[] pixelSize, final String unit )
	{
		this.cal = pixelSize;
		this.unit = unit;
	}

	@Override
	public String getUnit() { return unit; }

	@Override
	public double[] getPixelSize() { return cal; }

	public static int[][] estimateMultiResPyramid( final Dimensions dimensions, final double aniso )
	{
		final VoxelDimensions v = new FinalVoxelDimensions( "px", 1.0, 1.0, Double.isNaN( aniso ) ? 1.0 : aniso );
		final BasicViewSetup setup = new BasicViewSetup(0, "fusion", dimensions, v );
		final ExportMipmapInfo emi = ProposeMipmaps.proposeMipmaps( setup );

		return emi.getExportResolutions();
	}

	private ViewId getViewIdForGroup(
			final Group< ? extends ViewId > group,
			final int splittingType )
	{
		// 0 == "Each timepoint &amp; channel",
		// 1 == "Each timepoint, channel &amp; illumination",
		// 2 == "All views together",
		// 3 == "Each view"

		final ViewId vd = group.getViews().iterator().next();
		final int tpId, vsId;

		if ( splittingType == 0 || splittingType == 1 || splittingType == 3 )
		{
			tpId = vd.getTimePointId();

			if ( countViewIds.containsKey( tpId ) )
				vsId = countViewIds.get( tpId ) + 1;
			else
				vsId = 0;

			countViewIds.put( tpId, vsId );
		}
		else if ( splittingType == 2 )
		{
			tpId = 0;
			vsId = 0;

			// even though this is unnecessary
			countViewIds.put( tpId, vsId );
		}
		else
		{
			IOFunctions.println( "SplittingType " + splittingType + " unknown. Stopping.");
			return null;
		}

		return new ViewId(tpId, vsId);
	}
}
