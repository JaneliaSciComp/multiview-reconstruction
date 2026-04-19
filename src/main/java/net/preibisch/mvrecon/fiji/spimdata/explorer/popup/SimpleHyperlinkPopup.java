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

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URI;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;

import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;

public class SimpleHyperlinkPopup extends JMenuItem implements ExplorerWindowSetable
{

	private static final long serialVersionUID = 1L;

	public SimpleHyperlinkPopup(String title, URI uri)
	{
		super(title);
		this.addActionListener( ev -> {
			if (Desktop.isDesktopSupported())
				if (Desktop.getDesktop().isSupported( Desktop.Action.BROWSE ))
					try { Desktop.getDesktop().browse( uri ); } catch ( IOException e ) { e.printStackTrace(); }
		});
	}

	@Override
	public JComponent setExplorerWindow( ExplorerWindow< ? > panel )
	{
		return this;
	}

	public static JLabel createHyperlinkLabel(String text, URI uri)
	{
		final JLabel linkLabel = new JLabel( text );
		if (Desktop.isDesktopSupported())
			if (Desktop.getDesktop().isSupported( Desktop.Action.BROWSE ))
			{
				linkLabel.addMouseListener( new MouseAdapter()
				{
					@Override
					public void mouseClicked( final MouseEvent e )
					{
						try { Desktop.getDesktop().browse( uri ); } catch ( IOException ex ) { ex.printStackTrace(); }
					}

					@Override
					public void mouseEntered( final MouseEvent e )
					{
						linkLabel.setForeground( Color.BLUE );
						linkLabel.setCursor( new Cursor( Cursor.HAND_CURSOR ) );
					}

					@Override
					public void mouseExited( final MouseEvent e )
					{
						linkLabel.setForeground( Color.BLACK );
						linkLabel.setCursor( new Cursor( Cursor.DEFAULT_CURSOR ) );
					}
				} );
			}
		return linkLabel;
	}

}
