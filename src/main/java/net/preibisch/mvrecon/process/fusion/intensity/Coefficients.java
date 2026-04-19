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
