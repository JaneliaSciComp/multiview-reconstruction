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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import bdv.util.Affine3DHelpers;
import bdv.util.RandomAccessibleIntervalSource;
import bdv.viewer.AbstractViewerPanel.AlignPlane;
import bdv.viewer.BasicViewerState;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.animate.RotationAnimator;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.LinAlgHelpers;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BDVPopup;

/**
 * {@link BDVPopup#initTransform(int, int, boolean, bdv.viewer.ViewerState)} fits the current source into
 * the window without rotating. A source transform that mirrors an axis (a flipped view with a registration
 * on top, det &lt; 0) used to yield a negative scale, i.e. an improper viewer transform; BDV's align
 * (shift+X/Y/Z) then extracted a non-unit quaternion from it and zoomed out at every press.
 */
public class TestBDVPopupInitTransform
{
	static final int W = 800, H = 600;
	static final long[] SIZE = { 64, 48, 16 };

	@Test
	public void mirroredSourceGetsProperViewerTransform()
	{
		final AffineTransform3D mirrored = new AffineTransform3D();
		mirrored.set( 1, 0, 0, 5, 0, -1, 0, 100, 0, 0, 2, -3 ); // y flipped, z calibrated: det < 0
		final AffineTransform3D proper = new AffineTransform3D();
		proper.set( 1, 0, 0, 5, 0, 1, 0, 100, 0, 0, 2, -3 );

		final AffineTransform3D vMirrored = checkFit( mirrored );
		final AffineTransform3D vProper = checkFit( proper );

		// the window fit does not depend on the mirroring
		assertEquals( Affine3DHelpers.extractScale( vProper, 0 ), Affine3DHelpers.extractScale( vMirrored, 0 ), 1e-12 );

		// shift+X/Y/Z (AbstractViewerPanel.align + RotationAnimator) must keep the zoom
		for ( final AffineTransform3D source : new AffineTransform3D[] { mirrored, proper } )
		{
			AffineTransform3D v = BDVPopup.initTransform( W, H, false, state( source ) );
			final double scale = Affine3DHelpers.extractScale( v, 0 );
			for ( final AlignPlane plane : new AlignPlane[] { AlignPlane.XY, AlignPlane.ZY, AlignPlane.XZ, AlignPlane.XY } )
			{
				v = align( v, source, plane );
				assertEquals( scale, Affine3DHelpers.extractScale( v, 0 ), 1e-9, "zoom changed by aligning " + plane );
				assertTrue( det( v ) > 0 );
			}
		}
	}

	/** the viewer transform is proper, centers the source in the window and fits it (touching one border) */
	static AffineTransform3D checkFit( final AffineTransform3D source )
	{
		final AffineTransform3D v = BDVPopup.initTransform( W, H, false, state( source ) );
		assertTrue( det( v ) > 0, "viewer transform must be proper" );
		assertTrue( Affine3DHelpers.extractScale( v, 0 ) > 0 );

		final double cX = W / 2.0, cY = H / 2.0;
		final double sZ = Math.round( ( SIZE[ 2 ] - 1 ) / 2.0 );
		final double[] center = screen( v, source, ( SIZE[ 0 ] - 1 ) / 2.0, ( SIZE[ 1 ] - 1 ) / 2.0, sZ );
		assertEquals( cX - 0.5, center[ 0 ], 1e-9 );
		assertEquals( cY - 0.5, center[ 1 ], 1e-9 );

		final double[] corner = screen( v, source, SIZE[ 0 ] - 0.5, SIZE[ 1 ] - 0.5, sZ );
		final double dx = Math.abs( corner[ 0 ] - ( cX - 0.5 ) ), dy = Math.abs( corner[ 1 ] - ( cY - 0.5 ) );
		assertTrue( dx <= cX + 1e-9 && dy <= cY + 1e-9, "corner outside the window: " + dx + ", " + dy );
		assertTrue( Math.abs( dx - cX ) < 1e-9 || Math.abs( dy - cY ) < 1e-9, "source does not fill the window: " + dx + ", " + dy );
		return v;
	}

	static BasicViewerState state( final AffineTransform3D sourceTransform )
	{
		final Img< UnsignedShortType > img = ArrayImgs.unsignedShorts( SIZE );
		final RandomAccessibleIntervalSource< UnsignedShortType > source = new RandomAccessibleIntervalSource<>( img, new UnsignedShortType(), sourceTransform, "test" );
		final SourceAndConverter< UnsignedShortType > soc = new SourceAndConverter<>( source, ( in, out ) -> out.set( in.get() ) );
		final BasicViewerState state = new BasicViewerState();
		state.addSource( soc );
		state.setSourceActive( soc, true );
		state.setCurrentSource( soc );
		return state;
	}

	/** what AbstractViewerPanel.align does, with the animation played to the end */
	static AffineTransform3D align( final AffineTransform3D viewer, final AffineTransform3D sourceTransform, final AlignPlane plane )
	{
		final double[] qSource = new double[ 4 ];
		Affine3DHelpers.extractApproximateRotationAffine( sourceTransform, qSource, plane.coerceAffineDimension );
		final double[] qTmpSource = new double[ 4 ];
		LinAlgHelpers.quaternionMultiply( qSource, plane.qAlign, qTmpSource );
		final double[] qTarget = new double[ 4 ];
		LinAlgHelpers.quaternionInvert( qTmpSource, qTarget );
		return new RotationAnimator( viewer, W / 2.0, H / 2.0, qTarget, 300 ).get( 1.0 );
	}

	static double[] screen( final AffineTransform3D viewer, final AffineTransform3D source, final double... p )
	{
		final double[] g = new double[ 3 ], s = new double[ 3 ];
		source.apply( p, g );
		viewer.apply( g, s );
		return s;
	}

	static double det( final AffineTransform3D t )
	{
		final double[][] m = new double[ 3 ][ 4 ];
		t.toMatrix( m );
		return m[ 0 ][ 0 ] * ( m[ 1 ][ 1 ] * m[ 2 ][ 2 ] - m[ 1 ][ 2 ] * m[ 2 ][ 1 ] )
				- m[ 0 ][ 1 ] * ( m[ 1 ][ 0 ] * m[ 2 ][ 2 ] - m[ 1 ][ 2 ] * m[ 2 ][ 0 ] )
				+ m[ 0 ][ 2 ] * ( m[ 1 ][ 0 ] * m[ 2 ][ 1 ] - m[ 1 ][ 1 ] * m[ 2 ][ 0 ] );
	}
}
