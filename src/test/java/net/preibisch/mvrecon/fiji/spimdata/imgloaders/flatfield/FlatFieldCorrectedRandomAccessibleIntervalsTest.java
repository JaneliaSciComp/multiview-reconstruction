package net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.ShortArray;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;

public class FlatFieldCorrectedRandomAccessibleIntervalsTest
{
	/**
	 * dark 100, bright 1100 everywhere: corrected = raw - 100. A voxel below the dark level must clamp to 0 in uint16,
	 * not wrap (50 - 100 = -50 -> 65486).
	 */
	@Test
	public void integerOutputClampsInsteadOfWrapping()
	{
		final ArrayImg< UnsignedShortType, ShortArray > raw = ArrayImgs.unsignedShorts( 4, 4, 2 );
		raw.forEach( v -> v.set( 600 ) );
		raw.randomAccess().setPositionAndGet( 1, 2, 1 ).set( 50 );

		final ArrayImg< FloatType, ? > dark = ArrayImgs.floats( 4, 4 );
		dark.forEach( v -> v.set( 100 ) );
		final ArrayImg< FloatType, ? > bright = ArrayImgs.floats( 4, 4 );
		bright.forEach( v -> v.set( 1100 ) );

		final RandomAccessibleInterval< UnsignedShortType > corrected =
				FlatFieldCorrectedRandomAccessibleIntervals.create( raw, bright, dark, new UnsignedShortType() );

		assertEquals( 500, corrected.randomAccess().setPositionAndGet( 0, 0, 0 ).get() );
		assertEquals( 0, corrected.randomAccess().setPositionAndGet( 1, 2, 1 ).get() );
	}

	/** bright only (a gain field, no dark image): corrected = raw * mean(bright) / bright */
	@Test
	public void brightOnly()
	{
		final ArrayImg< UnsignedShortType, ShortArray > raw = ArrayImgs.unsignedShorts( 4, 4, 2 );
		raw.forEach( v -> v.set( 600 ) );
		final ArrayImg< FloatType, ? > bright = ArrayImgs.floats( 4, 4 );
		bright.forEach( v -> v.set( 1 ) );
		bright.randomAccess().setPositionAndGet( 3, 0 ).set( 3 ); // mean = (15 + 3) / 16 = 1.125

		final RandomAccessibleInterval< FloatType > corrected =
				FlatFieldCorrectedRandomAccessibleIntervals.create( raw, bright, null, new FloatType() );

		assertEquals( 600 * 1.125, corrected.randomAccess().setPositionAndGet( 0, 0, 1 ).get(), 1e-3 );
		assertEquals( 600 * 1.125 / 3, corrected.randomAccess().setPositionAndGet( 3, 0, 1 ).get(), 1e-3 );
	}
}
