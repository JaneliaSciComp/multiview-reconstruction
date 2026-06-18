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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting;

import mpicbg.spim.data.generic.sequence.ImgLoaderHint;
import mpicbg.spim.data.sequence.SetupImgLoader;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import util.ImgLib2Tools;

public class SplitSetupImgLoader< T > implements SetupImgLoader< T >
{
	final SetupImgLoader< T > underlyingSetupImgLoader;
	final Interval interval;
	final Dimensions size;

	public SplitSetupImgLoader( final SetupImgLoader< T > underlyingSetupImgLoader, final Interval interval )
	{
		this.underlyingSetupImgLoader = underlyingSetupImgLoader;
		this.interval = interval;

		final long[] dim = new long[ interval.numDimensions() ];
		interval.dimensions( dim );

		this.size = new FinalDimensions( dim );
	}

	@Override
	public RandomAccessibleInterval< T > getImage( final int timepointId, final ImgLoaderHint... hints )
	{
		return Views.zeroMin( Views.interval( underlyingSetupImgLoader.getImage( timepointId, hints ), interval ) );
	}

	@Override
	public T getImageType()
	{
		return underlyingSetupImgLoader.getImageType();
	}

	@Override
	public RandomAccessibleInterval< FloatType > getFloatImage( final int timepointId, final boolean normalize, final ImgLoaderHint... hints )
	{
		final RandomAccessibleInterval< FloatType > img = Views.zeroMin( Views.interval( underlyingSetupImgLoader.getFloatImage( timepointId, false, hints ), interval ) );

		// TODO: this is stupid, remove capablitity to get FloatType images!
		if ( normalize )
		{
			return ImgLib2Tools.normalizeVirtualRAI( img );
		}
		else
		{
			return img;
		}
	}

	@Override
	public Dimensions getImageSize( final int timepointId )
	{
		return size;
	}

	@Override
	public VoxelDimensions getVoxelSize( final int timepointId )
	{
		return underlyingSetupImgLoader.getVoxelSize( timepointId );
	}
}
