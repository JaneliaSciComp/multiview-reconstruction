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
}
