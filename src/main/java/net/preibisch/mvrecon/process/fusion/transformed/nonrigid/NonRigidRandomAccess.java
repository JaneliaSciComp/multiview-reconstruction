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
package net.preibisch.mvrecon.process.fusion.transformed.nonrigid;

import java.util.ArrayList;
import java.util.Collection;

import mpicbg.models.AffineModel3D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.MovingLeastSquaresTransform2;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.process.fusion.transformed.AbstractTransformedImgRandomAccess;

/**
 * Virtually transforms any RandomAccessibleInterval&lt;RealType&gt; into a RandomAccess&lt;FloatType&gt; using an AffineTransformation
 * and Linear Interpolation. It will only interpolate from the actual data (no outofbounds) to avoid artifacts at the edges
 * and return 0 outside by default (can be changed).
 * 
 * @author preibisch
 */
public class NonRigidRandomAccess< T extends RealType< T > > extends AbstractTransformedImgRandomAccess< T >
{
	final Collection< ? extends NonrigidIP > ips;
	final double alpha;
	final AffineModel3D invertedModelOpener;
	final double[] s;

	final MovingLeastSquaresTransform2 transform;

	public NonRigidRandomAccess(
			final RandomAccessibleInterval< T > img, // from ImgLoader
			final Collection< ? extends NonrigidIP > ips,
			final double alpha,
			final AffineModel3D invertedModelOpener,
			final InterpolatorFactory< FloatType, RandomAccessible< FloatType > > interpolatorFactory,
			final boolean hasMinValue,
			final float minValue,
			final FloatType outside,
			final long[] offset )
	{
		super( img, interpolatorFactory, hasMinValue, minValue, outside, offset );

		this.ips = ips;
		this.alpha = alpha;
		this.invertedModelOpener = invertedModelOpener;
		this.s = new double[ n ];

		this.transform = new MovingLeastSquaresTransform2();
		final ArrayList< PointMatch > matches = new ArrayList<>();

		for ( final NonrigidIP ip : ips )
			matches.add( new PointMatch( new Point( ip.getTargetW().clone() ), new Point( ip.getL().clone() ) ) );

		try
		{
			transform.setAlpha( alpha );
			transform.setModel( new AffineModel3D() );
			transform.setMatches( matches );
		}
		catch ( NotEnoughDataPointsException | IllDefinedDataPointsException e )
		{
			e.printStackTrace();
		}
	}

	public double getAlpha() { return alpha; }

	@Override
	public FloatType get()
	{
		// go from PSI(Decon)_image local coordinate system to world coordinate system
		s[ 0 ] = position[ 0 ] + offsetX;
		s[ 1 ] = position[ 1 ] + offsetY;
		s[ 2 ] = position[ 2 ] + offsetZ;

		// go from world coordinate system to local coordinate system of input image (pixel coordinates)
		if ( invertedModelOpener == null )
		{
			transform.applyInPlace( s );
		}
		else
		{
			final AffineModel3D model = (AffineModel3D)transform.getModel();
			model.preConcatenate( invertedModelOpener );
			model.applyInPlace( s );
		}

		// check if position t is inside of the input image (pixel coordinates)
		if ( intersectsLinearInterpolation( s[ 0 ], s[ 1 ], s[ 2 ], imgMinX, imgMinY, imgMinZ, imgMaxX, imgMaxY, imgMaxZ ) )
		{
			ir.setPosition( s );

			return getInsideValue( v, ir, hasMinValue, minValue );
		}
		else
		{
			return outside;
		}
	}


	@Override
	public NonRigidRandomAccess< T > copy()
	{
		return copyRandomAccess();
	}

	@Override
	public NonRigidRandomAccess< T > copyRandomAccess()
	{
		final NonRigidRandomAccess< T > r = new NonRigidRandomAccess< T >(
				img, ips, alpha, invertedModelOpener, interpolatorFactory, hasMinValue, minValue, outside, new long[] { offsetX, offsetY, offsetZ } );
		r.setPosition( this );
		return r;
	}
}
