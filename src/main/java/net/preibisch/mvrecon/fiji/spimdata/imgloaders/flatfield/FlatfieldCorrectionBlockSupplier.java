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

import static net.imglib2.type.PrimitiveType.FLOAT;
import static net.imglib2.util.Util.safeInt;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import bdv.util.ConstantRandomAccessible;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.AbstractBlockSupplier;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.algorithm.blocks.convert.Convert;
import net.imglib2.blocks.BlockInterval;
import net.imglib2.blocks.TempArray;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Cast;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

/**
 * Block-based flatfield correction for efficient fusion processing.
 *
 * Applies the correction formula:
 *   corrected = (source - dark) * (meanBrightCorrected / (bright - dark))
 *
 * The bright and dark images are 2D (XY only), while the source may be 3D.
 * For 3D blocks, the same 2D bright/dark data is reused for all Z slices,
 * making this much more efficient than per-pixel RandomAccess.
 *
 * The blocks are FloatType, or UnsignedShortType (rounded and clamped) for 16-bit data.
 */
public class FlatfieldCorrectionBlockSupplier<T extends NativeType<T>> extends AbstractBlockSupplier<T> {
	/** FloatType or UnsignedShortType */
	private final T type;
	/** blocks of {@link #type}: the raw 16-bit data, or the data converted to float */
	private final BlockSupplier<T> source;
	private final BlockSupplier<FloatType> bright;
	private final BlockSupplier<FloatType> dark;
	private final double meanBrightCorrected;
	private final int numDimensions;
	/** the correction loop for {@link #type} */
	private final Loop loop;

	private interface Loop {
		/** corrects one block into {@code dest}, an array of the output type */
		void correct(Interval interval, Object dest, float[] gain, float[] dark, int xyLen, int zSize);
	}

	// Temp arrays for block data; not thread-safe, threadSafe() (inherited) gives every thread its own copy
	private final TempArray<float[]> srcTemp = TempArray.forPrimitiveType(FLOAT);
	private final TempArray<float[]> brightTemp = TempArray.forPrimitiveType(FLOAT);
	private final TempArray<float[]> darkTemp = TempArray.forPrimitiveType(FLOAT);

	/**
	 * @param source source blocks (2D or 3D) of the output type
	 * @param bright bright (flatfield) image as BlockSupplier (2D), null = constant 1
	 * @param dark dark image as BlockSupplier (2D), null = constant 0
	 * @param meanBrightCorrected pre-computed mean of (bright - dark)
	 */
	private FlatfieldCorrectionBlockSupplier(
			final BlockSupplier<T> source,
			final BlockSupplier<FloatType> bright,
			final BlockSupplier<FloatType> dark,
			final double meanBrightCorrected)
	{
		this.type = source.getType();
		if (type instanceof FloatType)
			this.loop = this::correctFloat;
		else if (type instanceof UnsignedShortType)
			this.loop = this::correctUnsignedShort;
		else
			throw new IllegalArgumentException("unsupported type: " + type.getClass().getSimpleName());
		this.source = source;
		this.bright = bright;
		this.dark = dark;
		this.meanBrightCorrected = meanBrightCorrected;
		this.numDimensions = source.numDimensions();
	}

	/**
	 * Factory method to create a flatfield correction BlockSupplier from an existing FloatType source.
	 */
	public static BlockSupplier<FloatType> of(
			final BlockSupplier<FloatType> source,
			final RandomAccessibleInterval<FloatType> bright,
			final RandomAccessibleInterval<FloatType> dark,
			final double meanBrightCorrected)
	{
		return new FlatfieldCorrectionBlockSupplier<>(
				source,
				BlockSupplier.of(bright),
				BlockSupplier.of(dark),
				meanBrightCorrected);
	}

	/**
	 * Factory method to create a flatfield correction BlockSupplier from RAIs.
	 * Handles interval adjustment for 2D bright/dark with 3D source, similar to
	 * {@link FlatFieldCorrectedRandomAccessibleIntervals#create}.
	 *
	 * @param sourceImg source image (can be any RealType, will be converted to FloatType)
	 * @param brightImg bright (flatfield) image, can be null
	 * @param darkImg dark image, can be null
	 * @return BlockSupplier that applies flatfield correction
	 */
	public static <T extends RealType<T> & NativeType<T>, S extends RealType<S>, R extends RealType<R>>
	BlockSupplier<FloatType> of(
			final RandomAccessibleInterval<T> sourceImg,
			final RandomAccessibleInterval<S> brightImg,
			final RandomAccessibleInterval<R> darkImg)
	{
		return of(BlockSupplier.of(Views.extendBorder(sourceImg)).andThen(Convert.convert(new FloatType())),
				sourceImg, brightImg, darkImg);
	}

