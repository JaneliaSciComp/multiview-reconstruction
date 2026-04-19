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

import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RealRandomAccess;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxReorientation;
import net.preibisch.mvrecon.process.fusion.transformed.AbstractTransformedIntervalRandomAccess;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.ModelGrid;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.NumericAffineModel3D;

/**
 * Virtually transforms any RandomAccessibleInterval&lt;RealType&gt; into a RandomAccess&lt;FloatType&gt; using an AffineTransformation
 * and Linear Interpolation. It will only interpolate from the actual data (no outofbounds) to avoid artifacts at the edges
 * and return 0 outside by default (can be changed).
 * 
 * @author preibisch
 */
public class DistanceVisualizingRandomAccess extends AbstractTransformedIntervalRandomAccess
{
	// the original transformation
	final AffineTransform3D originalTransform;

	// to interpolate transformations
	final ModelGrid grid;
	final RealRandomAccess< NumericAffineModel3D > interpolatedModel;

	final double[] s, t, u;

	public DistanceVisualizingRandomAccess(
			final Interval interval, // from ImgLoader
			final ModelGrid grid,
			final AffineTransform3D originalTransform,
			final InterpolatorFactory< FloatType, RandomAccessible< FloatType > > interpolatorFactory,
			final FloatType outside,
			final long[] offset )
	{
		super( interval, interpolatorFactory, outside, offset );

		this.originalTransform = originalTransform;

		this.grid = grid;
		this.interpolatedModel = grid.realRandomAccess();

		this.t = new double[ n ];
		this.u = new double[ n ];
		this.s = new double[ n ];
	}

	@Override
	public FloatType get()
	{
		// go from PSI(Decon)_image local coordinate system to world coordinate system
		t[ 0 ] = s[ 0 ] = position[ 0 ] + offsetX;
		t[ 1 ] = s[ 1 ] = position[ 1 ] + offsetY;
		t[ 2 ] = s[ 2 ] = position[ 2 ] + offsetZ;

		// get the right interpolated affine
		interpolatedModel.setPosition( s );

		// transform the coordinates
		interpolatedModel.get().getModel().applyInPlace( s );

		// go from world coordinate system to local coordinate system of input image (pixel coordinates)
		originalTransform.applyInverse( u, t );

		// check if position t is inside of the input image (pixel coordinates)
		if ( intersectsLinearInterpolation( s[ 0 ], s[ 1 ], s[ 2 ], imgMinX, imgMinY, imgMinZ, imgMaxX, imgMaxY, imgMaxZ ) )
		{
			v.setReal( Math.sqrt( BoundingBoxReorientation.squareDistance( s[ 0 ], s[ 1 ], s[ 2 ], u[ 0 ], u[ 1 ], u[ 2 ] ) ) );

			return v;
		}
		else
		{
			return outside;
		}
	}

	@Override
	public DistanceVisualizingRandomAccess copy()
	{
		return copyRandomAccess();
	}

	@Override
	public DistanceVisualizingRandomAccess copyRandomAccess()
	{
		final DistanceVisualizingRandomAccess r = new DistanceVisualizingRandomAccess(
				interval, grid, originalTransform, interpolatorFactory, outside, new long[] { offsetX, offsetY, offsetZ } );
		r.setPosition( this );
		return r;
	}
}
