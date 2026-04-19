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
package net.preibisch.mvrecon.process.export;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;

import bdv.BigDataViewer;
import bdv.util.ConstantRandomAccessible;
import bdv.util.RandomAccessibleIntervalSource;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import ch.epfl.biop.kheops.ometiff.OMETiffExporter.OMETiffExporterBuilder;
import ch.epfl.biop.kheops.ometiff.OMETiffExporter.OMETiffExporterBuilder.Data.DataBuilder;
import fiji.util.gui.GenericDialogPlus;
import mpicbg.spim.data.sequence.ViewDescription;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.BlockAlgoUtils;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.converter.ColorChannelOrder;
import net.imglib2.converter.Converters;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionExportInterface;
import net.preibisch.mvrecon.fiji.plugin.util.PluginHelper;
import net.preibisch.mvrecon.process.deconvolution.DeconViews;
import net.preibisch.mvrecon.process.fusion.blk.BlkAffineFusion;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class ExportLarge2DTIFF implements ImgExport
{
	public static ExecutorService service = DeconViews.createExecutorService();
	public static String defaultPath = null;

	final static String noCompression = "No compression";
	final static int[] imgLib2blockSize = new int[] { 128, 1024, 1 };

	public static int defaultChoiceR = 0;
	public static int defaultChoiceG = 1;
	public static int defaultChoiceB = 2;
	public static String defaultCompression = noCompression;

	File path;
	int choiceR, choiceG, choiceB;
	int numFusionGroups;
	String compression;

	ArrayList< RandomAccessibleInterval< UnsignedByteType > > groups = new ArrayList<>();

	@Override
	public ImgExport newInstance() { return new ExportLarge2DTIFF(); }

	@Override
	public String getDescription() { return "Large 2D-TIFF (supports 8-bit, 2D slices only)"; }

	@Override
	public int[] blocksize() { return imgLib2blockSize.clone(); } // TIFF writer writes Width * 16 blocks, so this is to maximize multi-threading efficiency

	@Override
	public boolean finish() { return true; }

	//@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public <T extends RealType<T> & NativeType<T>> boolean exportImage(
			final BlockSupplier< T > blocks, //RandomAccessibleInterval<T> imgInterval,
			final Interval bb,
			final double downsampling,
			final double anisoF,
			final String title,
			final Group<? extends ViewDescription> fusionGroup )
	{
		// is zero min
		RandomAccessibleInterval<T> imgInterval = BlockAlgoUtils.cellImg( blocks.threadSafe(), bb.dimensionsAsLongArray(), new int[]{ 1024, 1024, 1 } );

		// hack to make the interval divisable by 16 (see https://imagesc.zulipchat.com/#narrow/stream/212929-general/topic/Writing.20large.202D.20TIFFs)
		if ( bb.dimension( 0 ) % 16 != 0 || bb.dimension( 1 ) % 16 != 0 )
		{
			final long[] min = bb.minAsLongArray();
			final long[] max = bb.maxAsLongArray();

			max[ 0 ] += ( 16 - bb.dimension( 0 ) % 16 );
			max[ 1 ] += ( 16 - bb.dimension( 1 ) % 16 );

			final Interval interval = Intervals.zeroMin( new FinalInterval(min, max) );

			IOFunctions.println( "WARNING: changing output interval be divisible by 16:" );
			IOFunctions.println( "OLD: " + Util.printInterval(bb) );
			IOFunctions.println( "OLD zeroMin: " + Util.printInterval(Intervals.zeroMin( bb) ) );
			IOFunctions.println( "NEW zeroMin: " + Util.printInterval(interval) );

			//imgInterval = Views.interval( Views.extendZero( imgInterval ), interval );
			imgInterval = Views.interval( Views.extendZero( imgInterval ), interval );
		}

		// remember all fusiongroups
		//groups.add(Views.zeroMin((RandomAccessibleInterval)(Object)imgInterval) );
		groups.add((RandomAccessibleInterval)(Object)imgInterval );

		// do nothing until all fusiongroups arrived
		if ( groups.size() < numFusionGroups )
		{
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Added fusiongroup & waiting ... ");
			return true;
		}

		// the empty image to choose from
		groups.add( Views.interval( new ConstantRandomAccessible<UnsignedByteType>( new UnsignedByteType(), 3 ), new FinalInterval( imgInterval ) ) );

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Fusing as Large 2D TIFF:'"+path+"'");

		final RandomAccessibleInterval< UnsignedByteType > virtualR = groups.get( choiceR );
		final RandomAccessibleInterval< UnsignedByteType > virtualG = groups.get( choiceG );
		final RandomAccessibleInterval< UnsignedByteType > virtualB = groups.get( choiceB );

		final RandomAccessibleInterval<ARGBType> rgb =
				Converters.mergeARGB( Views.stack( virtualR, virtualG, virtualB ) , ColorChannelOrder.RGB );

		long time = System.currentTimeMillis();

		try
		{
			final DataBuilder<ARGBType> dataBuilder = new OMETiffExporterBuilder.Data.DataBuilder<ARGBType>();
			//final OMETiffExporter.OMETiffExporterBuilder.Data.DataBuilder dataBuilder = OMETiffExporter.builder();

			dataBuilder.putXYZRAI(0, 0, rgb);

			dataBuilder.defineMetaData( "Image" )
			.defineWriteOptions()
			.tileSize(1024, 1024)
			.lzw()
			.nResolutionLevels(1)
			//.monitor(ij.get(TaskService.class))
			.savePath(path.getAbsolutePath())
			.nThreads(Threads.numThreads())
			.maxTilesInQueue(60) // Number of blocks computed in advanced, default 10
			.create()
			.export();

			/*
			OMETiffPyramidizerExporter.builder()
				.tileSize(1024, 1024)
				.lzw()
				//.downsample(2)
				.nResolutionLevels(1)
				//.monitor(taskService) // Monitor
				.maxTilesInQueue(60) // Number of blocks computed in advanced, default 10
				.savePath(path.getAbsolutePath())
				.nThreads(Threads.numThreads())
				.micrometer()
				.create(createSourceAndConverter(rgb))
				.export();*/
		}
		catch (Exception e)
		{
			e.printStackTrace();
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Error writing  Large 2D TIFF file: " + e );
			return false;
		}

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): done. [" + (System.currentTimeMillis() - time ) + " ms]" );

		return true;
	}

	@Override
	public boolean queryParameters( final FusionExportInterface fusion )
	{
		final Interval imgInterval = fusion.getDownsampledBoundingBox();
		final List< Group< ViewDescription > > fusionGroups = fusion.getFusionGroups();
		this.numFusionGroups = fusionGroups.size();

		if ( imgInterval.dimension( 2 ) > 1 )
		{
			IOFunctions.println( "\nERROR: Only 2D export is supported. You can:" );
			IOFunctions.println( " a) export a single slice of a 3D image by defining a bounding box with size=1 in z (minZ = maxZ)");
			IOFunctions.println( " b) export a 2D BigSticher dataset.");

			return false;
		}

		if ( fusionGroups.size() > 3 )
		{
			IOFunctions.println( "\nERROR: You can select 1-3 fusion groups (e.g. 1-3 channels) at once since we need to convert to RGB for export.");
			return false;
		}

		if ( fusion.getPixelType() != 2 )
		{
			IOFunctions.println( "\nERROR: Only 8-bit export is supported by Large 2D TIFF Export.");
			return false;
		}

		final GenericDialogPlus gd = new GenericDialogPlus( "Large 2D TIFF export" );

		if ( defaultPath == null || defaultPath.length() == 0 )
		{
			defaultPath = fusion.getSpimData().getBasePath().getAbsolutePath();

			if ( defaultPath.endsWith( "/." ) )
				defaultPath = defaultPath.substring( 0, defaultPath.length() - 1 );

			if ( defaultPath.endsWith( "/./" ) )
				defaultPath = defaultPath.substring( 0, defaultPath.length() - 2 );

			defaultPath = new File( defaultPath, "fused.tif" ).getAbsolutePath();
		}

		PluginHelper.addSaveAsFileField( gd, "TIFF_file", defaultPath, 80 );

		final String[] choices = new String[ fusionGroups.size() + 1 ];
		choices[ choices.length - 1 ] = "[Empty channel]";

		for ( int i = 0; i < fusionGroups.size(); ++i )
		{
			choices[ i ] = OpenSeaDragon.getNameForFusionGroup( fusionGroups.get( i ), fusion.getSplittingType() );
			if ( choices[ i ] == null )
				return false;
		}

		if ( defaultChoiceR >= choices.length )
			defaultChoiceR = 0;

		if ( defaultChoiceG >= choices.length )
			defaultChoiceG = 0;

		if ( defaultChoiceB >= choices.length )
			defaultChoiceB = 0;

		gd.addChoice( "Red channel", choices, choices[defaultChoiceR] );
		gd.addChoice( "Green channel", choices, choices[defaultChoiceG] );
		gd.addChoice( "Blue channel", choices, choices[defaultChoiceB] );

		//gd.addChoice( "Compression", getSupportedCompressions(), defaultCompression );

		gd.showDialog();
		if ( gd.wasCanceled() )
			return false;

		this.path = new File( defaultPath = gd.getNextString() );

		this.choiceR = defaultChoiceR = gd.getNextChoiceIndex();
		this.choiceG = defaultChoiceG = gd.getNextChoiceIndex();
		this.choiceB = defaultChoiceB = gd.getNextChoiceIndex();
		//this.compression = defaultCompression = gd.getNextChoice();

		return true;
	}

	public static String[] getSupportedCompressions()
	{
		return new String[] { noCompression, "LZW", "JPEG2000", "JPEG2000 lossy", "JPG" };
	}

	public static SourceAndConverter<ARGBType> createSourceAndConverter( RandomAccessibleInterval<ARGBType> img )
	{
		if ( img.numDimensions() == 2 )
			img = Views.addDimension( img, 0, 0 );

		final Source< ARGBType > source = new RandomAccessibleIntervalSource<>( img, new ARGBType(), new AffineTransform3D(), "noname" );
		return new SourceAndConverter<>( source, BigDataViewer.createConverterToARGB( new ARGBType() ) );
	}

	public static void main( String[] args )
	{
		long size = 33;
		System.out.println( size + " >> " + size + (16 - size % 16) );
		
		final FunctionRandomAccessible<ARGBType> checkerboard = new FunctionRandomAccessible<>(
				2,
				(location, value) -> {
					value.set(
							Math.abs(location.getIntPosition(0)) % 10 +
									(-Math.abs(location.getIntPosition(1))));// % 10 +Math.abs(location.getIntPosition(2)) % 10 );
				},
				ARGBType::new);

		RandomAccessibleInterval<ARGBType> img = Views.interval( checkerboard , new FinalInterval( new long[] { 0, 0 }, new long[] { 500, 100 }));

		if ( img.dimension( 0 ) % 16 != 0 || img.dimension( 1 ) % 16 != 0 )
		{
			final long[] min = img.minAsLongArray();
			final long[] max = img.maxAsLongArray();

			max[ 0 ] += ( 16 - img.dimension( 0 ) % 16 );
			max[ 1 ] += ( 16 - img.dimension( 1 ) % 16 );

			final Interval interval = new FinalInterval(min, max);

			IOFunctions.println( "WARNING: changing output interval be divisible by 16:" );
			IOFunctions.println( "OLD: " + Util.printInterval(img) );
			IOFunctions.println( "NEW: " + Util.printInterval(interval) );

			img = Views.interval( Views.extendZero( img ), interval );
		}

		ImageJFunctions.show( img  );
		//SimpleMultiThreading.threadHaltUnClean();
		//converterSetups.add( BigDataViewer.createConverterSetup( soc, setupId ) );
		//sources.add( soc );

		//SourceAndConverter sac = new SourceAndConverter(null, converter);

		try {
			Instant start = Instant.now();

			final DataBuilder<ARGBType> dataBuilder = new OMETiffExporterBuilder.Data.DataBuilder<ARGBType>();
			//DataBuilder<ARGBType> dataBuilder = OMETiffExporter.builder();

			dataBuilder.putXYZRAI(0, 0, img);

			dataBuilder.defineMetaData( "Image" )
			.defineWriteOptions()
			.tileSize(Math.min(1024,(int)img.dimension(0)), Math.min(1024,(int)img.dimension(1)))
			.lzw()
			.nResolutionLevels(1)
			//.monitor(ij.get(TaskService.class))
			.savePath("/Users/preibischs/Downloads/test24a.tiff")
			.nThreads(Threads.numThreads())
			.maxTilesInQueue(60) // Number of blocks computed in advanced, default 10
			.create()
			.export();

			Instant end = Instant.now();

            System.out.println("Export time (ms) \t" + Duration.between(start, end).toMillis());

			/*
			OMETiffPyramidizerExporter.builder()
				.tileSize(Math.min(1024,(int)img.dimension(0)), Math.min(1024,(int)img.dimension(1)))
				.lzw()
				//.downsample(2)
				.nResolutionLevels(1)
				//.monitor(taskService) // Monitor
				.maxTilesInQueue(60) // Number of blocks computed in advanced, default 10
				.savePath("/Users/preibischs/Downloads/test24a.tiff")
				.nThreads(Threads.numThreads())
				.micrometer()
				.create(createSourceAndConverter(img))
				.export();
			*/
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		System.out.println( "done");
	}
}
