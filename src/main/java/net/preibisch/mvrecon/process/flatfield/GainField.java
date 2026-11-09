package net.preibisch.mvrecon.process.flatfield;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import Jama.CholeskyDecomposition;
import Jama.Matrix;

/**
 * A log-linear intensity field fixed in every tile's frame, fitted from the tile overlaps, a port of spotlight's
 * {@code gainfield.py}:
 *
 * <pre>
 *     log f(y, x) = c_y * u_y + c_x * u_x        u in [-0.5, 0.5] of the tile ({@link #frameCoord})
 * </pre>
 *
 * so {@code e^c_x} is the end-to-end ramp along x; one field per camera. It is for a ramp across every tile that
 * BaSiC cannot see (few tiles of one specimen, where per-pixel statistics follow the specimen, not the optics) and
 * that a chain of per-tile gains would otherwise compound into a ramp across the whole sample. The overlaps see it
 * because they compare the same tissue at two places in the frame.
 *
 * A pair's log ratio is modelled as {@code d = (G_A - G_B) + c_A . pos_a - c_B . pos_b + (u_a - u_b) + e}: G one
 * fixed gain per camera, u a random per-tile deviation (variance tau^2), e the measurement (variance sigma^2 / w).
 * Generalized least squares under that covariance, {@code rho = tau^2 / sigma^2} by restricted maximum likelihood
 * over {@link #RHO_GRID}. Random rather than free per-tile gains: free ones absorb any pattern at the level of a pair,
 * so a field beside them is pinned only by the variation within each overlap. An axis is kept only if its
 * coefficient clears {@link #FIELD_SIGMA} standard errors in every camera, then the fit is repeated without the rest.
 */
public class GainField
{
	public static final String[] AXES = { "y", "x" };

	/** an axis is applied only if its coefficient clears this many standard errors (in every camera) */
	public static final double FIELD_SIGMA = 3.0;

	/** tau^2 / sigma^2 the REML search tries: 0 (no tile scatter, weighted least squares) to 10^6 */
	static final double[] RHO_GRID = new double[ 38 ];
	static
	{
		for ( int i = 0; i < 37; ++i )
			RHO_GRID[ i + 1 ] = Math.pow( 10, -3 + 9.0 * i / 36 );
	}

	/** one overlapping pair of tiles a, b (indices into the tile list) */
	public static class Constraint
	{
		public final int a, b;
		/** log(median_a) - log(median_b) on the shared foreground */
		public final double d;
		/** foreground voxels behind it */
		public final double w;
		/** mean frame coordinate of the matched voxels in each tile: ya, xa, yb, xb */
		public final double[] pos;

		public Constraint( final int a, final int b, final double d, final double w, final double[] pos )
		{
			this.a = a; this.b = b; this.d = d; this.w = w; this.pos = pos;
		}

		@Override
		public String toString()
		{
			return String.format( "%d-%d: d=%+.3f w=%.0f pos=(%.2f, %.2f | %.2f, %.2f)", a, b, d, w, pos[ 0 ], pos[ 1 ], pos[ 2 ], pos[ 3 ] );
		}
	}

	/** the fitted field of one camera; c and sigma per axis in {@link #AXES} order */
	public static class Field
	{
		public final double[] c, sigma;

		Field( final double[] c, final double[] sigma )
		{
			this.c = c; this.sigma = sigma;
		}

		/** the multiplicative field on a row-major {@code h x w} (y, x) plane */
		public float[] plane( final int h, final int w )
		{
			final float[] f = new float[ h * w ];
			for ( int y = 0; y < h; ++y )
				for ( int x = 0; x < w; ++x )
					f[ y * w + x ] = ( float ) Math.exp( c[ 0 ] * frameCoord( y, h ) + c[ 1 ] * frameCoord( x, w ) );
			return f;
		}

		@Override
		public String toString()
		{
			final StringBuilder s = new StringBuilder();
			for ( int k = 0; k < AXES.length; ++k )
				s.append( String.format( "%sc_%s = %.4f +- %.4f (ramp x%.3f)", k > 0 ? ", " : "", AXES[ k ], c[ k ], sigma[ k ], Math.exp( c[ k ] ) ) );
			return s.toString();
		}
	}

	public static class Fit
	{
		/** camera -> field */
		public final Map< Integer, Field > fields;
		/** tile -> gain with one gain per camera (the per-tile gains are left to the intensity correction) */
		public final double[] gains;
		/** per axis: "kept", or why it was not applied */
		public final String[] status;
		/** tile-to-tile log-gain scatter the REML fit allowed for */
		public final double tau;

		Fit( final Map< Integer, Field > fields, final double[] gains, final String[] status, final double tau )
		{
			this.fields = fields; this.gains = gains; this.status = status; this.tau = tau;
		}
	}

	/** a voxel index as its centre's position in [-0.5, 0.5] of an n-voxel axis */
	public static double frameCoord( final double index, final int n )
	{
		return ( index + 0.5 ) / n - 0.5;
	}

