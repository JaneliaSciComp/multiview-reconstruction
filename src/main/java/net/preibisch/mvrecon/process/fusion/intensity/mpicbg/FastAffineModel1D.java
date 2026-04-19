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
import java.util.List;

import mpicbg.models.AbstractModel;
import mpicbg.models.AffineModel1D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;

public class FastAffineModel1D extends AffineModel1D implements FastModel
{
	public FastAffineModel1D()
	{
		super();
	}

	@Override
	public FastAffineModel1D copy()
	{
		FastAffineModel1D  m = new FastAffineModel1D();
		m.set( this );
		return m;
	}

	public void fit( final FlattenedMatches matches )
			throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		fit( matches, null );
	}

	/**
	 * Estimate a {@code AffineModel1D} from a set with many outliers by first
	 * filtering the worst outliers with RANSAC and filter potential outliers by
	 * robust iterative regression.
	 *
	 * @param candidates candidate data points including (many) outliers
	 * @param iterations number of RANSAC iterations
	 * @param maxEpsilon maximal allowed transfer error
	 * @param minInlierRatio minimal number of inliers to number of candidates
	 * @param minNumInliers minimally required absolute number of inliers
	 * @param maxTrust reject candidates with a cost larger than maxTrust * median cost
	 *
	 * @return indices of {@code candidates} that are inliers, or {@code null}
	 * if the model could not be fitted (this model remains unchanged in that
	 * case).
	 */
	public MatchIndices fastFilterRansac(
			final FlattenedMatches candidates,
			final int iterations,
			final double maxEpsilon,
			final double minInlierRatio,
			final int minNumInliers,
			final double maxTrust )
			throws NotEnoughDataPointsException
	{
		final MatchIndices inliers = new MatchIndices( candidates.size() );
		if ( !ransac( candidates, inliers, iterations, maxEpsilon, minInlierRatio, minNumInliers ) )
			return null;
		if ( !filter( candidates, inliers, maxTrust, minNumInliers ) )
			return null;
		return inliers;
	}

	private boolean filter(
			final FlattenedMatches candidates,
			final MatchIndices inliers,
			final double maxTrust,
			final int minNumInliers )
			throws NotEnoughDataPointsException
	{
		if ( inliers.size() < getMinNumMatches() )
			throw new NotEnoughDataPointsException( inliers.size() + " data points are not enough to solve the Model, at least " + getMinNumMatches() + " data points required." );

		final FastAffineModel1D copy = copy();

		// extract PointMatch data into flat arrays
		final int numCandidates = candidates.size();
		final SimpleErrorStatistic observer = new SimpleErrorStatistic( numCandidates );

		int numInliers;
		do
		{
			observer.clear();
			numInliers = inliers.size();
			try
			{
				copy.fit( candidates, inliers );
			}
			catch ( final NotEnoughDataPointsException | IllDefinedDataPointsException e )
			{
				return false;
			}
			final int[] samples = inliers.indices();
			final double[] p = candidates.p()[ 0 ];
			final double[] q = candidates.q()[ 0 ];
			for ( int i = 0; i < numInliers; i++ )
			{
				final int k = samples[ i ];
				final double distance = Math.abs( copy.apply( p[ k ] ) - q[ k ] );
				observer.add( distance );
			}
			final double t = observer.getMedian() * maxTrust;
			int j = 0;
			for ( int i = 0; i < numInliers; i++ )
			{
				final int k = samples[ i ];
				final double distance = Math.abs( copy.apply( p[ k ] ) - q[ k ] );
				if ( distance <= t )
					samples[ j++ ] = k;
			}
			inliers.setSize( j );
			copy.cost = observer.mean();
		}
		while ( numInliers > inliers.size() );

		if ( numInliers < minNumInliers )
			return false;

		set( copy );
		return true;

	}

	private void fit( FlattenedMatches matches, MatchIndices indices ) throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		final double[] p = matches.p()[ 0 ];
		final double[] q = matches.q()[ 0 ];
		final double[] w = matches.w();
		final int size = indices != null ? indices.size() : matches.size();

		if ( size < MIN_NUM_MATCHES )
			throw new NotEnoughDataPointsException( size + " data points are not enough to estimate a 2d affine model, at least " + MIN_NUM_MATCHES + " data points required." );

		if ( matches.weighted() )
		{

			double W = 0;
			double S_p = 0;
			double S_q = 0;
			double S_pp = 0;
			double S_pq = 0;

			if ( indices != null )
			{
				final int[] samples = indices.indices();
				for ( int i = 0; i < size; i++ )
				{
					final int sample = samples[ i ];
					final double p_i = p[ sample ];
					final double q_i = q[ sample ];
					final double w_i = w[ sample ];
					W += w_i;
					S_p += w_i * p_i;
					S_q += w_i * q_i;
					S_pp += w_i * p_i * p_i;
					S_pq += w_i * p_i * q_i;
				}
			} else {
				for ( int i = 0; i < size; i++ )
				{
					final double p_i = p[ i ];
					final double q_i = q[ i ];
					final double w_i = w[ i ];
					W += w_i;
					S_p += w_i * p_i;
					S_q += w_i * q_i;
					S_pp += w_i * p_i * p_i;
					S_pq += w_i * p_i * q_i;
				}
			}

			final double a = W * S_pp - S_p * S_p;
			if ( a == 0 )
				throw new IllDefinedDataPointsException();
			m00 = ( W * S_pq - S_p * S_q ) / a;
			m01 = ( S_q - m00 * S_p ) / W;
		}
		else
		{
			double S_p = 0;
			double S_q = 0;
			double S_pp = 0;
			double S_pq = 0;

			if ( indices != null )
			{
				final int[] samples = indices.indices();
				for ( int i = 0; i < size; i++ )
				{
					final int sample = samples[ i ];
					final double p_i = p[ sample ];
					final double q_i = q[ sample ];
					S_p += p_i;
					S_q += q_i;
					S_pp += p_i * p_i;
					S_pq += p_i * q_i;
				}
			}
			else
			{
				for ( int i = 0; i < size; i++ )
				{
					final double p_i = p[ i ];
					final double q_i = q[ i ];
					S_p += p_i;
					S_q += q_i;
					S_pp += p_i * p_i;
					S_pq += p_i * q_i;
				}
			}

			final double a = size * S_pp - S_p * S_p;
			if ( a == 0 )
				throw new IllDefinedDataPointsException();
			m00 = ( size * S_pq - S_p * S_q ) / a;
			m01 = ( S_q - m00 * S_p ) / size;
		}
		invert();
	}

	private boolean ransac(
			final FlattenedMatches candidates,
			final MatchIndices inliers,
			final int iterations,
			final double epsilon,
			final double minInlierRatio,
			final int minNumInliers
	) throws NotEnoughDataPointsException
	{
		if ( candidates.size() < getMinNumMatches() )
			throw new NotEnoughDataPointsException( candidates.size() + " data points are not enough to solve the Model, at least " + getMinNumMatches() + " data points required." );

		cost = Double.MAX_VALUE;

		final FastAffineModel1D copy = copy();
		final FastAffineModel1D m = copy();

		final int numCandidates = candidates.size();
		final int numSampled = getMinNumMatches();
		final MatchIndices samples = new MatchIndices( numSampled );
		final MatchIndices tempInliers = new MatchIndices( numCandidates );
		inliers.setSize( 0 );

		int i = 0;
		A:
		while ( i < iterations )
		{
			// choose model.MIN_SET_SIZE disjunctive matches randomly
			samples.sample( rnd, numCandidates );
			try
			{
				m.fit( candidates, samples );
			}
			catch ( final IllDefinedDataPointsException e )
			{
				++i;
				continue;
			}

			int numInliers = 0;

			boolean isGood = m.test( candidates, tempInliers, epsilon, minInlierRatio, minNumInliers );
			while ( isGood && numInliers != tempInliers.size() )
			{
				numInliers = tempInliers.size();
				try
				{
					m.fit( candidates, tempInliers );
				}
				catch ( final IllDefinedDataPointsException e )
				{
					++i;
					continue A;
				}
				isGood = m.test( candidates, tempInliers, epsilon, minInlierRatio, minNumInliers );
			}
			if ( isGood && m.betterThan( copy ) )
			{
				copy.set( m );
				inliers.set( tempInliers );
			}
			++i;
		}

		if ( inliers.size() == 0 )
			return false;

		set( copy );
		return true;
	}

	public double apply( final double l )
	{
		return l * m00 + m01;
	}

	/**
	 * Test the model for a set of PointMatch candidates. Store the indices
	 * of inlier candidates into the given {@code inliers} array and return
	 * the number of inliers.
	 *
	 * @param candidates
	 * 		set of point correspondence candidates
	 * @param inliers
	 * 		will be filled with indices of candidates that fit the model
	 * @param epsilon
	 * 		maximal allowed transfer error
	 * @param minInlierRatio
	 * 		minimal number of inliers to number of candidates
	 *
	 * @return number of inliers (number of valid indices in {@code inliers})
	 */
	private boolean test(
			final FlattenedMatches candidates,
			final MatchIndices inliers,
			final double epsilon,
			final double minInlierRatio,
			final int minNumInliers )
	{
		final int numCandidates = candidates.size();
		final double[] p = candidates.p()[ 0 ];
		final double[] q = candidates.q()[ 0 ];
		final int[] inlierIndices = inliers.indices();
		int i = 0;
		for ( int k = 0; k < numCandidates; k++ )
		{
			final double diff = apply( p[ k ] ) - q[ k ];
			if ( Math.abs( diff ) < epsilon )
			{
				inlierIndices[ i++ ] = k;
			}
		}
		final int numInliers = i;
		inliers.setSize( numInliers );

		final double ir = ( double ) numInliers / ( double ) numCandidates;
		setCost( Math.max( 0.0, Math.min( 1.0, 1.0 - ir ) ) );
		return ( numInliers >= minNumInliers && ir > minInlierRatio );
	}
}
