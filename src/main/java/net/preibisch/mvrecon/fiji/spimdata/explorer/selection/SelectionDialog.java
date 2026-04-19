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
package net.preibisch.mvrecon.fiji.spimdata.explorer.selection;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewSetup;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;

/**
 * Dialog for selecting views based on predefined ranges and values.
 * Supports comma-separated lists, ranges (e.g., 4-10), and combinations.
 * Allows AND/OR logic for combining multiple attribute selections.
 */
public class SelectionDialog extends JDialog
{
	private static final long serialVersionUID = 1L;

	// Static variables to remember last settings
	private static String lastTimepoint = "";
	private static String lastViewSetup = "";
	private static String lastAngle = "";
	private static String lastChannel = "";
	private static String lastIllumination = "";
	private static String lastTile = "";
	private static boolean lastUseAnd = true;

	private final SpimData2 data;
	private final JFrame parent;

	// Input fields for each attribute
	private JTextField timepointField;
	private JTextField viewSetupField;
	private JTextField angleField;
	private JTextField channelField;
	private JTextField illuminationField;
	private JTextField tileField;

	// Radio buttons for combination logic
	private JRadioButton andButton;
	private JRadioButton orButton;

	// Result
	private List<BasicViewDescription<?>> selectedViews = null;
	private boolean wasCanceled = true;

	public SelectionDialog( final JFrame parent, final SpimData2 data )
	{
		super( parent, "Select Views by Range", true );
		this.parent = parent;
		this.data = data;

		initComponents();
		pack();
		setLocationRelativeTo( parent );
	}

