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
import net.imglib2.Localizable;
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

	private class FlatFieldCorrectedRandomAccess implements RandomAccess<O>
	{
		private final RandomAccess<T> sourceRA;
		private final RandomAccess<S> brightRA;
		private final RandomAccess<R> darkRA;
		private final O value;

		private final int nDimSource;
		private final int nDimBright;
		private final int nDimDark;

		// Cached min/max values to avoid virtual method calls in get()
		private final double minValue;
		private final double maxValue;

		public FlatFieldCorrectedRandomAccess()
		{
			sourceRA = sourceImg.randomAccess();
			brightRA = brightImg.randomAccess();
			darkRA = darkImg.randomAccess();
			value = type.createVariable();
			nDimSource = sourceImg.numDimensions();
			nDimBright = brightImg.numDimensions();
			nDimDark = darkImg.numDimensions();
			minValue = value.getMinValue();
			maxValue = value.getMaxValue();
		}

		@Override
		public O get()
		{
			final double darkValue = darkRA.get().getRealDouble();
			final double corrBright = brightRA.get().getRealDouble() - darkValue;
			final double corrImg = sourceRA.get().getRealDouble() - darkValue;

			if (corrBright == 0)
				value.setReal(0.0);
			else
			{
				final double corr = Math.min(Math.max(corrImg * meanBrightCorrected / corrBright, minValue), maxValue);
				value.setReal(corr);
			}

			return value;
		}

		@Override
		public void fwd(int d)
		{
			sourceRA.fwd(d);
			if (d < nDimBright) brightRA.fwd(d);
			if (d < nDimDark) darkRA.fwd(d);
		}

		@Override
		public void bck(int d)
		{
			sourceRA.bck(d);
			if (d < nDimBright) brightRA.bck(d);
			if (d < nDimDark) darkRA.bck(d);
		}

		@Override
		public void move(int distance, int d)
		{
			sourceRA.move(distance, d);
			if (d < nDimBright) brightRA.move(distance, d);
			if (d < nDimDark) darkRA.move(distance, d);
		}

		@Override
		public void move(long distance, int d)
		{
			sourceRA.move(distance, d);
			if (d < nDimBright) brightRA.move(distance, d);
			if (d < nDimDark) darkRA.move(distance, d);
		}

		@Override
		public void move(Localizable distance)
		{
			sourceRA.move(distance);
			for (int d = 0; d < nDimBright; d++)
				brightRA.move(distance.getLongPosition(d), d);
			for (int d = 0; d < nDimDark; d++)
				darkRA.move(distance.getLongPosition(d), d);
		}

		@Override
		public void move(int[] distance)
		{
			sourceRA.move(distance);
			for (int d = 0; d < nDimBright; d++)
				brightRA.move(distance[d], d);
			for (int d = 0; d < nDimDark; d++)
				darkRA.move(distance[d], d);
		}

		@Override
		public void move(long[] distance)
		{
			sourceRA.move(distance);
			for (int d = 0; d < nDimBright; d++)
				brightRA.move(distance[d], d);
			for (int d = 0; d < nDimDark; d++)
				darkRA.move(distance[d], d);
		}

		@Override
		public void setPosition(Localizable position)
		{
			sourceRA.setPosition(position);
			for (int d = 0; d < nDimBright; d++)
				brightRA.setPosition(position.getLongPosition(d), d);
			for (int d = 0; d < nDimDark; d++)
				darkRA.setPosition(position.getLongPosition(d), d);
		}

		@Override
		public void setPosition(int[] position)
		{
			sourceRA.setPosition(position);
			for (int d = 0; d < nDimBright; d++)
				brightRA.setPosition(position[d], d);
			for (int d = 0; d < nDimDark; d++)
				darkRA.setPosition(position[d], d);
		}

		@Override
		public void setPosition(long[] position)
		{
			sourceRA.setPosition(position);
			for (int d = 0; d < nDimBright; d++)
				brightRA.setPosition(position[d], d);
			for (int d = 0; d < nDimDark; d++)
				darkRA.setPosition(position[d], d);
		}

		@Override
		public void setPosition(int position, int d)
		{
			sourceRA.setPosition(position, d);
			if (d < nDimBright) brightRA.setPosition(position, d);
			if (d < nDimDark) darkRA.setPosition(position, d);
		}

		@Override
		public void setPosition(long position, int d)
		{
			sourceRA.setPosition(position, d);
			if (d < nDimBright) brightRA.setPosition(position, d);
			if (d < nDimDark) darkRA.setPosition(position, d);
		}

		// Localizable - delegate to sourceRA

		@Override
		public int numDimensions()
		{
			return nDimSource;
		}

		@Override
		public void localize(int[] position)
		{
			sourceRA.localize(position);
		}

		@Override
		public void localize(long[] position)
		{
			sourceRA.localize(position);
		}

		@Override
		public int getIntPosition(int d)
		{
			return sourceRA.getIntPosition(d);
		}

		@Override
		public long getLongPosition(int d)
		{
			return sourceRA.getLongPosition(d);
		}

		@Override
		public void localize(float[] position)
		{
			sourceRA.localize(position);
		}

		@Override
		public void localize(double[] position)
		{
			sourceRA.localize(position);
		}

		@Override
		public float getFloatPosition(int d)
		{
			return sourceRA.getFloatPosition(d);
		}

		@Override
		public double getDoublePosition(int d)
		{
			return sourceRA.getDoublePosition(d);
		}

		@Override
		public RandomAccess<O> copy()
		{
			final FlatFieldCorrectedRandomAccessibleInterval<O, T, S, R>.FlatFieldCorrectedRandomAccess copy = new FlatFieldCorrectedRandomAccess();
			copy.setPosition(sourceRA);
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
