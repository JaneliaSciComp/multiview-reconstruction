package net.preibisch.mvrecon.process.flatfield;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

public class BasicTest
{
	/**
	 * Frames = vignetting x specimen texture x per-frame brightness + a constant pedestal. BaSiC must recover the
	 * vignetting (mean 1) to a few percent (rms) and the pedestal to within its noise. Parity with spotlight's basic.py on
	 * the same kind of stack was checked separately (flat within 3e-6, dark within 1e-4 relative, same autotuned lambda).
	 */
	@Test
	public void recoversVignettingAndPedestal()
	{
		final int h = 48, w = 64, n = 21;
		final float[] truth = new float[ h * w ];
		double sum = 0;
		for ( int y = 0; y < h; ++y )
			for ( int x = 0; x < w; ++x )
			{
				final double u = 2.0 * x / ( w - 1 ) - 1, v = 2.0 * y / ( h - 1 ) - 1;
				sum += truth[ y * w + x ] = ( float ) ( 1 - 0.35 * ( u * u + 0.7 * v * v ) + 0.1 * u );
			}
		for ( int i = 0; i < truth.length; ++i )
			truth[ i ] /= sum / truth.length;

		final Random rnd = new Random( 7 );
		final float[][] frames = new float[ n ][ h * w ];
		for ( int k = 0; k < n; ++k )
			for ( int i = 0; i < h * w; ++i )
				frames[ k ][ i ] = ( float ) ( truth[ i ] * ( 0.5 + rnd.nextDouble() ) * ( 200 + 65 * k ) + 80 );

		final Basic.Parameters p = new Basic.Parameters();
		p.autotune = false;
		final Basic.Fields f = Basic.estimate( frames, h, w, p, h, w );

		double rms = 0, dark = 0;
		for ( int i = 0; i < h * w; ++i )
		{
			final double r = f.flat[ i ] / truth[ i ] - 1;
			rms += r * r / ( h * w );
			dark += f.dark[ i ] / ( h * w );
		}
		rms = Math.sqrt( rms );
		System.out.println( "BasicTest: flat rms error " + rms + ", dark " + dark );
		assertTrue( rms < 0.05, "flat off by " + rms + " (rms)" );
		assertTrue( Math.abs( dark - 80 ) < 20, "dark " + dark );
	}
}
