/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2025 Multiview Reconstruction developers.
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
package net.preibisch.mvrecon.process.flatfield;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import Jama.Matrix;
import edu.emory.mathcs.jtransforms.dct.FloatDCT_2D;
import net.preibisch.legacy.io.IOFunctions;

/**
 * BaSiC flat-field / dark-field estimation (Peng et al., Nature Communications 2017), following
 * BigFlatFieldIlluminator.jl: the reweighted inexact ALM of the paper, lambdas from the L1 norm of the mean image's
 * DCT, and BaSiCPy's autotune. Frames are row-major (y, x) planes of {@code h x w}.
 *
 * Apply the result as {@code (raw - dark) / flat}: {@code flat} has mean 1, {@code dark} is in input counts.
 */
public class Basic
{
	public static class Parameters
	{
		/** 0 = automatic (l1(DCT(mean)) / 800, or autotuned) */
		public double lambda = 0;
		/** 0 = automatic (l1(DCT(mean)) / 2000) */
		public double lambdaDarkfield = 0;
		public boolean estimateDarkfield = true;
		/** search around the automatic lambda when {@link #lambda} is 0 */
		public boolean autotune = true;
		public int maxIterations = 500;
		public double optimizationTol = 1e-6;
		public double reweightTol = 1e-3;
		public int maxReweightingIterations = 10;
		public double epsilon = 0.1;
		/** fit on a square working x working grid, 0 = the stack's own frame */
		public int workingSize = 0;
		/** a darkfield to hold fixed, in counts, or null to fit (or not, see {@link #estimateDarkfield}) one */
		public Double darkfieldOverride = null;

		public Parameters copy()
		{
			final Parameters p = new Parameters();
			p.lambda = lambda; p.lambdaDarkfield = lambdaDarkfield; p.estimateDarkfield = estimateDarkfield; p.autotune = autotune;
			p.maxIterations = maxIterations; p.optimizationTol = optimizationTol; p.reweightTol = reweightTol;
			p.maxReweightingIterations = maxReweightingIterations; p.epsilon = epsilon; p.workingSize = workingSize;
			p.darkfieldOverride = darkfieldOverride;
			return p;
		}
	}

	/** flat (mean 1) and dark (counts) fields of {@code h x w}, and the lambda that was used */
	public static class Fields
	{
		public final float[] flat, dark;
		public final int h, w;
		public final double lambda;

		Fields( final float[] flat, final float[] dark, final int h, final int w, final double lambda )
		{
			this.flat = flat; this.dark = dark; this.h = h; this.w = w; this.lambda = lambda;
		}
	}

	private static final float EPS = Math.ulp( 1.0f );

	/** the size {@code l1(DCT)} is computed at, which the 800 / 2000 constants are calibrated for */
	public static final int LAMBDA_CAL_SIZE = 128;

	/**
	 * Autotunes lambda if asked and none was given, then fits.
	 *
	 * @param images frames (raw counts), each row-major {@code h x w}
	 * @param outH output frame (e.g. level 0), the fields are resized once from the working grid
	 */
	public static Fields estimate( final float[][] images, final int h, final int w, final Parameters p, final int outH, final int outW )
	{
		double lambda = p.lambda;
		if ( p.autotune && lambda == 0 )
		{
			final double[] lm = autotuneLambda( images, h, w, p );
			lambda = lm[ 0 ];
			IOFunctions.println( String.format( "BaSiC: autotuned lambda = %.5g (%gx the l1/800 default)", lm[ 0 ], lm[ 1 ] ) );
		}
		final Parameters q = p.copy();
		q.lambda = lambda;
		return basicEstimate( images, h, w, q, outH, outW );
	}

	// ─── Orthonormal 2D DCT-II ────────────────────────────────────────────────

	/** per (h, w): JTransforms plans are not cheap to create */
	private static final Map< List< Integer >, FloatDCT_2D > DCTS = new LinkedHashMap<>();

	private static synchronized FloatDCT_2D dct( final int h, final int w )
	{
		return DCTS.computeIfAbsent( Arrays.asList( h, w ), k -> new FloatDCT_2D( h, w ) );
	}

