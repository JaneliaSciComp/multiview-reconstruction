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
package net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.table.DefaultTableCellRenderer;

import bdv.tools.HelpDialog;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.ImgLib2Temp.Pair;
import net.preibisch.mvrecon.fiji.ImgLib2Temp.ValuePair;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorer;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondenceTools;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;

public class InterestPointExplorerPanel extends JPanel
{
	private static final long serialVersionUID = -3767947754096099774L;

	final FilteredAndGroupedExplorer< ? > viewSetupExplorer;

	protected JTable table;
	protected InterestPointTableModel tableModel;
	protected JLabel label;

	// when save is called save those files and delete the other ones
	//protected ArrayList< Pair< InterestPointList, ViewId > > save;
	protected ArrayList< Pair< InterestPoints, ViewId > > delete;

	public InterestPointExplorerPanel( final ViewInterestPoints viewInterestPoints, final FilteredAndGroupedExplorer< ? > viewSetupExplorer )
	{
		//this.save = new ArrayList< Pair< InterestPointList, ViewId > >();
		this.delete = new ArrayList< Pair< InterestPoints, ViewId > >();

		this.viewSetupExplorer = viewSetupExplorer;

		// Make the panel focusable to receive key events
		setFocusable( true );

		initComponent( viewInterestPoints );
	}

	public InterestPointTableModel getTableModel() { return tableModel; }
	public JTable getTable() { return table; }

	public void updateViewDescription( final List< BasicViewDescription< ? > > viewDescriptionsUnfiltered )
	{
		final ArrayList< BasicViewDescription< ? > > viewDescriptions = new ArrayList<>();

		for ( final BasicViewDescription< ? > vd : viewDescriptionsUnfiltered )
			if ( vd.isPresent() )
				viewDescriptions.add( vd );

		if ( viewDescriptions.size() == 1 && label != null )
			this.label.setText( "Timepoint: " + viewDescriptions.get( 0 ).getTimePointId() + ", ViewSetup: " + viewDescriptions.get( 0 ).getViewSetupId() );
		else if ( viewDescriptions == null || viewDescriptions.size() == 0 )
			this.label.setText( "No View Descriptions selected");
		else
			this.label.setText( viewDescriptions.size() + " View Descriptions selected");

		tableModel.updateViewDescription( viewDescriptions );

		if ( table.getSelectedRowCount() == 0 )
			table.getSelectionModel().setSelectionInterval( 0, 0 );
	}

	public void initComponent( final ViewInterestPoints viewInterestPoints )
	{
		tableModel = new InterestPointTableModel( viewInterestPoints, this );

		table = new JTable();
		table.setModel( tableModel );
		table.setSurrendersFocusOnKeystroke( true );
		table.setSelectionMode( ListSelectionModel.SINGLE_INTERVAL_SELECTION );
		
		final MyRenderer myRenderer = new MyRenderer();
		myRenderer.setHorizontalAlignment( JLabel.CENTER );
		
		// center all columns
		for ( int column = 0; column < tableModel.getColumnCount(); ++column )
			table.getColumnModel().getColumn( column ).setCellRenderer( myRenderer );

		table.setPreferredScrollableViewportSize( new Dimension( 1020, 300 ) );
		final Font f = table.getFont();
		
		table.setFont( new Font( f.getName(), f.getStyle(), 11 ) );

		this.setLayout( new BorderLayout() );
		this.label = new JLabel( "View Description --- " );

		// Create top panel with label and sliders
		final JPanel topPanel = new JPanel( new BorderLayout() );
		topPanel.add( label, BorderLayout.WEST );

		// Create combined slider panel on the right with both sliders
		final JPanel slidersPanel = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );

