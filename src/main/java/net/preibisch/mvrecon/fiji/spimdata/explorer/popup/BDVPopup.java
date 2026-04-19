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
package net.preibisch.mvrecon.fiji.spimdata.explorer.popup;

import bdv.tools.brightness.ConverterSetup;
import bdv.util.Bounds;
import bdv.viewer.ConverterSetups;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerFrame;
import bdv.viewer.ViewerState;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.util.Collection;

import javax.swing.JMenuItem;
import javax.swing.JOptionPane;

import bdv.AbstractSpimSource;
import bdv.BigDataViewer;
import bdv.tools.InitializeViewerState;
import bdv.tools.transformation.TransformedSource;
import bdv.viewer.Source;
import bdv.viewer.ViewerOptions;
import bdv.viewer.ViewerPanel;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.registration.ViewRegistration;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.histogram.DiscreteFrequencyDistribution;
import net.imglib2.histogram.Histogram1d;
import net.imglib2.histogram.Real1dBinMapper;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Cast;
import net.imglib2.util.LinAlgHelpers;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.apply.BigDataViewerTransformationWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.GroupedRowWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ViewSetupExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.bdv.ScrollableBrightnessDialog;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.AbstractImgLoader;


public class BDVPopup extends JMenuItem implements ExplorerWindowSetable, BasicBDVPopup
{
	private static final long serialVersionUID = 5234649267634013390L;

	public ExplorerWindow< ? > panel;
	public BigDataViewer bdv = null;

	public BDVPopup()
	{
		super( "Display in BigDataViewer (on/off)" );

		this.addActionListener( new MyActionListener() );
	}

	@Override
	public JMenuItem setExplorerWindow( final ExplorerWindow< ? > panel )
	{
		this.panel = panel;
		return this;
	}

	// TODO (TP): replace with method and method reference
	public class MyActionListener implements ActionListener
	{
		@Override
		public void actionPerformed( final ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set for " + this.getClass().getSimpleName() );
				return;
			}

