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
package net.preibisch.mvrecon.process.interestpointregistration.global.apgo;

import java.util.Arrays;

import org.apache.commons.math3.exception.MaxCountExceededException;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.ConjugateGradient;
import org.apache.commons.math3.linear.RealLinearOperator;
import org.apache.commons.math3.linear.RealVector;

import Jama.Matrix;

/**
 * Block version of {@link APGONormalEquations}, for {@link APGOSolver.Weighting#INFORMATION}.
 *
 * The scalar solver weights each link by one number, which forces the same confidence onto all
 * twelve affine parameters. Here each link carries a 12x12 information matrix instead, so the
 * directions a link genuinely measures and the directions it does not are weighted separately.
 * The twelve parameters no longer decouple, so this is one system of <code>12 * numFree</code>
 * unknowns rather than twelve independent scalar ones.
 *
 * The system is the block graph Laplacian
 * <pre>
 * (L u)_a = sum over links at a of  Lambda'_ab ( u_a - u_b )
 * </pre>
 * which stays symmetric positive semi-definite, so conjugate gradient still applies. It is only
 * semi-definite: a view whose links carry no information in some direction leaves that direction
 * in the null space. That is the honest answer rather than a defect - but it still has to be
 * resolved, so a small Tikhonov term pulls those directions toward "no change" instead of
 * letting them be driven by noise.
 *
 * Never assembled: at 1678 views this would be 20124 x 20124.
 */
public class APGOBlockNormalEquations
{
	/** Twelve free parameters of a 3x4 affine, vectorized row-major: index = row * 4 + col. */
	public static final int D = 12;

	private final int[] pairA, pairB;

	/** Per link, the 12x12 SPD block. */
	private final double[][][] blocks;

	private final int[] column;
	private final int numFree;

	/** Per free view, the inverse of its accumulated diagonal block. */
	private final double[][][] preconditioner;

	private final double tikhonov;
	private final int cgMaxIterations;
	private final double cgTolerance;

	private double worstRelativeResidual;
	private int notConverged;

	public APGOBlockNormalEquations(
			final int[] pairA,
			final int[] pairB,
			final double[][][] blocks,
			final int[] column,
			final int numFree,
			final double tikhonovRelative,
			final int cgMaxIterations,
			final double cgTolerance )
	{
		this.pairA = pairA;
		this.pairB = pairB;
		this.blocks = blocks;
		this.column = column;
		this.numFree = numFree;
		this.cgMaxIterations = cgMaxIterations;
		this.cgTolerance = cgTolerance;

		// accumulate the diagonal blocks
		final double[][][] diagonal = new double[ numFree ][ D ][ D ];
		for ( int p = 0; p < blocks.length; ++p )
		{
			final int ca = column[ pairA[ p ] ], cb = column[ pairB[ p ] ];
			for ( int i = 0; i < D; ++i )
				for ( int j = 0; j < D; ++j )
				{
					if ( ca >= 0 )
						diagonal[ ca ][ i ][ j ] += blocks[ p ][ i ][ j ];
					if ( cb >= 0 )
						diagonal[ cb ][ i ][ j ] += blocks[ p ][ i ][ j ];
				}
		}

		// scale the Tikhonov term to the problem, so it is a genuinely weak prior
		double meanScale = 0;
		for ( int v = 0; v < numFree; ++v )
		{
			double trace = 0;
			for ( int i = 0; i < D; ++i )
				trace += diagonal[ v ][ i ][ i ];
			meanScale += trace / D;
		}
		this.tikhonov = numFree > 0 ? tikhonovRelative * meanScale / numFree : 0;

		this.preconditioner = new double[ numFree ][][];
		for ( int v = 0; v < numFree; ++v )
		{
			for ( int i = 0; i < D; ++i )
				diagonal[ v ][ i ][ i ] += tikhonov;
			preconditioner[ v ] = invertOrIdentity( diagonal[ v ] );
		}
	}

	private static double[][] invertOrIdentity( final double[][] m )
	{
		try
		{
			final double[][] inv = new Matrix( m ).inverse().getArray();
			for ( final double[] row : inv )
				for ( final double v : row )
					if ( !Double.isFinite( v ) )
						return identity();
			return inv;
		}
		catch ( final RuntimeException singular )
		{
			return identity();
		}
	}

