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
package net.preibisch.mvrecon.fiji.plugin.resave;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import mpicbg.spim.data.sequence.VoxelDimensions;
import org.bigdataviewer.n5.N5CloudImageLoader;
import org.janelia.saalfeldlab.n5.Compression;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import bdv.export.ExportMipmapInfo;
import bdv.export.ProgressWriter;
import bdv.img.hdf5.Hdf5ImageLoader;
import bdv.img.n5.N5ImageLoader;
import ij.IJ;
import ij.ImageJ;
import ij.plugin.PlugIn;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.AllenOMEZarrLoader;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.AllenOMEZarrLoader.OMEZARREntry;
import net.preibisch.mvrecon.process.export.RetryTracker;
import net.preibisch.mvrecon.process.n5api.N5ApiTools;
import net.preibisch.mvrecon.process.n5api.N5ApiTools.MultiResolutionLevelInfo;
import net.preibisch.mvrecon.process.n5api.SpimData2Tools;
import util.Grid;
import util.URITools;

public class Resave_N5Api implements PlugIn
{
	@Override
	public void run(String arg)
	{
		final LoadParseQueryXML xml = new LoadParseQueryXML();

		if ( !xml.queryXML( "Resaving using N5-API (ZARR, N5, HDF5)", "Resave", true, true, true, true, true ) )
			return;

		final ParametersResaveN5Api n5params =
				ParametersResaveN5Api.getParamtersIJ( xml.getXMLURI(), xml.getViewSetupsToProcess(), true, true );

		if ( n5params == null )
			return;

		final ArrayList< ViewId > vidsToProcess = new ArrayList<>();
		for ( final TimePoint tp : xml.getTimePointsToProcess() )
			for ( final ViewSetup vs : xml.getViewSetupsToProcess() )
				vidsToProcess.add( new ViewId( tp.getId(), vs.getId() ) );

		resaveN5( xml.getData(), vidsToProcess, n5params, true );
	}


