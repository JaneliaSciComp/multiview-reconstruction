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
package net.preibisch.mvrecon.fiji.spimdata.explorer.analyzeerror;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;

import bdv.AbstractSpimSource;
import bdv.BigDataViewer;
import bdv.tools.brightness.ConverterSetup;
import bdv.tools.transformation.TransformedSource;
import bdv.viewer.ConverterSetups;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import ij.gui.GUI;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.type.numeric.ARGBType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.bdv.BDVColors;
import net.preibisch.mvrecon.fiji.spimdata.explorer.analyzeerror.AnalyzeErrorsUtil.GroupPairResult;
import net.preibisch.mvrecon.fiji.spimdata.explorer.analyzeerror.AnalyzeErrorsUtil.Mode;
import net.preibisch.mvrecon.fiji.spimdata.explorer.analyzeerror.AnalyzeErrorsUtil.Parameters;
import net.preibisch.mvrecon.fiji.spimdata.explorer.analyzeerror.AnalyzeErrorsUtil.SingleGroupError;
import net.preibisch.mvrecon.fiji.spimdata.explorer.analyzeerror.AnalyzeErrorsUtil.SingleViewError;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ViewSetupExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BasicBDVPopup;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.ViewNeighbors;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

/**
 * Sortable results browser for {@link AnalyzeErrorsUtil}.
 *
 * Two modes (toggled via the top combo box):
 * <ul>
 *   <li><b>Pair mode</b>: one row per (vidA, vidB) pair (or per group-pair when grouped).</li>
 *   <li><b>Single view mode</b>: one row per view (or per group when grouped) with all of its
 *       connections aggregated. Click cycles through three highlight states in BDV:
 *       <ul>
 *         <li>state 1 (white row): just the view (green)</li>
 *         <li>state 2 (yellow row): view + connected (green + magenta)</li>
 *         <li>state 3 (red row): view + connected + overlapping (green + red + light blue)</li>
 *       </ul>
 *       Cycle wraps; switching to another row resets the previous row to default.</li>
 * </ul>
 *
 * In grouped + single-view mode the "actual view" is the whole group; connected groups
 * contribute their views; overlapping is the union of geometric neighbours of any view
 * in the group.
 *
 * Right-click on a grouped row drills down to an ungrouped sub-window restricted to that
 * row's group/group-pair, preserving the parent's mode.
 */
public class AnalyzeErrorsResultsWindow extends JFrame
{
	private static final long serialVersionUID = 1L;

	private static final NumberFormat FMT = new DecimalFormat( "#.####" );

	private static final Color ROW_STATE_2_BG = new Color( 255, 245, 160 ); // yellow
	private static final Color ROW_STATE_3_BG = new Color( 255, 200, 200 ); // light red

	private static final ARGBType COLOR_ACTUAL_STATE1 = BDVColors.GRAY;
	private static final ARGBType COLOR_ACTUAL      = BDVColors.GREEN;
	private static final ARGBType COLOR_CONNECTED_2 = BDVColors.MAGENTA;
	private static final ARGBType COLOR_CONNECTED_3 = BDVColors.MAGENTA; // same as state 2; state 3 only adds overlap
	private static final ARGBType COLOR_OVERLAP     = BDVColors.LIGHTBLUE;

	/** Controls when row clicks recentre BDV. */
	public enum BdvResetPolicy { NEVER, NEW_VIEW_SELECTED, SWITCHING_STATES }
	public static BdvResetPolicy defaultBdvResetPolicy = BdvResetPolicy.NEW_VIEW_SELECTED;

	// Pair-mode columns
	private static final int PCOL_A = 0, PCOL_B = 1, PCOL_COUNT = 2, PCOL_NUM_CORR = 3, PCOL_MIN = 4, PCOL_AVG = 5, PCOL_MAX = 6;
	// Single-view-mode columns
	private static final int SCOL_VIEW = 0, SCOL_CONN = 1, SCOL_COUNT = 2, SCOL_NUM_CORR = 3, SCOL_MIN = 4, SCOL_AVG = 5, SCOL_MAX = 6;

	private final SpimData2 data;
	private final ArrayList< AnalyzeErrorsUtil.PairError > errors;
	private final Parameters params;
	private final ViewSetupExplorerPanel< ? > panel;

	private final JScrollPane scroll;

	// Current table state
	private JTable table;
	private List< ? > currentRows; // either List<PairRow> or List<SingleRow>

	// Click-cycle state (single-view mode only). Tracks the model-row index in currentRows.
	private int activeRowModelIdx = -1;
	private int activeClickState = 0; // 0 = none, 1/2/3 = active

	// Cached map ViewId → universe-level Group (computed lazily in grouped single-view mode
	// to expand the errors-derived actual/connected/overlap sets to full group members).
	private Map< ViewId, Group< ViewDescription > > universeGroupMapCache = null;

