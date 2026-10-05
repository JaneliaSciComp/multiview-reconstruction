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

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
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
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import bdv.tools.brightness.SliderPanelDouble;
import bdv.tools.transformation.TransformedSource;
import bdv.ui.BdvDefaultCards;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvHandle;
import bdv.util.BdvHandleFrame;
import bdv.util.BdvStackSource;
import bdv.util.BoundedValueDouble;
import bdv.viewer.AbstractViewerPanel.AlignPlane;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.interactive.MultiResolutionSource;
import net.preibisch.mvrecon.fiji.plugin.interactive.MultiResolutionTools;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.ScaleSpaceGUI;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

/**
 * Interactive estimation of the anisotropy of a view (z voxel / xy voxel at full resolution) in
 * BigDataViewer: the view is shown on its raw pixel grid, one slider scales it in z in real time,
 * the anisotropy is right when round structures (beads) look round in a side view (shift+X, shift+Y).
 * The confirmed value is logged, kept in {@link #lastAnisotropy} and set as the default of the
 * scale-space detection dialog (ScaleSpaceGUI).
 *
 * @author Stephan Preibisch
 */
public class InteractiveAnisotropy
{
	/** the last value confirmed with Done or by closing the window, NaN if none yet */
	public static double lastAnisotropy = Double.NaN;

	final BasicViewDescription< ? > vd;
	final double calibration;

	final BdvStackSource< ? > bdv;
	final TransformedSource< ? > transformedSource;
	final BoundedValueDouble model;

	double anisotropy;
	boolean finished = false;

	public InteractiveAnisotropy( final AbstractSpimData< ? > data, final ViewId view )
	{
		this.vd = data.getSequenceDescription().getViewDescriptions().get( view );
		this.calibration = calibrationAnisotropy( vd );
		this.anisotropy = calibration;

		final String name = Group.pvid( view );

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Opening " + name + " on its raw pixel grid in BigDataViewer, anisotropy z / xy of the calibration = " + calibration );

		final List< Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > > multiRes = rawMultiResolution( data, view );
		final double[] minmax = FusionTools.minMaxApprox1( multiRes.get( multiRes.size() - 1 ).getA() );

		this.bdv = BdvFunctions.show( new MultiResolutionSource( MultiResolutionTools.createVolatileRAIs( multiRes ), name ), Bdv.options().frameTitle( "Anisotropy: " + name ) );
		this.bdv.setDisplayRange( minmax[ 0 ], minmax[ 1 ] );
		this.transformedSource = (TransformedSource< ? >)bdv.getSources().get( 0 ).getSpimSource();

		// the BDV way of reacting to a slider: the SliderPanelDouble listens to this model, we react in setCurrentValue
		this.model = new BoundedValueDouble( 0.5, Math.max( 10, 4 * calibration ), calibration )
		{
			@Override
			public void setCurrentValue( final double value )
			{
				super.setCurrentValue( value );
				setAnisotropy( getCurrentValue() );
			}
		};

		setAnisotropy( calibration );

		final BdvHandle handle = bdv.getBdvHandle();

		handle.getCardPanel().addCard( "anisotropy", "Anisotropy (z / xy voxel at full resolution)", createPanel(), true, new Insets( 0, 4, 0, 0 ) );
		handle.getCardPanel().setCardExpanded( BdvDefaultCards.DEFAULT_SOURCES_CARD, false );
		handle.getCardPanel().setCardExpanded( BdvDefaultCards.DEFAULT_SOURCEGROUPS_CARD, false );
		handle.getCardPanel().setCardExpanded( BdvDefaultCards.DEFAULT_VIEWERMODES_CARD, false );
		handle.getSplitPanel().setCollapsed( false );

		// a side view, the anisotropy is visible along z
		handle.getViewerPanel().align( AlignPlane.XZ );

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

		transformedSource.setFixedTransform( anisotropyTransform( a ) );
		bdv.getBdvHandle().getViewerPanel().requestRepaint();
	}

