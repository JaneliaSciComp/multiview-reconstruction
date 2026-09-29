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

	private static final int BRUTE_FORCE_BLOCK = 4096;
	private static final int RADIUS_SAMPLE_SIZE = 256;

	/** tree vs brute force, by neighbor count and by the fraction of B inside the search radius (1 = no radius) */
	private static boolean preferTree( final int numNeighbors, final double inRadiusFraction )
	{
		final double minFractionForTree = switch ( numNeighbors )
		{
			case 5  -> 0.55;
			case 4  -> 0.35;
			default -> 0.2;
		};
		return numNeighbors <= 5 && inRadiusFraction >= minFractionForTree;
	}

	/** @param subsets the neighbor subsets the descriptors were built with, {@link SubsetMatcher#getNeighbors()} */
	public static < I extends InterestPoint, D extends AbstractPointDescriptor< I, D > > ArrayList< PointMatchGeneric< I > > match(
			final List< D > descsA, final List< D > descsB, final int[][] subsets, final double ratioOfDistance, final double differenceThreshold,
			final boolean limitSearchRadius, final double searchRadius, final Search search )
	{
		if ( search == Search.LEGACY )
			throw new IllegalArgumentException( "LEGACY is handled by RGLDMMatcher" );
		final ArrayList< PointMatchGeneric< I > > candidates = new ArrayList<>();
		if ( descsA.isEmpty() || descsB.size() < 2 )
			return candidates;

		final int numSubsets = subsets.length;
		final int numNeighbors = subsets[ 0 ].length;
		final int numDims = descsA.get( 0 ).numDimensions();
		final float[][] vecsA = subsetVectors( descsA, subsets, numDims );
		final float[][] vecsB = subsetVectors( descsB, subsets, numDims );
		final BestMatches bestMatches = new BestMatches( descsA.size(), numSubsets );

		if ( limitSearchRadius )
			withRadius( descsA, descsB, vecsA, vecsB, numNeighbors, searchRadius, search, bestMatches );
		else if ( search == Search.BLOCKED_BRUTE_FORCE || ( search == Search.AUTO && !preferTree( numNeighbors, 1 ) ) )
			bruteForce( vecsA, vecsB, bestMatches );
		else
			flatTree( vecsA, vecsB, bestMatches );

		for ( int indexA = 0; indexA < descsA.size(); ++indexA )
		{
			final double bestDist = bestMatches.bestDist[ indexA ] / numDims;
			final double secondDist = bestMatches.secondDist[ indexA ] / numDims;
			if ( bestMatches.bestOwner[ indexA ] >= 0 && bestMatches.secondDist[ indexA ] < Double.MAX_VALUE
					&& bestDist < differenceThreshold && bestDist * ratioOfDistance < secondDist )
				candidates.add( new PointMatchGeneric< I >( descsA.get( indexA ).getBasisPoint(), descsB.get( bestMatches.bestOwner[ indexA ] ).getBasisPoint() ) );
		}
		return candidates;
	}

	/** offsets of the chosen neighbors (world coordinates of the descriptor points, as SquareDistance uses), concatenated */
	private static < I extends InterestPoint, D extends AbstractPointDescriptor< I, D > > float[][] subsetVectors(
			final List< D > descs, final int[][] subsets, final int numDims )
	{
		final int numSubsets = subsets.length;
		final int numNeighbors = subsets[ 0 ].length;
		final float[][] vecs = new float[ descs.size() * numSubsets ][ numNeighbors * numDims ];
		for ( int desc = 0; desc < descs.size(); ++desc )
			for ( int subset = 0; subset < numSubsets; ++subset )
				for ( int neighbor = 0; neighbor < numNeighbors; ++neighbor )
				{
					final double[] offset = descs.get( desc ).getDescriptorPoint( subsets[ subset ][ neighbor ] ).getW();
					for ( int dim = 0; dim < numDims; ++dim )
						vecs[ desc * numSubsets + subset ][ neighbor * numDims + dim ] = (float)offset[ dim ];
				}
		return vecs;
	}

	/** running best / second-best B owner per A descriptor */
	private static final class BestMatches
	{
		final double[] bestDist, secondDist;
		final int[] bestOwner;
		final int numSubsets;

		BestMatches( final int numA, final int numSubsets )
		{
			this.numSubsets = numSubsets;
			bestDist = new double[ numA ];
			secondDist = new double[ numA ];
			bestOwner = new int[ numA ];
			Arrays.fill( bestDist, Double.MAX_VALUE );
			Arrays.fill( secondDist, Double.MAX_VALUE );
			Arrays.fill( bestOwner, -1 );
		}

		void fold( final int indexA, final int ownerB, final double dist )
		{
			if ( ownerB == bestOwner[ indexA ] )
			{
				if ( dist < bestDist[ indexA ] )
					bestDist[ indexA ] = dist;
			}
			else if ( dist < bestDist[ indexA ] )
			{
				secondDist[ indexA ] = bestDist[ indexA ];
				bestDist[ indexA ] = dist;
				bestOwner[ indexA ] = ownerB;
			}
			else if ( dist < secondDist[ indexA ] )
				secondDist[ indexA ] = dist;
		}

		void fold( final int indexA, final FlatKDTree.Query query )
		{
			final int found = query.size();
			for ( int i = 0; i < found; ++i )
				fold( indexA, query.index( i ) / numSubsets, query.squareDistance( i ) );
		}
	}

	/** k = numSubsets + 1 suffices: everything closer than the second-best owner's best entry belongs to the best owner (numSubsets entries) */
	private static void flatTree( final float[][] vecsA, final float[][] vecsB, final BestMatches bestMatches )
	{
		final FlatKDTree.Query query = new FlatKDTree( vecsB ).new Query( bestMatches.numSubsets + 1 );
		for ( int vec = 0; vec < vecsA.length; ++vec )
		{
			query.search( vecsA[ vec ] );
			bestMatches.fold( vec / bestMatches.numSubsets, query );
		}
	}

	private static void bruteForce( final float[][] vecsA, final float[][] vecsB, final BestMatches bestMatches )
	{
		final int numSubsets = bestMatches.numSubsets;
		final int numVecsB = vecsB.length;
		final int blockSize = Math.max( numSubsets, BRUTE_FORCE_BLOCK / numSubsets * numSubsets ); // whole owners per block
		final float[][] transposedB = transpose( vecsB, new float[ vecsB[ 0 ].length ][ numVecsB ], null, numVecsB );
		final float[] dists = new float[ numVecsB ];
		final float[] minDistPerOwner = new float[ numVecsB / numSubsets ];
		for ( int indexA = 0; indexA < bestMatches.bestDist.length; ++indexA )
		{
			Arrays.fill( minDistPerOwner, Float.MAX_VALUE );
			for ( int blockStart = 0; blockStart < numVecsB; blockStart += blockSize )
			{
				final int blockEnd = Math.min( numVecsB, blockStart + blockSize );
				for ( int subset = 0; subset < numSubsets; ++subset )
				{
					sqDists( vecsA[ indexA * numSubsets + subset ], transposedB, dists, blockStart, blockEnd );
					minPerOwner( dists, blockStart, blockEnd, numSubsets, minDistPerOwner );
				}
			}
			for ( int ownerB = 0; ownerB < minDistPerOwner.length; ++ownerB )
				bestMatches.fold( indexA, ownerB, minDistPerOwner[ ownerB ] );
		}
	}

	/** component-major copy of the vectors selected by {@code which} (all if null) into {@code target} */
	private static float[][] transpose( final float[][] vecs, final float[][] target, final int[] which, final int count )
	{
		for ( int col = 0; col < count; ++col )
		{
			final float[] source = vecs[ which == null ? col : which[ col ] ];
			for ( int dim = 0; dim < target.length; ++dim )
				target[ dim ][ col ] = source[ dim ];
		}
		return target;
	}

	/** minDistPerOwner[owner] = minimum over the owner's numSubsets consecutive columns (its own method: the JIT compiles it much better than inline in the caller's loop) */
	private static void minPerOwner( final float[] dists, final int from, final int to, final int numSubsets, final float[] minDistPerOwner )
	{
		for ( int col = from, owner = from / numSubsets; col < to; ++owner )
		{
			float min = minDistPerOwner[ owner ];
			for ( int subset = 0; subset < numSubsets; ++subset, ++col )
				if ( dists[ col ] < min )
					min = dists[ col ];
			minDistPerOwner[ owner ] = min;
		}
	}

	/** dists[col] = |query - column col|^2 on [from, to); the loops over the columns vectorize */
	private static void sqDists( final float[] query, final float[][] transposed, final float[] dists, final int from, final int to )
	{
		final float query0 = query[ 0 ];
		final float[] row0 = transposed[ 0 ];
		for ( int col = from; col < to; ++col )
		{
			final float diff = query0 - row0[ col ];
			dists[ col ] = diff * diff;
		}
		for ( int dim = 1; dim < transposed.length; ++dim )
		{
			final float queryVal = query[ dim ];
			final float[] row = transposed[ dim ];
			for ( int col = from; col < to; ++col )
			{
				final float diff = queryVal - row[ col ];
				dists[ col ] += diff * diff;
			}
		}
	}

	private static < I extends InterestPoint, D extends AbstractPointDescriptor< I, D > > void withRadius( final List< D > descsA, final List< D > descsB,
			final float[][] vecsA, final float[][] vecsB, final int numNeighbors, final double radius, final Search search, final BestMatches bestMatches )
	{
		final int numA = descsA.size();
		final int numB = descsB.size();
		final int numSubsets = bestMatches.numSubsets;
		final int vecLen = vecsB[ 0 ].length;

		// 3-D tree over B's basis points (RealLocalizable over world coordinates, like Point.distance in the legacy loop)
		final ArrayList< I > basisPointsB = new ArrayList<>( numB );
		final ArrayList< Integer > indicesB = new ArrayList<>( numB );
		for ( int indexB = 0; indexB < numB; ++indexB )
		{
			basisPointsB.add( descsB.get( indexB ).getBasisPoint() );
			indicesB.add( indexB );
		}
		final RadiusNeighborSearchOnKDTree< Integer > radiusSearch = new RadiusNeighborSearchOnKDTree<>( new KDTree<>( indicesB, basisPointsB ) );

		Search strategy = search;
		if ( strategy == Search.AUTO ) // sample the in-radius fraction of B to decide
		{
			long numInRadius = 0;
			final int sampleStep = Math.max( 1, numA / RADIUS_SAMPLE_SIZE );
			int numSampled = 0;
			for ( int indexA = 0; indexA < numA; indexA += sampleStep, ++numSampled )
			{
				radiusSearch.search( descsA.get( indexA ).getBasisPoint(), radius, false );
				numInRadius += radiusSearch.numNeighbors();
			}
			strategy = preferTree( numNeighbors, (double)numInRadius / ( (double)numSampled * numB ) ) ? Search.FLAT_KDTREE : Search.BLOCKED_BRUTE_FORCE;
		}

		if ( strategy == Search.FLAT_KDTREE ) // tree over all of B, entries of out-of-radius owners filtered per query
		{
			final FlatKDTree.Query query = new FlatKDTree( vecsB ).new Query( numSubsets + 1 );
			final int[] inRadiusStamp = new int[ numB ]; // holds the A index for which owner B was last inside the radius
			final int[] currentA = { -1 };
			Arrays.fill( inRadiusStamp, -1 );
			final IntPredicate ownerInRadius = vec -> inRadiusStamp[ vec / numSubsets ] == currentA[ 0 ];
			for ( int indexA = 0; indexA < numA; ++indexA )
			{
				radiusSearch.search( descsA.get( indexA ).getBasisPoint(), radius, false );
				final int numInRadiusB = radiusSearch.numNeighbors();
				if ( numInRadiusB < 2 )
					continue;
				for ( int neighbor = 0; neighbor < numInRadiusB; ++neighbor )
					inRadiusStamp[ radiusSearch.getSampler( neighbor ).get() ] = indexA;
				currentA[ 0 ] = indexA;
				for ( int subset = 0; subset < numSubsets; ++subset )
				{
					query.search( vecsA[ indexA * numSubsets + subset ], ownerInRadius );
					bestMatches.fold( indexA, query );
				}
			}
			return;
		}

		// gather the in-radius B points and run the vectorized brute force over just those
		final float[][] gatheredB = new float[ vecLen ][ numB * numSubsets ];
		final float[] dists = new float[ numB * numSubsets ];
		final int[] gatheredCols = new int[ numB * numSubsets ];
		final int[] gatheredOwners = new int[ numB ];
		for ( int indexA = 0; indexA < numA; ++indexA )
		{
			radiusSearch.search( descsA.get( indexA ).getBasisPoint(), radius, false );
			final int numInRadiusB = radiusSearch.numNeighbors();
			if ( numInRadiusB < 2 )
				continue;
			for ( int neighbor = 0; neighbor < numInRadiusB; ++neighbor )
			{
				gatheredOwners[ neighbor ] = radiusSearch.getSampler( neighbor ).get();
				for ( int subset = 0; subset < numSubsets; ++subset )
					gatheredCols[ neighbor * numSubsets + subset ] = gatheredOwners[ neighbor ] * numSubsets + subset;
			}
			final int numCols = numInRadiusB * numSubsets;
			transpose( vecsB, gatheredB, gatheredCols, numCols );
			for ( int subset = 0; subset < numSubsets; ++subset )
			{
				sqDists( vecsA[ indexA * numSubsets + subset ], gatheredB, dists, 0, numCols );
				for ( int neighbor = 0, col = 0; neighbor < numInRadiusB; ++neighbor )
					for ( int subB = 0; subB < numSubsets; ++subB, ++col )
						bestMatches.fold( indexA, gatheredOwners[ neighbor ], dists[ col ] );
			}
		}
	}
}
