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
package net.preibisch.mvrecon.process.n5api;

import static org.janelia.saalfeldlab.n5.DataType.FLOAT32;
import static org.janelia.saalfeldlab.n5.DataType.UINT16;
import static org.janelia.saalfeldlab.n5.DataType.UINT8;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.janelia.saalfeldlab.n5.Compression;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.RawCompression;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.OmeNgffMultiScaleMetadata;

import bdv.export.ExportMipmapInfo;
import bdv.util.MipmapTransforms;
import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.SetupImgLoader;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.BlockAlgoUtils;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.algorithm.blocks.downsample.Downsample;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.Cast;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.OMEZarrAttibutes;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import util.Grid;

public class N5ApiTools
{
	static final EnumSet< DataType > supportedDataTypes = EnumSet.of( UINT8, UINT16, FLOAT32 );

	public static ViewId gridBlockToViewId( final long[][] gridBlock )
	{
		if ( gridBlock.length <= 3 )
			throw new RuntimeException( "gridBlockToViewId() needs an extended GridBlock long[][], where Gridblock[3][] encodes the ViewId");

		return new ViewId( (int)gridBlock[ 3 ][ 0 ], (int)gridBlock[ 3 ][ 1 ]);
	}

	/**
	 * @param level - the downsampling level
	 * @param storageType - N5 or HDF5 (soon Zarr)
	 * @return a Function that maps the gridBlock to a N5 dataset name
	 */
	public static Function<long[][], String> gridToDatasetBdv( final int level, final StorageFormat storageType )
	{
		return (gridBlock) -> viewIdToDatasetBdv( level, storageType ).apply( gridBlockToViewId( gridBlock ) );
	}

	/**
	 * @param level - the downsampling level
	 * @param storageType - N5 or HDF5 (soon Zarr)
	 * @return a Function that maps the ViewId to a N5 dataset name
	 */
	public static Function<ViewId, String> viewIdToDatasetBdv( final int level, final StorageFormat storageType )
	{
		return (viewId) -> createBDVPath( viewId, level, storageType );
	}

	/**
	 * @param storageType - N5 or HDF5 (soon Zarr)
	 * @return a Function that maps (ViewId, level) to a N5 dataset name
	 */
	public static BiFunction<ViewId, Integer, String> viewIdToDatasetBdv( final StorageFormat storageType )
	{
		return (viewId, level) -> viewIdToDatasetBdv( level, storageType ).apply( viewId );
	}

	/**
	 * @param storageType - N5 or HDF5 (soon Zarr)
	 * @return a Function that maps (gridBlock, level) to a N5 dataset name
	 */
	public static BiFunction<long[][], Integer, String> gridToDatasetBdv( final StorageFormat storageType )
	{
		return (gridBlock, level) -> gridToDatasetBdv( level, storageType ).apply( gridBlock );
	}

	public static ViewId getViewId(final String bdvString )
	{
		final String[] entries = bdvString.trim().split( "," );
		final int timepointId = Integer.parseInt( entries[ 0 ].trim() );
		final int viewSetupId = Integer.parseInt( entries[ 1 ].trim() );

		return new ViewId(timepointId, viewSetupId);
	}

	public static String createBDVPath(final String bdvString, final int level, final StorageFormat storageType)
	{
		return createBDVPath( getViewId( bdvString ), level, storageType);
	}

	public static String createBDVPath( final ViewId viewId, final int level, final StorageFormat storageType)
	{
		String path = null;

		if ( StorageFormat.N5.equals(storageType) )
		{
			path = "setup" + viewId.getViewSetupId() + "/" + "timepoint" + viewId.getTimePointId() + "/s" + level;
		}
		else if ( StorageFormat.HDF5.equals(storageType) )
		{
			path = "t" + String.format("%05d", viewId.getTimePointId()) + "/" + "s" + String.format("%02d", viewId.getViewSetupId()) + "/" + level + "/cells";
		}
		else if ( StorageFormat.ZARR.equals( storageType ) )
		{
			path = "s" + viewId.getViewSetupId() + "-t" + viewId.getTimePointId() + ".zarr/" + level;
		}
		else
		{
			new RuntimeException( "BDV-compatible dataset cannot be written for " + storageType + " (yet).");
		}

		return path;
	}

