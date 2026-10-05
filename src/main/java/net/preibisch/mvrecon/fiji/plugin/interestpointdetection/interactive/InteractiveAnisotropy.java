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
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Ellipse2D;
import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicSliderUI;

import bdv.tools.transformation.TransformedSource;
import bdv.ui.BdvDefaultCards;
import bdv.ui.UIUtils;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvHandle;
import bdv.util.BdvHandleFrame;
import bdv.util.BdvStackSource;
import bdv.viewer.Interpolation;
import bdv.viewer.ViewerPanel;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.converter.Converters;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.miginfocom.swing.MigLayout;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.interactive.MultiResolutionSource;
import net.preibisch.mvrecon.fiji.plugin.interactive.MultiResolutionTools;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.ScaleSpaceGUI;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

/**
 * Interactive estimation of the anisotropy of a view (z voxel / xy voxel at full resolution) in
 * BigDataViewer: the view is shown on its raw pixel grid as a side view, one slider scales it in z in
 * real time, the anisotropy is right when round structures (beads) look round (shift+X, shift+Y).
 * The slider looks and behaves like the brightness slider of BigDataViewer: value textbox next to
 * it, the bounds stacked to the right (click them or right-click the slider to change them, a value
 * typed outside the bounds extends them). The confirmed value is logged, kept in
 * {@link #lastAnisotropy} and set as the default of the scale-space detection dialog (ScaleSpaceGUI).
 *
 * @author Stephan Preibisch
 */
public class InteractiveAnisotropy
{
	/** the last value confirmed with Done or by closing the window, NaN if none yet */
	public static double lastAnisotropy = Double.NaN;

	protected static final int sliderLength = 10000;

	final BasicViewDescription< ? > vd;
	final double calibration;

	final BdvStackSource< ? > bdv;
	final TransformedSource< ? > transformedSource;

	double anisotropy, lowerBound, upperBound;
	boolean finished = false;

	// the widgets of the card
	JSlider slider;
	JSpinner valueSpinner;
	JLabel lowerBoundLabel, upperBoundLabel;
	boolean updating = false;

	public InteractiveAnisotropy( final AbstractSpimData< ? > data, final ViewId view )
	{
		this.vd = data.getSequenceDescription().getViewDescriptions().get( view );
		this.calibration = calibrationAnisotropy( vd );
		this.anisotropy = calibration;
		this.lowerBound = 0.25;
		this.upperBound = Math.max( 5, 2 * calibration );

		final String name = Group.pvid( view );

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Opening " + name + " on its raw pixel grid in BigDataViewer, anisotropy z / xy of the calibration = " + calibration );

		final List< Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > > multiRes = rawMultiResolution( data, view );
		final double[] minmax = FusionTools.minMaxApprox1( multiRes.get( multiRes.size() - 1 ).getA() );

		this.bdv = BdvFunctions.show( new MultiResolutionSource( MultiResolutionTools.createVolatileRAIs( multiRes ), name ), Bdv.options().frameTitle( "Anisotropy: " + name ) );
		this.bdv.setDisplayRange( minmax[ 0 ], minmax[ 1 ] );
		this.transformedSource = (TransformedSource< ? >)bdv.getSources().get( 0 ).getSpimSource();

		setAnisotropy( calibration );

		final BdvHandle handle = bdv.getBdvHandle();

		// Set the interpolation mode to LINEAR
		handle.getViewerPanel().setInterpolation( Interpolation.NLINEAR );

		handle.getCardPanel().addCard( "anisotropy", "Anisotropy (z / xy voxel at full resolution)", createPanel(), true, new Insets( 0, 4, 0, 0 ) );
		handle.getCardPanel().setCardExpanded( BdvDefaultCards.DEFAULT_SOURCES_CARD, false );
		handle.getCardPanel().setCardExpanded( BdvDefaultCards.DEFAULT_SOURCEGROUPS_CARD, false );
		handle.getCardPanel().setCardExpanded( BdvDefaultCards.DEFAULT_VIEWERMODES_CARD, false );
		handle.getSplitPanel().setCollapsed( false );

		// the card panel takes space from the viewer: the layout posts a resize event for the display, on which
		// BigDataViewer rescales the view (TransformEventHandler3D.setCanvasSize), so the stack is fitted only after that
		SwingUtilities.invokeLater( () ->
		{
			final Component root = SwingUtilities.getRoot( handle.getViewerPanel() );

			if ( root != null )
				root.validate();

			SwingUtilities.invokeLater( this::fitSideView );
		} );

		if ( BdvHandleFrame.class.isInstance( handle ) )
		{
			( (BdvHandleFrame)handle ).getBigDataViewer().getViewerFrame().addWindowListener( new WindowAdapter()
			{
				@Override
				public void windowClosing( final WindowEvent e ) { finish( false ); }

				@Override
				public void windowClosed( final WindowEvent e ) { finish( false ); }
			} );
		}
	}

