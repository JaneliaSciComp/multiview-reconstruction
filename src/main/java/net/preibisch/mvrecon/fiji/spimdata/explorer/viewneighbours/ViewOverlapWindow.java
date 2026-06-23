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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.awt.event.ItemListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import ij.gui.GUI;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.type.numeric.ARGBType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.bdv.BDVColors;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BasicBDVPopup;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.ViewNeighbors;

/**
 * Companion window of {@link FilteredAndGroupedExplorerPanel} that highlights views in BDV which
 * geometrically overlap the explorer's current selection.
 *
 * Triggered from the explorer by pressing {@code o}/{@code O}. Mirrors
 * {@link ViewNeighboursWindow}'s lifecycle: lazy-create on first press; toggle on
 * subsequent presses (expand ↔ collapse); "Update" button always re-applies.
 *
 * Five checkboxes govern whether overlap can be reported across each attribute axis.
 * When a box is checked the attribute may differ between the actual view and the
 * candidate; when unchecked the attribute must match.
 *
 * Colour scheme:
 * <ul>
 *   <li>selection &rarr; green</li>
 *   <li>overlap &rarr; light blue</li>
 * </ul>
 */
public class ViewOverlapWindow extends JFrame
{
	private static final long serialVersionUID = 1L;

	public enum ResetPolicy { NEVER, NEW_VIEW_SELECTED, ALWAYS }
	public static ResetPolicy defaultResetPolicy = ResetPolicy.NEVER;

	private final SpimData2 data;
	private final FilteredAndGroupedExplorerPanel< ? > panel;

	private final JCheckBox cbTile;
	private final JCheckBox cbChannel;
	private final JCheckBox cbAngle;
	private final JCheckBox cbIllumination;
	private final JCheckBox cbTimePoint;
	private final JComboBox< String > resetCombo;
	private final JLabel legend;

	private ResetPolicy resetPolicy = defaultResetPolicy;
	private Set< ViewId > lastApplied = Collections.emptySet();
	private Set< ViewId > lastActual = null;

	public ViewOverlapWindow( final FilteredAndGroupedExplorerPanel< ? > panel )
	{
		super( "Overlap" );
		this.panel = panel;
		this.data = panel.getSpimData();

		setDefaultCloseOperation( DISPOSE_ON_CLOSE );

		// --- Body: checkboxes ---
		final JPanel body = new JPanel( new BorderLayout( 0, 0 ) );
		body.setBorder( BorderFactory.createEmptyBorder( 2, 6, 2, 6 ) );

		final JPanel boxes = new JPanel( new FlowLayout( FlowLayout.LEFT, 6, 0 ) );
		boxes.add( new JLabel( "Across:" ) );
		cbTile         = new JCheckBox( "Tile",         true );
		cbChannel      = new JCheckBox( "Channel",      false );
		cbAngle        = new JCheckBox( "Angle",        true );
		cbIllumination = new JCheckBox( "Illumination", true );
		cbTimePoint    = new JCheckBox( "TimePoint",    false );
		boxes.add( cbTile );
		boxes.add( cbChannel );
		boxes.add( cbAngle );
		boxes.add( cbIllumination );
		boxes.add( cbTimePoint );
		body.add( boxes, BorderLayout.WEST );

		// --- Status bar ---
		final JPanel statusBar = new JPanel( new BorderLayout( 12, 0 ) );
		statusBar.setBorder( BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder( 1, 0, 0, 0, Color.LIGHT_GRAY ),
				BorderFactory.createEmptyBorder( 4, 8, 4, 8 ) ) );
		legend = new JLabel();
		updateLegend( 0, 0 );
		statusBar.add( legend, BorderLayout.WEST );

		// Auto-apply on any checkbox change. ItemListener fires once per state-change
		// (unlike ActionListener+ItemListener combos), so each click runs apply() exactly once.
		// Attached after legend init so apply()'s legend update is safe.
		final ItemListener autoApply = e -> apply();
		cbTile.addItemListener( autoApply );
		cbChannel.addItemListener( autoApply );
		cbAngle.addItemListener( autoApply );
		cbIllumination.addItemListener( autoApply );
		cbTimePoint.addItemListener( autoApply );

		addWindowListener( new WindowAdapter()
		{
			@Override
			public void windowClosing( final WindowEvent e )
			{
				// Closing the window restores the original selection — same effect as
				// pressing 'o' again while expanded.
				if ( isExpanded() )
					collapse();
			}
		} );

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

