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

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.swing.JMenuItem;

import bdv.BigDataViewer;
import bdv.SpimSource;
import bdv.ViewerImgLoader;
import bdv.ViewerSetupImgLoader;
import bdv.VolatileSpimSource;
import bdv.cache.CacheControl;
import bdv.tools.brightness.ConverterSetup;
import bdv.tools.brightness.MinMaxGroup;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerOptions;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.apply.BigDataViewerTransformationWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.SelectedViewDescriptionListener;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ViewSetupExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.bdv.ScrollableBrightnessDialog;
import util.BDVTools;

/**
 * Opt-in alternative to {@link BDVPopup} that opens BigDataViewer with NO sources
 * and then dynamically adds (and removes / hides) sources as the user selects views
 * in the explorer. Sidesteps the eager-init O(N) source construction and the
 * O(N²) {@code BrightnessDialog} listener cascade that we measured in heavily-split
 * datasets (~30 ms per setup × N setups).
 */
public class LazyBDVPopup extends JMenuItem implements ExplorerWindowSetable, BasicBDVPopup
{
	private static final long serialVersionUID = -8463128348928763621L;

	/**
	 * If {@code true}, deselecting a view in the explorer calls
	 * {@code state.removeSource(...)} — frees BDV state, lower memory.
	 * If {@code false}, deselect calls {@code state.setSourcesActive(..., false)} —
	 * re-select is faster but BDV's source list grows monotonically over a session.
	 */
	public static boolean removeOnDeselect = true;

	public ExplorerWindow< ? > panel;
	public BigDataViewer bdv = null;

	// Per-instance state for the running lazy BDV.
	private final Map< Integer, SourceAndConverter< ? > > activeBySetupId = new HashMap<>();
	private final Map< Integer, ConverterSetup > setupBySetupId = new HashMap<>();
	private double[] sharedRange = null;
	private boolean firstTransformDone = false;
	private SelectedViewDescriptionListener< ? > registeredListener = null;

	public LazyBDVPopup()
	{
		super( "Display in BigDataViewer (lazy on/off)" );
		this.addActionListener( new MyActionListener() );
	}

