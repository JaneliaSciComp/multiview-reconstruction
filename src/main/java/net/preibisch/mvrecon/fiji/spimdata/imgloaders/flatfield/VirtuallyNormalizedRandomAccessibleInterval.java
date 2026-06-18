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
import net.imglib2.Interval;
import net.imglib2.Point;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Sampler;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Pair;
import net.imglib2.view.Views;

public class VirtuallyNormalizedRandomAccessibleInterval <T extends RealType< T >> extends AbstractInterval implements RandomAccessibleInterval< T >
{

	private final double minValue;
	private final double maxValue;
	private final RandomAccessibleInterval< T > input;
	
	public VirtuallyNormalizedRandomAccessibleInterval(RandomAccessibleInterval< T > input)
	{
		super( input );
		this.input = input;
		
		// TODO: slow, do MT?
		final Pair< Double, Double > minMax = FlatFieldCorrectedRandomAccessibleInterval.getMinMax( input );
		minValue = minMax.getA();
		maxValue = minMax.getB();
	}

	@Override
	public RandomAccess< T > randomAccess()
	{
		return new VirtuallyNormalizedRandomAccess();
	}

	@Override
	public RandomAccess< T > randomAccess(Interval interval)
	{
		return randomAccess();
	}

	@Override
	public T getType()
	{
		return input.getType();
	}

	private class VirtuallyNormalizedRandomAccess extends Point implements RandomAccess< T >
	{
		private final T value;
		private final RandomAccess< T > inputRA;

		public VirtuallyNormalizedRandomAccess()
		{
			super(input.numDimensions());
			this.value = Views.iterable( input ).firstElement().createVariable();
			inputRA = input.randomAccess();
		}
		
		@Override
		public T get()
		{
			inputRA.setPosition( this );			
			double inValue = inputRA.get().getRealDouble();

			value.setReal( (inValue - minValue ) / ( maxValue - minValue ) );			
			return value;
		}

		@Override
		public RandomAccess< T > copy()
		{
			return new VirtuallyNormalizedRandomAccess();
		}
		
	}
	

}
