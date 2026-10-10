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

import net.imglib2.KDTree;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.geometrichashing.GeometricHasher;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.LocalCoordinateSystemPointDescriptor;

/**
 * Geometric hashing must return exactly the candidates of its definition for every redundancy: an A descriptor yields a
 * candidate when its nearest B descriptor is ratioOfDistance times closer than the nearest descriptor of a different B
 * point. The reference evaluates that definition by brute force over all descriptors.
 */
public class GeometricHasherTest
{
	private static final int ID_OFFSET_B = 100000;

	private static ArrayList< InterestPoint > randomCloud( final Random rnd, final int n, final int firstId, final double box )
	{
		final ArrayList< InterestPoint > points = new ArrayList<>();
		for ( int i = 0; i < n; ++i )
			points.add( new InterestPoint( firstId + i, new double[] { rnd.nextDouble() * box, rnd.nextDouble() * box, rnd.nextDouble() * box } ) );
		return points;
	}

	/** rotates about z, then about x, translates and jitters */
	private static double[] transform( final double[] p, final double aboutZ, final double aboutX, final double[] shift, final Random rnd, final double jitter )
	{
		final double x1 = Math.cos( aboutZ ) * p[ 0 ] - Math.sin( aboutZ ) * p[ 1 ];
		final double y1 = Math.sin( aboutZ ) * p[ 0 ] + Math.cos( aboutZ ) * p[ 1 ];
		final double z1 = p[ 2 ];
		final double y2 = Math.cos( aboutX ) * y1 - Math.sin( aboutX ) * z1;
		final double z2 = Math.sin( aboutX ) * y1 + Math.cos( aboutX ) * z1;
		return new double[] {
				x1 + shift[ 0 ] + rnd.nextGaussian() * jitter,
				y2 + shift[ 1 ] + rnd.nextGaussian() * jitter,
				z2 + shift[ 2 ] + rnd.nextGaussian() * jitter };
	}

	/** A, and B = 90 % of A rotated, shifted and jittered (id + ID_OFFSET_B) plus unrelated points */
	private static ArrayList< InterestPoint >[] clouds( final Random rnd, final double jitter )
	{
		final double box = 2000;
		final ArrayList< InterestPoint > a = randomCloud( rnd, 500, 0, box );
		final ArrayList< InterestPoint > b = new ArrayList<>();
		for ( final InterestPoint p : a )
			if ( rnd.nextDouble() < 0.9 )
				b.add( new InterestPoint( ID_OFFSET_B + p.getId(), transform( p.getL(), 0.6, 0.35, new double[] { 31.5, -12.25, 7.75 }, rnd, jitter ) ) );
		b.addAll( randomCloud( rnd, 250, 2 * ID_OFFSET_B, box ) );
		@SuppressWarnings( "unchecked" )
		final ArrayList< InterestPoint >[] result = new ArrayList[] { a, b };
		return result;
	}

	private static Set< String > pairs( final List< PointMatchGeneric< InterestPoint > > matches )
	{
		final Set< String > s = new HashSet<>();
		for ( final PointMatchGeneric< InterestPoint > m : matches )
			s.add( m.getPoint1().getId() + ">" + m.getPoint2().getId() );
		return s;
	}

	private static ArrayList< LocalCoordinateSystemPointDescriptor< InterestPoint > > descriptors( final ArrayList< InterestPoint > points, final int redundancy )
	{
		return GeometricHasher.createLocalCoordinateSystemPointDescriptors( new KDTree<>( points, points ), points, redundancy, false );
	}

	/** the definition, by brute force: per A descriptor the nearest B descriptor and the nearest one of another B point */
	private static Set< String > reference( final ArrayList< InterestPoint > a, final ArrayList< InterestPoint > b, final int redundancy, final double ratio )
	{
		final ArrayList< LocalCoordinateSystemPointDescriptor< InterestPoint > > descsA = descriptors( a, redundancy );
		final ArrayList< LocalCoordinateSystemPointDescriptor< InterestPoint > > descsB = descriptors( b, redundancy );
		final double[] dist = new double[ descsB.size() ];
		final Set< String > candidates = new HashSet<>();

		for ( final LocalCoordinateSystemPointDescriptor< InterestPoint > dA : descsA )
		{
			int nearest = -1;
			for ( int i = 0; i < descsB.size(); ++i )
			{
				dist[ i ] = dA.descriptorDistance( descsB.get( i ) );
				if ( nearest < 0 || dist[ i ] < dist[ nearest ] )
					nearest = i;
			}
			final InterestPoint pointB = descsB.get( nearest ).getBasisPoint();
			double second = Double.MAX_VALUE;
			for ( int i = 0; i < descsB.size(); ++i )
				if ( descsB.get( i ).getBasisPoint() != pointB )
					second = Math.min( second, dist[ i ] );

			if ( second < Double.MAX_VALUE && dist[ nearest ] * ratio <= second )
				candidates.add( dA.getBasisPoint().getId() + ">" + pointB.getId() );
		}
		return candidates;
	}

	@Test
	public void matchesTheDefinitionForEveryRedundancy()
	{
		final GeometricHasher< InterestPoint > hasher = new GeometricHasher<>();

		// 0.3 px: every true match passes; 5 px: the ratio test decides, and so does the choice of the second-best
		for ( final double jitter : new double[] { 0.3, 5 } )
		{
			final ArrayList< InterestPoint >[] ab = clouds( new Random( 42 ), jitter );
			for ( final int redundancy : new int[] { 0, 1, 2 } )
				for ( final double ratio : new double[] { 3, 10 } )
				{
					final String what = "jitter=" + jitter + " redundancy=" + redundancy + " ratio=" + ratio;
					final Set< String > expected = reference( ab[ 0 ], ab[ 1 ], redundancy, ratio );
					assertTrue( expected.size() > 30, what + ": the reference found only " + expected.size() + " candidates, test data is broken" );
					assertEquals( expected, pairs( hasher.extractCorrespondenceCandidates( ab[ 0 ], ab[ 1 ], Double.MAX_VALUE, redundancy, ratio ) ), what );
				}
		}
	}

	@Test
	public void redundancyAddsTrueMatches()
	{
		final GeometricHasher< InterestPoint > hasher = new GeometricHasher<>();

		for ( final double jitter : new double[] { 0.3, 5 } )
		{
			final ArrayList< InterestPoint >[] ab = clouds( new Random( 42 ), jitter );
			int withoutRedundancy = -1;

			for ( final int redundancy : new int[] { 0, 1, 2 } )
			{
				final List< PointMatchGeneric< InterestPoint > > candidates = hasher.extractCorrespondenceCandidates( ab[ 0 ], ab[ 1 ], Double.MAX_VALUE, redundancy, 10 );
				int correct = 0;
				for ( final PointMatchGeneric< InterestPoint > m : candidates )
					if ( m.getPoint2().getId() == m.getPoint1().getId() + ID_OFFSET_B )
						++correct;
				final String what = "jitter=" + jitter + " redundancy=" + redundancy;
				System.out.println( "geometric hashing " + what + ": " + candidates.size() + " candidates, " + correct + " correct" );

				assertTrue( correct >= 0.9 * candidates.size(), what + ": only " + correct + " of " + candidates.size() + " candidates are correct" );
				if ( redundancy == 0 )
					withoutRedundancy = correct;
				else
					assertTrue( correct > withoutRedundancy, what + ": " + correct + " correct candidates, no more than the " + withoutRedundancy + " without redundancy" );
			}
		}
	}
}