	static float[] dct2( final float[] x, final int h, final int w )
	{
		final float[] y = x.clone();
		dct( h, w ).forward( y, true );
		return y;
	}

	static float[] idct2( final float[] x, final int h, final int w )
	{
		final float[] y = x.clone();
		dct( h, w ).inverse( y, true );
		return y;
	}

	private static void shrink( final float[] x, final float t )
	{
		for ( int i = 0; i < x.length; ++i )
		{
			final float a = Math.abs( x[ i ] ) - t;
			x[ i ] = a > 0 ? Math.signum( x[ i ] ) * a : 0;
		}
	}

	// ─── Resize (ImageTransformations.imresize: corner-aligned bilinear, no anti-aliasing) ─────

	private static void axisWeights( final int nIn, final int nOut, final int[] lo, final int[] hi, final double[] g )
	{
		final double sf = ( double ) nIn / nOut;
		for ( int i = 0; i < nOut; ++i )
		{
			final double c = Math.clamp( sf * ( i + 0.5 ) - 0.5, 0.0, nIn - 1.0 );
			int l = ( int ) Math.floor( c );
			if ( nIn > 1 )
			{
				l = Math.min( l, nIn - 2 );
				hi[ i ] = l + 1;
			}
			else
				hi[ i ] = l;
			lo[ i ] = l;
			g[ i ] = c - l;
		}
	}

	/** bilinear resize of a row-major {@code h x w} plane to {@code outH x outW} */
	public static float[] imresize( final float[] a, final int h, final int w, final int outH, final int outW )
	{
		if ( h == outH && w == outW )
			return a.clone();
		// rows first, then columns
		double[] rows = new double[ outH * w ];
		{
			final int[] lo = new int[ outH ], hi = new int[ outH ];
			final double[] g = new double[ outH ];
			axisWeights( h, outH, lo, hi, g );
			for ( int y = 0; y < outH; ++y )
				for ( int x = 0; x < w; ++x )
					rows[ y * w + x ] = h == outH ? a[ y * w + x ] : ( 1.0 - g[ y ] ) * a[ lo[ y ] * w + x ] + g[ y ] * a[ hi[ y ] * w + x ];
		}
		final float[] out = new float[ outH * outW ];
		final int[] lo = new int[ outW ], hi = new int[ outW ];
		final double[] g = new double[ outW ];
		axisWeights( w, outW, lo, hi, g );
		for ( int y = 0; y < outH; ++y )
			for ( int x = 0; x < outW; ++x )
				out[ y * outW + x ] = ( float ) ( w == outW ? rows[ y * w + x ] : ( 1.0 - g[ x ] ) * rows[ y * w + lo[ x ] ] + g[ x ] * rows[ y * w + hi[ x ] ] );
		return out;
	}

	// ─── Core algorithm ───────────────────────────────────────────────────────

	private static float mean( final float[] a )
	{
		double s = 0;
		for ( final float v : a )
			s += v;
		return ( float ) ( s / a.length );
	}

	private static float mean( final float[][] a )
	{
		double s = 0;
		for ( final float[] r : a )
			for ( final float v : r )
				s += v;
		return ( float ) ( s / ( ( double ) a.length * a[ 0 ].length ) );
	}

	private static float l1Dct( final float[] meanImg, final int h, final int w )
	{
		final float[] m = h == LAMBDA_CAL_SIZE && w == LAMBDA_CAL_SIZE ? meanImg : imresize( meanImg, h, w, LAMBDA_CAL_SIZE, LAMBDA_CAL_SIZE );
		double s = 0;
		for ( final float v : dct2( m, LAMBDA_CAL_SIZE, LAMBDA_CAL_SIZE ) )
			s += Math.abs( v );
		return ( float ) s;
	}

