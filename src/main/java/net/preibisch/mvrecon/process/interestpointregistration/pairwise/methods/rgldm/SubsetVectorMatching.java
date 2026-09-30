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
import net.preibisch.mvrecon.process.pointcloud.ComponentMajor;
import net.preibisch.mvrecon.process.pointcloud.FlatKDTree;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.AbstractPointDescriptor;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.matcher.SubsetMatcher;

/**
 * Exact, fast RGLDM descriptor matching. The RGLDM distance (minimum over subset pairs of the squared L2 distance between
 * concatenated neighbor offsets) is a nearest-neighbor search in which every descriptor contributes one "subset vector"
 * per subset. A {@link SubsetSearch} answers it per A descriptor, optionally restricted to the B descriptors inside the
 * search radius: {@link FlatTreeSearch} over a {@link FlatKDTree}, or {@link BruteForceSearch}, a vectorized sweep. The tree
 * only pays off while it prunes, so brute force takes over for many neighbors (high dimension) or a radius that admits few
 * points; {@link #preferTree} holds the measured thresholds. Candidate sets equal the legacy loop, see
 * {@code RGLDMMatcherTest}.
 */
public class SubsetVectorMatching
{
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
			final boolean limitSearchRadius, final double searchRadius, final DescriptorSearch search )
	{
		if ( search == DescriptorSearch.LEGACY )
			throw new IllegalArgumentException( "LEGACY is handled by RGLDMMatcher" );
		final ArrayList< PointMatchGeneric< I > > candidates = new ArrayList<>();
		if ( descsA.isEmpty() || descsB.size() < 2 )
			return candidates;

		final int numSubsets = subsets.length;
		final int numNeighbors = subsets[ 0 ].length;
		final int numDims = descsA.get( 0 ).numDimensions();
		final float[][] vecsA = subsetVectors( descsA, subsets, numDims );
		final float[][] vecsB = subsetVectors( descsB, subsets, numDims );
		final BestMatches bestMatches = new BestMatches( descsA.size() );

		if ( !limitSearchRadius )
		{
			final SubsetSearch searcher = createSearch( search, numNeighbors, 1.0, vecsA, vecsB, numSubsets );
			for ( int indexA = 0; indexA < descsA.size(); ++indexA )
				searcher.search( indexA, null, 0, bestMatches );
		}
		else
		{
			// 3-D tree over B's basis points (RealLocalizable over world coordinates, like Point.distance in the legacy loop)
			final ArrayList< I > basisPointsB = new ArrayList<>( descsB.size() );
			final ArrayList< Integer > indicesB = new ArrayList<>( descsB.size() );
			for ( int indexB = 0; indexB < descsB.size(); ++indexB )
			{
				basisPointsB.add( descsB.get( indexB ).getBasisPoint() );
				indicesB.add( indexB );
			}
			final RadiusNeighborSearchOnKDTree< Integer > radiusSearch = new RadiusNeighborSearchOnKDTree<>( new KDTree<>( indicesB, basisPointsB ) );

			final double inRadiusFraction = ( search == DescriptorSearch.AUTO ) ? sampleInRadiusFraction( descsA, descsB.size(), radiusSearch, searchRadius ) : 1.0;
			final SubsetSearch searcher = createSearch( search, numNeighbors, inRadiusFraction, vecsA, vecsB, numSubsets );
			final int[] ownersB = new int[ descsB.size() ];
			for ( int indexA = 0; indexA < descsA.size(); ++indexA )
			{
				radiusSearch.search( descsA.get( indexA ).getBasisPoint(), searchRadius, false );
				final int numInRadius = radiusSearch.numNeighbors();
				if ( numInRadius < 2 ) // no second-best possible, same as the legacy loop
					continue;
				for ( int neighbor = 0; neighbor < numInRadius; ++neighbor )
					ownersB[ neighbor ] = radiusSearch.getSampler( neighbor ).get();
				searcher.search( indexA, ownersB, numInRadius, bestMatches );
			}
		}

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

	private static SubsetSearch createSearch( final DescriptorSearch search, final int numNeighbors, final double inRadiusFraction,
			final float[][] vecsA, final float[][] vecsB, final int numSubsets )
	{
		final boolean tree = ( search == DescriptorSearch.FLAT_KDTREE ) || ( search == DescriptorSearch.AUTO && preferTree( numNeighbors, inRadiusFraction ) );
		return tree ? new FlatTreeSearch( vecsA, vecsB, numSubsets ) : new BruteForceSearch( vecsA, vecsB, numSubsets );
	}

	/** average fraction of B inside the radius of an A point, from up to {@link #RADIUS_SAMPLE_SIZE} evenly spaced A points */
	private static < I extends InterestPoint, D extends AbstractPointDescriptor< I, D > > double sampleInRadiusFraction(
			final List< D > descsA, final int numB, final RadiusNeighborSearchOnKDTree< Integer > radiusSearch, final double radius )
	{
		final int sampleStep = Math.max( 1, descsA.size() / RADIUS_SAMPLE_SIZE );
		long numInRadius = 0;
		int numSampled = 0;
		for ( int indexA = 0; indexA < descsA.size(); indexA += sampleStep, ++numSampled )
		{
			radiusSearch.search( descsA.get( indexA ).getBasisPoint(), radius, false );
			numInRadius += radiusSearch.numNeighbors();
		}
		return (double)numInRadius / ( (double)numSampled * numB );
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

		BestMatches( final int numA )
		{
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
	}

	/**
	 * Finds the best and second-best B owner for one A descriptor over all of B, or over the given B owners only.
	 * Built once per view pair over A's and B's subset vectors.
	 */
	private interface SubsetSearch
	{
		/** @param ownersB the B descriptors to consider (first numOwners entries), or null for all of B */
		void search( int indexA, int[] ownersB, int numOwners, BestMatches out );
	}

	/**
	 * {@link FlatKDTree} over B's subset vectors. k = numSubsets + 1 nearest entries suffice: everything closer than the
	 * second-best owner's best entry belongs to the best owner, which has numSubsets entries. A radius restriction becomes
	 * an index filter: the tree skips entries whose owner was not stamped for the current A descriptor.
	 */
	private static final class FlatTreeSearch implements SubsetSearch
	{
		private final float[][] vecsA;
		private final int numSubsets;
		private final FlatKDTree tree;
		private final int[] inRadiusStamp; // the A index for which each B owner was last inside the radius
		private final int[] currentA = { -1 };
		private final IntPredicate ownerInRadius;

		FlatTreeSearch( final float[][] vecsA, final float[][] vecsB, final int numSubsets )
		{
			this.vecsA = vecsA;
			this.numSubsets = numSubsets;
			this.tree = new FlatKDTree( vecsB, numSubsets + 1 );
			this.inRadiusStamp = new int[ vecsB.length / numSubsets ];
			Arrays.fill( inRadiusStamp, -1 );
			this.ownerInRadius = vec -> inRadiusStamp[ vec / numSubsets ] == currentA[ 0 ];
		}

		@Override
		public void search( final int indexA, final int[] ownersB, final int numOwners, final BestMatches out )
		{
			if ( ownersB != null )
			{
				for ( int i = 0; i < numOwners; ++i )
					inRadiusStamp[ ownersB[ i ] ] = indexA;
				currentA[ 0 ] = indexA;
			}
			for ( int subset = 0; subset < numSubsets; ++subset )
			{
				tree.search( vecsA[ indexA * numSubsets + subset ], ownersB == null ? null : ownerInRadius );
				final int found = tree.size();
				for ( int i = 0; i < found; ++i )
					out.fold( indexA, tree.index( i ) / numSubsets, tree.squareDistance( i ) );
			}
		}
	}

	/**
	 * Vectorized sweep over B's subset vectors, stored component-major so the distance loop runs over contiguous columns.
	 * Without a radius the full matrix is swept in cache-sized blocks; with a radius the in-radius owners' columns are
	 * gathered into a scratch matrix first.
	 */
	private static final class BruteForceSearch implements SubsetSearch
	{
		private final float[][] vecsA, vecsB;
		private final int numSubsets, numVecsB, vecLen;
		private float[][] transposedB, gatheredB;
		private final float[] dists;
		private final float[] minDistPerOwner;
		private int[] gatheredCols;

		BruteForceSearch( final float[][] vecsA, final float[][] vecsB, final int numSubsets )
		{
			this.vecsA = vecsA;
			this.vecsB = vecsB;
			this.numSubsets = numSubsets;
			this.numVecsB = vecsB.length;
			this.vecLen = vecsB[ 0 ].length;
			this.dists = new float[ numVecsB ];
			this.minDistPerOwner = new float[ numVecsB / numSubsets ];
		}

		@Override
		public void search( final int indexA, final int[] ownersB, final int numOwners, final BestMatches out )
		{
			if ( ownersB == null )
				sweepAll( indexA, out );
			else
				sweepGathered( indexA, ownersB, numOwners, out );
		}

		private void sweepAll( final int indexA, final BestMatches out )
		{
			if ( transposedB == null )
				transposedB = ComponentMajor.transpose( vecsB, new float[ vecLen ][ numVecsB ], null, numVecsB );
			final int blockSize = Math.max( numSubsets, BRUTE_FORCE_BLOCK / numSubsets * numSubsets ); // whole owners per block
			Arrays.fill( minDistPerOwner, Float.MAX_VALUE );
			for ( int blockStart = 0; blockStart < numVecsB; blockStart += blockSize )
			{
				final int blockEnd = Math.min( numVecsB, blockStart + blockSize );
				for ( int subset = 0; subset < numSubsets; ++subset )
				{
					ComponentMajor.squaredDistances( vecsA[ indexA * numSubsets + subset ], transposedB, blockStart, blockEnd, dists );
					minPerOwner( dists, blockEnd - blockStart, numSubsets, blockStart / numSubsets, minDistPerOwner );
				}
			}
			for ( int ownerB = 0; ownerB < minDistPerOwner.length; ++ownerB )
				out.fold( indexA, ownerB, minDistPerOwner[ ownerB ] );
		}

		private void sweepGathered( final int indexA, final int[] ownersB, final int numOwners, final BestMatches out )
		{
			if ( gatheredB == null )
			{
				gatheredB = new float[ vecLen ][ numVecsB ];
				gatheredCols = new int[ numVecsB ];
			}
			for ( int owner = 0; owner < numOwners; ++owner )
				for ( int subset = 0; subset < numSubsets; ++subset )
					gatheredCols[ owner * numSubsets + subset ] = ownersB[ owner ] * numSubsets + subset;
			final int numCols = numOwners * numSubsets;
			ComponentMajor.transpose( vecsB, gatheredB, gatheredCols, numCols );
			for ( int subset = 0; subset < numSubsets; ++subset )
			{
				ComponentMajor.squaredDistances( vecsA[ indexA * numSubsets + subset ], gatheredB, 0, numCols, dists );
				for ( int owner = 0, col = 0; owner < numOwners; ++owner )
					for ( int subB = 0; subB < numSubsets; ++subB, ++col )
						out.fold( indexA, ownersB[ owner ], dists[ col ] );
			}
		}
	}

	/** minDistPerOwner[firstOwner + n] = minimum over the n-th group of numSubsets consecutive distances (its own method: the JIT compiles it much better than inline in the caller's loop) */
	private static void minPerOwner( final float[] dists, final int count, final int numSubsets, final int firstOwner, final float[] minDistPerOwner )
	{
		for ( int col = 0, owner = firstOwner; col < count; ++owner )
		{
			float min = minDistPerOwner[ owner ];
			for ( int subset = 0; subset < numSubsets; ++subset, ++col )
				if ( dists[ col ] < min )
					min = dists[ col ];
			minDistPerOwner[ owner ] = min;
		}
	}
}
