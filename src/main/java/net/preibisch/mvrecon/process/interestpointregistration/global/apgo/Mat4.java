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

import Jama.Matrix;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * 4x4 matrix helpers for {@link APGOSolver}, including the matrix exponential and
 * logarithm. Neither <code>expm</code> nor <code>logm</code> exists in any dependency
 * on the classpath (JAMA, commons-math3, EJML, ojalgo all lack them), so they are
 * implemented here.
 *
 * All matrices are <code>double[4][4]</code>. Group elements are affine, i.e. their
 * last row is <code>[0,0,0,1]</code>; algebra elements have a last row of zeros. Both
 * {@link #exp} and {@link #log} preserve that structure and snap the last row to kill
 * rounding drift.
 */
public class Mat4
{
	private Mat4() {}

	public static double[][] identity()
	{
		final double[][] m = new double[ 4 ][ 4 ];
		for ( int i = 0; i < 4; ++i )
			m[ i ][ i ] = 1;
		return m;
	}

	public static double[][] copy( final double[][] a )
	{
		final double[][] m = new double[ 4 ][ 4 ];
		for ( int i = 0; i < 4; ++i )
			System.arraycopy( a[ i ], 0, m[ i ], 0, 4 );
		return m;
	}

	public static double[][] mult( final double[][] a, final double[][] b )
	{
		final double[][] m = new double[ 4 ][ 4 ];
		for ( int i = 0; i < 4; ++i )
			for ( int k = 0; k < 4; ++k )
			{
				final double aik = a[ i ][ k ];
				if ( aik == 0 )
					continue;
				for ( int j = 0; j < 4; ++j )
					m[ i ][ j ] += aik * b[ k ][ j ];
			}
		return m;
	}

	public static double[][] add( final double[][] a, final double[][] b )
	{
		final double[][] m = new double[ 4 ][ 4 ];
		for ( int i = 0; i < 4; ++i )
			for ( int j = 0; j < 4; ++j )
				m[ i ][ j ] = a[ i ][ j ] + b[ i ][ j ];
		return m;
	}

	public static double[][] sub( final double[][] a, final double[][] b )
	{
		final double[][] m = new double[ 4 ][ 4 ];
		for ( int i = 0; i < 4; ++i )
			for ( int j = 0; j < 4; ++j )
				m[ i ][ j ] = a[ i ][ j ] - b[ i ][ j ];
		return m;
	}

	public static double[][] scale( final double[][] a, final double s )
	{
		final double[][] m = new double[ 4 ][ 4 ];
		for ( int i = 0; i < 4; ++i )
			for ( int j = 0; j < 4; ++j )
				m[ i ][ j ] = a[ i ][ j ] * s;
		return m;
	}

	/** Maximum absolute row sum. */
	public static double normInf( final double[][] a )
	{
		double max = 0;
		for ( int i = 0; i < 4; ++i )
		{
			double row = 0;
			for ( int j = 0; j < 4; ++j )
				row += Math.abs( a[ i ][ j ] );
			max = Math.max( max, row );
		}
		return max;
	}

	/** Frobenius norm - this is what numpy's <code>linalg.norm(m)</code> computes, which the reference uses for its residual tracking. */
	public static double normFrobenius( final double[][] a )
	{
		double sum = 0;
		for ( int i = 0; i < 4; ++i )
			for ( int j = 0; j < 4; ++j )
				sum += a[ i ][ j ] * a[ i ][ j ];
		return Math.sqrt( sum );
	}

	public static double[][] inverse( final double[][] a )
	{
		return new Matrix( a ).inverse().getArray();
	}

	/**
	 * Matrix exponential by scaling and squaring with a Taylor series. After scaling
	 * to <code>||X|| &lt;= 0.5</code>, 18 terms is machine precision.
	 */
	public static double[][] exp( final double[][] x )
	{
		int s = 0;
		double n = normInf( x );
		while ( n > 0.5 )
		{
			n /= 2;
			++s;
		}

		final double[][] a = scale( x, 1.0 / Math.pow( 2, s ) );

		double[][] result = identity();
		double[][] term = identity();
		for ( int k = 1; k <= 18; ++k )
		{
			term = scale( mult( term, a ), 1.0 / k );
			result = add( result, term );
		}

		for ( int i = 0; i < s; ++i )
			result = mult( result, result );

		result[ 3 ][ 0 ] = result[ 3 ][ 1 ] = result[ 3 ][ 2 ] = 0;
		result[ 3 ][ 3 ] = 1;
		return result;
	}

	/**
	 * Matrix logarithm by inverse scaling and squaring: take repeated square roots
	 * until the matrix is close enough to the identity for the inverse-hyperbolic-
	 * tangent series to converge quickly, then undo the square roots.
	 */
	public static double[][] log( final double[][] m )
	{
		final double[][] id = identity();

		int k = 0;
		double[][] a = copy( m );
		while ( normInf( sub( a, id ) ) > 0.25 && k < 64 )
		{
			a = sqrt( a );
			++k;
		}

		// log(A) = 2 * atanh(Z) with Z = (A-I)(A+I)^-1
		final double[][] z = mult( sub( a, id ), inverse( add( a, id ) ) );
		final double[][] z2 = mult( z, z );

		double[][] term = copy( z );
		double[][] sum = copy( z );
		for ( int j = 3; j <= 41; j += 2 )
		{
			term = mult( term, z2 );
			sum = add( sum, scale( term, 1.0 / j ) );
		}

		final double[][] result = scale( sum, 2.0 * Math.pow( 2, k ) );
		result[ 3 ][ 0 ] = result[ 3 ][ 1 ] = result[ 3 ][ 2 ] = result[ 3 ][ 3 ] = 0;
		return result;
	}

	/** Matrix square root by Denman-Beavers iteration. */
	public static double[][] sqrt( final double[][] m )
	{
		double[][] y = copy( m );
		double[][] z = identity();

		for ( int i = 0; i < 50; ++i )
		{
			final double[][] yn = scale( add( y, inverse( z ) ), 0.5 );
			final double[][] zn = scale( add( z, inverse( y ) ), 0.5 );
			y = yn;
			z = zn;

			if ( normInf( sub( mult( y, y ), m ) ) < 1e-14 )
				break;
		}

		return y;
	}

	public static double[][] fromAffine( final AffineTransform3D t )
	{
		final double[][] m = identity();
		for ( int r = 0; r < 3; ++r )
			for ( int c = 0; c < 4; ++c )
				m[ r ][ c ] = t.get( r, c );
		return m;
	}

	public static AffineTransform3D toAffine( final double[][] m )
	{
		final AffineTransform3D t = new AffineTransform3D();
		t.set( m[ 0 ][ 0 ], m[ 0 ][ 1 ], m[ 0 ][ 2 ], m[ 0 ][ 3 ],
				m[ 1 ][ 0 ], m[ 1 ][ 1 ], m[ 1 ][ 2 ], m[ 1 ][ 3 ],
				m[ 2 ][ 0 ], m[ 2 ][ 1 ], m[ 2 ][ 2 ], m[ 2 ][ 3 ] );
		return t;
	}

	/** Self-check: exp(log(M)) == M and log(exp(X)) == X for random affines. */
	public static void main( final String[] args )
	{
		final java.util.Random rnd = new java.util.Random( 42 );
		double worstRoundTrip = 0, worstInverse = 0;

		for ( int trial = 0; trial < 1000; ++trial )
		{
			// random affine: identity plus a perturbation of growing magnitude
			final double s = 0.01 * ( 1 + trial % 100 );
			final double[][] m = identity();
			for ( int r = 0; r < 3; ++r )
				for ( int c = 0; c < 4; ++c )
					m[ r ][ c ] += s * ( rnd.nextDouble() - 0.5 ) * ( c == 3 ? 100 : 1 );

			worstRoundTrip = Math.max( worstRoundTrip, normInf( sub( exp( log( m ) ), m ) ) / normInf( m ) );
			worstInverse = Math.max( worstInverse, normInf( sub( mult( m, inverse( m ) ), identity() ) ) );
		}

		System.out.println( "worst relative exp(log(M)) error: " + worstRoundTrip );
		System.out.println( "worst M*inv(M) - I error:         " + worstInverse );
		assert worstRoundTrip < 1e-10 : "exp/log round trip broken";
		if ( worstRoundTrip >= 1e-10 )
			throw new AssertionError( "exp/log round trip broken: " + worstRoundTrip );
		System.out.println( "OK" );
	}
}