	/**
	 * Scales the displayed view in z (the source transform becomes diag( 1, 1, a ) times the mipmap transform) and repaints
	 */
	public void setAnisotropy( final double a )
	{
		this.anisotropy = a;

		// a scaling of 0 is not invertible, the display uses a tiny one instead
		transformedSource.setFixedTransform( anisotropyTransform( Math.max( a, 0.001 ) ) );
		bdv.getBdvHandle().getViewerPanel().requestRepaint();
	}

	/**
	 * Shows the whole stack as a side view (xz, as shift+Y in BigDataViewer) at the current anisotropy
	 */
	public void fitSideView()
	{
		final ViewerPanel viewer = bdv.getBdvHandle().getViewerPanel();
		final int w = viewer.getDisplayComponent().getWidth();
		final int h = viewer.getDisplayComponent().getHeight();

		if ( w <= 0 || h <= 0 )
			return;

		// the stack in global coordinates (its raw pixel grid scaled in z by the anisotropy)
		final int t = viewer.state().getCurrentTimepoint();
		final AffineTransform3D sourceTransform = new AffineTransform3D();
		transformedSource.getSourceTransform( t, 0, sourceTransform );
		final RealInterval bounds = sourceTransform.estimateBounds( transformedSource.getSource( t, 0 ) );

		final double cx = ( bounds.realMin( 0 ) + bounds.realMax( 0 ) ) / 2;
		final double cy = ( bounds.realMin( 1 ) + bounds.realMax( 1 ) ) / 2;
		final double cz = ( bounds.realMin( 2 ) + bounds.realMax( 2 ) ) / 2;
		final double scale = Math.min( w / ( bounds.realMax( 0 ) - bounds.realMin( 0 ) ), h / ( bounds.realMax( 2 ) - bounds.realMin( 2 ) ) );

		// x to the right, z up (the orientation of shift+Y), the central xz plane of the stack in the center of the display
		final AffineTransform3D viewerTransform = new AffineTransform3D();
		viewerTransform.set(
				scale, 0, 0, w / 2.0 - scale * cx,
				0, 0, -scale, h / 2.0 + scale * cz,
				0, scale, 0, -scale * cy );

		viewer.state().setViewerTransform( viewerTransform );
	}

	/**
	 * Sets the value (extends the bounds if necessary) and updates the slider, the textbox and the display
	 */
	public void setValue( final double value )
	{
		if ( value < lowerBound || value > upperBound )
			setBounds( Math.min( lowerBound, value ), Math.max( upperBound, value ) );

		setAnisotropy( value );
		updateWidgets();
	}

	/**
	 * Sets the bounds of the slider (the value is kept if inside, otherwise clamped)
	 */
	public void setBounds( final double min, final double max )
	{
		lowerBound = Math.max( 0, Math.min( min, max ) );
		upperBound = Math.max( lowerBound + 0.001, Math.max( min, max ) );

		setAnisotropy( Math.min( Math.max( anisotropy, lowerBound ), upperBound ) );
		updateWidgets();
	}

	public double getAnisotropy() { return anisotropy; }

	public double getCalibration() { return calibration; }

	public double getLowerBound() { return lowerBound; }

	public double getUpperBound() { return upperBound; }

	public boolean isFinished() { return finished; }

	protected synchronized void finish( final boolean close )
	{
		if ( finished )
			return;

		finished = true;
		lastAnisotropy = anisotropy;

		// pre-fill the anisotropy of the scale-space detection dialog (it keeps the value while the calibration stays the same)
		ScaleSpaceGUI.defaultAnisotropyZ = anisotropy;
		ScaleSpaceGUI.defaultAnisotropyCalibration = calibration;

		IOFunctions.println(
				"(" + new Date( System.currentTimeMillis() ) + "): anisotropy (z / xy voxel at full resolution) of " + 
				Group.pvid( vd ) + " = " + anisotropy + " (calibration: " + calibration + "), set as default of the scale-space detection." );

		if ( close )
			bdv.getBdvHandle().close();
	}

