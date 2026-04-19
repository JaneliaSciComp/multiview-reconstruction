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
package net.preibisch.mvrecon.process.fusion.transformed.weights;

import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RealRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Util;

public class TransformedRasteredRandomAccessible< T > implements RandomAccessible< T >
{
	final RealRandomAccessible< T > realRandomAccessible;
	final T zero;
	final AffineTransform3D transform;
	final long[] offset;

	/**
	 * @param realRandomAccessible - some {@link RealRandomAccessible} that we transform
	 * @param transform - the affine transformation
	 * @param offset - an additional translational offset
	 * @param zero - the zero constant
	 */
	public TransformedRasteredRandomAccessible(
			final RealRandomAccessible< T > realRandomAccessible,
			final T zero,
			final AffineTransform3D transform,
			final long[] offset )
	{
		this.realRandomAccessible = realRandomAccessible;
		this.zero = zero;
		this.transform = transform;
		this.offset = offset;
	}

	@Override
	public int numDimensions() { return realRandomAccessible.numDimensions(); }

	@Override
	public RandomAccess< T > randomAccess()
	{
		return new TransformedRasteredRandomAccess< T >( realRandomAccessible, zero, transform, Util.long2int( offset ) );
	}

	@Override
	public RandomAccess< T > randomAccess( final Interval interval ) { return randomAccess(); }

	@Override
	public T getType()
	{
		return realRandomAccessible.getType();
	}
}