	public static String createDownsampledBDVPath( final String s0path, final int level, final StorageFormat storageType )
	{
		if ( StorageFormat.N5.equals(storageType) )
		{
			return s0path.substring( 0, s0path.length() - 3 ) + "/s" + level;
		}
		else if ( StorageFormat.HDF5.equals(storageType) )
		{
			return s0path.substring( 0, s0path.length() - 8 ) + "/" + level + "/cells";
		}
		else
		{
			throw new RuntimeException( "BDV-compatible dataset cannot be written for " + storageType + " (yet).");
		}
	}

	public static class MultiResolutionLevelInfo implements Serializable
	{
		private static final long serialVersionUID = 5392269335394869108L;

		final public int[] relativeDownsampling, absoluteDownsampling, blockSize;
		final public long[] dimensions;
		final public String dataset;
		final public DataType dataType;

		public MultiResolutionLevelInfo(
				final String dataset,
				final long[] dimensions,
				final DataType dataType,
				final int[] relativeDownsampling,
				final int[] absoluteDownsampling,
				final int[] blockSize )
		{
			this.dataset = dataset;
			this.dimensions = dimensions;
			this.dataType = dataType;
			this.relativeDownsampling = relativeDownsampling;
			this.absoluteDownsampling = absoluteDownsampling;
			this.blockSize = blockSize;
		}

		public double[] absoluteDownsamplingDouble()
		{
			return Arrays.stream( absoluteDownsampling ).asDoubleStream().toArray();
		}

		public int[] absoluteDownsamplingInt()
		{
			return absoluteDownsampling;
		}
	}

	public static MultiResolutionLevelInfo[] setupMultiResolutionPyramid(
			final N5Writer driverVolumeWriter,
			final Function<Integer, String> levelToDataset,
			final DataType dataType,
			final long[] dimensionsS0,
			final Compression compression,
			final int[] blockSize,
			final int[][] downsamplings )
	{
		return setupMultiResolutionPyramid(
				driverVolumeWriter,
				null,
				(viewId, level) -> levelToDataset.apply( level ),
				dataType,
				dimensionsS0,
				compression,
				blockSize,
				downsamplings);
	}

	public static MultiResolutionLevelInfo[] setupMultiResolutionPyramid(
			final N5Writer driverVolumeWriter,
			final ViewId viewId,
			final BiFunction<ViewId, Integer, String> viewIdToDataset,
			final DataType dataType,
			final long[] dimensionsS0, // 3d by default, can be up to 5d for ome-zarr
			final Compression compression,
			final int[] blockSize, // 3d by default, can be up to 5d for ome-zarr
			final int[][] downsamplings ) // TODO:  3d by default, can be up to 5d for ome-zarr
	{
		final MultiResolutionLevelInfo[] mrInfo = new MultiResolutionLevelInfo[ downsamplings.length];

		// set up s0
		int[] relativeDownsampling = downsamplings[ 0 ].clone();
		Arrays.setAll( relativeDownsampling, i -> 1 );

		mrInfo[ 0 ] = new MultiResolutionLevelInfo(
				viewIdToDataset.apply( viewId, 0 ), dimensionsS0.clone(), dataType, relativeDownsampling, downsamplings[ 0 ], blockSize );

		driverVolumeWriter.createDataset(
				viewIdToDataset.apply( viewId, 0 ),
				dimensionsS0,
				blockSize,
				dataType,
				compression );

		long[] previousDim = dimensionsS0.clone();

		// set up s1 ... sN
		for ( int level = 1; level < downsamplings.length; ++level )
		{
			relativeDownsampling = computeRelativeDownsampling( downsamplings, level );

			final String datasetLevel = viewIdToDataset.apply( viewId, level );

			final long[] dim = new long[ previousDim.length ];
			for ( int d = 0; d < dim.length; ++d )
				dim[ d ] = previousDim[ d ] / relativeDownsampling[ d ];

			mrInfo[ level ] = new MultiResolutionLevelInfo(
					datasetLevel, dim.clone(), dataType, relativeDownsampling, downsamplings[ level ], blockSize );

			driverVolumeWriter.createDataset(
					datasetLevel,
					dim,
					blockSize,
					dataType,
					compression );

			driverVolumeWriter.setAttribute( datasetLevel, "downsamplingFactors", downsamplings[ level ] );

			previousDim = dim;
		}

		return mrInfo;
	}

