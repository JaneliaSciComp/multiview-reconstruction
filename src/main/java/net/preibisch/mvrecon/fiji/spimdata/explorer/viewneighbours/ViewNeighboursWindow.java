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
package net.preibisch.mvrecon.fiji.spimdata.explorer.viewneighbours;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.event.ListSelectionListener;

import ij.gui.GUI;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.type.numeric.ARGBType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.bdv.BDVColors;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BasicBDVPopup;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.ViewNeighbors;

/**
 * Companion window of {@link FilteredAndGroupedExplorerPanel} that highlights views in BDV which
 * are correspondence-connected to the explorer's current selection.
 *
 * Triggered from the explorer by pressing {@code n}/{@code N}. First press opens this
 * window and immediately applies; subsequent presses just re-apply with the window's
 * current parameters. Closing the window dismisses it; the next {@code n} press
 * re-opens.
 *
 * Colour scheme (matches AnalyzeErrors single-view-mode state 2):
 * <ul>
 *   <li>selection &rarr; green</li>
 *   <li>connected &rarr; magenta</li>
 * </ul>
 */
public class ViewNeighboursWindow extends JFrame
{
	private static final long serialVersionUID = 1L;

	public enum ResetPolicy { NEVER, NEW_VIEW_SELECTED, ALWAYS }
	public static ResetPolicy defaultResetPolicy = ResetPolicy.NEVER;

	private final SpimData2 data;
	private final FilteredAndGroupedExplorerPanel< ? > panel;

	private final javax.swing.JList< String > labelList;
	private final JComboBox< String > resetCombo;
	private final JLabel legend;

	private ResetPolicy resetPolicy = defaultResetPolicy;

	// Suppress auto-apply while refreshLabels() programmatically rebuilds the JList.
	private boolean suppressAutoApply = false;

	// Snapshot of last applied selection — used to decide whether NEW_VIEW_SELECTED triggers a recenter
	private Set< ViewId > lastApplied = Collections.emptySet();
	// The original "actual" set the user picked the last time we expanded. Lets a subsequent
	// Apply with no labels collapse back to that set (rather than treating the previously
	// expanded selection as the new actual).
	private Set< ViewId > lastActual = null;

	public ViewNeighboursWindow( final FilteredAndGroupedExplorerPanel< ? > panel )
	{
		super( "Neighbors" );
		this.panel = panel;
		this.data = panel.getSpimData();

		setDefaultCloseOperation( DISPOSE_ON_CLOSE );

		// --- Body: header + multi-select label list ---
		final JPanel body = new JPanel( new BorderLayout( 8, 4 ) );
		body.setBorder( BorderFactory.createEmptyBorder( 6, 8, 6, 8 ) );
		body.add( new JLabel( "Interest-point labels (select one or more):" ), BorderLayout.NORTH );

		labelList = new javax.swing.JList<>( new DefaultListModel<>() );
		labelList.setSelectionMode( javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION );
		final javax.swing.JScrollPane labelScroll = new javax.swing.JScrollPane( labelList );
		labelScroll.setPreferredSize( new java.awt.Dimension( 380, 100 ) );
		body.add( labelScroll, BorderLayout.CENTER );

		// Initialize legend up front so apply()'s legend update is safe from the moment
		// any listener that could call apply() is attached.
		legend = new JLabel();
		updateLegend( 0, 0 );

		// Initial population — must happen *before* the auto-apply listener is attached
		// so the constructor's first setSelectedIndices() doesn't trigger apply().
		refreshLabels();

		// Auto-apply when the user changes the JList selection.
		labelList.addListSelectionListener( e -> {
			if ( e.getValueIsAdjusting() ) return;
			if ( suppressAutoApply ) return;
			apply();
		} );

		// Refresh on explorer-table selection changes so labels reflect the current selection.
		final ListSelectionListener selListener = e -> {
			if ( e.getValueIsAdjusting() ) return;
			refreshLabels();
		};
		panel.table.getSelectionModel().addListSelectionListener( selListener );
		addWindowListener( new WindowAdapter()
		{
			@Override
			public void windowClosing( final WindowEvent e )
			{
				// Closing the window restores the original selection — same effect as
				// pressing 'n' again while expanded.
				if ( isExpanded() )
					collapse();
			}

			@Override
			public void windowClosed( final WindowEvent e )
			{
				panel.table.getSelectionModel().removeListSelectionListener( selListener );
			}
		} );

		// --- Status bar ---
		final JPanel statusBar = new JPanel( new BorderLayout( 12, 0 ) );
		statusBar.setBorder( BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder( 1, 0, 0, 0, Color.LIGHT_GRAY ),
				BorderFactory.createEmptyBorder( 4, 8, 4, 8 ) ) );

