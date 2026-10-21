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

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.ImgLib2Temp.Pair;
import net.preibisch.mvrecon.fiji.ImgLib2Temp.ValuePair;
import net.preibisch.mvrecon.fiji.plugin.CreateFromCorresponding_Detections;
import net.preibisch.mvrecon.fiji.plugin.Filter_Detections;
import net.preibisch.mvrecon.fiji.plugin.Limit_Detections;
import net.preibisch.mvrecon.fiji.plugin.RelativeThinOut_Detections;
import net.preibisch.mvrecon.fiji.plugin.Show_Relative_Histogram;
import net.preibisch.mvrecon.fiji.plugin.ThinOut_Detections;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint.InterestPointExplorer;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter.Axis;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
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

	public RemoveDetectionsPopup()
	{
		super( "Filter/Manage Interest Points" );

		final JMenuItem createFiltered = new JMenuItem( "Filter interest points (response/sigma/intensity) ..." );
		final JMenuItem createLimited = new JMenuItem( "Filter interest points (keep fixed number) ..." );
		final JMenu createRamp = new JMenu( "Filter interest points (threshold ramp)" );
		final JMenu filterInteractively = new JMenu( "Filter interest points (interactively)" );
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
