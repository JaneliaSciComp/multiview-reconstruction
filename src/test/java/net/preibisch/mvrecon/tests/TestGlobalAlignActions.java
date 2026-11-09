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
package net.preibisch.mvrecon.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import javax.swing.Action;

import org.junit.jupiter.api.Test;
import org.scijava.ui.behaviour.InputTrigger;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.Actions;
import org.scijava.ui.behaviour.util.InputActionBindings;

import bdv.util.Affine3DHelpers;
import bdv.viewer.AbstractViewerPanel.AlignPlane;
import bdv.viewer.NavigationActions;
import bdv.viewer.animate.RotationAnimator;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.LinAlgHelpers;
import net.preibisch.mvrecon.fiji.spimdata.explorer.bdv.GlobalAlignActions;

/**
 * {@link GlobalAlignActions}: shift+X/Y/Z align with the global axes whatever the current source's registration,
 * and they take over BDV's key strokes while BDV's source alignment moves to ctrl+shift.
 */
public class TestGlobalAlignActions
{
	@Test
	public void globalAlignIsBDVsAlignForAnUnrotatedSourceAndIgnoresTheSource()
	{
		final AffineTransform3D unrotated = new AffineTransform3D();
		unrotated.set( 1, 0, 0, 10, 0, 1, 0, -20, 0, 0, 3, 5 ); // calibrated, translated, no rotation
		final AffineTransform3D rotated = new AffineTransform3D();
		rotated.rotate( 1, Math.PI / 4 ); // 45 degrees around y, as the first Shroff view
		rotated.preConcatenate( unrotated );

		final Random rnd = new Random( 7 );
		for ( int i = 0; i < 25; ++i )
		{
			final AffineTransform3D start = randomViewerTransform( rnd );
			final double cX = 100 + 500 * rnd.nextDouble(), cY = 100 + 400 * rnd.nextDouble();
			final double[] fixedGlobal = new double[ 3 ];
			start.applyInverse( fixedGlobal, new double[] { cX, cY, 0 } );

			for ( final AlignPlane plane : AlignPlane.values() )
			{
				final AffineTransform3D ours = GlobalAlignActions.aligned( start, cX, cY, plane );

				assertTransformEquals( bdvAlign( start, cX, cY, unrotated, plane ), ours, "plane " + plane + " differs from BDV's align of an unrotated source" );
				assertTrue( maxDifference( bdvAlign( start, cX, cY, rotated, plane ), ours ) > 1e-3, "plane " + plane + ": BDV's align of the rotated source should differ" );

				// the rotation part is the global orientation of the plane, the zoom is kept, the rotation center stays
				final double[][] r = new double[ 3 ][ 3 ];
				LinAlgHelpers.quaternionToR( GlobalAlignActions.globalOrientation( plane ), r );
				final double scale = Affine3DHelpers.extractScale( start, 0 );
				for ( int row = 0; row < 3; ++row )
					for ( int col = 0; col < 3; ++col )
						assertEquals( scale * r[ row ][ col ], ours.get( row, col ), 1e-9 );

				final double[] s = new double[ 3 ];
				ours.apply( fixedGlobal, s );
				assertEquals( cX, s[ 0 ], 1e-9 );
				assertEquals( cY, s[ 1 ], 1e-9 );
			}
		}

		// what the shortcuts show: XY = global x to the right and y down, ZY = z horizontal, XZ = z vertical
		final AffineTransform3D xy = GlobalAlignActions.aligned( new AffineTransform3D(), 0, 0, AlignPlane.XY );
		assertEquals( 1, xy.get( 0, 0 ), 1e-12 );
		assertEquals( 1, xy.get( 1, 1 ), 1e-12 );
		final AffineTransform3D zy = GlobalAlignActions.aligned( new AffineTransform3D(), 0, 0, AlignPlane.ZY );
		assertEquals( 1, Math.abs( zy.get( 0, 2 ) ), 1e-12 );
		assertEquals( 1, zy.get( 1, 1 ), 1e-12 );
		final AffineTransform3D xz = GlobalAlignActions.aligned( new AffineTransform3D(), 0, 0, AlignPlane.XZ );
		assertEquals( 1, xz.get( 0, 0 ), 1e-12 );
		assertEquals( 1, Math.abs( xz.get( 1, 2 ) ), 1e-12 );
	}