	// When to recentre BDV on row click; per-window, sticky default updated when changed.
	private BdvResetPolicy bdvResetPolicy = defaultBdvResetPolicy;

	// Universe used to compute geometric overlap (state 3). Set at construction to the
	// view-set that was passed to AnalyzeErrors (selected views; or all views when the
	// "Include all views" checkbox was on). Pluggable: a future option can swap this for
	// the dataset's whole view-set.
	private final java.util.Collection< ? extends ViewId > overlapUniverse;

	public AnalyzeErrorsResultsWindow(
			final SpimData2 data,
			final ArrayList< AnalyzeErrorsUtil.PairError > errors,
			final Parameters params,
			final ViewSetupExplorerPanel< ? > panel,
			final java.util.Collection< ? extends ViewId > analyzedViews )
	{
		super( buildTitle( params ) );
		this.data = data;
		this.errors = errors;
		this.params = params;
		this.panel = panel;
		this.overlapUniverse = analyzedViews;

		setDefaultCloseOperation( DISPOSE_ON_CLOSE );

		this.scroll = new JScrollPane();

		getContentPane().setLayout( new BorderLayout() );
		getContentPane().add( scroll, BorderLayout.CENTER );
		getContentPane().add( buildStatusBar(), BorderLayout.SOUTH );

		if ( params.mode == Mode.PAIR )
			buildPairTable();
		else
			buildSingleViewTable();
		scroll.setViewportView( table );

		installRecenterShortcut();

		setSize( 980, 500 );
		GUI.center( this );
		setVisible( true );
	}

	/** 'r'/'R' on the table re-centers BDV on the explorer's current selection. Mirrors
	 *  the same shortcut on {@code FilteredAndGroupedExplorerPanel}. */
	private void installRecenterShortcut()
	{
		table.addKeyListener( new KeyAdapter()
		{
			@Override
			public void keyPressed( final KeyEvent e )
			{
				if ( e.getKeyChar() != 'r' && e.getKeyChar() != 'R' )
					return;
				final BasicBDVPopup pop = panel.runningBdvPopup();
				if ( pop == null || pop.getBDV() == null || !pop.getBDV().getViewerFrame().isVisible() )
					return;
				TransformationTools.reCenterViews( pop.getBDV(),
						panel.selectedRows.stream().collect(
								HashSet< BasicViewDescription< ? > >::new,
								( a, b ) -> a.addAll( b ), ( a, b ) -> a.addAll( b ) ),
						panel.getSpimData().getViewRegistrations() );
			}
		} );
	}

	private JComponent buildStatusBar()
	{
		final JPanel bar = new JPanel( new BorderLayout( 12, 0 ) );
		bar.setBorder( javax.swing.BorderFactory.createCompoundBorder(
				javax.swing.BorderFactory.createMatteBorder( 1, 0, 0, 0, Color.LIGHT_GRAY ),
				javax.swing.BorderFactory.createEmptyBorder( 4, 8, 4, 8 ) ) );

		bar.add( new JLabel( buildLegendHtml() ), BorderLayout.WEST );

		final JPanel resetPanel = new JPanel( new java.awt.FlowLayout( java.awt.FlowLayout.RIGHT, 4, 0 ) );
		resetPanel.add( new JLabel( "Reset BDV:" ) );
		final String[] choices = { "Never", "New view selected", "Switching states" };
		final javax.swing.JComboBox< String > combo = new javax.swing.JComboBox<>( choices );
		combo.setSelectedIndex( bdvResetPolicy == BdvResetPolicy.NEVER ? 0
				: bdvResetPolicy == BdvResetPolicy.NEW_VIEW_SELECTED ? 1 : 2 );
		combo.addActionListener( e -> {
			switch ( combo.getSelectedIndex() )
			{
				case 0: bdvResetPolicy = BdvResetPolicy.NEVER; break;
				case 1: bdvResetPolicy = BdvResetPolicy.NEW_VIEW_SELECTED; break;
				default: bdvResetPolicy = BdvResetPolicy.SWITCHING_STATES; break;
			}
			defaultBdvResetPolicy = bdvResetPolicy;
		} );
		resetPanel.add( combo );
		bar.add( resetPanel, BorderLayout.EAST );

		return bar;
	}

	private String buildLegendHtml()
	{
		final String drillHint = params.anyGroupingSelected()
				? " Right-click grouped row to drill down."
				: "";
		if ( params.mode == Mode.PAIR )
		{
			return "<html>Click a row to select views &amp; re-center BDV." + drillHint + "</html>";
		}
		// Single-view legend: 3 states, swatches matching BDV/row colours.
		return "<html>Row click cycles [1&rarr;2&rarr;3] "
				+ "&middot; <font color='#87CEFA'><b>1:</b> selected</font>(<font color='#A0A0A0'>&#9632;</font>) "
				+ "&middot; <font color='#BBA200'><b>2:</b> +connected</font>(<font color='#00D200'>&#9632;</font><font color='#D200D2'>&#9632;</font>) "
				+ "&middot; <font color='#AA0200'><b>3:</b> +overlapping</font>(<font color='#00D200'>&#9632;</font><font color='#D200D2'>&#9632;</font><font color='#87CEFA'>&#9632;</font>)."
				+ drillHint + "</html>";
	}//<font color='#FF8080'>&#9632;</font>
	//(row <font color='#FF8080'>&#9632;</font>): +overlap <font color='#87CEFA'>&#9632;</font>

