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
import java.util.List;

import javax.swing.JMenuItem;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.interactive.InteractiveAnisotropy;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;

/**
 * Opens the first selected view in BigDataViewer to estimate its anisotropy interactively (InteractiveAnisotropy)
 */
public class EstimateAnisotropyPopup extends JMenuItem implements ExplorerWindowSetable
{
	private static final long serialVersionUID = -3486223947920532113L;

	ExplorerWindow< ? > panel;

	public EstimateAnisotropyPopup()
	{
		super( "Estimate Anisotropy (BDV) ..." );

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

			if ( !SpimData2.class.isInstance( panel.getSpimData() ) )
			{
				IOFunctions.println( "Only supported for SpimData2 objects: " + this.getClass().getSimpleName() );
				return;
			}

			final List< ViewId > viewIds = ApplyTransformationPopup.getSelectedViews( panel );

			if ( viewIds.size() == 0 )
			{
				IOFunctions.println( "No view selected." );
				return;
			}

			if ( viewIds.size() > 1 )
				IOFunctions.println( "Several views selected, using the first one for the anisotropy estimation." );

			new Thread( () -> new InteractiveAnisotropy( (SpimData2)panel.getSpimData(), viewIds.get( 0 ) ) ).start();
		}
	}
}
