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

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

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
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

/**
 * Interactive estimation of the anisotropy of a view (z voxel / xy voxel at full resolution) in
 * BigDataViewer: the view is shown on its raw pixel grid, one slider scales it in z in real time,
 * the anisotropy is right when round structures (beads) look round in a side view (shift+X, shift+Y).
 * Self-contained; the confirmed value is logged and kept in {@link #lastAnisotropy}.
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

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): anisotropy (z / xy voxel at full resolution) of " + Group.pvid( vd ) + " = " + anisotropy + " (calibration: " + calibration + ")" );

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

		panel.add( new JLabel( "<html>shift+X, shift+Y, shift+Z: look along an axis<br>beads should look round at the right value</html>" ), c );

		final SliderPanelDouble slider = new SliderPanelDouble( "z / xy", model, 0.01 );
		slider.setDecimalFormat( "0.000" );
		slider.setNumColummns( 7 );

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
