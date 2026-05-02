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
package net.preibisch.mvrecon.fiji.spimdata.explorer.analyzeerror;

import java.awt.BorderLayout;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SortOrder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;

import ij.gui.GUI;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.ViewDescription;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.analyzeerror.AnalyzeErrorsUtil.GroupPairResult;
import net.preibisch.mvrecon.fiji.spimdata.explorer.analyzeerror.AnalyzeErrorsUtil.Parameters;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ViewSetupExplorerPanel;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

/**
 * Results browser for {@link AnalyzeErrorsUtil}: a single sortable table with one row
 * per view-pair (no grouping) or per group-pair (any Group_by_* selected). Clicking
 * a row recenters the explorer's BDV on the involved views, if BDV is open.
 */
public class AnalyzeErrorsResultsWindow extends JFrame
{
	private static final long serialVersionUID = 1L;

	private static final NumberFormat FMT = new DecimalFormat( "#.####" );

	private static final int COL_A = 0, COL_B = 1, COL_COUNT = 2, COL_NUM_CORR = 3, COL_MIN = 4, COL_AVG = 5, COL_MAX = 6;

	public AnalyzeErrorsResultsWindow(
			final SpimData2 data,
			final ArrayList< AnalyzeErrorsUtil.PairError > errors,
			final Parameters params,
			final ViewSetupExplorerPanel< ? > panel )
	{
		super( buildTitle( params ) );

		setDefaultCloseOperation( DISPOSE_ON_CLOSE );

		final List< Row > rows = buildRows( data, errors, params );

		final JTable table = new JTable( new ResultsTableModel( rows ) );
		final TableRowSorter< ResultsTableModel > sorter = new TableRowSorter<>( ( ResultsTableModel ) table.getModel() );
		table.setRowSorter( sorter );
		sorter.setSortKeys( java.util.Collections.singletonList(
				new javax.swing.RowSorter.SortKey( COL_AVG, SortOrder.DESCENDING ) ) );

		// Right-align numeric columns and format error columns with 4 decimals
		final DefaultTableCellRenderer right = new DefaultTableCellRenderer();
		right.setHorizontalAlignment( javax.swing.SwingConstants.RIGHT );
		table.getColumnModel().getColumn( COL_COUNT ).setCellRenderer( right );
		table.getColumnModel().getColumn( COL_NUM_CORR ).setCellRenderer( right );

		final DefaultTableCellRenderer errorRenderer = new DefaultTableCellRenderer()
		{
			private static final long serialVersionUID = 1L;
			@Override
			protected void setValue( final Object value )
			{
				setHorizontalAlignment( javax.swing.SwingConstants.RIGHT );
				setText( value == null ? "" : FMT.format( ( Double ) value ) );
			}
		};
		table.getColumnModel().getColumn( COL_MIN ).setCellRenderer( errorRenderer );
		table.getColumnModel().getColumn( COL_AVG ).setCellRenderer( errorRenderer );
		table.getColumnModel().getColumn( COL_MAX ).setCellRenderer( errorRenderer );

		// Reasonable default column widths
		table.getColumnModel().getColumn( COL_A ).setPreferredWidth( 280 );
		table.getColumnModel().getColumn( COL_B ).setPreferredWidth( 280 );
		table.getColumnModel().getColumn( COL_COUNT ).setPreferredWidth( 60 );
		table.getColumnModel().getColumn( COL_NUM_CORR ).setPreferredWidth( 70 );
		table.getColumnModel().getColumn( COL_MIN ).setPreferredWidth( 90 );
		table.getColumnModel().getColumn( COL_AVG ).setPreferredWidth( 90 );
		table.getColumnModel().getColumn( COL_MAX ).setPreferredWidth( 90 );

		table.setSelectionMode( ListSelectionModel.SINGLE_SELECTION );
		table.getSelectionModel().addListSelectionListener( e -> {
			if ( e.getValueIsAdjusting() )
				return;
			final int viewRow = table.getSelectedRow();
			if ( viewRow < 0 )
				return;
			final Row r = rows.get( table.convertRowIndexToModel( viewRow ) );

			// off-EDT: AnalyzeErrorsUtil.selectViewsAndRecenter uses threadWait
			new Thread( () ->
					AnalyzeErrorsUtil.selectViewsAndRecenter( panel, params, r.viewsForBDV ) ).start();
		} );

		getContentPane().setLayout( new BorderLayout() );
		getContentPane().add( new JScrollPane( table ), BorderLayout.CENTER );
		setSize( 900, 500 );
		GUI.center( this );
		setVisible( true );
	}

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

