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
 *   corrected = (source - dark) * meanBrightCorrected / (bright - dark)
 *
 * The bright and dark images are 2D (XY only), while the source may be 3D.
 * For 3D blocks, the same 2D bright/dark data is reused for all Z slices,
 * making this much more efficient than per-pixel RandomAccess.
 */
public class FlatfieldCorrectionBlockSupplier implements BlockSupplier<FloatType> {
	private final BlockSupplier<FloatType> source;
	private final BlockSupplier<FloatType> bright;
	private final BlockSupplier<FloatType> dark;
	private final double meanBrightCorrected;
	private final int numDimensions;

	// Temp arrays for block data
	private final TempArray<float[]> srcTemp;
	private final TempArray<float[]> brightTemp;
	private final TempArray<float[]> darkTemp;

	/**
	 * Create a flatfield correction BlockSupplier.
	 *
	 * @param source source image BlockSupplier (can be 2D or 3D)
	 * @param bright bright (flatfield) image as BlockSupplier (2D), null = constant 1
	 * @param dark dark image as BlockSupplier (2D), null = constant 0
	 * @param meanBrightCorrected pre-computed mean of (bright - dark)
	 */
	public FlatfieldCorrectionBlockSupplier(
			final BlockSupplier<FloatType> source,
			final BlockSupplier<FloatType> bright,
			final BlockSupplier<FloatType> dark,
			final double meanBrightCorrected)
	{
		this.source = source;
		this.bright = bright;
		this.dark = dark;
		this.meanBrightCorrected = meanBrightCorrected;
		this.numDimensions = source.numDimensions();

		this.srcTemp = TempArray.forPrimitiveType(FLOAT);
		this.brightTemp = TempArray.forPrimitiveType(FLOAT);
		this.darkTemp = TempArray.forPrimitiveType(FLOAT);
	}

