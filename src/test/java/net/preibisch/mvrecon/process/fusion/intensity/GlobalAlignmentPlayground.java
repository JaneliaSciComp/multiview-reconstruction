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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import util.URITools;

public class GlobalAlignmentPlayground {

	private static final double minIntensityThreshold = 5.0;
	private static final double maxIntensityThreshold = 250.0;
	private static final int minNumCandidates = 1000;
	private static final int iterations = 1000;
	private static final double maxEpsilon = 0.1 * 255;
	private static final double minInlierRatio = 0.1;
	private static final int minNumInliers = 10;
	private static final double maxTrust = 3.0;

	public static void main(String[] args) throws URISyntaxException, SpimDataException, IOException {

		final URI xml = new URI("file:/Users/pietzsch/Desktop/data/Janelia/keller-shadingcorrected/dataset.xml");
		final XmlIoSpimData2 io = new XmlIoSpimData2();
		final SpimData2 spimData = io.load(xml);
		final SequenceDescription seq = spimData.getSequenceDescription();

		final int timepointId = 0; // TODO: make argument

		final ViewId[] views = seq.getViewSetupsOrdered().stream()
				.map(setup -> new ViewId(timepointId, setup.getId()))
				.toArray(ViewId[]::new);

		final double renderScale = 0.25;
		final String outputDirectory = "file:/Users/pietzsch/Desktop/matches_uri/";
		final ViewPairCoefficientMatchesIO matchesIO = new ViewPairCoefficientMatchesIO(URI.create(outputDirectory));
		int[] coefficientsSize = {8, 8, 8};
		matchesIO.writeCoefficientsSize(coefficientsSize);
		final IntensityMatcher matcher = new IntensityMatcher(spimData, renderScale, coefficientsSize);
		final IntensitySolver intensitySolver = new IntensitySolver(coefficientsSize);
		final boolean writeMatches = false;
		if (writeMatches) {
			for (int i = 0; i < views.length; ++i) {
				for (int j = i + 1; j < views.length; ++j) {
					System.out.println("matching view " + views[i] + " and " + views[j]);
                    final List<IntensityMatcher.CoefficientMatch> coefficientMatches = matcher.match(
                            views[i], Collections.emptyList(),
                            views[j], Collections.emptyList(),
                            minIntensityThreshold, maxIntensityThreshold, minNumCandidates, iterations, maxEpsilon, minInlierRatio, minNumInliers, maxTrust);
                    intensitySolver.connect(views[i], views[j], coefficientMatches);
					matchesIO.write(views[i], views[j], coefficientMatches);
				}
			}
		} else {
			final int[] coefficientsSizeR = matchesIO.readCoefficientsSize();
			System.out.println("coefficientsSizeR = " + Arrays.toString(coefficientsSizeR));
			for (int i = 0; i < views.length; ++i) {
				for (int j = i + 1; j < views.length; ++j) {
					System.out.println("matching view " + views[i] + " and " + views[j]);
					try {
						final ViewPairCoefficientMatches matches = matchesIO.read(views[i], views[j]);
						if (matches != null) {
							intensitySolver.connect(matches);
						}
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
			}
		}

		intensitySolver.solveForGlobalCoefficients(1000);

		final URI uri = new File( "/Users/pietzsch/Desktop/intensity.n5" ).toURI();
		final Map<ViewId, IntensityTile> intensityTiles = intensitySolver.getIntensityTiles();
		final Map<ViewId, Coefficients> coefficients = new HashMap<>();
		intensityTiles.forEach((k, v) -> coefficients.put(k, v.getCoefficients()));
		try ( final N5Writer n5Writer = URITools.instantiateN5Writer( StorageFormat.N5, uri ) )
		{
			IntensityCorrection.writeCoefficients(
					n5Writer,
					"",
					"coefficients",
					coefficients
			);
		}

		// TODO: iterate IntensityTiles
		//       for each IntensityTile iterate Coefficient Tiles
		//       extract Coefficient instance (scale models by 255!?)

	}
}