	/** The fit for one fixed lambda (0 = l1/800). */
	public static Fields basicEstimate( float[][] images, int h, int w, final Parameters p, final int outH, final int outW )
	{
		final int n = images.length;
		final boolean fixedDarkfield = p.darkfieldOverride != null;
		final boolean estimateDarkfield = p.estimateDarkfield && !fixedDarkfield;

		if ( p.workingSize > 0 && ( h != p.workingSize || w != p.workingSize ) )
		{
			final float[][] small = new float[ n ][];
			for ( int k = 0; k < n; ++k )
				small[ k ] = imresize( images[ k ], h, w, p.workingSize, p.workingSize );
			images = small;
			h = w = p.workingSize;
		}
		final int hw = h * w;

		final float globalMean = mean( images );
		if ( globalMean < EPS )
			throw new IllegalArgumentException( "BaSiC: image stack is all-zero" );

		// d = images / global_mean, sorted per pixel across frames (MATLAB sort(D,3))
		final float[][] d = new float[ n ][ hw ];
		final float[] col = new float[ n ];
		final float[] meanImg = new float[ hw ];
		for ( int i = 0; i < hw; ++i )
		{
			for ( int k = 0; k < n; ++k )
				col[ k ] = images[ k ][ i ] / globalMean;
			Arrays.sort( col );
			double s = 0;
			for ( int k = 0; k < n; ++k )
			{
				d[ k ][ i ] = col[ k ];
				s += col[ k ];
			}
			meanImg[ i ] = ( float ) ( s / n );
		}

		float lam = ( float ) p.lambda, lamDark = ( float ) p.lambdaDarkfield;
		if ( lam == 0 || lamDark == 0 )
		{
			final float l1 = l1Dct( meanImg, h, w );
			if ( lam == 0 )
				lam = l1 / 800.0f;
			if ( lamDark == 0 )
				lamDark = l1 / 2000.0f;
		}

		// spectral norm of the (hw x n) matrix = sqrt of the largest eigenvalue of its n x n Gram matrix
		final double[][] gram = new double[ n ][ n ];
		for ( int a = 0; a < n; ++a )
			for ( int b = a; b < n; ++b )
			{
				double s = 0;
				for ( int i = 0; i < hw; ++i )
					s += ( double ) d[ a ][ i ] * d[ b ][ i ];
				gram[ a ][ b ] = gram[ b ][ a ] = s;
			}
		double maxEig = 0, normSq = 0;
		for ( final double v : new Matrix( gram ).eig().getRealEigenvalues() )
			maxEig = Math.max( maxEig, v );
		for ( int k = 0; k < n; ++k )
			normSq += gram[ k ][ k ];
		final float normTwo = ( float ) Math.sqrt( maxEig );
		final float normD = ( float ) Math.sqrt( normSq );
		final float muInit = 12.5f / normTwo;
		final float muBar = muInit * 1e7f;

		// after the sort, d[0] holds the per-pixel minimum across frames
		final float darkfieldLimit = mean( d[ 0 ] );

		final State s = new State( n, hw, estimateDarkfield );
		s.wHat = dct2( meanImg, h, w );             // warm-started from the mean image
		if ( fixedDarkfield )
			Arrays.fill( s.aOffset, ( float ) ( p.darkfieldOverride / globalMean ) );

		final float[] flatPrev = new float[ hw ];
		Arrays.fill( flatPrev, 1 );
		final float[] darkPrev = new float[ hw ];
		final float[] ffCurr = new float[ hw ];

		for ( int r = 0; r < p.maxReweightingIterations; ++r )
		{
			for ( int k = 0; k < n; ++k )
			{
				Arrays.fill( s.y1[ k ], 0 );
				Arrays.fill( s.e[ k ], 0 );
			}
			final float b1Off = almLoop( s, d, h, w, muInit, muBar, 1.5f, 1.0f, 10.0f, lam, lamDark, darkfieldLimit,
					estimateDarkfield, p.maxIterations, ( float ) p.optimizationTol, normD );

			if ( estimateDarkfield )
				for ( int i = 0; i < hw; ++i )
					s.aOffset[ i ] += b1Off * s.f[ i ];

			// reweighting convergence: normalised L1 change, matching MATLAB
			final float a1Mean = mean( s.a1Coeff );
			for ( int i = 0; i < hw; ++i )
				ffCurr[ i ] = s.f[ i ] * a1Mean;
			final float ffm = Math.max( mean( ffCurr ), EPS );
			double dff = 0, sff = 0;
			for ( int i = 0; i < hw; ++i )
			{
				ffCurr[ i ] /= ffm;
				dff += Math.abs( ffCurr[ i ] - flatPrev[ i ] );
				sff += Math.abs( flatPrev[ i ] );
			}
			final double madFF = dff / Math.max( sff, EPS );
			double madDF = 0;
			if ( estimateDarkfield )
			{
				double td = 0, sd = 0;
				for ( int i = 0; i < hw; ++i )
				{
					td += Math.abs( s.aOffset[ i ] - darkPrev[ i ] );
					sd += Math.abs( darkPrev[ i ] );
				}
				madDF = td < 1e-7 ? 0 : td / Math.max( sd, 1e-6 );
			}

			if ( Math.max( madFF, madDF ) <= p.reweightTol )
				break;

			System.arraycopy( ffCurr, 0, flatPrev, 0, hw );
			if ( estimateDarkfield )
				System.arraycopy( s.aOffset, 0, darkPrev, 0, hw );
			updateWeights( s, ( float ) p.epsilon );
		}

		float[] flat = idct2( s.wHat, h, w );
		for ( int i = 0; i < hw; ++i )
			flat[ i ] = Math.max( flat[ i ], 0 );
		float fm = mean( flat );
		if ( fm < EPS )
			fm = 1;
		for ( int i = 0; i < hw; ++i )
			flat[ i ] /= fm;

		float[] dark = s.aOffset;
		if ( h != outH || w != outW )
		{
			flat = imresize( flat, h, w, outH, outW );
			dark = imresize( dark, h, w, outH, outW );
		}
		// the darkfield in the units of the input (it is additive; the flatfield is a scale-free multiplier)
		final float[] darkCounts = new float[ dark.length ];
		for ( int i = 0; i < dark.length; ++i )
			darkCounts[ i ] = dark[ i ] * globalMean;
		return new Fields( flat, darkCounts, outH, outW, lam );
	}

