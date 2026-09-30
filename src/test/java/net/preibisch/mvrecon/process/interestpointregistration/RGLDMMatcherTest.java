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
 * search radius, for every neighbor / redundancy combination in use.
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

	@Test
	public void fastSearchesEqualLegacy()
	{
		final Random rnd = new Random( 42 );
		final double box = 2000;

		// B: 90 % of A shifted and jittered, plus unrelated points
		final ArrayList< InterestPoint > a = randomCloud( rnd, 500, 0, box );
		final ArrayList< InterestPoint > b = new ArrayList<>();
		int id = 100000;
		for ( final InterestPoint p : a )
			if ( rnd.nextDouble() < 0.9 )
				b.add( new InterestPoint( id++, new double[] {
						p.getL()[ 0 ] + 31.5 + rnd.nextGaussian() * 0.3,
						p.getL()[ 1 ] - 12.25 + rnd.nextGaussian() * 0.3,
						p.getL()[ 2 ] + 7.75 + rnd.nextGaussian() * 0.3 } ) );
		b.addAll( randomCloud( rnd, 250, id, box ) );

		final RGLDMMatcher< InterestPoint > matcher = new RGLDMMatcher<>();
		final int[][] configs = { { 3, 0 }, { 3, 1 }, { 3, 2 }, { 4, 1 }, { 5, 1 } };
		final double[] radii = { -1, 150, 600, 5000 }; // -1: no radius limit; 150 admits a handful of B points, 5000 all of them

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
}
