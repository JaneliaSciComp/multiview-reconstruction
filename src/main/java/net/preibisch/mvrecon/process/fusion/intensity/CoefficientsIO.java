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

import java.util.Arrays;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.DoubleArrayDataBlock;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.RawCompression;

class CoefficientsIO {

	private CoefficientsIO() {}

	static void save(final Coefficients coefficients, final N5Writer n5Writer, final String datasetPath) {

		if (n5Writer.exists(datasetPath))
			n5Writer.remove(datasetPath);

		final int n = coefficients.numDimensions();
		final long[] dimensions = new long[n + 1];
		final int[] blockSize = new int[n + 1];
		for (int d = 0; d < n; d++) {
			dimensions[d] = coefficients.size(d);
			blockSize[d] = coefficients.size(d);
		}
		dimensions[n] = coefficients.numCoefficients();
		blockSize[n] = 1;
		final DatasetAttributes attr = new DatasetAttributes(dimensions, blockSize, DataType.FLOAT64, new RawCompression());
		n5Writer.createDataset(datasetPath, attr);
		n5Writer.setAttribute(datasetPath, "coefficients version", "1.0");

		final long[] gridPosition = new long[n + 1];
		for (int i = 0; i < coefficients.numCoefficients(); ++i) {
			gridPosition[n] = i;
			final DoubleArrayDataBlock block = new DoubleArrayDataBlock(blockSize, gridPosition, coefficients.flattenedCoefficients[i]);
			n5Writer.writeBlock(datasetPath, attr, block);
		}
	}

	static Coefficients load(final N5Reader n5Reader, final String datasetPath) {

		final DatasetAttributes attr = n5Reader.getDatasetAttributes(datasetPath);
		final int n = attr.getNumDimensions() - 1;
		final int[] fieldDimensions = Arrays.copyOf(attr.getBlockSize(), n);
		final int numCoefficients = (int) attr.getDimensions()[n];
		final double[][] coefficients = new double[numCoefficients][];
		final long[] gridPosition = new long[n + 1];
		for (int i = 0; i < numCoefficients; i++) {
			gridPosition[n] = i;
			final DataBlock<?> block = n5Reader.readBlock(datasetPath, attr, gridPosition);
			coefficients[i] = (double[]) block.getData();
		}
		return new Coefficients(coefficients, fieldDimensions);
	}
}
