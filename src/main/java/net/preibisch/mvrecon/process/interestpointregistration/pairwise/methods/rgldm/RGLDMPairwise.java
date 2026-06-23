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
import java.util.Collection;

import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.ImgLib2Temp.Pair;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.MatcherPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.PairwiseResult;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.ransac.RANSAC;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.ransac.RANSACParameters;

public class RGLDMPairwise< I extends InterestPoint > implements MatcherPairwise< I >
{
	final RANSACParameters rp;
	final RGLDMParameters dp;
	boolean printResult = true;

	public RGLDMPairwise(
			final RANSACParameters rp,
			final RGLDMParameters dp  )
	{
		this.rp = rp;
		this.dp = dp;
	}

	public void setPrintResult( final boolean printResult ) { this.printResult = printResult; }
	public boolean printResult() { return printResult; }

	@Override
	public <V> PairwiseResult<I> match(
			final Collection<I> listAIn,
			final Collection<I> listBIn,
			final V viewsA,
			final V viewsB,
			final String labelA,
			final String labelB )
	{
		final PairwiseResult< I > result = new PairwiseResult< I >( true );
		result.setPrintOut( printResult );

		final ArrayList< I > listA = new ArrayList<>( listAIn );
		final ArrayList< I > listB = new ArrayList<>( listBIn );

		final int minPoints = dp.getNumNeighbors() + dp.getRedundancy() + 1;

		if ( listA.size() < minPoints || listB.size() < minPoints )
		{
			result.setResult( System.currentTimeMillis(), "Not enough detections to match" );
			result.setCandidates( new ArrayList< PointMatchGeneric< I > >() );
			result.setInliers( new ArrayList< PointMatchGeneric< I > >(), Double.NaN );
			return result;
		}

		final RGLDMMatcher< I > matcher = new RGLDMMatcher< I >();
		final ArrayList< PointMatchGeneric< I > > candidates = matcher.extractCorrespondenceCandidates(
				listA,
				listB,
				dp.getNumNeighbors(),
				dp.getRedundancy(),
				dp.getRatioOfDistance(),
				dp.getDifferenceThreshold(),
				dp.limitSearchRadius(),
				dp.searchRadius() );

		result.setCandidates( candidates );

		// compute ransac and remove inconsistent candidates
		final ArrayList< PointMatchGeneric< I > > inliers = new ArrayList<>();
		final ArrayList< Integer > setIds = new ArrayList<>();

		final Pair< String, Double > ransacResult =
				RANSAC.computeRANSAC(
						candidates,
						inliers,
						setIds,
						dp.getModel(),
						rp.getMaxEpsilon(),
						rp.getMinInlierRatio(),
						rp.getMinNumMatches(),
						rp.getNumIterations(),
						rp.multiConsensus(),
						rp.getMaxTrust(),
						rp.getFilterRansac() );

		result.setInliers( inliers, ransacResult.getB(), setIds );
	
		result.setResult( System.currentTimeMillis(), ransacResult.getA() );
		
		return result;
	}

	/**
	 * We only read the points, no reason to duplicate, RANSAC does its own duplication
	 */
	@Override
	public boolean requiresInterestPointDuplication() { return true; }
}
