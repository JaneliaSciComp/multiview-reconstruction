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

import net.imglib2.Interval;
import net.imglib2.algorithm.blocks.BlockAlgoUtils;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.img.ReadOnlyCachedCellImgFactory;
import net.imglib2.cache.img.ReadOnlyCachedCellImgOptions;
import net.imglib2.cache.img.optional.CacheOptions.CacheType;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.basictypeaccess.array.ArrayDataAccess;
import net.imglib2.type.NativeType;

public class BlockSupplierUtils
{
	/*
	 * Made to represent a BlockSupplier as a CachedCellImage, dimensions do not have to match, but should
	 * 
	 * for SOFTREF cache call BlockAlgoUtils.cellImg( ... )
	 *
	 * @param <T>
	 * @param blocks
	 * @param dimensions
	 * @param cellDimensions
	 * @param maxCacheSize
	 * @return
	 */
	public static < T extends NativeType< T > > CachedCellImg< T, ? > cellImgBoundedCache(
			final BlockSupplier< T > blocks,
			long[] dimensions,
			final int[] cellDimensions,
			final int maxCacheSize )
	{
		return new ReadOnlyCachedCellImgFactory().create(
				dimensions,
				blocks.getType(),
				BlockAlgoUtils.cellLoader( blocks ),
				ReadOnlyCachedCellImgOptions.options().cellDimensions( cellDimensions ).cacheType( CacheType.BOUNDED ).maxCacheSize( maxCacheSize ) );
	}

	/*
	 * Allows you to copy a subset of a BlockSupplier into an ArrayImg
	 * 
	 * @param <T>
	 * @param blocks
	 * @param interval
	 * @return
	 */
	public static < T extends NativeType< T > > ArrayImg< T, ? > arrayImg(
			final BlockSupplier< T > blocks,
			final Interval interval )
	{
		final ArrayImg< T, ? > img = new ArrayImgFactory<>( blocks.getType() ).create( interval );
		final Object dest = ( ( ArrayDataAccess< ? > ) img.update( null ) ).getCurrentStorageArray();
		blocks.copy( interval, dest );
		return img;
	}

}
