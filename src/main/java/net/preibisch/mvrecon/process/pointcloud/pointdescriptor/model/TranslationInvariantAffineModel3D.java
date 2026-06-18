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
package net.preibisch.mvrecon.process.pointcloud.pointdescriptor.model;

import java.util.Collection;

import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;

/**
 * 3d-affine transformation models to be applied to points in 3d-space.
 * This model includes the closed form weighted least squares solution as
 * described by \citet{SchaeferAl06} transferred to 3d
 *
 * Changed by Stephan Preibisch to be translation invariant for descriptor matching
 *
 * BibTeX:
 * <pre>
 * &#64;article{SchaeferAl06,
 *   author    = {Scott Schaefer and Travis McPhail and Joe Warren},
 *   title     = {Image deformation using moving least squares},
 *   journal   = {ACM Transactions on Graphics},
 *   volume    = {25},
 *   number    = {3},
 *   year      = {2006},
 *   pages     = {533--540},
 *   publisher = {ACM},
 *   address   = {New York, NY, USA},
 *   url       = {http://faculty.cs.tamu.edu/schaefer/research/mls.pdf},
 * }
 * </pre>
 *
 * @author Stephan Saalfeld &gt;saalfeld@mpi-cbg.de&lt; and Johannes Schindelin and Stephan Preibisch
 * @version 0.1b
 *
 */
public class TranslationInvariantAffineModel3D extends TranslationInvariantModel<TranslationInvariantAffineModel3D>
{
	static final protected int MIN_NUM_MATCHES = 4;

	protected double
		m00 = 1.0, m01 = 0.0, m02 = 0.0,
		m10 = 0.0, m11 = 1.0, m12 = 0.0,
		m20 = 0.0, m21 = 0.0, m22 = 1.0;

	@Override
	public boolean canDoNumDimension( final int numDimensions ) { return numDimensions == 3; }

	@Override
	final public <P extends PointMatch> void fit( final Collection< P > matches )
		throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		if ( matches.size() < MIN_NUM_MATCHES )
			throw new NotEnoughDataPointsException( matches.size() + " data points are not enough to estimate a 3d affine model, at least " + MIN_NUM_MATCHES + " data points required." );

		double
			a00, a01, a02,
			     a11, a12,
			          a22;
		double
			b00, b01, b02,
			b10, b11, b12,
			b20, b21, b22;

		a00 = a01 = a02 = a11 = a12 = a22 = b00 = b01 = b02 = b10 = b11 = b12 = b20 = b21 = b22 = 0;
		for ( final PointMatch m : matches )
		{
			final double[] p = m.getP1().getL();
			final double[] q = m.getP2().getW();
			final double w = m.getWeight();

			final double px = p[ 0 ], py = p[ 1 ], pz = p[ 2 ];
			final double qx = q[ 0 ], qy = q[ 1 ], qz = q[ 2 ];
			a00 += w * px * px;
			a01 += w * px * py;
			a02 += w * px * pz;
			a11 += w * py * py;
			a12 += w * py * pz;
			a22 += w * pz * pz;

			b00 += w * px * qx;
			b01 += w * px * qy;
			b02 += w * px * qz;
			b10 += w * py * qx;
			b11 += w * py * qy;
			b12 += w * py * qz;
			b20 += w * pz * qx;
			b21 += w * pz * qy;
			b22 += w * pz * qz;
		}

		final double det =
			a00 * a11 * a22 +
			a01 * a12 * a02 +
			a02 * a01 * a12 -
			a02 * a11 * a02 -
			a12 * a12 * a00 -
			a22 * a01 * a01;

		if ( det == 0 )
			throw new IllDefinedDataPointsException();

		final double idet = 1f / det;

		final double ai00 = ( a11 * a22 - a12 * a12 ) * idet;
		final double ai01 = ( a02 * a12 - a01 * a22 ) * idet;
		final double ai02 = ( a01 * a12 - a02 * a11 ) * idet;
		final double ai11 = ( a00 * a22 - a02 * a02 ) * idet;
		final double ai12 = ( a02 * a01 - a00 * a12 ) * idet;
		final double ai22 = ( a00 * a11 - a01 * a01 ) * idet;

		m00 = ai00 * b00 + ai01 * b10 + ai02 * b20;
		m01 = ai01 * b00 + ai11 * b10 + ai12 * b20;
		m02 = ai02 * b00 + ai12 * b10 + ai22 * b20;

		m10 = ai00 * b01 + ai01 * b11 + ai02 * b21;
		m11 = ai01 * b01 + ai11 * b11 + ai12 * b21;
		m12 = ai02 * b01 + ai12 * b11 + ai22 * b21;

		m20 = ai00 * b02 + ai01 * b12 + ai02 * b22;
		m21 = ai01 * b02 + ai11 * b12 + ai12 * b22;
		m22 = ai02 * b02 + ai12 * b12 + ai22 * b22;
	}

	@Override
	final public void set( final TranslationInvariantAffineModel3D m )
	{
		m00 = m.m00;
		m10 = m.m10;
		m20 = m.m20;
		m01 = m.m01;
		m11 = m.m11;
		m21 = m.m21;
		m02 = m.m02;
		m12 = m.m12;
		m22 = m.m22;

		cost = m.cost;
	}

	@Override
	public TranslationInvariantAffineModel3D copy()
	{
		final TranslationInvariantAffineModel3D m = new TranslationInvariantAffineModel3D();
		m.m00 = m00;
		m.m10 = m10;
		m.m20 = m20;
		m.m01 = m01;
		m.m11 = m11;
		m.m21 = m21;
		m.m02 = m02;
		m.m12 = m12;
		m.m22 = m22;

		m.cost = cost;

		return m;
	}

	@Override
	final public int getMinNumMatches(){ return MIN_NUM_MATCHES; }

	@Override
	final public double[] apply( final double[] l )
	{
		final double[] transformed = l.clone();
		applyInPlace( transformed );
		return transformed;
	}

	@Override
	final public void applyInPlace( final double[] l )
	{
		assert l.length == 3 : "3d affine transformations can be applied to 3d points only.";

		final double l0 = l[ 0 ];
		final double l1 = l[ 1 ];
		l[ 0 ] = l0 * m00 + l1 * m01 + l[ 2 ] * m02;
		l[ 1 ] = l0 * m10 + l1 * m11 + l[ 2 ] * m12;
		l[ 2 ] = l0 * m20 + l1 * m21 + l[ 2 ] * m22;
	}

	@Override
    final public String toString()
	{
		return
			"3d-affine: (" +
			m00 + ", " + m01 + ", " + m02 + ", " +
			m10 + ", " + m11 + ", " + m12 + ", " +
			m20 + ", " + m21 + ", " + m22 + ")";
	}

}