		// Fixed size rather than pack() so the legend doesn't elide as count digits grow.
		setSize( 720, 110 );
		GUI.center( this );
		setVisible( true );
	}

	private void updateLegend( final int actualCount, final int overlapCount )
	{
		legend.setText(
				"<html>Selection <font color='#00D200'>&#9632;</font> (" + actualCount + ") "
				+ "&middot; Overlap <font color='#5DA8E2'>&#9632;</font> (" + overlapCount + ")"
				+ " &nbsp; Press <b>o</b> in the explorer to toggle.</html>" );
	}

	/**
	 * Toggle expand ↔ collapse for the explorer's `o`/`O` shortcut. See
	 * {@link ViewNeighboursWindow#toggle()} for the semantics.
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

	public void collapse()
	{
		if ( lastActual == null || lastActual.isEmpty() )
			return;
		final HashSet< ViewId > targetSet = new HashSet<>( lastActual );
		applyExplorerSelection( targetSet );

		final boolean newSelection = !targetSet.equals( lastApplied );
		final boolean recenter =
				resetPolicy == ResetPolicy.ALWAYS
				|| ( resetPolicy == ResetPolicy.NEW_VIEW_SELECTED && newSelection );
		if ( recenter )
			recenterBdv();

		final ARGBType white = new ARGBType( ARGBType.rgba( 255, 255, 255, 255 ) );
		final Map< Integer, ARGBType > colorBySetupId = new HashMap<>();
		for ( final ViewId v : lastApplied )
			colorBySetupId.put( v.getViewSetupId(), white );
		SwingUtilities.invokeLater( () ->
				BDVColors.applyCategoryColors( panel.runningBdvPopup(), colorBySetupId ) );

		lastApplied = new HashSet<>( lastActual );

		updateLegend( lastActual.size(), 0 );
	}

	/**
	 * Run the geometric-overlap query against the panel's selection (or {@code lastActual}
	 * when re-applying), filter the universe by the attribute checkboxes, and paint
	 * per-source category colours in BDV.
	 */
	public void apply()
	{
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

		final boolean[] flags = new boolean[] {
				cbTile.isSelected(),
				cbChannel.isSelected(),
				cbAngle.isSelected(),
				cbIllumination.isSelected(),
				cbTimePoint.isSelected() };

		// Filter the dataset's view-set by attribute compatibility against any actual view.
		final Map< Integer, ? extends BasicViewSetup > vss =
				data.getSequenceDescription().getViewSetups();
		final Collection< ViewId > allViews = new ArrayList<>(
				data.getSequenceDescription().getViewDescriptions().keySet() );
		final HashSet< ViewId > filteredUniverse = new HashSet<>();
		for ( final ViewId u : allViews )
		{
			if ( actualVids.contains( u ) ) { filteredUniverse.add( u ); continue; }
			for ( final ViewId a : actualVids )
			{
				if ( compatible( vss, u, a, flags ) )
				{
					filteredUniverse.add( u );
					break;
				}
			}
		}

		final Set< ViewId > overlap =
				ViewNeighbors.overlappingFor( data, actualVids, filteredUniverse );

		final HashSet< ViewId > visible = new HashSet<>();
		visible.addAll( actualVids );
		visible.addAll( overlap );

		applyExplorerSelection( visible );

		final boolean newSelection = !visible.equals( lastApplied );
		final boolean recenter =
				resetPolicy == ResetPolicy.ALWAYS
				|| ( resetPolicy == ResetPolicy.NEW_VIEW_SELECTED && newSelection );
		if ( recenter )
			recenterBdv();

		final Map< Integer, ARGBType > colorBySetupId = new HashMap<>();
		final ARGBType white = new ARGBType( ARGBType.rgba( 255, 255, 255, 255 ) );
		for ( final ViewId v : lastApplied )
			if ( !visible.contains( v ) )
				colorBySetupId.put( v.getViewSetupId(), white );
		for ( final ViewId v : overlap )
			colorBySetupId.put( v.getViewSetupId(), BDVColors.LIGHTBLUE );
		for ( final ViewId v : actualVids )
			colorBySetupId.put( v.getViewSetupId(), BDVColors.GREEN );
		SwingUtilities.invokeLater( () ->
				BDVColors.applyCategoryColors( panel.runningBdvPopup(), colorBySetupId ) );

		lastApplied = visible;
		lastActual = new HashSet<>( actualVids );

		updateLegend( actualVids.size(), overlap.size() );
	}

	/** True iff {@code u} is compatible with {@code a} given the across-flags
	 *  ({Tile, Channel, Angle, Illumination, TimePoint}; TRUE = "may differ"). */
	private static boolean compatible(
			final Map< Integer, ? extends BasicViewSetup > vss,
			final ViewId u, final ViewId a, final boolean[] flags )
	{
		if ( !flags[ 0 ] && !attrEquals( vss, u, a, Tile.class ) )         return false;
		if ( !flags[ 1 ] && !attrEquals( vss, u, a, Channel.class ) )      return false;
		if ( !flags[ 2 ] && !attrEquals( vss, u, a, Angle.class ) )        return false;
		if ( !flags[ 3 ] && !attrEquals( vss, u, a, Illumination.class ) ) return false;
		if ( !flags[ 4 ] && u.getTimePointId() != a.getTimePointId() )     return false;
		return true;
	}

	private static boolean attrEquals(
			final Map< Integer, ? extends BasicViewSetup > vss,
			final ViewId u, final ViewId a, final Class< ? extends Entity > attrClass )
	{
		final BasicViewSetup vsU = vss.get( u.getViewSetupId() );
		final BasicViewSetup vsA = vss.get( a.getViewSetupId() );
		if ( vsU == null || vsA == null ) return false;
		final Entity entU = vsU.getAttribute( attrClass );
		final Entity entA = vsA.getAttribute( attrClass );
		return entU != null && entU.equals( entA );
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