	// ===================================================================================
	// Pair mode
	// ===================================================================================

	private void buildPairTable()
	{
		final List< PairRow > rows = buildPairRows( data, errors, params );
		this.currentRows = rows;

		final PairTableModel model = new PairTableModel( rows );
		this.table = new JTable( model );
		final TableRowSorter< PairTableModel > sorter = new TableRowSorter<>( model );
		table.setRowSorter( sorter );
		sorter.setSortKeys( java.util.Collections.singletonList(
				new javax.swing.RowSorter.SortKey( PCOL_AVG, SortOrder.DESCENDING ) ) );

		final DefaultTableCellRenderer right = rightAligned();
		table.getColumnModel().getColumn( PCOL_COUNT ).setCellRenderer( right );
		table.getColumnModel().getColumn( PCOL_NUM_CORR ).setCellRenderer( right );

		final TableCellRenderer errorRenderer = errorRenderer();
		table.getColumnModel().getColumn( PCOL_MIN ).setCellRenderer( errorRenderer );
		table.getColumnModel().getColumn( PCOL_AVG ).setCellRenderer( errorRenderer );
		table.getColumnModel().getColumn( PCOL_MAX ).setCellRenderer( errorRenderer );

		table.getColumnModel().getColumn( PCOL_A ).setPreferredWidth( 280 );
		table.getColumnModel().getColumn( PCOL_B ).setPreferredWidth( 280 );
		table.getColumnModel().getColumn( PCOL_COUNT ).setPreferredWidth( 60 );
		table.getColumnModel().getColumn( PCOL_NUM_CORR ).setPreferredWidth( 70 );
		table.getColumnModel().getColumn( PCOL_MIN ).setPreferredWidth( 90 );
		table.getColumnModel().getColumn( PCOL_AVG ).setPreferredWidth( 90 );
		table.getColumnModel().getColumn( PCOL_MAX ).setPreferredWidth( 90 );

		table.setSelectionMode( ListSelectionModel.SINGLE_SELECTION );
		table.getSelectionModel().addListSelectionListener( e -> {
			if ( e.getValueIsAdjusting() ) return;
			final int viewRow = table.getSelectedRow();
			if ( viewRow < 0 ) return;
			final PairRow r = rows.get( table.convertRowIndexToModel( viewRow ) );

			// In pair mode every click is "a new view"; only Never disables recentre.
			final boolean recenter = bdvResetPolicy != BdvResetPolicy.NEVER;

			// off-EDT: AnalyzeErrorsUtil.selectViewsAndRecenter uses threadWait
			new Thread( () ->
					AnalyzeErrorsUtil.selectViewsAndRecenter( panel, params, r.viewsForBDV, recenter ) ).start();
		} );

		// Right-click drill-down (grouped pair-pair rows only)
		if ( params.anyGroupingSelected() )
			installPopup( table,
					() -> ( PairRow ) currentRows.get( table.convertRowIndexToModel( table.getSelectedRow() ) ),
					row -> {
						if ( row.groupA == null || row.groupB == null )
							return;
						openPairSubWindow( row );
					},
					"Show ungrouped pairs in this group-pair" );
	}

	private void openPairSubWindow( final PairRow clickedRow )
	{
		final HashSet< ViewId > setA = new HashSet<>();
		for ( final ViewDescription vd : clickedRow.groupA.getViews() ) setA.add( vd );
		final HashSet< ViewId > setB = new HashSet<>();
		for ( final ViewDescription vd : clickedRow.groupB.getViews() ) setB.add( vd );

		final ArrayList< AnalyzeErrorsUtil.PairError > filtered = new ArrayList<>();
		for ( final AnalyzeErrorsUtil.PairError e : errors )
		{
			if ( ( setA.contains( e.a ) && setB.contains( e.b ) )
					|| ( setA.contains( e.b ) && setB.contains( e.a ) ) )
				filtered.add( e );
		}

		final Parameters child = ungroupedClone( params );
		child.mode = Mode.PAIR;
		new AnalyzeErrorsResultsWindow( data, filtered, child, panel, overlapUniverse );
	}

	// ===================================================================================
	// Single-view mode
	// ===================================================================================