	public static String[] exportOptions()
	{
		return Arrays.asList(StorageFormat.values()).stream().map(s -> s.name().equals("ZARR") ? "OME-ZARR" : s.name())
				.toArray(String[]::new);
	}

	public static MultiResolutionLevelInfo[] setupBdvDatasetsHDF5(
			final N5Writer driverVolumeWriter,
			final ViewId viewId,
			final DataType dataType,
			final long[] dimensions,
			final Compression compression,
			final int[] blockSize,
			final int[][] downsamplings )
	{
		final MultiResolutionLevelInfo[] mrInfo = setupMultiResolutionPyramid(
				driverVolumeWriter,
				viewId,
				viewIdToDatasetBdv( StorageFormat.HDF5 ),
				dataType,
				dimensions,
				compression,
				blockSize,
				downsamplings);

		final String subdivisionsDatasets = "s" + String.format("%02d", viewId.getViewSetupId()) + "/subdivisions";
		final String resolutionsDatasets = "s" + String.format("%02d", viewId.getViewSetupId()) + "/resolutions";

		if ( !driverVolumeWriter.datasetExists( subdivisionsDatasets ) || !driverVolumeWriter.datasetExists( resolutionsDatasets ) )
		{
			final Img<IntType> subdivisions;
			final Img<DoubleType> resolutions;
	
			if ( downsamplings == null || downsamplings.length == 0 )
			{
				subdivisions = ArrayImgs.ints( blockSize, new long[] { 3, 1 } ); // blocksize
				resolutions = ArrayImgs.doubles( new double[] { 1,1,1 }, new long[] { 3, 1 } ); // downsampling
			}
			else
			{
				final int[] blocksizes = new int[ 3 * downsamplings.length ];
				final double[] downsamples = new double[ 3 * downsamplings.length ];
	
				int i = 0;
				for ( int level = 0; level < downsamplings.length; ++level )
				{
					downsamples[ i ] = downsamplings[ level ][ 0 ];
					blocksizes[ i++ ] = blockSize[ 0 ];
					downsamples[ i ] = downsamplings[ level ][ 1 ];
					blocksizes[ i++ ] = blockSize[ 1 ];
					downsamples[ i ] = downsamplings[ level ][ 2 ];
					blocksizes[ i++ ] = blockSize[ 2 ];
				}
	
				subdivisions = ArrayImgs.ints( blocksizes, new long[] { 3, downsamplings.length } ); // blocksize
				resolutions = ArrayImgs.doubles( downsamples, new long[] { 3, downsamplings.length } ); // downsampling
			}

			driverVolumeWriter.createDataset(
					subdivisionsDatasets,
					subdivisions.dimensionsAsLongArray(),// new long[] { 3, 1 },
					Arrays.stream( subdivisions.dimensionsAsLongArray() ).mapToInt(i -> (int) i).toArray(),//new int[] { (int)subdivisions.dimension( 0 ), (int)subdivisions.dimension( 1 ) }, //new int[] { 3, 1 },
					DataType.INT32,
					new RawCompression() );
	
			driverVolumeWriter.createDataset(
					resolutionsDatasets,
					resolutions.dimensionsAsLongArray(),// new long[] { 3, 1 },
					Arrays.stream( resolutions.dimensionsAsLongArray() ).mapToInt(i -> (int) i).toArray(),//new int[] { (int)resolutions.dimension( 0 ), (int)resolutions.dimension( 1 ) },//new int[] { 3, 1 },
					DataType.FLOAT64,
					new RawCompression() );
			
			N5Utils.saveBlock(subdivisions, driverVolumeWriter, "s" + String.format("%02d", viewId.getViewSetupId()) + "/subdivisions", new long[] {0,0} );
			N5Utils.saveBlock(resolutions, driverVolumeWriter, "s" + String.format("%02d", viewId.getViewSetupId()) + "/resolutions", new long[] {0,0} );
		}

		return mrInfo;
	}

