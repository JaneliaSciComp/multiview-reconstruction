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

/**
 * Weighted least squares against the signed incidence matrix of the pair graph.
 *
 * Both stages of {@link APGOSolver} reduce to the same system. Row <code>p</code> of
 * <code>A</code>, for a pair <code>(a,b)</code> with weight <code>w</code>, is
 * <code>+w</code> at column <code>a</code> and <code>-w</code> at column <code>b</code>;
 * gauge-fixed views have no column. Each of the 12 affine parameters is an independent
 * right-hand side over that one matrix, so a single instance is built once per solve and
 * reused.
 *
 * <code>A^T A</code> is the weighted graph Laplacian restricted to the free views, which
 * is symmetric positive definite once one view per connected component is fixed. That
 * makes preconditioned conjugate gradient the natural solver, and it never needs the
 * matrix itself - only the mat-vec below. At 1678 views the assembled Gauss-Newton
 * matrix of the reference implementation would be 12N x 20124.
 */
public class APGONormalEquations
{
	private final int[] pairA, pairB;
	private final double[] w;

	/** view index -> column index, or -1 when gauge-fixed or unconstrained. */
	private final int[] column;

	private final int numFree;

	/** Diagonal of A^T A, for Jacobi preconditioning. */
	private final double[] diagonal;

	private final int cgMaxIterations;
	private final double cgTolerance;

	public APGONormalEquations(
			final int[] pairA,
			final int[] pairB,
			final double[] w,
			final int[] column,
			final int numFree,
			final int cgMaxIterations,
			final double cgTolerance )
	{
		this.pairA = pairA;
		this.pairB = pairB;
		this.w = w;
		this.column = column;
		this.numFree = numFree;
		this.cgMaxIterations = cgMaxIterations;
		this.cgTolerance = cgTolerance;

		this.diagonal = new double[ numFree ];
		for ( int p = 0; p < w.length; ++p )
		{
			final double ww = w[ p ] * w[ p ];
			final int ca = column[ pairA[ p ] ];
			final int cb = column[ pairB[ p ] ];
			if ( ca >= 0 )
				diagonal[ ca ] += ww;
			if ( cb >= 0 )
				diagonal[ cb ] += ww;
		}

		// an isolated free column would make the operator singular; guard the preconditioner
		for ( int i = 0; i < numFree; ++i )
			if ( diagonal[ i ] == 0 )
				diagonal[ i ] = 1;
	}

	public int numFree() { return numFree; }

	/** y = A^T A x */
	private void applyNormal( final double[] x, final double[] y )
	{
		Arrays.fill( y, 0 );

		for ( int p = 0; p < w.length; ++p )
		{
			final int ca = column[ pairA[ p ] ];
			final int cb = column[ pairB[ p ] ];

			final double d = w[ p ] * ( ( ca >= 0 ? x[ ca ] : 0 ) - ( cb >= 0 ? x[ cb ] : 0 ) );

			if ( ca >= 0 )
				y[ ca ] += w[ p ] * d;
			if ( cb >= 0 )
				y[ cb ] -= w[ p ] * d;
		}
	}

	/** y = A^T b */
	private double[] applyTranspose( final double[] obs )
	{
		final double[] y = new double[ numFree ];

		for ( int p = 0; p < obs.length; ++p )
		{
			final double v = w[ p ] * obs[ p ];
			final int ca = column[ pairA[ p ] ];
			final int cb = column[ pairB[ p ] ];

			if ( ca >= 0 )
				y[ ca ] += v;
			if ( cb >= 0 )
				y[ cb ] -= v;
		}

		return y;
	}

	/**
	 * Solve for one right-hand side. <code>obs</code> holds the unweighted per-pair
	 * observation; the weights live in the operator, matching the reference, which scales
	 * both the constraint rows and the right-hand side by the link confidence.
	 *
	 * @return one value per free column
	 */
	public double[] solve( final double[] obs )
	{
		if ( numFree == 0 )
			return new double[ 0 ];

		final RealLinearOperator ata = new RealLinearOperator()
		{
			@Override public int getRowDimension() { return numFree; }
			@Override public int getColumnDimension() { return numFree; }

			@Override public RealVector operate( final RealVector x )
			{
				final double[] y = new double[ numFree ];
				applyNormal( ( ( ArrayRealVector ) x ).getDataRef(), y );
				return new ArrayRealVector( y, false );
			}
		};

		final RealLinearOperator jacobi = new RealLinearOperator()
		{
			@Override public int getRowDimension() { return numFree; }
			@Override public int getColumnDimension() { return numFree; }

			@Override public RealVector operate( final RealVector r )
			{
				final double[] in = ( ( ArrayRealVector ) r ).getDataRef();
				final double[] y = new double[ numFree ];
				for ( int i = 0; i < numFree; ++i )
					y[ i ] = in[ i ] / diagonal[ i ];
				return new ArrayRealVector( y, false );
			}
		};

		final double[] x = new double[ numFree ];
		final ArrayRealVector solution = new ArrayRealVector( x, false );
		final double[] rhs = applyTranspose( obs );

		try
		{
			new ConjugateGradient( cgMaxIterations, cgTolerance, false )
					.solveInPlace( ata, jacobi, new ArrayRealVector( rhs, false ), solution );
		}
		catch ( final MaxCountExceededException e )
		{
			// CG updates the iterate in place, so x still holds the best estimate reached
			++notConverged;
		}

		// ||A^T A x - A^T b|| / ||A^T b||, so the caller can tell a converged solve from a
		// stalled one. Conjugate gradient on the normal equations squares the condition
		// number, and with link weights spanning several orders of magnitude that can be
		// enough to stop it converging - which shows up here rather than as an exception.
		final double[] check = new double[ numFree ];
		applyNormal( x, check );
		double num = 0, den = 0;
		for ( int i = 0; i < numFree; ++i )
		{
			num += ( check[ i ] - rhs[ i ] ) * ( check[ i ] - rhs[ i ] );
			den += rhs[ i ] * rhs[ i ];
		}
		lastRelativeResidual = den > 0 ? Math.sqrt( num / den ) : 0;
		worstRelativeResidual = Math.max( worstRelativeResidual, lastRelativeResidual );

		return x;
	}

	private double lastRelativeResidual, worstRelativeResidual;
	private int notConverged;

	public double worstRelativeResidual() { return worstRelativeResidual; }

	public int numNotConverged() { return notConverged; }
}