	/** the arrays of the ALM iteration, frames as rows */
	private static class State
	{
		final int n, hw;
		float[] wHat;
		final float[][] e, a1Hat, r1, z, y1, wCoeff;
		final float[] a1Coeff, aOffset, f, rW, b1Coeff, bOffset, a1Offset;

		State( final int n, final int hw, final boolean darkfield )
		{
			this.n = n; this.hw = hw;
			e = new float[ n ][ hw ]; a1Hat = new float[ n ][ hw ]; r1 = new float[ n ][ hw ];
			z = new float[ n ][ hw ]; y1 = new float[ n ][ hw ]; wCoeff = new float[ n ][ hw ];
			for ( final float[] r : wCoeff )
				Arrays.fill( r, 1 );
			a1Coeff = new float[ n ];
			Arrays.fill( a1Coeff, 1 );
			aOffset = new float[ hw ]; f = new float[ hw ]; rW = new float[ hw ];
			b1Coeff = new float[ darkfield ? n : 0 ];
			bOffset = new float[ darkfield ? hw : 0 ];
			a1Offset = new float[ darkfield ? hw : 0 ];
		}

		/** f = max(idct(wHat), 0) */
		void flatFromW( final int h, final int w )
		{
			final float[] ff = idct2( wHat, h, w );
			for ( int i = 0; i < hw; ++i )
				f[ i ] = Math.max( ff[ i ], 0 );
		}

		/** a1Hat = f x a1Coeff + aOffset */
		void model()
		{
			for ( int k = 0; k < n; ++k )
			{
				final float c = a1Coeff[ k ];
				final float[] row = a1Hat[ k ];
				for ( int i = 0; i < hw; ++i )
					row[ i ] = f[ i ] * c + aOffset[ i ];
			}
		}
	}