	public static MultiResolutionLevelInfo[] setupBdvDatasetsOMEZARR(
			final N5Writer driverVolumeWriter,
			final ViewId viewId,
			final DataType dataType,
			final long[] dimensions,
			final double[] resolutionS0,
			final String resolutionUnit,
			final Compression compression,
			final int[] blockSize,
			int[][] downsamplings )
	{
		final String s0Dataset = viewIdToDatasetBdv( StorageFormat.ZARR ).apply( viewId, 0 );
		final String baseDataset = s0Dataset.substring(0, s0Dataset.lastIndexOf( "/" ) + 1);

		IOFunctions.println( "Creating 5D OME-ZARR metadata for '" + baseDataset + "' ... " );

		final long[] dim5d = new long[] { dimensions[ 0 ], dimensions[ 1 ], dimensions[ 2 ], 1, 1 };
		final int[] blockSize5d = new int[] { blockSize[ 0 ], blockSize[ 1 ], blockSize[ 2 ], 1, 1 };
		final int[][] ds5d = new int[ downsamplings.length ][];
		for ( int d = 0; d < ds5d.length; ++d )
			ds5d[ d ] = new int[] { downsamplings[ d ][ 0 ], downsamplings[ d ][ 1 ], downsamplings[ d ][ 2 ], 1, 1 };

		final Function<Integer, String> levelToName = (level) -> "/" + level;

		// all is 5d now
		final MultiResolutionLevelInfo[] mrInfo = N5ApiTools.setupMultiResolutionPyramid(
				driverVolumeWriter,
				viewId,
				viewIdToDatasetBdv( StorageFormat.ZARR ),
				dataType,
				dim5d, //5d
				compression,
				blockSize5d, //5d
				ds5d ); // 5d

		final Function<Integer, AffineTransform3D> levelToMipmapTransform =
				(level) -> MipmapTransforms.getMipmapTransformDefault( mrInfo[level].absoluteDownsamplingDouble() );

		// create metadata
		final OmeNgffMultiScaleMetadata[] meta = OMEZarrAttibutes.createOMEZarrMetadata(
				5, // int n
				"/", // String name, I also saw "/"
				resolutionS0, // double[] resolutionS0,
				resolutionUnit, //vx.unit() might not be OME-ZARR compatible // String unitXYZ, // e.g micrometer
				mrInfo.length, // int numResolutionLevels,
				levelToName,
				levelToMipmapTransform );

		// save metadata
		driverVolumeWriter.setAttribute( baseDataset, "multiscales", meta );

		return mrInfo;
	}