	private void buildSingleViewTable()
	{
		final List< SingleRow > rows = buildSingleRows( data, errors, params );
		this.currentRows = rows;

		final SingleTableModel model = new SingleTableModel( rows );
		this.table = new JTable( model )
		{
			private static final long serialVersionUID = 1L;
			@Override
			public String getToolTipText( final MouseEvent e )
			{
				final java.awt.Point p = e.getPoint();
				final int viewRow = rowAtPoint( p );
				final int viewCol = columnAtPoint( p );
				if ( viewRow < 0 || viewCol < 0 )
					return null;
				final int modelCol = convertColumnIndexToModel( viewCol );
				if ( modelCol == SCOL_CONN )
				{
					final SingleRow sr = rows.get( convertRowIndexToModel( viewRow ) );
					if ( sr.connectedLabels == null || sr.connectedLabels.isEmpty() )
						return null;
					return "<html>" + String.join( "<br>", sr.connectedLabels ) + "</html>";
				}
				return super.getToolTipText( e );
			}
		};

		final TableRowSorter< SingleTableModel > sorter = new TableRowSorter<>( model );
		table.setRowSorter( sorter );
		sorter.setSortKeys( java.util.Collections.singletonList(
				new javax.swing.RowSorter.SortKey( SCOL_AVG, SortOrder.DESCENDING ) ) );

		// Custom renderer that paints the active row's background based on click state.
		final TableCellRenderer baseRight = rightAligned();
		final TableCellRenderer baseError = errorRenderer();
		final TableCellRenderer rowAwareDefault = rowStateRenderer( null );
		final TableCellRenderer rowAwareRight   = rowStateRenderer( baseRight );
		final TableCellRenderer rowAwareError   = rowStateRenderer( baseError );

		table.getColumnModel().getColumn( SCOL_VIEW ).setCellRenderer( rowAwareDefault );
		table.getColumnModel().getColumn( SCOL_CONN ).setCellRenderer( rowAwareDefault );
		table.getColumnModel().getColumn( SCOL_COUNT ).setCellRenderer( rowAwareRight );
		table.getColumnModel().getColumn( SCOL_NUM_CORR ).setCellRenderer( rowAwareRight );
		table.getColumnModel().getColumn( SCOL_MIN ).setCellRenderer( rowAwareError );
		table.getColumnModel().getColumn( SCOL_AVG ).setCellRenderer( rowAwareError );
		table.getColumnModel().getColumn( SCOL_MAX ).setCellRenderer( rowAwareError );

		table.getColumnModel().getColumn( SCOL_VIEW ).setPreferredWidth( 240 );
		table.getColumnModel().getColumn( SCOL_CONN ).setPreferredWidth( 320 );
		table.getColumnModel().getColumn( SCOL_COUNT ).setPreferredWidth( 60 );
		table.getColumnModel().getColumn( SCOL_NUM_CORR ).setPreferredWidth( 70 );
		table.getColumnModel().getColumn( SCOL_MIN ).setPreferredWidth( 90 );
		table.getColumnModel().getColumn( SCOL_AVG ).setPreferredWidth( 90 );
		table.getColumnModel().getColumn( SCOL_MAX ).setPreferredWidth( 90 );

		table.setSelectionMode( ListSelectionModel.SINGLE_SELECTION );
		// Use a MouseListener (not SelectionListener) so reclicking the same row advances the
		// cycle — selection-changed wouldn't fire when the row is already selected.
		table.addMouseListener( new MouseAdapter()
		{
			@Override
			public void mouseClicked( final MouseEvent e )
			{
				if ( !SwingUtilities.isLeftMouseButton( e ) ) return;
				final int viewRow = table.rowAtPoint( e.getPoint() );
				if ( viewRow < 0 ) return;
				final int modelIdx = table.convertRowIndexToModel( viewRow );
				handleSingleViewRowClick( modelIdx, rows.get( modelIdx ) );
				table.repaint();
			}
		} );

		// Right-click drill-down (grouped single-view rows only)
		if ( params.anyGroupingSelected() )
			installPopup( table,
					() -> ( SingleRow ) currentRows.get( table.convertRowIndexToModel( table.getSelectedRow() ) ),
					row -> {
						if ( row.group == null )
							return;
						openSingleSubWindow( row );
					},
					"Show ungrouped views in this group" );
	}

