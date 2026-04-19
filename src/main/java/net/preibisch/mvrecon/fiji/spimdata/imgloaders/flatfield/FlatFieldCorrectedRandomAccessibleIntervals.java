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

import java.util.Arrays;

import bdv.util.ConstantRandomAccessible;
import bdv.viewer.overlay.SourceInfoOverlayRenderer;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

public class FlatFieldCorrectedRandomAccessibleIntervals
{
	public static < R extends RealType< R >, S extends RealType< S >, T extends RealType< T >> RandomAccessibleInterval< R > create(
			RandomAccessibleInterval< R > sourceImg,
			RandomAccessibleInterval< S > brightImg,
			RandomAccessibleInterval< T > darkImg )
	{
		R type = Views.iterable( sourceImg ).firstElement().createVariable();
		return create( sourceImg, brightImg, darkImg, type );
	}
	public static <O extends RealType< O >, R extends RealType< R >, S extends RealType< S >, T extends RealType< T >> RandomAccessibleInterval< O > create(
			RandomAccessibleInterval< R > sourceImg,
			RandomAccessibleInterval< S > brightImg,
			RandomAccessibleInterval< T > darkImg,
			O outputType)
	{

		// get intervals for bright and/or dark imgs: interval of source img, but only for dimensionality of bright/dark
		final long[] minsBright;
		final long[] maxsBright;
		FinalInterval intervalBright = null;
		if (brightImg != null)
		{
			minsBright = new long[brightImg.numDimensions()];
			maxsBright = new long[brightImg.numDimensions()];
			Arrays.fill( maxsBright, 1 );
			for (int d = 0; d < brightImg.numDimensions(); ++d)
			{
				minsBright[d] = sourceImg.min( d );
				maxsBright[d] = sourceImg.max( d );
			}
			intervalBright = new FinalInterval( minsBright, maxsBright );
		}
		final long[] minsDark;
		final long[] maxsDark;
		FinalInterval intervalDark = null;
		if (darkImg != null)
		{
			minsDark = new long[darkImg.numDimensions()];
			maxsDark = new long[darkImg.numDimensions()];
			Arrays.fill( maxsDark, 1 );
			for (int d = 0; d < darkImg.numDimensions(); ++d)
			{
				minsDark[d] = sourceImg.min( d );
				maxsDark[d] = sourceImg.max( d );
			}
			intervalDark = new FinalInterval( minsDark, maxsDark );
		}

		if (brightImg == null && darkImg == null)
		{
			// assume bright and dark images constant -> should return original
			// TODO: 'optimize' by really returning sourceImg?
			final ConstantRandomAccessible< FloatType > constantBright = new ConstantRandomAccessible<FloatType>( new FloatType(1.0f), sourceImg.numDimensions() );
			final ConstantRandomAccessible< FloatType > constantDark = new ConstantRandomAccessible<FloatType>( new FloatType(0.0f), sourceImg.numDimensions() );
			return new FlatFieldCorrectedRandomAccessibleInterval<>(outputType, sourceImg, Views.interval( constantBright, sourceImg ), Views.interval( constantDark, sourceImg ) );
		}
		else if (brightImg == null)
		{
			// assume bright image == constant
			final ConstantRandomAccessible< FloatType > constantBright = new ConstantRandomAccessible<FloatType>( new FloatType(1.0f), sourceImg.numDimensions() );
			return new FlatFieldCorrectedRandomAccessibleInterval<>(outputType, sourceImg, Views.interval( constantBright, sourceImg ), Views.interval( Views.extendBorder( darkImg ), intervalDark ) );
		}
		else if (darkImg == null)
		{
			// assume dark image == constant == 0;
			final ConstantRandomAccessible< FloatType > constantDark = new ConstantRandomAccessible<FloatType>( new FloatType(0.0f), sourceImg.numDimensions() );
			return new FlatFieldCorrectedRandomAccessibleInterval<>(outputType, sourceImg, Views.interval( Views.extendBorder( brightImg ), intervalBright ), Views.interval( constantDark, sourceImg ) );
		}
			
		return new FlatFieldCorrectedRandomAccessibleInterval<>(outputType, sourceImg, Views.interval( Views.extendBorder( brightImg ), intervalBright ), Views.interval( Views.extendBorder( darkImg ), intervalDark ) );
	}
}
