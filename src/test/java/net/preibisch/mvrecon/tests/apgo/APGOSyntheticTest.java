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
package net.preibisch.mvrecon.tests.apgo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import mpicbg.models.AffineModel3D;
import mpicbg.models.Model;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.process.interestpointregistration.global.apgo.APGOSolver;
import net.preibisch.mvrecon.process.interestpointregistration.global.apgo.Mat4;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.PointMatchCreator;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

/**
 * Synthetic checks for {@link APGOSolver}.
 *
 * The exact-recovery case is the one that matters: with noise-free pairwise transforms the
 * solver must return the ground-truth per-view transforms to near machine precision. Getting
 * the fixed/moving slot convention or the sign of the incidence matrix wrong still produces
 * a plausible-looking result on real data, but fails this immediately.
 *
 * The grid deliberately includes diagonal neighbors so the pair graph has odd cycles and is
 * not bipartite - the case bigstream's parameterization cannot express.
 */
public class APGOSyntheticTest
{
	/** Ground-truth transforms and the links between them. */
	static class Problem
	{
		final int nx, ny, nz;
		final ArrayList< ViewId > views = new ArrayList<>();
		final HashMap< ViewId, double[][] > truth = new HashMap<>();
		final ArrayList< ViewId[] > links = new ArrayList<>();

		Problem( final int nx, final int ny, final int nz, final boolean diagonals, final Random rnd )
		{
			this.nx = nx;
			this.ny = ny;
			this.nz = nz;

			for ( int i = 0; i < nx * ny * nz; ++i )
			{
				final ViewId v = new ViewId( 0, i );
				views.add( v );
				// view 0 is the gauge - the solver holds the lowest index of each component
				// fixed, so its ground truth has to be the identity
				truth.put( v, i == 0 ? Mat4.identity() : randomAffine( rnd, 0.04, 20 ) );
			}

			for ( int z = 0; z < nz; ++z )
				for ( int y = 0; y < ny; ++y )
					for ( int x = 0; x < nx; ++x )
					{
						link( x, y, z, 1, 0, 0 );
						link( x, y, z, 0, 1, 0 );
						link( x, y, z, 0, 0, 1 );

						if ( diagonals )
						{
							// odd cycles: a diagonal closes a triangle with two face links
							link( x, y, z, 1, 1, 0 );
							link( x, y, z, 1, 0, 1 );
						}
					}
		}

		private void link( final int x, final int y, final int z, final int dx, final int dy, final int dz )
		{
			final int x2 = x + dx, y2 = y + dy, z2 = z + dz;
			if ( x2 >= nx || y2 >= ny || z2 >= nz )
				return;
			links.add( new ViewId[] { views.get( idx( x, y, z ) ), views.get( idx( x2, y2, z2 ) ) } );
		}

		private int idx( final int x, final int y, final int z ) { return ( z * ny + y ) * nx + x; }
	}

	/**
	 * Builds the point matches a solver sees. For a link (a,b) the matches carry
	 * <code>p1</code> in a's frame and <code>p2</code> in b's, and the ground truth satisfies
	 * <code>G_a p_a = G_b p_b</code>, i.e. <code>p_b = G_b&#8315;&#185; G_a p_a</code>.
	 */
	static class SyntheticPointMatchCreator implements PointMatchCreator
	{
		final Problem problem;
		final int pointsPerLink;
		final double noise;
		final Random rnd;

		SyntheticPointMatchCreator( final Problem problem, final int pointsPerLink, final double noise, final Random rnd )
		{
			this.problem = problem;
			this.pointsPerLink = pointsPerLink;
			this.noise = noise;
			this.rnd = rnd;
		}

		@Override
		public HashSet< ViewId > getAllViews()
		{
			return new HashSet<>( problem.views );
		}

		@Override
		public < M extends Model< M > > void assignWeights(
				final HashMap< ViewId, Tile< M > > tileMap,
				final ArrayList< Group< ViewId > > groups,
				final Collection< ViewId > fixedViews )
		{}

