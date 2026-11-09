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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.embeddings;

import java.util.ArrayList;
import java.util.List;

import net.imglib2.KDTree;
import net.imglib2.neighborsearch.RadiusNeighborSearchOnKDTree;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;

/**
 * Matches two views on the embeddings stored with their interest points (the per-point array {@code embedding} of the
 * interest point store, one float32 unit vector per point, written by pcmatch's {@code embed}, read by
 * {@code InterestPointArrays}): a radius query in the registered frame, one cosine per candidate pair, a ratio test in
 * similarity space. The reference implementation is pcmatch's {@code matching.radius_sparse_match} (one scale),
 * specified in {@code SCALESPACE_TASK_matching.md} section 3. {@code EmbeddingsPairwise} wraps it as a matcher.
 *
 * <pre>
 * for every point a of A with a vector:
 *     J = the points of B (with a vector) within the radius of a (Euclidean, inclusive) in the registered frame
 *     s_j = v_a . v_j
 *     one neighbour: a candidate if s_j &gt; smin (no runner-up for the ratio test, the geometric prior has disambiguated it)
 *     else: best, second = the two largest s_j; a candidate if best &gt; smin and (1 - best) &lt; ratio * (1 - second)
 * </pre>
 *
 * One candidate per point of A at most, none per point of B (several points of A may name the same point of B; RANSAC's
 * consistency check reduces that). The candidates of (A, B) are not those of (B, A).
 *
 * @author Stephan Preibisch
 */
public class EmbeddingsMatcher
{
	/** the ratio test's threshold: the best partner's cosine distance must be below this fraction of the runner-up's (Stephan, 2026-10-28: one fixed value while the models are under test) */
	public static final double defaultRatio = 0.85;

	/** the smallest cosine a candidate may have */
	public static final double defaultSmin = 0.3;

	/**
	 * The candidates and what was seen on the way: how many points of A and of B have a partner within the radius (the
	 * pair's overlap as far as the registration knows it).
	 */
	public record Candidates< I extends InterestPoint >( ArrayList< PointMatchGeneric< I > > candidates, int withinRadiusA, int withinRadiusB ) {}

	/**
	 * @param pointsA the points of A that have a vector, positions ({@link InterestPoint#getW()}) in the registered frame
	 * @param vectorsA the vectors of {@code pointsA}, {@code columns} values per point in the order of the list
	 * @param pointsB the points of B that have a vector
	 * @param vectorsB the vectors of {@code pointsB}
	 * @param columns the dimension of the vectors
	 * @param radius the search radius in the registered frame (the registration's uncertainty), {@link Double#POSITIVE_INFINITY} for no limit (every point of B is a possible partner of every point of A)
	 * @param ratio the ratio test's threshold in similarity space, see {@link #defaultRatio}
	 * @param smin the smallest cosine of a candidate, see {@link #defaultSmin}
	 * @return the candidates, one per point of A at most
	 */
	public static < I extends InterestPoint > Candidates< I > match(
			final List< I > pointsA,
			final float[] vectorsA,
			final List< I > pointsB,
			final float[] vectorsB,
			final int columns,
			final double radius,
			final double ratio,
			final double smin )
	{
		if ( vectorsA.length != pointsA.size() * columns || vectorsB.length != pointsB.size() * columns )
			throw new IllegalArgumentException( "The vectors do not fit the points: " + vectorsA.length + " values for " + pointsA.size() + " points of A, " + vectorsB.length + " for " + pointsB.size() + " of B, " + columns + " per point." );
		if ( !( radius >= 0 ) )
			throw new IllegalArgumentException( "The search radius must be a non-negative number, got " + radius + "." );

		final ArrayList< PointMatchGeneric< I > > candidates = new ArrayList<>();

		if ( pointsA.isEmpty() || pointsB.isEmpty() )
			return new Candidates<>( candidates, 0, 0 );

		final List< Integer > indicesB = new ArrayList<>( pointsB.size() );
		for ( int j = 0; j < pointsB.size(); ++j )
			indicesB.add( j );

		final boolean unlimited = Double.isInfinite( radius ); // every point of B: no tree, all of B per point of A
		final RadiusNeighborSearchOnKDTree< Integer > search = unlimited ? null : new RadiusNeighborSearchOnKDTree<>( new KDTree<>( indicesB, pointsB ) );
		final boolean[] hitB = new boolean[ pointsB.size() ];
		final double squaredRadius = radius * radius;
		final double queryRadius = Math.nextUp( radius ); // the tree's search is strict at the radius, the reference is inclusive: ask for a hair more and test exactly below
		int withinA = 0;

		for ( int a = 0; a < pointsA.size(); ++a )
		{
			final I pointA = pointsA.get( a );
			final int numNeighbors;

			if ( unlimited )
			{
				numNeighbors = pointsB.size();
			}
			else
			{
				search.search( pointA, queryRadius, false );
				numNeighbors = search.numNeighbors();
			}

			int count = 0, bestJ = -1;
			double best = Double.NEGATIVE_INFINITY, second = Double.NEGATIVE_INFINITY;

			for ( int i = 0; i < numNeighbors; ++i )
			{
				if ( !unlimited && search.getSquareDistance( i ) > squaredRadius ) // inclusive, as the reference's query_ball_point
					continue;

				final int j = unlimited ? i : search.getSampler( i ).get();
				hitB[ j ] = true;
				++count;

				final double s = dot( vectorsA, a * columns, vectorsB, j * columns, columns );
				if ( s > best )
				{
					second = best;
					best = s;
					bestJ = j;
				}
				else if ( s > second )
				{
					second = s;
				}
			}

			if ( count == 0 )
				continue;

			++withinA;

			final boolean candidate = count == 1 ? best > smin : best > smin && ( 1 - best ) < ratio * ( 1 - second );
			if ( candidate )
				candidates.add( new PointMatchGeneric< I >( pointA, pointsB.get( bestJ ) ) );
		}

		int withinB = 0;
		for ( final boolean hit : hitB )
			if ( hit )
				++withinB;

		return new Candidates<>( candidates, withinA, withinB );
	}

	/** The dot product of two vectors (unit vectors: their cosine), accumulated in double. */
	static double dot( final float[] a, final int fromA, final float[] b, final int fromB, final int columns )
	{
		double sum = 0;
		for ( int d = 0; d < columns; ++d )
			sum += (double) a[ fromA + d ] * b[ fromB + d ];
		return sum;
	}
}