		// Point Size slider
		final JLabel sizeSliderLabel = new JLabel( "<html>Point<br>Size:</html>" );
		sizeSliderLabel.setFont( sizeSliderLabel.getFont().deriveFont( 9.0f ) );
		final JSlider sizeSlider = new JSlider( 0, 100, 30 ); // Initialize at 30 to give scale = 1.0 (current size 3.0)
		sizeSlider.setPreferredSize( new Dimension( 150, 25 ) );
		final JTextField sizeTextField = new JTextField( 4 );
		sizeTextField.setPreferredSize( new Dimension( 40, 20 ) );
		sizeSlider.addChangeListener( new ChangeListener()
		{
			@Override
			public void stateChanged( ChangeEvent e )
			{
				final int sliderValue = sizeSlider.getValue();
				// Exponential scaling: scale = 10^((sliderValue-30)/85)
				// At 0: scale~0.44, At 30: scale=1.0, At 100: scale~6.67 (size=20)
				final double scale = Math.pow( 10.0, (sliderValue - 30.0) / 85.0 );
				tableModel.setPointSizeScale( scale );
				// Display actual pixel size (scale * 3.0)
				final double pixelSize = scale * 3.0;
				sizeTextField.setText( String.format( "%.2f", pixelSize ) );
			}
		});
		final ActionListener sizeTextFieldListener = new ActionListener()
		{
			@Override
			public void actionPerformed( ActionEvent e )
			{
				try
				{
					final double pixelSize = Double.parseDouble( sizeTextField.getText() );
					// Convert to scale factor and apply directly to model
					final double scale = pixelSize / 3.0;
					tableModel.setPointSizeScale( scale );

					// Inverse formula: sliderValue = 30 + 85 * log10(scale)
					int sliderValue = (int)( 30 + 85.0 * Math.log10( scale ) );
					// Clamp slider to valid range (0-100) but don't constrain the actual value
					sliderValue = Math.max( 0, Math.min( 100, sliderValue ) );

					// Temporarily remove listener to avoid overwriting text field
					final ChangeListener[] listeners = sizeSlider.getChangeListeners();
					for ( ChangeListener listener : listeners )
						sizeSlider.removeChangeListener( listener );

					sizeSlider.setValue( sliderValue );

					// Re-add listeners
					for ( ChangeListener listener : listeners )
						sizeSlider.addChangeListener( listener );
				}
				catch ( NumberFormatException ex )
				{
					// Reset to current value from model
					final double scale = tableModel.getPointSizeScale();
					final double pixelSize = scale * 3.0;
					sizeTextField.setText( String.format( "%.2f", pixelSize ) );
				}
			}
		};
		sizeTextField.addActionListener( sizeTextFieldListener );
		sizeTextField.addFocusListener( new FocusAdapter()
		{
			@Override
			public void focusLost( FocusEvent e )
			{
				sizeTextFieldListener.actionPerformed( null );
			}
		});
		// Initialize text field
		sizeTextField.setText( "3.00" );

		// Plane Thickness slider
		final JLabel planeThicknessSliderLabel = new JLabel( "<html>Plane<br>Thickness:</html>" );
		planeThicknessSliderLabel.setFont( planeThicknessSliderLabel.getFont().deriveFont( 9.0f ) );
		final JSlider planeThicknessSlider = new JSlider( 0, 100, 50 ); // 0-100, default 50 (thickness=3)
		planeThicknessSlider.setPreferredSize( new Dimension( 150, 25 ) );
		final JTextField planeThicknessTextField = new JTextField( 4 );
		planeThicknessTextField.setPreferredSize( new Dimension( 40, 20 ) );
		planeThicknessSlider.addChangeListener( new ChangeListener()
		{
			@Override
			public void stateChanged( ChangeEvent e )
			{
				final int sliderValue = planeThicknessSlider.getValue();
				// Power scaling: thickness = 100 * (sliderValue/100)^5
				// At 0: thickness=0, At 50: thickness~3.125, At 100: thickness=100
				final double thickness = 100.0 * Math.pow( sliderValue / 100.0, 5.0 );
				tableModel.setPlaneThickness( thickness );
				planeThicknessTextField.setText( String.format( "%.2f", thickness ) );
			}
		});
		final ActionListener planeThicknessTextFieldListener = new ActionListener()
		{
			@Override
			public void actionPerformed( ActionEvent e )
			{
				try
				{
					final double thickness = Double.parseDouble( planeThicknessTextField.getText() );
					// Apply directly to model
					tableModel.setPlaneThickness( thickness );

					// Inverse formula: sliderValue = 100 * (thickness/100)^(1/5)
					int sliderValue = (int)( 100.0 * Math.pow( Math.max( 0.0, thickness ) / 100.0, 0.2 ) );
					// Clamp slider to valid range (0-100) but don't constrain the actual value
					sliderValue = Math.max( 0, Math.min( 100, sliderValue ) );

					// Temporarily remove listener to avoid overwriting text field
					final ChangeListener[] listeners = planeThicknessSlider.getChangeListeners();
					for ( ChangeListener listener : listeners )
						planeThicknessSlider.removeChangeListener( listener );

					planeThicknessSlider.setValue( sliderValue );

					// Re-add listeners
					for ( ChangeListener listener : listeners )
						planeThicknessSlider.addChangeListener( listener );
				}
				catch ( NumberFormatException ex )
				{
					// Reset to current value from model
					final double thickness = tableModel.getPlaneThickness();
					planeThicknessTextField.setText( String.format( "%.2f", thickness ) );
				}
			}
		};
		planeThicknessTextField.addActionListener( planeThicknessTextFieldListener );
		planeThicknessTextField.addFocusListener( new FocusAdapter()
		{
			@Override
			public void focusLost( FocusEvent e )
			{
				planeThicknessTextFieldListener.actionPerformed( null );
			}
		});
		// Initialize text field
		planeThicknessTextField.setText( "3.13" );

