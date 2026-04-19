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

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import bdv.util.ConstantRandomAccessible;
import fiji.util.gui.GenericDialogPlus;
import gov.nist.isg.archiver.DirectoryArchiver;
import gov.nist.isg.pyramidio.PartialImageReader;
import gov.nist.isg.pyramidio.ScalablePyramidBuilder;
import mpicbg.spim.data.sequence.ViewDescription;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.converter.ColorChannelOrder;
import net.imglib2.converter.Converters;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionExportInterface;
import net.preibisch.mvrecon.fiji.plugin.util.PluginHelper;
import net.preibisch.mvrecon.process.fusion.blk.BlkAffineFusion;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import util.BlockSupplierUtils;

public class OpenSeaDragon implements ImgExport
{
	final static String[] exportFormats = new String[] { "jpg", "png" };

	public static String defaultPath = null;
	public static String defaultDataset = "myData";

	public static int defaultChoiceR = 0;
	public static int defaultChoiceG = 1;
	public static int defaultChoiceB = 2;

	public static int defaultFormat = 1;

	public static int defaultTileSize = 256;
	public static int defaultTileOverlap = 1;

	File path;
	String dataset;
	int choiceR, choiceG, choiceB, format, tileSize, tileOverlap;
	int numFusionGroups;

	ArrayList< BlockSupplier< UnsignedByteType > > groups = new ArrayList<>();

	@Override
	public ImgExport newInstance() { return new OpenSeaDragon(); }

	@Override
	public String getDescription() { return "OpenSeaDragon (supports 8-bit, 2D slices only)"; }

	@Override
	public int[] blocksize() { return new int[] { tileSize, tileSize, 1 }; }

