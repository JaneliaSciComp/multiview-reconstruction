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
package net.preibisch.mvrecon.fiji.spimdata.explorer.registration;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.DefaultCellEditor;
import javax.swing.JTextField;

import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.registration.ViewTransformAffine;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.realtransform.AffineTransform3D;

public class RegistrationExplorerPanel extends JPanel
{
	private static final long serialVersionUID = -3767947754096099774L;

	final RegistrationExplorer< ? > explorer;

	protected JTable table;
	protected HierarchicalRegistrationTableModel tableModel;
	protected ViewRegistrations viewRegistrations;
	protected List<BasicViewDescription<?>> lastSelectedVDs;

	protected ArrayList< ViewTransform > cache;

	public RegistrationExplorerPanel( final ViewRegistrations viewRegistrations, final RegistrationExplorer< ? > explorer )
	{
		this.cache = new ArrayList< ViewTransform >();
		this.explorer = explorer;
		this.viewRegistrations = viewRegistrations;
		this.lastSelectedVDs = new ArrayList<>();

		initComponent();
	}

	public JTable getTable() { return table; }

	public void updateViewDescriptions( final List<BasicViewDescription<?>> vds )
	{
		this.lastSelectedVDs = vds;
		tableModel.updateData( vds );
	}

	protected void initComponent()
	{
		this.setLayout( new BorderLayout() );

		// Create table model and table
		tableModel = new HierarchicalRegistrationTableModel( viewRegistrations );
		table = new JTable( tableModel );
		table.setSurrendersFocusOnKeystroke( true );
		table.setSelectionMode( ListSelectionModel.MULTIPLE_INTERVAL_SELECTION );
		table.setAutoResizeMode( JTable.AUTO_RESIZE_OFF );

		// Set up custom renderers and editors
		table.setDefaultRenderer( Object.class, new HierarchicalCellRenderer() );
		table.setDefaultEditor( Object.class, new HierarchicalCellEditor() );

		// Column widths
		table.getColumnModel().getColumn( 0 ).setPreferredWidth( 300 );
		for ( int i = 1; i < table.getColumnCount(); ++i )
			table.getColumnModel().getColumn( i ).setPreferredWidth( 80 );

		// Font
		final Font f = table.getFont();
		table.setFont( new Font( f.getName(), f.getStyle(), 11 ) );

		// Row height
		table.setRowHeight( 22 );

		// Mouse listener for expand/collapse
		table.addMouseListener( new MouseAdapter()
		{
			@Override
			public void mouseClicked( MouseEvent e )
			{
				int row = table.rowAtPoint( e.getPoint() );
				int col = table.columnAtPoint( e.getPoint() );

				// Only handle clicks on the first column (Name column)
				if ( col == 0 && row >= 0 && row < tableModel.getRowCount() )
				{
					TableRow tableRow = tableModel.getRowAt( row );
					if ( tableRow.isGroup() )
					{
						// Toggle expand/collapse
						tableModel.toggleExpanded( tableRow.vd );
					}
				}
			}
		});

		this.add( new JScrollPane( table ), BorderLayout.CENTER );

		addPopupMenu( table );
	}

	/**
	 * Table row data structure
	 */
	private static class TableRow
	{
		enum RowType { GROUP, REGISTRATION }

		final RowType rowType;
		final BasicViewDescription< ? > vd;
		final int transformIndex;

		public TableRow( RowType rowType, BasicViewDescription< ? > vd, int transformIndex )
		{
			this.rowType = rowType;
			this.vd = vd;
			this.transformIndex = transformIndex;
		}

		public boolean isGroup() { return rowType == RowType.GROUP; }
		public boolean isRegistration() { return rowType == RowType.REGISTRATION; }
	}

	/**
	 * Hierarchical table model
	 */
	private class HierarchicalRegistrationTableModel extends AbstractTableModel
	{
		private static final long serialVersionUID = 1L;

		private final ViewRegistrations viewRegistrations;
		private final List<TableRow> rows;
		private final Set<ViewId> expandedGroups;
		private final String[] columnNames;

		public HierarchicalRegistrationTableModel( ViewRegistrations viewRegistrations )
		{
			this.viewRegistrations = viewRegistrations;
			this.rows = new ArrayList<>();
			this.expandedGroups = new HashSet<>();

			// Column names: Name + m00-m33
			this.columnNames = new String[13];
			columnNames[0] = "Name";
			int idx = 1;
			for ( int i = 0; i < 3; i++ )
				for ( int j = 0; j < 4; j++ )
					columnNames[idx++] = "m" + i + j;
		}

