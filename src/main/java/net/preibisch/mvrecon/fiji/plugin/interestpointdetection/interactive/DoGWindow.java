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

import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;

import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.interactive.InteractiveDoG.ValueChange;

/**
 * The window of the interactive DoG: sliders for sigma and threshold, minima/maxima, Done and Cancel (Swing, packed to its content)
 */
public class DoGWindow
{
	final InteractiveDoG parent;
	final JFrame doGFrame;

	public DoGWindow( final InteractiveDoG parent )
	{
		this.parent = parent;
		this.doGFrame = new JFrame( "Adjust difference-of-gaussian values" );
		this.doGFrame.setDefaultCloseOperation( JFrame.DO_NOTHING_ON_CLOSE );

		/* Instantiation */
		int sliderInitialPosition = HelperFunctions.computeScrollbarPositionFromValue(parent.params.sigma, InteractiveDoG.sigmaMin, InteractiveDoG.sigmaMax, parent.scrollbarSize);
		final JSlider sigma1Bar = new JSlider( SwingConstants.HORIZONTAL, 0, parent.scrollbarSize, sliderInitialPosition );

		final float log1001 = (float) Math.log10(parent.scrollbarSize + 1);
		sliderInitialPosition = (int) Math
				.round(1001 - Math.pow(10, (InteractiveDoG.thresholdMax - parent.params.threshold) / (InteractiveDoG.thresholdMax - InteractiveDoG.thresholdMin) * log1001));
		final JSlider thresholdBar = new JSlider( SwingConstants.HORIZONTAL, 0, parent.scrollbarSize, Math.max( 0, Math.min( parent.scrollbarSize, sliderInitialPosition ) ) );

		for ( final JSlider slider : new JSlider[] { sigma1Bar, thresholdBar } )
		{
			slider.setFocusable( false );
			slider.setPreferredSize( new Dimension( 440, slider.getPreferredSize().height ) );
		}

		final JLabel sigmaText1 = new JLabel("Sigma = " + String.format(java.util.Locale.US, "%.3f", parent.params.sigma), SwingConstants.CENTER);
		final JLabel thresholdText = new JLabel("Threshold = " + String.format(java.util.Locale.US, "%.5f", parent.params.threshold), SwingConstants.CENTER);

		final JButton button = new JButton("Done");
		final JButton cancel = new JButton("Cancel");
		final JCheckBox maxima = new JCheckBox("Find DoG maxima (red)", parent.params.findMaxima);
		final JCheckBox minima = new JCheckBox("Find DoG minima (green)", parent.params.findMinima);

		/* Layout */
		final JPanel panel = new JPanel( new GridBagLayout() );
		final GridBagConstraints c = new GridBagConstraints();

		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridx = 0;
		c.gridy = 0;
		c.gridwidth = 2;
		c.weightx = 1;
		c.insets = new Insets( 4, 8, 0, 8 );
		panel.add(sigmaText1, c);

		++c.gridy;
		c.insets = new Insets( 0, 8, 4, 8 );
		panel.add(sigma1Bar, c);

		++c.gridy;
		c.insets = new Insets( 4, 8, 0, 8 );
		panel.add(thresholdText, c);

		++c.gridy;
		c.insets = new Insets( 0, 8, 4, 8 );
		panel.add(thresholdBar, c);

		final JPanel checkboxes = new JPanel();
		checkboxes.add( maxima );
		checkboxes.add( minima );

		++c.gridy;
		c.insets = new Insets( 0, 8, 0, 8 );
		panel.add( checkboxes, c );

		++c.gridy;
		c.gridwidth = 1;
		c.insets = new Insets( 4, 8, 8, 4 );
		panel.add( button, c );
		c.gridx = 1;
		c.insets = new Insets( 4, 4, 8, 8 );
		panel.add( cancel, c );

		doGFrame.setContentPane( panel );
		doGFrame.pack();

		/* On screen positioning */
		doGFrame.setLocation( 20, 20 );

		/* Configuration */
		sigma1Bar.addChangeListener(new SigmaListener(parent, sigmaText1, InteractiveDoG.sigmaMin, InteractiveDoG.sigmaMax, parent.scrollbarSize, sigma1Bar));
		thresholdBar.addChangeListener(new ThresholdListener(parent, thresholdText, InteractiveDoG.thresholdMin, InteractiveDoG.thresholdMax, thresholdBar));
		maxima.addItemListener( l -> {parent.params.findMaxima = maxima.isSelected(); parent.updatePreview(ValueChange.MINMAX);} );
		minima.addItemListener( l -> {parent.params.findMinima = minima.isSelected(); parent.updatePreview(ValueChange.MINMAX);} );
		button.addActionListener(new FinishedButtonListener(parent, false));
		cancel.addActionListener(new FinishedButtonListener(parent, true));
		doGFrame.addWindowListener(new FrameListener(parent));
	}

	public JFrame getFrame() { return doGFrame; }
}