	/**
	 * @param cameraOfTile camera of each tile (tile i of the constraints' indices)
	 */
	public static Fit fit( final List< Constraint > constraints, final int[] cameraOfTile )
	{
		final int[] all = { 0, 1 };
		Gls g = gls( constraints, cameraOfTile, all );
		final String[] status = new String[ AXES.length ];
		final List< Integer > keep = new ArrayList<>();
		for ( final int k : all )
		{
			double z = Double.POSITIVE_INFINITY;
			for ( final Field f : g.fields.values() )
				z = Math.min( z, f.sigma[ k ] > 0 ? Math.abs( f.c[ k ] ) / f.sigma[ k ] : ( f.c[ k ] != 0 ? Double.POSITIVE_INFINITY : 0 ) );
			status[ k ] = g.why.containsKey( k ) ? g.why.get( k ) : z > FIELD_SIGMA ? "kept" : "below " + java.math.BigDecimal.valueOf( FIELD_SIGMA ).stripTrailingZeros().toPlainString() + " sigma (" + String.format( "%.1f", z ) + ")";
			if ( status[ k ].equals( "kept" ) )
				keep.add( k );
		}
		if ( keep.size() != all.length )
			g = gls( constraints, cameraOfTile, keep.stream().mapToInt( Integer::intValue ).toArray() );
		return new Fit( g.fields, g.gains, status, g.tau );
	}

	private static class Gls
	{
		Map< Integer, Field > fields;
		double[] gains;
		double tau;
		Map< Integer, String > why;
	}

	private static Gls gls( final List< Constraint > constraints, final int[] cameraOfTile, final int[] axes )
	{
		final TreeSet< Integer > camSet = new TreeSet<>();
		for ( final int c : cameraOfTile )
			camSet.add( c );
		final List< Integer > cams = new ArrayList<>( camSet );
		final int nTiles = cameraOfTile.length, nGain = cams.size() - 1, p = nGain + cams.size() * axes.length;
		final Map< Integer, Integer > gcol = new HashMap<>();
		for ( int i = 1; i < cams.size(); ++i )                 // camera 0 is the reference
			gcol.put( cams.get( i ), i - 1 );
		final int n = constraints.size();
		final double[][] X = new double[ n ][ p ], D = new double[ n ][ nTiles ];
		final double[] d = new double[ n ], w = new double[ n ];
		for ( int r = 0; r < n; ++r )
		{
			final Constraint con = constraints.get( r );
			final int ca = cameraOfTile[ con.a ], cb = cameraOfTile[ con.b ];
			if ( ca != cb )
			{
				if ( gcol.containsKey( ca ) )
					X[ r ][ gcol.get( ca ) ] += 1;
				if ( gcol.containsKey( cb ) )
					X[ r ][ gcol.get( cb ) ] -= 1;
			}
			for ( int j = 0; j < axes.length; ++j )
			{
				final int k = axes[ j ];
				X[ r ][ fcol( cams, ca, j, axes, nGain ) ] += con.pos[ k ];        // (ya, xa)[k]
				X[ r ][ fcol( cams, cb, j, axes, nGain ) ] -= con.pos[ 2 + k ];    // (yb, xb)[k]
			}
			D[ r ][ con.a ] += 1;
			D[ r ][ con.b ] -= 1;
			d[ r ] = con.d;
			w[ r ] = con.w;
		}

		final Reml res = remlGls( X, D, d, w );
		final Gls g = new Gls();
		g.why = new HashMap<>();
		for ( final int k : axes )
			for ( final int c : cams )
			{
				final int col = fcol( cams, c, indexOf( axes, k ), axes, nGain );
				boolean any = false;
				for ( int r = 0; r < n; ++r )
					any |= X[ r ][ col ] != 0;
				if ( !any )
					g.why.put( k, "no lever arm: the overlaps never vary it" );
			}
		if ( res.dof < 1 )
			for ( final int k : axes )
				g.why.put( k, String.format( "too few pairs (%d for %d unknowns)", n, n - res.dof ) );

		final Map< Integer, Double > logg = new HashMap<>();
		double mean = 0;
		for ( final int c : cams )
		{
			final double v = gcol.containsKey( c ) ? res.beta[ gcol.get( c ) ] : 0.0;
			logg.put( c, v );
			mean += v / cams.size();
		}
		// centred over cameras, so turning the field on does not move a multi-camera dataset's absolute level
		g.gains = new double[ nTiles ];
		for ( int t = 0; t < nTiles; ++t )
			g.gains[ t ] = Math.exp( logg.get( cameraOfTile[ t ] ) - mean );
		g.fields = new HashMap<>();
		for ( final int c : cams )
		{
			final double[] coef = new double[ AXES.length ], sig = new double[ AXES.length ];
			for ( int j = 0; j < axes.length; ++j )
			{
				final int col = fcol( cams, c, j, axes, nGain );
				coef[ axes[ j ] ] = res.beta[ col ];
				sig[ axes[ j ] ] = Math.sqrt( res.var[ col ] );
			}
			g.fields.put( c, new Field( coef, sig ) );
		}
		g.tau = Math.sqrt( res.rho * res.s2 );
		return g;
	}

