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
package net.preibisch.mvrecon.fiji.spimdata.explorer.popup;

import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.ImgLib2Temp.Pair;
import net.preibisch.mvrecon.fiji.ImgLib2Temp.ValuePair;
import net.preibisch.mvrecon.fiji.plugin.CreateFromCorresponding_Detections;
import net.preibisch.mvrecon.fiji.plugin.Filter_Detections;
import net.preibisch.mvrecon.fiji.plugin.Limit_Detections;
import net.preibisch.mvrecon.fiji.plugin.RelativeThinOut_Detections;
import net.preibisch.mvrecon.fiji.plugin.Show_Relative_Histogram;
import net.preibisch.mvrecon.fiji.plugin.ThinOut_Detections;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.StoredDetectionParameters;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint.InterestPointExplorer;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter.Axis;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5ToZarr;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarr;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.VirtualFilteredInterestPoints;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointremoval.DistanceHistogram;
import net.preibisch.mvrecon.process.interestpointremoval.InteractiveProjections;

import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.ViewId;

public class RemoveDetectionsPopup extends JMenu implements ExplorerWindowSetable
{
	private static final long serialVersionUID = 1L;

	ExplorerWindow< ? > panel = null;

	public static int defaultLabel = 0;
	public static String defaultNewLabel = "Manually removed";

	/** the labels of the selected views whose interest points are still in the old interestpoints.n5, filled when this menu opens (fillResaveZarr) */
	final JMenu resaveZarr = new JMenu( "Resave as ZARR store" );

