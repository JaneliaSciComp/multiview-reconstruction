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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import fiji.tool.SliceObserver;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.OvalRoi;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.TextRoi;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.legacy.registration.bead.laplace.LaPlaceFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.ScaleSpaceGUI;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.DoGScaleSpace;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.DoGScaleSpace.Candidates;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.DoGScaleSpace.ScaleSpacePeak;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.ScaleSpace;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.ScaleSpaceParameters;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

/**
 * The interactive preview of the scale-space DoG: one view is opened at a starting resolution exactly
 * as the detection opens it (DownsampleTools.openAndDownsample, ScaleSpace.anisotropy), the scale space
 * is computed once for all candidates down to a low threshold (DoGScaleSpace.computeScaleSpaceCandidates)
 * and the detections at the current threshold are shown as circles on the image: radius = sqrt(3) sigma
 * (the ball of maximal response), hue from red (the initial blur) to blue (the coarsest level), pink for
 * the detections of the finest level that are no extremum in scale, dashed for minima. While the scale
 * space is computed the image window is disabled, shows "computing" and ImageJ's progress bar runs.
 * The threshold (slider), "detect finest level" and the sign are filtered without computing anything again
 * (DoGScaleSpace.filterCandidates), the steps per octave and the octaves change the pyramid and compute it
 * again; a rectangle ROI restricts the computation in x and y and a z range
 * around the current slice in z that follows from a voxel budget (large stacks are never processed as a
 * whole); leaving that z range or drawing another rectangle computes again.
 *
 * Several previews at different starting resolutions can be open at once (a {@link Session}): "Done"
 * adopts the threshold, the flags and the starting resolution of the window it was pressed in and closes
 * the others, "Cancel" closes all.
 *
 * @author Stephan Preibisch
 */
public class InteractiveScaleSpace
{
	/** the strongest candidates kept per window, the lower bound of the threshold is raised if there are more */
	public static int maxCandidates = 500000;

	/** circles drawn on one slice at most (the strongest) */
	public static int maxCirclesPerSlice = 5000;

	/** the default ROI in x and y (centered, smaller if the image is), the z range around the current slice follows from the voxel budget */
	public static int defaultRoiSize = 200;

	/** voxels of the computed region at most: the z range around the current slice is derived from it and the area of the ROI */
	public static long defaultMaxVoxels = 16L * 1024 * 1024;

	/** the z range around the current slice is at least this many slices in each direction */
	public static int minZHalf = 16;

	/** the entries of the octaves drop-down: all (-1) and 1..maxOctaveChoice */
	public static final int maxOctaveChoice = 8;
	public static final String[] octaveChoices = new String[ maxOctaveChoice + 1 ];

	static
	{
		octaveChoices[ 0 ] = "all";

		for ( int i = 1; i <= maxOctaveChoice; ++i )
			octaveChoices[ i ] = Integer.toString( i );
	}

	/** circles are drawn with at least this radius (pixels of the opened image), so that tiny fitted sizes stay visible */
	public static double minRadius = 1.0;

	/** slice changes outside the computed z range wait this long (ms) before the scale space is computed there */
	public static int recomputeDelay = 300;

	/** the image is displayed virtually (slower browsing, no copy) above this many voxels */
	public static long maxVoxelsInMemory = 256L * 1024 * 1024;

	/** the initial bounds of the threshold slider (the lower bound is the threshold the candidates are computed down to) */
	public static double defaultLowerBound = 0.001, defaultUpperBound = 0.3;

	/**
	 * The windows of one interactive session and its result
	 */
	public static class Session
	{
		final SpimData2 data;
		final ViewDescription vd;
		final VoxelDimensions voxelSize;

		/** the precomputed resolution levels offered ("fx, fy, fz"), see DownsampleTools.availableDownsamplings */
		final String[] resolutions;

		/** the parameters of the dialog, the initial threshold, flags and intensity range (NaN = computed from every image, as the detection does per view) */
		final ScaleSpaceParameters base;
		final double anisotropyZ;

		final ArrayList< InteractiveScaleSpace > windows = new ArrayList<>();
		int opening = 0;

		// the result (initially the values of the dialog)
		double threshold;
		boolean detectFinestLevel, findMin, findMax;
		int steps, octaves;
		long[] downsampling = null;
		int resolutionIndex = -1;

		volatile boolean finished = false, cancelled = false;

		public Session( final SpimData2 data, final ViewDescription vd, final String[] resolutions, final ScaleSpaceParameters base, final double anisotropyZ )
		{
			this.data = data;
			this.vd = vd;
			this.voxelSize = vd.getViewSetup().hasVoxelSize() ? vd.getViewSetup().getVoxelSize() : null;
			this.resolutions = resolutions;
			this.base = new ScaleSpaceParameters( base );
			this.anisotropyZ = anisotropyZ;
			this.threshold = base.threshold;
			this.detectFinestLevel = base.detectFinestLevel;
			this.findMin = base.findMin;
			this.findMax = base.findMax;
			this.steps = base.steps;
			this.octaves = base.octaves;
		}

		/**
		 * Opens a preview at a starting resolution (in a new thread) with the threshold and flags of the dialog
		 *
		 * @param resolutionIndex - the index in resolutions, -1 if typed manually
		 */
		public void open( final long[] downsampling, final int resolutionIndex )
		{
			open( downsampling, resolutionIndex, base.threshold, base.detectFinestLevel, base.findMin, base.findMax, base.steps, base.octaves, null, null );
		}

