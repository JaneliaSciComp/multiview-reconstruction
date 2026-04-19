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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import mpicbg.models.PointMatch;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.process.fusion.intensity.IntensityMatcher.CoefficientMatch;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.Point1D;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.PointMatch1D;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;

class IntensityMatchesIO {

	static class Writer implements Closeable {

		private final BufferedWriter writer;

		Writer(final BufferedWriter bufferedWriter) {
			writer = bufferedWriter;
		}

		void writeViewId(final ViewId viewId) throws IOException {
			final int t = viewId.getTimePointId();
			final int s = viewId.getViewSetupId();
			writer.write(Integer.toString(t));
			writer.write(" ");
			writer.write(Integer.toString(s));
			writer.write(" ");
			writer.newLine();
		}

		void writeMatches(final CoefficientMatch coefficientMatch) throws IOException {
			final int coeff1 = coefficientMatch.coeff1();
			final int coeff2 = coefficientMatch.coeff2();
			final int numVoxels = coefficientMatch.numVoxels();
			final Collection<PointMatch> matches = coefficientMatch.matches();
			writer.write(String.format("%d %d %d ", coeff1, coeff2, numVoxels));
			for (final PointMatch match : matches) {
				final double l1 = match.getP1().getL()[0];
				final double l2 = match.getP2().getL()[0];
				writer.write(Double.toString(l1));
				writer.write(" ");
				writer.write(Double.toString(l2));
				writer.write(" ");
			}
			writer.newLine();
		}

		@Override
		public void close() throws IOException {
			writer.close();
		}
	}

	static class Reader implements Closeable {

		private final BufferedReader reader;

		Reader(final BufferedReader bufferedReader) {
			reader = bufferedReader;
		}

		ViewId readViewId() throws IOException {
			final String line = reader.readLine();
			if (line == null)
				return null;

			final String[] tokens = line.split("\\s+");
			final int t = Integer.parseInt(tokens[0]);
			final int s = Integer.parseInt(tokens[1]);
			return new ViewId(t, s);
		}

		CoefficientMatch readMatches() throws IOException {
			final String line = reader.readLine();
			if (line == null)
				return null;

			final String[] tokens = line.split("\\s+");
			final int coeff1 = Integer.parseInt(tokens[0]);
			final int coeff2 = Integer.parseInt(tokens[1]);
			final int numVoxels = Integer.parseInt(tokens[2]);

			final List<PointMatch> matches = new ArrayList<>();
			for (int i = 3; i < tokens.length; i += 2) {
				final double l1 = Double.parseDouble(tokens[i]);
				final double l2 = Double.parseDouble(tokens[i + 1]);
				matches.add(new PointMatch1D(new Point1D(l1), new Point1D(l2)));
			}
			return new CoefficientMatch(coeff1, coeff2, numVoxels, matches);
		}

		@Override
		public void close() throws IOException {
			reader.close();
		}
	}
}