	@Override
	public JMenuItem setExplorerWindow( final ExplorerWindow< ? > panel )
	{
		this.panel = panel;
		return this;
	}

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
			new Thread( () -> {
				if ( bdv != null && !bdv.getViewerFrame().isVisible() )
				{
					bdv = null;
					resetState();
				}
				if ( bdv == null )
					openBDV( panel );
				else
					closeBDV();
			} ).start();
		}
	}

	/**
	 * Open BDV in lazy mode for {@code panel}'s data, if not already open.
	 * Returns the running {@link BigDataViewer}. Public so the explorer
	 * auto-open path can call it directly without going through the menu.
	 */
	public BigDataViewer openBDV( final ExplorerWindow< ? > panel )
	{
		if ( bdv != null && bdv.getViewerFrame().isVisible() )
			return bdv;
		if ( bdv != null )
		{
			bdv = null;
			resetState();
		}
		try
		{
			bdv = createLazyBDV( panel );
		}
		catch ( final Exception ex )
		{
			IOFunctions.println( "Could not run BigDataViewer (lazy): " + ex );
			ex.printStackTrace();
			bdv = null;
		}
		return bdv;
	}

	@Override
	public void closeBDV()
	{
		if ( bdvRunning() )
			BigDataViewerTransformationWindow.disposeViewerWindow( bdv );
		bdv = null;
		resetState();
	}

	@Override public BigDataViewer getBDV() { return bdv; }

	@Override
	public boolean bdvRunning()
	{
		return bdv != null && bdv.getViewerFrame().isVisible();
	}

	@Override
	public void updateBDV()
	{
		// Mirror BDVPopup.updateBDV: reload registration transforms onto the
		// current sources and ask the renderer to repaint. ALSO trigger an
		// explicit Swing-side display.repaint() — without that, overlay-only
		// changes (interest-point point-size or plane-thickness sliders, etc.)
		// don't actually redraw on screen until a Swing-driven event (mouse
		// hover) catches up.
		if ( bdv == null )
			return;
		bdv.getViewer().requestRepaint();
		bdv.getViewer().getDisplay().repaint();
	}

	private void resetState()
	{
		activeBySetupId.clear();
		setupBySetupId.clear();
		sharedRange = null;
		firstTransformDone = false;
		registeredListener = null;
	}

	@SuppressWarnings( { "rawtypes", "unchecked" } )
	private BigDataViewer createLazyBDV( final ExplorerWindow< ? > panel )
	{
		final long tStart = System.currentTimeMillis();
		final AbstractSpimData< ? > spimData = panel.getSpimData();
		final AbstractSequenceDescription< ?, ?, ? > seq = spimData.getSequenceDescription();
		IOFunctions.println( "[LazyBDV-open] starting (view-setups="
				+ seq.getViewSetupsOrdered().size() + ", img-loader="
				+ seq.getImgLoader().getClass().getSimpleName()
				+ ", removeOnDeselect=" + removeOnDeselect + ")" );

		// Open BDV with NO sources; sources are added on selection.
		final ArrayList< ConverterSetup > emptyConverterSetups = new ArrayList<>();
		final ArrayList< SourceAndConverter< ? > > emptySources = new ArrayList<>();
		final int numTimepoints = seq.getTimePoints().size();
		final CacheControl cache = ( ( ViewerImgLoader ) seq.getImgLoader() ).getCacheControl();
		final BigDataViewer newBdv = new BigDataViewer( emptyConverterSetups, emptySources, spimData,
				numTimepoints, cache, panel.xml().toString(),
				IOFunctions.getProgressWriter(), ViewerOptions.options() );
		newBdv.getViewerFrame().setVisible( true );
		ScrollableBrightnessDialog.setAsBrightnessDialog( newBdv );


		// Switch BDV into fused display mode so multiple active sources render
		// simultaneously. Default is SINGLE which only shows the current source.
		BDVTools.setFusedModeSimple( newBdv, spimData );

		// Assign the field BEFORE the initial sync so syncSources's `bdv` reads
		// the live instance instead of the still-null field. (Without this, the
		// initial population no-ops and BDV starts empty until the user re-selects.)
		this.bdv = newBdv;

		IOFunctions.println( "[LazyBDV-open] empty BDV opened: "
				+ ( System.currentTimeMillis() - tStart ) + "ms" );

		// Wire selection listener — fires on every explorer-row selection change.
		// Raw types here intentional: SelectedViewDescriptionListener<AS> generics don't
		// match cleanly through ExplorerWindow<?>. The panel's addListener accepts the
		// raw form via the unchecked path.
		final ViewSetupExplorerPanel rawPanel = ( ViewSetupExplorerPanel ) panel;
		final SelectedViewDescriptionListener listener = new SelectedViewDescriptionListener()
		{
			@Override
			public void selectedViewDescriptions( final List viewDescriptions )
			{
				if ( bdv == null || !bdv.getViewerFrame().isVisible() )
					return;
				try
				{
					@SuppressWarnings( "unchecked" )
					final List< List< BasicViewDescription< ? > > > vds =
							( List< List< BasicViewDescription< ? > > > ) viewDescriptions;
					syncSources( spimData, vds );
				}
				catch ( final Exception ex )
				{
					IOFunctions.println( "[LazyBDV] sync failed: " + ex );
					ex.printStackTrace();
				}
			}

			@Override public void updateContent( final AbstractSpimData data ) {}
			@Override public void save() {}
			@Override public void quit() {}
		};
		rawPanel.addListener( listener );
		registeredListener = listener;

		// Trigger an initial sync with whatever's already selected so the user sees
		// their current selection in the new BDV without an extra click.
		final List< List< BasicViewDescription< ? > > > current = new ArrayList<>();
		for ( final List< BasicViewDescription< ? > > row : ( ( ViewSetupExplorerPanel< ? > ) panel ).selectedRows )
			current.add( row );
		try { syncSources( spimData, current ); }
		catch ( final Exception ex ) { ex.printStackTrace(); }

		IOFunctions.println( "[LazyBDV-open] TOTAL: "
				+ ( System.currentTimeMillis() - tStart ) + "ms" );

		return newBdv;
	}

	private void syncSources( final AbstractSpimData< ? > spimData,
			final List< List< BasicViewDescription< ? > > > selected )
	{
		if ( bdv == null || !bdv.getViewerFrame().isVisible() )
			return;

		// Wanted set: every setupId referenced by the selection.
		final HashSet< Integer > wantedIds = new HashSet<>();
		for ( final List< BasicViewDescription< ? > > row : selected )
			for ( final BasicViewDescription< ? > vd : row )
				wantedIds.add( vd.getViewSetupId() );

		// Diff against currently-active.
		final ArrayList< Integer > toAdd = new ArrayList<>();
		for ( final Integer id : wantedIds )
			if ( !activeBySetupId.containsKey( id ) )
				toAdd.add( id );
		final ArrayList< Integer > toRemoveOrHide = new ArrayList<>();
		for ( final Integer id : activeBySetupId.keySet() )
			if ( !wantedIds.contains( id ) )
				toRemoveOrHide.add( id );

		// Resolve sequence description once.
		final AbstractSequenceDescription< ?, ?, ? > seq = spimData.getSequenceDescription();
		final int sampleTimepoint = seq.getTimePoints().getTimePointsOrdered().get( 0 ).getId();

		// Decide the brightness range for new adds, BEFORE we modify any maps.
		// Priority:
		//   (1) any currently-active setup — picks up the user's manual brightness
		//       adjustment so newly added views match what's already on screen.
		//   (2) sharedRange — the carried-forward default (set when sources were
		//       last removed, or sampled on the very first add).
		//   (3) null → sample a histogram from the first source we add.
		double[] addRange = null;
		if ( !setupBySetupId.isEmpty() )
		{
			final ConverterSetup any = setupBySetupId.values().iterator().next();
			if ( any != null )
				addRange = new double[]{ any.getDisplayRangeMin(), any.getDisplayRangeMax() };
		}
		if ( addRange == null && sharedRange != null )
			addRange = sharedRange;

		// Before removing, capture the range from the first to-remove setup as the
		// new default. Carries the user's brightness adjustment forward to future
		// adds when all actives are removed at once.
		if ( !toRemoveOrHide.isEmpty() )
		{
			final ConverterSetup csR = setupBySetupId.get( toRemoveOrHide.get( 0 ) );
			if ( csR != null )
				sharedRange = new double[]{ csR.getDisplayRangeMin(), csR.getDisplayRangeMax() };
		}

		// ADD: build SourceAndConverter + ConverterSetup, set brightness, register.
		final ArrayList< SourceAndConverter< ? > > addList = new ArrayList<>();
		for ( final Integer id : toAdd )
		{
			final BasicViewSetup setup = seq.getViewSetups().get( id );
			if ( setup == null )
				continue;
			final SourceAndConverter< ? > soc = createSourceAndConverter( spimData, setup );
			final ConverterSetup cs = BigDataViewer.createConverterSetup( soc, id );

			// First-ever add with no prior brightness → sample histogram now.
			if ( addRange == null )
			{
				sharedRange = sampleAndComputeRange( soc.getSpimSource(), sampleTimepoint );
				addRange = sharedRange;
			}
			if ( cs != null && addRange != null )
				cs.setDisplayRange( addRange[ 0 ], addRange[ 1 ] );

			// Register: ConverterSetups (forwarder listener) → SetupAssignments (group)
			// → ViewerState (the source list).
			if ( cs != null )
			{
				bdv.getViewerFrame().getConverterSetups().put( soc, cs );
				bdv.getSetupAssignments().addSetup( cs );

				// Move this setup into the FIRST group so all setups share one group
				// (matches what BDV's constructor does for initial setups). Avoids the
				// brightness dialog accumulating one panel per setup.
				final List< MinMaxGroup > groups = bdv.getSetupAssignments().getMinMaxGroups();
				if ( groups.size() > 1 )
					bdv.getSetupAssignments().moveSetupToGroup( cs, groups.get( 0 ) );
			}
			addList.add( soc );
			activeBySetupId.put( id, soc );
			setupBySetupId.put( id, cs );
		}
		if ( !addList.isEmpty() )
		{
			bdv.getViewer().state().addSources( addList );
			// addSources() does not auto-mark sources active. In FUSED display mode
			// only active sources render, so without this only the current source
			// (the first ever added) would be visible.
			bdv.getViewer().state().setSourcesActive( addList, true );
		}

		// REMOVE or HIDE.
		if ( removeOnDeselect )
		{
			final ArrayList< SourceAndConverter< ? > > removeList = new ArrayList<>();
			for ( final Integer id : toRemoveOrHide )
			{
				final SourceAndConverter< ? > soc = activeBySetupId.remove( id );
				setupBySetupId.remove( id );
				if ( soc != null )
					removeList.add( soc );
			}
			if ( !removeList.isEmpty() )
				bdv.getViewer().state().removeSources( removeList );
		}
		else
		{
			// hide deselected
			final ArrayList< SourceAndConverter< ? > > hideList = new ArrayList<>();
			for ( final Integer id : toRemoveOrHide )
			{
				final SourceAndConverter< ? > soc = activeBySetupId.get( id );
				if ( soc != null )
					hideList.add( soc );
			}
			if ( !hideList.isEmpty() )
				bdv.getViewer().state().setSourcesActive( hideList, false );
			// (newly added sources are visible by default; previously-hidden but
			// now-selected sources go through the toAdd path on first add and stay
			// visible — they are not in activeBySetupId.keys at remove time.)
		}

		// First time we have any source, fit-to-window once.
		if ( !firstTransformDone && !activeBySetupId.isEmpty() )
		{
			firstTransformDone = true;
			BDVPopup.initTransform( bdv.getViewer() );
		}
	}

	private static < T extends NumericType< T >, V extends Volatile< T > & NumericType< V > > SourceAndConverter< T >
			createSourceAndConverter( final AbstractSpimData< ? > spimData, final BasicViewSetup setup )
	{
		final int setupId = setup.getId();
		final ViewerImgLoader imgLoader = ( ViewerImgLoader ) spimData.getSequenceDescription().getImgLoader();
		@SuppressWarnings( "unchecked" )
		final ViewerSetupImgLoader< T, V > setupImgLoader = ( ViewerSetupImgLoader< T, V > ) imgLoader.getSetupImgLoader( setupId );
		final T type = setupImgLoader.getImageType();
		final V volatileType = setupImgLoader.getVolatileImageType();

		if ( !( type instanceof NumericType ) )
			throw new IllegalArgumentException( "ImgLoader of type " + type.getClass() + " not supported." );

		final String name = createSetupName( setup );

		SourceAndConverter< V > vsoc = null;
		if ( volatileType != null )
		{
			final VolatileSpimSource< V > vs = new VolatileSpimSource<>( spimData, setupId, name );
			vsoc = new SourceAndConverter<>( vs, BigDataViewer.<V>createConverterToARGB( volatileType ) );
		}
		final SpimSource< T > s = new SpimSource<>( spimData, setupId, name );
		final SourceAndConverter< T > soc = new SourceAndConverter<>( s, BigDataViewer.<T>createConverterToARGB( type ), vsoc );
		return BigDataViewer.<T, V>wrapWithTransformedSource( soc );
	}

	/** Mirrors BigDataViewer.createSetupName (which is private). */
	private static String createSetupName( final BasicViewSetup setup )
	{
		if ( setup.hasName() )
			return setup.getName();
		String name = "";
		final Angle angle = setup.getAttribute( Angle.class );
		if ( angle != null )
			name += ( name.isEmpty() ? "" : " " ) + "a " + angle.getName();
		final Channel channel = setup.getAttribute( Channel.class );
		if ( channel != null )
			name += ( name.isEmpty() ? "" : " " ) + "c " + channel.getName();
		return name;
	}

	/**
	 * Single-source histogram-based brightness. Mirrors BDV's
	 * {@code estimateBounds} (6535 bins over [0, 65535], cumulative cutoffs
	 * 0.001 / 0.999). Falls back to type-default for non-{@link UnsignedShortType}.
	 */
	private static double[] sampleAndComputeRange( final bdv.viewer.Source< ? > src, final int timepoint )
	{
		final Object type = src.getType();
		if ( type instanceof UnsignedByteType )
			return new double[]{ 0, 255 };
		if ( !( type instanceof UnsignedShortType ) )
			return new double[]{ 0, 255 };
		if ( !src.isPresent( timepoint ) )
			return new double[]{ 0, 255 };

		@SuppressWarnings( "unchecked" )
		final RandomAccessibleInterval< UnsignedShortType > img =
				( RandomAccessibleInterval< UnsignedShortType > ) src.getSource( timepoint, src.getNumMipmapLevels() - 1 );
		if ( img.numDimensions() < 3 || img.dimension( 2 ) <= 0 )
			return new double[]{ 0, 255 };

		final long z = ( img.min( 2 ) + img.max( 2 ) + 1 ) / 2;
		final int numBins = 6535;
		final long[] hist = new long[ numBins ];
		long total = 0;
		for ( final UnsignedShortType v : Views.hyperSlice( img, 2, z ) )
		{
			int bin = ( v.get() * numBins ) / 65536;
			if ( bin >= numBins )
				bin = numBins - 1;
			hist[ bin ]++;
			total++;
		}
		if ( total == 0 )
			return new double[]{ 0, 255 };

		long cum = 0;
		int i = 0;
		while ( i < numBins && ( double ) cum / total < 0.001 )
			cum += hist[ i++ ];
		final double rangeMin = i * 65535.0 / numBins;
		while ( i < numBins && ( double ) cum / total < 0.999 )
			cum += hist[ i++ ];
		final double rangeMax = i * 65535.0 / numBins;
		return new double[]{ rangeMin, rangeMax };
	}

}
