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

/**
 * Compute the corrected intensity when {@code intensity} was observed in a
 * pixel that has been bleached by previously being imaged.
 */
@FunctionalInterface
public interface UnbleachFunction {

	/**
	 * Compute the corrected intensity when {@code intensity} was observed
	 * in a pixel that has been {@code nBleachIterations} times imaged
	 * already. (When this is the first time the pixel is imaged, {@code
	 * nBleachIterations==0}.
	 *
	 * @param intensity
	 * 		observed intensity
	 * @param nBleachIterations
	 * 		number of times the pixel was bleached
	 *
	 * @return corrected intensity
	 */
	double unbleach(double intensity, int nBleachIterations);

	/**
	 * Each earlier exposure kept {@code 1 - bleachFactor} of the intensity: {@code v / (1 - bleachFactor)^n}, as
	 * {@link Bleaching} applies it in preview and fusion.
	 *
	 * @param bleachFactor in [0, 1), 0 = no bleaching
	 */
	static UnbleachFunction bleachFactor(final double bleachFactor) {
		return bleachFactor(bleachFactor, false);
	}

	/**
	 * @param once a voxel imaged before is unbleached once, {@code v / (1 - bleachFactor)}, however often it was imaged
	 */
	static UnbleachFunction bleachFactor(final double bleachFactor, final boolean once) {
		if (!(bleachFactor >= 0 && bleachFactor < 1))
			throw new IllegalArgumentException("bleachFactor must be in [0, 1), but was " + bleachFactor);
		final double retained = 1 - bleachFactor;
		return (v, n) -> n == 0 ? v : v / Math.pow(retained, once ? Math.min(n, 1) : n);
	}
}