		// Distance Fade slider
		final JPanel distanceFadeSliderPanel = new JPanel( new FlowLayout( FlowLayout.CENTER, 0, 0 ) );
		final JLabel distanceFadeSliderLabel = new JLabel( "<html>Distance<br>Fade:</html>" );
		distanceFadeSliderLabel.setFont( distanceFadeSliderLabel.getFont().deriveFont( 9.0f ) );
		final JSlider distanceFadeSlider = new JSlider( 0, 100, 50 ); // 0-100, default 50 (medium fade)
		distanceFadeSlider.setPreferredSize( new Dimension( 150, 25 ) );
		final JTextField distanceFadeTextField = new JTextField( 4 );
		distanceFadeTextField.setPreferredSize( new Dimension( 40, 20 ) );
		distanceFadeSlider.addChangeListener( new ChangeListener()
		{
			@Override
			public void stateChanged( ChangeEvent e )
			{
				final int sliderValue = distanceFadeSlider.getValue();

				// Check if we're at maximum (filter mode)
				final boolean filterMode = (sliderValue == 100);

				// Change appearance when in filter mode
				if ( filterMode )
				{
					distanceFadeSliderPanel.setBackground( new Color( 255, 200, 200 ) ); // Light red
					distanceFadeSliderLabel.setForeground( Color.red );
				}
				else
				{
					distanceFadeSliderPanel.setBackground( null ); // Default background
					distanceFadeSliderLabel.setForeground( null ); // Default foreground
				}

				// Apply exponential scaling for better control (cubic for very aggressive fade at max)
				final double fadeFactor = Math.pow( sliderValue / 100.0, 3.0 );
				tableModel.setDistanceFade( fadeFactor, filterMode );
				distanceFadeTextField.setText( String.format( "%.2f", fadeFactor ) );
			}
		});
		final ActionListener distanceFadeTextFieldListener = new ActionListener()
		{
			@Override
			public void actionPerformed( ActionEvent e )
			{
				try
				{
					final double fadeFactor = Double.parseDouble( distanceFadeTextField.getText() );

					// Inverse formula: sliderValue = 100 * fadeFactor^(1/3)
					int sliderValue = (int)( 100.0 * Math.pow( Math.max( 0.0, fadeFactor ), 1.0 / 3.0 ) );
					// Clamp slider to valid range (0-100) but don't constrain the actual value
					sliderValue = Math.max( 0, Math.min( 100, sliderValue ) );

					// Determine filter mode (>= 1.0)
					final boolean filterMode = fadeFactor >= 1.0;

					// Apply directly to model
					tableModel.setDistanceFade( fadeFactor, filterMode );

					// Update slider panel appearance if at max
					if ( filterMode )
					{
						distanceFadeSliderPanel.setBackground( new Color( 255, 200, 200 ) );
						distanceFadeSliderLabel.setForeground( Color.red );
					}
					else
					{
						distanceFadeSliderPanel.setBackground( null );
						distanceFadeSliderLabel.setForeground( null );
					}

					// Temporarily remove listener to avoid overwriting text field
					final ChangeListener[] listeners = distanceFadeSlider.getChangeListeners();
					for ( ChangeListener listener : listeners )
						distanceFadeSlider.removeChangeListener( listener );

					distanceFadeSlider.setValue( sliderValue );

					// Re-add listeners
					for ( ChangeListener listener : listeners )
						distanceFadeSlider.addChangeListener( listener );
				}
				catch ( NumberFormatException ex )
				{
					// Reset to current value from model
					final double fadeFactor = tableModel.getDistanceFade();
					distanceFadeTextField.setText( String.format( "%.2f", fadeFactor ) );
				}
			}
		};
		distanceFadeTextField.addActionListener( distanceFadeTextFieldListener );
		distanceFadeTextField.addFocusListener( new FocusAdapter()
		{
			@Override
			public void focusLost( FocusEvent e )
			{
				distanceFadeTextFieldListener.actionPerformed( null );
			}
		});
		// Initialize text field
		distanceFadeTextField.setText( "0.13" );
		distanceFadeSliderPanel.add( distanceFadeSliderLabel );
		distanceFadeSliderPanel.add( distanceFadeSlider );
		distanceFadeSliderPanel.add( distanceFadeTextField );

