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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.legacy;

import java.util.ArrayList;

import mpicbg.models.Point;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.AbstractPointDescriptor;

/**
 * The original RGLDM descriptor matching: every A descriptor against every B descriptor through
 * {@link AbstractPointDescriptor#descriptorDistance}. Kept as the reference implementation, {@code RGLDMMatcherTest}
 * checks that the fast paths in {@code SubsetVectorMatching} return exactly its candidates. Selected by
 * {@code DescriptorSearch.LEGACY}.
 * <p>
 * With a search radius, an A descriptor with a single B descriptor inside the radius has no second-best for the ratio
 * test: that B descriptor is a candidate, the radius has disambiguated it (2026-11-13, such A descriptors were dropped
 * before; see {@code SubsetVectorMatching}).
 */
public class RGLDMLegacy
{
	public static final < I extends InterestPoint, D extends AbstractPointDescriptor< I , D > > ArrayList< PointMatchGeneric< I > > findCorrespondingDescriptors(
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

			int numCompared = 0; // the B descriptors inside the search radius, all of them without a radius

			for ( final D descriptorB : descriptorsB )
			{
				if ( limitSearchRadius && Point.distance( descriptorA.getBasisPoint(), descriptorB.getBasisPoint() ) > searchRadius )
						continue;

				++numCompared;

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

			// the only B descriptor inside the search radius: a candidate without the ratio test, there is no second-best
			final boolean lone = limitSearchRadius && numCompared == 1;

			if ( bestMatch != null && bestDifference < differenceThreshold &&
				( lone || ( bestDifference * nTimesBetter < secondBestDifference && secondBestDifference != Double.MAX_VALUE ) ) ) // otherwise there must be a second one (make sure 2nd best is set)
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

}