	public static MultiResolutionLevelInfo[] setupBdvDatasetsN5(
			final N5Writer driverVolumeWriter,
			final ViewId viewId,
			final DataType dataType,
			final long[] dimensions,
			final Compression compression,
			final int[] blockSize,
			int[][] downsamplings )
	{
		final MultiResolutionLevelInfo[] mrInfo = setupMultiResolutionPyramid(
				driverVolumeWriter,
				viewId,
				viewIdToDatasetBdv( StorageFormat.N5 ),
				dataType,
				dimensions,
				compression,
				blockSize,
				downsamplings);

		final String s0Dataset = createBDVPath( viewId, 0, StorageFormat.N5 );
		final String setupDataset = s0Dataset.substring(0, s0Dataset.indexOf( "/timepoint" ));
		final String timepointDataset = s0Dataset.substring(0, s0Dataset.indexOf("/s0" ));

		final Map<String, Class<?>> attribs = driverVolumeWriter.listAttributes( setupDataset );

		// if viewsetup does not exist
		if ( !attribs.containsKey( DatasetAttributes.DATA_TYPE_KEY ) || !attribs.containsKey( DatasetAttributes.BLOCK_SIZE_KEY ) || !attribs.containsKey( DatasetAttributes.DIMENSIONS_KEY ) || !attribs.containsKey( DatasetAttributes.COMPRESSION_KEY ) || !attribs.containsKey( "downsamplingFactors" ) )
		{
			// set N5 attributes for setup
			// e.g. {"compression":{"type":"gzip","useZlib":false,"level":1},"downsamplingFactors":[[1,1,1],[2,2,1]],"blockSize":[128,128,32],"dataType":"uint16","dimensions":[512,512,86]}
			IOFunctions.println( "setting attributes for '" + "setup" + viewId.getViewSetupId() + "'");

			final HashMap<String, Object > attribs2 = new HashMap<>();
			attribs2.put(DatasetAttributes.DATA_TYPE_KEY, dataType );
			attribs2.put(DatasetAttributes.BLOCK_SIZE_KEY, blockSize );
			attribs2.put(DatasetAttributes.DIMENSIONS_KEY, dimensions );
			attribs2.put(DatasetAttributes.COMPRESSION_KEY, compression );

			if ( downsamplings == null || downsamplings.length == 0 )
				attribs2.put( "downsamplingFactors", new int[][] {{1,1,1}} );
			else
				attribs2.put( "downsamplingFactors", downsamplings );

			driverVolumeWriter.setAttributes (setupDataset, attribs2 );
		}
		else
		{
			// TODO: test that the values are consistent?
		}

		// set N5 attributes for timepoint
		// e.g. {"resolution":[1.0,1.0,3.0],"saved_completely":true,"multiScale":true}
		driverVolumeWriter.setAttribute(timepointDataset, "resolution", new double[] {1,1,1} );
		driverVolumeWriter.setAttribute(timepointDataset, "saved_completely", true );
		driverVolumeWriter.setAttribute(timepointDataset, "multiScale", downsamplings != null && downsamplings.length != 0 );

		if ( downsamplings == null || downsamplings.length == 0 )
		{
			downsamplings = new int[1][ dimensions.length ];
			Arrays.setAll( downsamplings[ 0 ], i -> 1 );
		}

		// set additional N5 attributes for s0 ... sN datasets
		for ( int level = 0; level < downsamplings.length; ++level )
			driverVolumeWriter.setAttribute( mrInfo[ level ].dataset, "downsamplingFactors", mrInfo[ level ].absoluteDownsampling );

		return mrInfo;
	}

	public static < T extends NativeType< T > & RealType< T > > void writeDownsampledBlock(
			final N5Writer n5,
			final MultiResolutionLevelInfo mrInfo,
			final MultiResolutionLevelInfo mrInfoPreviousScale,
			final long[][] gridBlock )
	{
		final String dataset = mrInfo.dataset;
		final String datasetPreviousScale = mrInfoPreviousScale.dataset;

		final DataType dataType = mrInfo.dataType;

		if ( !supportedDataTypes.contains( dataType ) )
		{
			n5.close();
			throw new RuntimeException("Unsupported pixel type: " + dataType );
		}

		final RandomAccessibleInterval<T> previousScale = N5Utils.open(n5, datasetPreviousScale);
		final T type = previousScale.getType().createVariable();

		final BlockSupplier< T > blocks = BlockSupplier.of( previousScale ).andThen( Downsample.downsample( mrInfo.relativeDownsampling ) );
		final long[] dimensions = n5.getAttribute( dataset, DatasetAttributes.DIMENSIONS_KEY, long[].class );
		final RandomAccessibleInterval< T > downsampled = BlockAlgoUtils.cellImg( blocks, dimensions, new int[] { 64 } );

		final RandomAccessibleInterval<T> sourceGridBlock = Views.offsetInterval(downsampled, gridBlock[0], gridBlock[1]);
		N5Utils.saveNonEmptyBlock(sourceGridBlock, n5, dataset, gridBlock[2], type);
	}

