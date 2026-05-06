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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap;

import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistration;
import net.imglib2.Dimensions;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * Tight overlap test for two affine-transformed voxel cubes (parallelepipeds in world space)
 * via the 3-D Separating Axis Theorem (SAT). Used as a narrow-phase refinement on top of
 * {@link SimpleBoundingBoxOverlap}'s AABB broad phase.
 *
 * SAT for two convex parallelepipeds checks 15 candidate separating axes:
 * <ul>
 *   <li>3 face normals from each box (= 6) — cross products of edge pairs at corner 0.</li>
 *   <li>9 edge-cross-products (3 from A × 3 from B).</li>
 * </ul>
 * For each axis, project all 8 corners of each box and compare the resulting 1-D intervals.
 * Near-zero-length axes (parallel edges, 2-D views, etc.) are skipped — those degeneracies
 * never separate the volumes.
 */
public class ParallelepipedOverlap
{
	private static final double EPS = 1e-12;

	private ParallelepipedOverlap() {}

	/**
	 * The 8 world-space corners of the unit voxel cube {@code [0..d-1]^3} transformed by
	 * the view's registration affine. Returns {@code null} if the view setup has no size.
	 */
	public static double[][] corners( final BasicViewSetup vs, final ViewRegistration vr )
	{
		if ( !vs.hasSize() )
			return null;
		vr.updateModel();
		return corners( vs.getSize(), vr.getModel() );
	}

	public static double[][] corners( final Dimensions dims, final AffineTransform3D transform )
	{
		final double dx = dims.dimension( 0 ) - 1;
		final double dy = dims.dimension( 1 ) - 1;
		final double dz = dims.dimension( 2 ) - 1;
		// Corner i: bit 0 = x, bit 1 = y, bit 2 = z.
		final double[][] src = {
				{ 0,  0,  0  }, { dx, 0,  0  }, { 0,  dy, 0  }, { dx, dy, 0  },
				{ 0,  0,  dz }, { dx, 0,  dz }, { 0,  dy, dz }, { dx, dy, dz }
		};
		final double[][] out = new double[ 8 ][ 3 ];
		for ( int i = 0; i < 8; ++i )
			transform.apply( src[ i ], out[ i ] );
		return out;
	}

	/**
	 * 3-D SAT for two parallelepipeds given by their 8 world-space corners. Corner ordering
	 * must match {@link #corners(Dimensions, AffineTransform3D)}: corners 1, 2, 4 are the
	 * edges from corner 0 along x, y, z respectively.
	 */
	public static boolean intersects( final double[][] a, final double[][] b )
	{
		final double[] eA0 = sub( a[ 1 ], a[ 0 ] );
		final double[] eA1 = sub( a[ 2 ], a[ 0 ] );
		final double[] eA2 = sub( a[ 4 ], a[ 0 ] );
		final double[] eB0 = sub( b[ 1 ], b[ 0 ] );
		final double[] eB1 = sub( b[ 2 ], b[ 0 ] );
		final double[] eB2 = sub( b[ 4 ], b[ 0 ] );

		// 3 face normals from A
		if ( separated( a, b, cross( eA0, eA1 ) ) ) return false;
		if ( separated( a, b, cross( eA0, eA2 ) ) ) return false;
		if ( separated( a, b, cross( eA1, eA2 ) ) ) return false;
		// 3 face normals from B
		if ( separated( a, b, cross( eB0, eB1 ) ) ) return false;
		if ( separated( a, b, cross( eB0, eB2 ) ) ) return false;
		if ( separated( a, b, cross( eB1, eB2 ) ) ) return false;
		// 9 edge × edge
		if ( separated( a, b, cross( eA0, eB0 ) ) ) return false;
		if ( separated( a, b, cross( eA0, eB1 ) ) ) return false;
		if ( separated( a, b, cross( eA0, eB2 ) ) ) return false;
		if ( separated( a, b, cross( eA1, eB0 ) ) ) return false;
		if ( separated( a, b, cross( eA1, eB1 ) ) ) return false;
		if ( separated( a, b, cross( eA1, eB2 ) ) ) return false;
		if ( separated( a, b, cross( eA2, eB0 ) ) ) return false;
		if ( separated( a, b, cross( eA2, eB1 ) ) ) return false;
		if ( separated( a, b, cross( eA2, eB2 ) ) ) return false;
		return true;
	}

	/** Returns true iff the corner-projections of A and B onto {@code axis} are disjoint.
	 *  Degenerate (near-zero-length) axes return false (cannot separate). */
	private static boolean separated( final double[][] a, final double[][] b, final double[] axis )
	{
		final double len2 = axis[ 0 ] * axis[ 0 ] + axis[ 1 ] * axis[ 1 ] + axis[ 2 ] * axis[ 2 ];
		if ( len2 < EPS )
			return false;
		double minA = Double.POSITIVE_INFINITY, maxA = Double.NEGATIVE_INFINITY;
		for ( int i = 0; i < 8; ++i )
		{
			final double p = a[ i ][ 0 ] * axis[ 0 ] + a[ i ][ 1 ] * axis[ 1 ] + a[ i ][ 2 ] * axis[ 2 ];
			if ( p < minA ) minA = p;
			if ( p > maxA ) maxA = p;
		}
		double minB = Double.POSITIVE_INFINITY, maxB = Double.NEGATIVE_INFINITY;
		for ( int i = 0; i < 8; ++i )
		{
			final double p = b[ i ][ 0 ] * axis[ 0 ] + b[ i ][ 1 ] * axis[ 1 ] + b[ i ][ 2 ] * axis[ 2 ];
			if ( p < minB ) minB = p;
			if ( p > maxB ) maxB = p;
		}
		return maxA < minB || maxB < minA;
	}

	private static double[] sub( final double[] u, final double[] v )
	{
		return new double[] { u[ 0 ] - v[ 0 ], u[ 1 ] - v[ 1 ], u[ 2 ] - v[ 2 ] };
	}

	private static double[] cross( final double[] u, final double[] v )
	{
		return new double[] {
				u[ 1 ] * v[ 2 ] - u[ 2 ] * v[ 1 ],
				u[ 2 ] * v[ 0 ] - u[ 0 ] * v[ 2 ],
				u[ 0 ] * v[ 1 ] - u[ 1 ] * v[ 0 ]
		};
	}
}
