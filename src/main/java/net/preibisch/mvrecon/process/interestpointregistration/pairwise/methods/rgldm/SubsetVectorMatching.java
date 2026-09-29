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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntPredicate;

import net.imglib2.KDTree;
import net.imglib2.neighborsearch.RadiusNeighborSearchOnKDTree;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.pointcloud.FlatKDTree;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.AbstractPointDescriptor;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.matcher.SubsetMatcher;

/**
 * Exact, fast RGLDM descriptor matching. The RGLDM distance (minimum over subset pairs of the squared L2 distance between
 * concatenated neighbor offsets) is a nearest-neighbor search in which every descriptor contributes one "subset vector"
 * per subset. Without a search radius a {@link FlatKDTree} answers it; with a radius, a 3-D radius query on B's positions
 * gathers the candidates and a vectorized brute force runs over those, or the tree with an owner filter once the radius
 * admits a large share of B. The tree only pays off while it prunes, so brute force takes over for many neighbors (high
 * dimension); {@link #preferTree} holds the measured thresholds. Candidate sets equal the legacy loop, see
 * {@code RGLDMMatcherTest}.
 */
public class SubsetVectorMatching
{
	public enum Search { AUTO, FLAT_KDTREE, BLOCKED_BRUTE_FORCE, LEGACY }

	private static final int BLOCK = 4096;
	private static final int SAMPLE = 256;

	private static boolean preferTree( final int numNeighbors, final double inRadiusFraction )
	{
		final double maxFraction = switch (numNeighbors) {
			case 5  -> 0.55;
			case 4  -> 0.35;
			default -> 0.2;
		};
		return numNeighbors <= 5 && inRadiusFraction >= maxFraction;
	}

	/** @param subsets the neighbor subsets the descriptors were built with, {@link SubsetMatcher#getNeighbors()} */
	public static < I extends InterestPoint, D extends AbstractPointDescriptor< I, D > > ArrayList< PointMatchGeneric< I > > match(
			final List< D > descA, final List< D > descB, final int[][] subsets, final double ratioOfDistance, final double differenceThreshold,
			final boolean limitSearchRadius, final double searchRadius, final Search search )
	{
		if ( search == Search.LEGACY )
			throw new IllegalArgumentException( "LEGACY is handled by RGLDMMatcher, not by SubsetVectorMatching" );
		final ArrayList< PointMatchGeneric< I > > candidates = new ArrayList<>();
		if ( descA.isEmpty() || descB.size() < 2 )
			return candidates;

		final int nA = descA.size();
		final int C = subsets.length;
		final int numNeighbors = subsets[ 0 ].length;
		final int dims = descA.get( 0 ).numDimensions();
		final float[][] vA = subsetVectors( descA, subsets, dims );
		final float[][] vB = subsetVectors( descB, subsets, dims );
		final Best best = new Best( nA, C );

		if ( !limitSearchRadius )
		{
			if ( search == Search.BLOCKED_BRUTE_FORCE || ( search == Search.AUTO && !preferTree( numNeighbors, 1 ) ) )
				bruteForce( vA, vB, best );
			else
				flatTree( vA, vB, best );
		}
		else
			withRadius( descA, descB, vA, vB, numNeighbors, searchRadius, search, best );

		for ( int a = 0; a < nA; ++a )
		{
			final double b = best.best[ a ] / dims;
			final double s = best.second[ a ] / dims;
			if ( best.owner[ a ] >= 0 && best.second[ a ] < Double.MAX_VALUE && b < differenceThreshold && b * ratioOfDistance < s )
				candidates.add( new PointMatchGeneric< I >( descA.get( a ).getBasisPoint(), descB.get( best.owner[ a ] ).getBasisPoint() ) );
		}
		return candidates;
	}

