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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.subsetvector;

import java.util.ArrayList;
import java.util.List;

import net.imglib2.KDTree;
import net.imglib2.neighborsearch.RadiusNeighborSearchOnKDTree;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.DescriptorSearch;
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
 * <p>
 * A candidate is the best B descriptor if it is {@code ratioOfDistance} times better than the second-best. With a search
 * radius both come from inside the radius, and an A descriptor with a single B descriptor inside it has no second-best:
 * that B descriptor is a candidate without the ratio test, the radius has disambiguated it (as {@code EmbeddingsMatcher}
 * does). {@code differenceThreshold} still applies to it. Until 2026-11-13 such an A descriptor was dropped, so a radius
 * smaller than the spacing of the points lost nearly all candidates, the true partners first.
 */
public class SubsetVectorMatching
{
	/**
	 * Number of A descriptors, evenly spaced over A, whose radius queries estimate the fraction of B inside the search
	 * radius ({@link #sampleInRadiusFraction}). Only {@link DescriptorSearch#AUTO} needs the estimate, in
	 * {@link #preferTree}, to pick the tree or brute force for a view pair. The estimate only has to fall on the right side
	 * of those thresholds, and 256 queries against the 3-D tree are negligible next to the matching itself.
	 */
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
	public static < I extends InterestPoint, D extends AbstractPointDescriptor< I, D > > ArrayList< PointMatchGeneric< I > > findCorrespondingDescriptors(
			final List< D > descsA, final List< D > descsB, final int[][] subsets, final double ratioOfDistance, final double differenceThreshold,
			final boolean limitSearchRadius, final double searchRadius, final DescriptorSearch search )
	{
		final ArrayList< PointMatchGeneric< I > > candidates = new ArrayList<>();
		// without a radius a candidate needs a second-best, so two B descriptors; with a radius a single one inside it suffices
		if ( descsA.isEmpty() || descsB.isEmpty() || ( !limitSearchRadius && descsB.size() < 2 ) )
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
				searcher.searchAll( indexA, bestMatches );
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
			// only asked for two or more B descriptors inside the radius
			final SubsetSearch searcher = descsB.size() < 2 ? null : createSearch( search, numNeighbors, inRadiusFraction, vecsA, vecsB, numSubsets );
			final int[] ownersB = new int[ descsB.size() ];
			for ( int indexA = 0; indexA < descsA.size(); ++indexA )
			{
				radiusSearch.search( descsA.get( indexA ).getBasisPoint(), searchRadius, false );
				final int numInRadius = radiusSearch.numNeighbors();
				if ( numInRadius == 0 )
					continue;
				if ( numInRadius == 1 )
				{
					// the only B descriptor inside the radius: best without a second-best, see the candidate test below
					final int ownerB = radiusSearch.getSampler( 0 ).get();
					bestMatches.fold( indexA, ownerB, minSubsetDistance( vecsA, vecsB, indexA, ownerB, numSubsets ) );
					continue;
				}
				for ( int neighbor = 0; neighbor < numInRadius; ++neighbor )
					ownersB[ neighbor ] = radiusSearch.getSampler( neighbor ).get();
				searcher.searchWithin( indexA, ownersB, numInRadius, bestMatches );
			}
		}

		for ( int indexA = 0; indexA < descsA.size(); ++indexA )
		{
			if ( bestMatches.bestOwner[ indexA ] < 0 )
				continue;

			final double bestDist = bestMatches.bestDist[ indexA ] / numDims;
			final double secondDist = bestMatches.secondDist[ indexA ] / numDims;

			// no second-best = the only B descriptor inside the search radius: a candidate without the ratio test
			final boolean lone = limitSearchRadius && bestMatches.secondDist[ indexA ] == Double.MAX_VALUE;

			if ( bestDist < differenceThreshold && ( lone || ( bestMatches.secondDist[ indexA ] < Double.MAX_VALUE && bestDist * ratioOfDistance < secondDist ) ) )
				candidates.add( new PointMatchGeneric< I >( descsA.get( indexA ).getBasisPoint(), descsB.get( bestMatches.bestOwner[ indexA ] ).getBasisPoint() ) );
		}
		return candidates;
	}

	/** the RGLDM distance of one A to one B descriptor before the division by the dimensions: the minimum over all pairs of their subset vectors */
	private static double minSubsetDistance( final float[][] vecsA, final float[][] vecsB, final int indexA, final int ownerB, final int numSubsets )
	{
		double min = Double.MAX_VALUE;
		for ( int subsetA = 0; subsetA < numSubsets; ++subsetA )
		{
			final float[] vecA = vecsA[ indexA * numSubsets + subsetA ];
			for ( int subsetB = 0; subsetB < numSubsets; ++subsetB )
			{
				final float[] vecB = vecsB[ ownerB * numSubsets + subsetB ];
				double dist = 0;
				for ( int i = 0; i < vecA.length; ++i )
				{
					final double diff = vecA[ i ] - vecB[ i ];
					dist += diff * diff;
				}
				if ( dist < min )
					min = dist;
			}
		}
		return min;
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
}
