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
import bdv.tools.brightness.ConverterSetup;
import bdv.viewer.DisplayMode;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerState;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.base.NamedEntity;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.XMLSaveAs;
import net.preibisch.mvrecon.fiji.spimdata.GroupedViews;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.SpimDataTools;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.bdv.BDVFlyThrough;
import net.preibisch.mvrecon.fiji.spimdata.explorer.bdv.BDVUtils;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BDVPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ExplorerWindowSetable;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
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
	private static boolean bulkSelecting = false;

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
				// Skip intermediate selection changes while user is still adjusting
				// (e.g., shift-clicking multiple rows). Only process when finalized.
				if ( arg0.getValueIsAdjusting() )
					return;

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

	public static void resetBDVManualTransformations( BigDataViewer bdv )
	{
		if ( bdv == null )
			return;

		// reset manual transform for all views
		final AffineTransform3D identity = new AffineTransform3D();
		final ViewerState state = bdv.getViewer().state();
		synchronized ( state )
		{
			BDVUtils.forEachTransformedSource(
					state.getSources(),
					( soc, source ) -> {
						source.setFixedTransform( identity );
						source.setIncrementalTransform( identity );
					} );
		}
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
		setFusedModeSimple( bdv, data );

		resetBDVManualTransformations( bdv );

		if ( selectedRows == null || selectedRows.size() == 0 )
			return;

		if ( firstVD == null )
			firstVD = selectedRows.iterator().next().get( 0 );

		final ViewerState state = bdv.getViewer().state();

		// always use the first timepoint
		final TimePoint firstTP = firstVD.getTimePoint();
		state.setCurrentTimepoint( getBDVTimePointIndex( firstTP, data ) );

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
		setVisibleSources( state, active );

//		if ( selectedRows.size() > 1 && colorMode )
//			colorSources( bdv.getSetupAssignments().getConverterSetups(), data, channelColors);
//		else
//			whiteSources( bdv.getSetupAssignments().getConverterSetups() );

		bdv.getViewer().requestRepaint();
	}

	public static void setFusedModeSimple( final BigDataViewer bdv, final AbstractSpimData< ? > data )
	{
		if ( bdv == null )
			return;

		final ViewerState state = bdv.getViewer().state();
		if ( state.getDisplayMode() != DisplayMode.FUSED )
		{
			setVisibleSources( state, state.getSources().subList( 0, 0 ) );
			state.setDisplayMode( DisplayMode.FUSED );
		}
	}

	// TODO (TP) This has duplicates in StitchingExplorerPanel and ViewSetupExplorerPanel
	//           Move to common utility class?
	public static void whiteSources( final List< ConverterSetup > cs )
	{
		sameColorSources( cs, 255, 255, 255, 255 );
	}

	public static void sameColorSources( final List< ConverterSetup > cs, final int r, final int g, final int b, final int a )
	{
		final ARGBType color = new ARGBType( ARGBType.rgba( r, g, b, a ) );
		cs.forEach( c -> c.setColor( color ) );
	}

	public static void setVisibleSources( final ViewerState state, final Collection< ? extends SourceAndConverter< ? > > active )
	{
		final List< SourceAndConverter< ? > > inactive = new ArrayList<>( state.getSources() );
		inactive.removeAll( active );
		state.setSourcesActive( inactive, false );
		state.setSourcesActive( active, true );
	}

	public static int getBDVTimePointIndex( final TimePoint t, final AbstractSpimData< ? > data )
	{
		final List< TimePoint > list = data.getSequenceDescription().getTimePoints().getTimePointsOrdered();

		for ( int i = 0; i < list.size(); ++i )
			if ( list.get( i ).getId() == t.getId() )
				return i;

		return 0;
	}

	public static int getBDVSourceIndex( final BasicViewSetup vs, final AbstractSpimData< ? > data )
	{
		final List< ? extends BasicViewSetup > list = data.getSequenceDescription().getViewSetupsOrdered();

		for ( int i = 0; i < list.size(); ++i )
			if ( list.get( i ).getId() == vs.getId() )
				return i;

		return 0;
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

					final BDVPopup p = bdvPopup();
					if ( p != null && p.bdv != null && p.bdv.getViewerFrame().isVisible() )
						updateBDV( p.bdv, colorMode, data, null, selectedRows );
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
					final BDVPopup p = bdvPopup();
					if ( p != null && p.bdv != null && p.bdv.getViewerFrame().isVisible() )
					{
						toggleViewSetupIdOverlay( p.bdv );
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
			final BDVPopup p = bdvPopup();
			if ( p != null && p.bdv != null )
			{
				p.bdv.getViewer().renderTransformListeners().remove( viewSetupIdOverlay );
				p.bdv.getViewer().getDisplay().overlays().remove( viewSetupIdOverlay );
				p.bdv.getViewer().repaint();
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
					final BDVPopup p = bdvPopup();
					if ( p != null && p.bdv != null && p.bdv.getViewerFrame().isVisible() )
					{
						TransformationTools.reCenterViews( p.bdv,
								selectedRows.stream().collect(
										HashSet< BasicViewDescription< ? > >::new,
										( a, b ) -> a.addAll( b ), ( a, b ) -> a.addAll( b ) ),
								data.getViewRegistrations() );
					}
				}
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
		final net.preibisch.mvrecon.fiji.spimdata.explorer.selection.SelectionDialog dialog =
			new net.preibisch.mvrecon.fiji.spimdata.explorer.selection.SelectionDialog(
				explorer().getFrame(), data );
		dialog.setVisible( true );

		if ( !dialog.wasCanceled() )
		{
			final List<BasicViewDescription<?>> selectedViews = dialog.getSelectedViews();
			if ( selectedViews != null && !selectedViews.isEmpty() )
			{
				// Select the views in the table (disable history saving during bulk selection)
				bulkSelecting = true;
				selectViews( selectedViews );
				bulkSelecting = false;

				// Save the final selection to history once
				saveSelectionToHistory();

				IOFunctions.println( "Selected " + selectedViews.size() + " views based on criteria." );
			}
		}
	}

	protected void selectViews( final List<BasicViewDescription<?>> views )
	{
		// Clear current selection
		table.clearSelection();

		// Find and select matching rows
		for ( int row = 0; row < tableModel.getRowCount(); row++ )
		{
			final List<BasicViewDescription<?>> rowViews = tableModel.getElements().get( row );
			for ( final BasicViewDescription<?> vd : rowViews )
			{
				if ( views.contains( vd ) )
				{
					table.addRowSelectionInterval( row, row );
					break;
				}
			}
		}
	}

	protected void saveSelectionToHistory()
	{
		if ( navigatingHistory || bulkSelecting )
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
					final boolean bdvRunning = bdvPopup().bdvRunning() && !(bdvPopup().bdv == null);

					if ( arg0.getKeyChar() == 'r' )
						if (bdvRunning)
							new Thread( new Runnable()
							{
								@Override
								public void run()
								{ BDVFlyThrough.record( bdvPopup().bdv.getViewer() ); }
							} ).start();
						else
							IOFunctions.println("Please open BigDataViewer to record a fly-through or add keypoints.");

					if ( arg0.getKeyChar() == 'a' )
						if (bdvRunning)
							BDVFlyThrough.addCurrentViewerTransform( bdvPopup().bdv.getViewer() );
						else
							IOFunctions.println("Please open BigDataViewer to record a fly-through or add keypoints.");

					if ( arg0.getKeyChar() == 'x' )
						BDVFlyThrough.clearAllViewerTransform();

					if ( arg0.getKeyChar() == 'd' )
						BDVFlyThrough.deleteLastViewerTransform();

					if ( arg0.getKeyChar() == 'j' )
						BDVFlyThrough.jumpToLastViewerTransform( bdvPopup().bdv.getViewer() );

					if ( arg0.getKeyChar() == 's' )
						try { BDVFlyThrough.saveViewerTransforms(); } catch ( Exception e ) { IOFunctions.println( "couldn't save json: " + e ); }

					if ( arg0.getKeyChar() == 'l' )
						try { BDVFlyThrough.loadViewerTransforms(); } catch ( Exception e ) { IOFunctions.println( "couldn't load json: " + e ); }

					if ( arg0.getKeyChar() == 'R' )
						if ( bdvRunning )
							new Thread( () -> BDVFlyThrough.renderScreenshot( bdvPopup().bdv.getViewer() ) ).start();
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
