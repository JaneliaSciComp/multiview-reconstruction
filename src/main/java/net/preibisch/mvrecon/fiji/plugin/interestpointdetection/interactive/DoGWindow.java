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
package net.preibisch.mvrecon.fiji.plugin.interestpointdetection.interactive;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.event.ChangeListener;

import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.interactive.InteractiveDoG.ValueChange;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;

/**
 * The window of the interactive DoG: sliders for sigma and threshold, each with a text field that also accepts values
 * outside of the slider range, minima/maxima, Done and Cancel (Swing, packed to its content)
 */
public class DoGWindow
{
	final InteractiveDoG parent;
	final JFrame doGFrame;

	// the threshold widgets (a locked threshold is set by the parent when sigma changes), the noise floor button and line, the factor widgets and the lock
	final JSlider thresholdBar, factorBar;
	final JTextField thresholdText, factorText;
	final JButton noiseButton;
	final JLabel noiseLabel;
	final JCheckBox lockBox;

	/** the factor slider is linear from 0 to this (the text takes any value) */
	public static final double factorMax = 50;

	public DoGWindow( final InteractiveDoG parent )
	{
		this.parent = parent;
		this.doGFrame = new JFrame( "Adjust difference-of-gaussian values" );
		this.doGFrame.setDefaultCloseOperation( JFrame.DO_NOTHING_ON_CLOSE );

		/* Instantiation */
		final JSlider sigma1Bar = new JSlider( SwingConstants.HORIZONTAL, 0, parent.scrollbarSize, sigmaPosition( parent.params.sigma ) );
		this.thresholdBar = new JSlider( SwingConstants.HORIZONTAL, 0, parent.scrollbarSize, thresholdPosition( parent.params.threshold ) );
		this.factorBar = new JSlider( SwingConstants.HORIZONTAL, 0, parent.scrollbarSize, 0 );

		for ( final JSlider slider : new JSlider[] { sigma1Bar, thresholdBar, factorBar } )
		{
			slider.setFocusable( false );
			slider.setPreferredSize( new Dimension( 400, slider.getPreferredSize().height ) );
		}

		final JTextField sigmaText1 = new JTextField( HelperFunctions.formatValue( parent.params.sigma, 3 ), 8 );
		this.thresholdText = new JTextField( HelperFunctions.formatValue( parent.params.threshold, 5 ), 8 );
		this.factorText = new JTextField( "", 8 );

		final JButton button = new JButton("Done");
		final JButton cancel = new JButton("Cancel");
		final JCheckBox maxima = new JCheckBox("Find DoG maxima (red)", parent.params.findMaxima);
		final JCheckBox minima = new JCheckBox("Find DoG minima (green)", parent.params.findMinima);

		/* Layout */
		final JPanel panel = new JPanel( new GridBagLayout() );
		final GridBagConstraints c = new GridBagConstraints();

		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridy = 0;
		addRow( panel, c, "Sigma", sigma1Bar, sigmaText1 );

		++c.gridy;
		addRow( panel, c, "Threshold", thresholdBar, thresholdText );

		// the noise floor of all views at the current sigma (the button starts it, it follows sigma from then on), the threshold as a multiple of it on a second slider, and the lock
		final int numViews = parent.noiseEstimator == null ? 0 : parent.noiseEstimator.numViews();
		this.noiseButton = new JButton( "Estimate noise floor (all " + numViews + " views)" );
		this.noiseButton.setToolTipText( "<html>the robust noise of the DoG at the current sigma in random blocks of all views of the detection, the highest of them;<br>it follows sigma from then on and the threshold can be set as a multiple of it (the second slider)</html>" );
		this.noiseButton.setEnabled( parent.noiseEstimator != null );
		this.noiseLabel = new JLabel( " " );
		this.noiseLabel.setFont( GUIHelper.smallStatusFont );
		// the label takes what is left of the row and truncates (the full text is its tooltip): a long text must not widen the row, GridBagLayout would squeeze the text fields to nothing
		this.noiseLabel.setPreferredSize( new Dimension( 10, noiseLabel.getPreferredSize().height ) );
		this.noiseLabel.setMinimumSize( new Dimension( 10, noiseLabel.getPreferredSize().height ) );
		this.lockBox = new JCheckBox( "Lock noise factor", false );
		this.lockBox.setToolTipText( "<html>locked, the noise factor stays when sigma changes and the threshold follows the noise floor;<br>unlocked, the threshold stays and the factor follows</html>" );

		final JPanel noise = new JPanel( new BorderLayout( 8, 0 ) );
		noise.add( noiseButton, BorderLayout.WEST );
		noise.add( noiseLabel, BorderLayout.CENTER );

		++c.gridy;
		c.gridx = 0;
		c.gridwidth = 3;
		c.weightx = 1;
		c.insets = new Insets( 6, 8, 0, 8 );
		panel.add( noise, c );

		++c.gridy;
		addRow( panel, c, "Noise factor", factorBar, factorText );

		++c.gridy;
		c.gridx = 0;
		c.gridwidth = 3;
		c.weightx = 1;
		c.insets = new Insets( 0, 8, 0, 8 );
		panel.add( lockBox, c );
		updateNoise();

		final JPanel checkboxes = new JPanel();
		checkboxes.add( maxima );
		checkboxes.add( minima );

		++c.gridy;
		c.gridx = 0;
		c.gridwidth = 3;
		c.weightx = 1;
		c.insets = new Insets( 0, 8, 0, 8 );
		panel.add( checkboxes, c );

		final JPanel buttons = new JPanel( new GridLayout( 1, 2, 8, 0 ) );
		buttons.add( button );
		buttons.add( cancel );

		++c.gridy;
		c.insets = new Insets( 4, 8, 8, 8 );
		panel.add( buttons, c );

		doGFrame.setContentPane( panel );
		doGFrame.pack();

		/* On screen positioning */
		doGFrame.setLocation( 20, 20 );

		/* Configuration */
		sigma1Bar.addChangeListener(new SigmaListener(parent, sigmaText1, InteractiveDoG.sigmaMin, InteractiveDoG.sigmaMax, parent.scrollbarSize, sigma1Bar));
		thresholdBar.addChangeListener(new ThresholdListener(parent, thresholdText, InteractiveDoG.thresholdMin, InteractiveDoG.thresholdMax, thresholdBar));

		// typed values go to the parameters unclamped, the slider only shows the clamped position
		addTextListener( sigmaText1, 3, () -> parent.params.sigma, sigma -> {
			parent.params.sigma = sigma;
			setSilently( sigma1Bar, sigmaPosition( sigma ) );
			parent.updatePreview( ValueChange.SIGMA );
		});

		addTextListener( thresholdText, 5, () -> parent.params.threshold, threshold -> {
			parent.params.threshold = threshold;
			setSilently( thresholdBar, thresholdPosition( threshold ) );
			parent.updatePreview( ValueChange.THRESHOLD );
		});

		noiseButton.addActionListener( l -> parent.estimateNoise() );
		lockBox.addItemListener( l -> parent.setLocked( lockBox.isSelected() ) );

		// the factor slider (linear 0 .. factorMax) and its text set the threshold to factor x noise floor
		factorBar.addChangeListener( l ->
		{
			if ( !parent.isComputing )
				parent.setNoiseFactor( factorBar.getValue() * factorMax / parent.scrollbarSize, true );
		} );

		addTextListener( factorText, 2, () -> parent.noiseFactor, factor -> parent.setNoiseFactor( factor, true ) );
		maxima.addItemListener( l -> {parent.params.findMaxima = maxima.isSelected(); parent.updatePreview(ValueChange.MINMAX);} );
		minima.addItemListener( l -> {parent.params.findMinima = minima.isSelected(); parent.updatePreview(ValueChange.MINMAX);} );
		button.addActionListener(new FinishedButtonListener(parent, false));
		cancel.addActionListener(new FinishedButtonListener(parent, true));
		doGFrame.addWindowListener(new FrameListener(parent));
	}

