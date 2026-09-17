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
package net.preibisch.mvrecon.fiji.plugin;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

import ij.IJ;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistory;
import net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionRecord;
import net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionToSparkCli;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;

/**
 * Shows the recorded action history of a dataset and lets the user copy each entry as the
 * equivalent BigStitcher-Spark CLI invocation. Opened from the multiview explorer's menu
 * ({@code ShowActionHistoryPopup}) on the dataset that window has loaded.
 */
public class Show_Action_History
{
	public static void open( final ExplorerWindow< ? > panel )
	{
		final ActionHistory history = panel.getSpimData() == null ? null : panel.getSpimData().getActionHistory();
		if ( history == null || history.isEmpty() )
		{
			IJ.showMessage( "Action History", "No actions have been recorded for this dataset yet." );
			return;
		}
		new Frame( panel, history ).setVisible( true );
	}

	private static class Frame extends JFrame
	{
		private static final long serialVersionUID = 1L;

		private final ExplorerWindow< ? > panel;
		private final ActionHistory history;
		private final String xmlPath;
		private final JTable table;
		private final HistoryTableModel model;
		private final JTextArea detail;
		private final JButton save;
		private boolean dirty = false;
		/** set by the delete dialog's "Don't show this message again"; resets on restart */
		private static boolean skipDeleteWarning = false;

		Frame( final ExplorerWindow< ? > panel, final ActionHistory history )
		{
			super( "BigStitcher Action History" );
			this.panel = panel;
			this.history = history;
			final URI xmlURI = panel.xml();
			this.xmlPath = xmlURI == null ? "" : xmlURI.toString();

			this.model = new HistoryTableModel( history );
			this.table = new JTable( model );
			this.table.setSelectionMode( ListSelectionModel.MULTIPLE_INTERVAL_SELECTION );
			this.table.setAutoCreateRowSorter( true );
			installDeletePopup();

			this.detail = new JTextArea( 8, 80 );
			this.detail.setEditable( false );
			this.detail.setLineWrap( false );

			this.table.getSelectionModel().addListSelectionListener( e -> updateDetail() );

			final JButton copyRow = new JButton( "Copy selected as Spark CLI" );
			copyRow.addActionListener( e -> copySelectedRow() );

			final JButton copyAll = new JButton( "Copy all as Spark script" );
			copyAll.addActionListener( e -> copyAll() );

			this.save = new JButton( "Save" );
			this.save.setToolTipText( "Save the dataset (same as the explorer's Save button)" );
			this.save.setEnabled( false );
			this.save.addActionListener( e -> saveDataset() );

			final JPanel buttons = new JPanel( new FlowLayout( FlowLayout.LEFT ) );
			buttons.add( copyRow );
			buttons.add( copyAll );
			buttons.add( save );

			final JPanel topInfo = new JPanel( new BorderLayout() );
			topInfo.add( new JLabel( xmlPath == null || xmlPath.isEmpty() ? " " : "XML: " + xmlPath ), BorderLayout.WEST );

			final JScrollPane tableScroll = new JScrollPane( table );
			tableScroll.setPreferredSize( new Dimension( 900, 280 ) );

			final JScrollPane detailScroll = new JScrollPane( detail );
			detailScroll.setPreferredSize( new Dimension( 900, 200 ) );

			final JPanel root = new JPanel( new BorderLayout( 4, 4 ) );
			root.add( topInfo, BorderLayout.NORTH );
			root.add( tableScroll, BorderLayout.CENTER );
			final JPanel south = new JPanel( new BorderLayout( 4, 4 ) );
			south.add( buttons, BorderLayout.NORTH );
			south.add( detailScroll, BorderLayout.CENTER );
			root.add( south, BorderLayout.SOUTH );

			setContentPane( root );
			pack();
			setLocationRelativeTo( null );

			setDefaultCloseOperation( DO_NOTHING_ON_CLOSE );
			addWindowListener( new WindowAdapter()
			{
				@Override public void windowClosing( final WindowEvent e ) { confirmClose(); }
			} );

			if ( model.getRowCount() > 0 )
				table.setRowSelectionInterval( 0, 0 );
		}

