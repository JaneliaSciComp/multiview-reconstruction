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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.geometrichashing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

import net.imglib2.KDTree;
import net.imglib2.neighborsearch.KNearestNeighborSearchOnKDTree;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.LocalCoordinateSystemPointDescriptor;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.exception.NoSuitablePointsException;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.matcher.SubsetMatcher;

/**
 * Geometric hashing: every point gets a rotation-invariant {@link LocalCoordinateSystemPointDescriptor} from its three
 * nearest neighbors, B's descriptors go into a KD-tree, and an A descriptor yields a candidate when its nearest B
 * descriptor is {@code ratioOfDistance} times closer (in squared descriptor distance) than the nearest descriptor of a
 * different B point.
 * <p>
 * With redundancy r every point has C = (3 + r choose 3) descriptors, one per 3-subset of its 3 + r nearest neighbors,
 * and all of them sit in the same tree. The nearest descriptor of a different point is therefore looked for among the
 * C + 1 nearest descriptors, which always contain one: at most C of them can belong to the nearest point. This is the
 * C + 1 construction of the flat KD-tree search of RGLDM, applied per descriptor. Until 2026-10 the second-nearest
 * descriptor was used whatever its point, so another descriptor of the nearest point could veto a match; on synthetic
 * clouds that cost about 2 % of the true candidates at redundancy 2 and nothing at redundancy 0.
 * <p>
 * Folding the C queries of an A point into one distance per B point, the minimum over all descriptor pairs as in RGLDM,
 * was measured and is much worse for these descriptors: the second-best point gets the minimum over C&sup2; pairs too, and
 * at realistic localization errors the ratio test then rejects most true matches.
 *
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 */
public class GeometricHasher< I extends InterestPoint >
{
	private static final int NUM_NEIGHBORS = 3;

	public ArrayList< PointMatchGeneric< I > > extractCorrespondenceCandidates(
			final ArrayList< I > nodeListA,
			final ArrayList< I > nodeListB,
			final double differenceThreshold,
			final int redundancy,
			final double ratioOfDistance )
	{
		final ArrayList< PointMatchGeneric< I > > candidates = new ArrayList<>();
		if ( nodeListA.isEmpty() || nodeListB.isEmpty() )
			return candidates;

		final ArrayList< LocalCoordinateSystemPointDescriptor< I > > descriptorsA =
				createLocalCoordinateSystemPointDescriptors( new KDTree<>( nodeListA, nodeListA ), nodeListA, redundancy, false );
		final ArrayList< LocalCoordinateSystemPointDescriptor< I > > descriptorsB =
				createLocalCoordinateSystemPointDescriptors( new KDTree<>( nodeListB, nodeListB ), nodeListB, redundancy, false );
		if ( descriptorsB.isEmpty() )
			return candidates;

		// the C + 1 nearest B descriptors always include one of a point other than the nearest (see the class comment)
		final int numSubsets = SubsetMatcher.computePD( NUM_NEIGHBORS + redundancy, NUM_NEIGHBORS, 1 ).length;
		final int numNearest = Math.min( numSubsets + 1, descriptorsB.size() );
		final KDTree< LocalCoordinateSystemPointDescriptor< I > > lookUpTreeB = new KDTree<>( descriptorsB, descriptorsB );
		final KNearestNeighborSearchOnKDTree< LocalCoordinateSystemPointDescriptor< I > > searchB =
				new KNearestNeighborSearchOnKDTree<>( lookUpTreeB, numNearest );

		// the same pair can show up once per descriptor of the A point
		final LinkedHashSet< Pair< I, I > > pairs = new LinkedHashSet<>();

		for ( final LocalCoordinateSystemPointDescriptor< I > descriptorA : descriptorsA )
		{
			searchB.search( descriptorA );
			final LocalCoordinateSystemPointDescriptor< I > nearest = searchB.getSampler( 0 ).get();
			final double best = descriptorA.descriptorDistance( nearest );

			// the nearest descriptor of a different B point
			double secondBest = Double.MAX_VALUE;
			for ( int n = 1; n < numNearest; ++n )
			{
				final LocalCoordinateSystemPointDescriptor< I > other = searchB.getSampler( n ).get();
				if ( other.getBasisPoint() != nearest.getBasisPoint() )
				{
					secondBest = descriptorA.descriptorDistance( other );
					break;
				}
			}

			if ( secondBest < Double.MAX_VALUE && best < differenceThreshold && best * ratioOfDistance <= secondBest )
				pairs.add( new ValuePair<>( descriptorA.getBasisPoint(), nearest.getBasisPoint() ) );
		}

		for ( final Pair< I, I > pair : pairs )
			candidates.add( new PointMatchGeneric< I >( pair.getA(), pair.getB(), 1 ) );

		return candidates;
	}

	/**
	 * One {@link LocalCoordinateSystemPointDescriptor} per 3-subset of the 3 + redundancy nearest neighbors of every point,
	 * in the order of the points.
	 */
	public static < I extends InterestPoint > ArrayList< LocalCoordinateSystemPointDescriptor< I > > createLocalCoordinateSystemPointDescriptors(
			final KDTree< I > tree,
			final Collection< I > basisPoints,
			final int redundancy,
			final boolean normalize )
	{
		final int[][] subsets = SubsetMatcher.computePD( NUM_NEIGHBORS + redundancy, NUM_NEIGHBORS, 1 );
		final KNearestNeighborSearchOnKDTree< I > neighborSearch = new KNearestNeighborSearchOnKDTree<>( tree, NUM_NEIGHBORS + redundancy + 1 );
		final ArrayList< LocalCoordinateSystemPointDescriptor< I > > descriptors = new ArrayList<>();

		for ( final I point : basisPoints )
			addDescriptors( neighborSearch, point, subsets, normalize, descriptors );

		return descriptors;
	}

	/**
	 * Adds the descriptors of one point, one per subset of its nearest neighbors (indices into the neighbor search, where
	 * 0 is the point itself). A subset that does not span a local coordinate system (identical points) is skipped.
	 */
	private static < I extends InterestPoint > void addDescriptors(
			final KNearestNeighborSearchOnKDTree< I > neighborSearch,
			final I point,
			final int[][] subsets,
			final boolean normalize,
			final List< LocalCoordinateSystemPointDescriptor< I > > descriptors )
	{
		neighborSearch.search( point );

		for ( final int[] subset : subsets )
		{
			final ArrayList< I > neighbors = new ArrayList<>( subset.length );
			for ( final int neighbor : subset )
				neighbors.add( neighborSearch.getSampler( neighbor ).get() );

			try
			{
				descriptors.add( new LocalCoordinateSystemPointDescriptor< I >( point, neighbors, normalize ) );
			}
			catch ( final NoSuitablePointsException e )
			{
				// two identical points, no local coordinate system
			}
		}
	}
}
