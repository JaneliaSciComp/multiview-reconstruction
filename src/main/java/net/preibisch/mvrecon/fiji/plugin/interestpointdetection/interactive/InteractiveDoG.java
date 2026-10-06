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
package net.preibisch.mvrecon.fiji.plugin.interestpointdetection.interactive;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.ExecutorService;

import fiji.tool.SliceObserver;
import ij.ImageJ;
import ij.ImagePlus;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.io.Opener;
import ij.process.ImageProcessor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointValue;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;

public class InteractiveDoG
{
	// TODO: Pass as the parameter (?)
	final public int sensitivity = 4;//RadialSymParams.defaultSensitivity;

	// Frames that are potentially open
	DoGWindow dogWindow;

	// TODO: Pass them or their values
	SliceObserver sliceObserver;
	ROIListener roiListener;

	final ImagePlus imagePlus;

	/** the image as it is, the detection normalizes it with min and max (as the final detection does) */
	final RandomAccessibleInterval< FloatType > img;
	final double min, max;

	final long[] dim;
	//final int type;
	Rectangle rectangle;

	/** the detections of DoGImgLib2.computeDoG in the current region down to thresholdMin (maxima and minima of the image), filtered by the threshold slider */
	ArrayList< InterestPointValue > peaksMax = null, peaksMin = null;

	// TODO: always process only this part of the initial image READ ONLY
	RandomAccessibleInterval<FloatType> imgTmp;
	Interval extendedRoi;

	final ExecutorService service = Threads.createFixedExecutorService();
	WindowAdapter imageWindowListener;

	boolean isComputing = false;
	boolean isStarted = false;

	public static enum ValueChange {
		SIGMA, THRESHOLD, SLICE, ROI, ALL, MINMAX
	}
	
	// stores all the parameters 
	final InteractiveDoGParams params;
	
	// min/max values for GUI
	public static final int supportRadiusMin = 1;
	public static final int supportRadiusMax = 25;
	public static final float inlierRatioMin = (float) (0.0 / 100.0); // 0%
	public static final float inlierRatioMax = 1; // 100%
	public static final float maxErrorMin = 0.0001f;
	public static final float maxErrorMax = 10.00f;
	
	// min/max value
	final float bsInlierRatioMin = (float) (0.0 / 100.0); // 0%
	final float bsInlierRatioMax = 1; // 100%
	final float bsMaxErrorMin = 0.0001f;
	final float bsMaxErrorMax = 10.00f;
	
	// min/max value
	public static final float sigmaMin = 0.5f;
	public static final float sigmaMax = 10f;
	public static final float thresholdMin = 0.00001f;
	public static final float thresholdMax = 0.3f;
	
	final int scrollbarSize = 1000;
	// ----------------------------------------
	
	boolean isFinished = false;
	boolean wasCanceled = false;	

	public boolean isFinished() {
		return isFinished;
	}

	public boolean wasCanceled() {
		return wasCanceled;
	}

	/**
	 * The intensity range is computed from the image (its exact min and max, as the final detection does for every view)
	 */
	public InteractiveDoG( final ImagePlus imp, final InteractiveDoGParams params )
	{
		this( imp, params, minMax( imp ) );
	}

	private InteractiveDoG( final ImagePlus imp, final InteractiveDoGParams params, final double[] minmax )
	{
		this( imp, params, minmax[ 0 ], minmax[ 1 ] );
	}

	/**
	 * Triggers the interactive DoG
	 * Single-channel imageplus, 2d or 3d or 4d
	 * 
	 * @param imp - intial image
	 * @param params - parameters for the computation of the DoG
	 * @param min - min intensity of the image (the detection normalizes with it)
	 * @param max - max intensity of the image
	 */
	@SuppressWarnings("unchecked")
	public InteractiveDoG( final ImagePlus imp, final InteractiveDoGParams params, final double min, final double max )
	{
		this.imagePlus = imp;

		if ( Double.isNaN( min ) || Double.isNaN( max ) )
			throw new IllegalArgumentException( "min/max not set for interactive DoG." );

		this.min = min;
		this.max = max;
		this.img = Converters.convert( (RandomAccessibleInterval<RealType<?>>)(Object)ImagePlusImgs.from( imp ), (i,o) -> o.set( i.getRealFloat() ), new FloatType() );

		this.params = params;
		this.dim = new long[]{ imp.getWidth(), imp.getHeight() };

		final Roi roi = imagePlus.getRoi();

		if ( roi != null && roi.getType() == Roi.RECTANGLE  )
		{
			rectangle = roi.getBounds();
		}
		else
		{
			// initial rectangle
			rectangle = new Rectangle(
					imagePlus.getWidth() / 4,
					imagePlus.getHeight() / 4,
					Math.min( 100, imagePlus.getWidth() / 2 ),
					Math.min( 100, imagePlus.getHeight() / 2) );

			imagePlus.setRoi( rectangle );
		}

		initInteractiveKit();
	}
	
	
	/**
	 *	Initialize the image kit - DoG and RANSAC windows to adjust the parameters
	 * */
	protected void initInteractiveKit(){
		// show the interactive dog kit
		this.dogWindow = new DoGWindow( this );
		this.dogWindow.getFrame().setVisible( true );

		// add listener to the imageplus slice slider
		sliceObserver = new SliceObserver(imagePlus, new ImagePlusListener( this ));
		// compute first version
		updatePreview(ValueChange.ALL);
		isStarted = true;
		// check whenever roi is modified to update accordingly
		roiListener = new ROIListener( this, imagePlus );

		final ImageCanvas canvas = imagePlus.getCanvas();

		if ( canvas != null )
			canvas.addMouseListener( roiListener );

		// closing the image cancels (otherwise the caller waits forever)
		final ImageWindow window = imagePlus.getWindow();

		if ( window != null )
		{
			imageWindowListener = new WindowAdapter()
			{
				@Override
				public void windowClosed( final WindowEvent e )
				{
					wasCanceled = true;
					dispose();
				}
			};

			window.addWindowListener( imageWindowListener );
		}
	}

