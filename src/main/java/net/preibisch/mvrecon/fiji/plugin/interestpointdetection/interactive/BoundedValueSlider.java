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

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.Locale;

import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.plaf.basic.BasicSliderUI;

import bdv.ui.UIUtils;
import net.miginfocom.swing.MigLayout;

/**
 * The brightness slider of BigDataViewer for one value: from left to right a slider (the look of
 * bdv.ui.rangeslider.RangeSliderUI), a small value textbox and the bounds stacked to the right (min above
 * max). Clicking the bounds or right-clicking the slider opens a menu to change them ("set bounds ...",
 * presets, "narrow bounds around the current value"), a value typed outside the bounds extends them.
 * Optionally logarithmic: the slider is then linear in log( value ) and the bounds must be positive.
 *
 * @author Stephan Preibisch
 */
public class BoundedValueSlider extends JPanel
{
	private static final long serialVersionUID = 1L;

	public interface Listener
	{
		void valueChanged( final double value );

		default void boundsChanged( final double min, final double max ) {}
	}

	protected static class Preset
	{
		final String label;
		final double min, max;

		Preset( final String label, final double min, final double max )
		{
			this.label = label;
			this.min = min;
			this.max = max;
		}
	}

	protected static final int sliderLength = 10000;

	final boolean logarithmic;

	/** the smallest lower bound that can be set (0 if linear, a tiny positive number if logarithmic) */
	final double minimumBound;

	final String format;
	final double spinnerStep;

	double value, lowerBound, upperBound;

	final JSlider slider;
	final JSpinner valueSpinner;
	final JLabel lowerBoundLabel, upperBoundLabel;
	boolean updating = false;

	final ArrayList< Listener > listeners = new ArrayList<>();
	final ArrayList< Preset > presets = new ArrayList<>();

	/**
	 * @param value - the initial value (the bounds are extended if it is outside)
	 * @param min - the initial lower bound
	 * @param max - the initial upper bound
	 * @param logarithmic - whether the slider is linear in log( value ) (bounds &gt; 0)
	 * @param format - the format of the textbox and the bounds, e.g. "0.000"
	 * @param spinnerStep - the step of the arrows of the textbox
	 */
	public BoundedValueSlider( final double value, final double min, final double max, final boolean logarithmic, final String format, final double spinnerStep )
	{
		super( new MigLayout( "ins 5 5 5 10, fillx, filly, hidemode 3", "[grow][][]", "[]0[]" ) );

		this.logarithmic = logarithmic;
		this.minimumBound = logarithmic ? 1e-9 : 0;
		this.format = format;
		this.spinnerStep = spinnerStep;

		this.lowerBound = Math.max( minimumBound, Math.min( min, max ) );
		this.upperBound = Math.max( lowerBound * ( logarithmic ? 1.001 : 1 ) + 1e-9, Math.max( min, max ) );
		this.value = Math.min( Math.max( value, lowerBound ), upperBound );

		slider = new JSlider( SwingConstants.HORIZONTAL, 0, sliderLength, 0 );
		slider.setUI( new SingleValueSliderUI( slider ) );
		slider.setFocusable( false );
		UIUtils.setPreferredWidth( slider, 50 );
		slider.addChangeListener( e -> {
			if ( !updating )
				setValue( positionToValue( slider.getValue() ) );
		} );

		// no maximum: the editor then sizes the textbox for the format and not for the maximum
		valueSpinner = new JSpinner( new SpinnerNumberModel( this.value, minimumBound, null, spinnerStep ) );
		valueSpinner.setEditor( new JSpinner.NumberEditor( valueSpinner, format ) );
		valueSpinner.addChangeListener( e -> {
			if ( !updating )
				setValue( (Double)valueSpinner.getValue() );
		} );

		lowerBoundLabel = new JLabel( "", SwingConstants.RIGHT );
		upperBoundLabel = new JLabel( "", SwingConstants.RIGHT );
		final Font font = lowerBoundLabel.getFont().deriveFont( lowerBoundLabel.getFont().getSize2D() * 0.8f );
		lowerBoundLabel.setFont( font );
		upperBoundLabel.setFont( font );
		lowerBoundLabel.setToolTipText( "click to change the bounds" );
		upperBoundLabel.setToolTipText( "click to change the bounds" );

		// as the brightness slider of BigDataViewer: click the bounds or right-click to set them
		final MouseAdapter popupListener = new MouseAdapter()
		{
			@Override
			public void mousePressed( final MouseEvent e )
			{
				if ( e.isPopupTrigger() || ( e.getButton() == MouseEvent.BUTTON1 && ( e.getComponent() == lowerBoundLabel || e.getComponent() == upperBoundLabel ) ) )
					createPopupMenu().show( e.getComponent(), e.getX(), e.getY() );
			}

			@Override
			public void mouseReleased( final MouseEvent e )
			{
				if ( e.isPopupTrigger() )
					createPopupMenu().show( e.getComponent(), e.getX(), e.getY() );
			}
		};

		for ( final Component component : new Component[] { this, slider, lowerBoundLabel, upperBoundLabel } )
			component.addMouseListener( popupListener );

		add( slider, "growx, sy 2" );
		add( valueSpinner, "sy 2" );
		add( lowerBoundLabel, "right, wrap" );
		add( upperBoundLabel, "right" );

		updateWidgets();
	}