	public RemoveDetectionsPopup()
	{
		super( "Filter/Manage Interest Points" );

		final JMenuItem createFiltered = new JMenuItem( "Filter interest points (response/sigma/intensity) ..." );
		final JMenuItem createLimited = new JMenuItem( "Filter interest points (keep fixed number) ..." );
		final JMenu createRamp = new JMenu( "Filter interest points (threshold ramp)" );
		final JMenu filterInteractively = new JMenu( "Filter interest points (interactively)" );
		final JMenu redetect = new JMenu( "Re-detect interest points (stored parameters)" );
		final JMenuItem createFromCorr = new JMenuItem( "New interest points from correspondences ..." );
		final JMenu showDistanceHist = new JMenu( "Show Distance Histogram" );
		final JMenuItem showRelativeDistanceHist = new JMenuItem( "Show Relative Distance Histogram ... " );
		final JMenuItem byDistance = new JMenuItem( "Remove by Distance ..." );
		final JMenuItem byRelativeDistance = new JMenuItem( "Remove by relative Distance ..." );
		final JMenuItem interactivelyXY = new JMenuItem( "Remove Interactively (XY Projection) ..." );
		final JMenuItem interactivelyXZ = new JMenuItem( "Remove Interactively (XZ Projection) ..." );
		final JMenuItem interactivelyYZ = new JMenuItem( "Remove Interactively (YZ Projection) ..." );

		createFiltered.addActionListener( new FilterListener() );
		this.add( createFiltered );
		createLimited.addActionListener( new LimitListener() );
		this.add( createLimited );

		// one entry per label, so that it is clear which detection threshold the ramp starts from
		createRamp.addMenuListener( new MenuListener()
		{
			@Override
			public void menuSelected( final MenuEvent e )
			{
				createRamp.removeAll();

				final SpimData2 spimData = (SpimData2)panel.getSpimData();

				final ArrayList< ViewId > views = new ArrayList<>();
				views.addAll( ApplyTransformationPopup.getSelectedViews( panel ) );

				// filter not present ViewIds
				SpimData2.filterMissingViews( spimData, views );

				final String[] labels = InterestPointTools.getAllInterestPointLabels( spimData, views );

				if ( labels.length == 0 )
				{
					final JMenuItem item = new JMenuItem( "No interest points found" );
					item.setForeground( Color.GRAY );
					createRamp.add( item );
				}
				else
				{
					for ( int i = 0; i < labels.length; ++i )
					{
						final String label = InterestPointTools.getSelectedLabel( labels, i );
						final String warning = labels[ i ].substring( label.length() );
						final double t = Filter_Detections.thresholdOf( spimData, views, label );
						// a label stored before the attributes existed has no response to filter by (the points are loaded, the table loaded them already)
						final boolean response = InterestPointTools.missingAttributes( spimData, views, label, EnumSet.of( Axis.RESPONSE ) ).isEmpty();
						final JMenuItem item;

						if ( !response || Double.isNaN( t ) )
						{
							item = new JMenuItem( label + ( response ? " (no detection threshold)" : " (no response stored, detect again)" ) + warning );
							item.setForeground( Color.GRAY );
							item.setEnabled( false );
						}
						else
						{
							item = new JMenuItem( label + " (t = " + InterestPointFilter.plain( t ) + ")" + warning );
							item.addActionListener( new RampListener( label ) );
						}

						createRamp.add( item );
					}
				}
			}

			@Override
			public void menuDeselected( final MenuEvent e ) {}

			@Override
			public void menuCanceled( final MenuEvent e ) {}
		} );
		this.add( createRamp );

		// a shortcut to "Filter interactively ..." of the Interest Point Explorer, one entry per label
		filterInteractively.addMenuListener( new MenuListener()
		{
			@Override
			public void menuSelected( final MenuEvent e )
			{
				filterInteractively.removeAll();

				final SpimData2 spimData = (SpimData2)panel.getSpimData();

				final ArrayList< ViewId > views = new ArrayList<>();
				views.addAll( ApplyTransformationPopup.getSelectedViews( panel ) );

				// filter not present ViewIds
				SpimData2.filterMissingViews( spimData, views );

				final String[] labels = InterestPointTools.getAllInterestPointLabels( spimData, views );

				if ( labels.length == 0 )
				{
					final JMenuItem item = new JMenuItem( "No interest points found" );
					item.setForeground( Color.GRAY );
					filterInteractively.add( item );
				}
				else
				{
					for ( int i = 0; i < labels.length; ++i )
					{
						final JMenuItem item = new JMenuItem( labels[ i ] );
						item.addActionListener( new InteractiveFilterListener( InterestPointTools.getSelectedLabel( labels, i ) ) );
						filterInteractively.add( item );
					}
				}
			}

			@Override
			public void menuDeselected( final MenuEvent e ) {}

			@Override
			public void menuCanceled( final MenuEvent e ) {}
		} );
		this.add( filterInteractively );

		// the labels of the selected views whose points lack response, sigma or intensity (detected before the attributes were
		// stored): one entry each, which opens "Detect Interest Points" with the label and its stored parameters preset, so that
		// the detection replaces the label with points that have them; filled when it opens (it loads the points)
		redetect.setToolTipText( "<html>Lists the labels of the selected views whose points lack response, sigma or intensity (detected before they were stored)<br>" +
				"and opens 'Detect Interest Points' with the label and its stored parameters preset; the detection replaces the label.</html>" );
		redetect.addMenuListener( new MenuListener()
		{
			@Override
			public void menuSelected( final MenuEvent e ) { fillRedetect( redetect ); }

			@Override
			public void menuDeselected( final MenuEvent e ) {}

			@Override
			public void menuCanceled( final MenuEvent e ) {}
		} );
		this.add( redetect );

		// the labels of the selected views that still have interest points in the old interestpoints.n5 (one entry each,
		// "ALL labels" if there are several), gray if there are none; filled when THIS menu opens, since a disabled submenu
		// cannot open to fill itself
		resaveZarr.setToolTipText( "<html>Moves the interest points of the selected views that are still stored in the old interestpoints.n5 into interestpoints.zarr.<br>" +
				"The N5 is kept; the command line 'InterestPointsN5ToZarr dataset.xml' verifies and deletes it.</html>" );
		this.addMenuListener( new MenuListener()
		{
			@Override
			public void menuSelected( final MenuEvent e ) { fillResaveZarr(); }

			@Override
			public void menuDeselected( final MenuEvent e ) {}

			@Override
			public void menuCanceled( final MenuEvent e ) {}
		} );
		this.add( resaveZarr );

		this.add( new Separator() );

		createFromCorr.addActionListener( new CreateFromCorrespondencesListener() );
		this.add( createFromCorr );

		this.add( new Separator() );

		showDistanceHist.addMenuListener( new MenuListener()
		{
			@Override
			public void menuSelected( MenuEvent e )
			{
				showDistanceHist.removeAll();

				final SpimData2 spimData = (SpimData2)panel.getSpimData();

				final ArrayList< ViewId > views = new ArrayList<>();
				views.addAll( ApplyTransformationPopup.getSelectedViews( panel ) );

				// filter not present ViewIds
				SpimData2.filterMissingViews( panel.getSpimData(), views );

				final String[] labels = InterestPointTools.getAllInterestPointLabels( spimData, views );

				if ( labels.length == 0 )
				{
					JMenuItem item = new JMenuItem( "No interest points found" );
					item.setForeground( Color.GRAY );
					showDistanceHist.add( item );
				}
				else
				{
					for ( int i = 0; i < labels.length; ++i )
					{
						JMenuItem item = new JMenuItem( labels[ i ] );
						item.addActionListener( new HistogramListener( spimData, views, InterestPointTools.getSelectedLabel( labels, i ), i ) );
						showDistanceHist.add( item );
					}
				}
			}

			@Override
			public void menuDeselected( MenuEvent e ) {}

			@Override
			public void menuCanceled( MenuEvent e ) {}
		} );
		this.add( showDistanceHist );

		showRelativeDistanceHist.addActionListener( new RelativeDistanceHistogramListener() );
		this.add( showRelativeDistanceHist );

		this.add( new Separator() );

		byDistance.addActionListener( new ThinOutListener() );
		this.add( byDistance );
		byRelativeDistance.addActionListener( new RelativeThinOutListener() );
		this.add( byRelativeDistance );

		interactivelyXY.addActionListener( new InteractiveListener( 1 ) );
		interactivelyXZ.addActionListener( new InteractiveListener( 2 ) );
		interactivelyYZ.addActionListener( new InteractiveListener( 3 ) );
		this.add( interactivelyXY );
		this.add( interactivelyXZ );
		this.add( interactivelyYZ );
	}

