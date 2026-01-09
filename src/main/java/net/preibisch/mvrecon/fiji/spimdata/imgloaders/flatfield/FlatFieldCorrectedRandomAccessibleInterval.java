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

import net.imglib2.AbstractInterval;
import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.Point;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Pair;
import net.imglib2.util.RealSum;
import net.imglib2.util.ValuePair;

/*
 * 
 */
public class FlatFieldCorrectedRandomAccessibleInterval <O extends RealType< O >, T extends RealType<T>, S extends RealType<S>, R extends RealType<R>> extends AbstractInterval implements RandomAccessibleInterval< O >
{
	private final RandomAccessibleInterval< T > sourceImg;
	private final RandomAccessibleInterval< S > brightImg;
	private final RandomAccessibleInterval< R > darkImg;
	private final double meanBrightCorrected;
	private final O type;

	/*
	 * TODO: add option to not drop last dimension (for 2D input)
	 */
	
	public FlatFieldCorrectedRandomAccessibleInterval(O outputType, RandomAccessibleInterval< T > sourceImg, RandomAccessibleInterval< S > brightImg, RandomAccessibleInterval< R > darkImg)
	{
		super( sourceImg );
		this.sourceImg = sourceImg;
		this.brightImg = brightImg;
		this.darkImg = darkImg;

		if (brightImg.numDimensions() > sourceImg.numDimensions() || darkImg.numDimensions() > sourceImg.numDimensions())
			throw new IllegalArgumentException("Bright-/darkfield images have more dimensions than source image!");

		meanBrightCorrected = getMeanCorrected( brightImg, darkImg );
		type = outputType;
	}

	@Override
	public RandomAccess< O > randomAccess()
	{
		return new FlatFieldCorrectedRandomAccess();
	}

	@Override
	public RandomAccess< O > randomAccess(Interval interval)
	{
		return randomAccess();
	}

	@Override
	public O getType()
	{
		return type;
	}

	private class FlatFieldCorrectedRandomAccess extends Point implements RandomAccess< O >
	{
		/*
		 * TODO: manually implement move methods
		 */
		
		private final RandomAccess< T > sourceRA;
		private final RandomAccess< S > brightRA;
		private final RandomAccess< R > darkRA;
		private final O value;

		private final int nDimBright;
		private final int nDimDark;

		public FlatFieldCorrectedRandomAccess()
		{
			super( sourceImg.numDimensions() );
			sourceRA = sourceImg.randomAccess();
			brightRA = brightImg.randomAccess();
			darkRA = darkImg.randomAccess();
			value = type.createVariable();
			nDimBright = brightImg.numDimensions();
			nDimDark = darkImg.numDimensions();
		}

		@Override
		public O get()
		{
			// Use the fact that bright and dark must be of dimensionality <= source,
			// and that coordinates outside the dimensionality are ignored
			sourceRA.setPosition(position);
			brightRA.setPosition(position);
			darkRA.setPosition(position);

			final double darkValue = darkRA.get().getRealDouble();
			final double corrBright = brightRA.get().getRealDouble() - darkValue;
			final double corrImg = sourceRA.get().getRealDouble() - darkValue;

			if (corrBright == 0)
				value.setReal( 0.0 );
			else
			{
				final double corr = Math.min( Math.max( corrImg * meanBrightCorrected / corrBright, value.getMinValue() ), value.getMaxValue() );
				value.setReal( corr );
			}

			return value;
		}

		@Override
		public RandomAccess< O > copy()
		{
			final FlatFieldCorrectedRandomAccessibleInterval<O, T, S, R >.FlatFieldCorrectedRandomAccess copy = new FlatFieldCorrectedRandomAccess();
			copy.setPosition( this );
			return copy;
		}
		
	}
	
	public static <P extends RealType< P >, Q extends RealType< Q >> double getMeanCorrected(RandomAccessibleInterval< P > brightImg, RandomAccessibleInterval< Q > darkImg)
	{
		final RealSum sum = new RealSum();
		long count = 0;
		
		final Cursor< P > brightCursor = brightImg.cursor();
		final RandomAccess< Q > darkRA = darkImg.randomAccess();
		
		while (brightCursor.hasNext())
		{
			brightCursor.fwd();
			darkRA.setPosition( brightCursor );
			sum.add( brightCursor.get().getRealDouble() - darkRA.get().getRealDouble());
			count++;
		}
		
		if (count == 0)
			return 0.0;
		else
			return sum.getSum() / count;
		
	}
	
	
	public static <P extends RealType< P >> Pair<Double, Double> getMinMax(RandomAccessibleInterval< P > img)
	{
		double min = Double.MAX_VALUE;
		double max = - Double.MAX_VALUE;

		for (final P pixel : img)
		{
			double value = pixel.getRealDouble();
			
			if (value > max)
				max = value;
			
			if (value < min)
				min = value;
		}
		
		return new ValuePair<>( min, max );
	}

}