		statusBar.add( legend, BorderLayout.WEST );

		final JPanel resetPanel = new JPanel( new FlowLayout( FlowLayout.RIGHT, 4, 0 ) );
		resetPanel.add( new JLabel( "Reset BDV:" ) );
		final String[] resetChoices = { "Never", "New view selected", "Always" };
		resetCombo = new JComboBox<>( resetChoices );
		resetCombo.setSelectedIndex( resetPolicy == ResetPolicy.NEVER ? 0
				: resetPolicy == ResetPolicy.NEW_VIEW_SELECTED ? 1 : 2 );
		resetCombo.addActionListener( e -> {
			switch ( resetCombo.getSelectedIndex() )
			{
				case 0: resetPolicy = ResetPolicy.NEVER; break;
				case 1: resetPolicy = ResetPolicy.NEW_VIEW_SELECTED; break;
				default: resetPolicy = ResetPolicy.ALWAYS; break;
			}
			defaultResetPolicy = resetPolicy;
		} );
		resetPanel.add( resetCombo );
		statusBar.add( resetPanel, BorderLayout.EAST );

		getContentPane().setLayout( new BorderLayout() );
		getContentPane().add( body, BorderLayout.CENTER );
		getContentPane().add( statusBar, BorderLayout.SOUTH );