	@Override
	public JMenuItem setExplorerWindow( final ExplorerWindow< ? > panel )
	{
		this.panel = panel;

		return this;
	}

	public class HistogramListener implements ActionListener
	{
		final SpimData2 spimData;
		final ArrayList< ViewId > views;
		final String label;
		final int i;

		public HistogramListener( final SpimData2 spimData, final ArrayList< ViewId > views, final String label, final int i )
		{
			this.spimData = spimData;
			this.views = views;
			this.label = label;
			this.i = i;
		}

		@Override
		public void actionPerformed( final ActionEvent e )
		{
			new Thread( new Runnable()
			{
				@Override
				public void run()
				{
					// suggest to thin out this one when calling the plugin
					ThinOut_Detections.defaultLabel = i;

					DistanceHistogram.plotHistogram( spimData, views, label, DistanceHistogram.getHistogramTitle( views ) );
				}
			} ).start();
		}
	}

	/**
	 * Fills "Re-detect interest points (stored parameters)" for the current selection: one item per plain label whose points
	 * lack an attribute in any of the selected views (the points are loaded); a label without a detection string (e.g. one
	 * made from correspondences) is listed gray, a filtered label is not listed (it has no points of its own and follows
	 * its base label, which the detection re-creates it from)
	 */
	protected void fillRedetect( final JMenu menu )
	{
		menu.removeAll();

		if ( panel == null )
			return;

		final SpimData2 spimData = (SpimData2)panel.getSpimData();

		final ArrayList< ViewId > views = new ArrayList<>();
		views.addAll( ApplyTransformationPopup.getSelectedViews( panel ) );

		// filter not present ViewIds
		SpimData2.filterMissingViews( spimData, views );

		final String[] labels = InterestPointTools.getAllInterestPointLabels( spimData, views );
		int listed = 0;

		for ( int i = 0; i < labels.length; ++i )
		{
			final String label = InterestPointTools.getSelectedLabel( labels, i );
			final String warning = labels[ i ].substring( label.length() );
			final List< ViewId > withLabel = InterestPointTools.viewsWithLabel( spimData, label, views );

			if ( withLabel.isEmpty() )
				continue;

			final InterestPoints first = spimData.getViewInterestPoints().getViewInterestPointLists( withLabel.get( 0 ) ).getInterestPointList( label );

			if ( first instanceof VirtualFilteredInterestPoints )
				continue;

			final EnumSet< Axis > missing;

			try
			{
				missing = InterestPointTools.missingAttributes( spimData, withLabel, label, EnumSet.allOf( Axis.class ) );
			}
			catch ( final RuntimeException ex )
			{
				IOFunctions.println( "Cannot read the interest points '" + label + "': " + ex.getMessage() );
				continue;
			}

			if ( missing.isEmpty() )
				continue;

			final String parameters = first.getParameters();
			final String what = " (no " + missing.stream().map( a -> a.key ).collect( Collectors.joining( ", " ) ) + ")";
			final JMenuItem item;

			if ( StoredDetectionParameters.parse( parameters ) == null )
			{
				item = new JMenuItem( label + what + " - no stored detection parameters" + warning );
				item.setForeground( Color.GRAY );
				item.setEnabled( false );
			}
			else
			{
				for ( final ViewId v : withLabel )
					if ( !parameters.equals( spimData.getViewInterestPoints().getViewInterestPointLists( v ).getInterestPointList( label ).getParameters() ) )
					{
						IOFunctions.println( "The parameters of '" + label + "' differ between the selected views, 'Re-detect' uses the ones of view " + Group.pvid( withLabel.get( 0 ) ) + ": " + parameters );
						break;
					}

				item = new JMenuItem( label + what + warning );
				item.addActionListener( new RedetectListener( label, parameters ) );
			}

			menu.add( item );
			++listed;
		}

		if ( listed == 0 )
		{
			final JMenuItem item = new JMenuItem( "No labels without attributes" );
			item.setForeground( Color.GRAY );
			item.setEnabled( false );
			menu.add( item );
		}
	}