	/**
	 * {@link #of(RandomAccessibleInterval, RandomAccessibleInterval, RandomAccessibleInterval)} for 16-bit data,
	 * rounded and clamped to 16 bit in one pass over the block.
	 */
	public static <S extends RealType<S>, R extends RealType<R>> BlockSupplier<UnsignedShortType> ofUnsignedShort(
			final RandomAccessibleInterval<UnsignedShortType> sourceImg,
			final RandomAccessibleInterval<S> brightImg,
			final RandomAccessibleInterval<R> darkImg)
	{
		return of(BlockSupplier.of(Views.extendBorder(sourceImg)), sourceImg, brightImg, darkImg);
	}

	private static <T extends NativeType<T>, S extends RealType<S>, R extends RealType<R>> BlockSupplier<T> of(
			final BlockSupplier<T> source,
			final Interval sourceImg,
			final RandomAccessibleInterval<S> brightImg,
			final RandomAccessibleInterval<R> darkImg)
	{
		// Handle null bright/dark images
		final RandomAccessibleInterval<FloatType> bright;
		final RandomAccessibleInterval<FloatType> dark;

		if (brightImg == null && darkImg == null) {
			// No correction needed, return source as-is
			return source;
		} else if (brightImg == null) {
			// Assume bright == constant 1.0
			final ConstantRandomAccessible<FloatType> constantBright =
					new ConstantRandomAccessible<>(new FloatType(1.0f), darkImg.numDimensions());
			bright = Views.interval(constantBright, createInterval(sourceImg, darkImg.numDimensions()));
			dark = Views.interval(Views.extendBorder(convertToFloat(darkImg)),
					createInterval(sourceImg, darkImg.numDimensions()));
		} else if (darkImg == null) {
			// Assume dark == constant 0.0
			final ConstantRandomAccessible<FloatType> constantDark =
					new ConstantRandomAccessible<>(new FloatType(0.0f), brightImg.numDimensions());
			bright = Views.interval(Views.extendBorder(convertToFloat(brightImg)),
					createInterval(sourceImg, brightImg.numDimensions()));
			dark = Views.interval(constantDark, createInterval(sourceImg, brightImg.numDimensions()));
		} else {
			bright = Views.interval(Views.extendBorder(convertToFloat(brightImg)),
					createInterval(sourceImg, brightImg.numDimensions()));
			dark = Views.interval(Views.extendBorder(convertToFloat(darkImg)),
					createInterval(sourceImg, darkImg.numDimensions()));
		}

		final double meanBrightCorrected = meanCorrected(brightImg, darkImg, bright, dark);

		// a missing field stays null and is filled as a constant: BlockSupplier.of() would copy a
		// ConstantRandomAccessible pixel by pixel
		return new FlatfieldCorrectionBlockSupplier<>(
				source,
				brightImg == null ? null : BlockSupplier.of(bright),
				darkImg == null ? null : BlockSupplier.of(dark),
				meanBrightCorrected);
	}

	/**
	 * mean(bright - dark) per field pair and extent, cached: the views of a camera share their fields, and
	 * computing it per view and level took ~50 ms each. Weak keys, so the value must not reference its key.
	 */
	private static final Map<Object, Map<List<Object>, Double>> MEANS = Collections.synchronizedMap(new WeakHashMap<>());

	private static double meanCorrected(
			final RandomAccessibleInterval<?> brightImg,
			final RandomAccessibleInterval<?> darkImg,
			final RandomAccessibleInterval<FloatType> bright,
			final RandomAccessibleInterval<FloatType> dark) {
		final Object owner = brightImg != null ? brightImg : darkImg;
		final List<Object> key = Arrays.asList(brightImg != null ? darkImg : null,
				Arrays.toString(bright.minAsLongArray()), Arrays.toString(bright.maxAsLongArray()));
		final Map<List<Object>, Double> means = MEANS.computeIfAbsent(owner, k -> new ConcurrentHashMap<>());
		return means.computeIfAbsent(key, k -> FlatFieldCorrectedRandomAccessibleInterval.getMeanCorrected(bright, dark));
	}