	public static SpimData2 resaveN5(
			final SpimData2 data,
			final Collection<? extends ViewId> vidsToResave,
			final ParametersResaveN5Api n5Params,
			final boolean saveXML )
	{
		final SpimData2 sdReduced = SpimData2Tools.reduceSpimData2( data, vidsToResave.stream().collect( Collectors.toList() ) );

		final ProgressWriter progressWriter = new ProgressWriterIJ();
		progressWriter.out().println( new Date( System.currentTimeMillis() ) + ": Saving " + n5Params.n5URI );

		//final Map< Integer, ExportMipmapInfo > proposedMipmaps;
		if ( n5Params.proposedMipmaps == null )
		{
			// propose downsampling
			n5Params.proposedMipmaps = Resave_HDF5.proposeMipmaps( sdReduced.getSequenceDescription().getViewSetupsOrdered() );

			// crude overwrite of block size (should be bigger than for normal hdf5)
			n5Params.proposedMipmaps.keySet().forEach( k -> {
				ExportMipmapInfo exportMipmapInfo = n5Params.proposedMipmaps.get( k );
				for (int[] row : exportMipmapInfo.getSubdivisions())
				{
					Arrays.fill( row, ParametersResaveN5Api.defaultBlockSize );
					row[ 0 ] = ParametersResaveN5Api.defaultBlockSizeXY;
					if ( row.length >= 2 )
						row[ 1 ] = ParametersResaveN5Api.defaultBlockSizeXY;
				}
			});
		}

		// save to cloud or file
		final N5Writer n5Writer = URITools.instantiateN5Writer( n5Params.format, n5Params.n5URI );

		final int[] blockSize = n5Params.subdivisions[ 0 ];
		final int[] computeBlockSize = new int[ blockSize.length ];
		final Compression compression = n5Params.compression;

		for ( int d = 0; d < blockSize.length; ++d )
			computeBlockSize[ d ] = blockSize[ d ] * n5Params.blockSizeFactor[ d ];

		final HashMap<Integer, long[]> dimensions =
				N5ApiTools.assembleDimensions( data, vidsToResave );

		final int[][] downsamplings =
				N5ApiTools.mipMapInfoToDownsamplings( n5Params.proposedMipmaps );

		final List<long[][]> gridS0 =
				vidsToResave.stream().map( viewId ->
						N5ApiTools.assembleJobs(
								viewId,
								dimensions.get( viewId.getViewSetupId() ),
								blockSize,
								computeBlockSize ) ).flatMap(List::stream).collect( Collectors.toList() );

		final Map<Integer, DataType> dataTypes =
				N5ApiTools.assembleDataTypes( data, dimensions.keySet() );

		//IOFunctions.println( "Dimensions of raw images: " );
		//dimensions.forEach( ( id,dim ) -> IOFunctions.println( "ViewSetup " + id + ": " + Arrays.toString( dim )) );
		IOFunctions.println( "Downsamplings: " + Arrays.deepToString( downsamplings ) );

		// create all datasets and write BDV metadata for all ViewIds (including downsampling) in parallel
		long time = System.currentTimeMillis();

		final Map< ViewId, MultiResolutionLevelInfo[] > viewIdToMrInfo =
				vidsToResave.parallelStream().map(
						viewId ->
						{
							final MultiResolutionLevelInfo[] mrInfo;

							if ( n5Params.format == StorageFormat.N5 )
							{
								mrInfo = N5ApiTools.setupBdvDatasetsN5(
										n5Writer,
										viewId,
										dataTypes.get( viewId.getViewSetupId() ),
										dimensions.get( viewId.getViewSetupId() ),
										compression,
										blockSize,
										downsamplings );
							}
							else if ( n5Params.format == StorageFormat.HDF5 )
							{
								mrInfo = N5ApiTools.setupBdvDatasetsHDF5(
										n5Writer,
										viewId,
										dataTypes.get( viewId.getViewSetupId() ),
										dimensions.get( viewId.getViewSetupId() ),
										compression,
										blockSize,
										downsamplings );
							}
							else
							{
								VoxelDimensions vx = data.getSequenceDescription().getViewDescription( viewId ).getViewSetup().getVoxelSize();
								// 5d OME-ZARR with dimension=1 in c and t
								mrInfo = N5ApiTools.setupBdvDatasetsOMEZARR(
										n5Writer,
										viewId,
										dataTypes.get( viewId.getViewSetupId() ),
										dimensions.get( viewId.getViewSetupId() ),
										vx.dimensionsAsDoubleArray(), // resolutionS0
										vx.unit(),
										compression,
										blockSize,
										downsamplings);
							}

							return new ValuePair<>( viewId, mrInfo );
						} ).collect(Collectors.toMap( e -> e.getA(), e -> e.getB() ));

		IOFunctions.println( "Created BDV-metadata, took: " + (System.currentTimeMillis() - time ) + " ms." );
		IOFunctions.println( "Number of compute blocks (s0): " + gridS0.size() );

		final AtomicInteger progress = new AtomicInteger( 0 );
		IJ.showProgress( progress.get(), gridS0.size() );

		//
		// Save full resolution dataset (s0)
		//
		try
		{
			final ForkJoinPool myPool = new ForkJoinPool( n5Params.numCellCreatorThreads );
			final RetryTracker<long[][]> retryTracker = RetryTracker.forGridBlocks("s0 resaving", gridS0.size());

			time = System.currentTimeMillis();

			do
			{
				if (!retryTracker.beginAttempt())
					return null;

				final ArrayList< Callable< long[][] > > tasks = new ArrayList<>();

				for ( final long[][] gridBlock : gridS0 )
					tasks.add( () ->
					{
						N5ApiTools.resaveS0Block(
								data,
								n5Writer,
								n5Params.format,
								dataTypes.get( N5ApiTools.gridBlockToViewId( gridBlock ).getViewSetupId() ),
								N5ApiTools.gridToDatasetBdv( 0, n5Params.format ), // a function mapping the gridblock to the dataset name for level 0 and N5
								gridBlock );

						IJ.showProgress( progress.incrementAndGet(), gridS0.size() );

						return gridBlock.clone();
					});

				/*
				myPool.submit(() -> grid.parallelStream().map( gridBlock -> 
				{
					N5ApiTools.resaveS0Block(
						data,
						n5Writer,
						n5Params.format,
						dataTypes.get( N5ApiTools.gridBlockToViewId( gridBlock ).getViewSetupId() ),
						N5ApiTools.gridToDatasetBdv( 0, n5Params.format ), // a function mapping the gridblock to the dataset name for level 0 and N5
						gridBlock );

					IJ.showProgress( progress.incrementAndGet(), grid.size() );

					// TOOD: add re-try logic
					return gridBlock;
				})).get();*/

				final List<Future<long[][]>> futures = myPool.invokeAll( tasks );

				// extract all blocks that failed
				final Set<long[][]> failedBlocksSet = retryTracker.processWithFutures( futures, gridS0 );

				// Use RetryTracker to handle retry counting and removal
				if (!retryTracker.processFailures(failedBlocksSet))
					return null;

				// Update grid for next iteration with remaining failed blocks
				gridS0.clear();
				gridS0.addAll(failedBlocksSet);
			}
			while ( gridS0.size() > 0 );

			myPool.shutdown();
			myPool.awaitTermination(Long.MAX_VALUE, TimeUnit.HOURS);
		}
		catch (Exception e)
		{
			IOFunctions.println( "Failed to write s0 for " + n5Params.format + " '" + n5Params.n5URI + "'. Error: " + e );
			e.printStackTrace();
			return null;
		}

		IJ.showProgress( progress.getAndSet( 0 ), gridS0.size() );
		IOFunctions.println( "Saved level s0, took: " + (System.currentTimeMillis() - time ) + " ms." );

		//
		// Save remaining downsampling levels (s1 ... sN)
		//
		for ( int level = 1; level < downsamplings.length; ++level )
		{
			final int s = level;
			IOFunctions.println( "Downsampling level s" + s + "... " );

			final List<long[][]> allBlocks =
					vidsToResave.stream().map( viewId ->
							N5ApiTools.assembleJobs(
									viewId,
									viewIdToMrInfo.get(viewId)[s] )).flatMap(List::stream).collect( Collectors.toList() );

			time = System.currentTimeMillis();

			try
			{
				final ForkJoinPool myPool = new ForkJoinPool( n5Params.numCellCreatorThreads );
				final RetryTracker<long[][]> retryTracker = RetryTracker.forGridBlocks("s" + s + " resaving", allBlocks.size());

				IOFunctions.println( "Number of compute blocks (s"+s+"): " + allBlocks.size() );
				IJ.showProgress( progress.get(), allBlocks.size() );

				do
				{
					if (!retryTracker.beginAttempt())
						return null;

					final ArrayList< Callable< long[][] > > tasks = new ArrayList<>();

					for ( final long[][] gridBlock : allBlocks )
						tasks.add( () ->
						{
							// 5D OME-ZARR CONTAINER
							if ( n5Params.format == StorageFormat.ZARR )
							{
								N5ApiTools.writeDownsampledBlock5dOMEZARR(
										n5Writer,
										viewIdToMrInfo.get( N5ApiTools.gridBlockToViewId( gridBlock ) )[ s ], //N5ResaveTools.gridToDatasetBdv( s, StorageType.N5 ),
										viewIdToMrInfo.get( N5ApiTools.gridBlockToViewId( gridBlock ) )[ s - 1 ],//N5ResaveTools.gridToDatasetBdv( s - 1, StorageType.N5 ),
										gridBlock,
										0,
										0 );
							}
							else
							{
								N5ApiTools.writeDownsampledBlock(
									n5Writer,
									viewIdToMrInfo.get( N5ApiTools.gridBlockToViewId( gridBlock ) )[ s ], //N5ResaveTools.gridToDatasetBdv( s, StorageType.N5 ),
									viewIdToMrInfo.get( N5ApiTools.gridBlockToViewId( gridBlock ) )[ s - 1 ],//N5ResaveTools.gridToDatasetBdv( s - 1, StorageType.N5 ),
									gridBlock );
							}

							IJ.showProgress( progress.incrementAndGet(), allBlocks.size() );

							return gridBlock.clone();
						});

					final List<Future<long[][]>> futures = myPool.invokeAll( tasks );

					// extract all blocks that failed
					final Set<long[][]> failedBlocksSet = retryTracker.processWithFutures( futures, allBlocks );

					// Use RetryTracker to handle retry counting and removal
					if (!retryTracker.processFailures(failedBlocksSet))
						return null;

					// Update grid for next iteration with remaining failed blocks
					allBlocks.clear();
					allBlocks.addAll(failedBlocksSet);

				} while ( allBlocks.size() > 0 );
				
				myPool.shutdown();
				myPool.awaitTermination( Long.MAX_VALUE, TimeUnit.HOURS );

				/*
			try
			{
				myPool.submit(() -> allBlocks.parallelStream().forEach(
						gridBlock -> 
						{
							// 5D OME-ZARR CONTAINER
							if ( n5Params.format == StorageFormat.ZARR )
							{
								N5ApiTools.writeDownsampledBlock5dOMEZARR(
										n5Writer,
										viewIdToMrInfo.get( N5ApiTools.gridBlockToViewId( gridBlock ) )[ s ], //N5ResaveTools.gridToDatasetBdv( s, StorageType.N5 ),
										viewIdToMrInfo.get( N5ApiTools.gridBlockToViewId( gridBlock ) )[ s - 1 ],//N5ResaveTools.gridToDatasetBdv( s - 1, StorageType.N5 ),
										gridBlock,
										0,
										0 );
							}
							else
							{
								N5ApiTools.writeDownsampledBlock(
									n5Writer,
									viewIdToMrInfo.get( N5ApiTools.gridBlockToViewId( gridBlock ) )[ s ], //N5ResaveTools.gridToDatasetBdv( s, StorageType.N5 ),
									viewIdToMrInfo.get( N5ApiTools.gridBlockToViewId( gridBlock ) )[ s - 1 ],//N5ResaveTools.gridToDatasetBdv( s - 1, StorageType.N5 ),
									gridBlock );
							}

							IJ.showProgress( progress.incrementAndGet(), allBlocks.size() );

							// TOOD: add re-try logic
							return gridBlock;

						} ) ).get();*/
			}
			catch (Exception e)
			{
				IOFunctions.println( "Failed to write downsample step s" + s +" for " + n5Params.format + " '" + n5Params.n5URI + "'. Error: " + e );
				e.printStackTrace();
				return null;
			}

			IJ.showProgress( progress.getAndSet( 0 ), allBlocks.size() );
			IOFunctions.println( "Resaved " + n5Params.format + " s" + s + " level, took: " + (System.currentTimeMillis() - time ) + " ms." );
		}



		if ( n5Params.format == StorageFormat.N5 && URITools.isFile( n5Params.n5URI )) // local file
		{
			// we need to init with File and not with URI, since otherwise the N5ImageLoader will trigger the exception above if this object is re-used
			// this seems to not matter when opening directly from disc...
			sdReduced.getSequenceDescription().setImgLoader( new N5ImageLoader( new File( URITools.fromURI( n5Params.n5URI ) ), sdReduced.getSequenceDescription() ) );
			n5Writer.close();
		}
		else if ( n5Params.format == StorageFormat.N5 ) // some cloud location
		{
			sdReduced.getSequenceDescription().setImgLoader(
					new N5CloudImageLoader( n5Writer, n5Params.n5URI, sdReduced.getSequenceDescription() ) );
		}
		else if ( n5Params.format == StorageFormat.ZARR )
		{
			final Map< ViewId, OMEZARREntry > viewIdToPath = new HashMap<>();

			viewIdToMrInfo.forEach( (viewId, mrInfo ) ->
				viewIdToPath.put(
						viewId,
						new OMEZARREntry(
								mrInfo[ 0 ].dataset.substring(0, mrInfo[ 0 ].dataset.lastIndexOf( "/" ) ),
								new int[] { 0, 0 } ) )
			);

			sdReduced.getSequenceDescription().setImgLoader(
					new AllenOMEZarrLoader( n5Params.n5URI, sdReduced.getSequenceDescription(), viewIdToPath ) );
		}
		else if ( n5Params.format == StorageFormat.HDF5 )
		{
			sdReduced.getSequenceDescription().setImgLoader(
					new Hdf5ImageLoader( new File( URITools.fromURI( n5Params.n5URI ) ), null, sdReduced.getSequenceDescription() ) );
			n5Writer.close();
		}
		else
			throw new RuntimeException( "There is no ImgLoader available for " + n5Params.format + ". Data is resaved, but we will not be able to load it" );

		sdReduced.setBasePathURI( URITools.getParentURINoEx( n5Params.xmlURI ) );

		if ( saveXML )
		{
			progressWriter.out().println( new Date( System.currentTimeMillis() ) + ": Saving " + n5Params.xmlURI );
			new XmlIoSpimData2().save( sdReduced, n5Params.xmlURI );
		}

		progressWriter.setProgress( 1.0 );
		progressWriter.out().println( new Date( System.currentTimeMillis() ) + ": Finished saving " + n5Params.n5URI );

		return sdReduced;
	}

	public static void main(String[] args)
	{
		List<long[][]> grid = Grid.create( new long[] { 500, 500 }, new int[] { 400, 400 }, new int[] { 200, 200 } );

		grid.forEach( b -> {
			System.out.println( Arrays.toString( b[0]));
			System.out.println( Arrays.toString( b[1]));
			System.out.println( Arrays.toString( b[2]));
			System.out.println();
		});

		new ImageJ();
		new Resave_N5Api().run( null );
	}
}