	@Override
	public boolean finish() { return true; }

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public <T extends RealType<T> & NativeType<T>> boolean exportImage(
			final BlockSupplier<T> supplier,
			final Interval bb,
			final double downsampling,
			final double anisoF,
			final String title,
			final Group<? extends ViewDescription > fusionGroup )
	{
		// remember all fusiongroups
		//groups.add(Views.zeroMin((RandomAccessibleInterval)(Object)img Interval) );
		groups.add( (BlockSupplier)(Object)supplier.threadSafe() );

		// do nothing until all fusiongroups arrived
		if ( groups.size() < numFusionGroups )
		{
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Added fusiongroup & waiting ... ");
			return true;
		}

		// the empty image to choose from
		groups.add( BlockSupplier.of( Views.interval( new ConstantRandomAccessible<UnsignedByteType>( new UnsignedByteType(), 3 ), Intervals.zeroMin( bb ) ) ).threadSafe() );

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Fusing to OpenSeaDragon path='"+path+"', dataset='"+dataset+"' ... ");

		final BlockSupplier< UnsignedByteType > virtualR = groups.get( choiceR );
		final BlockSupplier< UnsignedByteType > virtualG = groups.get( choiceG );
		final BlockSupplier< UnsignedByteType > virtualB = groups.get( choiceB );

		//final RandomAccessibleInterval<ARGBType> rgb = Converters.mergeARGB( Views.stack( virtualR, virtualG, virtualB ) , ColorChannelOrder.RGB );
		//final OpenSeaDragonImgLib2 osd = new OpenSeaDragonImgLib2( rgb );

		final OpenSeaDragonImgLib2 osd = new OpenSeaDragonImgLib2( virtualR, virtualG, virtualB, bb );

		long time = System.currentTimeMillis();

		try
		{
			ScalablePyramidBuilder spb = new ScalablePyramidBuilder(tileSize, tileOverlap, exportFormats[ format ], "dzi");
			spb.buildPyramid(osd, this.dataset, new DirectoryArchiver( this.path ), Threads.numThreads() );
		}
		catch (IOException e)
		{
			e.printStackTrace();
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Error writing OpenSeaDragon project: " + e );
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
			IOFunctions.println( "\nERROR: Only 8-bit export is supported by OpenSeaDragon.");
			return false;
		}

		final GenericDialogPlus gd = new GenericDialogPlus( "OpenSeaDragon export" );

		if ( defaultPath == null || defaultPath.length() == 0 )
		{
			defaultPath = fusion.getSpimData().getBasePath().getAbsolutePath();
			
			if ( defaultPath.endsWith( "/." ) )
				defaultPath = defaultPath.substring( 0, defaultPath.length() - 1 );
			
			if ( defaultPath.endsWith( "/./" ) )
				defaultPath = defaultPath.substring( 0, defaultPath.length() - 2 );
		}

		PluginHelper.addSaveAsDirectoryField( gd, "OpenSeaDragon_output_directory", defaultPath, 80 );
		gd.addStringField( "OpenSeaDragon_dataset", defaultDataset );

		final String[] choices = new String[ fusionGroups.size() + 1 ];
		choices[ choices.length - 1 ] = "[Empty channel]";

		for ( int i = 0; i < fusionGroups.size(); ++i )
		{
			choices[ i ] = getNameForFusionGroup( fusionGroups.get( i ), fusion.getSplittingType() );
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

		gd.addChoice( "Format (OpenSeaDragon)", exportFormats, exportFormats[ defaultFormat ] );

		gd.addNumericField( "Tile_size", defaultTileSize, 0 );
		gd.addNumericField( "Tile_overlap", defaultTileOverlap, 0 );

		gd.showDialog();
		if ( gd.wasCanceled() )
			return false;

		this.path = new File( defaultPath = gd.getNextString() );
		this.dataset = defaultDataset = gd.getNextString();

		this.choiceR = defaultChoiceR = gd.getNextChoiceIndex();
		this.choiceG = defaultChoiceG = gd.getNextChoiceIndex();
		this.choiceB = defaultChoiceB = gd.getNextChoiceIndex();

		this.format = defaultFormat = gd.getNextChoiceIndex();

		this.tileSize = defaultTileSize = (int)Math.round( gd.getNextNumber() );
		this.tileOverlap = defaultTileOverlap = (int)Math.round( gd.getNextNumber() );

		return true;
	}

	protected static String getNameForFusionGroup(
			final Group<ViewDescription> group,
			final int splittingType )
	{
		// 0 == "Each timepoint &amp; channel",
		// 1 == "Each timepoint, channel &amp; illumination",
		// 2 == "All views together",
		// 3 == "Each view"

		final ViewDescription vd = group.getViews().iterator().next();

		if ( splittingType == 0 )
		{
			return "tp=" + vd.getTimePointId() +
					", ch=" + vd.getViewSetup().getChannel().getName();
		}
		else if ( splittingType == 1 )
		{
			return "tp=" + vd.getTimePointId() +
					", ch=" + vd.getViewSetup().getChannel().getName() +
					", ill="+vd.getViewSetup().getIllumination().getName();
		}
		else if ( splittingType == 2 )
		{
			return "All views";
		}
		else if ( splittingType == 3 )
		{
			return "tp=" + vd.getTimePointId() +
					", ch=" + vd.getViewSetup().getChannel().getName() +
					", ill="+vd.getViewSetup().getIllumination().getName() +
					", tile="+vd.getViewSetup().getTile().getName() +
					", angle="+vd.getViewSetup().getAngle().getName();
		}
		else
		{
			IOFunctions.println( "SplittingType " + splittingType + " unknown. Stopping.");
			return null;
		}
	}

	public static class OpenSeaDragonImgLib2 implements PartialImageReader
	{
		final BlockSupplier< UnsignedByteType > virtualR, virtualG, virtualB;
		final Interval interval;

		public OpenSeaDragonImgLib2( 
				final BlockSupplier< UnsignedByteType > virtualR,
				final BlockSupplier< UnsignedByteType > virtualG,
				final BlockSupplier< UnsignedByteType > virtualB,
				final Interval interval )
		{
			this.virtualR = virtualR;
			this.virtualG = virtualG;
			this.virtualB = virtualB;
			this.interval = Intervals.zeroMin( interval );
		}

		@Override
		public BufferedImage read() throws IOException {
			throw new RuntimeException( "cannot render full image.");
		}

		@Override
		public int getWidth() {
			return (int)interval.dimension( 0 );
		}

		@Override
		public int getHeight() {
			return (int)interval.dimension( 1 );
		}

		@Override
		public BufferedImage read(final Rectangle rectangle) throws IOException
		{
			final Interval interval = new FinalInterval(
					new long[] {rectangle.x, rectangle.y, 0},
					new long[] {rectangle.x + rectangle.width - 1, rectangle.y + rectangle.height - 1, 0 } );

			final ArrayImg<UnsignedByteType, ?> r = BlockSupplierUtils.arrayImg( virtualR, interval );
			final ArrayImg<UnsignedByteType, ?> g = BlockSupplierUtils.arrayImg( virtualG, interval );
			final ArrayImg<UnsignedByteType, ?> b = BlockSupplierUtils.arrayImg( virtualB, interval );

			final RandomAccessibleInterval<ARGBType> img = Converters.mergeARGB( Views.stack( r, g, b ) , ColorChannelOrder.RGB );

			final RandomAccessibleInterval<ARGBType> block = Views.zeroMin( Views.interval( Views.extendZero( img ), interval ) );

			/*
			System.out.println( img.size() );
			System.out.println( block.size() );
			System.out.println( rectangle );
			System.out.println( Util.printInterval(interval) );
			ImageJFunctions.show( block, DeconViews.createExecutorService() );
			*/

			final BufferedImage bi = new BufferedImage( rectangle.width, rectangle.height, BufferedImage.TYPE_3BYTE_BGR );
			final Cursor<ARGBType> c = Views.flatIterable( block ).cursor();

			for ( int y = 0; y < rectangle.height; ++y )
				for ( int x = 0; x < rectangle.width; ++x )
				{
					//final int rgb = c.next().get();
					//bi.setRGB(x, y, new Color(ARGBType.red( rgb ),ARGBType.green( rgb ),ARGBType.blue( rgb )).getRGB() );
					
					bi.setRGB(x, y, c.next().get() );
				}

			/*
			JFrame frame = new JFrame("Display BufferedImage");
			frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

			ImageIcon icon = new ImageIcon(bi);
			JLabel label = new JLabel(icon);

			JPanel panel = new JPanel();
			panel.add(label);

			frame.getContentPane().add(panel);
			frame.pack();
			frame.setLocationRelativeTo(null); // Center the frame on screen
			frame.setVisible(true);

			SimpleMultiThreading.threadHaltUnClean();
			*/
			return bi;
		}
	}

}
