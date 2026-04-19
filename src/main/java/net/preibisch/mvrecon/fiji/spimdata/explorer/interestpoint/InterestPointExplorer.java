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
package net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JFrame;

import bdv.BigDataViewer;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.ImgLib2Temp.Pair;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorer;
import net.preibisch.mvrecon.fiji.spimdata.explorer.SelectedViewDescriptionListener;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BasicBDVPopup;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;

public class InterestPointExplorer< AS extends SpimData2 >
	implements SelectedViewDescriptionListener< AS >
{
	final URI xml;
	final JFrame frame;
	final InterestPointExplorerPanel panel;
	final FilteredAndGroupedExplorer< AS > viewSetupExplorer;

	public InterestPointExplorer( final URI xml, final XmlIoSpimData2 io, final FilteredAndGroupedExplorer< AS > viewSetupExplorer )
	{
		this.xml = xml;
		this.viewSetupExplorer = viewSetupExplorer;

		frame = new JFrame( "Interest Point Explorer (Press F1 for help)" );
		panel = new InterestPointExplorerPanel( viewSetupExplorer.getPanel().getSpimData().getViewInterestPoints(), viewSetupExplorer );
		frame.add( panel, BorderLayout.CENTER );

		frame.setSize( panel.getPreferredSize() );

		frame.pack();
		frame.setVisible( true );
		
		// Get the size of the screen
		final Dimension dim = Toolkit.getDefaultToolkit().getScreenSize();

		// Move the window
		frame.setLocation( ( dim.width - frame.getSize().width ) / 2, ( dim.height - frame.getSize().height ) / 4 );

		// this call also triggers the first update of the registration table
		viewSetupExplorer.addListener( this );

		frame.addWindowListener( new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent e)
			{
				quit();
				e.getWindow().dispose();
			}
		});
	}

	public JFrame frame() { return frame; }

	@Override
	public void selectedViewDescriptions( final List< List< BasicViewDescription< ? > > > viewDescriptions )
	{
		final ArrayList< BasicViewDescription< ? > > fullList = new ArrayList<>();

		for ( final List< BasicViewDescription< ? > > list : viewDescriptions )
			for ( final BasicViewDescription< ? > vd : list )
				if ( vd.isPresent() )
					fullList.add( vd );

		panel.updateViewDescription( fullList );
	}

	@Override
	public void save()
	{
		for ( final Pair< InterestPoints, ViewId > list : panel.delete )
		{
			IOFunctions.println( "Deleting correspondences and interestpoints in timepointid=" + list.getB().getTimePointId() + ", viewid=" + list.getB().getViewSetupId() );

			if ( list.getA().deleteInterestPoints() )
				IOFunctions.println( "Deleted.");
			else
				IOFunctions.println( "FAILED to delete." );

			if ( list.getA().deleteCorrespondingInterestPoints() )
				IOFunctions.println( "Deleted.");
			else
				IOFunctions.println( "FAILED to delete." );
		}

		//panel.save.clear();
		panel.delete.clear();
	}

	@Override
	public void quit()
	{
		final BasicBDVPopup bdvPopup = viewSetupExplorer.getPanel().bdvPopup();

		if ( bdvPopup.bdvRunning() && panel.tableModel.interestPointOverlay != null )
		{
			final BigDataViewer bdv = bdvPopup.getBDV();
			bdv.getViewer().removeTransformListener( panel.tableModel.interestPointOverlay );
			bdv.getViewer().getDisplay().removeOverlayRenderer( panel.tableModel.interestPointOverlay );
			bdvPopup.updateBDV();
		}

		frame.setVisible( false );
		frame.dispose();
	}

	public InterestPointExplorerPanel panel() { return panel; }

	@Override
	public void updateContent( final AS data )
	{
		panel.getTableModel().update( data.getViewInterestPoints() );
		panel.getTableModel().fireTableDataChanged();
	}
}
