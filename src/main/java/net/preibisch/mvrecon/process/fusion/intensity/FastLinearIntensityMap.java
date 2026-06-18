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
package net.preibisch.mvrecon.process.fusion.intensity;

import static net.imglib2.type.PrimitiveType.FLOAT;

import java.util.Arrays;
import java.util.function.Function;

import net.imglib2.Dimensions;
import net.imglib2.algorithm.blocks.AbstractBlockProcessor;
import net.imglib2.algorithm.blocks.BlockProcessor;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.algorithm.blocks.ClampType;
import net.imglib2.algorithm.blocks.DefaultUnaryBlockOperator;
import net.imglib2.algorithm.blocks.UnaryBlockOperator;
import net.imglib2.blocks.TempArray;
import net.imglib2.type.NativeType;
import net.imglib2.type.PrimitiveType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Cast;
import net.imglib2.util.IntervalIndexer;


// TODO: move to sub-package?
public class FastLinearIntensityMap {

	/**
	 * Apply an interpolated linear intensity map to blocks of the standard
	 * ImgLib2 {@code RealType}s.
	 * <p>
	 * The returned factory function creates an operator matching the type a
	 * given input {@code BlockSupplier<T>}.
	 *
	 * @param coefficients coefficients
	 * @param imageDimensions dimensions of the image
	 * @param <T>
	 * 		the input/output type
	 *
	 * @return factory for {@code UnaryBlockOperator} to intensity-map blocks of type {@code T}
	 */
	public static <T extends NativeType<T>> Function<BlockSupplier<T>, UnaryBlockOperator<T, T>> linearIntensityMap(
			final Coefficients coefficients,
			final Dimensions imageDimensions) {
		return s -> createLinearIntensityMapOperator(
				s.getType(), s.numDimensions(), coefficients, imageDimensions, ClampType.CLAMP);
	}

	/**
	 * Create a {@code UnaryBlockOperator} to apply an interpolated linear
	 * intensity map to blocks of the standard ImgLib2 {@code RealType}s.
	 *
	 * @param type
	 * 		instance of the input type
	 * @param coefficients coefficients
	 * @param imageDimensions dimensions of the image
	 * @param clampType how to clamp when converting to target type
	 * @param <T>
	 * 		the input/output type
	 *
	 * @return {@code UnaryBlockOperator} to intensity-map blocks of type {@code T}
	 */
	public static <T extends NativeType<T>> UnaryBlockOperator<T, T> createLinearIntensityMapOperator(
			final T type,
			final int numDimensions,
			final Coefficients coefficients,
			final Dimensions imageDimensions,
			final ClampType clampType) {
		if (numDimensions != imageDimensions.numDimensions() || numDimensions != coefficients.numDimensions()) {
			throw new IllegalArgumentException("numDimensions mismatch");
		}

		final FloatType floatType = new FloatType();
		final LinearIntensityMapProcessor processor = new LinearIntensityMapProcessor(TransformCoefficients.create(imageDimensions, coefficients));
		final UnaryBlockOperator<FloatType, FloatType> op = new DefaultUnaryBlockOperator<>(floatType, floatType, numDimensions, numDimensions, processor);
		return op.adaptSourceType(type, ClampType.NONE).adaptTargetType(type, clampType);
	}


	/**
	 * Apply LinearIntensityMap defined by {@code TransformCoefficients} to {@code float[]} blocks.
	 */
	static class LinearIntensityMapProcessor extends AbstractBlockProcessor<float[], float[]> {

		private final TransformCoefficients coefficients;
		private final int[] sourceStride;
		private final long[] start;
		private final TempArray<float[]>[] tempArrays;

		public LinearIntensityMapProcessor(final TransformCoefficients coefficients) {
			super(PrimitiveType.FLOAT, coefficients.numDimensions());
			this.coefficients = coefficients;

			final int n = coefficients.numDimensions();
			sourceStride = new int[n];
			start = new long[n];

			tempArrays = Cast.unchecked(new TempArray[4]);
			Arrays.setAll(tempArrays, i -> TempArray.forPrimitiveType(FLOAT));
		}

		private LinearIntensityMapProcessor(final LinearIntensityMapProcessor processor) {
			super(processor);
			this.coefficients = processor.coefficients.independentCopy();

			final int n = coefficients.numDimensions();
			sourceStride = new int[n];
			start = new long[n];

			tempArrays = Cast.unchecked(new TempArray[4]);
			Arrays.setAll(tempArrays, i -> TempArray.forPrimitiveType(FLOAT));
		}

		@Override
		public BlockProcessor<float[], float[]> independentCopy() {
			return new LinearIntensityMapProcessor(this);
		}

		@Override
		public void compute(final float[] src, final float[] dst) {
			start[0] = sourcePos[0];
			IntervalIndexer.createAllocationSteps(sourceSize, sourceStride);
			final int len = sourceSize[0];
			final float[] tmp_coeff0 = tempArrays[0].get(len);
			final float[] tmp_coeff1 = tempArrays[1].get(len);
			final float[] tmp_lsrc = tempArrays[2].get(len);
			final float[] tmp_ldst = tempArrays[3].get(len);
			compute(sourcePos.length - 1, src, dst, 0, tmp_coeff0, tmp_coeff1, tmp_lsrc, tmp_ldst);
		}

		private void compute(final int d, final float[] src, final float[] dst, final int o,
				final float[] tmp_coeff0, final float[] tmp_coeff1,
				final float[] tmp_lsrc, final float[] tmp_ldst) {
			final int len = sourceSize[d];
			if (d > 0) {
				final long p0 = sourcePos[d];
				for (int p = 0; p < len; ++p) {
					start[d] = p0 + p;
					compute(d - 1, src, dst, o + p * sourceStride[d], tmp_coeff0, tmp_coeff1, tmp_lsrc, tmp_ldst);
				}
			} else {
				coefficients.line(start, len, 0, tmp_coeff0);
				coefficients.line(start, len, 1, tmp_coeff1);
				System.arraycopy(src, o, tmp_lsrc, 0, len);
				map(tmp_lsrc, tmp_coeff0, tmp_coeff1, tmp_ldst, len);
				System.arraycopy(tmp_ldst, 0, dst, o, len);
			}
		}

		private static void map(final float[] src, final float[] a, final float[] b, final float[] dst, final int len) {
			for (int x = 0; x < len; ++x) {
				dst[x] = src[x] * a[x] + b[x];
			}
		}
	}
}
