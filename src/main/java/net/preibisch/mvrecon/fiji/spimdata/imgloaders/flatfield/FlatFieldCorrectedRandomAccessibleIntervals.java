/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2026 Multiview Reconstruction developers.
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import mpicbg.spim.data.generic.sequence.ImgLoaderHint;
import mpicbg.spim.data.generic.sequence.ImgLoaderHints;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.algorithm.blocks.ClampType;
import net.imglib2.algorithm.blocks.convert.Convert;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.ReadOnlyCachedCellImgFactory;
import net.imglib2.cache.img.ReadOnlyCachedCellImgOptions;
import net.imglib2.converter.Converters;
import net.imglib2.converter.RealTypeConverters;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
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
	 * The corrected image as {@code outputType}; normalized if requested (float only) and copied into memory on
	 * {@link ImgLoaderHints#LOAD_COMPLETELY}.
	 */
	public static <O extends RealType<O> & NativeType<O>, R extends RealType<R> & NativeType<R>, S extends RealType<S>, T extends RealType<T>>
	RandomAccessibleInterval<O> create(
			final RandomAccessibleInterval<R> sourceImg,
			final RandomAccessibleInterval<S> brightImg,
			final RandomAccessibleInterval<T> darkImg,
			final O outputType,
			final boolean normalize,
			final ImgLoaderHint... hints)
	{
		RandomAccessibleInterval<O> rai = create(sourceImg, brightImg, darkImg, outputType);
		if (normalize)
			rai = new VirtuallyNormalizedRandomAccessibleInterval<>(rai);
		if (!Arrays.asList(hints).contains(ImgLoaderHints.LOAD_COMPLETELY))
			return rai;

		// an ArrayImg holds at most 2^31 pixels
		final ImgFactory<O> factory = Intervals.numElements(rai) < (1L << 31) ? new ArrayImgFactory<>(outputType) : new CellImgFactory<>(outputType);
		final Img<O> loaded = factory.create(rai);
		RealTypeConverters.copyFromTo(Views.extendZero(rai), loaded);
		return loaded;
	}

	/** downsampling of the 2D fields for a mipmap level: the level's in x and y, none in z */
	public static int[] fieldDownsampling(final double[] mipmapResolution, final int numDimensions)
	{
		final int[] ds = new int[numDimensions];
		for (int d = 0; d < numDimensions - 1; d++)
			ds[d] = (int) mipmapResolution[d];
		ds[numDimensions - 1] = 1;
		return ds;
	}

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
		final Supplier<BlockSupplier<FloatType>> correctedFloat = () -> correctedBlocks(sourceImg, brightImg, darkImg);

		// If output type is FloatType, return directly
		if (outputType instanceof FloatType)
			return (RandomAccessibleInterval<O>) wrapWithOffset(correctedFloat, new FloatType(), sourceImg, DEFAULT_CELL_SIZE);

		// 16 bit in and out: one pass over the block
		if (outputType instanceof UnsignedShortType && sourceImg.getType() instanceof UnsignedShortType
				&& (brightImg != null || darkImg != null))
			return (RandomAccessibleInterval<O>) wrapWithOffset(
					() -> FlatfieldCorrectionBlockSupplier.ofUnsignedShort((RandomAccessibleInterval) sourceImg, brightImg, darkImg),
					new UnsignedShortType(), sourceImg, DEFAULT_CELL_SIZE);

		// other types: converted per block (the result stays a cell image) and clamped, since an integer type
		// would wrap where raw < dark
		if (outputType instanceof NativeType && !(outputType instanceof Volatile))
			return (RandomAccessibleInterval<O>) wrapWithOffset(
					() -> correctedFloat.get().andThen(Convert.convert((NativeType) outputType, ClampType.CLAMP)),
					(NativeType) ((NativeType) outputType).createVariable(), sourceImg, DEFAULT_CELL_SIZE);

		final double min = outputType.getMinValue(), max = outputType.getMaxValue();
		return Converters.convert(wrapWithOffset(correctedFloat, new FloatType(), sourceImg, DEFAULT_CELL_SIZE),
				(i, o) -> o.setReal(Math.min(Math.max(i.getRealDouble(), min), max)), outputType);
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
		return wrapWithOffset(() -> correctedBlocks(sourceImg, brightImg, darkImg), new FloatType(), sourceImg, cellSize);
	}

	private static <R extends RealType<R> & NativeType<R>, S extends RealType<S>, T extends RealType<T>>
	BlockSupplier<FloatType> correctedBlocks(
			final RandomAccessibleInterval<R> sourceImg,
			final RandomAccessibleInterval<S> brightImg,
			final RandomAccessibleInterval<T> darkImg)
	{
		// Handle null bright/dark - if both null, just convert source to float
		if (brightImg == null && darkImg == null)
			return BlockSupplier
					.of(Views.extendBorder(sourceImg))
					.andThen(Convert.convert(new FloatType()));

		// Create the block supplier with flatfield correction
		return FlatfieldCorrectionBlockSupplier.of(sourceImg, brightImg, darkImg);
	}

	/**
	 * Wrap a BlockSupplier in a CachedCellImg and translate to match source interval.
	 * The BlockSupplier is created with the first cell: creating it reads a source block, which would
	 * otherwise happen on BDV's render thread for every level of every view.
	 */
	private static <O extends NativeType<O>, R extends RealType<R>> RandomAccessibleInterval<O> wrapWithOffset(
			final Supplier<BlockSupplier<O>> createBlocks,
			final O type,
			final RandomAccessibleInterval<R> sourceImg,
			final int[] cellSize)
	{
		final AtomicReference<BlockSupplier<O>> blocks = new AtomicReference<>();
		final CellLoader<O> loader = cell -> {
			BlockSupplier<O> b = blocks.get();
			if (b == null) {
				b = createBlocks.get().threadSafe();
				if (!blocks.compareAndSet(null, b))
					b = blocks.get();
			}
			b.copy(cell, cell.getStorageArray());
		};

		// zero-min; volatile accesses for VolatileViews.wrapAsVolatile
		final CachedCellImg<O, ?> cellImg = new ReadOnlyCachedCellImgFactory().create(
				sourceImg.dimensionsAsLongArray(),
				type,
				loader,
				ReadOnlyCachedCellImgOptions.options().cellDimensions(cellSize).volatileAccesses(true));

		// Translate to match source interval if not zero-min
		if (Views.isZeroMin(sourceImg))
			return cellImg;
		else
			return Views.translate(cellImg, sourceImg.minAsLongArray());
	}
}