		/**
		 * @param fromRoi - the computed region of the window this one is opened from (scaled to the new resolution), null = the default
		 * @param fromDownsampling - the starting resolution of that window
		 */
		protected void open( final long[] downsampling, final int resolutionIndex, final double threshold, final boolean detectFinestLevel, final boolean findMin, final boolean findMax, final int steps, final int octaves, final Interval fromRoi, final long[] fromDownsampling )
		{
			synchronized ( this ) { ++opening; }

			new Thread( () ->
			{
				try
				{
					new InteractiveScaleSpace( this, downsampling, resolutionIndex, threshold, detectFinestLevel, findMin, findMax, steps, octaves, fromRoi, fromDownsampling );
				}
				catch ( final Exception e )
				{
					IOFunctions.println( "Could not open the scale-space preview at downsampling " + Util.printCoordinates( downsampling ) + ": " + e );
					e.printStackTrace();
				}
				finally
				{
					synchronized ( this )
					{
						--opening;
						checkEmpty();
					}
				}
			}, "InteractiveScaleSpace" ).start();
		}

		synchronized void register( final InteractiveScaleSpace window )
		{
			if ( finished )
				SwingUtilities.invokeLater( window::dispose );
			else
				windows.add( window );
		}

		/**
		 * Done in a window: its threshold, flags and starting resolution are the result, all windows close
		 */
		synchronized void done( final InteractiveScaleSpace window )
		{
			if ( finished )
				return;

			threshold = window.thresholdSlider.getValue();
			detectFinestLevel = window.finestBox.isSelected();
			findMax = window.maximaBox.isSelected();
			findMin = window.minimaBox.isSelected();
			steps = window.p.steps;
			octaves = window.p.octaves;
			downsampling = window.downsampling.clone();
			resolutionIndex = window.resolutionIndex;
			finished = true;

			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Scale-space preview: threshold = " + threshold + ", finest structures = " + detectFinestLevel + ", maxima = " + findMax + ", minima = " + findMin + ", steps per octave = " + steps + ", octaves = " + octaves + ", starting resolution (downsampling x, y, z) = " + Util.printCoordinates( downsampling ) );

			closeAll();
		}

		public synchronized void cancel()
		{
			if ( finished )
				return;

			cancelled = true;
			finished = true;

			closeAll();
		}

		synchronized void closed( final InteractiveScaleSpace window )
		{
			windows.remove( window );
			checkEmpty();
		}

		/** the last window closed without Done: cancelled */
		protected void checkEmpty()
		{
			if ( !finished && windows.isEmpty() && opening == 0 )
			{
				cancelled = true;
				finished = true;
			}
		}

		protected void closeAll()
		{
			for ( final InteractiveScaleSpace window : new ArrayList<>( windows ) )
				SwingUtilities.invokeLater( window::dispose );
		}

		public void waitUntilFinished()
		{
			while ( !finished )
			{
				try
				{
					Thread.sleep( 100 );
				}
				catch ( final InterruptedException e ) {}
			}
		}

		public boolean isFinished() { return finished; }

		public boolean isCancelled() { return cancelled; }

		public double getThreshold() { return threshold; }

		public boolean getDetectFinestLevel() { return detectFinestLevel; }

		public boolean getFindMin() { return findMin; }

		public boolean getFindMax() { return findMax; }

		public int getSteps() { return steps; }

		/** -1 = as many as the image allows */
		public int getOctaves() { return octaves; }

		/** the adopted starting resolution (downsampling x, y, z), null if cancelled */
		public long[] getDownsampling() { return downsampling; }

		/** the index of the adopted resolution in resolutions, -1 if typed manually */
		public int getResolutionIndex() { return resolutionIndex; }
	}

	final Session session;
	final long[] downsampling;
	final int resolutionIndex;

	/** the parameters of this window: the ones of the dialog with the anisotropy and intensity range of the opened image */
	final ScaleSpaceParameters p;

	final RandomAccessibleInterval< FloatType > img;
	final AffineTransform3D mipmapTransform;
	final ImagePlus imp;

	final JFrame frame;
	final BoundedValueSlider thresholdSlider;
	final JCheckBox finestBox, maximaBox, minimaBox;
	final JComboBox< String > resolutionBox, octavesBox;
	final JSpinner stepsSpinner;
	boolean updatingOctavesBox = false;
	final JButton doneButton, cancelButton;
	boolean updatingResolutionBox = false;
	final JLabel countsLabel, statusLabel;
	final ScaleLegend legend;
	final Timer filterTimer, recomputeTimer;

	final ExecutorService worker = Executors.newSingleThreadExecutor();
	final AtomicInteger generation = new AtomicInteger();

	volatile Interval roi;
	volatile Candidates candidates = null;
	volatile boolean computing = false;

	/** the detections at the current threshold and flags, sorted by |response| */
	volatile ArrayList< ScaleSpacePeak > shown = new ArrayList<>();

	/** the detections per slice (those whose ball intersects the slice), in the order of shown */
	volatile List< List< ScaleSpacePeak > > perSlice = null;

	final SliceObserver sliceObserver;
	final MouseAdapter roiListener;
	final WindowAdapter imageWindowListener;

	boolean disposed = false;