	private void initComponents()
	{
		final JPanel mainPanel = new JPanel( new BorderLayout( 10, 10 ) );
		mainPanel.setBorder( BorderFactory.createEmptyBorder( 10, 10, 10, 10 ) );

		// Instructions
		final JLabel instructions = new JLabel(
			"<html><b>Enter ranges (e.g., 4-10) or comma-separated values (e.g., 1,6,10,100,2).</b><br>" +
			"Leave fields empty to ignore that attribute.</html>" );
		mainPanel.add( instructions, BorderLayout.NORTH );

		// Input panel
		final JPanel inputPanel = new JPanel( new GridBagLayout() );
		final GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets( 5, 5, 5, 5 );
		gbc.anchor = GridBagConstraints.WEST;
		gbc.fill = GridBagConstraints.HORIZONTAL;

		int row = 0;

		// Timepoint
		gbc.gridx = 0; gbc.gridy = row;
		inputPanel.add( new JLabel( "Timepoint:" ), gbc );
		gbc.gridx = 1; gbc.weightx = 1.0;
		timepointField = new JTextField( 20 );
		inputPanel.add( timepointField, gbc );

		// ViewSetup
		row++;
		gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
		inputPanel.add( new JLabel( "ViewSetup:" ), gbc );
		gbc.gridx = 1; gbc.weightx = 1.0;
		viewSetupField = new JTextField( 20 );
		inputPanel.add( viewSetupField, gbc );

		// Angle
		row++;
		gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
		inputPanel.add( new JLabel( "Angle:" ), gbc );
		gbc.gridx = 1; gbc.weightx = 1.0;
		angleField = new JTextField( 20 );
		inputPanel.add( angleField, gbc );

		// Channel
		row++;
		gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
		inputPanel.add( new JLabel( "Channel:" ), gbc );
		gbc.gridx = 1; gbc.weightx = 1.0;
		channelField = new JTextField( 20 );
		inputPanel.add( channelField, gbc );

		// Illumination
		row++;
		gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
		inputPanel.add( new JLabel( "Illumination:" ), gbc );
		gbc.gridx = 1; gbc.weightx = 1.0;
		illuminationField = new JTextField( 20 );
		inputPanel.add( illuminationField, gbc );

		// Tile
		row++;
		gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
		inputPanel.add( new JLabel( "Tile:" ), gbc );
		gbc.gridx = 1; gbc.weightx = 1.0;
		tileField = new JTextField( 20 );
		inputPanel.add( tileField, gbc );

		mainPanel.add( inputPanel, BorderLayout.CENTER );

		// Combination logic panel
		final JPanel logicPanel = new JPanel( new GridBagLayout() );
		logicPanel.setBorder( BorderFactory.createTitledBorder( "Combine multiple attributes with:" ) );
		andButton = new JRadioButton( "AND (views must match all specified attributes)", true );
		orButton = new JRadioButton( "OR (views can match any specified attribute)" );
		final ButtonGroup group = new ButtonGroup();
		group.add( andButton );
		group.add( orButton );

		final GridBagConstraints gbcLogic = new GridBagConstraints();
		gbcLogic.gridx = 0;
		gbcLogic.gridy = 0;
		gbcLogic.anchor = GridBagConstraints.WEST;
		gbcLogic.insets = new Insets( 5, 5, 5, 5 );
		logicPanel.add( andButton, gbcLogic );

		gbcLogic.gridy = 1;
		logicPanel.add( orButton, gbcLogic );

		mainPanel.add( logicPanel, BorderLayout.SOUTH );

		// Buttons
		final JPanel buttonPanel = new JPanel();
		final JButton okButton = new JButton( "OK" );
		final JButton cancelButton = new JButton( "Cancel" );

		okButton.addActionListener( new ActionListener()
		{
			@Override
			public void actionPerformed( ActionEvent e )
			{
				if ( processSelection() )
				{
					wasCanceled = false;
					dispose();
				}
			}
		});

		cancelButton.addActionListener( new ActionListener()
		{
			@Override
			public void actionPerformed( ActionEvent e )
			{
				wasCanceled = true;
				dispose();
			}
		});

		buttonPanel.add( okButton );
		buttonPanel.add( cancelButton );

		final JPanel southPanel = new JPanel( new BorderLayout() );
		southPanel.add( logicPanel, BorderLayout.CENTER );
		southPanel.add( buttonPanel, BorderLayout.SOUTH );
		mainPanel.add( southPanel, BorderLayout.SOUTH );

		getContentPane().add( mainPanel );
		setPreferredSize( new Dimension( 500, 450 ) );

		// Initialize fields with last settings
		timepointField.setText( lastTimepoint );
		viewSetupField.setText( lastViewSetup );
		angleField.setText( lastAngle );
		channelField.setText( lastChannel );
		illuminationField.setText( lastIllumination );
		tileField.setText( lastTile );

		if ( lastUseAnd )
			andButton.setSelected( true );
		else
			orButton.setSelected( true );
	}

	private boolean processSelection()
	{
		try
		{
			// Parse input fields
			final Set<Integer> timepoints = parseInput( timepointField.getText() );
			final Set<Integer> viewSetups = parseInput( viewSetupField.getText() );
			final Set<Integer> angles = parseInput( angleField.getText() );
			final Set<Integer> channels = parseInput( channelField.getText() );
			final Set<Integer> illuminations = parseInput( illuminationField.getText() );
			final Set<Integer> tiles = parseInput( tileField.getText() );

			final boolean useAnd = andButton.isSelected();

			// Select matching views
			selectedViews = new ArrayList<>();
			for ( final BasicViewDescription<?> vd : data.getSequenceDescription().getViewDescriptions().values() )
			{
				if ( !vd.isPresent() )
					continue;

				if ( matchesSelection( vd, timepoints, viewSetups, angles, channels, illuminations, tiles, useAnd ) )
					selectedViews.add( vd );
			}

			if ( selectedViews.isEmpty() )
			{
				JOptionPane.showMessageDialog( this,
					"No views match the specified criteria.",
					"No Matches",
					JOptionPane.WARNING_MESSAGE );
				return false;
			}

			// Save settings for next time
			lastTimepoint = timepointField.getText();
			lastViewSetup = viewSetupField.getText();
			lastAngle = angleField.getText();
			lastChannel = channelField.getText();
			lastIllumination = illuminationField.getText();
			lastTile = tileField.getText();
			lastUseAnd = useAnd;

			return true;
		}
		catch ( Exception e )
		{
			JOptionPane.showMessageDialog( this,
				"Error parsing input: " + e.getMessage(),
				"Parse Error",
				JOptionPane.ERROR_MESSAGE );
			return false;
		}
	}

