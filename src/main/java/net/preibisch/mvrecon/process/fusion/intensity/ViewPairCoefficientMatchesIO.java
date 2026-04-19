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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import mpicbg.models.PointMatch;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.Point1D;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.PointMatch1D;
import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.N5Exception;
import util.URITools;

public class ViewPairCoefficientMatchesIO {

	/**
	 * Reduced matches are read/written from/to text files in this directory
	 */
	private final URI uri;

	private final KeyValueAccess kva;

	public ViewPairCoefficientMatchesIO(final URI uri) {
		this.uri = uri;
		kva = URITools.getKeyValueAccess( uri );
	}

	public void writeCoefficientsSize(
			final int[] coefficientsSize
	) throws IOException {
		final URI fn = uri.resolve("coefficients-size.txt");
		try (
				final OutputStream os = URITools.openFileWriteCloudStream(kva, fn);
				final BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
			for (final int size : coefficientsSize) {
				bw.write(String.format("%d ", size));
			}
			bw.newLine();
		}
	}

	public int[] readCoefficientsSize() throws IOException {
		final URI fn = uri.resolve("coefficients-size.txt");
		try (
				final InputStream is = URITools.openFileReadCloudStream(kva, fn);
				final BufferedReader br = new BufferedReader(new InputStreamReader(is))) {

			final String line = br.readLine();
			if (line == null)
				return null;
			final String[] tokens = line.split("\\s+");
			final int[] coefficientsSize = new int[tokens.length];
			for (int i = 0; i < tokens.length; ++i) {
				coefficientsSize[i] = Integer.parseInt(tokens[i]);
			}
			return coefficientsSize;
		}
	}

	public void write(
			final ViewId p1,
			final ViewId p2,
			final List<IntensityMatcher.CoefficientMatch> coefficientMatches
	) throws IOException {
		final URI fn = getURI(p1, p2);
		try (
				final OutputStream os = URITools.openFileWriteCloudStream(kva, fn);
				final BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os));
				final IntensityMatchesIO.Writer output = new IntensityMatchesIO.Writer(bw)) {
			output.writeViewId(p1);
			output.writeViewId(p2);
			for (final IntensityMatcher.CoefficientMatch m : coefficientMatches) {
				output.writeMatches(m);
			}
		}
	}

	public void write(
			ViewPairCoefficientMatches matches
	) throws IOException {
		write(matches.view1(), matches.view2(), matches.coefficientMatches());
	}

	public ViewPairCoefficientMatches read(
			final ViewId p1,
			final ViewId p2
	) throws IOException {
		final URI fn = getURI(p1, p2);
		return readFromFile(fn);
	}

	private ViewPairCoefficientMatches readFromFile(final URI fn)
			throws IOException {
		try (
				final InputStream is = URITools.openFileReadCloudStream(kva, fn);
				final BufferedReader br = new BufferedReader(new InputStreamReader(is));
				final IntensityMatchesIO.Reader input = new IntensityMatchesIO.Reader(br)) {
			final ViewId p1 = input.readViewId();
			final ViewId p2 = input.readViewId();
			List<IntensityMatcher.CoefficientMatch> coefficientMatches = new ArrayList<>();
			IntensityMatcher.CoefficientMatch match;
			while ((match = input.readMatches()) != null) {
				coefficientMatches.add(match);
			}
			return new ViewPairCoefficientMatches(p1, p2, coefficientMatches);
		} catch (N5Exception.N5NoSuchKeyException e) {
			return null;
		}
	}

	private URI getURI(final ViewId p1, final ViewId p2) {
		return uri.resolve(String.format("t%d_s%d--t%d_s%d.txt",
				p1.getTimePointId(), p1.getViewSetupId(),
				p2.getTimePointId(), p2.getViewSetupId()));
	}
}
