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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

import bdv.BigDataViewer;
import bdv.ViewerImgLoader;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.bdv.ScrollableBrightnessDialog;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.AnalyzeErrorPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ApplyTransformationPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BDVPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BakeManualTransformationPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BasicBDVPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BoundingBoxPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.DeconvolutionPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.DetectInterestPointsPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.DisplayFusedImagesPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.DisplayRawImagesPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ExplorerWindowSetable;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.FlatFieldCorrectionPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.FusionPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.IntensityAdjustmentPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.InterestPointsExplorerPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.LabelPopUp;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.LazyBDVPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.MaxProjectPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.MissingViewsPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.PointSpreadFunctionsPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.QualityPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.RegisterInterestPointsPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.RegistrationExplorerPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.RemoveDetectionsPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.RemoveTransformationPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ReorientSamplePopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ResavePopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.Separator;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.SimpleHyperlinkPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.SpecifyCalibrationPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.VisualizeDetectionsPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.VisualizeNonRigid;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.filemap2.FileMapImgLoaderLOCI2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting.SplitMultiResolutionImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting.SplitViewerImgLoader;
import util.BDVTools;
import util.URITools;


public class ViewSetupExplorerPanel< AS extends SpimData2 > extends FilteredAndGroupedExplorerPanel< AS > implements ExplorerWindow< AS >
{
	private static final long serialVersionUID = -2512096359830259015L;

	static
	{
		IOFunctions.printIJLog = true;
	}

	public JCheckBox groupTilesCheckbox;
	public JCheckBox groupIllumsCheckbox;
	public JCheckBox hideMissingViewsCheckbox;
	private FilteredAndGroupedTableModel< AS > filteredTableModel; // underlying model before decorators
	private static long colorOffset = 0;

	@Override
	public boolean tilesGrouped()
	{
		if ( groupTilesCheckbox == null || !groupTilesCheckbox.isSelected() )
			return false;
		else
			return true;
	}

	@Override
	public boolean illumsGrouped()
	{
		if ( groupIllumsCheckbox == null || !groupIllumsCheckbox.isSelected() )
			return false;
		else
			return true;
	}

	@Override
	public boolean channelsGrouped() { return false; }

	/**
	 * Detects if the dataset is a Split dataset (created by splitting large images into tiles)
	 * @return true if the imgLoader is a SplitViewerImgLoader or SplitMultiResolutionImgLoader
	 */
	protected boolean isSplitDataset()
	{
		final Object imgLoader = data.getSequenceDescription().getImgLoader();
		return imgLoader instanceof SplitViewerImgLoader || imgLoader instanceof SplitMultiResolutionImgLoader;
	}