			new Thread( () ->
			{
				// if BDV was closed by the user
				if ( bdv != null && !bdv.getViewerFrame().isVisible() )
					bdv = null;

				if ( bdv == null )
				{
					try
					{
						bdv = createBDV( panel );
					}
					catch (Exception ex)
					{
						IOFunctions.println( "Could not run BigDataViewer: " + e );
						ex.printStackTrace();
						bdv = null;
					}
				}
				else
				{
					closeBDV();
				}
			}).start();
		}
	}

	public void reStartBDV()
	{
		new Thread( () ->
		{
			// if BDV was closed by the user
			if ( bdv != null && !bdv.getViewerFrame().isVisible() )
				bdv = null;

			if ( bdv != null )
			{
				final ViewerFrame frame = bdv.getViewerFrame();
				final WindowEvent windowClosing = new WindowEvent( frame, WindowEvent.WINDOW_CLOSING );
				frame.dispatchEvent( windowClosing );
				bdv = null;
			}

			try
			{
				bdv = createBDV( panel );
			}
			catch (Exception ex)
			{
				IOFunctions.println( "Could not run BigDataViewer: " + ex );
				ex.printStackTrace();
				bdv = null;
			}
		}).start();
	}

	@Override
	public void closeBDV()
	{
		if ( bdvRunning() )
			BigDataViewerTransformationWindow.disposeViewerWindow( bdv );
		bdv = null;
	}

	@Override
	public BigDataViewer getBDV() { return bdv; }

	@Override
	public void updateBDV()
	{
		if ( bdv == null )
			return;

		final Collection< ViewRegistration > regs = panel.getSpimData().getViewRegistrations().getViewRegistrations().values();
		regs.forEach( ViewRegistration::updateModel );

		final ViewerPanel viewer = bdv.getViewer();

		final ViewerState state = viewer.state().snapshot();
		state.getSources().forEach( BDVPopup::reloadTransformFromViewRegistrations );
		viewer.requestRepaint();
	}

	/**
	 * Calls {@link AbstractSpimSource#reload} on volatile and non-volatile
	 * versions nested under {@code source}. This reloads transformations from
	 * modified {@code ViewRegistrations}.
	 */
	private static void reloadTransformFromViewRegistrations( final SourceAndConverter< ? > source )
	{
		Source< ? > s = source.getSpimSource();

		if ( s instanceof TransformedSource )
			s = ( ( TransformedSource<?> ) s ).getWrappedSource();

		if ( s instanceof AbstractSpimSource )
			( ( AbstractSpimSource< ? > ) s ).reload();

		if ( source.asVolatile() != null )
			reloadTransformFromViewRegistrations( source.asVolatile() );
	}


	@Override
	public boolean bdvRunning()
	{
		final BasicBDVPopup p = panel.bdvPopup();
		return ( p != null && p.getBDV() != null && p.getBDV().getViewerFrame().isVisible() );
	}


	public void setBDV(BigDataViewer bdv)
	{
		// close existing bdv if necessary
		if (bdvRunning())
			new Thread(() -> {closeBDV();}).start();

		this.bdv = bdv;
		ViewSetupExplorerPanel.updateBDV( this.bdv, panel.colorMode(), panel.getSpimData(), panel.firstSelectedVD(), ((GroupedRowWindow)panel).selectedRowsGroups() );
	}

	/**
	 * set BDV brightness by sampling the mid z plane (and 1/4 and 3/4 if z is large enough )
	 * of the currently selected source (typically the first source) and getting quantiles from intensity histogram
	 * (slightly modified version of InitializeViewerState.initBrightness)
	 *
	 * @param cumulativeMinCutoff
	 * 		fraction of pixels that are allowed to be saturated at the lower end of the range.
	 * @param cumulativeMaxCutoff
	 * 		fraction of pixels that are allowed to be saturated at the upper end of the range.
	 * @param viewerFrame
	 *      the ViewerFrame containing ViewerState and ConverterSetups
	 */
	public static void initBrightness( final double cumulativeMinCutoff, final double cumulativeMaxCutoff, final ViewerFrame viewerFrame )
	{
		initBrightness( cumulativeMinCutoff, cumulativeMaxCutoff, viewerFrame.getViewerPanel().state().snapshot(), viewerFrame.getConverterSetups() );
	}

	private static void initBrightness( final double cumulativeMinCutoff, final double cumulativeMaxCutoff, final ViewerState state, final ConverterSetups converterSetups )
	{
		final SourceAndConverter< ? > current = state.getCurrentSource();
		if ( current == null )
			return;
		final Source< ? > source = current.getSpimSource();
		final int timepoint = state.getCurrentTimepoint();
		final Bounds bounds = estimateSourceRange( source, timepoint, cumulativeMinCutoff, cumulativeMaxCutoff );
		for ( final SourceAndConverter< ? > s : state.getSources() )
		{
			final ConverterSetup setup = converterSetups.getConverterSetup( s );
			setup.setDisplayRange( bounds.getMinBound(), bounds.getMaxBound() );
		}
	}

	/**
	 * @param cumulativeMinCutoff
	 * 		fraction of pixels that are allowed to be saturated at the lower end of the range.
	 * @param cumulativeMaxCutoff
	 * 		fraction of pixels that are allowed to be saturated at the upper end of the range.
	 */
	private static < T extends RealType< T > > Bounds estimateSourceRange( final Source< ? > source, final int timepoint, final double cumulativeMinCutoff, final double cumulativeMaxCutoff )
	{
		final Object type = source.getType();
		if ( type instanceof UnsignedShortType && source.isPresent( timepoint ) )
		{
			final RandomAccessibleInterval< T > img = Cast.unchecked( source.getSource( timepoint, source.getNumMipmapLevels() - 1 ) );
			final double sZ0 = img.min( 2 );
			final double sZ1 = img.max( 2 );
			final long z = ( img.min( 2 ) + img.max( 2 ) + 1 ) / 2;

			final int numBins = 6535;
			final Histogram1d< T > histogram = new Histogram1d<>( Views.hyperSlice( img, 2, z ), new Real1dBinMapper<>( 0, 65535, numBins, false ) );

			// sample some more planes if we have enough
			if ( img.dimension( 2 ) > 4 )
			{
				final long z14 = ( img.min( 2 ) + img.max( 2 ) + 1 ) / 4;
				final long z34 = ( img.min( 2 ) + img.max( 2 ) + 1 ) / 4 * 3;
				histogram.addData( Views.hyperSlice( img, 2, z14 ) );
				histogram.addData( Views.hyperSlice( img, 2, z34 ) );
			}

			final DiscreteFrequencyDistribution dfd = histogram.dfd();
			final long[] bin = new long[] { 0 };
			double cumulative = 0;
			int i = 0;
			for ( ; i < numBins && cumulative < cumulativeMinCutoff; ++i )
			{
				bin[ 0 ] = i;
				cumulative += dfd.relativeFrequency( bin );
			}
			final int min = i * 65535 / numBins;
			for ( ; i < numBins && cumulative < cumulativeMaxCutoff; ++i )
			{
				bin[ 0 ] = i;
				cumulative += dfd.relativeFrequency( bin );
			}
			final int max = i * 65535 / numBins;
			return new Bounds( min, max );
		}
		else if ( type instanceof UnsignedByteType )
			return new Bounds( 0, 255 );
		else
			return new Bounds( 0, 65535 );
	}

	public static BigDataViewer createBDV( final ExplorerWindow< ? > panel )
	{
		final BigDataViewer bdv = createBDV( panel.getSpimData(), panel.xml() );

		if ( bdv == null )
			return null;

		ViewSetupExplorerPanel.updateBDV( bdv, panel.colorMode(), panel.getSpimData(), panel.firstSelectedVD(), ((GroupedRowWindow)panel).selectedRowsGroups() );

		return bdv;
	}

	public static BigDataViewer createBDV(
			final AbstractSpimData< ? > spimData,
			final URI xml )
	{
		if ( AbstractImgLoader.class.isInstance( spimData.getSequenceDescription().getImgLoader() ) )
		{
			if ( JOptionPane.showConfirmDialog( null,
					"Opening <SpimData> dataset that is not suited for interactive browsing.\n" +
					"Consider resaving as HDF5 for better performance.\n" +
					"Proceed anyways?",
					"Warning",
					JOptionPane.YES_NO_OPTION ) == JOptionPane.NO_OPTION )
				return null;
		}

		final BigDataViewer bdv = BigDataViewer.open( spimData, xml.toString(), IOFunctions.getProgressWriter(), ViewerOptions.options() );
		if ( !bdv.tryLoadSettings( xml.toString() ) )
			InitializeViewerState.initBrightness( 0.001, 0.999, bdv.getViewerFrame() );

		// do not rotate BDV view by default
		BDVPopup.initTransform( bdv.getViewer() );

		ScrollableBrightnessDialog.setAsBrightnessDialog( bdv );

//		final ArrayList< InterestPointSource > interestPointSources = new ArrayList< InterestPointSource >();
//		interestPointSources.add( new InterestPointSource()
//		{
//			private final ArrayList< RealPoint > points;
//			{
//				points = new ArrayList< RealPoint >();
//				final Random rand = new Random();
//				for ( int i = 0; i < 1000; ++i )
//					points.add( new RealPoint( rand.nextDouble() * 1400, rand.nextDouble() * 800, rand.nextDouble() * 300 ) );
//			}
//
//			@Override
//			public final Collection< ? extends RealLocalizable > getLocalCoordinates( final int timepointIndex )
//			{
//				return points;
//			}
//
//			@Override
//			public void getLocalToGlobalTransform( final int timepointIndex, final AffineTransform3D transform )
//			{
//				transform.identity();
//			}
//		} );
//		final InterestPointOverlay interestPointOverlay = new InterestPointOverlay( bdv.getViewer(), interestPointSources );
//		bdv.getViewer().addRenderTransformListener( interestPointOverlay );
//		bdv.getViewer().getDisplay().addOverlayRenderer( interestPointOverlay );
//		bdv.getViewer().removeTransformListener( interestPointOverlay );
//		bdv.getViewer().getDisplay().removeOverlayRenderer( interestPointOverlay );

		return bdv;
	}

	public static void initTransform( final ViewerPanel viewer )
	{
		final Dimension dim = viewer.getDisplayComponent().getSize();
		final AffineTransform3D viewerTransform = initTransform( dim.width, dim.height, false, viewer.state().snapshot() );
		viewer.state().setViewerTransform( viewerTransform );
	}

	// TODO (TP) Add initTransform without rotation to bdv-core
	public static AffineTransform3D initTransform( final int viewerWidth, final int viewerHeight, final boolean zoomedIn, final ViewerState state )
	{
		final AffineTransform3D viewerTransform = new AffineTransform3D();
		final double cX = viewerWidth / 2.0;
		final double cY = viewerHeight / 2.0;

		final SourceAndConverter< ? > current = state.getCurrentSource();
		if ( current == null )
			return viewerTransform;
		final Source< ? > source = current.getSpimSource();
		final int timepoint = state.getCurrentTimepoint();
		if ( !source.isPresent( timepoint ) )
			return viewerTransform;

		final AffineTransform3D sourceTransform = new AffineTransform3D();
		source.getSourceTransform( timepoint, 0, sourceTransform );

		final Interval sourceInterval = source.getSource( timepoint, 0 );
		final double sX0 = sourceInterval.min( 0 );
		final double sX1 = sourceInterval.max( 0 );
		final double sY0 = sourceInterval.min( 1 );
		final double sY1 = sourceInterval.max( 1 );
		final double sZ0 = sourceInterval.min( 2 );
		final double sZ1 = sourceInterval.max( 2 );
		final double sX = ( sX0 + sX1 ) / 2;
		final double sY = ( sY0 + sY1 ) / 2;
		final double sZ = Math.round( ( sZ0 + sZ1 ) / 2 ); // z-slice in the middle of a pixel

		final double[][] m = new double[ 3 ][ 4 ];

		// NO rotation
		final double[] qViewer = new double[]{ 1, 0, 0, 0 };
		LinAlgHelpers.quaternionToR( qViewer, m );

		// translation
		final double[] centerSource = new double[] { sX, sY, sZ };
		final double[] centerGlobal = new double[ 3 ];
		final double[] translation = new double[ 3 ];
		sourceTransform.apply( centerSource, centerGlobal );
		LinAlgHelpers.quaternionApply( qViewer, centerGlobal, translation );
		LinAlgHelpers.scale( translation, -1, translation );
		LinAlgHelpers.setCol( 3, translation, m );

		viewerTransform.set( m );

		// scale
		final double[] pSource = new double[] { sX1 + 0.5, sY1 + 0.5, sZ };
		final double[] pGlobal = new double[ 3 ];
		final double[] pScreen = new double[ 3 ];
		sourceTransform.apply( pSource, pGlobal );
		viewerTransform.apply( pGlobal, pScreen );
		final double scaleX = cX / pScreen[ 0 ];
		final double scaleY = cY / pScreen[ 1 ];
		final double scale;
		if ( zoomedIn )
			scale = Math.max( scaleX, scaleY );
		else
			scale = Math.min( scaleX, scaleY );
		viewerTransform.scale( scale );

		// window center offset
		viewerTransform.set( viewerTransform.get( 0, 3 ) + cX - 0.5, 0, 3 );
		viewerTransform.set( viewerTransform.get( 1, 3 ) + cY - 0.5, 1, 3 );
		return viewerTransform;
	}

	/*
	This does not work yet, because invalidateAll is not implemented yet.

	private static final void forceBDVReload(final AbstractSpimSource< ? > s)
	{
		try
		{
			Class< ? > clazz = null;
			boolean found = false;

			do
			{
				if ( clazz == null )
					clazz = s.getClass();
				else
					clazz = clazz.getSuperclass();

				if ( clazz != null )
					for ( final Field field : clazz.getDeclaredFields() )
						if ( field.getName().equals( "cachedSources" ) )
							found = true;
			}
			while ( !found && clazz != null );

			if ( !found )
			{
				System.out.println( "Failed to find SpimSource.cachedSources field. Quiting." );
				return;
			}

			final Field cachedSources = clazz.getDeclaredField( "cachedSources" );
			cachedSources.setAccessible( true );
			CacheAsUncheckedCacheAdapter< ?, ? > chachedSourcesField =
					(CacheAsUncheckedCacheAdapter< ?, ? >) cachedSources.get( s );
			chachedSourcesField.invalidateAll();
			final Field cachedInterpolatedSources = clazz.getDeclaredField( "cachedInterpolatedSources" );
			cachedInterpolatedSources.setAccessible( true );
			CacheAsUncheckedCacheAdapter< ?, ? > cachedInterpolatedSourcesField =
					(CacheAsUncheckedCacheAdapter< ?, ? >) cachedInterpolatedSources.get( s );
			cachedInterpolatedSourcesField.invalidateAll();

		}
		catch ( Exception e )
		{
			e.printStackTrace();
		}

	}
	*/
}
