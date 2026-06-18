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

import static net.imglib2.util.Util.safeInt;

import java.io.File;
import java.net.URI;
import java.util.Random;

import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import net.imglib2.util.Intervals;
import util.URITools;

public class CoefficientsPlayground {

	public static void main(String[] args) {
		final Coefficients coefficients = createCoefficients();

		final URI uri = new File("/Users/pietzsch/Desktop/coefficients.n5").toURI();
		final N5Writer n5Writer = URITools.instantiateN5Writer(StorageFormat.N5, uri);

		String dataset = "coefficients";
		CoefficientsIO.save(coefficients, n5Writer, dataset);

		n5Writer.close();
	}


	// Create 8x8x8 affine1D coefficient field,
	// with every affine1D having random scale (0.5, 1.5), and random offset [-100, 100]
	static Coefficients createCoefficients() {
		final int[] fieldDimensions = {8, 8, 8};
		final int numElements = safeInt(Intervals.numElements(fieldDimensions));
		final double[][] coeffData = new double[2][numElements];
		final Random random = new Random(1);
		for (int i = 0; i < numElements; ++i) {
			coeffData[0][i] = 0.5 + random.nextDouble();
			coeffData[1][i] = random.nextInt(200) - 100;
		}
		return new Coefficients(coeffData, fieldDimensions);
	}
}