	private static float almLoop( final State s, final float[][] d, final int h, final int w,
			final float muInit, final float muBar, final float rho, final float ent1, final float ent2,
			final float lam, final float lamDark, final float darkfieldLimit, final boolean estimateDarkfield,
			final int maxIterations, final float tol, final float normD )
	{
		final int n = s.n, hw = s.hw;
		float mu = muInit;
		float b1Off = 0;

		for ( int it = 0; it < maxIterations; ++it )
		{
			// low-rank model: a rank-1 flatfield scaled per frame, plus the additive darkfield
			s.flatFromW( h, w );
			s.model();

			// W_hat update
			Arrays.fill( s.rW, 0 );
			for ( int k = 0; k < n; ++k )
			{
				final float[] ek = s.e[ k ], dk = d[ k ], ak = s.a1Hat[ k ], yk = s.y1[ k ];
				for ( int i = 0; i < hw; ++i )
				{
					ek[ i ] = -ek[ i ] + dk[ i ] - ak[ i ] + yk[ i ] / mu;
					s.rW[ i ] += ek[ i ];
				}
			}
			for ( int i = 0; i < hw; ++i )
				s.rW[ i ] = s.rW[ i ] / n / ent1;
			final float[] dw = dct2( s.rW, h, w );
			for ( int i = 0; i < hw; ++i )
				s.wHat[ i ] += dw[ i ];
			shrink( s.wHat, lam / ( ent1 * mu ) );

			s.flatFromW( h, w );
			s.model();

			// E update: proximal step on |W_coeff . E|_1 subject to D = A1_hat + E
			for ( int k = 0; k < n; ++k )
			{
				final float[] ek = s.e[ k ], dk = d[ k ], ak = s.a1Hat[ k ], yk = s.y1[ k ], wk = s.wCoeff[ k ];
				for ( int i = 0; i < hw; ++i )
				{
					final float v = dk[ i ] - ak[ i ] + yk[ i ] / mu;
					final float t = Math.abs( v ) - wk[ i ] / ( ent1 * mu );
					ek[ i ] = t > 0 ? Math.signum( v ) * t : 0;
				}
			}

			// per-frame illumination scale
			double gs = 0;
			final double[] frameSum = new double[ n ];
			for ( int k = 0; k < n; ++k )
			{
				final float[] rk = s.r1[ k ], dk = d[ k ], ek = s.e[ k ];
				double fs = 0;
				for ( int i = 0; i < hw; ++i )
				{
					rk[ i ] = dk[ i ] - ek[ i ];
					fs += rk[ i ];
				}
				frameSum[ k ] = fs;
				gs += fs;
			}
			final float globalR1 = ( float ) ( gs / ( ( double ) n * hw ) );
			for ( int k = 0; k < n; ++k )
				s.a1Coeff[ k ] = Math.max( ( float ) ( frameSum[ k ] / hw ) / globalR1, 0 );
			s.model();

			if ( estimateDarkfield )
			{
				b1Off = updateDarkfield( s, h, w, darkfieldLimit, lamDark, ent2, mu );
				s.model();
			}

			// dual ascent and penalty growth
			double zz = 0;
			for ( int k = 0; k < n; ++k )
			{
				final float[] zk = s.z[ k ], dk = d[ k ], ak = s.a1Hat[ k ], ek = s.e[ k ], yk = s.y1[ k ];
				for ( int i = 0; i < hw; ++i )
				{
					zk[ i ] = dk[ i ] - ak[ i ] - ek[ i ];
					yk[ i ] += mu * zk[ i ];
					zz += ( double ) zk[ i ] * zk[ i ];
				}
			}
			mu = Math.min( mu * rho, muBar );

			if ( ( float ) Math.sqrt( zz ) / normD < tol )
				break;
		}
		return b1Off;
	}