	/**
	 * Create an interval matching the source's first N dimensions.
	 */
	private static FinalInterval createInterval(final Interval source, final int numDimensions) {
		final long[] mins = new long[numDimensions];
		final long[] maxs = new long[numDimensions];
		for (int d = 0; d < numDimensions; d++) {
			mins[d] = source.min(d);
			maxs[d] = source.max(d);
		}
		return new FinalInterval(mins, maxs);
	}

	/**
	 * Convert a RealType RAI to FloatType.
	 */
	@SuppressWarnings("unchecked")
	private static <T extends RealType<T>> RandomAccessibleInterval<FloatType> convertToFloat(
			final RandomAccessibleInterval<T> img)
	{
		if (img.getType() instanceof FloatType) {
			return (RandomAccessibleInterval<FloatType>) img;
		}

		return net.imglib2.converter.RealTypeConverters.convert(img, new FloatType());
	}

	@Override
	public void copy(final Interval interval, final Object dest) {
		final int[] size = BlockInterval.asBlockInterval(interval).size();
		final int len = safeInt(Intervals.numElements(size));
		final int xyLen = numDimensions >= 3 ? size[0] * size[1] : len;
		final int zSize = numDimensions >= 3 ? size[2] : 1;
		final float[][] fields = gainAndDark(interval, xyLen);
		loop.correct(interval, dest, fields[0], fields[1], xyLen, zSize);
	}

	private void correctFloat(final Interval interval, final Object dest, final float[] gain, final float[] dark, final int xyLen, final int zSize) {
		final float[] src = srcTemp.get(xyLen * zSize);
		source.copy(interval, src);
		final float[] d = Cast.unchecked(dest);
		for (int z = 0; z < zSize; z++) {
			final int zOffset = z * xyLen;
			for (int i = 0; i < xyLen; i++)
				d[zOffset + i] = (src[zOffset + i] - dark[i]) * gain[i];
		}
	}

	/** in place, rounded and clamped; (int)(v + 0.5f) is cheaper than Math.round() */
	private void correctUnsignedShort(final Interval interval, final Object dest, final float[] gain, final float[] dark, final int xyLen, final int zSize) {
		final short[] d = Cast.unchecked(dest);
		source.copy(interval, d);
		for (int z = 0; z < zSize; z++) {
			final int zOffset = z * xyLen;
			for (int i = 0; i < xyLen; i++) {
				final float v = (UnsignedShortType.getUnsignedShort(d[zOffset + i]) - dark[i]) * gain[i];
				d[zOffset + i] = UnsignedShortType.getCodedSignedShortChecked((int) (v + 0.5f));
			}
		}
	}

	/**
	 * {gain, dark} for the block's xy extent, valid until the next call:
	 * gain = meanBrightCorrected / (bright - dark), 0 where bright == dark.
	 */
	private float[][] gainAndDark(final Interval interval, final int xyLen) {
		final float[] brightArray = brightTemp.get(xyLen);
		final float[] darkArray = darkTemp.get(xyLen);

		// Create 2D interval for bright/dark (XY only)
		final Interval interval2D;
		if (numDimensions >= 3) {
			interval2D = new FinalInterval(
					new long[]{interval.min(0), interval.min(1)},
					new long[]{interval.max(0), interval.max(1)});
		} else {
			interval2D = interval;
		}

		// Copy bright/dark blocks (2D)
		if (bright == null)
			Arrays.fill(brightArray, 0, xyLen, 1f);
		else
			bright.copy(interval2D, brightArray);
		if (dark == null)
			Arrays.fill(darkArray, 0, xyLen, 0f);
		else
			dark.copy(interval2D, darkArray);

		final float meanBC = (float) meanBrightCorrected;
		for (int i = 0; i < xyLen; i++) {
			final float corrBright = brightArray[i] - darkArray[i];
			brightArray[i] = corrBright == 0 ? 0 : meanBC / corrBright; // the gain, in place
		}
		return new float[][] { brightArray, darkArray };
	}

	@Override
	public BlockSupplier<T> independentCopy() {
		return new FlatfieldCorrectionBlockSupplier<>(
				source.independentCopy(),
				bright == null ? null : bright.independentCopy(),
				dark == null ? null : dark.independentCopy(),
				meanBrightCorrected);
	}

	@Override
	public int numDimensions() {
		return numDimensions;
	}

	@Override
	public T getType() {
		return type;
	}
}