	protected InteractiveScaleSpace( final Session session, final long[] downsampling, final int resolutionIndex, final double threshold, final boolean detectFinestLevel, final boolean findMin, final boolean findMax, final int steps, final int octaves, final Interval fromRoi, final long[] fromDownsampling )
	{
		this.session = session;
		this.downsampling = downsampling.clone();
		this.resolutionIndex = resolutionIndex;

		final String name = Group.pvid( session.vd ) + " at downsampling " + Util.printCoordinates( downsampling );

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Scale-space preview: opening " + name );

		// exactly as the detection (ScaleSpace.addInterestPoints) opens the view
		@SuppressWarnings( "rawtypes" )
		final Pair< RandomAccessibleInterval, AffineTransform3D > input = DownsampleTools.openAndDownsample( session.data.getSequenceDescription().getImgLoader(), session.vd, downsampling, false );

		this.img = toFloat( input.getA() );
		this.mipmapTransform = input.getB();

		this.p = new ScaleSpaceParameters( session.base );

		// the intensity range: the one of the dialog, otherwise the one of this image, exactly as the detection computes it for this view
		if ( Double.isNaN( p.minIntensity ) || Double.isNaN( p.maxIntensity ) )
		{
			final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );
			final float[] minmax = FusionTools.minMax( img, service );
			service.shutdown();

			this.p.minIntensity = minmax[ 0 ];
			this.p.maxIntensity = minmax[ 1 ];

			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Scale-space preview: intensity range computed from the image [" + minmax[ 0 ] + ", " + minmax[ 1 ] + "] (the detection computes it per view the same way)." );
		}
		this.p.anisotropy = ScaleSpace.anisotropy( session.vd, mipmapTransform, session.anisotropyZ );
		this.p.findMin = findMin;
		this.p.findMax = findMax;
		this.p.detectFinestLevel = detectFinestLevel;
		this.p.threshold = threshold;
		this.p.steps = steps;
		this.p.octaves = octaves;

		this.imp = FusionTools.getImagePlusInstance( img, Intervals.numElements( img ) > maxVoxelsInMemory, name, p.minIntensity, p.maxIntensity, null );
		this.imp.setDimensions( 1, imp.getStackSize(), 1 );
		this.imp.show();
		this.imp.setSlice( Math.max( 1, imp.getStackSize() / 2 ) );

		// the computed region: x and y as the rectangle ROI (none = the whole slice), z around the current slice; the region of
		// the window this one was opened from is scaled to this resolution (and its central slice shown)
		final Interval scaled = fromRoi == null ? null : scaledRoi( fromRoi, fromDownsampling, downsampling, img, defaultMaxVoxels );

		if ( scaled != null )
		{
			this.roi = scaled;
			this.imp.setSlice( (int)( ( scaled.min( 2 ) + scaled.max( 2 ) ) / 2 ) + 1 );
		}
		else
		{
			this.roi = defaultRoi( img, imp.getZ() - 1, defaultMaxVoxels );
		}

		if ( roi.dimension( 0 ) < img.dimension( 0 ) || roi.dimension( 1 ) < img.dimension( 1 ) )
			imp.setRoi( new Rectangle( (int)roi.min( 0 ), (int)roi.min( 1 ), (int)roi.dimension( 0 ), (int)roi.dimension( 1 ) ) );

		// the control window
		this.thresholdSlider = new BoundedValueSlider( threshold, defaultLowerBound, defaultUpperBound, true, "0.00000", 0.0005 );
		this.thresholdSlider.addPreset( "set bounds 0.001 .. 0.03 (beads)", 0.001, 0.03 );
		this.thresholdSlider.addPreset( "set bounds 0.01 .. 0.3 (sample)", 0.01, 0.3 );
		this.finestBox = new JCheckBox( "Finest structures", detectFinestLevel );
		this.finestBox.setToolTipText( "also keep all extrema at the initial blur that are no extremum in scale (structures below the sampled scales, pink)" );
		this.maximaBox = new JCheckBox( "Maxima", findMax );
		this.maximaBox.setToolTipText( "bright structures" );
		this.minimaBox = new JCheckBox( "Minima", findMin );
		this.minimaBox.setToolTipText( "dark structures (drawn dashed)" );
		this.resolutionBox = new JComboBox<>( ScaleSpaceGUI.resolutionChoices( session.resolutions, session.voxelSize ) );
		this.resolutionBox.setSelectedIndex( resolutionIndex < 0 ? session.resolutions.length : Math.min( resolutionIndex, session.resolutions.length - 1 ) );
		this.stepsSpinner = new JSpinner( new SpinnerNumberModel( steps, 1, 16, 1 ) );
		this.stepsSpinner.setToolTipText( "steps per octave, k = 2^(1/steps); the scale space is computed again" );
		this.octavesBox = new JComboBox<>( octaveChoices );
		this.octavesBox.setSelectedIndex( octaves < 1 ? 0 : Math.min( octaves, maxOctaveChoice ) );
		this.octavesBox.setToolTipText( "number of octaves (every octave halves the image), all = as many as the image allows; the scale space is computed again" );
		this.doneButton = new JButton( "Done" );
		this.cancelButton = new JButton( "Cancel" );
		this.countsLabel = new JLabel( " " );
		this.statusLabel = new JLabel( " " );
		this.legend = new ScaleLegend();
		this.filterTimer = new Timer( 50, e -> refilter() );
		this.filterTimer.setRepeats( false );
		this.recomputeTimer = new Timer( recomputeDelay, e -> computeHere() );
		this.recomputeTimer.setRepeats( false );

		this.frame = new JFrame( "Scale-space preview: " + name );
		this.frame.setContentPane( createPanel( name ) );
		this.frame.pack();