	private static float updateDarkfield( final State s, final int h, final int w, final float darkfieldLimit,
			final float lamDark, final float ent2, final float mu )
	{
		final int n = s.n, hw = s.hw;
		// frames with below-average illumination carry the most darkfield signal
		final boolean[] valid = new boolean[ n ];
		int nValid = 0;
		for ( int k = 0; k < n; ++k )
			if ( valid[ k ] = s.a1Coeff[ k ] < 1.0f )
				++nValid;
		if ( nValid == 0 )
			return 0;

		final float fMean = mean( s.f );
		final boolean[] fHigh = new boolean[ hw ], fLow = new boolean[ hw ];
		int nHigh = 0, nLow = 0;
		for ( int i = 0; i < hw; ++i )
		{
			if ( fHigh[ i ] = s.f[ i ] > fMean - 1e-6f )
				++nHigh;
			if ( fLow[ i ] = s.f[ i ] < fMean + 1e-6f )
				++nLow;
		}
		final float g = mean( s.r1 );
		final float safeG = Math.abs( g ) < EPS ? 1.0f : g;

		if ( nHigh == 0 || nLow == 0 )
			Arrays.fill( s.b1Coeff, 0 );
		else
			for ( int k = 0; k < n; ++k )
			{
				if ( !valid[ k ] )
				{
					s.b1Coeff[ k ] = 0;
					continue;
				}
				double hiS = 0, loS = 0;
				final float[] frame = s.r1[ k ];
				for ( int i = 0; i < hw; ++i )
				{
					if ( fHigh[ i ] )
						hiS += frame[ i ];
					if ( fLow[ i ] )
						loS += frame[ i ];
				}
				s.b1Coeff[ k ] = ( ( float ) ( hiS / nHigh ) - ( float ) ( loS / nLow ) ) / safeG;
			}

		// least-squares scalar darkfield offset (MATLAB B1_offset), clamped to [0, darkfield_limit / f_mean]
		float t1 = 0, t2 = 0, t3 = 0, t4 = 0, a1ValidSum = 0;
		for ( int k = 0; k < n; ++k )
			if ( valid[ k ] )
			{
				final float a = s.a1Coeff[ k ], b = s.b1Coeff[ k ];
				t1 += a * a; t2 += a; t3 += b; t4 += a * b;
				a1ValidSum += a;
			}
		final float kn = nValid;
		final float t5 = t2 * t3 - kn * t4;
		float b1Off = Math.abs( t5 ) < EPS ? 0 : ( t1 * t3 - t2 * t4 ) / t5;
		b1Off = Math.min( Math.max( b1Off, 0 ), darkfieldLimit / Math.max( fMean, EPS ) );

		// the part anti-correlated with the flatfield shape
		final float a1ValidMean = a1ValidSum / nValid;
		for ( int i = 0; i < hw; ++i )
		{
			s.bOffset[ i ] = b1Off * fMean - b1Off * s.f[ i ];
			double r = 0;
			for ( int k = 0; k < n; ++k )
				if ( valid[ k ] )
					r += s.r1[ k ][ i ];
			s.a1Offset[ i ] = ( float ) ( r / nValid ) - a1ValidMean * s.f[ i ];
		}
		final float a1OffMean = mean( s.a1Offset );
		for ( int i = 0; i < hw; ++i )
		{
			s.a1Offset[ i ] -= a1OffMean;
			s.aOffset[ i ] = s.a1Offset[ i ] - s.bOffset[ i ];
		}

		// smooth and sparsify via DCT, then again in the image domain
		final float t = lamDark / ( ent2 * mu );
		final float[] wOff = dct2( s.aOffset, h, w );
		shrink( wOff, t );
		final float[] back = idct2( wOff, h, w );
		shrink( back, t );
		for ( int i = 0; i < hw; ++i )
			s.aOffset[ i ] = back[ i ] + s.bOffset[ i ];
		return b1Off;
	}