	protected int sigmaPosition( final double sigma )
	{
		final int position = HelperFunctions.computeScrollbarPositionFromValue( sigma, InteractiveDoG.sigmaMin, InteractiveDoG.sigmaMax, parent.scrollbarSize );
		return Math.max( 0, Math.min( parent.scrollbarSize, position ) );
	}

	protected static int thresholdPosition( final double threshold )
	{
		return HelperFunctions.computeThresholdScrollbarPositionFromValue( threshold, InteractiveDoG.thresholdMin, InteractiveDoG.thresholdMax );
	}

	protected static void addRow( final JPanel panel, final GridBagConstraints c, final String name, final JSlider slider, final JTextField text )
	{
		c.gridx = 0;
		c.gridwidth = 1;
		c.weightx = 0;
		c.insets = new Insets( 6, 8, 2, 4 );
		panel.add( new JLabel( name ), c );

		c.gridx = 1;
		c.weightx = 1;
		c.insets = new Insets( 6, 4, 2, 4 );
		panel.add( slider, c );

		c.gridx = 2;
		c.weightx = 0;
		c.insets = new Insets( 6, 4, 2, 8 );
		// a text field shrinks to nothing when the window is short of space, so its minimum is its preferred width
		text.setMinimumSize( text.getPreferredSize() );
		panel.add( text, c );
	}