	// TODO: fix the check: "==" must not be used with floats
	protected boolean isRoiChanged(final ValueChange change, final Rectangle rect, boolean roiChanged){
		boolean res = false;
		res = (roiChanged || extendedRoi == null || change == ValueChange.SLICE ||rect.getMinX() != rectangle.getMinX()
				|| rect.getMaxX() != rectangle.getMaxX() || rect.getMinY() != rectangle.getMinY()
				|| rect.getMaxY() != rectangle.getMaxY());
		return res;
	}

	/**
	 * Updates the Preview with the current parameters (sigma, threshold, roi, slice number + RANSAC parameters)
	 * @param change - what did change
	 */
	protected void updatePreview(final ValueChange change)
	{
		if ( isFinished )
			return;

		isComputing = true;

		try
		{
			update( change );
		}
		finally
		{
			isComputing = false;
		}
	}

	protected void update(final ValueChange change) {
		// set up roi 
		boolean roiChanged = false;
		Roi roi = imagePlus.getRoi();

		if ( roi == null || roi.getType() != Roi.RECTANGLE )
		{
			imagePlus.setRoi(rectangle);
			roi = imagePlus.getRoi();
			roiChanged = true;
		}

		// Do I need this one or it is just the copy of the same thing?
		// sourceRectangle or rectangle
		final Rectangle roiBounds = roi.getBounds(); 

		// change the img2 size if the roi or the support radius size was changed
		if ( isRoiChanged(change, roiBounds, roiChanged) || change == ValueChange.SIGMA )
		{
			rectangle = roiBounds;

			// make sure the size is not 0 (is possible in ImageJ when making the Rectangle, not when changing it ... yeah)
			rectangle.width = Math.max( 1, rectangle.width );
			rectangle.height = Math.max( 1, rectangle.height );

			// a 2d or 3d view where we'll run DoG on
			//RandomAccessibleInterval< FloatType > imgTmp;
			long[] min, max;

			if ( imagePlus.getNSlices() > 1 ) { // 3d, 3d+t case

				if ( imagePlus.getNFrames() > 1 )
					imgTmp = Views.hyperSlice( img, 3, imagePlus.getT() - 1 );
				else
					imgTmp = img;

				// 3d case

				// 'channel', 'slice' and 'frame' are one-based indexes
				final int currentSlice = imagePlus.getZ() - 1;

				/*
				final int extZ = 
						Gauss3.halfkernelsizes(
								new double[] {
										HelperFunctions.computeSigma2( params.getSigmaDoG(), sensitivity ) *
										( params.useAnisotropyForDoG ? params.anisotropyCoefficient : 1.0 ) } )[ 0 ];
				*/
				// we need only one plane (+-1) in Z since we anyways use the entire image for convolution
				min = new long []{
						rectangle.x,
						rectangle.y,
						Math.max( imgTmp.min( 2 ), currentSlice - (long) (2.5 * Math.ceil(params.sigma) ) ) };
				max = new long []{
						rectangle.width + rectangle.x - 1,
						rectangle.height + rectangle.y - 1,
						Math.min( imgTmp.max( 2 ), currentSlice + (long) (2.5 * Math.ceil(params.sigma) ) ) };
			}
			else { // 2d or 2d+t case

				if ( imagePlus.getNFrames() > 1 )
					imgTmp = Views.hyperSlice( img, 2, imagePlus.getT() - 1 );
				else
					imgTmp = img;

				// 2d case

				min = new long []{rectangle.x, rectangle.y};
				max = new long []{rectangle.width + rectangle.x - 1, rectangle.height + rectangle.y - 1};
			}

			extendedRoi = new FinalInterval(min, max);//Views.interval( Views.extendMirrorSingle( imgTmp ), min, max);

			roiChanged = true;
		}

		// only recalculate DOG & gradient image if: sigma, roi (also through support region), slider
		if (roiChanged || peaksMax == null || peaksMin == null || change == ValueChange.SIGMA || change == ValueChange.SLICE || change == ValueChange.MINMAX || change == ValueChange.ALL )
		{
			dogDetection( Views.extendMirrorSingle( imgTmp ), extendedRoi );
		}

		// the detections at the threshold of the slider (the detection applies |value| > threshold the same way), drawn as
		// the scale-space preview draws them: the ball of maximal response, radius sqrt(3) sigma, cut by the current slice
		final double radius = Math.sqrt( 3 ) * params.sigma;

		HelperFunctions.drawRealLocalizable( HelperFunctions.filterPeaks( peaksMax, rectangle, params.threshold ), imagePlus, radius, Color.RED, true );
		HelperFunctions.drawRealLocalizable( HelperFunctions.filterPeaks( peaksMin, rectangle, params.threshold ), imagePlus, radius, Color.GREEN, false );
	}