		private void confirmClose()
		{
			if ( dirty )
			{
				final int choice = JOptionPane.showConfirmDialog( this,
						"You have unsaved changes to the action history.\nSave before closing?",
						"Unsaved Changes", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE );
				if ( choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION )
					return;
				if ( choice == JOptionPane.YES_OPTION )
				{
					saveDataset();
					if ( dirty ) // save failed
						return;
				}
			}
			dispose();
		}

		private void updateDetail()
		{
			final int row = selectedRow();
			if ( row < 0 ) { detail.setText( "" ); return; }
			final ActionRecord r = history.getRecords().get( row );
			final StringBuilder sb = new StringBuilder();
			sb.append( "Action: " ).append( r.getActionId() ).append( '\n' );
			sb.append( "When:   " ).append( new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss" ).format( new Date( r.getTimestampMillis() ) ) ).append( '\n' );
			if ( !r.getMvreconClass().isEmpty() )
				sb.append( "Class:  " ).append( r.getMvreconClass() ).append( '\n' );
			if ( !r.getResultRef().isEmpty() )
				sb.append( "Result: " ).append( r.getResultRef() ).append( '\n' );
			sb.append( "Parameters:\n" );
			for ( final Map.Entry<String,String> p : r.getParams().entrySet() )
				sb.append( "  " ).append( p.getKey() ).append( " = " ).append( p.getValue() ).append( '\n' );
			sb.append( '\n' );
			sb.append( "BigStitcher-Spark equivalent:\n" );
			for ( final String line : ActionToSparkCli.render( r, xmlPath ) )
				sb.append( "  " ).append( line ).append( '\n' );
			detail.setText( sb.toString() );
			detail.setCaretPosition( 0 );
		}

		private int selectedRow()
		{
			final int viewRow = table.getSelectedRow();
			return viewRow < 0 ? -1 : table.convertRowIndexToModel( viewRow );
		}

		private void installDeletePopup()
		{
			final JPopupMenu popup = new JPopupMenu();
			final JMenuItem delete = new JMenuItem( "Remove from history (log only)" );
			delete.addActionListener( e -> deleteSelectedRows() );
			popup.add( delete );

			table.addMouseListener( new MouseAdapter()
			{
				@Override public void mousePressed( final MouseEvent e ) { maybeShow( e ); }
				@Override public void mouseReleased( final MouseEvent e ) { maybeShow( e ); }

				private void maybeShow( final MouseEvent e )
				{
					if ( !e.isPopupTrigger() )
						return;
					final int viewRow = table.rowAtPoint( e.getPoint() );
					if ( viewRow >= 0 && !table.isRowSelected( viewRow ) )
						table.setRowSelectionInterval( viewRow, viewRow );
					final int selected = table.getSelectedRowCount();
					delete.setText( selected > 1
							? "Remove " + selected + " entries from history (log only)"
							: "Remove entry from history (log only)" );
					delete.setEnabled( selected > 0 );
					popup.show( e.getComponent(), e.getX(), e.getY() );
				}
			} );
		}

		private void deleteSelectedRows()
		{
			final int[] viewRows = table.getSelectedRows();
			if ( viewRows.length == 0 )
				return;

			final List<ActionRecord> toRemove = new ArrayList<>();
			for ( final int viewRow : viewRows )
				toRemove.add( history.getRecords().get( table.convertRowIndexToModel( viewRow ) ) );

			final int n = toRemove.size();

			if ( !skipDeleteWarning )
			{
				final JCheckBox dontAsk = new JCheckBox( "Don't show this message again" );
				final int choice = JOptionPane.showConfirmDialog( this,
						new Object[] {
								"Remove " + n + " action history " + ( n == 1 ? "entry" : "entries" ) + "?\n\n"
										+ "This only deletes the log " + ( n == 1 ? "entry" : "entries" ) + " — the dataset itself is not\n"
										+ "reverted: registrations, transformations and interest points stay as they are.\n"
										+ "(To undo a registration, remove the transformation in the explorer instead —\n"
										+ "that prunes the matching history entry for you.)\n\n"
										+ "The change stays in memory until you press Save.",
								dontAsk },
						"Remove From Action History", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE );
				if ( choice != JOptionPane.OK_OPTION )
					return;
				skipDeleteWarning = dontAsk.isSelected();
			}

			final int removed = history.removeExact( toRemove );
			model.fireTableDataChanged();
			if ( model.getRowCount() > 0 )
				table.setRowSelectionInterval( 0, 0 );
			else
				detail.setText( "" );
			if ( removed > 0 )
				markDirty();
			IOFunctions.println( "[ActionHistory] removed " + removed + " log " + ( removed == 1 ? "entry" : "entries" ) + " (dataset not reverted); press Save to persist." );
		}

		private void markDirty()
		{
			dirty = true;
			save.setEnabled( true );
			setTitle( "BigStitcher Action History *" );
		}

		private void saveDataset()
		{
			if ( !dirty )
				return;

			// the explorer owns the dataset we edited in place, so save through it — its saveXML()
			// also notifies the listeners that flush other pending changes (e.g. interest points).
			panel.saveXML();
			dirty = false;
			save.setEnabled( false );
			setTitle( "BigStitcher Action History" );
			IOFunctions.println( "[ActionHistory] saved dataset to '" + xmlPath + "'." );
		}

		private void copySelectedRow()
		{
			final int row = selectedRow();
			if ( row < 0 ) return;
			final ActionRecord r = history.getRecords().get( row );
			final List<String> lines = ActionToSparkCli.render( r, xmlPath );
			final String text = String.join( " && \\\n", lines );
			copyToClipboard( text );
			IOFunctions.println( "[ActionHistory] copied to clipboard:\n" + text );
		}

		private void copyAll()
		{
			final StringBuilder sb = new StringBuilder( "#!/bin/bash\nset -euo pipefail\n\n" );
			for ( final ActionRecord r : history.getRecords() )
			{
				final List<String> lines = ActionToSparkCli.render( r, xmlPath );
				sb.append( "# " ).append( r.getActionId() );
				if ( !r.getResultRef().isEmpty() ) sb.append( "  [" ).append( r.getResultRef() ).append( ']' );
				sb.append( '\n' );
				for ( final String line : lines )
					sb.append( line ).append( '\n' );
				sb.append( '\n' );
			}
			copyToClipboard( sb.toString() );
			IOFunctions.println( "[ActionHistory] copied " + history.size() + " entries as Spark script to clipboard." );
		}

		private static void copyToClipboard( final String text )
		{
			try
			{
				final Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
				cb.setContents( new StringSelection( text ), null );
			}
			catch ( final Throwable t )
			{
				IOFunctions.println( "[ActionHistory] failed to copy to clipboard: " + t );
			}
		}
	}

