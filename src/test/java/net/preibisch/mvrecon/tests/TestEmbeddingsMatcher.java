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
package net.preibisch.mvrecon.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.embeddings.EmbeddingsMatcher;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.embeddings.EmbeddingsMatcher.Candidates;

/**
 * The matcher on stored embeddings ({@link EmbeddingsMatcher}): the radius is inclusive, a single neighbour
 * needs no runner-up, the ratio test runs in similarity space, smin bounds the best partner, candidates go one way.
 * Vectors are 2-D unit vectors (1, 0) for A and (c, sqrt(1 - c^2)) for B, so that the cosine of a pair is c.
 */
public class TestEmbeddingsMatcher
{
	static final double RATIO = 0.85, SMIN = 0.3, RADIUS = 5;

	final List< InterestPoint > pointsA = new ArrayList<>(), pointsB = new ArrayList<>();
	final List< Float > cosB = new ArrayList<>();

	InterestPoint a( final int id, final double x, final double y, final double z )
	{
		final InterestPoint point = new InterestPoint( id, new double[] { x, y, z } );
		pointsA.add( point );
		return point;
	}

	InterestPoint b( final int id, final double x, final double y, final double z, final float cosine )
	{
		final InterestPoint point = new InterestPoint( id, new double[] { x, y, z } );
		pointsB.add( point );
		cosB.add( cosine );
		return point;
	}

	Candidates< InterestPoint > match( final double ratio, final double smin )
	{
		final float[] vA = new float[ 2 * pointsA.size() ];
		for ( int i = 0; i < pointsA.size(); ++i )
			vA[ 2 * i ] = 1;

		final float[] vB = new float[ 2 * pointsB.size() ];
		for ( int j = 0; j < pointsB.size(); ++j )
		{
			vB[ 2 * j ] = cosB.get( j );
			vB[ 2 * j + 1 ] = (float) Math.sqrt( 1 - cosB.get( j ) * cosB.get( j ) );
		}

		return EmbeddingsMatcher.match( pointsA, vA, pointsB, vB, 2, RADIUS, ratio, smin );
	}

	static Set< String > pairs( final Candidates< InterestPoint > candidates )
	{
		final Set< String > pairs = new HashSet<>();
		for ( final PointMatchGeneric< InterestPoint > match : candidates.candidates() )
			pairs.add( match.getPoint1().getId() + "-" + match.getPoint2().getId() );
		return pairs;
	}

	@Test
	public void singleNeighbourNeedsNoRunnerUp()
	{
		a( 1, 0, 0, 0 );
		b( 11, 1, 0, 0, 0.5f ); // the only partner of 1: a candidate above smin
		a( 2, 100, 0, 0 );
		b( 12, 101, 0, 0, 0.2f ); // the only partner of 2: below smin
		a( 3, 200, 0, 0 ); // no partner within the radius
		b( 13, 200, 0, 5.001, 0.9f );

		final Candidates< InterestPoint > candidates = match( RATIO, SMIN );
		assertEquals( Set.of( "1-11" ), pairs( candidates ) );
		assertEquals( 2, candidates.withinRadiusA() );
		assertEquals( 2, candidates.withinRadiusB() );
	}

	@Test
	public void ratioTestInSimilaritySpace()
	{
		a( 1, 0, 0, 0 );
		b( 11, 1, 0, 0, 0.9f ); // (1 - 0.9) = 0.1 < 0.85 * (1 - 0.5) = 0.425: a candidate
		b( 12, 0, 1, 0, 0.5f );
		a( 2, 100, 0, 0 );
		b( 21, 101, 0, 0, 0.9f ); // 0.1 < 0.85 * 0.11 = 0.0935 fails: too close a runner-up
		b( 22, 100, 1, 0, 0.89f );
		a( 3, 200, 0, 0 );
		b( 31, 200, 2, 0, 0.25f ); // best below smin although the ratio passes
		b( 32, 200, 0, 2, -0.5f );

		assertEquals( Set.of( "1-11" ), pairs( match( RATIO, SMIN ) ) );
		assertEquals( Set.of( "1-11", "2-21" ), pairs( match( 0.95, SMIN ) ) ); // 0.1 < 0.95 * 0.11 = 0.1045
		assertEquals( Set.of( "1-11", "3-31" ), pairs( match( RATIO, 0.2 ) ) );
		assertEquals( Set.of(), pairs( match( 0.1, SMIN ) ) ); // 0.1 < 0.1 * 0.5 = 0.05 fails
	}

	@Test
	public void radiusIsInclusiveAndCandidatesGoOneWay()
	{
		a( 1, 0, 0, 0 );
		a( 2, 0, 0, 3 ); // the same partner for 1 and 2 (RANSAC sorts that out)
		b( 11, 0, 0, 5, 0.8f ); // exactly on the radius of 1
		b( 12, 0, 0, 5.0001, 0.99f ); // just outside the radius of 1, the runner-up of 2 (0.01 < 0.85 * 0.2)
		b( 13, 50, 50, 50, 0.99f ); // nobody's partner

		final Candidates< InterestPoint > candidates = match( RATIO, SMIN );
		assertEquals( Set.of( "1-11", "2-12" ), pairs( candidates ) );
		assertEquals( 2, candidates.withinRadiusA() );
		assertEquals( 2, candidates.withinRadiusB() );
		assertTrue( candidates.candidates().get( 0 ).getPoint1() == pointsA.get( 0 ) ); // the points themselves, not copies

		// the other way round: both 11 and 12 reach 2 as their only partner and both name it (one candidate per point of A, none per point of B)
		final float[] one = { 1, 0 }, two = { 1, 0, 1, 0 };
		assertEquals( 2, EmbeddingsMatcher.match( pointsB.subList( 0, 2 ), two, pointsA.subList( 1, 2 ), one, 2, RADIUS, 1.0, SMIN ).candidates().size() );
		assertEquals( 0, EmbeddingsMatcher.match( pointsB.subList( 1, 2 ), one, pointsA.subList( 0, 1 ), one, 2, RADIUS, 1.0, SMIN ).candidates().size() ); // 12 is outside the radius of 1
	}

	@Test
	public void emptyAndInconsistentInput()
	{
		assertEquals( 0, match( RATIO, SMIN ).candidates().size() );
		a( 1, 0, 0, 0 );
		assertEquals( 0, match( RATIO, SMIN ).candidates().size() );
		assertEquals( 0, match( RATIO, SMIN ).withinRadiusA() );

		b( 11, 1, 0, 0, 0.5f );
		assertEquals( 1, match( RATIO, SMIN ).candidates().size() );
		assertThrows( IllegalArgumentException.class, () -> EmbeddingsMatcher.match( pointsA, new float[ 3 ], pointsB, new float[ 2 ], 2, RADIUS, RATIO, SMIN ) );
		assertThrows( IllegalArgumentException.class, () -> EmbeddingsMatcher.match( pointsA, new float[ 2 ], pointsB, new float[ 2 ], 2, Double.NaN, RATIO, SMIN ) );
	}
}