	public void addListener( final Listener listener ) { listeners.add( listener ); }

	/**
	 * Adds an entry "set bounds min .. max" to the menu of the bounds
	 */
	public void addPreset( final String label, final double min, final double max ) { presets.add( new Preset( label, min, max ) ); }

	public double getValue() { return value; }

	public double getLowerBound() { return lowerBound; }

	public double getUpperBound() { return upperBound; }

	public boolean isLogarithmic() { return logarithmic; }

	/**
	 * Sets the value (extends the bounds if necessary), updates the widgets and notifies the listeners
	 */
	public void setValue( final double value )
	{
		final double v = Math.max( minimumBound, value );

		if ( v < lowerBound || v > upperBound )
			setBounds( Math.min( lowerBound, v ), Math.max( upperBound, v ) );

		this.value = v;
		updateWidgets();

		for ( final Listener listener : listeners )
			listener.valueChanged( v );
	}

	/**
	 * Sets the bounds (the value is kept if inside, otherwise clamped), updates the widgets and notifies the listeners
	 */
	public void setBounds( final double min, final double max )
	{
		lowerBound = Math.max( minimumBound, Math.min( min, max ) );
		upperBound = Math.max( logarithmic ? lowerBound * 1.001 : lowerBound + 0.001, Math.max( min, max ) );

		final double clamped = Math.min( Math.max( value, lowerBound ), upperBound );
		final boolean valueChanged = clamped != value;

		value = clamped;
		updateWidgets();

		for ( final Listener listener : listeners )
		{
			listener.boundsChanged( lowerBound, upperBound );

			if ( valueChanged )
				listener.valueChanged( value );
		}
	}

	@Override
	public void setEnabled( final boolean enabled )
	{
		super.setEnabled( enabled );
		slider.setEnabled( enabled );
		valueSpinner.setEnabled( enabled );
	}

	protected double positionToValue( final int position )
	{
		final double t = (double)position / sliderLength;

		if ( logarithmic )
			return Math.exp( Math.log( lowerBound ) + t * ( Math.log( upperBound ) - Math.log( lowerBound ) ) );
		else
			return lowerBound + t * ( upperBound - lowerBound );
	}

	protected int valueToPosition( final double value )
	{
		final double t;

		if ( logarithmic )
			t = ( Math.log( value ) - Math.log( lowerBound ) ) / ( Math.log( upperBound ) - Math.log( lowerBound ) );
		else
			t = ( value - lowerBound ) / ( upperBound - lowerBound );

		return (int)Math.round( Math.min( 1, Math.max( 0, t ) ) * sliderLength );
	}

