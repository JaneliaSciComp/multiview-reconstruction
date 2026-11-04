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
package net.preibisch.mvrecon.process.fusion.intensity;

import java.io.Serializable;
import java.util.Arrays;
import net.imglib2.util.IntervalIndexer;
import net.imglib2.util.Intervals;

import static net.imglib2.util.Util.safeInt;

/**
 * Holds flattened coefficient array and dimensions.
 */
public class Coefficients implements Serializable {

	private final int[] size;
	private final int[] strides;

	/**
	 * {@code flattenedCoefficients[i]} holds the flattened array of the i-the coefficient.
	 * That is, for linear map {@code y=a*x+b}, {@code flattenedCoefficients[0]} holds all the {@code a}s and
	 * {@code flattenedCoefficients[1]} holds all the {@code b}s.
	 */
	final double[][] flattenedCoefficients;

	/**
	 * The lowest intensity the correction applies to. {@link Double#NaN} (the default)
	 * means every voxel is corrected.
	 */
	private final double threshold;

	/**
	 * Fraction of intensity lost each time a voxel was imaged before (0 = no bleaching), see {@link Bleaching}.
	 */
	private final double bleachFactor;

	/**
	 * Log-gain ramp {@code c} across the image: the scale {@code a} is multiplied by {@code exp(sum_d c[d] * u_d)},
	 * where {@code u_d = (x_d + 0.5) / n_d - 0.5} is the position in [-0.5, 0.5] along an image axis of {@code n_d}
	 * voxels (at any resolution level). null = none. See {@link GainField}.
	 */
	private final double[] gainField;

	/** Where the voxels of the view were imaged before; set per image by the caller, never stored. */
	private final transient Bleaching bleaching;

	public Coefficients(
			final double[][] coefficients,
			final int... fieldDimensions) {

		if (coefficients == null)
			throw new NullPointerException();

		final int numCoefficients = coefficients.length;
		if (numCoefficients == 0)
			throw new IllegalArgumentException();

		final int numElements = safeInt(Intervals.numElements(fieldDimensions));
		for (final double[] coefficient : coefficients)
			if (coefficient.length != numElements)
				throw new IllegalArgumentException();

		size = fieldDimensions.clone();
		strides = IntervalIndexer.createAllocationSteps(size);
		flattenedCoefficients = new double[numCoefficients][];
		Arrays.setAll(flattenedCoefficients, i -> coefficients[i].clone());
		threshold = Double.NaN;
		bleachFactor = 0;
		gainField = null;
		bleaching = null;
	}

	private Coefficients(final Coefficients other, final double threshold, final double bleachFactor,
			final double[] gainField, final Bleaching bleaching) {
		this.size = other.size;
		this.strides = other.strides;
		this.flattenedCoefficients = other.flattenedCoefficients;
		this.threshold = threshold;
		this.bleachFactor = bleachFactor;
		this.gainField = gainField;
		this.bleaching = bleaching;
	}

	/**
	 * @return the lowest intensity the correction applies to; voxels below it are left
	 *         unchanged. {@link Double#NaN} means every voxel is corrected.
	 */
	public double threshold() {
		return threshold;
	}

	/**
	 * @return a view of these coefficients that only applies to intensities {@code >= threshold}
	 *         (pass {@link Double#NaN} to correct every voxel)
	 */
	public Coefficients withThreshold(final double threshold) {
		return new Coefficients(this, threshold, bleachFactor, gainField, bleaching);
	}

	/**
	 * @return the fraction of intensity lost each time a voxel was imaged before, which the intensities these
	 *         coefficients were matched on were corrected for (0 = none)
	 */
	public double bleachFactor() {
		return bleachFactor;
	}

	/**
	 * @return a view of these coefficients that applies to intensities unbleached with {@code bleachFactor}
	 */
	public Coefficients withBleachFactor(final double bleachFactor) {
		if (!(bleachFactor >= 0 && bleachFactor < 1))
			throw new IllegalArgumentException("bleachFactor must be in [0, 1), but was " + bleachFactor);
		return new Coefficients(this, threshold, bleachFactor, gainField, bleaching);
	}

	/**
	 * @return the log-gain ramp across the image (one entry per axis), or null if there is none
	 */
	public double[] gainField() {
		return gainField == null ? null : gainField.clone();
	}

	/**
	 * @return a view of these coefficients whose scale varies across the image as {@code exp(c . u)}
	 *         (null = no ramp), see {@link #gainField()}
	 */
	public Coefficients withGainField(final double[] c) {
		if (c != null && c.length != numDimensions())
			throw new IllegalArgumentException("gain field needs " + numDimensions() + " entries, but has " + c.length);
		return new Coefficients(this, threshold, bleachFactor, c == null ? null : c.clone(), bleaching);
	}

	/** @return where the voxels of the image were imaged before, or null (not stored) */
	public Bleaching bleaching() {
		return bleaching;
	}

	/**
	 * @return a view of these coefficients that first unbleaches every voxel by {@code bleaching} (built for the image
	 *         these coefficients are applied to, at its resolution level); null = no unbleaching. Only meaningful when
	 *         {@link #bleachFactor()} is the factor the bleaching was built with.
	 */
	public Coefficients withBleaching(final Bleaching bleaching) {
		return new Coefficients(this, threshold, bleachFactor, gainField, bleaching);
	}

	int size(final int d) {
		return size[d];
	}

	int stride(final int d) {
		return strides[d];
	}

	int numDimensions() {
		return size.length;
	}

	int numCoefficients() {
		return flattenedCoefficients.length;
	}
}
