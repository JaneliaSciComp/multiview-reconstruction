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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm;

import java.util.ArrayList;
import java.util.List;

import mpicbg.models.Point;
import net.imglib2.KDTree;
import net.imglib2.neighborsearch.KNearestNeighborSearchOnKDTree;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.AbstractPointDescriptor;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.SimplePointDescriptor;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.exception.NoSuitablePointsException;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.matcher.Matcher;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.matcher.SubsetMatcher;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.similarity.SimilarityMeasure;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.similarity.SquareDistance;

public class RGLDMMatcher< I extends InterestPoint >
{
	public ArrayList< PointMatchGeneric< I > > extractCorrespondenceCandidates( 
			final List< I > nodeListA,
			final List< I > nodeListB,
			final int numNeighbors,
			final int redundancy,
			final double ratioOfDistance,
			final double differenceThreshold,
			final boolean limitSearchRadius,
			final double searchRadius )
	{
		/* create KDTrees */	
		final KDTree< I > treeA = new KDTree< I >( nodeListA, nodeListA );
		final KDTree< I > treeB = new KDTree< I >( nodeListB, nodeListB );
		
		/* extract point descriptors */
		final Matcher matcher = new SubsetMatcher( numNeighbors, numNeighbors + redundancy );
		final int numRequiredNeighbors = matcher.getRequiredNumNeighbors();
		
		final SimilarityMeasure similarityMeasure = new SquareDistance();
		
		final ArrayList< SimplePointDescriptor< I > > descriptorsA = createSimplePointDescriptors( treeA, nodeListA, numRequiredNeighbors, matcher, similarityMeasure );
		final ArrayList< SimplePointDescriptor< I > > descriptorsB = createSimplePointDescriptors( treeB, nodeListB, numRequiredNeighbors, matcher, similarityMeasure );

		return findCorrespondingDescriptors( descriptorsA, descriptorsB, ratioOfDistance, differenceThreshold, limitSearchRadius, searchRadius );
	}
	
	protected static final < I extends InterestPoint, D extends AbstractPointDescriptor< I , D > > ArrayList< PointMatchGeneric< I > > findCorrespondingDescriptors(
			final ArrayList< D > descriptorsA,
			final ArrayList< D > descriptorsB,
			final double nTimesBetter,
			final double differenceThreshold,
			final boolean limitSearchRadius,
			final double searchRadius )
	{
		final ArrayList< PointMatchGeneric< I > > correspondenceCandidates = new ArrayList<>();

		// TODO: smaller list on the outside
		for ( final D descriptorA : descriptorsA )
		{
			double bestDifference = Double.MAX_VALUE;
			double secondBestDifference = Double.MAX_VALUE;

			D bestMatch = null;
			D secondBestMatch = null;

			for ( final D descriptorB : descriptorsB )
			{
				if ( limitSearchRadius && Point.distance( descriptorA.getBasisPoint(), descriptorB.getBasisPoint() ) > searchRadius )
						continue;

				final double difference = descriptorA.descriptorDistance( descriptorB );

				if ( difference < secondBestDifference )
				{
					secondBestDifference = difference;
					secondBestMatch = descriptorB;
					
					if ( secondBestDifference < bestDifference )
					{
						double tmpDiff = secondBestDifference;
						D tmpMatch = secondBestMatch;
						
						secondBestDifference = bestDifference;
						secondBestMatch = bestMatch;
						
						bestDifference = tmpDiff;
						bestMatch = tmpMatch;
					}
				}
			}

			if ( bestDifference < differenceThreshold && bestDifference * nTimesBetter < secondBestDifference && secondBestDifference != Double.MAX_VALUE ) // there must be a second one (make sure 2nd best is set)
			{	
				// add correspondence for the two basis points of the descriptor
				I detectionA = descriptorA.getBasisPoint();
				I detectionB = bestMatch.getBasisPoint();
				
				// for RANSAC
				correspondenceCandidates.add( new PointMatchGeneric< I >( detectionA, detectionB ) );
			}
		}

		return correspondenceCandidates;
	}

	protected static < I extends InterestPoint > ArrayList< SimplePointDescriptor< I > > createSimplePointDescriptors(
			final KDTree< I > tree,
			final List< I > basisPoints,
			final int numNeighbors,
			final Matcher matcher,
			final SimilarityMeasure similarityMeasure )
	{
		final KNearestNeighborSearchOnKDTree< I > nnsearch = new KNearestNeighborSearchOnKDTree<>( tree, numNeighbors + 1 );
		final ArrayList< SimplePointDescriptor< I > > descriptors = new ArrayList<> ( );

		for ( final I p : basisPoints )
		{
			final ArrayList< I > neighbors = new ArrayList<>();
			nnsearch.search( p );

			// the first hit is always the point itself
			for ( int n = 1; n < numNeighbors + 1; ++n )
				neighbors.add( nnsearch.getSampler( n ).get() );

			try
			{
				descriptors.add( new SimplePointDescriptor< I >( p, neighbors, similarityMeasure, matcher ) );
			}
			catch ( NoSuitablePointsException e )
			{
				e.printStackTrace();
			}
		}

		return descriptors;
	}

}