	/** MATLAB: weight[h,w,k] = 1 / (|E / mean_hw(A1_hat[:,:,k])| + eps), mean-normalised */
	private static void updateWeights( final State s, final float epsilon )
	{
		final int n = s.n, hw = s.hw;
		final float fMean = mean( s.f ), offMean = mean( s.aOffset );
		double sum = 0;
		for ( int k = 0; k < n; ++k )
		{
			final float fm = fMean * s.a1Coeff[ k ] + offMean + 1e-6f;
			final float[] wk = s.wCoeff[ k ], ek = s.e[ k ];
			for ( int i = 0; i < hw; ++i )
			{
				wk[ i ] = 1.0f / ( Math.abs( ek[ i ] / fm ) + epsilon );
				sum += wk[ i ];
			}
		}
		final float scale = ( float ) ( ( double ) hw * n / sum );
		for ( final float[] wk : s.wCoeff )
			for ( int i = 0; i < hw; ++i )
				wk[ i ] *= scale;
	}

	// ─── Autotune (BaSiCPy's solver-free lambda selection) ─────────────────────

	static final double[] AUTOTUNE_COARSE = { 0.01, 0.1, 0.5, 2.0, 8.0, 10.0 };
	static final double[] AUTOTUNE_FINE = { 0.01, 0.1, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 10.0 };
	private static final double FOURIER_RADIUS = 10.0, FOURIER_TAU = 0.1, FOURIER_HINGE = 1e-3, FOURIER_COEF = 1e4;
	private static final int ENTROPY_BINS = 1000;
	private static final double VMIN_FACTOR = 0.6, VRANGE_FACTOR = 1.5;

	/** fraction of the flat field's high-frequency DCT coefficients above FOURIER_TAU (unnormalised DCT scale) */
	static double fourierL0( final float[] flat, final int h, final int w )
	{
		final int c = LAMBDA_CAL_SIZE;
		final float[] f = h == c && w == c ? flat : imresize( flat, h, w, c, c );
		final float[] coeff = dct2( f, c, c );
		final double scale = 2.0 * Math.sqrt( ( double ) c * c );
		long above = 0, outside = 0;
		for ( int u = 1; u < c; ++u )
			for ( int v = 1; v < c; ++v )
				if ( u * u + v * v > FOURIER_RADIUS * FOURIER_RADIUS )
				{
					++outside;
					if ( Math.abs( coeff[ u * c + v ] ) * scale > FOURIER_TAU )
						++above;
				}
		return ( double ) above / outside;
	}

	/** Shannon entropy of the density histogram of {@code values} over the fixed range [vmin, vmax] */
	static double histogramEntropy( final float[] values, final double vmin, final double vmax )
	{
		if ( !( vmax > vmin ) )
			return Double.POSITIVE_INFINITY;
		final long[] counts = new long[ ENTROPY_BINS ];
		long total = 0;
		final double width = ( vmax - vmin ) / ENTROPY_BINS;
		for ( final float v : values )
			if ( v >= vmin && v <= vmax )
			{
				final int b = Math.min( ( int ) ( ( v - vmin ) / width ), ENTROPY_BINS - 1 );
				++counts[ b ];
				++total;
			}
		if ( total == 0 )
			return Double.POSITIVE_INFINITY;
		double h = 0;
		for ( final long cnt : counts )
			if ( cnt > 0 )
			{
				final double p = cnt / ( total * width );
				h -= p * Math.log( p );
			}
		return h * width;
	}

	/** numpy's default (linear, R-7) quantile of a sorted copy */
	static double quantile( final float[] sorted, final double q )
	{
		final double pos = q * ( sorted.length - 1 );
		final int lo = ( int ) Math.floor( pos );
		final int hi = Math.min( lo + 1, sorted.length - 1 );
		return sorted[ lo ] + ( pos - lo ) * ( sorted[ hi ] - sorted[ lo ] );
	}

	/** max((stack - dark) / max(flat, eps), 0), all frames concatenated */
	private static float[] corrected( final float[][] stack, final Fields fl )
	{
		final int hw = fl.h * fl.w;
		final float[] out = new float[ stack.length * hw ];
		for ( int k = 0; k < stack.length; ++k )
			for ( int i = 0; i < hw; ++i )
				out[ k * hw + i ] = Math.max( ( stack[ k ][ i ] - fl.dark[ i ] ) / Math.max( fl.flat[ i ], EPS ), 0 );
		return out;
	}