		// Add all sliders and text fields to the combined panel
		slidersPanel.add( sizeSliderLabel );
		slidersPanel.add( sizeSlider );
		slidersPanel.add( sizeTextField );
		slidersPanel.add( planeThicknessSliderLabel );
		slidersPanel.add( planeThicknessSlider );
		slidersPanel.add( planeThicknessTextField );
		slidersPanel.add( distanceFadeSliderPanel );

		topPanel.add( slidersPanel, BorderLayout.EAST );

		this.add( topPanel, BorderLayout.NORTH );
		this.add( new JScrollPane( table ), BorderLayout.CENTER );

		table.getColumnModel().getColumn( 0 ).setPreferredWidth( 30 );
		table.getColumnModel().getColumn( 1 ).setPreferredWidth( 5 );
		table.getColumnModel().getColumn( 2 ).setPreferredWidth( 20 );
		table.getColumnModel().getColumn( 3 ).setPreferredWidth( 25 );
		table.getColumnModel().getColumn( 4 ).setPreferredWidth( 30 );
		table.getColumnModel().getColumn( 5 ).setPreferredWidth( 400 );

		table.addMouseListener( new MouseListener()
		{
			@Override
			public void mouseReleased(MouseEvent e) {}
			
			@Override
			public void mousePressed(MouseEvent e) {}
			
			@Override
			public void mouseExited(MouseEvent e) {}
			
			@Override
			public void mouseEntered(MouseEvent e) {}
			
			@Override
			public void mouseClicked(MouseEvent e)
			{
				int row = table.rowAtPoint( e.getPoint() );
				int col = table.columnAtPoint( e.getPoint() );

				// Always pass the click to the table model - it will handle state cycling
				tableModel.setSelected( row, col );

				// update everything
				final int sr = table.getSelectedRow();
				tableModel.fireTableDataChanged();
				table.setRowSelectionInterval( sr, sr );
			}
		});

		addPopupMenu( table );
		addHelpListener( table );