	private static String formatWeight( final Double w )
	{
		if ( w == null ) return "";
		final double d = w.doubleValue();
		if ( d == Math.floor( d ) && !Double.isInfinite( d ) )
			return Integer.toString( ( int ) d );
		return FMT.format( d );
	}

	private static List< Row > buildRows(
			final SpimData2 data,
			final ArrayList< AnalyzeErrorsUtil.PairError > errors,
			final Parameters params )
	{
		final ArrayList< Row > rows = new ArrayList<>();
		if ( params.anyGroupingSelected() )
		{
			for ( final GroupPairResult r : AnalyzeErrorsUtil.computeGroupPairs( data, errors, params ) )
			{
				final HashSet< BasicViewDescription< ? > > views = new HashSet<>();
				views.addAll( r.groupA.getViews() );
				views.addAll( r.groupB.getViews() );
				rows.add( new Row( Group.gvids( r.groupA ), Group.gvids( r.groupB ), r.count, r.numCorr, r.min, r.avg, r.max, views ) );
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
				rows.add( new Row( Group.pvids( e.a ), Group.pvids( e.b ), 1, e.numCorr, err, err, err, views ) );
			}
		}
		return rows;
	}

	private static final class Row
	{
		final String labelA, labelB;
		final int count;
		final int numCorr;
		final double min, avg, max;
		final HashSet< BasicViewDescription< ? > > viewsForBDV;

		Row( final String labelA, final String labelB, final int count, final int numCorr,
				final double min, final double avg, final double max,
				final HashSet< BasicViewDescription< ? > > viewsForBDV )
		{
			this.labelA = labelA;
			this.labelB = labelB;
			this.count = count;
			this.numCorr = numCorr;
			this.min = min;
			this.avg = avg;
			this.max = max;
			this.viewsForBDV = viewsForBDV;
		}
	}

	private static final class ResultsTableModel extends AbstractTableModel
	{
		private static final long serialVersionUID = 1L;
		private static final String[] COLUMNS = { "Side A", "Side B", "Count", "# Corr", "Min (px)", "Avg (px)", "Max (px)" };

		private final List< Row > rows;
		ResultsTableModel( final List< Row > rows ) { this.rows = rows; }

		@Override public int getRowCount() { return rows.size(); }
		@Override public int getColumnCount() { return COLUMNS.length; }
		@Override public String getColumnName( final int c ) { return COLUMNS[ c ]; }

		@Override
		public Class< ? > getColumnClass( final int c )
		{
			switch ( c )
			{
				case COL_COUNT: case COL_NUM_CORR: return Integer.class;
				case COL_MIN: case COL_AVG: case COL_MAX: return Double.class;
				default:                          return String.class;
			}
		}

		@Override public boolean isCellEditable( final int r, final int c ) { return false; }

		@Override
		public Object getValueAt( final int r, final int c )
		{
			final Row row = rows.get( r );
			switch ( c )
			{
				case COL_A:        return row.labelA;
				case COL_B:        return row.labelB;
				case COL_COUNT:    return row.count;
				case COL_NUM_CORR: return row.numCorr;
				case COL_MIN:      return row.min;
				case COL_AVG:      return row.avg;
				case COL_MAX:      return row.max;
				default:           return null;
			}
		}
	}
}
