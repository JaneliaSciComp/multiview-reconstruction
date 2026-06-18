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

import net.imglib2.Dimensions;
import net.imglib2.blocks.TempArray;
import net.imglib2.util.Cast;

/**
 * Apply scaling and translation to {@code Coefficients} array.
 * Use {@link #line} to extract an X line segment of transformed interpolated coefficients.
 */
class TransformCoefficients {

	private final Coefficients coefficients;
	private final int n;
	private final double[] g;
	private final double[] h;
	private final float[] sof;
	private final int[] S;
	private final TempArray<float[]>[] tempArrays;

	static TransformCoefficients create(final Dimensions target, final Coefficients coefficients) {
		final int n = coefficients.numDimensions();

		final double[] scale = new double[n];
		Arrays.setAll(scale, d -> (double) target.dimension(d) / coefficients.size(d));

		// shift everything in xy by 0.5 pixels so the coefficient sits in the middle of the block
		final double[] translation = new double[n];
		Arrays.setAll(translation, d -> 0.5 * scale[d]);

		return new TransformCoefficients(scale, translation, coefficients);
	}

	TransformCoefficients(final double[] scale, final double[] translation, final Coefficients coefficients) {
		this.coefficients = coefficients;
		n = coefficients.numDimensions();
		g = new double[n];
		h = new double[n];
		sof = new float[n];
		S = new int[n];
		Arrays.setAll(g, d -> 1.0 / scale[d]);
		Arrays.setAll(h, d -> -translation[d] * g[d]);
		tempArrays = Cast.unchecked(new TempArray[n]);
		Arrays.setAll(tempArrays, i -> TempArray.forPrimitiveType(FLOAT));
	}

	private TransformCoefficients(TransformCoefficients t) {
		this.coefficients = t.coefficients;
		this.n = t.n;
		this.g = t.g;
		this.h = t.h;
		this.sof = new float[n];
		this.S = new int[n];
		tempArrays = Cast.unchecked(new TempArray[n]);
		Arrays.setAll(tempArrays, i -> TempArray.forPrimitiveType(FLOAT));
	}

	void line(final long[] start, final int len0, final int coeff_index, final float[] target) {

		final double[] coeff = coefficients.flattenedCoefficients[coeff_index];

		for (int d = 0; d < n; ++d) {
			sof[d] = (float) (g[d] * start[d] + h[d]);
			S[d] = (int) Math.floor(sof[d]);
		}
		final int Smax = (int) Math.floor(len0 * g[0] + sof[0]) + 1;
		final int L0 = Smax - S[0] + 1;

		// interpolate all dimensions > 0 into tmp array
		final float[] tmp = tempArrays[0].get(L0);
		int o = 0;
		for (int d = 1; d < n; ++d) {
			final int posd = Math.min(Math.max(S[d], 0), coefficients.size(d) - 1);
			o += coefficients.stride(d) * posd;
		}
		interpolate_coeff_line(1, L0, coeff, tmp, o);

		// interpolate in dim0 into target array
		float s0f = sof[0] - S[0];
		final float step = (float) g[0];
		for (int x = 0; x < len0; ++x) {
			final int s0 = (int) s0f;
			final float r0 = s0f - s0;
			final float a0 = tmp[s0];
			final float a1 = tmp[s0 + 1];
			target[x] = a0 + r0 * (a1 - a0);
			s0f += step;
		}
	}

	private void interpolate_coeff_line(final int d, final int L0, final double[] coeff, final float[] dest, final int o) {
		if (d < n) {
			interpolate_coeff_line(d + 1, L0, coeff, dest, o);
			if (S[d] >= 0 && S[d] <= coefficients.size(d) - 2) {
				final float[] tmp = tempArrays[d].get(L0);
				interpolate_coeff_line(d + 1, L0, coeff, tmp, o + coefficients.stride(d));
				final float r = sof[d] - S[d];
				interpolate(dest, tmp, r, L0);
			}
		} else {
			padded_coeff_line(S[0], L0, coeff, dest, o);
		}
	}

	private void padded_coeff_line(final int x0, final int len, final double[] coeff, final float[] dest, final int o) {
		final int w = coefficients.size(0);
		final int pad_left = Math.max(0, Math.min(len, -x0));
		final int pad_right = Math.max(0, Math.min(len, x0 + len - w));
		final int copy_len = len - pad_left - pad_right;

		if (pad_left > 0) {
			Arrays.fill(dest, 0, pad_left, (float) coeff[o]);
		}
		if (copy_len > 0) {
			for (int i = 0; i < copy_len; ++i) {
				dest[pad_left + i] = (float) coeff[o + x0 + pad_left + i];
			}
		}
		if (pad_right > 0) {
			Arrays.fill(dest, len - pad_right, len, (float) coeff[w - 1]);
		}
	}

	// elements a[i] are set to (1-r) * a[i] + r * b[i]
	private void interpolate(final float[] a, final float[] b, final float r, final int len) {
		for (int i = 0; i < len; ++i) {
			a[i] += r * (b[i] - a[i]);
		}
	}

	TransformCoefficients independentCopy() {
		return new TransformCoefficients(this);
	}

	int numDimensions() {
		return n;
	}
}