	protected void updateWidgets()
	{
		updating = true;

		slider.setValue( valueToPosition( value ) );
		valueSpinner.setValue( value );
		lowerBoundLabel.setText( format( lowerBound ) );
		upperBoundLabel.setText( format( upperBound ) );

		updating = false;
	}

	protected String format( final double v )
	{
		return new java.text.DecimalFormat( format, java.text.DecimalFormatSymbols.getInstance( Locale.US ) ).format( v );
	}

	protected JPopupMenu createPopupMenu()
	{
		final JPopupMenu menu = new JPopupMenu();

		final JMenuItem setBounds = new JMenuItem( "set bounds ..." );
		setBounds.addActionListener( e -> setBoundsDialog() );
		menu.add( setBounds );

		for ( final Preset preset : presets )
		{
			final JMenuItem item = new JMenuItem( preset.label );
			item.addActionListener( e -> setBounds( preset.min, preset.max ) );
			menu.add( item );
		}

		final JMenuItem narrow = new JMenuItem( "narrow bounds around the current value (/ 1.5 .. x 1.5)" );
		narrow.addActionListener( e -> setBounds( value / 1.5, value * 1.5 ) );
		menu.add( narrow );

		return menu;
	}

	/**
	 * The "Set Bounds" dialog of the brightness slider of BigDataViewer
	 */
	protected void setBoundsDialog()
	{
		final JSpinner minSpinner = new JSpinner( new SpinnerNumberModel( lowerBound, minimumBound, null, spinnerStep ) );
		final JSpinner maxSpinner = new JSpinner( new SpinnerNumberModel( upperBound, minimumBound, null, spinnerStep ) );

		minSpinner.setEditor( new JSpinner.NumberEditor( minSpinner, format ) );
		maxSpinner.setEditor( new JSpinner.NumberEditor( maxSpinner, format ) );
		UIUtils.setPreferredWidth( minSpinner, 100 );
		UIUtils.setPreferredWidth( maxSpinner, 100 );

		minSpinner.addChangeListener( e -> {
			if ( (Double)minSpinner.getValue() > (Double)maxSpinner.getValue() )
				maxSpinner.setValue( minSpinner.getValue() );
		} );

		maxSpinner.addChangeListener( e -> {
			if ( (Double)maxSpinner.getValue() < (Double)minSpinner.getValue() )
				minSpinner.setValue( maxSpinner.getValue() );
		} );

		final JPanel panel = new JPanel( new GridLayout( 2, 2, 4, 4 ) );
		panel.add( new JLabel( "min", SwingConstants.RIGHT ) );
		panel.add( minSpinner );
		panel.add( new JLabel( "max", SwingConstants.RIGHT ) );
		panel.add( maxSpinner );

		if ( JOptionPane.showConfirmDialog( this, panel, "Set Bounds", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE ) == JOptionPane.OK_OPTION )
			setBounds( (Double)minSpinner.getValue(), (Double)maxSpinner.getValue() );
	}

	/**
	 * The look of the range slider of BigDataViewer (bdv.ui.rangeslider.RangeSliderUI) for a single knob:
	 * the plain track and a round, light gray knob with a dark gray outline
	 */
	protected static class SingleValueSliderUI extends BasicSliderUI
	{
		public SingleValueSliderUI( final JSlider slider ) { super( slider ); }

		@Override
		protected Dimension getThumbSize() { return new Dimension( 12, 12 ); }

		@Override
		public void paintFocus( final Graphics g ) {}

		@Override
		public void paintThumb( final Graphics g )
		{
			final Rectangle knobBounds = thumbRect;
			final Graphics2D g2d = (Graphics2D)g.create();

			g2d.setRenderingHint( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
			g2d.translate( knobBounds.x, knobBounds.y );

			final Ellipse2D knob = new Ellipse2D.Double( 0, 0, knobBounds.width - 1, knobBounds.height - 1 );

			g2d.setColor( slider.isEnabled() ? Color.lightGray : Color.white );
			g2d.fill( knob );
			g2d.setColor( slider.isEnabled() ? Color.darkGray : Color.lightGray );
			g2d.draw( knob );

			g2d.dispose();
		}
	}
}
