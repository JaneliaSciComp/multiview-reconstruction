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

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.BlockAlgoUtils;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.converter.RealTypeConverters;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

/**
 * Factory methods for creating flatfield-corrected images.
 *
 * All methods use efficient block-based processing internally via
 * {@link FlatfieldCorrectionBlockSupplier}, backed by a {@link CachedCellImg}.
 */
public class FlatFieldCorrectedRandomAccessibleIntervals
{
	/** Default cell size for block-based flatfield correction */
	private static final int[] DEFAULT_CELL_SIZE = new int[] {64, 64, 64};

	/**
	 * Create a flatfield-corrected image with the same type as the source.
	 * Uses efficient block-based processing internally.
	 *
	 * @param sourceImg source image
	 * @param brightImg bright (flatfield) image, can be null
	 * @param darkImg dark image, can be null
	 * @return corrected image with same type as source
	 */
	public static <R extends RealType<R> & NativeType<R>, S extends RealType<S>, T extends RealType<T>>
	RandomAccessibleInterval<R> create(
			final RandomAccessibleInterval<R> sourceImg,
			final RandomAccessibleInterval<S> brightImg,
			final RandomAccessibleInterval<T> darkImg)
	{
		final R type = sourceImg.getType().createVariable();
		return create(sourceImg, brightImg, darkImg, type);
	}

	/**
	 * Create a flatfield-corrected image with a specified output type.
	 * Uses efficient block-based processing internally.
	 *
	 * @param sourceImg source image
	 * @param brightImg bright (flatfield) image, can be null
	 * @param darkImg dark image, can be null
	 * @param outputType the desired output type
	 * @return corrected image with specified output type
	 */
	@SuppressWarnings("unchecked")
	public static <O extends RealType<O>, R extends RealType<R> & NativeType<R>, S extends RealType<S>, T extends RealType<T>>
	RandomAccessibleInterval<O> create(
			final RandomAccessibleInterval<R> sourceImg,
			final RandomAccessibleInterval<S> brightImg,
			final RandomAccessibleInterval<T> darkImg,
			final O outputType)
	{
		// Create block-based corrected image (always FloatType internally)
		final RandomAccessibleInterval<FloatType> correctedFloat = createBlockBased(
				sourceImg, brightImg, darkImg, DEFAULT_CELL_SIZE);

		// If output type is FloatType, return directly
		if (outputType instanceof FloatType)
			return (RandomAccessibleInterval<O>) correctedFloat;

		// Otherwise, convert to the requested output type
		return RealTypeConverters.convert(correctedFloat, outputType);
	}

	/**
	 * Create a flatfield-corrected FloatType image using efficient block-based processing.
	 * The result is backed by a CachedCellImg that computes correction on-demand.
	 *
	 * @param sourceImg source image (can be any RealType)
	 * @param brightImg bright (flatfield) image, can be null
	 * @param darkImg dark image, can be null
	 * @return FloatType RandomAccessibleInterval with flatfield correction applied
	 */
	public static <R extends RealType<R> & NativeType<R>, S extends RealType<S>, T extends RealType<T>>
	RandomAccessibleInterval<FloatType> createBlockBased(
			final RandomAccessibleInterval<R> sourceImg,
			final RandomAccessibleInterval<S> brightImg,
			final RandomAccessibleInterval<T> darkImg)
	{
		return createBlockBased(sourceImg, brightImg, darkImg, DEFAULT_CELL_SIZE);
	}

	/**
	 * Create a flatfield-corrected FloatType image using efficient block-based processing.
	 * The result is backed by a CachedCellImg that computes correction on-demand.
	 *
	 * @param sourceImg source image (can be any RealType)
	 * @param brightImg bright (flatfield) image, can be null
	 * @param darkImg dark image, can be null
	 * @param cellSize cell size for the cached image
	 * @return FloatType RandomAccessibleInterval with flatfield correction applied
	 */
	public static <R extends RealType<R> & NativeType<R>, S extends RealType<S>, T extends RealType<T>>
	RandomAccessibleInterval<FloatType> createBlockBased(
			final RandomAccessibleInterval<R> sourceImg,
			final RandomAccessibleInterval<S> brightImg,
			final RandomAccessibleInterval<T> darkImg,
			final int[] cellSize)
	{
		// Handle null bright/dark - if both null, just convert source to float
		if (brightImg == null && darkImg == null)
		{
			final BlockSupplier<FloatType> blocks = BlockSupplier
					.of(Views.extendBorder(sourceImg))
					.andThen(net.imglib2.algorithm.blocks.convert.Convert.convert(new FloatType()));
			return wrapWithOffset(blocks, sourceImg, cellSize);
		}

		// Create the block supplier with flatfield correction
		final BlockSupplier<FloatType> blocks = FlatfieldCorrectionBlockSupplier.of(
				sourceImg, brightImg, darkImg);

		return wrapWithOffset(blocks, sourceImg, cellSize);
	}

	/**
	 * Wrap a BlockSupplier in a CachedCellImg and translate to match source interval.
	 */
	private static <R extends RealType<R>> RandomAccessibleInterval<FloatType> wrapWithOffset(
			final BlockSupplier<FloatType> blocks,
			final RandomAccessibleInterval<R> sourceImg,
			final int[] cellSize)
	{
		// Create CachedCellImg (zero-min)
		final CachedCellImg<FloatType, ?> cellImg = BlockAlgoUtils.cellImg(
				blocks.threadSafe(),
				sourceImg.dimensionsAsLongArray(),
				cellSize);

		// Translate to match source interval if not zero-min
		if (Views.isZeroMin(sourceImg))
			return cellImg;
		else
			return Views.translate(cellImg, sourceImg.minAsLongArray());
	}
}
