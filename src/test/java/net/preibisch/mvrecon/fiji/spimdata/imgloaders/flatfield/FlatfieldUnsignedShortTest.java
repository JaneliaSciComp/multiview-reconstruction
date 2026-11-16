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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import net.imglib2.FinalInterval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.algorithm.blocks.ClampType;
import net.imglib2.algorithm.blocks.convert.Convert;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.basictypeaccess.array.ShortArray;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;

public class FlatfieldUnsignedShortTest
{
	/** the one-pass 16-bit correction gives exactly the values of the float correction converted with clamping */
	@Test
	public void sameAsFloatCorrectionClamped()
	{
		final int w = 37, h = 29, d = 11;
		final Random rnd = new Random( 7 );
		final short[] raw = new short[ w * h * d ];
		for ( int i = 0; i < raw.length; ++i )
			raw[ i ] = ( short ) rnd.nextInt( 65536 ); // also below the dark field and near the 16-bit maximum
		final float[] bright = new float[ w * h ], dark = new float[ w * h ];
		for ( int i = 0; i < bright.length; ++i )
		{
			dark[ i ] = 80 + 40 * rnd.nextFloat();
			bright[ i ] = i % 50 == 0 ? dark[ i ] : dark[ i ] + 200 + 4000 * rnd.nextFloat(); // some bright == dark
		}
		final ArrayImg< UnsignedShortType, ShortArray > src = ArrayImgs.unsignedShorts( raw, w, h, d );
		final ArrayImg< FloatType, FloatArray > b = ArrayImgs.floats( bright, w, h ), k = ArrayImgs.floats( dark, w, h );

		for ( int fields = 0; fields < 3; ++fields ) // both, bright only, dark only
		{
			final ArrayImg< FloatType, FloatArray > bb = fields == 2 ? null : b, kk = fields == 1 ? null : k;
			final FinalInterval block = new FinalInterval( new long[] { 3, 2, 1 }, new long[] { w - 2, h - 3, d - 2 } );
			final short[] expected = new short[ ( w - 4 ) * ( h - 4 ) * ( d - 2 ) ], actual = new short[ expected.length ];
			FlatfieldCorrectionBlockSupplier.of( src, bb, kk ).andThen( Convert.convert( new UnsignedShortType(), ClampType.CLAMP ) ).copy( block, expected );
			FlatfieldCorrectionBlockSupplier.ofUnsignedShort( src, bb, kk ).threadSafe().copy( block, actual );
			assertArrayEquals( expected, actual );
		}
	}

	/** blocks of different sizes copied from many threads through threadSafe() equal the single-threaded result */
	@Test
	public void threadSafeUnderConcurrentCopies() throws Exception
	{
		final int w = 96, h = 80, d = 24;
		final Random rnd = new Random( 3 );
		final short[] raw = new short[ w * h * d ];
		for ( int i = 0; i < raw.length; ++i )
			raw[ i ] = ( short ) ( 200 + rnd.nextInt( 2000 ) );
		final float[] bright = new float[ w * h ], dark = new float[ w * h ];
		for ( int i = 0; i < bright.length; ++i )
		{
			dark[ i ] = 80 + 40 * rnd.nextFloat();
			bright[ i ] = dark[ i ] + 200 + 4000 * rnd.nextFloat();
		}
		final ArrayImg< UnsignedShortType, ShortArray > src = ArrayImgs.unsignedShorts( raw, w, h, d );
		final ArrayImg< FloatType, FloatArray > b = ArrayImgs.floats( bright, w, h ), k = ArrayImgs.floats( dark, w, h );

		final List< FinalInterval > blocks = new ArrayList<>();
		for ( int n = 0; n < 400; ++n )
		{
			final long x = rnd.nextInt( w - 40 ), y = rnd.nextInt( h - 40 ), z = rnd.nextInt( d - 8 );
			blocks.add( new FinalInterval( new long[] { x, y, z }, new long[] { x + 8 + rnd.nextInt( 32 ), y + 8 + rnd.nextInt( 32 ), z + rnd.nextInt( 8 ) } ) );
		}

		for ( final boolean unsignedShort : new boolean[] { true, false } )
		{
			final BlockSupplier< ? > supplier = unsignedShort ? FlatfieldCorrectionBlockSupplier.ofUnsignedShort( src, b, k ) : FlatfieldCorrectionBlockSupplier.of( src, b, k );
			final Function< FinalInterval, Object > copy = block -> {
				final int len = ( int ) Intervals.numElements( block );
				final Object dest = unsignedShort ? new short[ len ] : new float[ len ];
				supplier.threadSafe().copy( block, dest );
				return dest;
			};
			final List< Object > expected = new ArrayList<>();
			for ( final FinalInterval block : blocks )
				expected.add( copy.apply( block ) );

			final ExecutorService pool = Executors.newFixedThreadPool( 8 );
			try
			{
				final BlockSupplier< ? > shared = supplier.threadSafe();
				final List< Future< Object > > futures = new ArrayList<>();
				for ( int rep = 0; rep < 20; ++rep )
					for ( final FinalInterval block : blocks )
						futures.add( pool.submit( () -> {
							final int len = ( int ) Intervals.numElements( block );
							final Object dest = unsignedShort ? new short[ len ] : new float[ len ];
							shared.copy( block, dest );
							return dest;
						} ) );
				for ( int i = 0; i < futures.size(); ++i )
					if ( unsignedShort )
						assertArrayEquals( ( short[] ) expected.get( i % blocks.size() ), ( short[] ) futures.get( i ).get() );
					else
						assertArrayEquals( ( float[] ) expected.get( i % blocks.size() ), ( float[] ) futures.get( i ).get(), 0f );
			}
			finally
			{
				pool.shutdownNow();
			}
		}
	}
}
