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

import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import bdv.BigDataViewer;
import bdv.tools.HelpDialog;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerState;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.base.NamedEntity;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.XMLSaveAs;
import net.preibisch.mvrecon.fiji.spimdata.GroupedViews;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.SpimDataTools;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.bdv.BDVFlyThrough;
import net.preibisch.mvrecon.fiji.spimdata.explorer.bdv.BDVUtils;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BDVPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BasicBDVPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ExplorerWindowSetable;
import net.preibisch.mvrecon.fiji.spimdata.explorer.selection.SelectionDialog;
import net.preibisch.mvrecon.fiji.spimdata.explorer.viewneighbours.ViewNeighboursWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.viewneighbours.ViewOverlapWindow;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import util.BDVTools;
import util.URITools;

public abstract class FilteredAndGroupedExplorerPanel< AS extends SpimData2 >
		extends JPanel implements ExplorerWindow< AS >, GroupedRowWindow
{
	public static FilteredAndGroupedExplorerPanel< ? > currentInstance = null;

	protected ArrayList< ExplorerWindowSetable > popups;

	// Selection history
	private static final int MAX_HISTORY_SIZE = 10;
	private static final List<List<BasicViewDescription<?>>> selectionHistory = new ArrayList<>();
	private static int historyIndex = -1;
	private static boolean navigatingHistory = false;

	static
	{
		IOFunctions.printIJLog = true;
	}



	private static final long serialVersionUID = -3767947754096099774L;

	public JTable table;
	protected ISpimDataTableModel< AS > tableModel;
	protected ArrayList< SelectedViewDescriptionListener< AS > > listeners;
	protected AS data;
	protected FilteredAndGroupedExplorer< AS > explorer;
	public URI xml;
	protected final XmlIoSpimData2 io;
	protected final boolean isMac;
	protected boolean colorMode = false;
	
	// ViewSetupId overlay
	protected volatile ViewSetupIdOverlay viewSetupIdOverlay = null;

	public JLabel xmlLabel;

	final public HashSet< List< BasicViewDescription< ? > > > selectedRows;
	protected BasicViewDescription< ? > firstSelectedVD;

	public FilteredAndGroupedExplorerPanel(final FilteredAndGroupedExplorer< AS > explorer, final AS data, final URI xml, final XmlIoSpimData2 io)
	{
		this.explorer = explorer;
		this.listeners = new ArrayList<>();
		this.data = data;

		// normalize the xml path
		this.xml = xml;// == null ? "" : xml.replace("\\\\", "////").replace( "\\", "/" ).replace( "//", "/" ).replace( "/./", "/" );
		// NB: a lot of path normalization problems (e.g. windows network locations not accessible) are also fixed by not normalizing
		// therefore, if we run into problems in the future, we could also use the line below:
		//this.xml = xml == null ? "" : xml;
		this.io = io;
		this.isMac = System.getProperty( "os.name" ).toLowerCase().contains( "mac" );
		this.selectedRows = new HashSet<>();
		this.firstSelectedVD = null;


		popups = initPopups();

		// for access to the current BDV
		currentInstance = this;
	}

	@Override
	public BDVPopup bdvPopup()
	{
		for ( final ExplorerWindowSetable s : popups )
			if ( s instanceof BDVPopup )
				return ( BDVPopup ) s;

		return null;
	}

	/**
	 * Returns the first registered popup that implements {@link BasicBDVPopup},
	 * regardless of whether a BDV is currently open. Use this when you need the
	 * popup instance itself (e.g. to set its {@code bdv} field) rather than just
	 * accessing a running BDV.
	 */
	public BasicBDVPopup getAnyBDVPopup()
	{
		for ( final ExplorerWindowSetable s : popups )
			if ( s instanceof BasicBDVPopup )
				return ( BasicBDVPopup ) s;
		return null;
	}

	/**
	 * Returns whichever BDV popup currently has a running BDV (eager
	 * {@link BDVPopup} or lazy variant — both implement {@link BasicBDVPopup}).
	 * Falls back to the eager {@link BDVPopup} (which may have a null bdv) so
	 * call sites that early-return on {@code bdv == null} keep their existing
	 * behaviour when no BDV is open.
	 */
	public BasicBDVPopup runningBdvPopup()
	{
		for ( final ExplorerWindowSetable s : popups )
			if ( s instanceof BasicBDVPopup && ( ( BasicBDVPopup ) s ).bdvRunning() )
				return ( BasicBDVPopup ) s;
		return bdvPopup();
	}

	@Override
	public boolean colorMode()
	{
		return colorMode;
	}

	@Override
	public BasicViewDescription< ? > firstSelectedVD()
	{
		return firstSelectedVD;
	}

	public ISpimDataTableModel< AS > getTableModel()
	{
		return tableModel;
	}

	@Override
	public AS getSpimData()
	{
		return data;
	}

	@Override
	public URI xml()
	{
		return xml;
	}

	public XmlIoSpimData2 io()
	{
		return io;
	}

	public FilteredAndGroupedExplorer< AS > explorer()
	{
		return explorer;
	}

	@SuppressWarnings( "unchecked" )
	public void setSpimData( final Object data )
	{
		this.data = ( AS ) data;
		this.getTableModel().updateElements();
	}

	@Override
	public void updateContent()
	{
		// this.getTableModel().fireTableDataChanged();
		for ( final SelectedViewDescriptionListener< AS > l : listeners )
			l.updateContent( this.data );
	}

	@Override
	public List< BasicViewDescription< ? > > selectedRows()
	{
		// TODO: this will break the grouping of selected Views -> change interface???
		final ArrayList< BasicViewDescription< ? > > list = new ArrayList<>();
		for ( List< BasicViewDescription< ? > > vds : selectedRows )
			list.addAll( vds );
		Collections.sort( list );
		return list;
	}

	@Override
	public List< ViewId > selectedRowsViewId()
	{
		// TODO: adding Grouped Views here, not all selected ViewIds individually
		final ArrayList< ViewId > list = new ArrayList<>();
		for ( List< BasicViewDescription< ? > > vds : selectedRows )
			list.add( new GroupedViews( new ArrayList<>( vds ) ) );
		Collections.sort( list );
		return list;
	}

	public void addListener(final SelectedViewDescriptionListener< AS > listener)
	{
		this.listeners.add( listener );

		final List< List< BasicViewDescription< ? > > > selectedList = new ArrayList<>( selectedRows );
		listener.selectedViewDescriptions( selectedList );
	}

	public ArrayList< SelectedViewDescriptionListener< AS > > getListeners()
	{
		return listeners;
	}

	public abstract void initComponent();

	/**
	 * Update UI checkboxes when grouping is programmatically cleared.
	 * Override this method to uncheck grouping checkboxes in subclasses.
	 */
	protected abstract void clearGroupingCheckboxes();

	public void updateFilter( Class< ? extends Entity > entityClass, Entity selectedInstance )
	{
		ArrayList< Entity > selectedInstances = new ArrayList<>();
		selectedInstances.add( selectedInstance );
		tableModel.addFilter( entityClass, selectedInstances );
	}

	protected static List< String > getEntityNamesOrIds( List< ? extends Entity > entities )
	{
		ArrayList< String > names = new ArrayList<>();

		for ( Entity e : entities )
			names.add( e instanceof NamedEntity ? ( ( NamedEntity ) e ).getName() : Integer.toString( e.getId() ) );

		return names;
	}

	public static Entity getInstanceFromNameOrId( AbstractSequenceDescription< ?, ?, ? > sd, Class< ? extends Entity > entityClass, String nameOrId )
	{
		for ( Entity e : SpimDataTools.getInstancesOfAttribute( sd, entityClass ) )
			if ( e instanceof NamedEntity && ( ( NamedEntity ) e ).getName().equals( nameOrId ) || Integer.toString( e.getId() ).equals( nameOrId ) )
				return e;
		return null;
	}

	protected void addHelp()
	{
		table.addKeyListener( new KeyAdapter()
		{
			@Override
			public void keyPressed( KeyEvent e )
			{
				if ( e.getKeyCode() == 112 )
					new HelpDialog( explorer().getFrame(), this.getClass().getResource( getHelpHtml() ) ).setVisible( true );
			}
		} );
	}

	protected abstract String getHelpHtml();

	protected ListSelectionListener getSelectionListener()
	{
		return new ListSelectionListener()
		{
			//int lastRow = -1;

			@Override
			public void valueChanged(final ListSelectionEvent arg0)
			{
				BDVPopup b = bdvPopup();

				selectedRows.clear();
				firstSelectedVD = null;
				for ( final int row : table.getSelectedRows() )
				{
					if ( firstSelectedVD == null )
						firstSelectedVD = tableModel.getElements().get( row ).get( 0 );

					selectedRows.add( tableModel.getElements().get( row ) );
				}


				List<List<BasicViewDescription< ? >>> selectedList = new ArrayList<>();
				for (List<BasicViewDescription< ? >> selectedI : selectedRows)
					selectedList.add( selectedI );

				for ( int i = 0; i < listeners.size(); ++i )
					listeners.get( i ).selectedViewDescriptions( selectedList );

				/*
				if ( table.getSelectedRowCount() != 1 )
				{
					lastRow = -1;

					for ( int i = 0; i < listeners.size(); ++i )
						listeners.get( i ).firstSelectedViewDescriptions( null );

					selectedRows.clear();
					firstSelectedVD = null;
					for ( final int row : table.getSelectedRows() )
					{
						if ( firstSelectedVD == null )
							// TODO: is this okay? only adding first vd of
							// potentially multiple per row
							firstSelectedVD = tableModel.getElements().get( row ).get( 0 );

						selectedRows.add( tableModel.getElements().get( row ) );
					}

				}
				else
				{
					final int row = table.getSelectedRow();

					if ( ( row != lastRow ) && row >= 0 && row < tableModel.getRowCount() )
					{
						lastRow = row;

						// not using an iterator allows that listeners can close
						// the frame and remove all listeners while they are
						// called
						final List< BasicViewDescription< ? extends BasicViewSetup > > vds = tableModel.getElements()
								.get( row );

						for ( int i = 0; i < listeners.size(); ++i )
							listeners.get( i ).firstSelectedViewDescriptions( vds );

						selectedRows.clear();
						selectedRows.add( vds );

						firstSelectedVD = vds.get( 0 );
					}
				}
				*/

				if ( b != null && b.bdv != null )
				{
					updateBDV( b.bdv, colorMode, data, firstSelectedVD, selectedRows);

				}

				// Save selection to history
				saveSelectionToHistory();

			}


		};
	}

	public static void updateBDV(
			final BigDataViewer bdv,
			final boolean colorMode,
			final AbstractSpimData< ? > data,
			BasicViewDescription< ? > firstVD,
			final Collection< List< BasicViewDescription< ? > > > selectedRows )
	{

		// bdv is not open
		if ( bdv == null )
			return;

		// we always set the fused mode
		BDVTools.setFusedModeSimple( bdv, data );

		BDVTools.resetBDVManualTransformations( bdv );

		if ( selectedRows == null || selectedRows.size() == 0 )
			return;

		if ( firstVD == null )
			firstVD = selectedRows.iterator().next().get( 0 );

		final ViewerState state = bdv.getViewer().state();

		// always use the first timepoint
		final TimePoint firstTP = firstVD.getTimePoint();
		state.setCurrentTimepoint( BDVTools.getBDVTimePointIndex( firstTP, data ) );

		final Set< Integer > selectedViewSetupIds = selectedRows.stream()
				.flatMap( Collection::stream )
				.filter( vd -> vd.getTimePointId() == firstTP.getId() )
				.map( ViewId::getViewSetupId )
				.collect( Collectors.toSet() );

		final List< SourceAndConverter< ? > > active = new ArrayList<>();
		synchronized ( state )
		{
			BDVUtils.forEachAbstractSpimSource(
					state.getSources(),
					( soc, source ) -> {
						if ( selectedViewSetupIds.contains( source.getSetupId() ) )
							active.add( soc );
					} );
		}
		BDVTools.setVisibleSources( state, active );

//		if ( selectedRows.size() > 1 && colorMode )
//			colorSources( bdv.getSetupAssignments().getConverterSetups(), data, channelColors);
//		else
//			whiteSources( bdv.getSetupAssignments().getConverterSetups() );

		bdv.getViewer().requestRepaint();
	}

	public Set< List< BasicViewDescription< ? > > > getSelectedRows()
	{
		return selectedRows;
	}

	public void showInfoBox()
	{
		new ViewSetupExplorerInfoBox<>( data );
	}

	public JPopupMenu addRightClickSaveAs()
	{
		final JPopupMenu popupMenu = new JPopupMenu();
		final JMenuItem item = new JMenuItem( "Save as ..." );

		item.addActionListener( e ->
		{
			final SpimData2 data = this.getSpimData();

			final URI newXMLPath = XMLSaveAs.saveAs( data, URITools.getFileName( this.xml() ) );

			if ( newXMLPath != null )
			{
				this.xml = newXMLPath;
				this.saveXML();
				this.xmlLabel.setText( "XML: " + newXMLPath );
			}
		});

		popupMenu.add( item );

		return popupMenu;
	}

	@Override
	public void saveXML()
	{
		io.save( data, xml );

		for ( final SelectedViewDescriptionListener< AS > l : listeners )
			l.save(); // e.g. delete interest points
	}

	protected void addPopupMenu( final JTable table )
	{
		final JPopupMenu popupMenu = new JPopupMenu();

		for ( final ExplorerWindowSetable item : popups )
			popupMenu.add( item.setExplorerWindow( this ) );

		table.setComponentPopupMenu( popupMenu );
	}

	protected void addColorMode()
	{
		table.addKeyListener( new KeyAdapter()
		{
			@Override
			public void keyPressed( final KeyEvent arg0 )
			{
				if ( arg0.getKeyChar() == 'c' || arg0.getKeyChar() == 'C' )
				{
					colorMode = !colorMode;

					System.out.println( "colormode" );

					final BasicBDVPopup p = runningBdvPopup();
					if ( p != null && p.getBDV() != null && p.getBDV().getViewerFrame().isVisible() )
						updateBDV( p.getBDV(), colorMode, data, null, selectedRows );
				}
			}
		} );
	}

	protected void addViewSetupIdShortcut()
	{
		table.addKeyListener( new KeyAdapter()
		{
			@Override
			public void keyPressed( final KeyEvent arg0 )
			{
				if ( arg0.getKeyChar() == 'v' || arg0.getKeyChar() == 'V' )
				{
					final BasicBDVPopup p = runningBdvPopup();
					if ( p != null && p.getBDV() != null && p.getBDV().getViewerFrame().isVisible() )
					{
						toggleViewSetupIdOverlay( p.getBDV() );
					}
				}
			}
		} );
	}

	private void toggleViewSetupIdOverlay( final BigDataViewer bdv )
	{
		if ( viewSetupIdOverlay == null )
		{
			// Create and add the overlay
			viewSetupIdOverlay = new ViewSetupIdOverlay( bdv.getViewer() );
			bdv.getViewer().renderTransformListeners().add( viewSetupIdOverlay );
			bdv.getViewer().getDisplay().overlays().add( viewSetupIdOverlay );
			IOFunctions.println( "ViewSetupId overlay enabled. Press 'v' again to disable." );
		}
		else
		{
			// Toggle visibility
			boolean currentlyVisible = viewSetupIdOverlay.isVisible();
			viewSetupIdOverlay.setVisible( !currentlyVisible );
			if ( !currentlyVisible )
			{
				IOFunctions.println( "ViewSetupId overlay enabled. Press 'v' again to disable." );
			}
			else
			{
				IOFunctions.println( "ViewSetupId overlay disabled. Press 'v' again to enable." );
			}
		}
		bdv.getViewer().repaint();
	}

	public void cleanupViewSetupIdOverlay()
	{
		if ( viewSetupIdOverlay != null )
		{
			final BasicBDVPopup p = runningBdvPopup();
			if ( p != null && p.getBDV() != null )
			{
				p.getBDV().getViewer().renderTransformListeners().remove( viewSetupIdOverlay );
				p.getBDV().getViewer().getDisplay().overlays().remove( viewSetupIdOverlay );
				p.getBDV().getViewer().repaint();
			}
			viewSetupIdOverlay = null;
		}
	}

	protected void addReCenterShortcut()
	{
		table.addKeyListener( new KeyAdapter()
		{
			@Override
			public void keyPressed( final KeyEvent arg0 )
			{
				if ( arg0.getKeyChar() == 'r' || arg0.getKeyChar() == 'R' )
				{
					final BasicBDVPopup p = runningBdvPopup();
					if ( p != null && p.getBDV() != null && p.getBDV().getViewerFrame().isVisible() )
					{
						TransformationTools.reCenterViews( p.getBDV(),
								selectedRows.stream().collect(
										HashSet< BasicViewDescription< ? > >::new,
										( a, b ) -> a.addAll( b ), ( a, b ) -> a.addAll( b ) ),
								data.getViewRegistrations() );
					}
				}
			}
		} );
	}

	/** 'n'/'N' opens the {@link net.preibisch.mvrecon.fiji.spimdata.explorer.viewneighbours.ViewNeighboursWindow}
	 *  on first press (and applies); subsequent presses re-apply with the window's current params. */
	protected void addNeighboursShortcut()
	{
		table.addKeyListener( new KeyAdapter()
		{
			@Override
			public void keyPressed( final KeyEvent arg0 )
			{
				if ( arg0.getKeyChar() != 'n' && arg0.getKeyChar() != 'N' )
					return;
				if ( viewNeighboursWindow == null || !viewNeighboursWindow.isDisplayable() )
					viewNeighboursWindow = new ViewNeighboursWindow( FilteredAndGroupedExplorerPanel.this );
				// If the Overlap window is currently expanded, collapse it first so 'n'
				// expands from the original anchor (not from 'o''s expanded set).
				if ( viewOverlapWindow != null && viewOverlapWindow.isDisplayable() && viewOverlapWindow.isExpanded() )
					viewOverlapWindow.collapse();
				viewNeighboursWindow.toggle();
			}
		} );
	}

	private ViewNeighboursWindow viewNeighboursWindow = null;

	/** 'o'/'O' opens the {@link ViewOverlapWindow}
	 *  on first press (and applies); subsequent presses toggle expand ↔ collapse. */
	protected void addOverlapShortcut()
	{
		table.addKeyListener( new KeyAdapter()
		{
			@Override
			public void keyPressed( final KeyEvent arg0 )
			{
				if ( arg0.getKeyChar() != 'o' && arg0.getKeyChar() != 'O' )
					return;
				if ( viewOverlapWindow == null || !viewOverlapWindow.isDisplayable() )
					viewOverlapWindow = new ViewOverlapWindow( FilteredAndGroupedExplorerPanel.this );
				// If the Neighbours window is currently expanded, collapse it first so 'o'
				// expands from the original anchor (not from 'n''s expanded set).
				if ( viewNeighboursWindow != null && viewNeighboursWindow.isDisplayable() && viewNeighboursWindow.isExpanded() )
					viewNeighboursWindow.collapse();
				viewOverlapWindow.toggle();
			}
		} );
	}

	private ViewOverlapWindow viewOverlapWindow = null;

	/**
	 * Adjust the max-intensity (white point) of BDV sources by ±10% per press:
	 * <ul>
	 *   <li>{@code [} / {@code ]} &ndash; lower / raise the max of the currently <em>visible</em> sources</li>
	 *   <li>{@code &#123;} / {@code &#125;} &ndash; lower / raise the max of <em>all</em> sources (including inactive ones)</li>
	 * </ul>
	 * Lowering the max brightens the image; raising it darkens it.
	 */
	protected void addMaxIntensityShortcut()
	{
		table.addKeyListener( new KeyAdapter()
		{
			@Override
			public void keyPressed( final KeyEvent arg0 )
			{
				final char c = arg0.getKeyChar();
				if ( c != '[' && c != ']' && c != '{' && c != '}' )
					return;
				final BasicBDVPopup p = runningBdvPopup();
				if ( p == null || p.getBDV() == null || !p.getBDV().getViewerFrame().isVisible() )
					return;
				final boolean visibleOnly = ( c == '[' || c == ']' );
				final double factor = ( c == '[' || c == '{' ) ? 0.9 : 1.1;
				net.preibisch.mvrecon.fiji.spimdata.explorer.bdv.BDVUtils.scaleDisplayRangeMax(
						p.getBDV(), factor, visibleOnly );
			}
		} );
	}

	protected void addSelectionDialog()
	{
		table.addKeyListener( new KeyAdapter()
		{
			@Override
			public void keyPressed( final KeyEvent arg0 )
			{
				if ( arg0.getKeyChar() == '+' )
				{
					if ( disableGroupingIfActive() )
					{
						// Wait for table to update after clearing grouping
						SwingUtilities.invokeLater( () -> openSelectionDialog() );
					}
					else
					{
						openSelectionDialog();
					}
				}
			}
		} );
	}

	protected void openSelectionDialog()
	{
		final SelectionDialog dialog =
			new SelectionDialog(
				explorer().getFrame(), data );
		dialog.setVisible( true );

		if ( !dialog.wasCanceled() )
		{
			final List<BasicViewDescription<?>> selectedViews = dialog.getSelectedViews();
			if ( selectedViews != null && !selectedViews.isEmpty() )
			{
				// Select the views in the table
				selectViews( selectedViews );
				IOFunctions.println( "Selected " + selectedViews.size() + " views based on criteria." );
			}
		}
	}

	protected void selectViews( final List<BasicViewDescription<?>> views )
	{
		// Clear current selection
		table.clearSelection();

		// Find and select matching rows
		int firstMatch = -1;
		for ( int row = 0; row < tableModel.getRowCount(); row++ )
		{
			final List<BasicViewDescription<?>> rowViews = tableModel.getElements().get( row );
			for ( final BasicViewDescription<?> vd : rowViews )
			{
				if ( views.contains( vd ) )
				{
					table.addRowSelectionInterval( row, row );
					if ( firstMatch < 0 )
						firstMatch = row;
					break;
				}
			}
		}

		// Scroll the table so the first matched row is visible — useful when
		// the dataset has tens of thousands of rows and the selection is
		// otherwise off-screen.
		if ( firstMatch >= 0 )
			table.scrollRectToVisible( table.getCellRect( firstMatch, 0, true ) );
	}

	protected void saveSelectionToHistory()
	{
		if ( navigatingHistory )
			return;

		// Get current selection
		final List<BasicViewDescription<?>> currentSelection = new ArrayList<>();
		for ( final int row : table.getSelectedRows() )
		{
			currentSelection.addAll( tableModel.getElements().get( row ) );
		}

		// Don't save empty selections or identical to current history position
		if ( currentSelection.isEmpty() )
			return;

		if ( historyIndex >= 0 && historyIndex < selectionHistory.size() )
		{
			final List<BasicViewDescription<?>> lastSelection = selectionHistory.get( historyIndex );
			if ( new HashSet<>( currentSelection ).equals( new HashSet<>( lastSelection ) ) )
				return;
		}

		// Remove everything after current index (when navigating back and making new selection)
		if ( historyIndex < selectionHistory.size() - 1 )
		{
			selectionHistory.subList( historyIndex + 1, selectionHistory.size() ).clear();
		}

		// Add new selection
		selectionHistory.add( new ArrayList<>( currentSelection ) );
		historyIndex++;

		// Limit history size
		if ( selectionHistory.size() > MAX_HISTORY_SIZE )
		{
			selectionHistory.remove( 0 );
			historyIndex--;
		}

		IOFunctions.println( "Saved selection to history (" + (historyIndex + 1) + "/" + selectionHistory.size() + ")" );
	}

	protected void navigateHistoryBackward()
	{
		if ( historyIndex > 0 )
		{
			historyIndex--;
			navigatingHistory = true;
			final List<BasicViewDescription<?>> selection = selectionHistory.get( historyIndex );
			selectViews( selection );
			navigatingHistory = false;
			IOFunctions.println( "History: " + (historyIndex + 1) + "/" + selectionHistory.size() );
		}
		else
		{
			IOFunctions.println( "Already at oldest selection in history" );
		}
	}

	protected void navigateHistoryForward()
	{
		if ( historyIndex < selectionHistory.size() - 1 )
		{
			historyIndex++;
			navigatingHistory = true;
			final List<BasicViewDescription<?>> selection = selectionHistory.get( historyIndex );
			selectViews( selection );
			navigatingHistory = false;
			IOFunctions.println( "History: " + (historyIndex + 1) + "/" + selectionHistory.size() );
		}
		else
		{
			IOFunctions.println( "Already at newest selection in history" );
		}
	}

	protected boolean disableGroupingIfActive()
	{
		if ( tableModel != null && tableModel.getGroupingFactors() != null && !tableModel.getGroupingFactors().isEmpty() )
		{
			tableModel.clearGroupingFactors();
			clearGroupingCheckboxes();
			IOFunctions.println( "Disabled grouping for selection operations" );
			return true;
		}
		return false;
	}

	protected void addHistoryNavigation()
	{
		table.addKeyListener( new KeyAdapter()
		{
			@Override
			public void keyPressed( final KeyEvent e )
			{
				if ( e.getKeyChar() == '<' || e.getKeyChar() == ',' )
				{
					if ( disableGroupingIfActive() )
					{
						// Wait for table to update after clearing grouping
						SwingUtilities.invokeLater( () -> navigateHistoryBackward() );
					}
					else
					{
						navigateHistoryBackward();
					}
				}
				else if ( e.getKeyChar() == '>' || e.getKeyChar() == '.' )
				{
					if ( disableGroupingIfActive() )
					{
						// Wait for table to update after clearing grouping
						SwingUtilities.invokeLater( () -> navigateHistoryForward() );
					}
					else
					{
						navigateHistoryForward();
					}
				}
			}
		} );
	}

	protected void addAppleA()
	{
		table.addKeyListener( new KeyListener()
		{
			boolean appleKeyDown = false;

			@Override
			public void keyTyped( KeyEvent arg0 )
			{
				if ( appleKeyDown && arg0.getKeyChar() == 'a' )
					table.selectAll();
			}

			@Override
			public void keyReleased( KeyEvent arg0 )
			{
				if ( arg0.getKeyCode() == 157 )
					appleKeyDown = false;
			}

			@Override
			public void keyPressed( KeyEvent arg0 )
			{
				if ( arg0.getKeyCode() == 157 )
					appleKeyDown = true;
			}
		} );
	}

	private boolean enableFlyThrough = false;

	protected void addScreenshot()
	{
		table.addKeyListener( new KeyAdapter()
		{
			@Override
			public void keyPressed( final KeyEvent arg0 )
			{
				if ( arg0.getKeyChar() == 'E' )
				{
					enableFlyThrough = true;

					IOFunctions.println( "EASTER EGG activated." );
					IOFunctions.println( "You can now record a fly-through: " );
					IOFunctions.println( "   press 'a' to add the current view as keypoint" );
					IOFunctions.println( "   press 'x' to remove all keypoints" );
					IOFunctions.println( "   press 'd' to remove last keypoint" );
					IOFunctions.println( "   press 'j' to jump with BDV to the last keypoint" );
					IOFunctions.println( "   press 'r' to start recording!" );
					IOFunctions.println( "   press 's' to save all keypoints" );
					IOFunctions.println( "   press 'l' to load all keypoints" );
					IOFunctions.println( "'R' makes a screenshot with a user-defined resolution" );
				}

				if ( enableFlyThrough )
				{
					final BasicBDVPopup p = runningBdvPopup();
					final boolean bdvRunning = p != null && p.bdvRunning() && p.getBDV() != null;

					if ( arg0.getKeyChar() == 'r' )
						if (bdvRunning)
							new Thread( new Runnable()
							{
								@Override
								public void run()
								{ BDVFlyThrough.record( p.getBDV().getViewer() ); }
							} ).start();
						else
							IOFunctions.println("Please open BigDataViewer to record a fly-through or add keypoints.");

					if ( arg0.getKeyChar() == 'a' )
						if (bdvRunning)
							BDVFlyThrough.addCurrentViewerTransform( p.getBDV().getViewer() );
						else
							IOFunctions.println("Please open BigDataViewer to record a fly-through or add keypoints.");

					if ( arg0.getKeyChar() == 'x' )
						BDVFlyThrough.clearAllViewerTransform();

					if ( arg0.getKeyChar() == 'd' )
						BDVFlyThrough.deleteLastViewerTransform();

					if ( arg0.getKeyChar() == 'j' )
						if ( bdvRunning )
							BDVFlyThrough.jumpToLastViewerTransform( p.getBDV().getViewer() );

					if ( arg0.getKeyChar() == 's' )
						try { BDVFlyThrough.saveViewerTransforms(); } catch ( Exception e ) { IOFunctions.println( "couldn't save json: " + e ); }

					if ( arg0.getKeyChar() == 'l' )
						try { BDVFlyThrough.loadViewerTransforms(); } catch ( Exception e ) { IOFunctions.println( "couldn't load json: " + e ); }

					if ( arg0.getKeyChar() == 'R' )
						if ( bdvRunning )
							new Thread( () -> BDVFlyThrough.renderScreenshot( p.getBDV().getViewer() ) ).start();
						else
							IOFunctions.println( "Please open BigDataViewer to make a screenshot." );
				}
			}
		} );
	}

	public abstract ArrayList< ExplorerWindowSetable > initPopups();

	@Override
	public Collection< List< BasicViewDescription< ? > > > selectedRowsGroups()
	{
		return selectedRows;
	}

	@Override
	public List< List< ViewId > > selectedRowsViewIdGroups()
	{
		final ArrayList< List< ViewId > > list = new ArrayList<>();
		for ( List< BasicViewDescription< ? > > vds : selectedRows )
			list.add( new ArrayList<>( vds ) );
		//Collections.sort( list );
		return list;
	}
}