	private static double[][] identity()
	{
		final double[][] m = new double[ D ][ D ];
		for ( int i = 0; i < D; ++i )
			m[ i ][ i ] = 1;
		return m;
	}

	public double worstRelativeResidual() { return worstRelativeResidual; }

	public int numNotConverged() { return notConverged; }

	public int numFree() { return numFree; }

	/** y = L x, with L the block Laplacian plus the Tikhonov term. */
	private void applyLaplacian( final double[] x, final double[] y )
	{
		Arrays.fill( y, 0 );

		final double[] z = new double[ D ], t = new double[ D ];

		for ( int p = 0; p < blocks.length; ++p )
		{
			final int ca = column[ pairA[ p ] ], cb = column[ pairB[ p ] ];
			if ( ca < 0 && cb < 0 )
				continue;

			for ( int i = 0; i < D; ++i )
				z[ i ] = ( ca >= 0 ? x[ ca * D + i ] : 0 ) - ( cb >= 0 ? x[ cb * D + i ] : 0 );

			final double[][] block = blocks[ p ];
			for ( int i = 0; i < D; ++i )
			{
				double s = 0;
				for ( int j = 0; j < D; ++j )
					s += block[ i ][ j ] * z[ j ];
				t[ i ] = s;
			}

			if ( ca >= 0 )
				for ( int i = 0; i < D; ++i )
					y[ ca * D + i ] += t[ i ];
			if ( cb >= 0 )
				for ( int i = 0; i < D; ++i )
					y[ cb * D + i ] -= t[ i ];
		}

		for ( int i = 0; i < y.length; ++i )
			y[ i ] += tikhonov * x[ i ];
	}

	/**
	 * @param rhs length <code>12 * numFree</code>, already accumulated per view
	 * @return the update, same layout
	 */
	public double[] solve( final double[] rhs )
	{
		if ( numFree == 0 )
			return new double[ 0 ];

		final int n = numFree * D;

		final RealLinearOperator laplacian = new RealLinearOperator()
		{
			@Override public int getRowDimension() { return n; }
			@Override public int getColumnDimension() { return n; }

			@Override public RealVector operate( final RealVector x )
			{
				final double[] y = new double[ n ];
				applyLaplacian( ( ( ArrayRealVector ) x ).getDataRef(), y );
				return new ArrayRealVector( y, false );
			}
		};

		final RealLinearOperator blockJacobi = new RealLinearOperator()
		{
			@Override public int getRowDimension() { return n; }
			@Override public int getColumnDimension() { return n; }

			@Override public RealVector operate( final RealVector r )
			{
				final double[] in = ( ( ArrayRealVector ) r ).getDataRef();
				final double[] y = new double[ n ];
				for ( int v = 0; v < numFree; ++v )
				{
					final double[][] p = preconditioner[ v ];
					for ( int i = 0; i < D; ++i )
					{
						double s = 0;
						for ( int j = 0; j < D; ++j )
							s += p[ i ][ j ] * in[ v * D + j ];
						y[ v * D + i ] = s;
					}
				}
				return new ArrayRealVector( y, false );
			}
		};

		final double[] x = new double[ n ];

		try
		{
			new ConjugateGradient( cgMaxIterations, cgTolerance, false )
					.solveInPlace( laplacian, blockJacobi, new ArrayRealVector( rhs, false ),
							new ArrayRealVector( x, false ) );
		}
		catch ( final MaxCountExceededException e )
		{
			++notConverged;
		}

		final double[] check = new double[ n ];
		applyLaplacian( x, check );
		double num = 0, den = 0;
		for ( int i = 0; i < n; ++i )
		{
			num += ( check[ i ] - rhs[ i ] ) * ( check[ i ] - rhs[ i ] );
			den += rhs[ i ] * rhs[ i ];
		}
		worstRelativeResidual = Math.max( worstRelativeResidual, den > 0 ? Math.sqrt( num / den ) : 0 );

		return x;
	}
}
