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
package util;

import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

public class ImgLib2Tools
{
	public static final < T extends RealType< T > > RandomAccessibleInterval<FloatType> convertVirtualRAI( final RandomAccessibleInterval< T > img )
	{
		return Converters.convertRAI( img, (i,o) -> o.set( i.getRealFloat() ), new FloatType());
	}

	public static final < T extends RealType< T > > RandomAccessible<FloatType> convertVirtual( final RandomAccessible< T > img )
	{
		return Converters.convert( img, (i,o) -> o.set( i.getRealFloat() ), new FloatType());
	}

	public static final < T extends RealType< T > > RandomAccessible<FloatType> normalizeVirtual( final RandomAccessible< T > img, final double min, final double max )
	{
		final float minf = (float)min;
		final float maxf = (float)max;
		
		return Converters.convert(
				img,
				( input, output ) -> output.set( ( input.getRealFloat() - minf ) / ( maxf - minf ) ),
				new FloatType() );
	}

	public static final < T extends RealType< T > > RandomAccessibleInterval<FloatType> normalizeVirtualRAI( final RandomAccessibleInterval< T > img, final double min, final double max )
	{
		final float minf = (float)min;
		final float maxf = (float)max;
		
		return Converters.convert(
				img,
				( input, output ) -> output.set( ( input.getRealFloat() - minf ) / ( maxf - minf ) ),
				new FloatType() );
	}

	public static final < T extends RealType< T > > RandomAccessibleInterval<FloatType> normalizeVirtualRAI( final RandomAccessibleInterval< T > img )
	{
		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;

		for ( final T t : Views.flatIterable( img ) )
		{
			final double v = t.getRealDouble();

			if ( v < min )
				min = v;

			if ( v > max )
				max = v;
		}

		return normalizeVirtualRAI( img, min, max );
	}
}