	public static < T extends NativeType< T > & RealType< T > > void writeDownsampledBlock5dOMEZARR(
			final N5Writer n5,
			final MultiResolutionLevelInfo mrInfo,
			final MultiResolutionLevelInfo mrInfoPreviousScale,
			final long[][] gridBlock,
			final long currentChannelIndex,
			final long currentTPIndex )
	{
		final String dataset = mrInfo.dataset;
		final String datasetPreviousScale = mrInfoPreviousScale.dataset;

		final DataType dataType = mrInfo.dataType;

		if ( !supportedDataTypes.contains( dataType ) )
		{
			n5.close();
			throw new RuntimeException("Unsupported pixel type: " + dataType );
		}

		final long[] blockOffset, blockSize, gridOffset;

		// gridBlock is 3d, make it 5d
		blockOffset = new long[] { gridBlock[0][0], gridBlock[0][1], gridBlock[0][2], currentChannelIndex, currentTPIndex };
		blockSize = new long[] { gridBlock[1][0], gridBlock[1][1], gridBlock[1][2], 1, 1 };
		gridOffset = new long[] { gridBlock[2][0], gridBlock[2][1], gridBlock[2][2], currentChannelIndex, currentTPIndex }; // because blocksize in C & T is 1

		// cut out the relevant 3D block
		final RandomAccessibleInterval<T> previousScaleRaw = N5Utils.open(n5, datasetPreviousScale);
		final RandomAccessibleInterval<T> previousScale = Views.hyperSlice( Views.hyperSlice( previousScaleRaw, 4, currentTPIndex ), 3, currentChannelIndex );
		final T type = previousScale.getType().createVariable();

		final BlockSupplier< T > blocks = BlockSupplier.of( previousScale ).andThen( Downsample.downsample( mrInfo.relativeDownsampling ) );

		// make dimensions 3d
		final long[] dimensionsRaw = n5.getAttribute( dataset, DatasetAttributes.DIMENSIONS_KEY, long[].class );
		final long[] dimensions = new long[] { dimensionsRaw[ 0 ], dimensionsRaw[ 1 ], dimensionsRaw[ 2 ] };

		final RandomAccessibleInterval< T > downsampled3d = BlockAlgoUtils.cellImg( blocks, dimensions, new int[] { 64 } );

		// the same information is returned no matter which index is queried in C and T
		final RandomAccessible< T > downsampled5d = Views.addDimension( Views.addDimension( downsampled3d ) );

		final RandomAccessibleInterval<T> sourceGridBlock = Views.offsetInterval(downsampled5d, blockOffset, blockSize);
		N5Utils.saveNonEmptyBlock(sourceGridBlock, n5, dataset, gridOffset, type);
	}

	public static List<long[][]> assembleJobs( final MultiResolutionLevelInfo mrInfo )
	{
		return assembleJobs( null, mrInfo );
	}

	public static List<long[][]> assembleJobs( final MultiResolutionLevelInfo mrInfo, final int[] computeBlockSize )
	{
		return assembleJobs( null, mrInfo, computeBlockSize );
	}

	public static List<long[][]> assembleJobs(
			final ViewId viewId,
			final MultiResolutionLevelInfo mrInfo )
	{
		return assembleJobs( viewId, mrInfo.dimensions, mrInfo.blockSize, mrInfo.blockSize );
	}

	public static List<long[][]> assembleJobs(
			final ViewId viewId,
			final MultiResolutionLevelInfo mrInfo,
			final int[] computeBlockSize )
	{
		return assembleJobs( viewId, mrInfo.dimensions, mrInfo.blockSize, computeBlockSize );
	}

	public static List<long[][]> assembleJobs(
			final long[] dimensions,
			final int[] blockSize )
	{
		return assembleJobs(null, dimensions, blockSize, blockSize );
	}

	public static List<long[][]> assembleJobs(
			final long[] dimensions,
			final int[] blockSize,
			final int[] computeBlockSize )
	{
		return assembleJobs(null, dimensions, blockSize, computeBlockSize );
	}

	public static List<long[][]> assembleJobs(
			final ViewId viewId, //can be null
			final long[] dimensions,
			final int[] blockSize )
	{
		return assembleJobs(viewId, dimensions, blockSize, blockSize );
	}

	public static List<long[][]> assembleJobs(
			final ViewId viewId, //can be null
			final long[] dimensions,
			final int[] blockSize,
			final int[] computeBlockSize )
	{
		final List<long[][]> grid = Grid.create(
				dimensions,
				computeBlockSize,
				blockSize);

		if ( viewId != null )
		{
			// all blocks (a.k.a. grids)
			final List<long[][]> allBlocks = new ArrayList<>();

			// add timepointId and ViewSetupId & dimensions to the gridblock
			for ( final long[][] gridBlock : grid )
				allBlocks.add( new long[][]{
					gridBlock[ 0 ].clone(),
					gridBlock[ 1 ].clone(),
					gridBlock[ 2 ].clone(),
					new long[] { viewId.getTimePointId(), viewId.getViewSetupId() }
				});
			return allBlocks;
		}
		else
		{
			return grid;
		}
	}