	public double getAnisotropy() { return anisotropy; }

	public double getCalibration() { return calibration; }

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

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): anisotropy (z / xy voxel at full resolution) of " + Group.pvid( vd ) + " = " + anisotropy + " (calibration: " + calibration + "), set as default of the scale-space detection." );

		if ( close )
			bdv.getBdvHandle().close();
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

		panel.add( new JLabel( "<html>shift+X, shift+Y, shift+Z: look along an axis<br>beads should look round at the right value<br>right-click the slider to set its bounds</html>" ), c );

		final SliderPanelDouble slider = new SliderPanelDouble( "z / xy", model, 0.01 );
		slider.setDecimalFormat( "0.000" );
		slider.setNumColummns( 7 );

		// as the brightness slider of BigDataViewer: a right-click sets the bounds
		final MouseAdapter popupListener = new MouseAdapter()
		{
			@Override
			public void mousePressed( final MouseEvent e ) { if ( e.isPopupTrigger() ) createPopupMenu().show( e.getComponent(), e.getX(), e.getY() ); }

			@Override
			public void mouseReleased( final MouseEvent e ) { if ( e.isPopupTrigger() ) createPopupMenu().show( e.getComponent(), e.getX(), e.getY() ); }
		};

		slider.addMouseListener( popupListener );

		for ( final Component component : slider.getComponents() )
			component.addMouseListener( popupListener );

		++c.gridy;
		panel.add( slider, c );

		++c.gridy;
		panel.add( new JLabel( "calibration: " + String.format( Locale.US, "%.3f", calibration ) ), c );

		final JButton reset = new JButton( "Reset to calibration" );
		reset.addActionListener( e -> model.setCurrentValue( calibration ) );

		final JButton done = new JButton( "Done" );
		done.addActionListener( e -> finish( true ) );

		++c.gridy;
		c.gridwidth = 1;
		panel.add( reset, c );
		c.gridx = 1;
		panel.add( done, c );

		return panel;
	}

	protected JPopupMenu createPopupMenu()
	{
		final JPopupMenu menu = new JPopupMenu();

		final JMenuItem setBounds = new JMenuItem( "set bounds ..." );
		setBounds.addActionListener( e -> setBoundsDialog() );
		menu.add( setBounds );

		final JMenuItem bounds1 = new JMenuItem( "set bounds 0.5 .. 10" );
		bounds1.addActionListener( e -> setBounds( 0.5, 10 ) );
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
	 * Sets the bounds of the slider (the current value is kept if inside, otherwise clamped)
	 */
	public void setBounds( final double min, final double max )
	{
		final double lower = Math.max( 0.001, Math.min( min, max ) );
		final double upper = Math.max( lower + 0.001, Math.max( min, max ) );

		model.setRange( lower, upper );

		// setRange clamps the value without calling setCurrentValue
		setAnisotropy( model.getCurrentValue() );
	}

	/**
	 * The "Set Bounds" dialog of the brightness slider of BigDataViewer
	 */
	protected void setBoundsDialog()
	{
		final JSpinner minSpinner = new JSpinner( new SpinnerNumberModel( model.getRangeMin(), 0.001, 1000.0, 0.1 ) );
		final JSpinner maxSpinner = new JSpinner( new SpinnerNumberModel( model.getRangeMax(), 0.001, 1000.0, 0.1 ) );

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
		panel.add( new JLabel( "min", JLabel.RIGHT ) );
		panel.add( minSpinner );
		panel.add( new JLabel( "max", JLabel.RIGHT ) );
		panel.add( maxSpinner );

		if ( JOptionPane.showConfirmDialog( null, panel, "Set Bounds", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE ) == JOptionPane.OK_OPTION )
			setBounds( (Double)minSpinner.getValue(), (Double)maxSpinner.getValue() );
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