		// further windows are offset so that they do not cover each other
		final int offset = 30 * session.windows.size();
		this.frame.setLocation( 20 + offset, 20 + offset );

		this.thresholdSlider.addListener( new BoundedValueSlider.Listener()
		{
			@Override
			public void valueChanged( final double value ) { filterTimer.restart(); }

			@Override
			public void boundsChanged( final double min, final double max )
			{
				// the candidates are complete down to their threshold only
				if ( candidates != null && min < candidates.threshold )
					recompute();
			}
		} );

		this.finestBox.addItemListener( e -> refilter() );
		this.maximaBox.addItemListener( e -> refilter() );
		this.minimaBox.addItemListener( e -> refilter() );
		// the steps change the pyramid: computed again
		this.stepsSpinner.addChangeListener( e ->
		{
			final int value = ( (Number)stepsSpinner.getValue() ).intValue();

			if ( value != p.steps && !computing )
			{
				p.steps = value;
				recompute();
			}
		} );

		// the octaves change the pyramid: computed again
		this.octavesBox.addActionListener( e ->
		{
			if ( updatingOctavesBox )
				return;

			final int value = octavesBox.getSelectedIndex() == 0 ? -1 : octavesBox.getSelectedIndex();

			if ( value != p.octaves && !computing )
			{
				p.octaves = value;
				recompute();
			}
		} );

		// selecting another starting resolution opens a preview there right away
		this.resolutionBox.addActionListener( e ->
		{
			if ( !updatingResolutionBox )
				openAnother();
		} );
		this.doneButton.addActionListener( e -> session.done( this ) );
		this.cancelButton.addActionListener( e -> session.cancel() );

		this.frame.setDefaultCloseOperation( JFrame.DO_NOTHING_ON_CLOSE );
		this.frame.addWindowListener( new WindowAdapter()
		{
			@Override
			public void windowClosing( final WindowEvent e ) { dispose(); }
		} );

		// the ROI (a rectangle in x and y, all z) restricts the computation
		this.roiListener = new MouseAdapter()
		{
			@Override
			public void mouseReleased( final MouseEvent e ) { roiChanged(); }
		};

		this.imageWindowListener = new WindowAdapter()
		{
			@Override
			public void windowClosed( final WindowEvent e ) { dispose(); }
		};

		final ImageCanvas canvas = imp.getCanvas();

		if ( canvas != null )
			canvas.addMouseListener( roiListener );

		final ImageWindow window = imp.getWindow();

		if ( window != null )
			window.addWindowListener( imageWindowListener );

		// redraws the circles of the slice (never computes anything)
		this.sliceObserver = new SliceObserver( imp, ip -> drawOverlay() );

		session.register( this );

		frame.setVisible( true );

