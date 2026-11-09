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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class TransformCoefficientsTest {

	/**
	 * 1x1x2 grid over a 4x1x4 image: z slab 0 has a = 1, slab 1 has a = 3. Past the last coefficient center in x the
	 * line must be padded with this row's coefficient, not row 0's.
	 */
	@Test
	public void rightPaddingStaysInTheCurrentRow() {
		final Coefficients coefficients = new Coefficients(new double[][] { { 1, 3 }, { 0, 0 } }, 1, 1, 2);
		final TransformCoefficients t = TransformCoefficients.create(new net.imglib2.FinalDimensions(4, 1, 4), coefficients);
		final float[] a = new float[4];
		t.line(new long[] { 0, 0, 3 }, 4, 0, a); // z = 3 is slab 1's center
		for (int x = 0; x < 4; ++x)
			assertEquals(3, a[x], 1e-6, "x = " + x);
	}
}
