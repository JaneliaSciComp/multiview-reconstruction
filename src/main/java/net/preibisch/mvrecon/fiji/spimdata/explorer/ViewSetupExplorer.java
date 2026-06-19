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
package net.preibisch.mvrecon.fiji.spimdata.explorer;

import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;

import javax.swing.JFrame;

import net.preibisch.mvrecon.fiji.plugin.util.MultiWindowLayoutHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BasicBDVPopup;

public class ViewSetupExplorer< AS extends SpimData2 > extends FilteredAndGroupedExplorer< AS >
{
	public static final double xPos = 0.4;
	public static final double yPos = 0.4;
	public static final double xPosLog = 0.0;
	public static final double yPosLog = 0.8;

	public ViewSetupExplorer( final AS data, final URI xml, final XmlIoSpimData2 io )
	{
		this( data, xml, io, true );
	}

	public ViewSetupExplorer( final AS data, final URI xml, final XmlIoSpimData2 io, final boolean openBDV )
	{
		frame = new JFrame( "ViewSetup Explorer (Press F1 for help)" );

		long start = System.currentTimeMillis();
		panel = new ViewSetupExplorerPanel< AS >( this, data, xml, io, openBDV );
		// net.preibisch.legacy.io.IOFunctions.println( "PERF: [ViewSetupExplorer] ViewSetupExplorerPanel creation took " + (System.currentTimeMillis() - start) + " ms" );

		start = System.currentTimeMillis();
		frame.add( panel, BorderLayout.CENTER );
		frame.setSize( panel.getPreferredSize() );

		frame.addWindowListener(
				new WindowAdapter()
				{
					@Override
					public void windowClosing( WindowEvent evt )
					{
						quit();
					}
				});
		// net.preibisch.legacy.io.IOFunctions.println( "PERF: [ViewSetupExplorer] frame setup took " + (System.currentTimeMillis() - start) + " ms" );

		start = System.currentTimeMillis();
		frame.pack();
		// net.preibisch.legacy.io.IOFunctions.println( "PERF: [ViewSetupExplorer] frame.pack() took " + (System.currentTimeMillis() - start) + " ms" );

		start = System.currentTimeMillis();
		frame.setVisible( true );
		// net.preibisch.legacy.io.IOFunctions.println( "PERF: [ViewSetupExplorer] frame.setVisible() took " + (System.currentTimeMillis() - start) + " ms" );

		// move explorer window and log to initial positions
		MultiWindowLayoutHelper.moveToScreenFraction( frame, xPos, yPos );
		MultiWindowLayoutHelper.moveToScreenFraction( MultiWindowLayoutHelper.getIJLogWindow(), xPosLog, yPosLog );

		// set the initial focus to the table
		panel.table.requestFocus();
	}
	
	public void quit()
	{
		for ( final SelectedViewDescriptionListener< AS > l : panel.getListeners() )
			l.quit();

		panel.getListeners().clear();

		frame.setVisible( false );
		frame.dispose();

		final BasicBDVPopup bdvPopup = panel.runningBdvPopup();

		if ( bdvPopup != null && bdvPopup.bdvRunning() )
			bdvPopup.closeBDV();

		ViewSetupExplorerPanel.currentInstance = null;
	}
}