	public static int[] computeRelativeDownsampling(
			final int[][] downsamplings,
			final int level )
	{
		final int[] ds = new int[ downsamplings[ 0 ].length ];

		for ( int d = 0; d < ds.length; ++d )
			ds[ d ] = downsamplings[ level ][ d ] / downsamplings[ level - 1 ][ d ];

		return ds;
	}

	public static <T extends NativeType<T>> void resaveS0Block(
			final SpimData2 data,
			final N5Writer n5,
			final StorageFormat storageType,
			final DataType dataType,
			final Function<long[][], String> gridBlockToDataset, // gridBlock to dataset name for s0
			final long[][] gridBlock )
	{
		final ViewId viewId = gridBlockToViewId( gridBlock );
		final String dataset = gridBlockToDataset.apply( gridBlock );

		if ( !supportedDataTypes.contains( dataType ) )
		{
			n5.close();
			throw new RuntimeException( "Unsupported pixel type: " + dataType );
		}

		final SetupImgLoader< ? > imgLoader = data.getSequenceDescription().getImgLoader().getSetupImgLoader( viewId.getViewSetupId() );
		final RandomAccessibleInterval< T > img = Cast.unchecked( imgLoader.getImage( viewId.getTimePointId() ) );

		final long[] blockOffset, blockSize, gridOffset;
		final RandomAccessible< T >image;

		// 5D OME-ZARR CONTAINER
		if ( storageType == StorageFormat.ZARR )
		{
			// gridBlock is 3d, make it 5d
			blockOffset = new long[] { gridBlock[0][0], gridBlock[0][1], gridBlock[0][2], 0, 0 };
			blockSize = new long[] { gridBlock[1][0], gridBlock[1][1], gridBlock[1][2], 1, 1 };
			gridOffset = new long[] { gridBlock[2][0], gridBlock[2][1], gridBlock[2][2], 0, 0 }; // because blocksize in C & T is 1

			// img is 3d, make it 5d
			// the same information is returned no matter which index is queried in C and T
			image = Views.addDimension( Views.addDimension( img ) );
		}
		else
		{
			blockOffset = gridBlock[0];
			blockSize = gridBlock[1];
			gridOffset = gridBlock[2];

			image = img;
		}

		final RandomAccessibleInterval< T > sourceGridBlock = Views.offsetInterval( image, blockOffset, blockSize );
		N5Utils.saveNonEmptyBlock( sourceGridBlock, n5, dataset, gridOffset, image.getType().createVariable() );

		System.out.println( "ViewId " + Group.pvid( viewId ) + ", written block: offset=" + Util.printCoordinates( blockOffset ) + ", dimension=" + Util.printCoordinates( blockSize ) );
	}

	public static Map< Integer, DataType > assembleDataTypes(
			final AbstractSpimData< ? > data,
			final Collection< Integer > viewSetupIds )
	{
		final HashMap< Integer, DataType > dataTypes = new HashMap<>();

		for ( final int viewSetupId : viewSetupIds )
		{
			final Object type = data.getSequenceDescription().getImgLoader().getSetupImgLoader( viewSetupId ).getImageType();
			final DataType dataType = type instanceof NativeType ? N5Utils.dataType( Cast.unchecked( type ) ) : null;
			if ( !supportedDataTypes.contains( dataType ) )
			{
				throw new RuntimeException( "Unsupported pixel type: " + type.getClass().getCanonicalName() );
			}

			dataTypes.put( viewSetupId, dataType );
		}

		return dataTypes;
	}

	public static int[][] mipMapInfoToDownsamplings( final Map< Integer, ExportMipmapInfo > mipmaps )
	{
		int[][] downsamplings = mipmaps.values().iterator().next().getExportResolutions();

		// just find the biggest one (most steps) and use it for all
		for ( final ExportMipmapInfo info : mipmaps.values() )
			if (info.getExportResolutions().length > downsamplings.length)
				downsamplings = info.getExportResolutions();

		return downsamplings;
	}