		public void updateData( List<BasicViewDescription<?>> vds )
		{
			rows.clear();

			// Auto-expand first entry on initial display
			if ( expandedGroups.isEmpty() && !vds.isEmpty() )
			{
				expandedGroups.add( vds.get( 0 ) );
			}

			for ( BasicViewDescription<?> vd : vds )
			{
				// Add group row
				rows.add( new TableRow( TableRow.RowType.GROUP, vd, -1 ) );

				// Add registration rows if expanded
				if ( expandedGroups.contains( vd ) )
				{
					ViewRegistration vr = viewRegistrations.getViewRegistration( vd );
					for ( int i = 0; i < vr.getTransformList().size(); i++ )
					{
						rows.add( new TableRow( TableRow.RowType.REGISTRATION, vd, i ) );
					}
				}
			}

			fireTableDataChanged();
		}

		public void toggleExpanded( ViewId vd )
		{
			if ( expandedGroups.contains( vd ) )
				expandedGroups.remove( vd );
			else
				expandedGroups.add( vd );

			updateData( lastSelectedVDs );
		}

		public TableRow getRowAt( int row )
		{
			if ( row >= 0 && row < rows.size() )
				return rows.get( row );
			return null;
		}

		@Override
		public int getRowCount()
		{
			return rows.size();
		}

		@Override
		public int getColumnCount()
		{
			return columnNames.length;
		}

		@Override
		public String getColumnName( int column )
		{
			return columnNames[column];
		}

		@Override
		public Object getValueAt( int rowIndex, int columnIndex )
		{
			if ( rowIndex >= rows.size() )
				return "";

			TableRow row = rows.get( rowIndex );

			// Name column
			if ( columnIndex == 0 )
			{
				if ( row.isGroup() )
					return "ViewSetup: " + row.vd.getViewSetupId() + ", TP: " + row.vd.getTimePointId();
				else if ( row.isRegistration() )
				{
					ViewTransform vt = viewRegistrations.getViewRegistration( row.vd )
							.getTransformList().get( row.transformIndex );
					return vt.getName();
				}
			}
			// Matrix columns
			else if ( row.isRegistration() )
			{
				ViewTransform vt = viewRegistrations.getViewRegistration( row.vd )
						.getTransformList().get( row.transformIndex );

				int matIndex = columnIndex - 1;
				int matRow = matIndex / 4;
				int matCol = matIndex % 4;

				return String.format( "%.6f", vt.asAffine3D().get( matRow, matCol ) );
			}

			return "";
		}

		@Override
		public boolean isCellEditable( int rowIndex, int columnIndex )
		{
			if ( rowIndex >= rows.size() )
				return false;

			TableRow row = rows.get( rowIndex );

			// Registration rows are editable (both name and matrix values)
			return row.isRegistration();
		}

		@Override
		public void setValueAt( Object value, int rowIndex, int columnIndex )
		{
			if ( rowIndex >= rows.size() )
				return;

			TableRow row = rows.get( rowIndex );

			if ( !row.isRegistration() )
				return;

			ViewTransform vtOld = viewRegistrations.getViewRegistration( row.vd )
					.getTransformList().get( row.transformIndex );

			try
			{
				if ( columnIndex == 0 )
				{
					// Edit name
					String newName = value.toString();
					ViewTransform vtNew = newName( vtOld, newName );

					viewRegistrations.getViewRegistration( row.vd ).getTransformList()
							.remove( row.transformIndex );
					viewRegistrations.getViewRegistration( row.vd ).getTransformList()
							.add( row.transformIndex, vtNew );
					viewRegistrations.getViewRegistration( row.vd ).updateModel();
				}
				else
				{
					// Edit matrix value
					int matIndex = columnIndex - 1;
					int matRow = matIndex / 4;
					int matCol = matIndex % 4;

					double newValue = Double.parseDouble( value.toString() );

					AffineTransform3D newT = new AffineTransform3D();
					newT.set( vtOld.asAffine3D().getRowPackedCopy() );
					newT.set( newValue, matRow, matCol );

					ViewTransform vtNew = new ViewTransformAffine( vtOld.getName(), newT );

					viewRegistrations.getViewRegistration( row.vd ).getTransformList()
							.remove( row.transformIndex );
					viewRegistrations.getViewRegistration( row.vd ).getTransformList()
							.add( row.transformIndex, vtNew );
					viewRegistrations.getViewRegistration( row.vd ).updateModel();
				}

				explorer.viewSetupExplorer.getPanel().bdvPopup().updateBDV();
				fireTableCellUpdated( rowIndex, columnIndex );
			}
			catch ( NumberFormatException e )
			{
				JOptionPane.showMessageDialog( table, "Invalid number format: " + value );
			}
		}
	}

	/**
	 * Custom cell renderer for hierarchical display
	 */
	private class HierarchicalCellRenderer extends DefaultTableCellRenderer
	{
		private static final long serialVersionUID = 1L;