		// Request focus on this panel when the mouse enters, so F1 works
		this.addMouseListener( new MouseListener()
		{
			@Override
			public void mouseReleased(MouseEvent e) {}

			@Override
			public void mousePressed(MouseEvent e) { requestFocusInWindow(); }

			@Override
			public void mouseExited(MouseEvent e) {}

			@Override
			public void mouseEntered(MouseEvent e) { requestFocusInWindow(); }

			@Override
			public void mouseClicked(MouseEvent e) {}
		});
	}

	protected void addHelpListener( final JTable table )
	{
		// Add to both panel and table to catch F1 regardless of focus
		final KeyAdapter helpKeyListener = new KeyAdapter()
		{
			@Override
			public void keyPressed( final KeyEvent e )
			{
				if ( e.getKeyCode() == KeyEvent.VK_F1 || e.getKeyCode() == 112 )
				{
					showHelp();
				}
			}
		};

		table.addKeyListener( helpKeyListener );
		this.addKeyListener( helpKeyListener );
	}

	protected void showHelp()
	{
		new HelpDialog( viewSetupExplorer.getFrame(), InterestPointExplorerPanel.class.getResource( "/mvr/InterestPointHelp.html" ) ).setVisible( true );
	}

	protected static class MyRenderer extends DefaultTableCellRenderer
	{
		private static final long serialVersionUID = 1L;

		Color backgroundColor = getBackground();

		@Override
		public Component getTableCellRendererComponent( JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column )
		{
			final Component c = super.getTableCellRendererComponent( table, value, isSelected, hasFocus, row, column );
			final InterestPointTableModel model = (InterestPointTableModel) table.getModel();

			final int state = model.getState( row, column );

			if ( state == 1 )
				c.setBackground( Color.red );
			else if ( state == 2 )
				c.setBackground( Color.green );
			else if ( !isSelected )
				c.setBackground( backgroundColor );

			return c;
		}
	}

	protected void delete()
	{
		if ( table.getSelectedRowCount() == 0 )
		{
			JOptionPane.showMessageDialog( table, "Nothing selected." );
			return;
		}

		final List< BasicViewDescription< ? > > vds = tableModel.getCurrentViewDescriptions();
		final ViewInterestPoints vip = tableModel.getViewInterestPoints();
		final HashMap< String, Integer > labels = InterestPointTools.getAllInterestPointMap( vip, vds );

		for ( final BasicViewDescription< ? > vd : vds )
		{
			if ( vd == null )
			{
				JOptionPane.showMessageDialog( table, "No active viewdescription." );
				return;
			}
	
			final int[] selectedRows = table.getSelectedRows();
			Arrays.sort( selectedRows );

			for ( int rowIndex = selectedRows.length - 1; rowIndex >= 0; rowIndex--)
			{
				final int row = selectedRows[ rowIndex ];
	
				final String label = InterestPointTableModel.label( labels, row );
	
				IOFunctions.println( "Removing label '" + label + "' for timepoint_id " + vd.getTimePointId() + " viewsetup_id " + vd.getViewSetupId() );

				// Remove symmetric correspondences from other views (multithreaded)
				final int removed = CorrespondenceTools.removeCorrespondencesForViewLabel(
						vip, vd, label, Runtime.getRuntime().availableProcessors() );
				IOFunctions.println( "Removed " + removed + " corresponding entries from other views." );

				// remember to delete the files
				this.delete.add( new ValuePair< InterestPoints, ViewId >(
						vip.getViewInterestPointLists( vd ).getInterestPointList( label ),
						vd ) );
	
				vip.getViewInterestPointLists( vd ).getHashMap().remove( label );

				// drop matching action-history entries so the history reflects current data state
				try
				{
					final net.preibisch.mvrecon.fiji.spimdata.SpimData2 sd = viewSetupExplorer.getSpimData();
					if ( sd != null && sd.getActionHistory() != null )
						sd.getActionHistory().removeByResultRef( "interestpoints:" + label );
				}
				catch ( final Throwable ignore ) {}
			}
		}

		// reset selection
		tableModel.setSelected( -1, -1 );

		// update everything
		tableModel.fireTableDataChanged();
	}

	protected void addPopupMenu( final JTable table )
	{
		final JPopupMenu popupMenu = new JPopupMenu();
		final JMenuItem deleteItem = new JMenuItem( "Delete" );

		deleteItem.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed( final ActionEvent e )
			{
				delete();
				System.out.println( "Right-click performed on table and choose DELETE" );
			}
		});

		popupMenu.add( deleteItem );

		table.setComponentPopupMenu( popupMenu );
	}
}