	/**
	 * The detection itself (DoGImgLib2.computeDoG, sigma and k as in the final run) on the region, down to thresholdMin
	 * so that the threshold slider only filters. Maxima and minima are detected separately: the detector decides the type
	 * before the sub-pixel refinement, so the sign of the refined value does not tell it reliably.
	 */
	protected void dogDetection( final RandomAccessible<FloatType> image, final Interval interval )
	{
		this.peaksMax = params.findMaxima ? detect( image, interval, false, true ) : new ArrayList<>();
		this.peaksMin = params.findMinima ? detect( image, interval, true, false ) : new ArrayList<>();
	}

	protected ArrayList< InterestPointValue > detect( final RandomAccessible<FloatType> image, final Interval interval, final boolean findMin, final boolean findMax )
	{
		final boolean silent = DoGImgLib2.silent;
		DoGImgLib2.silent = true;

		try
		{
			final ArrayList< InterestPointValue > peaks = new ArrayList<>();

			for ( final InterestPoint point : DoGImgLib2.computeDoG( image, null, interval, params.sigma, thresholdMin, 1, findMin, findMax, min, max, service ) )
				if ( InterestPointValue.class.isInstance( point ) )
					peaks.add( (InterestPointValue)point );

			return peaks;
		}
		finally
		{
			DoGImgLib2.silent = silent;
		}
	}

	protected final void dispose()
	{
		if ( isFinished )
			return;

		isFinished = true;

		if ( dogWindow != null && dogWindow.getFrame() != null)
			dogWindow.getFrame().dispose();

		if (sliceObserver != null)
			sliceObserver.unregister();

		if ( imagePlus != null)
		{
			// the image window might be gone already (closing it cancels the preview)
			final ImageCanvas canvas = imagePlus.getCanvas();

			if ( roiListener != null && canvas != null )
				canvas.removeMouseListener( roiListener );

			final ImageWindow window = imagePlus.getWindow();

			if ( imageWindowListener != null && window != null )
				window.removeWindowListener( imageWindowListener );

			final Overlay overlay = imagePlus.getOverlay();

			if ( overlay != null )
			{
				overlay.clear();
				imagePlus.updateAndDraw();
			}
		}

		service.shutdown();
	}

	/**
	 * @return the exact min and max of the image (what the detection computes for a view without a given range)
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static double[] minMax( final ImagePlus imp )
	{
		final float[] minmax = FusionTools.minMax( (RandomAccessibleInterval)ImagePlusImgs.from( imp ) );

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Interactive DoG: intensity range computed from the image [" + minmax[ 0 ] + ", " + minmax[ 1 ] + "] (the detection computes it per view the same way)." );

		return new double[] { minmax[ 0 ], minmax[ 1 ] };
	}

	public static void main(String[] args)
	{
		File path = new File( "/Users/preibischs/Documents/Microscopy/SPIM/HisYFP-SPIM/spim_TL18_Angle0.tif" );
		// path = path.concat("test_background.tif");

		if ( !path.exists() )
			throw new RuntimeException( "'" + path.getAbsolutePath() + "' doesn't exist." );

		new ImageJ();
		System.out.println( "Opening '" + path + "'");

		ImagePlus imp = new Opener().openImage( path.getAbsolutePath() );

		if (imp == null)
			throw new RuntimeException( "image was not loaded" );

		float min = Float.MAX_VALUE;
		float max = -Float.MAX_VALUE;

		for ( int z = 1; z <= imp.getStack().getSize(); ++z )
		{
			final ImageProcessor ip = imp.getStack().getProcessor( z );

			for ( int i = 0; i < ip.getPixelCount(); ++i )
			{
				final float v = ip.getf( i );
				min = Math.min( min, v );
				max = Math.max( max, v );
			}
		}
		
		IOFunctions.println( "min=" + min );
		IOFunctions.println( "max=" + max );

		imp.show();

		imp.setSlice(20);

		new InteractiveDoG( imp, new InteractiveDoGParams(), min, max );

		System.out.println("DOGE!");
	}
}