	private FlatfieldCorrectionBlockSupplier(final FlatfieldCorrectionBlockSupplier s) {
		this.source = s.source.independentCopy();
		this.bright = s.bright == null ? null : s.bright.independentCopy();
		this.dark = s.dark == null ? null : s.dark.independentCopy();
		this.meanBrightCorrected = s.meanBrightCorrected;
		this.numDimensions = s.numDimensions;

		this.srcTemp = TempArray.forPrimitiveType(FLOAT);
		this.brightTemp = TempArray.forPrimitiveType(FLOAT);
		this.darkTemp = TempArray.forPrimitiveType(FLOAT);
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
		return new FlatfieldCorrectionBlockSupplier(
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
		// Create source BlockSupplier and convert to float
		final BlockSupplier<FloatType> source = BlockSupplier
				.of(Views.extendBorder(sourceImg))
				.andThen(Convert.convert(new FloatType()));

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

		// a missing field is a constant, filled in copy(): BlockSupplier.of() cannot analyze a
		// ConstantRandomAccessible and would copy every block pixel by pixel (PrimitiveBlocks fall-back)
		return new FlatfieldCorrectionBlockSupplier(
				source,
				brightImg == null ? null : BlockSupplier.of(bright),
				darkImg == null ? null : BlockSupplier.of(dark),
				meanBrightCorrected);
	}

	/**
	 * mean(bright - dark) per field pair and extent. Every view of a camera shares its (downsampled) fields, and
	 * iterating them for each view and level took ~50 ms each: minutes on BDV's render thread for thousands of
	 * views, and again for every view a fusion block opens. Weak keys, so the fields go with their loader; the
	 * value must not reference its key, so it holds only the other field.
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
		final BlockInterval blockInterval = BlockInterval.asBlockInterval(interval);
		final int[] size = blockInterval.size();

		final int len = safeInt(Intervals.numElements(size));
		final float[] srcArray = srcTemp.get(len);

		// Copy source block (full 3D or 2D)
		source.copy(interval, srcArray);

		final int xyLen = numDimensions >= 3 ? size[0] * size[1] : len;
		final int zSize = numDimensions >= 3 ? size[2] : 1;
		final float[][] fields = fields(interval, xyLen);
		final float[] brightArray = fields[0], darkArray = fields[1];

		// Apply correction
		final float[] fdest = Cast.unchecked(dest);
		final float meanBC = (float) meanBrightCorrected;

		for (int z = 0; z < zSize; z++) {
			final int zOffset = z * xyLen;
			for (int i = 0; i < xyLen; i++) {
				final float darkVal = darkArray[i];
				final float corrBright = brightArray[i] - darkVal;
				final float srcVal = srcArray[zOffset + i];
				final float corrImg = srcVal - darkVal;

				if (corrBright == 0) {
					fdest[zOffset + i] = 0;
				} else {
					fdest[zOffset + i] = corrImg * meanBC / corrBright;
				}
			}
		}
	}

	/** the bright and dark field of the block's xy extent: {bright, dark}, valid until the next call */
	private float[][] fields(final Interval interval, final int xyLen) {
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
		return new float[][] { brightArray, darkArray };
	}

	/**
	 * The correction of {@link #of(RandomAccessibleInterval, RandomAccessibleInterval, RandomAccessibleInterval)} for
	 * 16-bit data, as 16 bit, computed in one pass over the 16-bit block instead of three (to float, correct, to 16 bit).
	 * The values are those of {@code of(...).andThen(Convert.convert(new UnsignedShortType(), ClampType.CLAMP))}, except
	 * that one may differ by one count where the exact result is next to a .5 boundary: the gain is multiplied instead
	 * of divided by, which rounds differently in the last bit. At least one of the fields must be given.
	 */
	public static <S extends RealType<S>, R extends RealType<R>> BlockSupplier<UnsignedShortType> ofUnsignedShort(
			final RandomAccessibleInterval<UnsignedShortType> sourceImg,
			final RandomAccessibleInterval<S> brightImg,
			final RandomAccessibleInterval<R> darkImg)
	{
		return new UnsignedShort(
				(FlatfieldCorrectionBlockSupplier) of(sourceImg, brightImg, darkImg),
				BlockSupplier.of(Views.extendBorder(sourceImg)));
	}

	private static final class UnsignedShort implements BlockSupplier<UnsignedShortType> {
		private final FlatfieldCorrectionBlockSupplier fields;
		private final BlockSupplier<UnsignedShortType> source;

		UnsignedShort(final FlatfieldCorrectionBlockSupplier fields, final BlockSupplier<UnsignedShortType> source) {
			this.fields = fields;
			this.source = source;
		}

		@Override
		public void copy(final Interval interval, final Object dest) {
			final int[] size = BlockInterval.asBlockInterval(interval).size();
			final int len = safeInt(Intervals.numElements(size));
			final int xyLen = fields.numDimensions >= 3 ? size[0] * size[1] : len;
			final int zSize = fields.numDimensions >= 3 ? size[2] : 1;

			final short[] d = Cast.unchecked(dest);
			source.copy(interval, d);
			final float[][] f = fields.fields(interval, xyLen);
			final float[] brightArray = f[0], darkArray = f[1];
			final float meanBC = (float) fields.meanBrightCorrected;

			// gain = mean / (bright - dark), once per block; (int)(v + 0.5f) is cheaper than Math.round()
			final float[] gain = brightArray; // the bright field is not needed after this
			for (int i = 0; i < xyLen; i++) {
				final float corrBright = brightArray[i] - darkArray[i];
				gain[i] = corrBright == 0 ? 0 : meanBC / corrBright;
			}
			for (int z = 0; z < zSize; z++) {
				final int zOffset = z * xyLen;
				for (int i = 0; i < xyLen; i++) {
					final int q = (int) (((d[zOffset + i] & 0xffff) - darkArray[i]) * gain[i] + 0.5f);
					d[zOffset + i] = (short) (q < 0 ? 0 : q > 0xffff ? 0xffff : q);
				}
			}
		}

		@Override
		public BlockSupplier<UnsignedShortType> independentCopy() {
			return new UnsignedShort(new FlatfieldCorrectionBlockSupplier(fields), source.independentCopy());
		}

		@Override
		public BlockSupplier<UnsignedShortType> threadSafe() {
			return new UnsignedShort((FlatfieldCorrectionBlockSupplier) fields.threadSafe(), source.threadSafe());
		}

		@Override
		public int numDimensions() {
			return fields.numDimensions;
		}

		@Override
		public UnsignedShortType getType() {
			return new UnsignedShortType();
		}
	}

	@Override
	public BlockSupplier<FloatType> independentCopy() {
		return new FlatfieldCorrectionBlockSupplier(this);
	}

	@Override
	public BlockSupplier<FloatType> threadSafe() {
		return new FlatfieldCorrectionBlockSupplier(
				source.threadSafe(),
				bright == null ? null : bright.threadSafe(),
				dark == null ? null : dark.threadSafe(),
				meanBrightCorrected);
	}

	@Override
	public int numDimensions() {
		return numDimensions;
	}

	private static final FloatType type = new FloatType();

	@Override
	public FloatType getType() {
		return type;
	}
}