		recompute();
	}

	/**
	 * Computes the candidates down to the lower bound of the threshold slider inside the ROI (in the worker thread), then filters and draws
	 */
	protected void recompute()
	{
		final int gen = generation.incrementAndGet();
		final Interval processInterval = roi;
		final double lowerBound = thresholdSlider.getLowerBound();

		SwingUtilities.invokeLater( () ->
		{
			setComputing( true );
			statusLabel.setText( "computing the scale space of " + roiText( processInterval ) + " down to |response| > " + format( lowerBound, "0.00000" ) + " ..." );
		} );

		worker.submit( () ->
		{
			try
			{
				final ScaleSpaceParameters pRun = new ScaleSpaceParameters( p );
				pRun.threshold = lowerBound;

				final long time = System.currentTimeMillis();
				final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );

				IJ.showStatus( "Scale-space preview: computing ..." );

				final Candidates c = DoGScaleSpace.computeScaleSpaceCandidates( Views.extendMirrorSingle( img ), new FinalInterval( img ), processInterval, null, pRun, maxCandidates, service, IJ::showProgress );

				service.shutdown();

				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Scale-space preview: " + c.size() + " candidates (" + c.octaves + " octaves) in " + ( System.currentTimeMillis() - time ) + " ms." );
				IJ.showStatus( "Scale-space preview: " + c.size() + " candidates." );

				if ( gen != generation.get() || disposed )
					return;

				candidates = c;

				SwingUtilities.invokeLater( () ->
				{
					// the cap raised the threshold the candidates are complete down to
					if ( c.threshold > thresholdSlider.getLowerBound() )
						thresholdSlider.setBounds( c.threshold, Math.max( c.threshold * 2, thresholdSlider.getUpperBound() ) );

					legend.repaint();
					setComputing( false );
					statusLabel.setText( c.size() + " candidates in " + roiText( processInterval ) + ", |response| > " + format( c.threshold, "0.00000" ) + ", " + c.octaves + " octaves" );
				} );

				refilter();
			}
			catch ( final Exception e )
			{
				IOFunctions.println( "Scale-space preview failed: " + e );
				e.printStackTrace();
				IJ.showProgress( 1.0 );
				SwingUtilities.invokeLater( () -> { setComputing( false ); statusLabel.setText( "failed: " + e ); } );
			}
		} );
	}

	/**
	 * Filters the candidates with the current threshold and flags (in the worker thread) and draws the circles
	 */
	protected void refilter()
	{
		final Candidates c = candidates;

		if ( c == null )
			return;

		final double threshold = thresholdSlider.getValue();
		final boolean finest = finestBox.isSelected();
		final boolean findMax = maximaBox.isSelected();
		final boolean findMin = minimaBox.isSelected();
		final int gen = generation.get();

		worker.submit( () ->
		{
			if ( gen != generation.get() || disposed )
				return;

			final ArrayList< ScaleSpacePeak > filtered = DoGScaleSpace.filterCandidates( c, threshold, finest, findMin, findMax, p.localization, p.combineDistance );
			final List< List< ScaleSpacePeak > > bins = binPerSlice( filtered, (int)img.dimension( 2 ), p.anisotropy[ 2 ] );

			final ArrayList< Double > finestSigmas = new ArrayList<>();

			for ( final ScaleSpacePeak peak : filtered )
				if ( peak.finest )
					finestSigmas.add( peak.sigma );

			finestSigmas.sort( null );

			final String finestText = finestSigmas.isEmpty() ? "0 finest structures" : finestSigmas.size() + " finest structures, median fitted sigma " + format( finestSigmas.get( finestSigmas.size() / 2 ), "0.00" ) + " px";

			shown = filtered;
			perSlice = bins;

			SwingUtilities.invokeLater( () ->
			{
				countsLabel.setText( filtered.size() + " detections (" + finestText + ")" );
				drawOverlay();
			} );
		} );
	}

	/**
	 * Draws the circles of the detections that intersect the current slice (a new overlay is swapped in, the canvas paints the old one meanwhile)
	 */
	protected void drawOverlay()
	{
		final List< List< ScaleSpacePeak > > bins = perSlice;

		if ( bins == null || disposed || computing )
			return;

		final int slice = imp.getZ() - 1;

		if ( slice < 0 || slice >= bins.size() )
			return;

		// the scale space was computed for a z range around a slice only: leaving it computes again (after a short delay, scrolling)
		final Interval computed = roi;

		if ( slice < computed.min( 2 ) || slice > computed.max( 2 ) )
		{
			imp.setOverlay( textOverlay( "leaving the computed slices " + ( computed.min( 2 ) + 1 ) + ".." + ( computed.max( 2 ) + 1 ) + ", computing ..." ) );
			recomputeTimer.restart();
			return;
		}

		final double az = p.anisotropy[ 2 ];
		final double ay = p.anisotropy[ 1 ];
		final double sigmaMax = candidates == null ? p.sigmaMin : candidates.sigmaMax;
		final Overlay overlay = new Overlay();
		int drawn = 0;

		for ( final ScaleSpacePeak peak : bins.get( slice ) )
		{
			final double rs = projectedRadius( Math.max( minRadius, radius( peak.sigma ) ), ( slice - peak.l[ 2 ] ) * az );

			if ( rs < 0.5 )
				continue;

			final Roi roi = new OvalRoi( peak.l[ 0 ] + 0.5 - rs, peak.l[ 1 ] + 0.5 - rs / ay, 2 * rs, 2 * rs / ay );
			roi.setStrokeColor( peak.finest ? finestColor : color( peak.sigma, p.sigmaMin, sigmaMax ) );
			roi.setStroke( stroke( !peak.isMax ) );
			overlay.add( roi );

			if ( ++drawn >= maxCirclesPerSlice )
				break;
		}

		if ( drawn >= maxCirclesPerSlice && bins.get( slice ).size() > drawn )
			countsLabel.setText( shown.size() + " detections, showing the strongest " + drawn + " of " + bins.get( slice ).size() + " on this slice" );

		imp.setOverlay( overlay );
	}

	/**
	 * A new rectangle: compute it (in z around the current slice)
	 */
	protected void roiChanged()
	{
		final Roi r = imp.getRoi();

		if ( r == null || r.getType() != Roi.RECTANGLE )
		{
			// only rectangles, restore the current one
			if ( roi.dimension( 0 ) < img.dimension( 0 ) || roi.dimension( 1 ) < img.dimension( 1 ) )
				imp.setRoi( new Rectangle( (int)roi.min( 0 ), (int)roi.min( 1 ), (int)roi.dimension( 0 ), (int)roi.dimension( 1 ) ) );

			return;
		}

		final Rectangle b = r.getBounds();
		final Interval newRoi = roiInterval( img, b.x, b.y, b.x + b.width - 1, b.y + b.height - 1, imp.getZ() - 1, defaultZHalf( (long)b.width * b.height, defaultMaxVoxels ) );

		if ( Intervals.isEmpty( newRoi ) || Intervals.equals( newRoi, roi ) )
			return;

		roi = newRoi;
		recompute();
	}

	/**
	 * Computes the current rectangle around the current slice (when the slice leaves the computed z range)
	 */
	protected void computeHere()
	{
		if ( disposed || computing )
			return;

		final Interval newRoi = roiInterval( img, roi.min( 0 ), roi.min( 1 ), roi.max( 0 ), roi.max( 1 ), imp.getZ() - 1, defaultZHalf( roi.dimension( 0 ) * roi.dimension( 1 ), defaultMaxVoxels ) );

		if ( Intervals.equals( newRoi, roi ) && candidates != null )
			return;

		roi = newRoi;
		recompute();
	}

	/**
	 * Opens another preview at the resolution selected in the drop-down with the current threshold, flags and (scaled) region;
	 * the drop-down then shows this window's resolution again
	 */
	protected void openAnother()
	{
		final int index = resolutionBox.getSelectedIndex();

		updatingResolutionBox = true;
		resolutionBox.setSelectedIndex( resolutionIndex < 0 ? session.resolutions.length : Math.min( resolutionIndex, session.resolutions.length - 1 ) );
		updatingResolutionBox = false;

		final long[] ds;

		if ( index >= session.resolutions.length )
		{
			ds = ScaleSpaceGUI.queryManualResolution( downsampling );

			if ( ds == null )
				return;
		}
		else
		{
			ds = DownsampleTools.parseDownsampleChoice( session.resolutions[ index ] );
		}

		if ( Arrays.equals( ds, downsampling ) )
			return;

		session.open( ds, index >= session.resolutions.length ? -1 : index, thresholdSlider.getValue(), finestBox.isSelected(), minimaBox.isSelected(), maximaBox.isSelected(), p.steps, p.octaves, roi, downsampling );
	}

	/**
	 * While computing the controls and the image window are disabled and the image shows "computing" instead of the circles
	 */
	protected void setComputing( final boolean computing )
	{
		this.computing = computing;

		thresholdSlider.setEnabled( !computing );
		finestBox.setEnabled( !computing );
		maximaBox.setEnabled( !computing );
		minimaBox.setEnabled( !computing );
		stepsSpinner.setEnabled( !computing );
		octavesBox.setEnabled( !computing );
		resolutionBox.setEnabled( !computing );

		final ImageWindow window = imp.getWindow();

		if ( window != null )
			window.setEnabled( !computing );

		if ( computing )
			imp.setOverlay( textOverlay( "computing the scale space ..." ) );
	}

	/**
	 * @return an overlay with a message in the upper left corner of the image
	 */
	protected Overlay textOverlay( final String message )
	{
		final Overlay overlay = new Overlay();
		final int fontSize = Math.max( 12, Math.min( imp.getWidth(), imp.getHeight() ) / 24 );
		final TextRoi text = new TextRoi( fontSize / 2.0, fontSize / 2.0, message, new Font( Font.SANS_SERIF, Font.BOLD, fontSize ) );
		text.setStrokeColor( Color.yellow );
		text.setFillColor( new Color( 0, 0, 0, 160 ) );
		overlay.add( text );

		return overlay;
	}

	protected synchronized void dispose()
	{
		if ( disposed )
			return;

		disposed = true;

		filterTimer.stop();
		recomputeTimer.stop();
		worker.shutdownNow();
		sliceObserver.unregister();

		final ImageCanvas canvas = imp.getCanvas();

		if ( canvas != null )
			canvas.removeMouseListener( roiListener );

		final ImageWindow window = imp.getWindow();

		if ( window != null )
			window.removeWindowListener( imageWindowListener );

		final ImageWindow imageWindow = imp.getWindow();

		if ( imageWindow != null )
			imageWindow.setEnabled( true );

		imp.setOverlay( null );
		imp.changes = false;
		imp.close();

		frame.dispose();

		session.closed( this );
	}

	protected JPanel createPanel( final String name )
	{
		final JPanel panel = new JPanel( new GridBagLayout() );
		final GridBagConstraints c = new GridBagConstraints();

		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		c.gridx = 0;
		c.gridy = 0;
		c.gridwidth = 2;
		c.insets = new Insets( 2, 4, 2, 4 );

		// the initial blur at this resolution, at full resolution and in physical units
		final double sx = Math.abs( mipmapTransform.get( 0, 0 ) );
		String blur = format( p.sigmaMin, "0.00" ) + " px at this resolution = " + format( p.sigmaMin * sx, "0.00" ) + " px at full resolution";

		if ( session.voxelSize != null )
			blur += " = " + format( p.sigmaMin * sx * session.voxelSize.dimension( 0 ), "0.00" ) + " " + session.voxelSize.unit();

		// a fixed width wraps the long lines instead of widening the window
		final JLabel info = new JLabel( "<html><body style='width: 560px'><b>" + name + "</b> (" + Util.printCoordinates( img.dimensionsAsLongArray() ) + " px)<br>"
				+ "initial blur (sigma): " + blur + "<br>"
				+ "anisotropy (voxel size relative to x): " + format( p.anisotropy[ 0 ], "0.00" ) + ", " + format( p.anisotropy[ 1 ], "0.00" ) + ", " + format( p.anisotropy[ 2 ], "0.00" ) + "<br>"
				+ "intensity range: [" + format( p.minIntensity, "0.0" ) + ", " + format( p.maxIntensity, "0.0" ) + "]<br>"
				+ "the scale space is computed inside the rectangle ROI (draw another one) around the current slice, again when you leave the computed slices</body></html>" );
		info.setFont( GUIHelper.smallStatusFont );
		panel.add( info, c );

		++c.gridy;
		panel.add( new JLabel( "Threshold (minimal |response| at any scale):" ), c );

		++c.gridy;
		panel.add( thresholdSlider, c );

		// the finest level, the steps and the sign in one row (the details are tooltips)
		final JPanel options = new JPanel( new FlowLayout( FlowLayout.LEFT, 12, 0 ) );
		options.add( finestBox );
		final JPanel stepsRow = new JPanel( new FlowLayout( FlowLayout.LEFT, 4, 0 ) );
		final JLabel stepsLabel = new JLabel( "Steps/octave:" );
		stepsLabel.setToolTipText( stepsSpinner.getToolTipText() );
		stepsRow.add( stepsLabel );
		stepsRow.add( stepsSpinner );
		options.add( stepsRow );
		final JPanel octavesRow = new JPanel( new FlowLayout( FlowLayout.LEFT, 4, 0 ) );
		final JLabel octavesLabel = new JLabel( "Octaves:" );
		octavesLabel.setToolTipText( octavesBox.getToolTipText() );
		octavesRow.add( octavesLabel );
		octavesRow.add( octavesBox );
		options.add( octavesRow );
		options.add( maximaBox );
		options.add( minimaBox );

		++c.gridy;
		panel.add( options, c );

		legend.setToolTipText( "<html>the color of a circle is the scale (sigma) it was found at, its radius sqrt(3) sigma (the ball of maximal response, at least 1 px)<br>pink = finest structures (no extremum in scale, sigma fitted to the finest levels), dashed = minima</html>" );

		++c.gridy;
		panel.add( legend, c );

		++c.gridy;
		panel.add( countsLabel, c );

		statusLabel.setFont( GUIHelper.smallStatusFont );

		++c.gridy;
		panel.add( statusLabel, c );

		++c.gridy;
		panel.add( new JLabel( "Open a preview at another starting resolution (downsampling x, y, z):" ), c );

		++c.gridy;
		panel.add( resolutionBox, c );

		++c.gridy;
		c.gridwidth = 1;
		panel.add( doneButton, c );
		c.gridx = 1;
		panel.add( cancelButton, c );

		return panel;
	}

	/**
	 * The colors of the scales: a pink block for the finest level (no extremum in scale), then the hue ramp from red (the initial blur)
	 * to blue (the coarsest level) with the octaves as ticks
	 */
	protected class ScaleLegend extends JComponent
	{
		private static final long serialVersionUID = 1L;

		ScaleLegend() { setPreferredSize( new Dimension( 300, 34 ) ); }

		@Override
		protected void paintComponent( final Graphics g )
		{
			final Graphics2D g2d = (Graphics2D)g;
			final int barHeight = 12;
			final int finestWidth = Math.max( 30, getWidth() / 10 );
			final int x0 = finestWidth + 6;
			final int w = getWidth() - 1 - x0;

			final int octaves = candidates == null ? ( p.octaves > 0 ? p.octaves : DoGScaleSpace.autoOctaves( new FinalInterval( img ), p ) ) : candidates.octaves;
			final double sigmaMax = candidates == null ? DoGScaleSpace.sigmaBase( p.sigmaMin, LaPlaceFunctions.computeK( p.steps ), octaves - 1, p.steps ) : candidates.sigmaMax;
			final double range = Math.log( sigmaMax / p.sigmaMin ) / Math.log( 2 );

			g2d.setFont( GUIHelper.smallStatusFont );

			// the finest level left of the ramp
			g2d.setColor( finestColor );
			g2d.fillRect( 0, 0, finestWidth, barHeight + 1 );
			g2d.setColor( Color.black );
			g2d.drawString( "finest", Math.max( 0, ( finestWidth - g2d.getFontMetrics().stringWidth( "finest" ) ) / 2 ), barHeight + 15 );

			for ( int x = 0; x < w; ++x )
			{
				g2d.setColor( Color.getHSBColor( (float)( hueMax * x / (double)( w - 1 ) ), 1f, 1f ) );
				g2d.drawLine( x0 + x, 0, x0 + x, barHeight );
			}

			g2d.setColor( Color.black );

			// a tick per octave (level 1 = sigmaMin * 2^o) and the end
			for ( int o = 0; o <= octaves; ++o )
			{
				final double sigma = o < octaves ? p.sigmaMin * ( 1L << o ) : sigmaMax;
				final int x = x0 + ( range <= 0 ? ( o < octaves ? 0 : w ) : (int)Math.round( Math.min( 1, Math.log( sigma / p.sigmaMin ) / Math.log( 2 ) / range ) * w ) );

				g2d.drawLine( x, 0, x, barHeight + 3 );

				final String label = format( sigma, "0.0" );
				final int lw = g2d.getFontMetrics().stringWidth( label );
				g2d.drawString( label, Math.max( x0, Math.min( x0 + w - lw, x - lw / 2 ) ), barHeight + 15 );
			}
		}
	}

	/** the hue of the coarsest level (red = 0 is the initial blur, blue = 2/3 the coarsest) */
	public static final float hueMax = 2f / 3f;

	/** the color of the finest-level detections (no extremum in scale), left of red in the legend */
	public static final Color finestColor = new Color( 255, 105, 180 );

	/**
	 * @return the color of a scale: hue from red (sigmaMin) to blue (sigmaMax), linear in log( sigma )
	 */
	public static Color color( final double sigma, final double sigmaMin, final double sigmaMax )
	{
		final double range = Math.log( sigmaMax / sigmaMin );
		final double t = range <= 0 ? 0 : Math.min( 1, Math.max( 0, Math.log( sigma / sigmaMin ) / range ) );

		return Color.getHSBColor( (float)( hueMax * t ), 1f, 1f );
	}

	/**
	 * @return the radius of the ball whose scale-normalized response is maximal at sigma (3d), in pixels of the opened image
	 */
	public static double radius( final double sigma )
	{
		return Math.sqrt( 3 ) * sigma;
	}

	/**
	 * @return the radius of the intersection of a ball of radius r with a plane at distance dz from its center (0 if it does not intersect)
	 */
	public static double projectedRadius( final double r, final double dz )
	{
		final double rs2 = r * r - dz * dz;

		return rs2 <= 0 ? 0 : Math.sqrt( rs2 );
	}

	/**
	 * @return the stroke: one screen pixel at any zoom (ImageJ does not scale a stroke set this way), dashed for minima
	 */
	public static BasicStroke stroke( final boolean minimum )
	{
		if ( minimum )
			return new BasicStroke( 1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] { 3f, 3f }, 0f );
		else
			return new BasicStroke( 1f );
	}

	/**
	 * @return the detections per slice: each detection is listed for all slices its ball (radius sqrt(3) sigma, in pixels of x) intersects, in the order handed in
	 */
	public static List< List< ScaleSpacePeak > > binPerSlice( final List< ScaleSpacePeak > peaks, final int numSlices, final double anisotropyZ )
	{
		final ArrayList< List< ScaleSpacePeak > > bins = new ArrayList<>();

		for ( int s = 0; s < numSlices; ++s )
			bins.add( new ArrayList<>() );

		for ( final ScaleSpacePeak peak : peaks )
		{
			final double z = peak.numDimensions() > 2 ? peak.l[ 2 ] : 0;
			final double extent = Math.max( minRadius, radius( peak.sigma ) ) / anisotropyZ;

			final int from = Math.max( 0, (int)Math.ceil( z - extent ) );
			final int to = Math.min( numSlices - 1, (int)Math.floor( z + extent ) );

			for ( int s = from; s <= to; ++s )
				bins.get( s ).add( peak );
		}

		return bins;
	}

	/**
	 * @return how many slices around the current one fit into the voxel budget for a ROI of the given area (at least minZHalf)
	 */
	public static long defaultZHalf( final long area, final long maxVoxels )
	{
		return Math.max( minZHalf, ( maxVoxels / area - 1 ) / 2 );
	}

	/**
	 * @return the box x0..x1, y0..y1, centerZ - zHalf .. centerZ + zHalf, intersected with the image
	 */
	public static Interval roiInterval( final Interval img, final long x0, final long y0, final long x1, final long y1, final long centerZ, final long zHalf )
	{
		return Intervals.intersect( new FinalInterval( new long[] { x0, y0, centerZ - zHalf }, new long[] { x1, y1, centerZ + zHalf } ), img );
	}

	/**
	 * @return the region of a window at another starting resolution scaled to this one (x and y by the ratio of the downsampling factors, the
	 * central slice likewise, the z range from the voxel budget for the scaled area), null if it does not intersect the image
	 */
	public static Interval scaledRoi( final Interval fromRoi, final long[] fromDownsampling, final long[] toDownsampling, final Interval img, final long maxVoxels )
	{
		final long[] min = new long[ 2 ], max = new long[ 2 ];

		for ( int d = 0; d < 2; ++d )
		{
			final double scale = (double)fromDownsampling[ d ] / toDownsampling[ d ];
			min[ d ] = Math.round( fromRoi.min( d ) * scale );
			max[ d ] = Math.round( ( fromRoi.max( d ) + 1 ) * scale ) - 1;
		}

		final long centerZ = Math.round( ( fromRoi.min( 2 ) + fromRoi.max( 2 ) ) / 2.0 * fromDownsampling[ 2 ] / toDownsampling[ 2 ] );
		final Interval roi = roiInterval( img, min[ 0 ], min[ 1 ], max[ 0 ], max[ 1 ], centerZ, defaultZHalf( ( max[ 0 ] - min[ 0 ] + 1 ) * ( max[ 1 ] - min[ 1 ] + 1 ), maxVoxels ) );

		return Intervals.isEmpty( roi ) ? null : roi;
	}

	/**
	 * @return the default region: defaultRoiSize x defaultRoiSize (or the image if it is smaller) in the middle of the image, centerZ +/- the z
	 * range that fits into maxVoxels for that area
	 */
	public static Interval defaultRoi( final Interval img, final long centerZ, final long maxVoxels )
	{
		final long[] min = new long[ 2 ], max = new long[ 2 ];

		for ( int d = 0; d < 2; ++d )
		{
			final long size = Math.min( defaultRoiSize, img.dimension( d ) );
			min[ d ] = img.min( d ) + ( img.dimension( d ) - size ) / 2;
			max[ d ] = min[ d ] + size - 1;
		}

		return roiInterval( img, min[ 0 ], min[ 1 ], max[ 0 ], max[ 1 ], centerZ, defaultZHalf( ( max[ 0 ] - min[ 0 ] + 1 ) * ( max[ 1 ] - min[ 1 ] + 1 ), maxVoxels ) );
	}

	/**
	 * @return the default region around the middle slice with the default voxel budget
	 */
	public static Interval defaultRoi( final Interval img )
	{
		return defaultRoi( img, ( img.min( 2 ) + img.max( 2 ) ) / 2, defaultMaxVoxels );
	}

	/**
	 * @return the ROI as "x 0..255, y 0..255, z 30..60"
	 */
	protected static String roiText( final Interval roi )
	{
		return "x " + roi.min( 0 ) + ".." + roi.max( 0 ) + ", y " + roi.min( 1 ) + ".." + roi.max( 1 ) + ", z " + roi.min( 2 ) + ".." + roi.max( 2 );
	}

	protected static String format( final double value, final String format )
	{
		return new java.text.DecimalFormat( format, java.text.DecimalFormatSymbols.getInstance( Locale.US ) ).format( value );
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	protected static RandomAccessibleInterval< FloatType > toFloat( final RandomAccessibleInterval img )
	{
		return Converters.convertRAI( (RandomAccessibleInterval< RealType >)img, ( i, o ) -> o.set( i.getRealFloat() ), new FloatType() );
	}
}