		@Override
		public < M extends Model< M > > void assignPointMatches(
				final HashMap< ViewId, Tile< M > > tileMap,
				final ArrayList< Group< ViewId > > groups,
				final Collection< ViewId > fixedViews )
		{
			for ( final ViewId[] link : problem.links )
			{
				final double[][] ga = problem.truth.get( link[ 0 ] );
				final double[][] gb = problem.truth.get( link[ 1 ] );
				final double[][] t = Mat4.mult( Mat4.inverse( gb ), ga );

				final ArrayList< PointMatch > matches = new ArrayList<>();
				for ( int i = 0; i < pointsPerLink; ++i )
				{
					final double[] pa = { rnd.nextDouble() * 200, rnd.nextDouble() * 200, rnd.nextDouble() * 200 };
					final double[] pb = apply( t, pa );

					for ( int d = 0; d < 3; ++d )
						pb[ d ] += noise * rnd.nextGaussian();

					matches.add( new PointMatch( new Point( pa ), new Point( pb ), 1.0 ) );
				}

				final Tile< ? > ta = tileMap.get( link[ 0 ] );
				final Tile< ? > tb = tileMap.get( link[ 1 ] );

				ta.addMatches( matches );
				tb.addMatches( PointMatch.flip( matches ) );
				ta.addConnectedTile( tb );
				tb.addConnectedTile( ta );
			}
		}
	}

	static double[][] randomAffine( final Random rnd, final double linear, final double translation )
	{
		final double[][] m = Mat4.identity();
		for ( int r = 0; r < 3; ++r )
		{
			for ( int c = 0; c < 3; ++c )
				m[ r ][ c ] += linear * ( rnd.nextDouble() - 0.5 );
			m[ r ][ 3 ] = translation * ( rnd.nextDouble() - 0.5 );
		}
		return m;
	}

	static double[] apply( final double[][] m, final double[] p )
	{
		final double[] out = new double[ 3 ];
		for ( int r = 0; r < 3; ++r )
			out[ r ] = m[ r ][ 0 ] * p[ 0 ] + m[ r ][ 1 ] * p[ 1 ] + m[ r ][ 2 ] * p[ 2 ] + m[ r ][ 3 ];
		return out;
	}

	/**
	 * How far, in world units, the recovered transform moves a point away from where the
	 * ground truth puts it - taken over the eight corners of the 200-unit cube the test
	 * points live in. Comparing matrix entries directly is meaningless here because the
	 * linear terms are ~0.02 while the translations are ~10.
	 */
	static double maxDisplacement( final Problem problem, final HashMap< ViewId, Tile< AffineModel3D > > tiles )
	{
		double worst = 0;

		for ( final ViewId v : problem.views )
		{
			final double[][] recovered = new double[ 3 ][ 4 ];
			tiles.get( v ).getModel().toMatrix( recovered );
			final double[][] expected = problem.truth.get( v );

			for ( int corner = 0; corner < 8; ++corner )
			{
				final double[] p = {
						( corner & 1 ) * 200.0,
						( corner >> 1 & 1 ) * 200.0,
						( corner >> 2 & 1 ) * 200.0 };

				final double[] a = apply( recovered, p );
				final double[] b = apply( expected, p );

				worst = Math.max( worst, Math.sqrt(
						( a[ 0 ] - b[ 0 ] ) * ( a[ 0 ] - b[ 0 ] ) +
						( a[ 1 ] - b[ 1 ] ) * ( a[ 1 ] - b[ 1 ] ) +
						( a[ 2 ] - b[ 2 ] ) * ( a[ 2 ] - b[ 2 ] ) ) );
			}
		}

		return worst;
	}

	static void check( final boolean condition, final String message )
	{
		if ( !condition )
			throw new AssertionError( message );
	}