	protected void updateWidgets()
	{
		if ( slider == null )
			return;

		updating = true;

		slider.setValue( (int)Math.round( ( anisotropy - lowerBound ) / ( upperBound - lowerBound ) * sliderLength ) );
		valueSpinner.setValue( anisotropy );
		lowerBoundLabel.setText( String.format( Locale.US, "%.3f", lowerBound ) );
		upperBoundLabel.setText( String.format( Locale.US, "%.3f", upperBound ) );

		updating = false;
	}

	protected JPanel createPanel()
	{
		final JPanel panel = new JPanel( new GridBagLayout() );
		final GridBagConstraints c = new GridBagConstraints();

		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		c.gridx = 0;
		c.gridy = 0;
		c.gridwidth = 2;
		c.insets = new Insets( 2, 2, 2, 2 );

		JLabel l = 
				new JLabel(
						"<html>"
						+ "Adjust slider to identify the <i>effective "
						+ "anisotropy</i> of the data (undersampling in z vs. PSF), "
						+ "features to detect should look approx. round in the axial view."
						+ "</html>");
		l.setFont( GUIHelper.smallStatusFont );
		panel.add( l, c);

		++c.gridy;
		panel.add( createSliderPanel(), c );

		++c.gridy;
		l = new JLabel( "<html>calibration (z/xy): <b>" + String.format( Locale.US, "%.3f", calibration ) + "</b>. Press 'Done' to update the ScaleSpace dialog.</html>");
		l.setFont( GUIHelper.smallStatusFont );
		panel.add( l, c );

		final JButton reset = new JButton( "Reset to calibration" );
		reset.addActionListener( e -> setValue( calibration ) );

		final JButton done = new JButton( "Done" );
		done.addActionListener( e -> finish( true ) );

		++c.gridy;
		c.gridwidth = 1;
		panel.add( reset, c );
		c.gridx = 1;
		panel.add( done, c );

		return panel;
	}

	/**
	 * The brightness slider of BigDataViewer for one value: slider, value textbox, the bounds stacked to the right
	 */
	protected JPanel createSliderPanel()
	{
		final JPanel panel = new JPanel( new MigLayout( "ins 5 5 5 10, fillx, filly, hidemode 3", "[grow][][]", "[]0[]" ) );

		slider = new JSlider( SwingConstants.HORIZONTAL, 0, sliderLength, 0 );
		slider.setUI( new SingleValueSliderUI( slider ) );
		slider.setFocusable( false );
		UIUtils.setPreferredWidth( slider, 50 );
		slider.addChangeListener( e -> {
			if ( !updating )
				setValue( lowerBound + ( (double)slider.getValue() / sliderLength ) * ( upperBound - lowerBound ) );
		} );

		// no maximum: the editor then sizes the textbox for "0.000" and not for the maximum
		valueSpinner = new JSpinner( new SpinnerNumberModel( anisotropy, 0.0, null, 0.01 ) );
		valueSpinner.setEditor( new JSpinner.NumberEditor( valueSpinner, "0.000" ) );
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

		for ( final Component component : new Component[] { panel, slider, lowerBoundLabel, upperBoundLabel } )
			component.addMouseListener( popupListener );

		panel.add( slider, "growx, sy 2" );
		panel.add( valueSpinner, "sy 2" );
		panel.add( lowerBoundLabel, "right, wrap" );
		panel.add( upperBoundLabel, "right" );

		updateWidgets();

		return panel;
	}

	protected JPopupMenu createPopupMenu()
	{
		final JPopupMenu menu = new JPopupMenu();

		final JMenuItem setBounds = new JMenuItem( "set bounds ..." );
		setBounds.addActionListener( e -> setBoundsDialog() );
		menu.add( setBounds );

		final JMenuItem bounds1 = new JMenuItem( "set bounds 0 .. 10" );
		bounds1.addActionListener( e -> setBounds( 0, 10 ) );
		menu.add( bounds1 );

		final JMenuItem bounds2 = new JMenuItem( "set bounds " + String.format( Locale.US, "%.3f .. %.3f", 0.5 * calibration, 2 * calibration ) + " (calibration / 2 .. x 2)" );
		bounds2.addActionListener( e -> setBounds( 0.5 * calibration, 2 * calibration ) );
		menu.add( bounds2 );

		final JMenuItem narrow = new JMenuItem( "narrow bounds around the current value (/ 1.5 .. x 1.5)" );
		narrow.addActionListener( e -> setBounds( anisotropy / 1.5, anisotropy * 1.5 ) );
		menu.add( narrow );

		return menu;
	}

