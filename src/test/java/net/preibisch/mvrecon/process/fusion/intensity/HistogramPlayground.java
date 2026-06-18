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

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.process.fusion.intensity.IntensityMatcher.CoefficientMatch;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;

public class HistogramPlayground {

    private static final double minIntensityThreshold = 5.0;
    private static final double maxIntensityThreshold = 250.0;
    private static final int minNumCandidates = 1000;

	public static void main(String[] args) throws URISyntaxException, SpimDataException {
		final URI xml = new URI("file:/Users/pietzsch/Desktop/data/Janelia/keller-shadingcorrected/dataset.xml");
		final XmlIoSpimData2 io = new XmlIoSpimData2();
		final SpimData2 spimData = io.load(xml);
		final SequenceDescription seq = spimData.getSequenceDescription();

		final ViewId id0 = new ViewId(0, 0);
		final ViewId id1 = new ViewId(0, 1);

		final double renderScale = 0.25;
		final IntensityMatcher matcher = new IntensityMatcher(spimData, renderScale, new int[] {8, 8, 8});
        final List<CoefficientMatch> coefficientMatches = matcher.match(
                id0, Collections.emptyList(),
                id1, Collections.emptyList(),
                minIntensityThreshold, maxIntensityThreshold, minNumCandidates);
    }
}