	/**
	 * Opens "Detect Interest Points" for the selected views with the label and its stored parameters preset
	 * (StoredDetectionParameters.preset); the dialog then runs as from "Detect Interest Points ..." and replaces the label
	 */
	public class RedetectListener implements ActionListener
	{
		final String label, parameters;

		public RedetectListener( final String label, final String parameters )
		{
			this.label = label;
			this.parameters = parameters;
		}

		@Override
		public void actionPerformed( final ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set for " + this.getClass().getSimpleName() );
				return;
			}

			final SpimData2 spimData = (SpimData2)panel.getSpimData();

			final ArrayList< ViewId > views = new ArrayList<>();
			views.addAll( ApplyTransformationPopup.getSelectedViews( panel ) );

			// filter not present ViewIds
			SpimData2.filterMissingViews( spimData, views );

			final StoredDetectionParameters stored = StoredDetectionParameters.parse( parameters );

			if ( stored == null )
			{
				IOFunctions.println( "'" + label + "' has no stored detection parameters: " + parameters );
				return;
			}

			IOFunctions.println( "Re-detecting '" + label + "' with its stored parameters: " + parameters );
			stored.preset( spimData, views, label );

			// the stored grouping wins over the explorer's; a string without it (older labels) gets the explorer's as always
			DetectInterestPointsPopup.detect( panel, !stored.hasGrouping() );
		}
	}

	/**
	 * Fills "Resave as ZARR store" for the current selection: one item per label with old (N5) entries and their number
	 * of views, "ALL labels" if there is more than one; the submenu is disabled (gray) when there are none
	 */
	protected void fillResaveZarr()
	{
		resaveZarr.removeAll();

		if ( panel == null )
		{
			resaveZarr.setEnabled( false );
			return;
		}

		final SpimData2 spimData = (SpimData2)panel.getSpimData();

		final ArrayList< ViewId > views = new ArrayList<>();
		views.addAll( ApplyTransformationPopup.getSelectedViews( panel ) );

		// filter not present ViewIds
		SpimData2.filterMissingViews( spimData, views );

		final Map< String, Integer > labels;

		try
		{
			labels = InterestPointsN5ToZarr.legacyLabels( spimData, views );
		}
		catch ( final RuntimeException ex )
		{
			IOFunctions.println( "Cannot look for old interest points: " + ex );
			ex.printStackTrace();
			resaveZarr.setEnabled( false );
			return;
		}

		if ( labels.isEmpty() )
		{
			final JMenuItem item = new JMenuItem( "No old interest points (all in the ZARR store)" );
			item.setForeground( Color.GRAY );
			item.setEnabled( false );
			resaveZarr.add( item );
			resaveZarr.setEnabled( false );
			return;
		}

		resaveZarr.setEnabled( true );

		for ( final Map.Entry< String, Integer > label : labels.entrySet() )
		{
			final JMenuItem item = new JMenuItem( label.getKey() + " (" + label.getValue() + " view" + ( label.getValue() == 1 ? "" : "s" ) + ")" );
			item.addActionListener( new ResaveZarrListener( label.getKey() ) );
			resaveZarr.add( item );
		}

		if ( labels.size() > 1 )
		{
			resaveZarr.addSeparator();

			final JMenuItem all = new JMenuItem( "ALL labels" );
			all.addActionListener( new ResaveZarrListener( null ) );
			resaveZarr.add( all );
		}
	}

	/**
	 * Moves the old (N5) interest points of one label (or of all labels, null) of the selected views into the ZARR store,
	 * multi-threaded in a thread of its own; nothing to save afterwards (the XML text of a label is the same for both)
	 */
	public class ResaveZarrListener implements ActionListener
	{
		final String label;

		public ResaveZarrListener( final String label ) { this.label = label; }

		@Override
		public void actionPerformed( final ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set for " + this.getClass().getSimpleName() );
				return;
			}

			final SpimData2 spimData = (SpimData2)panel.getSpimData();

			final ArrayList< ViewId > views = new ArrayList<>();
			views.addAll( ApplyTransformationPopup.getSelectedViews( panel ) );

			// filter not present ViewIds
			SpimData2.filterMissingViews( spimData, views );

			new Thread( () ->
			{
				try
				{
					final List< InterestPointsZarr > lists = InterestPointsN5ToZarr.legacyLists( spimData, views, label );

					IOFunctions.println( "Resave as ZARR store: " + lists.size() + " old interest point entries of " + ( label == null ? "all labels" : "'" + label + "'" ) + " in " + views.size() + " selected view(s)" );

					final int moved = InterestPointsN5ToZarr.moveIntoStore( lists, InterestPointsZarrStore.get( spimData.getBasePathURI() ), Threads.numThreads() );

					if ( moved > 0 )
						panel.updateContent(); // update interestpoint panel if available
				}
				catch ( final Exception ex )
				{
					IOFunctions.println( "Resave as ZARR store failed: " + ex );
					ex.printStackTrace();
				}
			} ).start();
		}
	}

	public class ThinOutListener implements ActionListener
	{
		@Override
		public void actionPerformed( final ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set for " + this.getClass().getSimpleName() );
				return;
			}

			final SpimData2 spimData = (SpimData2)panel.getSpimData();

			final ArrayList< ViewId > views = new ArrayList<>();
			views.addAll( ApplyTransformationPopup.getSelectedViews( panel ) );

			// filter not present ViewIds
			SpimData2.filterMissingViews( spimData, views );

			new Thread( new Runnable()
			{
				@Override
				public void run()
				{
					if ( ThinOut_Detections.thinOut( spimData, views ) )
						panel.updateContent(); // update interestpoint panel if available
				}
			} ).start();

		}
	}

	public class RelativeThinOutListener implements ActionListener
	{
		@Override
		public void actionPerformed( final ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set for " + this.getClass().getSimpleName() );
				return;
			}

			final SpimData2 spimData = (SpimData2)panel.getSpimData();

			final ArrayList< ViewId > views = new ArrayList<>();
			views.addAll( ApplyTransformationPopup.getSelectedViews( panel ) );

			// filter not present ViewIds
			SpimData2.filterMissingViews( spimData, views );

			new Thread( new Runnable()
			{
				@Override
				public void run()
				{
					if ( RelativeThinOut_Detections.thinOut( spimData, views ) )
						panel.updateContent(); // update interestpoint panel if available
				}
			} ).start();

		}
	}

	public class RelativeDistanceHistogramListener implements ActionListener
	{
		@Override
		public void actionPerformed( final ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set for " + this.getClass().getSimpleName() );
				return;
			}

			final SpimData2 spimData = (SpimData2)panel.getSpimData();

			final ArrayList< ViewId > views = new ArrayList<>();
			views.addAll( ApplyTransformationPopup.getSelectedViews( panel ) );

			// filter not present ViewIds
			SpimData2.filterMissingViews( spimData, views );

			new Thread( new Runnable()
			{
				@Override
				public void run()
				{
					if ( Show_Relative_Histogram.plotHistogram( spimData, views ) )
						panel.updateContent(); // update interestpoint panel if available
				}
			} ).start();

		}
	}

	public class CreateFromCorrespondencesListener implements ActionListener
	{
		@Override
		public void actionPerformed( final ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set for " + this.getClass().getSimpleName() );
				return;
			}

			final SpimData2 spimData = (SpimData2)panel.getSpimData();

			final ArrayList< ViewId > views = new ArrayList<>();
			views.addAll( ApplyTransformationPopup.getSelectedViews( panel ) );

			// filter not present ViewIds
			SpimData2.filterMissingViews( spimData, views );

			new Thread( new Runnable()
			{
				@Override
				public void run()
				{
					if ( CreateFromCorresponding_Detections.create( spimData, views ) )
						panel.updateContent(); // update interestpoint panel if available
				}
			} ).start();

		}
	}

	public class FilterListener implements ActionListener
	{
		@Override
		public void actionPerformed( final ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set for " + this.getClass().getSimpleName() );
				return;
			}

			final SpimData2 spimData = (SpimData2)panel.getSpimData();

			final ArrayList< ViewId > views = new ArrayList<>();
			views.addAll( ApplyTransformationPopup.getSelectedViews( panel ) );

			// filter not present ViewIds
			SpimData2.filterMissingViews( spimData, views );

			new Thread( new Runnable()
			{
				@Override
				public void run()
				{
					if ( Filter_Detections.filter( spimData, views ) )
						panel.updateContent(); // update interestpoint panel if available
				}
			} ).start();
		}
	}

	/**
	 * "Filter interest points (keep fixed number) ...": a filtered label that keeps the n highest / around the median / lowest points by an attribute of every view (Limit_Detections)
	 */
	public class LimitListener implements ActionListener
	{
		@Override
		public void actionPerformed( final ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set for " + this.getClass().getSimpleName() );
				return;
			}

			final SpimData2 spimData = (SpimData2)panel.getSpimData();

			final ArrayList< ViewId > views = new ArrayList<>();
			views.addAll( ApplyTransformationPopup.getSelectedViews( panel ) );

			// filter not present ViewIds
			SpimData2.filterMissingViews( spimData, views );

			new Thread( new Runnable()
			{
				@Override
				public void run()
				{
					if ( Limit_Detections.limit( spimData, views ) )
						panel.updateContent(); // update interestpoint panel if available
				}
			} ).start();
		}
	}

	/**
	 * An entry of "Filter interest points (threshold ramp)": the range dialog for the label prefilled with thresholds relative to its detection threshold (Filter_Detections.filterRamp)
	 */
	public class RampListener implements ActionListener
	{
		final String label;

		public RampListener( final String label )
		{
			this.label = label;
		}

		@Override
		public void actionPerformed( final ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set for " + this.getClass().getSimpleName() );
				return;
			}

			final SpimData2 spimData = (SpimData2)panel.getSpimData();

			final ArrayList< ViewId > views = new ArrayList<>();
			views.addAll( ApplyTransformationPopup.getSelectedViews( panel ) );

			// filter not present ViewIds
			SpimData2.filterMissingViews( spimData, views );

			new Thread( new Runnable()
			{
				@Override
				public void run()
				{
					if ( Filter_Detections.filterRamp( spimData, views, label ) )
						panel.updateContent(); // update interestpoint panel if available
				}
			} ).start();
		}
	}

	/**
	 * An entry of "Filter interest points (interactively)": opens the Interest Point Explorer if it is not open, selects the
	 * label there (which shows all of its points in BigDataViewer) and opens its interactive filter for the selected views
	 * (InterestPointExplorerPanel.filterInteractively); on the event thread like the explorer's own menu entry
	 */
	public class InteractiveFilterListener implements ActionListener
	{
		final String label;

		public InteractiveFilterListener( final String label )
		{
			this.label = label;
		}

		@Override
		public void actionPerformed( final ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set for " + this.getClass().getSimpleName() );
				return;
			}

			if ( !( panel instanceof FilteredAndGroupedExplorerPanel ) )
			{
				IOFunctions.println( "Only supported from the ViewSetup Explorer: " + this.getClass().getSimpleName() );
				return;
			}

			final InterestPointExplorer< ? > ipe = openExplorer( (FilteredAndGroupedExplorerPanel< ? >)panel );

			ipe.frame().toFront();
			ipe.panel().filterInteractively( label );
		}
	}

	private static InterestPointExplorer< ? > openExplorer( final FilteredAndGroupedExplorerPanel< ? > panel )
	{
		return InterestPointExplorer.open( panel.xml(), panel.io(), panel.explorer() );
	}

	public class InteractiveListener implements ActionListener
	{
		final int index;

		public InteractiveListener( final int index )
		{
			this.index = index;
		}

		@Override
		public void actionPerformed( final ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set for " + this.getClass().getSimpleName() );
				return;
			}

			final SpimData2 spimData = (SpimData2)panel.getSpimData();

			final ArrayList< ViewId > views = new ArrayList<>();
			views.addAll( ApplyTransformationPopup.getSelectedViews( panel ) );

			// filter not present ViewIds
			SpimData2.filterMissingViews( panel.getSpimData(), views );

			if ( views.size() != 1 )
			{
				JOptionPane.showMessageDialog( null, "Interactive Removal of Detections only supports a single view at a time." );
				return;
			}

			final Pair< String, String > labels = queryLabelAndNewLabel( (SpimData2)panel.getSpimData(), views.get( 0 ) );

			if ( labels == null )
				return;

			new Thread( new Runnable()
			{
				@Override
				public void run()
				{
					final InteractiveProjections ip = InteractiveProjections.removeInteractively( spimData, views.get( 0 ), index - 1, labels.getA(), labels.getB() );

					do
					{
						try { Thread.sleep( 100 ); } catch ( InterruptedException e ) {}
					}
					while ( ip.isRunning() );

					panel.updateContent(); // update interestpoint panel if available
				}
			} ).start();
		}
	}

	private static Pair< String, String > queryLabelAndNewLabel( final SpimData2 spimData, final ViewId vd )
	{
		final ViewInterestPoints interestPoints = spimData.getViewInterestPoints();
		final ViewInterestPointLists lists = interestPoints.getViewInterestPointLists( vd );

		if ( lists.getHashMap().keySet().size() == 0 )
		{
			IOFunctions.println( "No interest points available for view: " + Group.pvid( vd ) );
			return null;
		}

		final String[] labels = new String[ lists.getHashMap().keySet().size() ];

		int i = 0;
		for ( final String label : lists.getHashMap().keySet() )
			labels[ i++ ] = label;

		if ( defaultLabel >= labels.length )
			defaultLabel = 0;

		Arrays.sort( labels );

		final GenericDialog gd = new GenericDialog( "Select Interest Points To Remove" );

		gd.addChoice( "Interest_Point_Label", labels, labels[ defaultLabel ]);
		gd.addStringField( "New_Label", defaultNewLabel, 20 );

		gd.showDialog();
		if ( gd.wasCanceled() )
			return null;

		final String label = labels[ defaultLabel = gd.getNextChoiceIndex() ];
		final String newLabel = gd.getNextString();

		return new ValuePair< String, String >( label, newLabel );
	}
}
