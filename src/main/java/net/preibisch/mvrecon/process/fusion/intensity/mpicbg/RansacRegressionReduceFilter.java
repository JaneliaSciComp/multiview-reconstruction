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
package net.preibisch.mvrecon.process.fusion.intensity.mpicbg;

import java.util.Collection;

import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Stephan Saalfeld saalfelds@janelia.hhmi.org
 */
public class RansacRegressionReduceFilter
{
	private static final Logger LOG = LoggerFactory.getLogger(RansacRegressionReduceFilter.class);

	private final FastAffineModel1D model;

	private final int iterations;

	private final double maxEpsilon;

	private final double minInlierRatio;

	private final int minNumInliers;

	private final double maxTrust;

	/**
	 * @param model the model to fit
	 * @param iterations number of RANSAC iterations
	 * @param maxEpsilon maximal allowed transfer error
	 * @param minInlierRatio minimal number of inliers to number of candidates
	 * @param minNumInliers minimally required absolute number of inliers
	 * @param maxTrust reject candidates with a cost larger than maxTrust * median cost
	 */
	public RansacRegressionReduceFilter(
			final FastAffineModel1D model,
			final int iterations,
			final double maxEpsilon,
			final double minInlierRatio,
			final int minNumInliers,
			final double maxTrust
	)
	{
		this.model = model;
		this.iterations = iterations;
		this.maxEpsilon = maxEpsilon;
		this.minInlierRatio = minInlierRatio;
		this.minNumInliers = minNumInliers;
		this.maxTrust = maxTrust;
	}

	public void filter( final FlattenedMatches candidates, final Collection< PointMatch > inliers )
	{
		inliers.clear();
		try
		{
			final MatchIndices flatInliers = model.fastFilterRansac( candidates, iterations, maxEpsilon, minInlierRatio, minNumInliers, maxTrust );
			if ( flatInliers != null )
			{
				LOG.debug("inliers/candidates: {}/{}", flatInliers.size(), candidates.size());
				final double[] minMax = minMax( candidates, flatInliers );
				final double min = minMax[ 0 ];
				final double max = minMax[ 1 ];
				inliers.add( new PointMatch1D( new Point1D( min ), new Point1D( model.apply( min ) ), 1.0 ) );
				inliers.add( new PointMatch1D( new Point1D( max ), new Point1D( model.apply( max ) ), 1.0 ) );
			}
		}
		catch ( NotEnoughDataPointsException ignored )
		{
		}
	}

	private double[] minMax( final FlattenedMatches matches, final MatchIndices indices )
	{
		final int size = indices.size();
		if ( size == 0 )
			return new double[] { Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY };
		final int[] samples = indices.indices();
		final double[] p = matches.p()[ 0 ];
		double min = p[ samples[ 0 ] ];
		double max = min;
		for ( int i = 1; i < size; i++ )
		{
			final double x = p[ samples[ i ] ];
			if ( x < min )
				min = x;
			else if ( x > max )
				max = x;
		}
		return new double[] { min, max };
	}
}