	/**
	 * The dexp^-1 series must equal the true derivative of <code>d -&gt; log( exp(r) exp(d) )</code>.
	 * Checked against central finite differences, because the sign convention of the Bernoulli
	 * series is easy to get backwards and a wrong sign still produces a plausible-looking solve.
	 */
	static void checkDexpInverse()
	{
		final Random rnd = new Random( 5 );
		double worstRelative = 0;
		double worstIdentityCase = 0;

		for ( int trial = 0; trial < 200; ++trial )
		{
			// a residual of realistic size
			final double scale = 0.25 * rnd.nextDouble();
			final double[][] r = new double[ 4 ][ 4 ];
			for ( int i = 0; i < 3; ++i )
				for ( int j = 0; j < 4; ++j )
					r[ i ][ j ] = scale * ( rnd.nextDouble() - 0.5 );

			final double[][] R = Mat4.exp( r );
			final double[][] analytic = APGOSolver.dexpInvMatrix( r, 4 );

			final double h = 1e-6;
			for ( int j = 0; j < 12; ++j )
			{
				final double[][] e = new double[ 4 ][ 4 ];
				e[ j / 4 ][ j % 4 ] = h;

				// central difference of log( R exp(d) ) along basis direction j
				final double[][] plus = Mat4.log( Mat4.mult( R, Mat4.exp( e ) ) );
				final double[][] minus = Mat4.log( Mat4.mult( R, Mat4.exp( Mat4.scale( e, -1 ) ) ) );

				for ( int i = 0; i < 12; ++i )
				{
					final double numeric = ( plus[ i / 4 ][ i % 4 ] - minus[ i / 4 ][ i % 4 ] ) / ( 2 * h );
					worstRelative = Math.max( worstRelative, Math.abs( numeric - analytic[ i ][ j ] ) );
				}
			}

			// dexp^-1_{-r}(r) == r, the identity that makes the reference's residual-side dexp a no-op
			final double[] rv = new double[ 12 ];
			for ( int i = 0; i < 12; ++i )
				rv[ i ] = r[ i / 4 ][ i % 4 ];
			for ( int i = 0; i < 12; ++i )
			{
				double acc = 0;
				for ( int j = 0; j < 12; ++j )
					acc += analytic[ i ][ j ] * rv[ j ];
				worstIdentityCase = Math.max( worstIdentityCase, Math.abs( acc - rv[ i ] ) );
			}
		}

		System.out.printf( "dexp^-1 vs finite differences : worst abs error %.3e%n", worstRelative );
		System.out.printf( "dexp^-1_{-r}(r) == r          : worst abs error %.3e%n", worstIdentityCase );

		check( worstRelative < 1e-5, "dexpInvMatrix disagrees with finite differences: " + worstRelative );
		check( worstIdentityCase < 1e-12, "dexp^-1_{-r}(r) != r: " + worstIdentityCase );
	}