	public ViewSetupExplorerPanel( final FilteredAndGroupedExplorer< AS > explorer, final AS data, final URI xml, final XmlIoSpimData2 io, boolean requestStartBDV )
	{
		super( explorer, data, xml, io );

		data.gridMoveRequested = false;

		long start = System.currentTimeMillis();
		popups = initPopups();
		// IOFunctions.println( "PERF: [ViewSetupExplorerPanel] initPopups() took " + (System.currentTimeMillis() - start) + " ms" );

		start = System.currentTimeMillis();
		initComponent();
		// IOFunctions.println( "PERF: [ViewSetupExplorerPanel] initComponent() took " + (System.currentTimeMillis() - start) + " ms" );

		if ( requestStartBDV &&
				(ViewerImgLoader.class.isInstance( data.getSequenceDescription().getImgLoader() )
				|| data.getSequenceDescription().getImgLoader().getClass().getSimpleName().equals( "FractalImgLoader" )
				|| FileMapImgLoaderLOCI2.class.isInstance( data.getSequenceDescription().getImgLoader() ) ) )
		{
			// Find whichever BDV popup was registered above (eager BDVPopup or LazyBDVPopup
			// — exclusive based on BDVPopup.useLazyMode set by Data_Explorer's startup dialog).
			BasicBDVPopup p = null;
			for ( final ExplorerWindowSetable s : popups )
				if ( s instanceof BasicBDVPopup ) { p = ( BasicBDVPopup ) s; break; }

			if ( p instanceof LazyBDVPopup )
			{
				start = System.currentTimeMillis();
				( ( LazyBDVPopup ) p ).openBDV( this );
				// IOFunctions.println( "PERF: [ViewSetupExplorerPanel] LazyBDVPopup.openBDV() took " + (System.currentTimeMillis() - start) + " ms" );
				// Lazy mode wires its own selection listener; nothing more to do here.
			}
			else if ( p instanceof BDVPopup )
			{
				final BDVPopup bdvpopup = ( BDVPopup ) p;

				if ( !bdvpopup.bdvRunning() )
				{
					start = System.currentTimeMillis();
					bdvpopup.bdv = BDVPopup.createBDV( getSpimData(), xml() );
					// IOFunctions.println( "PERF: [ViewSetupExplorerPanel] BDVPopup.createBDV() took " + (System.currentTimeMillis() - start) + " ms" );
				}

				start = System.currentTimeMillis();
				BDVTools.setFusedModeSimple( bdvpopup.bdv, data );
				// IOFunctions.println( "PERF: [ViewSetupExplorerPanel] setFusedModeSimple() took " + (System.currentTimeMillis() - start) + " ms" );

				// Update BDV to show all grouped tiles based on initial table selection
				if ( !selectedRows.isEmpty() )
				{
					start = System.currentTimeMillis();
					updateBDV( bdvpopup.bdv, colorMode, data, firstSelectedVD, selectedRows );
					// IOFunctions.println( "PERF: [ViewSetupExplorerPanel] updateBDV() took " + (System.currentTimeMillis() - start) + " ms" );
				}
			}
		}

		// for access to the current BDV
		currentInstance = this;
	}