	/**
	 * Applies a typed value (any positive number, also outside of the slider range) on Enter or when the field loses the
	 * focus; invalid input restores the current value.
	 */
	protected static void addTextListener(
			final JTextField text,
			final int decimals,
			final DoubleSupplier current,
			final DoubleConsumer apply )
	{
		final Runnable commit = () ->
		{
			final double value;

			try
			{
				value = Double.parseDouble( text.getText().trim().replace( ',', '.' ) );
			}
			catch ( final NumberFormatException e )
			{
				text.setText( HelperFunctions.formatValue( current.getAsDouble(), decimals ) );
				return;
			}

			if ( !( value > 0 ) || Double.isInfinite( value ) )
			{
				text.setText( HelperFunctions.formatValue( current.getAsDouble(), decimals ) );
				return;
			}

			// nothing to recompute if the displayed value did not change (e.g. focus lost after Enter or after moving the slider)
			if ( !HelperFunctions.formatValue( value, decimals ).equals( HelperFunctions.formatValue( current.getAsDouble(), decimals ) ) )
				apply.accept( value );

			text.setText( HelperFunctions.formatValue( current.getAsDouble(), decimals ) );
		};

		text.addActionListener( e -> commit.run() );
		text.addFocusListener( new FocusAdapter()
		{
			@Override
			public void focusLost( final FocusEvent e ) { commit.run(); }
		});
	}

	/** sets the slider position without notifying its listeners, which would overwrite the typed value with the clamped one */
	protected static void setSilently( final JSlider slider, final int position )
	{
		final ChangeListener[] listeners = slider.getChangeListeners();

		for ( final ChangeListener l : listeners )
			slider.removeChangeListener( l );

		slider.setValue( position );

		for ( final ChangeListener l : listeners )
			slider.addChangeListener( l );
	}

	/**
	 * Shows a threshold set by the parent (a locked threshold following sigma) in the text and the slider without triggering the listeners
	 */
	public void showThreshold( final double threshold )
	{
		thresholdText.setText( HelperFunctions.formatValue( threshold, 5 ) );
		setSilently( thresholdBar, thresholdPosition( threshold ) );
	}

	/**
	 * The noise line (not estimated, estimating, or the floor of all views at the current sigma and the threshold as a
	 * multiple of it), the factor widgets and the lock, which are usable once there is a floor
	 */
	public void updateNoise()
	{
		final double floor = parent.noiseFloor;
		final boolean available = floor > 0;

		final String text;

		if ( parent.noiseEstimator == null )
			text = "no noise floor (no views to estimate it from)";
		else if ( !parent.noiseWanted )
			text = "noise floor: not estimated";
		else if ( !Double.isNaN( parent.noiseSigmaRunning ) )
			text = "estimating at sigma " + HelperFunctions.formatValue( parent.noiseSigmaRunning, 2 ) + " ...";
		else if ( !available )
			text = "not available at sigma " + HelperFunctions.formatValue( parent.params.sigma, 2 ) + " (see the log)";
		else if ( parent.noiseSigma != parent.params.sigma )
			text = "at sigma " + HelperFunctions.formatValue( parent.noiseSigma, 2 ) + ": " + HelperFunctions.formatValue( floor, 5 ) + " (sigma changed, updating when it settles)";
		else
			text = "at sigma " + HelperFunctions.formatValue( parent.noiseSigma, 2 ) + ": " + HelperFunctions.formatValue( floor, 5 ) + ", threshold = " + HelperFunctions.formatValue( parent.noiseFactor, 2 ) + " x floor";

		noiseLabel.setText( text );
		noiseLabel.setToolTipText( text );

		factorBar.setEnabled( available );
		factorText.setEnabled( available );
		lockBox.setEnabled( available );

		if ( available )
		{
			factorText.setText( HelperFunctions.formatValue( parent.noiseFactor, 2 ) );
			setSilently( factorBar, (int)Math.round( Math.max( 0, Math.min( 1, parent.noiseFactor / factorMax ) ) * parent.scrollbarSize ) );
		}
	}

	public JFrame getFrame() { return doGFrame; }
}