	private static int fcol( final List< Integer > cams, final int cam, final int axisIndex, final int[] axes, final int nGain )
	{
		return nGain + cams.indexOf( cam ) * axes.length + axisIndex;
	}

	private static int indexOf( final int[] a, final int v )
	{
		for ( int i = 0; i < a.length; ++i )
			if ( a[ i ] == v )
				return i;
		return -1;
	}

	static class Reml
	{
		double[] beta, var;
		double rho, s2;
		int dof;
	}

	/**
	 * Generalized least squares for {@code d = X beta + D u + e}, {@code u ~ N(0, tau^2)} per column of D,
	 * {@code e ~ N(0, sigma^2 / w)}. {@code rho = tau^2 / sigma^2} is the grid value with the highest restricted
	 * likelihood (sigma^2 profiled out). A column of X that is all zero has no estimate: beta 0, var infinite; so does
	 * every column when {@code dof < 1}.
	 */
	static Reml remlGls( final double[][] X, final double[][] D, final double[] d, final double[] wIn )
	{
		// ponytail: dense pairs x pairs covariance, fine to a few thousand pairs (as spotlight)
		final int n = X.length, p = n == 0 ? 0 : X[ 0 ].length;
		final double[] w = wIn.clone();
		final double[] sorted = w.clone();
		Arrays.sort( sorted );
		final double med = n == 0 ? 1 : ( n % 2 == 1 ? sorted[ n / 2 ] : 0.5 * ( sorted[ n / 2 - 1 ] + sorted[ n / 2 ] ) );
		for ( int i = 0; i < n; ++i )
			w[ i ] /= med;

		final List< Integer > live = new ArrayList<>();
		for ( int j = 0; j < p; ++j )
		{
			double s = 0;
			for ( int i = 0; i < n; ++i )
				s += Math.abs( X[ i ][ j ] );
			if ( s > 1e-12 )
				live.add( j );
		}
		final Reml out = new Reml();
		out.beta = new double[ p ];
		out.var = new double[ p ];
		Arrays.fill( out.var, Double.POSITIVE_INFINITY );
		out.dof = n - live.size();
		if ( out.dof < 1 )
			return out;

		final Matrix Xl = new Matrix( n, live.size() );
		for ( int i = 0; i < n; ++i )
			for ( int j = 0; j < live.size(); ++j )
				Xl.set( i, j, X[ i ][ live.get( j ) ] );
		final Matrix Dm = new Matrix( D );
		final Matrix DDt = Dm.times( Dm.transpose() );
		final Matrix dv = new Matrix( d, n );

		double bestLl = Double.NEGATIVE_INFINITY, bestRho = 0, bestS2 = 0;
		Matrix bestB = null, bestXtX = null;
		for ( final double rho : RHO_GRID )
		{
			final Matrix V = DDt.times( rho );
			for ( int i = 0; i < n; ++i )
				V.set( i, i, V.get( i, i ) + 1.0 / w[ i ] );
			final Matrix L = new CholeskyDecomposition( V ).getL();
			final Matrix Xs = forward( L, Xl ), ds = forward( L, dv );
			final Matrix XtX = Xs.transpose().times( Xs );
			final Matrix b = XtX.solve( Xs.transpose().times( ds ) );
			final Matrix res = ds.minus( Xs.times( b ) );
			double q = 0, logDetL = 0;
			for ( int i = 0; i < n; ++i )
			{
				q += res.get( i, 0 ) * res.get( i, 0 );
				logDetL += Math.log( L.get( i, i ) );
			}
			q = Math.max( q, 1e-300 );
			final double ll = -0.5 * ( out.dof * Math.log( q / out.dof ) + 2 * logDetL + Math.log( Math.abs( XtX.det() ) ) );
			if ( ll > bestLl )
			{
				bestLl = ll; bestRho = rho; bestB = b; bestXtX = XtX; bestS2 = q / out.dof;
			}
		}
		final Matrix cov = bestXtX.inverse();
		for ( int j = 0; j < live.size(); ++j )
		{
			out.beta[ live.get( j ) ] = bestB.get( j, 0 );
			out.var[ live.get( j ) ] = cov.get( j, j ) * bestS2;
		}
		out.rho = bestRho;
		out.s2 = bestS2;
		return out;
	}

	/** L^-1 B for lower-triangular L */
	private static Matrix forward( final Matrix L, final Matrix B )
	{
		final int n = L.getRowDimension(), m = B.getColumnDimension();
		final Matrix Y = new Matrix( n, m );
		for ( int c = 0; c < m; ++c )
			for ( int i = 0; i < n; ++i )
			{
				double s = B.get( i, c );
				for ( int k = 0; k < i; ++k )
					s -= L.get( i, k ) * Y.get( k, c );
				Y.set( i, c, s / L.get( i, i ) );
			}
		return Y;
	}
}
