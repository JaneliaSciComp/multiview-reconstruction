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

import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import fiji.util.gui.GenericDialogPlus;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.ViewDescription;
import net.imglib2.Interval;
import net.imglib2.algorithm.blocks.BlockAlgoUtils;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionExportInterface;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.process.deconvolution.DeconViews;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class DisplayImage implements ImgExport, Calibrateable
{
	// TODO: this is ugly, but otherwise the service is shutdown while the ImageJVirtualStack is still displayed and crashes when scrolling through the stack
	final public static ExecutorService service = DeconViews.createExecutorService();

	final String[] choiceText = new String[] { "cached (immediate, less memory, slower)", "precomputed (fast, complete copy in memory before display)" };

	public static int defaultChoice = 1;
	public static int defaultBlocksizeVirtualX = 256;
	public static int defaultBlocksizeVirtualY = 256;
	public static int defaultBlocksizeVirtualZ = 1;
	public static int defaultBlocksizePrecomputeX = 128;
	public static int defaultBlocksizePrecomputeY = 128;
	public static int defaultBlocksizePrecomputeZ = 64;
	public static int defaultMinIntensity = 0;
	public static int defaultMaxIntensity = 255;
	
	boolean virtualDisplay;
	String unit = "px";
	double[] cal = new double[] { 1.0, 1.0, 1.0 };
	int bsX = defaultBlocksizeVirtualX;
	int bsY = defaultBlocksizeVirtualY;
	int bsZ = defaultBlocksizeVirtualZ;
	int minIntensity = defaultMinIntensity;
	int maxIntensity = defaultMaxIntensity;

	public DisplayImage() { this( true ); }
	public DisplayImage( final boolean virtualDisplay ) { this.virtualDisplay = virtualDisplay; }

	@Override
	public < T extends RealType< T > & NativeType< T > > boolean exportImage(
			final BlockSupplier< T > img,
			final Interval bb,
			final double downsampling,
			final double anisoF,
			final String title,
			final Group< ? extends ViewDescription > fusionGroup )
	{
		// do nothing in case the image is null
		if ( img == null )
			return false;

		// determine min and max
		//final double[] minmax = FusionTools.minMaxApprox( null );//getFusionMinMax( img, min, max );

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Display range min=" + minIntensity + ", max=" + maxIntensity );

		final ImagePlus imp = getImagePlusInstance( img, bb, blocksize(), virtualDisplay, title, minIntensity, maxIntensity, service );

		setCalibration( imp, bb, downsampling, anisoF, cal, unit );

		imp.updateAndDraw();
		imp.show();

		return true;
	}

	public static void setCalibration( final ImagePlus imp, final Interval bb, final double downsampling, final double anisoF, final double[] cal, final String unit )
	{
		final double ds = Double.isNaN( downsampling ) ? 1.0 : downsampling;
		final double ai = Double.isNaN( anisoF ) ? 1.0 : anisoF;

		if ( bb != null )
		{
			final double calXY = ( cal[0] + cal[1] ) / 2.0;
			imp.getCalibration().xOrigin = -(bb.min( 0 ) / ds) * calXY;
			imp.getCalibration().yOrigin = -(bb.min( 1 ) / ds) * calXY;
			imp.getCalibration().zOrigin = -(bb.min( 2 ) / ds) * cal[2];
			imp.getCalibration().pixelWidth = imp.getCalibration().pixelHeight = ds * calXY;
			imp.getCalibration().pixelDepth = ds * ai * cal[2];
		}

		imp.getCalibration().setUnit( unit );
	}

	/*	
	public < T extends RealType< T > & NativeType< T > > void exportImage( final RandomAccessibleInterval< T > img )
	{
		exportImage( img, null, Double.NaN, Double.NaN, "Image", null );
	}

	public < T extends RealType< T > & NativeType< T > > void exportImage( final RandomAccessibleInterval< T > img, final String title )
	{
		exportImage( img, null, Double.NaN, Double.NaN, title, null );
	}

	public static < T extends RealType< T > > double[] getFusionMinMax(
			final RandomAccessibleInterval<T> img,
			final double min,
			final double max )
	{
		final double[] minmax;

		if ( Double.isNaN( min ) || Double.isNaN( max ) )
			minmax = FusionTools.minMaxApprox( img );
		else
			minmax = new double[]{ (float)min, (float)max };

		return minmax;
	}

	public static < T extends RealType< T > & NativeType< T > > ImagePlus getImagePlusInstance(
			final RandomAccessibleInterval< T > img,
			final boolean virtualDisplay,
			final String title,
			final double min,
			final double max )
	{
		return getImagePlusInstance( img, virtualDisplay, title, min, max, service );
	}

	public static < T extends RealType< T > & NativeType< T > > ImagePlus getImagePlusInstance(
			final RandomAccessibleInterval< T > img,
			final boolean virtualDisplay,
			final String title,
			final double min,
			final double max,
			final ExecutorService service )
	{
		ImagePlus imp = null;

		if ( img instanceof ImagePlusImg )
			try { imp = ((ImagePlusImg<T, ?>)img).getImagePlus(); } catch (ImgLibException e) {}

		if ( imp == null )
		{
			if ( virtualDisplay )
				imp = ImageJFunctions.wrap( img, title, service );
			else
				imp = ImageJFunctions.wrap( img, title, service ).duplicate();
		}

		final double[] minmax = getFusionMinMax( img, min, max );

		imp.setTitle( title );
		imp.setDimensions( 1, (int)img.dimension( 2 ), 1 );
		imp.setDisplayRange( minmax[ 0 ], minmax[ 1 ] );

		return imp;
	}
	*/

	public static < T extends RealType< T > & NativeType< T > > ImagePlus getImagePlusInstance(
			final BlockSupplier< T > supplier,
			final Interval interval,
			final int[] blockSize,
			final boolean virtualDisplay,
			final String title,
			double min,
			double max,
			final ExecutorService service )
	{
		//ImagePlus imp = null;

		//if ( img instanceof ImagePlusImg )
		//	try { imp = ((ImagePlusImg<T, ?>)img).getImagePlus(); } catch (ImgLibException e) {}

		final ImagePlus imp;
		CachedCellImg<T, ?> img = BlockAlgoUtils.cellImg( supplier, interval.dimensionsAsLongArray(), blockSize );

		if ( virtualDisplay )
		{
			imp = ImageJFunctions.wrap( img, title, service );
		}
		else
		{
			imp = ImageJFunctions.wrap( img, title, service ).duplicate(); // TODO: this can be done more efficient, use BlkAffineFusion.arrayImg for each slice
			img = null;
		}

		//final double[] minmax = getFusionMinMax( img, min, max );

		if ( Double.isNaN( min ) )
			min = 0;

		if ( Double.isNaN( max ) )
			max = 255;
			
		imp.setTitle( title );
		imp.setDimensions( 1, (int)interval.dimension( 2 ), 1 );
		imp.setDisplayRange( min, max );

		return imp;
	}

	@Override
	public boolean queryParameters( final FusionExportInterface fusion )
	{
		final boolean is2d =
				FusionTools.is2d( fusion.getViews().stream().map( v -> fusion.getSpimData().getSequenceDescription().getViewDescriptions().get( v ) ).collect( Collectors.toList() ) );
					
		final GenericDialogPlus gd = new GenericDialogPlus( "Display fused image as ImageJ stack" );

		gd.addChoice( "Display image", choiceText, choiceText[ defaultChoice ] );

		gd.addMessage( "Initial display range:", GUIHelper.smallStatusFont, GUIHelper.neutral );
		gd.addNumericField( "min_intensity", defaultMinIntensity, 0);
		gd.addNumericField( "max_intensity", defaultMaxIntensity, 0);

		gd.addCheckbox( "Show_advanced_block_size_options (in a new dialog, can optimize processing time)", ExportN5Api.defaultAdvancedBlockSize );

		if ( is2d )
			gd.addMessage( "Note: we have a 2d-dataset, choose 1 in z.", GUIHelper.smallStatusFont, GUIHelper.neutral );

		gd.showDialog();
		if ( gd.wasCanceled() )
			return false;

		if ( ( defaultChoice = gd.getNextChoiceIndex() ) == 0 )
			virtualDisplay = true;
		else
			virtualDisplay = false;

		minIntensity = defaultMinIntensity = (int)Math.round( gd.getNextNumber() );
		maxIntensity = defaultMaxIntensity = (int)Math.round( gd.getNextNumber() );

		if ( ExportN5Api.defaultAdvancedBlockSize  = gd.getNextBoolean() )
		{
			final GenericDialog gd2 = new GenericDialog( "Compute block size options" );
			
			if ( virtualDisplay )
			{
				gd2.addMessage( "Block size of lazy processing is optimized for multi-threaded viewing:", GUIHelper.smallStatusFont, GUIHelper.neutral );
				gd2.addNumericField( "block_size_x", defaultBlocksizeVirtualX, 0);
				gd2.addNumericField( "block_size_y", defaultBlocksizeVirtualY, 0);
				gd2.addNumericField( "block_size_z", defaultBlocksizeVirtualZ, 0);
			}
			else
			{
				gd2.addMessage( "Block size of lazy processing is optimized for precomputing the image:", GUIHelper.smallStatusFont, GUIHelper.neutral );
				gd2.addNumericField( "block_size_x", defaultBlocksizePrecomputeX, 0);
				gd2.addNumericField( "block_size_y", defaultBlocksizePrecomputeY, 0);
				gd2.addNumericField( "block_size_z", defaultBlocksizePrecomputeZ, 0);
			}

			gd2.showDialog();
			if ( gd2.wasCanceled() )
				return false;

			if ( virtualDisplay )
			{
				bsX = defaultBlocksizeVirtualX = (int)Math.round( gd2.getNextNumber() );
				bsY = defaultBlocksizeVirtualY = (int)Math.round( gd2.getNextNumber() );
				bsZ = defaultBlocksizeVirtualZ = (int)Math.round( gd2.getNextNumber() );
			}
			else
			{
				bsX = defaultBlocksizePrecomputeX = (int)Math.round( gd2.getNextNumber() );
				bsY = defaultBlocksizePrecomputeY = (int)Math.round( gd2.getNextNumber() );
				bsZ = defaultBlocksizePrecomputeZ = (int)Math.round( gd2.getNextNumber() );
			}
		}
		else
		{
			if ( virtualDisplay )
			{
				bsX = defaultBlocksizeVirtualX;
				bsY = defaultBlocksizeVirtualY;
				bsZ = defaultBlocksizeVirtualZ;
			}
			else
			{
				bsX = defaultBlocksizePrecomputeX;
				bsY = defaultBlocksizePrecomputeY;
				bsZ = defaultBlocksizePrecomputeZ;
			}
		}

		IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): compute block size=" + bsX + "x" + bsY + "x" + bsZ);

		return true;
	}

	@Override
	public ImgExport newInstance() { return new DisplayImage(); }

	@Override
	public String getDescription() { return "Display using ImageJ"; }

	@Override
	public boolean finish()
	{
		// this spimdata object was not modified
		return false;
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

	@Override
	public int[] blocksize() { return new int[] { bsX, bsY, bsZ }; }
}