	private static double cost( final float[] corrected, final Fields fl, final double vRange )
	{
		final float[] sorted = corrected.clone();
		Arrays.sort( sorted );
		final double vmin = quantile( sorted, 0.01 ) * VMIN_FACTOR;
		final double hinge = Math.max( 0.0, fourierL0( fl.flat, fl.h, fl.w ) - FOURIER_HINGE ) * FOURIER_COEF;
		return histogramEntropy( corrected, vmin, vmin + vRange ) + hinge;
	}

	/**
	 * Grid search over multiples of the l1/800 default, fitted at {@link #LAMBDA_CAL_SIZE}.
	 *
	 * @return {lambda, multiplier}
	 */
	public static double[] autotuneLambda( final float[][] images, final int h, final int w, final Parameters p )
	{
		final int c = LAMBDA_CAL_SIZE;
		final int n = images.length;
		final float[][] small = new float[ n ][];
		for ( int k = 0; k < n; ++k )
			small[ k ] = imresize( images[ k ], h, w, c, c );
		final float gm = mean( small );
		final float[] meanImg = new float[ c * c ];
		for ( int i = 0; i < c * c; ++i )
		{
			double s = 0;
			for ( int k = 0; k < n; ++k )
				s += small[ k ][ i ];
			meanImg[ i ] = ( float ) ( s / n ) / gm;
		}
		final double lamDefault = l1Dct( meanImg, c, c ) / 800.0;

		final Parameters fit = p.copy();
		fit.workingSize = 0;
		final Map< Double, Double > costs = new LinkedHashMap<>();

		fit.lambda = lamDefault;
		final Fields ref = basicEstimate( small, c, c, fit, c, c );
		final float[] refCorrected = corrected( small, ref );
		final float[] sorted = refCorrected.clone();
		Arrays.sort( sorted );
		final double vminRef = quantile( sorted, 0.01 ), vmaxRef = quantile( sorted, 0.99 );
		final double vRange = ( vmaxRef - vminRef * VMIN_FACTOR ) * VRANGE_FACTOR;
		costs.put( 1.0, cost( refCorrected, ref, vRange ) );

		final DoubleUnaryOperator costOf = mult -> costs.computeIfAbsent( mult, m -> {
			fit.lambda = m * lamDefault;
			final Fields fl = basicEstimate( small, c, c, fit, c, c );
			return cost( corrected( small, fl ), fl, vRange );
		} );

		int best = 0;
		for ( int i = 0; i < AUTOTUNE_COARSE.length; ++i )
			if ( costOf.applyAsDouble( AUTOTUNE_COARSE[ i ] ) < costOf.applyAsDouble( AUTOTUNE_COARSE[ best ] ) )
				best = i;
		final List< Double > neighbours = new ArrayList<>();
		for ( final int j : new int[] { best - 1, best + 1 } )
			if ( j >= 0 && j < AUTOTUNE_COARSE.length )
				neighbours.add( AUTOTUNE_COARSE[ j ] );
		double nb = neighbours.get( 0 );
		for ( final double v : neighbours )
			if ( costOf.applyAsDouble( v ) < costOf.applyAsDouble( nb ) )
				nb = v;
		final double lo = Math.min( AUTOTUNE_COARSE[ best ], nb ), hi = Math.max( AUTOTUNE_COARSE[ best ], nb );
		for ( final double m : AUTOTUNE_FINE )
			if ( lo <= m && m <= hi )
				costOf.applyAsDouble( m );

		// over every candidate scored, not just the bracket
		double bestMult = 1.0;
		for ( final Map.Entry< Double, Double > e : costs.entrySet() )
			if ( e.getValue() < costs.get( bestMult ) )
				bestMult = e.getKey();
		if ( bestMult == AUTOTUNE_FINE[ 0 ] || bestMult == AUTOTUNE_FINE[ AUTOTUNE_FINE.length - 1 ] )
			IOFunctions.println( "warning: BaSiC autotune chose " + bestMult + "x, the edge of its grid, so the best lambda may lie outside it." );
		return new double[] { bestMult * lamDefault, bestMult };
	}
}
