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
package net.preibisch.mvrecon.process.fusion.intensity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import net.imglib2.img.array.ArrayImgs;

public class AutoThresholdTest
{
	/** dim background (~5), foreground (~150) and a few very bright voxels, as in deconvolved light-sheet tiles */
	private static float[] values( final double outlier )
	{
		final Random rnd = new Random( 3 );
		final float[] v = new float[ 200_000 ];
		for ( int i = 0; i < v.length; ++i )
			v[ i ] = ( float ) ( i % 5 == 0 ? 150 + 40 * rnd.nextGaussian() : Math.abs( 5 + 3 * rnd.nextGaussian() ) );
		for ( int i = 0; i < 20; ++i )
			v[ i * 997 ] = ( float ) outlier;
		return v;
	}

	/** Li by the iteration of skimage's threshold_li, directly on the values */
	private static double liDirect( final float[] v )
	{
		double min = Double.POSITIVE_INFINITY, sum = 0;
		for ( final float x : v )
			min = Math.min( min, x );
		for ( final float x : v )
			sum += x - min;
		double next = sum / v.length, current = -1;
		while ( Math.abs( next - current ) > 1e-3 )
		{
			current = next;
			double sb = 0, nb = 0, sf = 0, nf = 0;
			for ( final float x : v )
				if ( x - min > current ) { sf += x - min; ++nf; } else { sb += x - min; ++nb; }
			next = ( sb / nb - sf / nf ) / ( Math.log( sb / nb ) - Math.log( sf / nf ) );
		}
		return next + min;
	}

	// 256 bins over [0, 83887] made each bin 328 wide and LI returned exactly one bin width (worm tile 2: 327.7)
	@Test
	public void brightOutliersDoNotQuantizeTheSplitAway()
	{
		final float[] v = values( 83887 );
		final double outliers = AutoThreshold.compute( AutoThreshold.Method.LI, ArrayImgs.floats( v.clone(), v.length ) );
		assertTrue( outliers > 10 && outliers < 100, "LI between background and foreground, was " + outliers );
		// the outliers do raise the foreground mean, and so LI, but only as on the values themselves (no bin floor)
		assertEquals( liDirect( v ), outliers, 1.5 );
		// (OTSU, by its definition, would split such outliers off; without them it splits background / foreground)
		final double otsu = AutoThreshold.compute( AutoThreshold.Method.OTSU, ArrayImgs.floats( values( 1000 ), 200_000 ) );
		assertTrue( otsu > 10 && otsu < 150, "OTSU between background and foreground, was " + otsu );
	}

	@Test
	public void liMatchesTheDirectIteration()
	{
		final float[] v = values( 1000 );
		assertEquals( liDirect( v ), AutoThreshold.compute( AutoThreshold.Method.LI, ArrayImgs.floats( v.clone(), v.length ) ), 1.0 );
	}

	// ~3.5G voxels, mostly background: ImageJ's int histogram overflowed here
	@Test
	public void largeCountsNeedNoScaling()
	{
		final long[] large = new long[ 256 ];
		final long[] small = new long[ 256 ];
		for ( int i = 0; i < 256; ++i )
		{
			large[ i ] = i < 20 ? 150_000_000L : 2_000_000L;
			small[ i ] = i < 20 ? 75 : 1;
		}
		assertEquals( AutoThreshold.li( small, 0, 1 ), AutoThreshold.li( large, 0, 1 ), 1e-9 );
		assertEquals( AutoThreshold.otsu( small, 0, 1 ), AutoThreshold.otsu( large, 0, 1 ), 1e-9 );
	}
}