	public static void main( final String[] args )
	{
		checkDexpInverse();

		// --- exact recovery, non-bipartite graph -------------------------------------------
		{
			final Random rnd = new Random( 7 );
			final Problem problem = new Problem( 4, 4, 3, true, rnd );

			final APGOSolver.Parameters params = new APGOSolver.Parameters();
			params.weighting = APGOSolver.Weighting.UNIFORM;
			params.verbose = false;

			final APGOSolver.Result result = APGOSolver.compute(
					new SyntheticPointMatchCreator( problem, 30, 0.0, rnd ), new ArrayList<>(), new ArrayList<>(), params );

			final double worst = maxDisplacement( problem, result.tiles );

			System.out.printf( "exact recovery : %d views, %d links, %d component(s), worst displacement %.3e%n",
					result.numTiles, result.numPairs, result.numComponents, worst );
			System.out.printf( "                 algebra residual %.3e -> %.3e over %d iterations%n",
					result.residuals.get( 0 )[ 1 ],
					result.residuals.get( result.residuals.size() - 1 )[ 1 ],
					result.residuals.size() );

			check( result.numComponents == 1, "grid should be one connected component" );
			check( worst < 1e-9, "exact recovery failed, worst displacement " + worst );
		}

		// --- exact recovery through the information-matrix (block) path ---------------------
		{
			final Random rnd = new Random( 7 );
			final Problem problem = new Problem( 4, 4, 3, true, rnd );

			final APGOSolver.Parameters params = new APGOSolver.Parameters();
			params.weighting = APGOSolver.Weighting.INFORMATION;
			params.verbose = false;

			final APGOSolver.Result result = APGOSolver.compute(
					new SyntheticPointMatchCreator( problem, 30, 0.0, rnd ), new ArrayList<>(), new ArrayList<>(), params );

			final double worst = maxDisplacement( problem, result.tiles );
			System.out.printf( "exact, block path : worst displacement %.3e, algebra residual %.3e -> %.3e%n",
					worst, result.residuals.get( 0 )[ 1 ], result.residuals.get( result.residuals.size() - 1 )[ 1 ] );

			// the block Laplacian is only semi-definite and carries a Tikhonov prior, so this
			// cannot hit 1e-13 like the scalar path - but it must still be exact to well under a
			// pixel, or a sign / transpose is wrong somewhere in A^T Lambda A
			check( worst < 1e-4, "block-path exact recovery failed, worst displacement " + worst );
		}

		// --- noisy, and the residual has to go down ----------------------------------------
		{
			final Random rnd = new Random( 11 );
			final Problem problem = new Problem( 4, 4, 3, true, rnd );

			final APGOSolver.Parameters params = new APGOSolver.Parameters();
			params.weighting = APGOSolver.Weighting.UNIFORM;
			params.verbose = false;

			final APGOSolver.Result result = APGOSolver.compute(
					new SyntheticPointMatchCreator( problem, 30, 0.5, rnd ), new ArrayList<>(), new ArrayList<>(), params );

			final List< double[] > residuals = result.residuals;
			final double first = residuals.get( 0 )[ 1 ];
			final double last = residuals.get( residuals.size() - 1 )[ 1 ];

			System.out.printf( "noisy (sigma=0.5): worst displacement %.3e, algebra residual %.4f -> %.4f%n",
					maxDisplacement( problem, result.tiles ), first, last );

			check( last <= first, "Gauss-Newton increased the residual: " + first + " -> " + last );

			// 0.5 units of noise per point, 30 points per link, over a 200-unit field: the
			// measured worst-corner displacement is ~0.7, so 2.0 leaves headroom without
			// being so loose that a real regression slips through
			check( maxDisplacement( problem, result.tiles ) < 2.0, "noisy recovery drifted too far" );
		}

		// --- coplanar correspondences must fall back, not drop the link --------------------
		{
			final Random rnd = new Random( 13 );
			final Problem problem = new Problem( 3, 3, 2, false, rnd );

			final APGOSolver.Parameters params = new APGOSolver.Parameters();
			params.weighting = APGOSolver.Weighting.UNIFORM;
			params.verbose = false;

			final APGOSolver.Result result = APGOSolver.compute(
					new CoplanarPointMatchCreator( problem, 30, rnd ), new ArrayList<>(), new ArrayList<>(), params );

			System.out.printf( "coplanar links   : %d requested, %d rigid fallback, %d translation fallback, %d dropped%n",
					result.numRequestedFits, result.numRigidFallbacks, result.numTranslationFallbacks, result.numDroppedPairs );

			check( result.numDroppedPairs == 0, "coplanar links were dropped instead of falling back" );
			check( result.numRequestedFits == 0, "coplanar points should not admit an affine fit" );
		}

		System.out.println( "OK" );
	}

	/** All correspondences of a link lie on a plane, as split-boundary points do. */
	static class CoplanarPointMatchCreator extends SyntheticPointMatchCreator
	{
		CoplanarPointMatchCreator( final Problem problem, final int pointsPerLink, final Random rnd )
		{
			super( problem, pointsPerLink, 0.0, rnd );
		}

		@Override
		public < M extends Model< M > > void assignPointMatches(
				final HashMap< ViewId, Tile< M > > tileMap,
				final ArrayList< Group< ViewId > > groups,
				final Collection< ViewId > fixedViews )
		{
			for ( final ViewId[] link : problem.links )
			{
				final double[][] t = Mat4.mult(
						Mat4.inverse( problem.truth.get( link[ 1 ] ) ), problem.truth.get( link[ 0 ] ) );

				final ArrayList< PointMatch > matches = new ArrayList<>();
				for ( int i = 0; i < pointsPerLink; ++i )
				{
					final double[] pa = { rnd.nextDouble() * 200, rnd.nextDouble() * 200, 42.0 };
					matches.add( new PointMatch( new Point( pa ), new Point( apply( t, pa ) ), 1.0 ) );
				}

				final Tile< ? > ta = tileMap.get( link[ 0 ] );
				final Tile< ? > tb = tileMap.get( link[ 1 ] );

				ta.addMatches( matches );
				tb.addMatches( PointMatch.flip( matches ) );
				ta.addConnectedTile( tb );
				tb.addConnectedTile( ta );
			}
		}
	}
}