		setSize( 720, 280 );
		GUI.center( this );
		setVisible( true );
	}

	/**
	 * Rebuild the label list from the explorer's current selection. Falls back to all
	 * dataset views when nothing is selected. Preserves the user's previously-chosen
	 * labels (matched by string) across the refresh; defaults to the first non-warning
	 * label if no prior selection survives.
	 */
	private void refreshLabels()
	{
		// Suppress auto-apply for the duration: rebuilding the model and reselecting indices
		// fires a non-adjusting ListSelectionEvent that would otherwise re-trigger apply()
		// from inside applyExplorerSelection().
		suppressAutoApply = true;
		try
		{
			final List< ViewId > viewIds = new ArrayList<>();
			for ( final List< BasicViewDescription< ? > > row : panel.selectedRows )
				for ( final BasicViewDescription< ? > vd : row )
					viewIds.add( vd );
			if ( viewIds.isEmpty() )
				viewIds.addAll( data.getSequenceDescription().getViewDescriptions().keySet() );

			final String[] newLabels = InterestPointTools.getAllInterestPointLabels( data, viewIds );
			final Set< String > previouslySelected = new HashSet<>( labelList.getSelectedValuesList() );

			final DefaultListModel< String > model = new DefaultListModel<>();
			if ( newLabels.length == 0 )
			{
				model.addElement( "(none)" );
				labelList.setModel( model );
				return;
			}
			for ( final String l : newLabels )
				model.addElement( l );
			labelList.setModel( model );

			// Restore selection where labels still exist; otherwise pick first non-warning.
			final List< Integer > indices = new ArrayList<>();
			for ( int i = 0; i < newLabels.length; ++i )
				if ( previouslySelected.contains( newLabels[ i ] ) )
					indices.add( i );
			if ( indices.isEmpty() )
			{
				for ( int i = 0; i < newLabels.length; ++i )
					if ( !newLabels[ i ].contains( InterestPointTools.warningLabel ) )
					{
						indices.add( i );
						break;
					}
			}
			final int[] arr = new int[ indices.size() ];
			for ( int j = 0; j < arr.length; ++j ) arr[ j ] = indices.get( j );
			labelList.setSelectedIndices( arr );
		}
		finally
		{
			suppressAutoApply = false;
		}
	}

	private void updateLegend( final int actualCount, final int connectedCount )
	{
		legend.setText(
				"<html>Selection <font color='#00D200'>&#9632;</font> (" + actualCount + ") "
				+ "&middot; Connected <font color='#D200D2'>&#9632;</font> (" + connectedCount + ")"
				+ " &nbsp; Press <b>n</b> in the explorer to toggle.</html>" );
	}

	/**
	 * Toggle expand ↔ collapse for the explorer's `n`/`N` shortcut.
	 * <p>
	 * If the explorer's current selection equals what we last expanded to (and that's
	 * different from the original anchor), collapse back to {@code lastActual}.
	 * Otherwise, run a fresh {@link #apply()} (expand).
	 */
	public void toggle()
	{
		final HashSet< ViewId > currentExplorerVids = new HashSet<>();
		for ( final List< BasicViewDescription< ? > > row : panel.selectedRows )
			for ( final BasicViewDescription< ? > vd : row )
				currentExplorerVids.add( vd );

		final boolean isExpandedState =
				lastActual != null
				&& !lastActual.equals( lastApplied )
				&& currentExplorerVids.equals( lastApplied );
		if ( isExpandedState )
			collapse();
		else
			apply();
	}

	/** True iff this window is currently in an "expanded" state (the explorer's
	 *  selection equals the last expanded set, which differs from the original anchor). */
	public boolean isExpanded()
	{
		if ( lastActual == null || lastActual.equals( lastApplied ) )
			return false;
		final HashSet< ViewId > currentExplorerVids = new HashSet<>();
		for ( final List< BasicViewDescription< ? > > row : panel.selectedRows )
			for ( final BasicViewDescription< ? > vd : row )
				currentExplorerVids.add( vd );
		return currentExplorerVids.equals( lastApplied );
	}

	/** Drive selection back to {@code lastActual} and reset BDV colours of the
	 *  previously highlighted views. */
	public void collapse()
	{
		if ( lastActual == null || lastActual.isEmpty() )
			return;
		final HashSet< ViewId > targetSet = new HashSet<>( lastActual );

		applyExplorerSelection( targetSet );

		// Recenter according to policy.
		final boolean newSelection = !targetSet.equals( lastApplied );
		final boolean recenter =
				resetPolicy == ResetPolicy.ALWAYS
				|| ( resetPolicy == ResetPolicy.NEW_VIEW_SELECTED && newSelection );
		if ( recenter )
			recenterBdv();

		// Reset to white: every previously-highlighted view (lastApplied), incl. the actual ones.
		final ARGBType white = new ARGBType( ARGBType.rgba( 255, 255, 255, 255 ) );
		final Map< Integer, ARGBType > colorBySetupId = new HashMap<>();
		for ( final ViewId v : lastApplied )
			colorBySetupId.put( v.getViewSetupId(), white );
		SwingUtilities.invokeLater( () ->
				BDVColors.applyCategoryColors( panel.runningBdvPopup(), colorBySetupId ) );

		// Mark as collapsed: lastApplied == lastActual → next toggle re-expands.
		lastApplied = new HashSet<>( lastActual );

		updateLegend( lastActual.size(), 0 );
	}

	/**
	 * Compute the connected views for the explorer's current selection using the window's
	 * configured label, drive the explorer selection to {@code selection ∪ connected},
	 * and paint per-source category colours in BDV.
	 */
	public void apply()
	{
		// Read all selected labels from the JList; strip "(WARNING…)" suffixes.
		final List< String > rawSelected = labelList.getSelectedValuesList();
		final HashMap< String, Double > labelMap = new HashMap<>();
		for ( final String raw : rawSelected )
		{
			if ( raw == null || raw.startsWith( "(" ) ) continue;
			String stripped = raw;
			if ( stripped.contains( InterestPointTools.warningLabel ) )
				stripped = stripped.substring( 0, stripped.indexOf( InterestPointTools.warningLabel ) );
			labelMap.put( stripped, 1.0 );
		}

		// Flatten panel.selectedRows into a ViewId set; this is the explorer's *current*
		// selection. If it equals what we last expanded to, treat the original actual
		// (lastActual) as the actual — this lets unselecting a label collapse back instead
		// of swallowing the previous expansion as the new starting point.
		final HashSet< ViewId > currentExplorerVids = new HashSet<>();
		for ( final List< BasicViewDescription< ? > > row : panel.selectedRows )
			for ( final BasicViewDescription< ? > vd : row )
				currentExplorerVids.add( vd );

		final HashSet< ViewId > actualVids;
		if ( lastActual != null && currentExplorerVids.equals( lastApplied ) )
			actualVids = new HashSet<>( lastActual );
		else
			actualVids = currentExplorerVids;

		if ( actualVids.isEmpty() )
		{
			updateLegend( 0, 0 );
			return;
		}

		final Collection< ViewId > universe =
				new ArrayList<>( data.getSequenceDescription().getViewDescriptions().keySet() );

		// No labels picked → collapse: connected = empty, visible = actualVids.
		final Set< ViewId > connected = labelMap.isEmpty()
				? Collections.emptySet()
				: ViewNeighbors.connectedFor( data, actualVids, universe, labelMap );

		final HashSet< ViewId > visible = new HashSet<>();
		visible.addAll( actualVids );
		visible.addAll( connected );

		// Drive explorer selection (which also drives lazy-BDV source add/remove).
		applyExplorerSelection( visible );

		// Recenter according to policy.
		final boolean newSelection = !visible.equals( lastApplied );
		final boolean recenter =
				resetPolicy == ResetPolicy.ALWAYS
				|| ( resetPolicy == ResetPolicy.NEW_VIEW_SELECTED && newSelection );
		if ( recenter )
			recenterBdv();

		// Build per-setupId colour map and paint after the explorer's selection-listener
		// has had a chance to update BDV state (lazy add/remove). Views that were highlighted
		// in the previous apply but are now removed get reset to white so they don't linger
		// magenta in eager BDV mode.
		final Map< Integer, ARGBType > colorBySetupId = new HashMap<>();
		for ( final ViewId v : lastApplied )
			if ( !visible.contains( v ) )
				colorBySetupId.put( v.getViewSetupId(), new ARGBType( ARGBType.rgba( 255, 255, 255, 255 ) ) );
		for ( final ViewId v : connected )
			colorBySetupId.put( v.getViewSetupId(), BDVColors.MAGENTA );
		for ( final ViewId v : actualVids )
			colorBySetupId.put( v.getViewSetupId(), BDVColors.GREEN ); // overrides connected if any clash
		SwingUtilities.invokeLater( () ->
				BDVColors.applyCategoryColors( panel.runningBdvPopup(), colorBySetupId ) );

		lastApplied = visible;
		lastActual = new HashSet<>( actualVids );

		updateLegend( actualVids.size(), connected.size() );
	}

	private void applyExplorerSelection( final Set< ViewId > vids )
	{
		final HashSet< Long > targets = new HashSet<>();
		for ( final ViewId v : vids )
			targets.add( ( ( long ) v.getTimePointId() << 32 ) | ( v.getViewSetupId() & 0xffffffffL ) );

		final List< ? extends List< ? extends BasicViewDescription< ? > > > elements =
				panel.getTableModel().getElements();

		panel.table.clearSelection();
		int firstMatch = -1;
		for ( int r = 0; r < elements.size(); r++ )
		{
			boolean matches = false;
			for ( final BasicViewDescription< ? > vd : elements.get( r ) )
			{
				final long key = ( ( long ) vd.getTimePointId() << 32 ) | ( vd.getViewSetupId() & 0xffffffffL );
				if ( targets.contains( key ) ) { matches = true; break; }
			}
			if ( matches )
			{
				if ( firstMatch < 0 ) firstMatch = r;
				panel.table.addRowSelectionInterval( r, r );
			}
		}
		if ( firstMatch >= 0 )
		{
			final int row = firstMatch;
			SwingUtilities.invokeLater( () ->
					panel.table.scrollRectToVisible( panel.table.getCellRect( row, 0, true ) ) );
		}
	}

	private void recenterBdv()
	{
		final BasicBDVPopup pop = panel.runningBdvPopup();
		if ( pop == null || pop.getBDV() == null || !pop.getBDV().getViewerFrame().isVisible() )
			return;
		final HashSet< BasicViewDescription< ? > > vds = new HashSet<>();
		for ( final List< BasicViewDescription< ? > > row : panel.selectedRows )
			vds.addAll( row );
		TransformationTools.reCenterViews( pop.getBDV(), vds, data.getViewRegistrations() );
	}
}