	private boolean matchesSelection(
			final BasicViewDescription<?> vd,
			final Set<Integer> timepoints,
			final Set<Integer> viewSetups,
			final Set<Integer> angles,
			final Set<Integer> channels,
			final Set<Integer> illuminations,
			final Set<Integer> tiles,
			final boolean useAnd )
	{
		// Get view attributes
		final int tp = vd.getTimePointId();
		final int vs = vd.getViewSetupId();

		final ViewSetup viewSetup = data.getSequenceDescription().getViewSetups().get( vs );
		final Integer angleId = viewSetup.getAngle() != null ? viewSetup.getAngle().getId() : null;
		final Integer channelId = viewSetup.getChannel() != null ? viewSetup.getChannel().getId() : null;
		final Integer illuminationId = viewSetup.getIllumination() != null ? viewSetup.getIllumination().getId() : null;
		final Integer tileId = viewSetup.getTile() != null ? viewSetup.getTile().getId() : null;

		// Check matches
		final boolean tpMatch = timepoints == null || timepoints.contains( tp );
		final boolean vsMatch = viewSetups == null || viewSetups.contains( vs );
		final boolean angleMatch = angles == null || (angleId != null && angles.contains( angleId ));
		final boolean channelMatch = channels == null || (channelId != null && channels.contains( channelId ));
		final boolean illuminationMatch = illuminations == null || (illuminationId != null && illuminations.contains( illuminationId ));
		final boolean tileMatch = tiles == null || (tileId != null && tiles.contains( tileId ));

		if ( useAnd )
		{
			// AND: all specified attributes must match
			return tpMatch && vsMatch && angleMatch && channelMatch && illuminationMatch && tileMatch;
		}
		else
		{
			// OR: at least one specified attribute must match
			// Only check attributes that were actually specified (not null)
			boolean anySpecified = false;
			boolean anyMatch = false;

			if ( timepoints != null ) { anySpecified = true; if ( tpMatch ) anyMatch = true; }
			if ( viewSetups != null ) { anySpecified = true; if ( vsMatch ) anyMatch = true; }
			if ( angles != null ) { anySpecified = true; if ( angleMatch ) anyMatch = true; }
			if ( channels != null ) { anySpecified = true; if ( channelMatch ) anyMatch = true; }
			if ( illuminations != null ) { anySpecified = true; if ( illuminationMatch ) anyMatch = true; }
			if ( tiles != null ) { anySpecified = true; if ( tileMatch ) anyMatch = true; }

			return anySpecified && anyMatch;
		}
	}

	/**
	 * Parse input string supporting ranges (4-10) and comma-separated values (1,6,10,100,2).
	 * Returns null if input is empty/whitespace only.
	 */
	private Set<Integer> parseInput( final String input ) throws NumberFormatException
	{
		if ( input == null || input.trim().isEmpty() )
			return null;

		final Set<Integer> values = new HashSet<>();
		final String[] parts = input.split( "," );

		for ( String part : parts )
		{
			part = part.trim();
			if ( part.isEmpty() )
				continue;

			// Check for range (e.g., "4-10")
			if ( part.contains( "-" ) )
			{
				final String[] range = part.split( "-" );
				if ( range.length != 2 )
					throw new NumberFormatException( "Invalid range format: " + part );

				final int start = Integer.parseInt( range[0].trim() );
				final int end = Integer.parseInt( range[1].trim() );

				if ( start > end )
					throw new NumberFormatException( "Invalid range (start > end): " + part );

				for ( int i = start; i <= end; i++ )
					values.add( i );
			}
			else
			{
				// Single value
				values.add( Integer.parseInt( part ) );
			}
		}

		return values.isEmpty() ? null : values;
	}

	public List<BasicViewDescription<?>> getSelectedViews()
	{
		return selectedViews;
	}

	public boolean wasCanceled()
	{
		return wasCanceled;
	}
}