	/** offsets of the chosen neighbors (world coordinates of the descriptor points, as SquareDistance uses), concatenated */
	private static < I extends InterestPoint, D extends AbstractPointDescriptor< I, D > > float[][] subsetVectors( final List< D > descs, final int[][] subsets, final int dims )
	{
		final int C = subsets.length;
		final int nn = subsets[ 0 ].length;
		final float[][] v = new float[ descs.size() * C ][ nn * dims ];
		for ( int i = 0; i < descs.size(); ++i )
			for ( int s = 0; s < C; ++s )
				for ( int n = 0; n < nn; ++n )
				{
					final double[] offset = descs.get( i ).getDescriptorPoint( subsets[ s ][ n ] ).getW();
					for ( int d = 0; d < dims; ++d )
						v[ i * C + s ][ n * dims + d ] = (float)offset[ d ];
				}
		return v;
	}

	/** running best / second-best B owner per A descriptor */
	private static final class Best
	{
		final double[] best, second;
		final int[] owner;
		final int C;

		Best( final int nA, final int C )
		{
			this.C = C;
			best = new double[ nA ];
			second = new double[ nA ];
			owner = new int[ nA ];
			Arrays.fill( best, Double.MAX_VALUE );
			Arrays.fill( second, Double.MAX_VALUE );
			Arrays.fill( owner, -1 );
		}

		void fold( final int a, final int b, final double d )
		{
			if ( b == owner[ a ] )
			{
				if ( d < best[ a ] )
					best[ a ] = d;
			}
			else if ( d < best[ a ] )
			{
				second[ a ] = best[ a ];
				best[ a ] = d;
				owner[ a ] = b;
			}
			else if ( d < second[ a ] )
				second[ a ] = d;
		}

		void fold( final int a, final FlatKDTree.Query q )
		{
			final int n = q.size();
			for ( int t = 0; t < n; ++t )
				fold( a, q.index( t ) / C, q.squareDistance( t ) );
		}
	}

	/** k = C + 1 suffices: everything closer than the second-best owner's best entry belongs to the best owner (C entries) */
	private static void flatTree( final float[][] vA, final float[][] vB, final Best best )
	{
		final FlatKDTree.Query q = new FlatKDTree( vB ).new Query( best.C + 1 );
		for ( int i = 0; i < vA.length; ++i )
		{
			q.search( vA[ i ] );
			best.fold( i / best.C, q );
		}
	}

	private static void bruteForce( final float[][] vA, final float[][] vB, final Best best )
	{
		final int C = best.C;
		final int m = vB.length;
		final int block = Math.max( C, BLOCK / C * C );
		final float[][] T = transpose( vB, new float[ vB[ 0 ].length ][ m ], null, m );
		final float[] dist = new float[ m ];
		final float[] distB = new float[ m / C ];
		for ( int a = 0; a < best.best.length; ++a )
		{
			Arrays.fill( distB, Float.MAX_VALUE );
			for ( int lo = 0; lo < m; lo += block )
			{
				final int hi = Math.min( m, lo + block );
				for ( int s = 0; s < C; ++s )
				{
					squaredDistances( vA[ a * C + s ], T, dist, lo, hi );
					minPerOwner( dist, lo, hi, C, distB );
				}
			}
			for ( int b = 0; b < distB.length; ++b )
				best.fold( a, b, distB[ b ] );
		}
	}

	/** component-major copy of vectors {@code which} (all if null) into {@code target} */
	private static float[][] transpose( final float[][] v, final float[][] target, final int[] which, final int count )
	{
		for ( int j = 0; j < count; ++j )
		{
			final float[] src = v[ which == null ? j : which[ j ] ];
			for ( int d = 0; d < target.length; ++d )
				target[ d ][ j ] = src[ d ];
		}
		return target;
	}

	/** distB[b] = min over the C owner-major slots of b (kept as its own small method: the JIT compiles it much better inline in the a loop) */
	private static void minPerOwner( final float[] dist, final int from, final int to, final int C, final float[] distB )
	{
		for ( int j = from, b = from / C; j < to; ++b )
		{
			float min = distB[ b ];
			for ( int t = 0; t < C; ++t, ++j )
				if ( dist[ j ] < min )
					min = dist[ j ];
			distB[ b ] = min;
		}
	}