	/**
	 * The "Set Bounds" dialog of the brightness slider of BigDataViewer
	 */
	protected void setBoundsDialog()
	{
		final JSpinner minSpinner = new JSpinner( new SpinnerNumberModel( lowerBound, 0.0, 1000000.0, 0.1 ) );
		final JSpinner maxSpinner = new JSpinner( new SpinnerNumberModel( upperBound, 0.0, 1000000.0, 0.1 ) );

		minSpinner.setEditor( new JSpinner.NumberEditor( minSpinner, "0.000" ) );
		maxSpinner.setEditor( new JSpinner.NumberEditor( maxSpinner, "0.000" ) );

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

		if ( JOptionPane.showConfirmDialog( null, panel, "Set Bounds", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE ) == JOptionPane.OK_OPTION )
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

	/**
	 * @return diag( 1, 1, a )
	 */
	public static AffineTransform3D anisotropyTransform( final double a )
	{
		final AffineTransform3D t = new AffineTransform3D();
		t.set( a, 2, 2 );
		return t;
	}

	/**
	 * @return z voxel / x voxel of the calibration of the view (1 if unknown)
	 */
	public static double calibrationAnisotropy( final BasicViewDescription< ? > vd )
	{
		return vd.getViewSetup().hasVoxelSize() ? calibrationAnisotropy( vd.getViewSetup().getVoxelSize() ) : 1.0;
	}

	public static double calibrationAnisotropy( final VoxelDimensions voxelSize )
	{
		return voxelSize.dimension( 2 ) / voxelSize.dimension( 0 );
	}

	/**
	 * @return the resolution levels of a view on its raw pixel grid (image and mipmap transform per level), a single level with the identity if the loader has no pyramid
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static List< Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > > rawMultiResolution( final AbstractSpimData< ? > data, final ViewId view )
	{
		final BasicImgLoader imgLoader = data.getSequenceDescription().getImgLoader();
		final ArrayList< Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > > multiRes = new ArrayList<>();

		if ( MultiResolutionImgLoader.class.isInstance( imgLoader ) )
		{
			final MultiResolutionSetupImgLoader< ? > setupLoader = ( (MultiResolutionImgLoader)imgLoader ).getSetupImgLoader( view.getViewSetupId() );
			final AffineTransform3D[] transforms = setupLoader.getMipmapTransforms();

			for ( int level = 0; level < transforms.length; ++level )
				multiRes.add( new ValuePair<>( toFloat( setupLoader.getImage( view.getTimePointId(), level ) ), transforms[ level ].copy() ) );
		}
		else
		{
			multiRes.add( new ValuePair<>( toFloat( (RandomAccessibleInterval)imgLoader.getSetupImgLoader( view.getViewSetupId() ).getImage( view.getTimePointId() ) ), new AffineTransform3D() ) );
		}

		return multiRes;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	protected static RandomAccessibleInterval< FloatType > toFloat( final RandomAccessibleInterval img )
	{
		return Converters.convertRAI( (RandomAccessibleInterval< RealType >)img, ( i, o ) -> o.set( i.getRealFloat() ), new FloatType() );
	}

	public static void main( final String[] args ) throws Exception
	{
		if ( args.length < 1 )
		{
			System.out.println( "usage: InteractiveAnisotropy dataset.xml [viewSetupId] [timepointId]" );
			return;
		}

		final SpimData2 data = new XmlIoSpimData2().load( new File( args[ 0 ] ).toURI() );

		ViewId view = null;

		if ( args.length >= 2 )
			view = new ViewId( args.length >= 3 ? Integer.parseInt( args[ 2 ] ) : 0, Integer.parseInt( args[ 1 ] ) );
		else
			for ( final ViewDescription vd : data.getSequenceDescription().getViewDescriptions().values() )
				if ( vd.isPresent() )
				{
					view = vd;
					break;
				}

		new InteractiveAnisotropy( data, view );
	}
}
