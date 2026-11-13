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
package net.preibisch.mvrecon.process.interestpointregistration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.RGLDMMatcher;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.DescriptorSearch;

/**
 * The fast RGLDM search paths must return exactly the candidate set of the legacy double loop, with and without a
 * search radius, for every neighbor / redundancy combination in use. With a search radius, the only B point inside the
 * radius of an A point is a candidate (no second-best, no ratio test).
 */
public class RGLDMMatcherTest
{
	private static ArrayList< InterestPoint > randomCloud( final Random rnd, final int n, final int firstId, final double box )
	{
		final ArrayList< InterestPoint > points = new ArrayList<>();
		for ( int i = 0; i < n; ++i )
			points.add( new InterestPoint( firstId + i, new double[] { rnd.nextDouble() * box, rnd.nextDouble() * box, rnd.nextDouble() * box } ) );
		return points;
	}

	private static Set< String > pairs( final List< PointMatchGeneric< InterestPoint > > matches )
	{
		final Set< String > s = new HashSet<>();
		for ( final PointMatchGeneric< InterestPoint > m : matches )
			s.add( m.getPoint1().getId() + ">" + m.getPoint2().getId() );
		return s;
	}

	/** B: 90 % of A shifted by about 35 px and jittered, plus 250 unrelated points; about 125 px between neighboring points */
	private static ArrayList< InterestPoint > shiftedCloud( final Random rnd, final List< InterestPoint > a, final double box )
	{
		final ArrayList< InterestPoint > b = new ArrayList<>();
		int id = 100000;
		for ( final InterestPoint p : a )
			if ( rnd.nextDouble() < 0.9 )
				b.add( new InterestPoint( id++, new double[] {
						p.getL()[ 0 ] + 31.5 + rnd.nextGaussian() * 0.3,
						p.getL()[ 1 ] - 12.25 + rnd.nextGaussian() * 0.3,
						p.getL()[ 2 ] + 7.75 + rnd.nextGaussian() * 0.3 } ) );
		b.addAll( randomCloud( rnd, 250, id, box ) );
		return b;
	}

	@Test
	public void fastSearchesEqualLegacy()
	{
		final Random rnd = new Random( 42 );
		final double box = 2000;

		final ArrayList< InterestPoint > a = randomCloud( rnd, 500, 0, box );
		final ArrayList< InterestPoint > b = shiftedCloud( rnd, a, box );

		final RGLDMMatcher< InterestPoint > matcher = new RGLDMMatcher<>();
		final int[][] configs = { { 3, 0 }, { 3, 1 }, { 3, 2 }, { 4, 1 }, { 5, 1 } };
		// -1: no radius limit; 60 admits a single B point for most A points, 150 a handful, 5000 all of them
		final double[] radii = { -1, 60, 150, 600, 5000 };

		for ( final int[] cfg : configs )
			for ( final double radius : radii )
			{
				final boolean limit = radius > 0;
				final Set< String > legacy = pairs( matcher.extractCorrespondenceCandidates( a, b, cfg[ 0 ], cfg[ 1 ], 3, Double.MAX_VALUE, limit, radius, DescriptorSearch.LEGACY ) );
				final String what = "neighbors=" + cfg[ 0 ] + " redundancy=" + cfg[ 1 ] + " radius=" + radius;

				assertTrue( legacy.size() > ( limit ? 5 : 30 ), what + ": legacy found only " + legacy.size() + " candidates, test data is broken" );
				for ( final DescriptorSearch s : new DescriptorSearch[] { DescriptorSearch.FLAT_KDTREE, DescriptorSearch.BLOCKED_BRUTE_FORCE, DescriptorSearch.AUTO } )
					assertEquals( legacy, pairs( matcher.extractCorrespondenceCandidates( a, b, cfg[ 0 ], cfg[ 1 ], 3, Double.MAX_VALUE, limit, radius, s ) ), what + " " + s );
			}
	}

	/**
	 * A radius smaller than the spacing of the points: most A points have one B point inside it, usually the true partner.
	 * It is a candidate although the ratio test has no second-best to compare it with (such A points were dropped until
	 * 2026-11-13, which left almost no candidates for a small radius).
	 */
	@Test
	public void onlyPointInRadiusIsCandidate()
	{
		final Random rnd = new Random( 7 );
		final double box = 2000, radius = 60;

		final ArrayList< InterestPoint > a = randomCloud( rnd, 500, 0, box );
		final ArrayList< InterestPoint > b = shiftedCloud( rnd, a, box );

		// what the rule says, by brute force: A points with exactly one B point inside the radius, and those with two or more
		final Set< String > lonePairs = new HashSet<>();
		final Set< Integer > severalInRadius = new HashSet<>();
		for ( final InterestPoint pa : a )
		{
			final ArrayList< InterestPoint > inRadius = new ArrayList<>();
			for ( final InterestPoint pb : b )
			{
				double squareDistance = 0;
				for ( int d = 0; d < 3; ++d )
					squareDistance += ( pa.getL()[ d ] - pb.getL()[ d ] ) * ( pa.getL()[ d ] - pb.getL()[ d ] );
				if ( squareDistance <= radius * radius )
					inRadius.add( pb );
			}
			if ( inRadius.size() == 1 )
				lonePairs.add( pa.getId() + ">" + inRadius.get( 0 ).getId() );
			else if ( inRadius.size() > 1 )
				severalInRadius.add( pa.getId() );
		}
		assertTrue( lonePairs.size() > 300 && !severalInRadius.isEmpty(), "test data is broken: " + lonePairs.size() + " A points with one B point in the radius, " + severalInRadius.size() + " with several" );

		final RGLDMMatcher< InterestPoint > matcher = new RGLDMMatcher<>();
		for ( final DescriptorSearch s : DescriptorSearch.values() )
		{
			// every lone pair is a candidate, and every other candidate is from an A point with several B points in the radius (ratio test)
			final Set< String > candidates = pairs( matcher.extractCorrespondenceCandidates( a, b, 3, 1, 3, Double.MAX_VALUE, true, radius, s ) );
			assertTrue( candidates.containsAll( lonePairs ), s + ": " + lonePairs.size() + " lone pairs, " + candidates.size() + " candidates" );
			for ( final String candidate : candidates )
				assertTrue( lonePairs.contains( candidate ) || severalInRadius.contains( Integer.parseInt( candidate.substring( 0, candidate.indexOf( '>' ) ) ) ), s + ": unexpected candidate " + candidate );

			// the absolute threshold on the descriptor distance still applies to a lone pair
			assertTrue( matcher.extractCorrespondenceCandidates( a, b, 3, 1, 3, 0, true, radius, s ).isEmpty(), s + ": candidates despite a difference threshold of 0" );
		}
	}
}
