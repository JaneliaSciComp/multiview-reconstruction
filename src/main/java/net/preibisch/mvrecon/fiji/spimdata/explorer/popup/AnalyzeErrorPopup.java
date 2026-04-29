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
import java.util.Arrays;
import java.util.List;

import javax.swing.JMenuItem;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.explorer.analyzeerror.AnalyzeErrorsResultsWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.analyzeerror.AnalyzeErrorsUtil;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ViewSetupExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint.InterestPointExplorer;

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

					final AnalyzeErrorsUtil.Parameters params =
							AnalyzeErrorsUtil.getParametersExtended( panel.getSpimData(), viewIds );

					if ( params == null )
						return;

					final ArrayList<Pair<Pair<ViewId, ViewId>, Double>> errors =
							AnalyzeErrorsUtil.getErrors( panel.getSpimData(), viewIds, params.labelAndWeights );

					if ( errors.size() > 0 )
					{
						AnalyzeErrorsUtil.printResults( panel.getSpimData(), errors, params );

						// open the sortable results browser; row clicks recenter BDV + select views
						javax.swing.SwingUtilities.invokeLater( () ->
								new AnalyzeErrorsResultsWindow(
										panel.getSpimData(), errors, params, panel ) );

						// auto-select the worst pair as the initial selection
						final Pair< Pair< ViewId, ViewId >, Double > worst = errors.get( 0 );
						AnalyzeErrorsUtil.selectViewsAndRecenter( panel, params,
								Arrays.asList( worst.getA().getA(), worst.getA().getB() ) );

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
