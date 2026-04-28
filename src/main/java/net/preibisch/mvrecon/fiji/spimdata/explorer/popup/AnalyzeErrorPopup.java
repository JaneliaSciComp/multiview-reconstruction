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
import java.util.Set;

import javax.swing.JMenuItem;

import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.util.Pair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.AnalyzeErrorsResultsWindow;
import net.preibisch.mvrecon.fiji.plugin.Analyze_Errors;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ViewSetupExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint.InterestPointExplorer;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class AnalyzeErrorPopup extends JMenuItem implements ExplorerWindowSetable
{
	private static final long serialVersionUID = 7784076140119163902L;

	ViewSetupExplorerPanel< ? > panel;
	InterestPointExplorer< ? > ipe = null;

	public AnalyzeErrorPopup()
	{
		super( "Analyze Alignment Errors ..." );

		this.addActionListener( new MyActionListener() );
	}

	@Override
	public JMenuItem setExplorerWindow( final ExplorerWindow< ? > panel )
	{
		this.panel = (ViewSetupExplorerPanel< ? >)panel;
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

			new Thread( new Runnable()
			{
				@Override
				public void run()
				{
					final List< ViewId > viewIds =
							ApplyTransformationPopup.getSelectedViews( panel );

					// pre-set the grouping defaults from the explorer panel's current state
					// (matches the RegisterInterestPointsPopup convention so the dialog opens
					// pre-checked according to what the user has grouped in the table).
					net.preibisch.mvrecon.fiji.plugin.Interest_Point_Registration.defaultGroupTiles    = panel.tilesGrouped();
					net.preibisch.mvrecon.fiji.plugin.Interest_Point_Registration.defaultGroupIllums   = panel.illumsGrouped();
					net.preibisch.mvrecon.fiji.plugin.Interest_Point_Registration.defaultGroupChannels = panel.channelsGrouped();

					final Analyze_Errors.Parameters params =
							Analyze_Errors.getParametersExtended( panel.getSpimData(), viewIds );

					if ( params == null )
						return;

					final ArrayList<Pair<Pair<ViewId, ViewId>, Double>> errors =
							Analyze_Errors.getErrors( panel.getSpimData(), viewIds, params.labelAndWeights );

					if ( errors.size() > 0 )
					{
						Analyze_Errors.printResults( panel.getSpimData(), errors, params );

						// open the sortable results browser; click on a row recenters BDV
						javax.swing.SwingUtilities.invokeLater( () ->
								new AnalyzeErrorsResultsWindow(
										panel.getSpimData(), errors, params,
										(ViewSetupExplorerPanel< ? >) panel ) );

						// disable coloring
						final BDVPopup p = panel.bdvPopup();
						if ( p != null && p.bdv != null && p.bdv.getViewerFrame().isVisible() )
							panel.updateBDV( p.bdv, false, panel.getSpimData(), null, panel.selectedRows );

						// remember prior grouping state so we can restore it after the worst-pair
						// row-selection (the row-parsing code below requires ungrouped rows)
						final ViewSetupExplorerPanel< ? > vsep = (ViewSetupExplorerPanel< ? >)panel;
						final boolean prevGroupTiles  = vsep.groupTilesCheckbox != null && vsep.groupTilesCheckbox.isSelected();
						final boolean prevGroupIllums = vsep.groupIllumsCheckbox != null && vsep.groupIllumsCheckbox.isSelected();
						final Set< Class< ? extends Entity > > prevFactors =
								new HashSet<>( panel.getTableModel().getGroupingFactors() );

						// ungroup everything
						vsep.groupTilesCheckbox.setSelected( false );
						vsep.groupIllumsCheckbox.setSelected( false );

						panel.getTableModel().clearGroupingFactors();
						panel.updateContent();

						// wait until the table is updated (otherwise there might be an exception thrown)
						SimpleMultiThreading.threadWait( 100 );

						final Pair<Pair<ViewId, ViewId>, Double> worstError = errors.get( 0 );

						// select the two rows
						boolean setFirst = false;
						for ( int r = 0; r < panel.table.getRowCount(); ++r )
						{
							//System.out.println( panel.table.getValueAt( r, 0 ) + ", " + Integer.parseInt( (String)panel.table.getValueAt( r, 0 ) ) );
							//System.out.println( panel.table.getValueAt( r, 1 ) + ", " + Integer.parseInt( (String)panel.table.getValueAt( r, 1 ) ) );
							//System.out.println();

							final int tp = Integer.parseInt( (String)panel.table.getValueAt( r, 0 ) );
							final int vs = Integer.parseInt( (String)panel.table.getValueAt( r, 1 ) );
							//System.out.println( tp + ", " + vs );

							if ( tp == worstError.getA().getA().getTimePointId() && vs == worstError.getA().getA().getViewSetupId() ||
								 tp == worstError.getA().getB().getTimePointId() && vs == worstError.getA().getB().getViewSetupId())
							{
								System.out.println( "setting" );
								if ( setFirst )
									panel.table.addRowSelectionInterval(r, r);
								else
								{
									setFirst = true;
									panel.table.setRowSelectionInterval(r, r);
								}
							}
						}

						// wait until the table is updated (otherwise there might be an exception thrown)
						SimpleMultiThreading.threadWait( 100 );

						// zoom into the two
						if ( p != null && p.bdv != null && p.bdv.getViewerFrame().isVisible() )
						{
							TransformationTools.reCenterViews( p.bdv,
									panel.selectedRows.stream().collect(
											HashSet< BasicViewDescription< ? > >::new,
											( a, b ) -> a.addAll( b ), ( a, b ) -> a.addAll( b ) ),
									panel.getSpimData().getViewRegistrations() );
						}

						// restore the explorer's grouping state to what it was before the popup ran
						vsep.groupTilesCheckbox.setSelected( prevGroupTiles );
						vsep.groupIllumsCheckbox.setSelected( prevGroupIllums );
						panel.getTableModel().clearGroupingFactors();
						for ( final Class< ? extends Entity > f : prevFactors )
							panel.getTableModel().addGroupingFactor( f );
						panel.updateContent();

						// TODO: activate overlay that shows the outlines of both stacks
					}
					else
					{
						IOFunctions.println( "No corresponding interest points found." );
					}
				}
			}).start();
		}
	}
}
