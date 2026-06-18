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
package net.preibisch.mvrecon.fiji.plugin.util;

import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

import javax.swing.JMenuItem;

public class MouseOverPopUpStateChanger implements ActionListener, MouseListener
{
	public static interface StateChanger
	{
		public void setSelectedState( final int state );
	}

	final JMenuItem[] items;
	final int myState;
	boolean hasMouseFocusWaiting = false;
	final StateChanger changer;

	public MouseOverPopUpStateChanger( final JMenuItem[] items, final int myState, final StateChanger changer )
	{
		this.items = items;
		this.myState = myState;
		this.changer = changer;
	}

	@Override
	public void actionPerformed( final ActionEvent e )
	{
		for ( int i = 0; i < items.length; ++i )
		{
			if ( i == myState )
			{
				items[ i ].setForeground( Color.RED );
				items[ i ].setFocusable( false );
			}
			else
				items[ i ].setForeground( Color.GRAY );
		}

		changer.setSelectedState( myState );

		this.hasMouseFocusWaiting = false;
	}

	@Override
	public void mouseEntered( MouseEvent e )
	{
		hasMouseFocusWaiting = true;

		new Thread( () ->
		{
			int countNull = 0;

			for ( int i = 0; i <= 7; ++i )
			{
				if ( items[ myState ].getMousePosition() == null )
					if ( i == 7 || ++countNull >= 2 )
						hasMouseFocusWaiting = false;

				if ( !hasMouseFocusWaiting )
					break;

				try { Thread.sleep( 100 ); } catch ( InterruptedException e1 ){}
			}

			if ( hasMouseFocusWaiting )
				this.actionPerformed( null );
		}).start();
	}

	@Override
	public void mouseExited( MouseEvent e )
	{
		hasMouseFocusWaiting = false;
	}

	@Override
	public void mouseClicked( MouseEvent e ) {}

	@Override
	public void mousePressed( MouseEvent e ) {}

	@Override
	public void mouseReleased( MouseEvent e ) {}
}