	public void initComponent()
	{
		filteredTableModel = new FilteredAndGroupedTableModel< AS >( this );
		tableModel = filteredTableModel;
		tableModel = new MultiViewTableModelDecorator<>( tableModel );
		tableModel = new MissingViewsTableModelDecorator<>( tableModel );
		tableModel.setColumnClasses( FilteredAndGroupedTableModel.defaultColumnClassesMV() );

		// For Split datasets, default to grouping Tiles; otherwise group Illuminations
		if ( isSplitDataset() )
			tableModel.addGroupingFactor( Tile.class );
		else
			tableModel.addGroupingFactor( Illumination.class );

		table = new JTable();
		table.setModel( tableModel );
		table.setSurrendersFocusOnKeystroke( true );
		table.setSelectionMode( ListSelectionModel.MULTIPLE_INTERVAL_SELECTION );

		final DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
		centerRenderer.setHorizontalAlignment( JLabel.CENTER );
		
		// center all columns
		for ( int column = 0; column < tableModel.getColumnCount(); ++column )
		{
			if ( tableModel.getColumnName( column ).equals( "PSF" ) )
			{
				table.getColumnModel().getColumn( column ).setCellRenderer( new CheckBoxRenderer() );
				table.getColumnModel().getColumn( column ).setPreferredWidth( 20 );
			}
			else
			{
				table.getColumnModel().getColumn( column ).setCellRenderer( centerRenderer );
			}
		}

		// add listener to which row is selected
		table.getSelectionModel().addListSelectionListener( getSelectionListener() );

		// check out if the user clicked on the column header and potentially sorting by that
		table.getTableHeader().addMouseListener( new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent mouseEvent)
			{
				int index = table.convertColumnIndexToModel(table.columnAtPoint(mouseEvent.getPoint()));
				if (index >= 0)
				{
					int row = table.getSelectedRow();
					tableModel.sortByColumn( index );
					table.clearSelection();
					table.getSelectionModel().setSelectionInterval( row, row );
				}
			};
		});

		if ( isMac )
			addAppleA();

		addColorMode();
		addHelp();
		addReCenterShortcut();
		addViewSetupIdShortcut(); // 'v' or 'V'
		addNeighboursShortcut(); // 'n' or 'N' to highlight correspondence-connected views
		addOverlapShortcut();    // 'o' or 'O' to highlight geometrically-overlapping views
		addMaxIntensityShortcut(); // '[' ']' adjust max intensity of visible sources; '{' '}' all sources
		addSelectionDialog(); // '+' to open selection dialog
		addHistoryNavigation(); // '<' and '>' to navigate selection history

		addScreenshot(); // 's' or 'S'

		table.setPreferredScrollableViewportSize( new Dimension( 750, 300 ) );
		table.getColumnModel().getColumn( 0 ).setPreferredWidth( 20 );
		table.getColumnModel().getColumn( 1 ).setPreferredWidth( 15 );
		table.getColumnModel().getColumn( tableModel.getSpecialColumn( ISpimDataTableModel.SpecialColumnType.VIEW_REGISTRATION_COLUMN) ).setPreferredWidth( 25 );

		if ( tableModel.getSpecialColumn( ISpimDataTableModel.SpecialColumnType.INTEREST_POINT_COLUMN) >= 0 )
			table.getColumnModel().getColumn( tableModel.getSpecialColumn( ISpimDataTableModel.SpecialColumnType.INTEREST_POINT_COLUMN) ).setPreferredWidth( 30 );

		this.setLayout( new BorderLayout() );

		final JButton save = new JButton( "Save" );
		save.addActionListener( new ActionListener()
		{
			@Override
			public void actionPerformed( final ActionEvent e )
			{
				if ( save.isEnabled() )
					saveXML();
			}
		});
		save.setComponentPopupMenu( addRightClickSaveAs() );

		final JButton info = new JButton( "Info" );
		info.addActionListener( new ActionListener()
		{
			@Override
			public void actionPerformed( final ActionEvent e )
			{
				if ( info.isEnabled() )
					showInfoBox();
			}
		});

		final JPanel buttons = new JPanel( new BorderLayout() );
		buttons.add( info, BorderLayout.WEST );
		buttons.add( save, BorderLayout.EAST );

		final JPanel header = new JPanel( new BorderLayout() );
		header.add( xmlLabel = getXMLLabel( xml ), BorderLayout.WEST );
		header.add( buttons, BorderLayout.EAST );
		this.add( header, BorderLayout.NORTH );
		this.add( new JScrollPane( table ), BorderLayout.CENTER );
		
		final JPanel footer = new JPanel(new BorderLayout());
		// For Split datasets, default to grouping Tiles; otherwise group Illuminations
		final boolean isSplit = isSplitDataset();
		this.groupTilesCheckbox = new JCheckBox("Group Tiles", isSplit);
		this.groupIllumsCheckbox = new JCheckBox("Group Illuminations", !isSplit);
		this.hideMissingViewsCheckbox = new JCheckBox("Hide Missing Views", false);
		footer.add(groupTilesCheckbox, BorderLayout.EAST);
		footer.add(hideMissingViewsCheckbox, BorderLayout.CENTER);
		footer.add(groupIllumsCheckbox, BorderLayout.WEST);
		this.add(footer, BorderLayout.SOUTH);

		// Enable checkbox only if there are missing views
		updateHideMissingViewsCheckbox();
		
		groupTilesCheckbox.addActionListener(e -> {
			if (groupTilesCheckbox.isSelected())
				tableModel.addGroupingFactor(Tile.class);
			else
			{
				tableModel.clearGroupingFactors();
				if ( groupIllumsCheckbox.isSelected())
					tableModel.addGroupingFactor(Illumination.class);
			}
			updateContent();
		});

		groupIllumsCheckbox.addActionListener(e -> {
			if (groupIllumsCheckbox.isSelected())
				tableModel.addGroupingFactor(Illumination.class);
			else
			{
				tableModel.clearGroupingFactors();
				if (groupTilesCheckbox.isSelected())
					tableModel.addGroupingFactor(Tile.class);
			}
			updateContent();
		});

		hideMissingViewsCheckbox.addActionListener(e -> {
			filteredTableModel.setHideMissingViews(hideMissingViewsCheckbox.isSelected());
			updateContent();
		});

		table.getSelectionModel().setSelectionInterval( 0, 0 );

		addPopupMenu( table );
	}

	public static JLabel getXMLLabel( final URI xml )
	{
		final JLabel l = new JLabel( "XML: " + xml );
		l.setBorder( new EmptyBorder( 0, 9, 0, 0 ) );
		return l;
	}

	@Override
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
				BDVPopup b = bdvPopup();

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

	@Override
	protected void clearGroupingCheckboxes()
	{
		if ( groupTilesCheckbox != null )
			groupTilesCheckbox.setSelected( false );
		if ( groupIllumsCheckbox != null )
			groupIllumsCheckbox.setSelected( false );
	}

	/**
	 * Updates the enabled state and label of the "Hide Missing Views" checkbox.
	 * The checkbox is only enabled when there are missing views in the dataset.
	 */
	public void updateHideMissingViewsCheckbox()
	{
		if ( hideMissingViewsCheckbox == null )
			return;

		final int missingCount = getMissingViewsCount();
		final boolean hasMissingViews = missingCount > 0;

		// Update label with count
		if ( hasMissingViews )
			hideMissingViewsCheckbox.setText( "Hide Missing Views (" + missingCount + ")" );
		else
			hideMissingViewsCheckbox.setText( "Hide Missing Views" );

		hideMissingViewsCheckbox.setEnabled( hasMissingViews );

		// If no missing views and checkbox was checked, uncheck it
		if ( !hasMissingViews && hideMissingViewsCheckbox.isSelected() )
		{
			hideMissingViewsCheckbox.setSelected( false );
			filteredTableModel.setHideMissingViews( false );
		}
	}

	/**
	 * Gets the count of missing views in the dataset.
	 */
	private int getMissingViewsCount()
	{
		if ( getSpimData() == null || getSpimData().getSequenceDescription() == null )
			return 0;

		if ( getSpimData().getSequenceDescription().getMissingViews() == null )
			return 0;

		if ( getSpimData().getSequenceDescription().getMissingViews().getMissingViews() == null )
			return 0;

		return getSpimData().getSequenceDescription().getMissingViews().getMissingViews().size();
	}

	public static void updateBDV(final BigDataViewer bdv, final boolean colorMode, final AbstractSpimData< ? > data,
			BasicViewDescription< ? > firstVD,
			final Collection< List< BasicViewDescription< ? >> > selectedRows)
	{
		// we always set the fused mode
		BDVTools.setFusedModeSimple( bdv, data );

		if ( selectedRows == null || selectedRows.size() == 0 )
			return;

		if ( firstVD == null )
			firstVD = selectedRows.iterator().next().iterator().next();

		// always use the first timepoint
		final TimePoint firstTP = firstVD.getTimePoint();
		bdv.getViewer().setTimepoint( BDVTools.getBDVTimePointIndex( firstTP, data ) );

		// Collect setup IDs of sources that should be active
		final HashSet<Integer> activeSetupIds = new HashSet<>();

		// set selected views active
		// also check whether at least one "group" of views is a real group (not just a single, wrapped, view)
		boolean anyGrouped = false;
		for ( final List<BasicViewDescription< ? >> vds : selectedRows )
		{
			if (vds.size() > 1)
				anyGrouped = true;

			for (BasicViewDescription< ? > vd : vds)
				if ( vd.getTimePointId() == firstTP.getId() )
					activeSetupIds.add( vd.getViewSetupId() );
		}

		if ( selectedRows.size() > 1 && colorMode )
		{
			// we have grouped views
			// a.t.m. we can only group by tiles, therefore we just color by Tile
			if (anyGrouped)
			{
				Set< Class< ? extends Entity > > factors = new HashSet<>();
				factors.add( Tile.class );
				BDVTools.colorByFactors( bdv, data, factors, colorOffset );
			}
			else
				BDVTools.colorSourcesBatch( bdv, colorOffset );
		}
		else
			BDVTools.whiteSourcesBatch( bdv );

		// Use batch API for setting source visibility (much faster than per-source calls)
		BDVTools.setVisibleSourcesBatch( bdv, activeSetupIds );

		ScrollableBrightnessDialog.updateBrightnessPanels( bdv );
	}

	public void showInfoBox()
	{
		new ViewSetupExplorerInfoBox< AS >( data );
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
		table.addKeyListener( new KeyListener()
		{
			@Override
			public void keyPressed( final KeyEvent arg0 )
			{
				if ( arg0.getKeyChar() == 'c' || arg0.getKeyChar() == 'C' )
				{
					colorMode = !colorMode;

					System.out.println( "colormode" );

					if (colorMode)
					{
						// cycle between color schemes
						colorOffset = (colorOffset + 1) % 5;
					}
					final BasicBDVPopup p = runningBdvPopup();
					if ( p != null && p.getBDV() != null && p.getBDV().getViewerFrame().isVisible() )
						updateBDV( p.getBDV(), colorMode, data, null, selectedRows );
				}
			}

			@Override
			public void keyReleased( final KeyEvent arg0 ) {}

			@Override
			public void keyTyped( final KeyEvent arg0 ) {}
		} );
	}

	protected String getHelpHtml() { return "/mvr/Help.html"; }

	private static class CheckBoxRenderer extends JCheckBox implements TableCellRenderer
	{
		private static final long serialVersionUID = 1L;

		public CheckBoxRenderer()
		{
			super();
			this.setHorizontalAlignment( JLabel.CENTER );
		}

		@Override
		public Component getTableCellRendererComponent(
			final JTable table,
			final Object value,
			final boolean isSelected,
			final boolean hasFocus,
			final int row,
			final int col )
		{
			boolean v = (boolean)value;
			this.setSelected( v );

			if (isSelected)
			{
				setForeground( table.getSelectionForeground() );
				setBackground( table.getSelectionBackground() );
			}
			else
			{
				setForeground( table.getForeground() );
				setBackground( table.getBackground() );
			}

			return this;
		}
	}

	@Override
	public ArrayList< ExplorerWindowSetable > initPopups()
	{
		final ArrayList< ExplorerWindowSetable > popups = new ArrayList< ExplorerWindowSetable >();

		popups.add( new LabelPopUp( " Display/Verify" ) );
		popups.add( BDVPopup.useLazyMode ? new LazyBDVPopup() : new BDVPopup() );
		popups.add( new DisplayRawImagesPopup() );
		popups.add( new DisplayFusedImagesPopup() );
		popups.add( new VisualizeNonRigid() );
		popups.add( new MaxProjectPopup() );
		popups.add( new Separator() );

		popups.add( new LabelPopUp( " Processing" ) );
		popups.add( new DetectInterestPointsPopup() );
		popups.add( new RegisterInterestPointsPopup() );
		popups.add( new IntensityAdjustmentPopup() );
		popups.add( new BoundingBoxPopup() );
		popups.add( new FusionPopup() );
		popups.add( new PointSpreadFunctionsPopup() );
		popups.add( new DeconvolutionPopup() );
		popups.add( new Separator() );

		popups.add( new LabelPopUp( " Quality" ) );
		popups.add( new QualityPopup() );
		popups.add( new Separator() );

		popups.add( new LabelPopUp( " Calibration/Transformations" ) );
		popups.add( new RegistrationExplorerPopup() );
		popups.add( new SpecifyCalibrationPopup() );
		popups.add( new ApplyTransformationPopup() );
		popups.add( new BakeManualTransformationPopup() );
		popups.add( new RemoveTransformationPopup() );
		popups.add( new ReorientSamplePopup() );
		popups.add( new Separator() );

		popups.add( new LabelPopUp( " Interest Points" ) );
		popups.add( new InterestPointsExplorerPopup() );
		popups.add( new AnalyzeErrorPopup() );
		popups.add( new RemoveDetectionsPopup() );
		popups.add( new VisualizeDetectionsPopup() );
		popups.add( new Separator() );

		popups.add( new LabelPopUp( " Modifications" ) );
		popups.add( new ResavePopup() );
		popups.add( new FlatFieldCorrectionPopup() );
		popups.add( new MissingViewsPopup() );

		popups.add( new Separator() );

		// add link to wiki
		popups.add( new LabelPopUp( "Help" ) );
		popups.add( new SimpleHyperlinkPopup("Browse Wiki...", URITools.toURI( "https://imagej.net/Multiview-Reconstruction" )) );

		return popups;
	}
}