	public static HashMap<Integer, long[]> assembleDimensions(
			final SpimData data,
			final Collection< ? extends ViewId > viewIds )
	{
		final HashMap< Integer, long[] > viewSetupIdToDimensions = new HashMap<>();
		final Map<ViewId, ViewDescription> map = data.getSequenceDescription().getViewDescriptions();

		viewIds.forEach( viewId -> viewSetupIdToDimensions.put( viewId.getViewSetupId(), map.get( viewId ).getViewSetup().getSize().dimensionsAsLongArray() ) );

		return viewSetupIdToDimensions;
	}

	public static ArrayList< ViewSetup > assembleViewSetups(
			final SpimData data,
			final Collection< ? extends ViewId > viewIds )
	{
		final ArrayList< ViewSetup > list = new ArrayList<>();
		final Map<ViewId, ViewDescription> map = data.getSequenceDescription().getViewDescriptions();

		viewIds.forEach( viewId -> list.add( map.get( viewId ).getViewSetup() ) );

		return list;
	}

	/*
	public static int numTimepoints( final List<? extends Group<? extends ViewDescription>> fusionGroups )
	{
		final HashSet< TimePoint > tps = new HashSet<>();

		for ( final Group<? extends ViewDescription> group : fusionGroups )
			for ( final ViewDescription vd : group )
				tps.add( vd.getTimePoint() );

		return tps.size();
	}

	public static int numChannels( final List<? extends Group<? extends ViewDescription>> fusionGroups )
	{
		final HashSet< Channel > channels = new HashSet<>();

		for ( final Group<? extends ViewDescription> group : fusionGroups )
			for ( final ViewDescription vd : group )
				channels.add( vd.getViewSetup().getChannel() );

		return channels.size();
	}
	*/

	public static ArrayList< TimePoint > timepoints( final List<? extends Group<? extends ViewDescription>> fusionGroups )
	{
		final ArrayList< TimePoint > tps = new ArrayList<>();

		for ( final Group<? extends ViewDescription> group : fusionGroups )
		{
			for ( final ViewDescription vd : group )
			{
				final TimePoint newT = vd.getTimePoint();
				boolean contains = false;

				for ( final TimePoint t : tps )
					if ( t.getId() == newT.getId() )
						contains = true;

				if ( !contains )
					tps.add( newT );
			}
		}

		Collections.sort( tps, ( t1, t2 ) -> t1.getId() - t2.getId() );

		return tps;
	}

	public static ArrayList< Channel > channels( final List<? extends Group<? extends ViewDescription>> fusionGroups )
	{
		final ArrayList< Channel > channels = new ArrayList<>();

		for ( final Group<? extends ViewDescription> group : fusionGroups )
		{
			for ( final ViewDescription vd : group )
			{
				final Channel newC = vd.getViewSetup().getChannel();
				boolean contains = false;

				for ( final Channel c : channels )
					if ( c.getId() == newC.getId() )
						contains = true;

				if ( !contains )
					channels.add( newC );
			}
		}

		Collections.sort( channels );

		return channels;
	}

	public static int channelIndex( final Group<? extends ViewDescription > queryGroup, final ArrayList< Channel > channels )
	{
		if ( queryGroup.size() == 0 )
			return -1;

		final Channel firstChannel = queryGroup.iterator().next().getViewSetup().getChannel();

		for ( final ViewDescription vd : queryGroup )
			if ( vd.getViewSetup().getChannel().getId() != firstChannel.getId() )
				throw new RuntimeException( "More than one channel in the queryGroup, cannot return a single index." );

		for ( int i = 0; i < channels.size(); ++i )
			if ( channels.get( i ).getId() == firstChannel.getId() )
				return i;

		return -1;
	}

	public static int timepointIndex( final Group<? extends ViewDescription > queryGroup, final ArrayList< TimePoint > timepoints )
	{
		if ( queryGroup.size() == 0 )
			return -1;

		final TimePoint firstTP = queryGroup.iterator().next().getTimePoint();

		for ( final ViewDescription vd : queryGroup )
			if ( vd.getTimePoint().getId() != firstTP.getId() )
				throw new RuntimeException( "More than one timepoint in the queryGroup, cannot return a single index." );

		for ( int i = 0; i < timepoints.size(); ++i )
			if ( timepoints.get( i ).getId() == firstTP.getId() )
				return i;

		return -1;
	}
}