		@Override
		public Component getTableCellRendererComponent( JTable table, Object value,
				boolean isSelected, boolean hasFocus, int row, int column )
		{
			Component c = super.getTableCellRendererComponent( table, value, isSelected, hasFocus, row, column );

			TableRow tableRow = tableModel.getRowAt( row );

			if ( tableRow == null )
				return c;

			// Styling for group rows
			if ( tableRow.isGroup() )
			{
				c.setFont( c.getFont().deriveFont( Font.BOLD ) );
				if ( !isSelected )
					c.setBackground( new Color( 230, 230, 250 ) );

				// Add expand/collapse indicator for Name column
				if ( column == 0 )
				{
					boolean expanded = tableModel.expandedGroups.contains( tableRow.vd );
					String icon = expanded ? "▼ " : "▶ ";
					setText( icon + value.toString() );
				}
			}
			// Styling for registration rows
			else if ( tableRow.isRegistration() )
			{
				c.setFont( c.getFont().deriveFont( Font.PLAIN ) );
				if ( !isSelected )
					c.setBackground( Color.WHITE );

				// Add indentation for Name column
				if ( column == 0 )
				{
					setText( "    " + value.toString() );
				}
			}

			// Center-align matrix columns
			if ( column > 0 )
			{
				setHorizontalAlignment( JLabel.CENTER );
			}
			else
			{
				setHorizontalAlignment( JLabel.LEFT );
			}

			return c;
		}
	}

	/**
	 * Custom cell editor for editable cells
	 */
	private class HierarchicalCellEditor extends DefaultCellEditor
	{
		private static final long serialVersionUID = 1L;

		public HierarchicalCellEditor()
		{
			super( new JTextField() );
		}

		@Override
		public Component getTableCellEditorComponent( JTable table, Object value,
				boolean isSelected, int row, int column )
		{
			JTextField textField = (JTextField) super.getTableCellEditorComponent(
					table, value, isSelected, row, column );

			// Remove indentation for editing
			if ( column == 0 )
			{
				String text = value.toString().trim();
				textField.setText( text );
			}

			return textField;
		}
	}

	protected void copySelection()
	{
		cache.clear();

		int[] selectedRows = table.getSelectedRows();

		if ( selectedRows.length == 0 )
		{
			JOptionPane.showMessageDialog( table, "Nothing selected" );
			return;
		}

		for ( int row : selectedRows )
		{
			TableRow tableRow = tableModel.getRowAt( row );

			if ( tableRow != null && tableRow.isRegistration() )
			{
				ViewTransform vt = viewRegistrations.getViewRegistration( tableRow.vd )
						.getTransformList().get( tableRow.transformIndex );
				cache.add( duplicate( vt ) );
				System.out.println( "Copied row " + vt.getName() );
			}
		}

		if ( cache.isEmpty() )
		{
			JOptionPane.showMessageDialog( table, "No registration rows selected" );
		}
	}

	/**
	 *
	 * @param type 0 == before, 1 == replace, 2 == after
	 */
	protected void pasteSelection( final int type )
	{
		int[] selectedRows = table.getSelectedRows();

		final Map<ViewId, List<Integer>> toInsert = new HashMap<>();

		if ( cache.size() == 0 )
		{
			JOptionPane.showMessageDialog( table, "Nothing copied so far." );
			return;
		}

		if ( selectedRows.length == 0 )
		{
			JOptionPane.showMessageDialog( table, "Nothing selected." );
			return;
		}

		for ( int row : selectedRows )
		{
			TableRow tableRow = tableModel.getRowAt( row );

			if ( tableRow != null && tableRow.isRegistration() )
			{
				if ( !toInsert.containsKey( tableRow.vd ) )
					toInsert.put( tableRow.vd, new ArrayList<>() );
				toInsert.get( tableRow.vd ).add( tableRow.transformIndex );
			}
		}

		if ( toInsert.isEmpty() )
		{
			JOptionPane.showMessageDialog( table, "No registration rows selected" );
			return;
		}

		for ( ViewId vd : toInsert.keySet() )
		{
			List< Integer > idxes = toInsert.get( vd );
			Collections.sort( idxes );

			// Remove if we want that
			if ( type == 1 )
				for ( int i = 0; i < idxes.size(); i++ )
					viewRegistrations.getViewRegistration( vd ).getTransformList()
							.remove( idxes.get( i ) - i );

			for ( int i = 0; i < idxes.size(); i++ )
				for ( int j = 0; j < cache.size(); j++ )
				{
					int idxToAddAt = ( type == 2 ) ?
							idxes.get( i ) + j + i * cache.size() + 1 :
							idxes.get( i ) + j + i * cache.size();
					viewRegistrations.getViewRegistration( vd ).getTransformList()
							.add( idxToAddAt, duplicate( cache.get( j ) ) );
				}

			viewRegistrations.getViewRegistration( vd ).updateModel();
		}

		explorer.viewSetupExplorer.getPanel().bdvPopup().updateBDV();
		tableModel.updateData( lastSelectedVDs );
	}