	private void handleSingleViewRowClick( final int modelIdx, final SingleRow row )
	{
		final boolean rowChanged = ( modelIdx != activeRowModelIdx );

		// Cycle wrap; switching row resets to state 1
		if ( rowChanged )
		{
			activeRowModelIdx = modelIdx;
			activeClickState = 1;
		}
		else
		{
			activeClickState = ( activeClickState % 3 ) + 1;
		}

		// Build category sets based on state. In grouped mode, expand each set to include
		// all members of the universe-level group for any view in the set — so grouping is
		// the unit of analysis (one stray AABB hit pulls in the whole group, etc.).
		final boolean grouped = ( row.group != null );
		final Set< ViewId > actual    = grouped ? expandToFullGroups( row.actualViews ) : row.actualViews;
		final Set< ViewId > connected = ( activeClickState >= 2 )
				? ( grouped ? expandToFullGroups( row.connectedAllViews ) : row.connectedAllViews )
				: java.util.Collections.emptySet();
		final Set< ViewId > overlapRaw = ( activeClickState >= 3 ) ? lookupOverlap( actual )
				: java.util.Collections.emptySet();
		final Set< ViewId > overlap   = grouped ? expandToFullGroups( overlapRaw ) : overlapRaw;

		final HashSet< ViewId > visible = new HashSet<>();
		visible.addAll( actual );
		visible.addAll( connected );
		visible.addAll( overlap );

		// Per-setupId category color map
		final Map< Integer, ARGBType > colorBySetupId = new HashMap<>();
		final ARGBType connectedColor = ( activeClickState == 2 ) ? COLOR_CONNECTED_2 : COLOR_CONNECTED_3;
		// connected first (so actual overrides if any overlap)
		for ( final ViewId v : connected )
			colorBySetupId.put( v.getViewSetupId(), connectedColor );
		for ( final ViewId v : overlap )
			colorBySetupId.putIfAbsent( v.getViewSetupId(), COLOR_OVERLAP );
		// actual overrides everything; gray at state 1, green when contrasted with categories
		final ARGBType actualColor = ( activeClickState == 1 ) ? COLOR_ACTUAL_STATE1 : COLOR_ACTUAL;
		for ( final ViewId v : actual )
			colorBySetupId.put( v.getViewSetupId(), actualColor );

		final boolean recenter = shouldRecenter( rowChanged );

		// Drive explorer selection off-EDT (uses threadWait), then apply colors.
		new Thread( () -> {
			AnalyzeErrorsUtil.selectViewsAndRecenter( panel, params, visible, recenter );
			SwingUtilities.invokeLater( () -> applyCategoryColors( colorBySetupId ) );
		} ).start();
	}

	private boolean shouldRecenter( final boolean rowChanged )
	{
		switch ( bdvResetPolicy )
		{
			case NEVER:             return false;
			case NEW_VIEW_SELECTED: return rowChanged;
			default:                return true; // SWITCHING_STATES
		}
	}

	private void openSingleSubWindow( final SingleRow clickedRow )
	{
		final HashSet< ViewId > inGroup = new HashSet<>();
		for ( final ViewDescription vd : clickedRow.group.getViews() ) inGroup.add( vd );

		final ArrayList< AnalyzeErrorsUtil.PairError > filtered = new ArrayList<>();
		for ( final AnalyzeErrorsUtil.PairError e : errors )
		{
			// keep pairs with at least one endpoint in this group (so the sub-window
			// shows each in-group view as a row with its connections)
			if ( inGroup.contains( e.a ) || inGroup.contains( e.b ) )
				filtered.add( e );
		}

		final Parameters child = ungroupedClone( params );
		child.mode = Mode.SINGLE_VIEW;
		new AnalyzeErrorsResultsWindow( data, filtered, child, panel, overlapUniverse );
	}

	private Set< ViewId > lookupOverlap( final Set< ViewId > forViews )
	{
		// Per-click query: AABB broad-phase scan over the universe + SAT narrow-phase on
		// candidates. No precomputed full-graph cache (would be O(N²) and prohibitive on
		// 200k-view datasets).
		return ViewNeighbors.overlappingFor( data, forViews, overlapUniverse );
	}

	/** Build (lazily) the ViewId → universe-level Group map for the current grouping factors. */
	private Map< ViewId, Group< ViewDescription > > universeGroupMap()
	{
		if ( universeGroupMapCache == null )
		{
			final java.util.Set< Class< ? extends Entity > > factors = new HashSet<>();
			if ( params.groupTiles )         factors.add( Tile.class );
			if ( params.groupChannels )      factors.add( Channel.class );
			if ( params.groupIlluminations ) factors.add( Illumination.class );
			if ( params.groupAngles )        factors.add( Angle.class );

			final java.util.List< ViewDescription > vds = new ArrayList<>();
			for ( final ViewId vid : overlapUniverse )
			{
				final ViewDescription vd = data.getSequenceDescription().getViewDescriptions().get( vid );
				if ( vd != null )
					vds.add( vd );
			}
			final java.util.List< Group< ViewDescription > > groups = Group.combineBy( vds, factors );
			universeGroupMapCache = new HashMap<>();
			for ( final Group< ViewDescription > g : groups )
				for ( final ViewDescription vd : g.getViews() )
					universeGroupMapCache.put( vd, g );
		}
		return universeGroupMapCache;
	}

	/** Expand each input view to include all members of its universe-level Group. */
	private Set< ViewId > expandToFullGroups( final Set< ViewId > raw )
	{
		final Map< ViewId, Group< ViewDescription > > ugm = universeGroupMap();
		final HashSet< ViewId > expanded = new HashSet<>();
		for ( final ViewId v : raw )
		{
			final Group< ViewDescription > g = ugm.get( v );
			if ( g != null )
				for ( final ViewDescription vd : g.getViews() )
					expanded.add( vd );
			else
				expanded.add( v );
		}
		return expanded;
	}

