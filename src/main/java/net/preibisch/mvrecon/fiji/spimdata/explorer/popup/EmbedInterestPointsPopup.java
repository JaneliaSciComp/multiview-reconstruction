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

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;

import javax.swing.JMenu;
import javax.swing.JMenuItem;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.Embed_Interest_Points;
import net.preibisch.mvrecon.fiji.plugin.Embed_Interest_Points.Source;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;

/**
 * "Compute Interest Point Embeddings" of the ViewSetup Explorer, one entry per {@link Source} ("Translation invariant (Hugging
 * Face) ...", "Local model ..."): {@link Embed_Interest_Points} on the selected views, then the Interest Point Explorer's
 * "Attributes" column shows the new array.
 *
 * @author Stephan Preibisch
 */
public class EmbedInterestPointsPopup extends JMenu implements ExplorerWindowSetable
{
	private static final long serialVersionUID = 1L;

	ExplorerWindow< ? > panel = null;

	public EmbedInterestPointsPopup()
	{
		super( "Compute Interest Point Embeddings" );

		for ( final Source source : Source.values() )
		{
			final JMenuItem item = new JMenuItem( source.title + " ..." );
			item.addActionListener( new MyActionListener( source ) );
			this.add( item );
		}
	}

	@Override
	public JMenuItem setExplorerWindow( final ExplorerWindow< ? > panel )
	{
		this.panel = panel;
		return this;
	}

	public class MyActionListener implements ActionListener
	{
		final Source source;

		public MyActionListener( final Source source )
		{
			this.source = source;
		}

		@Override
		public void actionPerformed( final ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set for " + this.getClass().getSimpleName() );
				return;
			}

			final SpimData2 spimData = (SpimData2) panel.getSpimData();

			final ArrayList< ViewId > views = new ArrayList<>();
			views.addAll( ApplyTransformationPopup.getSelectedViews( panel ) );

			// filter not present ViewIds
			SpimData2.filterMissingViews( spimData, views );

			new Thread( () ->
			{
				if ( Embed_Interest_Points.embed( spimData, views, source ) )
					panel.updateContent(); // the Interest Point Explorer lists the array in "Attributes"
			} ).start();
		}
	}
}