	protected static ViewTransform duplicate( final ViewTransform vt )
	{
		final AffineTransform3D t = new AffineTransform3D();
		t.set( vt.asAffine3D().getRowPackedCopy() );

		return new ViewTransformAffine( vt.getName(), t );
	}

	protected static ViewTransform newName( final ViewTransform vt, final String name )
	{
		final AffineTransform3D t = new AffineTransform3D();
		t.set( vt.asAffine3D().getRowPackedCopy() );

		return new ViewTransformAffine( name, t );
	}

	protected static ViewTransform newMatrixEntry( final ViewTransform vt, final double value, final int index )
	{
		final AffineTransform3D t = new AffineTransform3D();
		final double[] m = vt.asAffine3D().getRowPackedCopy();
		m[ index ] = value;
		t.set( m );

		return new ViewTransformAffine( vt.getName(), t );
	}

	protected void delete()
	{
		int[] selectedRows = table.getSelectedRows();

		final Map<ViewId, List<Integer>> toDelete = new HashMap<>();

		if ( selectedRows.length == 0 )
		{
			JOptionPane.showMessageDialog( table, "Nothing selected." );
			return;
		}

		for ( int row : selectedRows )
		{
			TableRow tableRow = tableModel.getRowAt( row );

			if ( tableRow != null && tableRow.isRegistration() )
			{
				if ( !toDelete.containsKey( tableRow.vd ) )
					toDelete.put( tableRow.vd, new ArrayList<>() );
				toDelete.get( tableRow.vd ).add( tableRow.transformIndex );
			}
		}

		if ( toDelete.isEmpty() )
		{
			JOptionPane.showMessageDialog( table, "No registration rows selected" );
			return;
		}

		for ( ViewId vd : toDelete.keySet() )
		{
			List< Integer > idxes = toDelete.get( vd );
			Collections.sort( idxes );
			for ( int i = 0; i < idxes.size(); i++ )
				viewRegistrations.getViewRegistration( vd ).getTransformList()
						.remove( idxes.get( i ) - i );

			if ( viewRegistrations.getViewRegistration( vd ).getTransformList().isEmpty() )
				viewRegistrations.getViewRegistration( vd ).getTransformList()
						.add( new ViewTransformAffine( null, new AffineTransform3D() ) );

			viewRegistrations.getViewRegistration( vd ).updateModel();
		}

		explorer.viewSetupExplorer.getPanel().bdvPopup().updateBDV();
		tableModel.updateData( lastSelectedVDs );
	}

	protected void addPopupMenu( final JTable table )
	{
		final JPopupMenu popupMenu = new JPopupMenu();

		JMenuItem copyItem = new JMenuItem( "Copy" );
		JMenuItem deleteItem = new JMenuItem( "Delete" );

		JMenuItem pasteBeforeItem = new JMenuItem( "Paste before selection" );
		JMenuItem pasteAndReplaceItem = new JMenuItem( "Paste and replace selection" );
		JMenuItem pasteAfterItem = new JMenuItem( "Paste after selection" );

		copyItem.addActionListener( new ActionListener()
		{
			@Override
			public void actionPerformed( final ActionEvent e )
			{
				copySelection();
			}
		});

		pasteBeforeItem.addActionListener( new ActionListener()
		{
			@Override
			public void actionPerformed( final ActionEvent e )
			{
				pasteSelection( 0 );
			}
		});

		pasteAndReplaceItem.addActionListener( new ActionListener()
		{
			@Override
			public void actionPerformed( final ActionEvent e )
			{
				pasteSelection( 1 );
			}
		});

		pasteAfterItem.addActionListener( new ActionListener()
		{
			@Override
			public void actionPerformed( final ActionEvent e )
			{
				pasteSelection( 2 );
			}
		});

		deleteItem.addActionListener( new ActionListener()
		{
			@Override
			public void actionPerformed( final ActionEvent e )
			{
				delete();
				System.out.println( "Right-click performed on table and choose DELETE" );
			}
		});

		popupMenu.add( copyItem );
		popupMenu.add( pasteBeforeItem );
		popupMenu.add( pasteAndReplaceItem );
		popupMenu.add( pasteAfterItem );
		popupMenu.add( deleteItem );

		table.setComponentPopupMenu( popupMenu );
	}
}