	/** dist[j] = |q - column j|^2 on [from, to); the loops over j vectorize */
	private static void squaredDistances( final float[] q, final float[][] T, final float[] dist, final int from, final int to )
	{
		final float q0 = q[ 0 ];
		final float[] c0 = T[ 0 ];
		for ( int j = from; j < to; ++j )
		{
			final float x = q0 - c0[ j ];
			dist[ j ] = x * x;
		}
		for ( int d = 1; d < T.length; ++d )
		{
			final float qd = q[ d ];
			final float[] cd = T[ d ];
			for ( int j = from; j < to; ++j )
			{
				final float x = qd - cd[ j ];
				dist[ j ] += x * x;
			}
		}
	}

	private static < I extends InterestPoint, D extends AbstractPointDescriptor< I, D > > void withRadius( final List< D > descA, final List< D > descB,
			final float[][] vA, final float[][] vB, final int numNeighbors, final double radius, final Search search, final Best best )
	{
		final int nA = descA.size();
		final int nB = descB.size();
		final int C = best.C;
		final int dim = vB[ 0 ].length;

		// 3-D tree over B's basis points (RealLocalizable over world coordinates, like Point.distance in the legacy loop)
		final ArrayList< I > basisB = new ArrayList<>( nB );
		final ArrayList< Integer > ids = new ArrayList<>( nB );
		for ( int b = 0; b < nB; ++b )
		{
			basisB.add( descB.get( b ).getBasisPoint() );
			ids.add( b );
		}
		final RadiusNeighborSearchOnKDTree< Integer > rs = new RadiusNeighborSearchOnKDTree<>( new KDTree<>( ids, basisB ) );

		Search s = search;
		if ( s == Search.AUTO )
		{
			long inRadius = 0;
			final int step = Math.max( 1, nA / SAMPLE );
			int sampled = 0;
			for ( int a = 0; a < nA; a += step, ++sampled )
			{
				rs.search( descA.get( a ).getBasisPoint(), radius, false );
				inRadius += rs.numNeighbors();
			}
			s = preferTree( numNeighbors, (double)inRadius / ( (double)sampled * nB ) ) ? Search.FLAT_KDTREE : Search.BLOCKED_BRUTE_FORCE;
		}

		if ( s == Search.BLOCKED_BRUTE_FORCE )
		{
			final float[][] scratch = new float[ dim ][ nB * C ];
			final float[] dist = new float[ nB * C ];
			final int[] columns = new int[ nB * C ];
			final int[] owners = new int[ nB ];
			for ( int a = 0; a < nA; ++a )
			{
				rs.search( descA.get( a ).getBasisPoint(), radius, false );
				final int n = rs.numNeighbors();
				if ( n < 2 )
					continue;
				for ( int t = 0; t < n; ++t )
				{
					owners[ t ] = rs.getSampler( t ).get();
					for ( int k = 0; k < C; ++k )
						columns[ t * C + k ] = owners[ t ] * C + k;
				}
				transpose( vB, scratch, columns, n * C );
				for ( int sub = 0; sub < C; ++sub )
				{
					squaredDistances( vA[ a * C + sub ], scratch, dist, 0, n * C );
					for ( int t = 0, j = 0; t < n; ++t )
						for ( int k = 0; k < C; ++k, ++j )
							best.fold( a, owners[ t ], dist[ j ] );
				}
			}
		}
		else
		{
			final FlatKDTree.Query q = new FlatKDTree( vB ).new Query( C + 1 );
			final int[] stamp = new int[ nB ];
			final int[] current = { -1 };
			Arrays.fill( stamp, -1 );
			final IntPredicate inRadius = v -> stamp[ v / C ] == current[ 0 ];
			for ( int a = 0; a < nA; ++a )
			{
				rs.search( descA.get( a ).getBasisPoint(), radius, false );
				final int n = rs.numNeighbors();
				if ( n < 2 )
					continue;
				for ( int t = 0; t < n; ++t )
					stamp[ rs.getSampler( t ).get() ] = a;
				current[ 0 ] = a;
				for ( int sub = 0; sub < C; ++sub )
				{
					q.search( vA[ a * C + sub ], inRadius );
					best.fold( a, q );
				}
			}
		}
	}
}