	private void applyCategoryColors( final Map< Integer, ARGBType > colorBySetupId )
	{
		BDVColors.applyCategoryColors( panel.runningBdvPopup(), colorBySetupId );
	}

	// ===================================================================================
	// Common helpers
	// ===================================================================================

	private static String buildTitle( final Parameters params )
	{
		final StringBuilder sb = new StringBuilder( params.anyGroupingSelected() ? "Errors [Grouped]" : "Errors" );
		if ( params.labelAndWeights != null && !params.labelAndWeights.isEmpty() )
		{
			sb.append( " ({" );
			boolean first = true;
			for ( final Map.Entry< String, Double > e : params.labelAndWeights.entrySet() )
			{
				if ( !first ) sb.append( ", " );
				first = false;
				sb.append( e.getKey() ).append( " (w=" ).append( formatWeight( e.getValue() ) ).append( ")" );
			}
			sb.append( "})" );
		}
		return sb.toString();
	}

	private static Parameters ungroupedClone( final Parameters src )
	{
		final Parameters p = new Parameters();
		p.labelAndWeights = src.labelAndWeights;
		p.groupTiles = false;
		p.groupChannels = false;
		p.groupIlluminations = false;
		p.groupAngles = false;
		p.topN = src.topN;
		p.bottomM = src.bottomM;
		p.middleK = src.middleK;
		p.useAllViews = src.useAllViews;
		p.mode = src.mode;
		return p;
	}

	private static String formatWeight( final Double w )
	{
		if ( w == null ) return "";
		final double d = w.doubleValue();
		if ( d == Math.floor( d ) && !Double.isInfinite( d ) )
			return Integer.toString( ( int ) d );
		return FMT.format( d );
	}

	private static DefaultTableCellRenderer rightAligned()
	{
		final DefaultTableCellRenderer r = new DefaultTableCellRenderer();
		r.setHorizontalAlignment( javax.swing.SwingConstants.RIGHT );
		return r;
	}

	private static TableCellRenderer errorRenderer()
	{
		return new DefaultTableCellRenderer()
		{
			private static final long serialVersionUID = 1L;
			@Override
			protected void setValue( final Object value )
			{
				setHorizontalAlignment( javax.swing.SwingConstants.RIGHT );
				setText( value == null ? "" : FMT.format( ( Double ) value ) );
			}
		};
	}

	/** Wraps a delegate renderer (or default) and tints the row background based on click state. */
	private TableCellRenderer rowStateRenderer( final TableCellRenderer delegate )
	{
		final DefaultTableCellRenderer fallback = new DefaultTableCellRenderer();
		return ( tbl, value, isSelected, hasFocus, viewRow, viewCol ) -> {
			final TableCellRenderer base = ( delegate != null ) ? delegate : fallback;
			final Component c = base.getTableCellRendererComponent( tbl, value, isSelected, hasFocus, viewRow, viewCol );
			final int modelRow = tbl.convertRowIndexToModel( viewRow );
			Color bg = null;
			if ( modelRow == activeRowModelIdx )
			{
				if ( activeClickState == 2 ) bg = ROW_STATE_2_BG;
				else if ( activeClickState == 3 ) bg = ROW_STATE_3_BG;
			}
			// Apply state color regardless of selection, so the user can see the cycle
			// state on the just-clicked (and therefore selected) row.
			if ( bg != null )
			{
				c.setBackground( bg );
				c.setForeground( Color.BLACK );
				if ( c instanceof javax.swing.JComponent )
					( ( javax.swing.JComponent ) c ).setOpaque( true );
			}
			else if ( !isSelected )
			{
				c.setBackground( tbl.getBackground() );
			}
			return c;
		};
	}

	private static < R > void installPopup( final JTable table, final java.util.function.Supplier< R > rowSupplier,
			final java.util.function.Consumer< R > onAction, final String menuLabel )
	{
		final JPopupMenu popup = new JPopupMenu();
		final JMenuItem item = new JMenuItem( menuLabel );
		item.addActionListener( ev -> {
			final int viewRow = table.getSelectedRow();
			if ( viewRow < 0 ) return;
			onAction.accept( rowSupplier.get() );
		} );
		popup.add( item );

		table.addMouseListener( new MouseAdapter()
		{
			@Override public void mousePressed( final MouseEvent e )  { maybeShow( e ); }
			@Override public void mouseReleased( final MouseEvent e ) { maybeShow( e ); }
			private void maybeShow( final MouseEvent e )
			{
				if ( !e.isPopupTrigger() ) return;
				final int viewRow = table.rowAtPoint( e.getPoint() );
				if ( viewRow < 0 ) return;
				table.setRowSelectionInterval( viewRow, viewRow );
				popup.show( e.getComponent(), e.getX(), e.getY() );
			}
		} );
	}

