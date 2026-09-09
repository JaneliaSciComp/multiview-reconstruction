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

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Random;

import mpicbg.models.AffineModel3D;
import mpicbg.models.Tile;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.process.interestpointregistration.global.apgo.APGOSolver;
import net.preibisch.mvrecon.process.interestpointregistration.global.apgo.Mat4;
import net.preibisch.mvrecon.tests.apgo.APGOSyntheticTest.Problem;
import net.preibisch.mvrecon.tests.apgo.APGOSyntheticTest.SyntheticPointMatchCreator;

/**
 * Generates a problem that bigstream's <code>find_tile_transforms</code> can also solve,
 * runs the Java solver on it, and writes everything to disk for
 * <code>apgo_reference.py</code> to cross-check.
 *
 * <h2>Why the grid is a checkerboard with face-only neighbors</h2>
 *
 * bigstream parameterizes each link as <code>T &#8776; M_mov &#183; M_fix</code> and inverts
 * the fixed tiles at the end, which only closes when every tile is consistently on one side
 * of every link it participates in. That is a 2-coloring, so the graph has to be bipartite.
 * A face-neighbor grid is; add diagonals and it is not. See {@link APGOSolver} for how the
 * general form avoids the restriction. The comparison therefore runs on the subset of
 * problems bigstream can express.
 *
 * <h2>Why the data is noise free</h2>
 *
 * The two parameterizations consume genuinely different observations of the same geometry
 * (<code>G_mov &#183; G_fix&#8315;&#185;</code> versus <code>G_b&#8315;&#185; &#183; G_a</code>),
 * and there is no way to hand both the <em>same</em> perturbed measurement. With exact data
 * both must return the ground truth, which pins down the log/exp, the incidence solve, the
 * Gauss-Newton loop and every sign and slot convention. Behaviour under noise is covered
 * separately, in Java, by {@link APGOSyntheticTest}.
 */
public class APGOReferenceExport
{
	public static void main( final String[] args ) throws IOException
	{
		final Path dir = Paths.get( args.length > 0 ? args[ 0 ] : "." );
		Files.createDirectories( dir );

		final int nx = 4, ny = 4, nz = 3;
		final Random rnd = new Random( 7 );

		// no diagonals: bigstream needs a bipartite graph
		final Problem problem = new Problem( nx, ny, nz, false, rnd );

		// --- ground truth -------------------------------------------------------------------

		try ( PrintWriter out = new PrintWriter( Files.newBufferedWriter( dir.resolve( "truth.txt" ) ) ) )
		{
			for ( final ViewId v : problem.views )
				out.println( format( problem.truth.get( v ) ) );
		}

		// --- the reference's view of the same problem -----------------------------------------

		try ( PrintWriter out = new PrintWriter( Files.newBufferedWriter( dir.resolve( "bigstream_input.txt" ) ) ) )
		{
			for ( final ViewId[] link : problem.links )
			{
				final int i = link[ 0 ].getViewSetupId();
				final int j = link[ 1 ].getViewSetupId();

				// checkerboard, exactly as align_all_neighbors assigns fix/mov
				final int fix = isFixed( i, nx, ny ) ? i : j;
				final int mov = fix == i ? j : i;

				if ( isFixed( fix, nx, ny ) == isFixed( mov, nx, ny ) )
					throw new IllegalStateException( "face neighbors must have opposite colors" );

				// bigstream solves for M with T = M_mov * M_fix, and reports M_mov for movers
				// and M_fix^-1 for fixers - i.e. it reports our G either way. So the observation
				// it needs is G_mov * G_fix^-1.
				final double[][] t = Mat4.mult(
						problem.truth.get( problem.views.get( mov ) ),
						Mat4.inverse( problem.truth.get( problem.views.get( fix ) ) ) );

				out.println( fix + " " + mov + " 1.0 " + format( t ) );
			}
		}

		// --- what the Java solver makes of it -------------------------------------------------

		final APGOSolver.Parameters params = new APGOSolver.Parameters();
		params.weighting = APGOSolver.Weighting.UNIFORM;
		params.verbose = false;

		final APGOSolver.Result result = APGOSolver.compute(
				new SyntheticPointMatchCreator( problem, 30, 0.0, rnd ),
				new ArrayList<>(), new ArrayList<>(), params );

		try ( PrintWriter out = new PrintWriter( Files.newBufferedWriter( dir.resolve( "java_output.txt" ) ) ) )
		{
			for ( final ViewId v : problem.views )
			{
				final Tile< AffineModel3D > tile = result.tiles.get( v );
				final double[][] m3x4 = new double[ 3 ][ 4 ];
				tile.getModel().toMatrix( m3x4 );

				final double[][] m = Mat4.identity();
				for ( int r = 0; r < 3; ++r )
					System.arraycopy( m3x4[ r ], 0, m[ r ], 0, 4 );

				out.println( format( m ) );
			}
		}

		System.out.println( "wrote " + problem.views.size() + " tiles and " + problem.links.size()
				+ " links to " + dir.toAbsolutePath() );
		System.out.println( "java worst displacement vs truth: "
				+ APGOSyntheticTest.maxDisplacement( problem, result.tiles ) );
	}

	/** Checkerboard colour of a raster index, matching <code>Problem.idx</code>. */
	private static boolean isFixed( final int index, final int nx, final int ny )
	{
		final int x = index % nx;
		final int y = ( index / nx ) % ny;
		final int z = index / ( nx * ny );
		return ( x + y + z ) % 2 == 0;
	}

	private static String format( final double[][] m )
	{
		final StringBuilder sb = new StringBuilder();
		for ( int r = 0; r < 4; ++r )
			for ( int c = 0; c < 4; ++c )
			{
				if ( sb.length() > 0 )
					sb.append( ' ' );
				sb.append( Double.toString( m[ r ][ c ] ) );
			}
		return sb.toString();
	}
}