	@Test
	public void shiftXYZAreReboundAndBDVsAlignMovesToCtrlShift()
	{
		final InputTriggerConfig config = new InputTriggerConfig();
		final InputActionBindings bindings = new InputActionBindings();
		final List< String > calls = new ArrayList<>();

		// what BigDataViewer installs
		final Actions navigation = new Actions( config, "bdv", "navigation" );
		navigation.install( bindings, "navigation" );
		navigation.runnableAction( () -> calls.add( "bdv XY" ), NavigationActions.ALIGN_XY_PLANE, NavigationActions.ALIGN_XY_PLANE_KEYS );
		navigation.runnableAction( () -> calls.add( "bdv ZY" ), NavigationActions.ALIGN_ZY_PLANE, NavigationActions.ALIGN_ZY_PLANE_KEYS );
		navigation.runnableAction( () -> calls.add( "bdv XZ" ), NavigationActions.ALIGN_XZ_PLANE, NavigationActions.ALIGN_XZ_PLANE_KEYS );

		// ours, afterwards
		GlobalAlignActions.create( config, false, plane -> calls.add( "global " + plane ), plane -> calls.add( "source " + plane ) ).install( bindings, GlobalAlignActions.ID );

		for ( final String keys : new String[] { "shift Z", "shift X", "shift Y", "shift A", "ctrl shift Z", "ctrl shift X", "ctrl shift Y", "ctrl shift A" } )
			press( bindings, keys );

		assertEquals( Arrays.asList( "global XY", "global ZY", "global XZ", "global XZ", "source XY", "source ZY", "source XZ", "source XZ" ), calls );

		// a 2-D viewer only aligns XY, as in BDV
		calls.clear();
		final InputActionBindings bindings2D = new InputActionBindings();
		GlobalAlignActions.create( config, true, plane -> calls.add( "global " + plane ), plane -> calls.add( "source " + plane ) ).install( bindings2D, GlobalAlignActions.ID );
		press( bindings2D, "shift Z" );
		assertEquals( Arrays.asList( "global XY" ), calls );
		assertEquals( null, bindings2D.getConcatenatedInputMap().get( InputTrigger.getFromString( "shift X" ).getKeyStroke() ) );
	}

	static void press( final InputActionBindings bindings, final String keys )
	{
		final Object name = bindings.getConcatenatedInputMap().get( InputTrigger.getFromString( keys ).getKeyStroke() );
		assertNotNull( name, keys + " is not bound" );
		final Action action = bindings.getConcatenatedActionMap().get( name );
		assertNotNull( action, name + " has no action" );
		action.actionPerformed( new ActionEvent( bindings, ActionEvent.ACTION_PERFORMED, keys ) );
	}

	/** what AbstractViewerPanel.align does for the given current source, with the animation played to the end */
	static AffineTransform3D bdvAlign( final AffineTransform3D viewer, final double cX, final double cY, final AffineTransform3D sourceTransform, final AlignPlane plane )
	{
		final double[] qSource = new double[ 4 ];
		Affine3DHelpers.extractApproximateRotationAffine( sourceTransform, qSource, plane.coerceAffineDimension );
		final double[] qTmpSource = new double[ 4 ];
		LinAlgHelpers.quaternionMultiply( qSource, plane.qAlign, qTmpSource );
		final double[] qTarget = new double[ 4 ];
		LinAlgHelpers.quaternionInvert( qTmpSource, qTarget );
		return new RotationAnimator( viewer, cX, cY, qTarget, 300 ).get( 1.0 );
	}

	/** a proper viewer transform: random rotation, uniform zoom, translation */
	static AffineTransform3D randomViewerTransform( final Random rnd )
	{
		final double[] q = new double[] { rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian() };
		LinAlgHelpers.normalize( q );
		final double[][] m = new double[ 3 ][ 4 ];
		LinAlgHelpers.quaternionToR( q, m );
		final AffineTransform3D t = new AffineTransform3D();
		t.set( m );
		t.scale( 0.5 + 2.5 * rnd.nextDouble() );
		t.translate( 1000 * rnd.nextDouble(), 1000 * rnd.nextDouble(), 1000 * rnd.nextDouble() );
		return t;
	}

	static void assertTransformEquals( final AffineTransform3D expected, final AffineTransform3D actual, final String message )
	{
		assertTrue( maxDifference( expected, actual ) < 1e-9, message + ": " + expected + " vs " + actual );
	}

	static double maxDifference( final AffineTransform3D a, final AffineTransform3D b )
	{
		double max = 0;
		for ( int row = 0; row < 3; ++row )
			for ( int col = 0; col < 4; ++col )
				max = Math.max( max, Math.abs( a.get( row, col ) - b.get( row, col ) ) );
		return max;
	}
}