	// ===================================================================================
	// Pair-mode rows + table model
	// ===================================================================================

	private static List< PairRow > buildPairRows(
			final SpimData2 data,
			final ArrayList< AnalyzeErrorsUtil.PairError > errors,
			final Parameters params )
	{
		final ArrayList< PairRow > rows = new ArrayList<>();
		if ( params.anyGroupingSelected() )
		{
			for ( final GroupPairResult r : AnalyzeErrorsUtil.computeGroupPairs( data, errors, params ) )
			{
				final HashSet< BasicViewDescription< ? > > views = new HashSet<>();
				views.addAll( r.groupA.getViews() );
				views.addAll( r.groupB.getViews() );
				rows.add( new PairRow( Group.gvids( r.groupA ), Group.gvids( r.groupB ),
						r.count, r.numCorr, r.min, r.avg, r.max, views, r.groupA, r.groupB ) );
			}
		}
		else
		{
			for ( final AnalyzeErrorsUtil.PairError e : errors )
			{
				final ViewDescription vdA = data.getSequenceDescription().getViewDescriptions().get( e.a );
				final ViewDescription vdB = data.getSequenceDescription().getViewDescriptions().get( e.b );
				final HashSet< BasicViewDescription< ? > > views = new HashSet<>();
				if ( vdA != null ) views.add( vdA );
				if ( vdB != null ) views.add( vdB );
				final double err = e.errorPx;
				rows.add( new PairRow( Group.pvids( e.a ), Group.pvids( e.b ), 1, e.numCorr,
						err, err, err, views, null, null ) );
			}
		}
		return rows;
	}

	private static final class PairRow
	{
		final String labelA, labelB;
		final int count;
		final int numCorr;
		final double min, avg, max;
		final HashSet< BasicViewDescription< ? > > viewsForBDV;
		final Group< ViewDescription > groupA, groupB; // non-null only in grouped mode

		PairRow( final String labelA, final String labelB, final int count, final int numCorr,
				final double min, final double avg, final double max,
				final HashSet< BasicViewDescription< ? > > viewsForBDV,
				final Group< ViewDescription > groupA, final Group< ViewDescription > groupB )
		{
			this.labelA = labelA;
			this.labelB = labelB;
			this.count = count;
			this.numCorr = numCorr;
			this.min = min;
			this.avg = avg;
			this.max = max;
			this.viewsForBDV = viewsForBDV;
			this.groupA = groupA;
			this.groupB = groupB;
		}
	}

	private static final class PairTableModel extends AbstractTableModel
	{
		private static final long serialVersionUID = 1L;
		private static final String[] COLUMNS = { "Side A", "Side B", "Count", "# Corr", "Min (px)", "Avg (px)", "Max (px)" };
		private final List< PairRow > rows;
		PairTableModel( final List< PairRow > rows ) { this.rows = rows; }
		@Override public int getRowCount() { return rows.size(); }
		@Override public int getColumnCount() { return COLUMNS.length; }
		@Override public String getColumnName( final int c ) { return COLUMNS[ c ]; }
		@Override
		public Class< ? > getColumnClass( final int c )
		{
			switch ( c )
			{
				case PCOL_COUNT: case PCOL_NUM_CORR: return Integer.class;
				case PCOL_MIN: case PCOL_AVG: case PCOL_MAX: return Double.class;
				default: return String.class;
			}
		}
		@Override public boolean isCellEditable( final int r, final int c ) { return false; }
		@Override
		public Object getValueAt( final int r, final int c )
		{
			final PairRow row = rows.get( r );
			switch ( c )
			{
				case PCOL_A:        return row.labelA;
				case PCOL_B:        return row.labelB;
				case PCOL_COUNT:    return row.count;
				case PCOL_NUM_CORR: return row.numCorr;
				case PCOL_MIN:      return row.min;
				case PCOL_AVG:      return row.avg;
				case PCOL_MAX:      return row.max;
				default: return null;
			}
		}
	}

	// ===================================================================================
	// Single-view rows + table model
	// ===================================================================================

