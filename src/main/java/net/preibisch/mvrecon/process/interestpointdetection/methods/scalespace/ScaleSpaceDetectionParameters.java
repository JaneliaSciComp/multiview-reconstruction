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
package net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace;

import java.util.Collection;

import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.ViewDescription;
import net.preibisch.mvrecon.process.interestpointdetection.methods.InterestPointParameters;

/**
 * Parameters of the scale-space DoG detection of views (see DoGParameters for the single-scale DoG):
 * which views, the downsampling (the "starting position" of the scale space), the intensity range
 * and the limit of detections come from InterestPointParameters, everything about the scale space
 * itself from {@link ScaleSpaceParameters}, which is the only place that defines its defaults.
 *
 * @author Stephan Preibisch
 */
public class ScaleSpaceDetectionParameters extends InterestPointParameters
{
	/**
	 * sigmaMin, steps, octaves, threshold, findMin/findMax, detectFinestLevel, localization, ...;
	 * its minIntensity/maxIntensity are set from this object by ScaleSpace.addInterestPoints
	 */
	public final ScaleSpaceParameters scaleSpace = new ScaleSpaceParameters();

	/**
	 * the starting resolution (octave 0) as downsampling in x, y, z of the full-resolution view, e.g.
	 * one of the precomputed resolution levels; the scale space does not use the inherited
	 * downsampleXY/downsampleZ
	 */
	public long[] downsampling = new long[] { 1, 1, 1 };

	public ScaleSpaceDetectionParameters() { super(); }

	public ScaleSpaceDetectionParameters(
			final Collection< ViewDescription > toProcess,
			final ImgLoader imgloader )
	{
		super( toProcess, imgloader );
	}
}