	private static class HistoryTableModel extends AbstractTableModel
	{
		private static final long serialVersionUID = 1L;
		private static final String[] COLUMNS = { "Timestamp", "Action", "Result", "Parameters" };
		private final ActionHistory history;
		private final SimpleDateFormat dateFmt = new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss" );

		HistoryTableModel( final ActionHistory history ) { this.history = history; }

		@Override public int getRowCount() { return history.size(); }
		@Override public int getColumnCount() { return COLUMNS.length; }
		@Override public String getColumnName( int c ) { return COLUMNS[ c ]; }

		@Override
		public Object getValueAt( int row, int col )
		{
			final ActionRecord r = history.getRecords().get( row );
			switch ( col )
			{
				case 0: return dateFmt.format( new Date( r.getTimestampMillis() ) );
				case 1: return r.getActionId();
				case 2: return r.getResultRef();
				case 3: return summarize( r.getParams() );
				default: return "";
			}
		}

		private static String summarize( final Map<String,String> params )
		{
			final StringBuilder sb = new StringBuilder();
			int n = 0;
			for ( final Map.Entry<String,String> e : params.entrySet() )
			{
				if ( n++ > 0 ) sb.append( ", " );
				if ( n > 4 ) { sb.append( "…" ); break; }
				sb.append( e.getKey() ).append( '=' ).append( e.getValue() );
			}
			return sb.toString();
		}
	}
}