	private static List< SingleRow > buildSingleRows(
			final SpimData2 data,
			final ArrayList< AnalyzeErrorsUtil.PairError > errors,
			final Parameters params )
	{
		final ArrayList< SingleRow > rows = new ArrayList<>();
		if ( params.anyGroupingSelected() )
		{
			for ( final SingleGroupError sge : AnalyzeErrorsUtil.computeSingleGroupErrors( data, errors, params ) )
			{
				final HashSet< ViewId > actual = new HashSet<>();
				for ( final ViewDescription vd : sge.group.getViews() ) actual.add( vd );
				final HashSet< ViewId > connected = new HashSet<>();
				final ArrayList< String > connLabels = new ArrayList<>();
				for ( final Group< ViewDescription > g : sge.connectedGroups )
				{
					connLabels.add( Group.gvids( g ) );
					for ( final ViewDescription vd : g.getViews() ) connected.add( vd );
				}
				final HashSet< BasicViewDescription< ? > > viewsForBDV = new HashSet<>();
				for ( final ViewDescription vd : sge.group.getViews() ) viewsForBDV.add( vd );
				rows.add( new SingleRow(
						Group.gvids( sge.group ),
						summarizeLabels( connLabels ),
						connLabels,
						sge.connectedGroups.size(),
						sge.numCorr,
						sge.min, sge.avg, sge.max,
						viewsForBDV,
						actual, connected,
						sge.group ) );
			}
		}
		else
		{
			for ( final SingleViewError sve : AnalyzeErrorsUtil.computeSingleViewErrors( errors ) )
			{
				final HashSet< ViewId > actual = new HashSet<>();
				actual.add( sve.view );
				final HashSet< ViewId > connected = new HashSet<>( sve.connectedViews );
				final ArrayList< String > connLabels = new ArrayList<>( sve.connectedViews.size() );
				for ( final ViewId v : sve.connectedViews )
					connLabels.add( Group.pvids( v ) );
				final HashSet< BasicViewDescription< ? > > viewsForBDV = new HashSet<>();
				final ViewDescription vd = data.getSequenceDescription().getViewDescriptions().get( sve.view );
				if ( vd != null ) viewsForBDV.add( vd );
				rows.add( new SingleRow(
						Group.pvids( sve.view ),
						summarizeLabels( connLabels ),
						connLabels,
						sve.connectedViews.size(),
						sve.numCorr,
						sve.min, sve.avg, sve.max,
						viewsForBDV,
						actual, connected,
						null ) );
			}
		}
		return rows;
	}

	private static String summarizeLabels( final List< String > labels )
	{
		if ( labels.isEmpty() ) return "";
		final int n = labels.size();
		if ( n <= 3 ) return String.join( ", ", labels );
		return labels.get( 0 ) + ", " + labels.get( 1 ) + ", " + labels.get( 2 ) + " (+" + ( n - 3 ) + " more)";
	}

	private static final class SingleRow
	{
		final String label;
		final String connectedSummary;
		final List< String > connectedLabels;
		final int count;
		final int numCorr;
		final double min, avg, max;

		// driven into the explorer for the click-cycle
		final HashSet< BasicViewDescription< ? > > viewsForBDV;
		final HashSet< ViewId > actualViews;       // state-1 = actual
		final HashSet< ViewId > connectedAllViews; // union of connected (groups → all member views)

		final Group< ViewDescription > group; // non-null only in grouped mode

		SingleRow( final String label, final String connectedSummary, final List< String > connectedLabels,
				final int count, final int numCorr, final double min, final double avg, final double max,
				final HashSet< BasicViewDescription< ? > > viewsForBDV,
				final HashSet< ViewId > actualViews, final HashSet< ViewId > connectedAllViews,
				final Group< ViewDescription > group )
		{
			this.label = label;
			this.connectedSummary = connectedSummary;
			this.connectedLabels = connectedLabels;
			this.count = count;
			this.numCorr = numCorr;
			this.min = min;
			this.avg = avg;
			this.max = max;
			this.viewsForBDV = viewsForBDV;
			this.actualViews = actualViews;
			this.connectedAllViews = connectedAllViews;
			this.group = group;
		}
	}

	private static final class SingleTableModel extends AbstractTableModel
	{
		private static final long serialVersionUID = 1L;
		private static final String[] COLUMNS = { "View", "Connected views", "Count", "# Corr", "Min (px)", "Avg (px)", "Max (px)" };
		private final List< SingleRow > rows;
		SingleTableModel( final List< SingleRow > rows ) { this.rows = rows; }
		@Override public int getRowCount() { return rows.size(); }
		@Override public int getColumnCount() { return COLUMNS.length; }
		@Override public String getColumnName( final int c ) { return COLUMNS[ c ]; }
		@Override
		public Class< ? > getColumnClass( final int c )
		{
			switch ( c )
			{
				case SCOL_COUNT: case SCOL_NUM_CORR: return Integer.class;
				case SCOL_MIN: case SCOL_AVG: case SCOL_MAX: return Double.class;
				default: return String.class;
			}
		}
		@Override public boolean isCellEditable( final int r, final int c ) { return false; }
		@Override
		public Object getValueAt( final int r, final int c )
		{
			final SingleRow row = rows.get( r );
			switch ( c )
			{
				case SCOL_VIEW:     return row.label;
				case SCOL_CONN:     return row.connectedSummary;
				case SCOL_COUNT:    return row.count;
				case SCOL_NUM_CORR: return row.numCorr;
				case SCOL_MIN:      return row.min;
				case SCOL_AVG:      return row.avg;
				case SCOL_MAX:      return row.max;
				default: return null;
			}
		}
	}
}
