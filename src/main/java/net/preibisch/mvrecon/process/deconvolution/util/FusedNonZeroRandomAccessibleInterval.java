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
package net.preibisch.mvrecon.process.deconvolution.util;

import java.util.List;
import java.util.Vector;

import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.process.fusion.transformed.FusedRandomAccessibleInterval;

public class FusedNonZeroRandomAccessibleInterval extends FusedRandomAccessibleInterval
{
	final Vector< FusedNonZeroRandomAccess > accesses;

	public FusedNonZeroRandomAccessibleInterval(
			final Interval interval,
			final List< ? extends RandomAccessible< FloatType > > images,
			final List< ? extends RandomAccessible< FloatType > > weights )
	{
		super( interval, Fusion.AVG, images, weights );

		this.accesses = new Vector<>();
	}

	public Vector< FusedNonZeroRandomAccess > getAllAccesses() { return accesses; }

	@Override
	public RandomAccess< FloatType > randomAccess()
	{
		final FusedNonZeroRandomAccess r = new FusedNonZeroRandomAccess( numDimensions(), getImages(), getWeights() );

		accesses.add( r );

		return r;
	}
}
