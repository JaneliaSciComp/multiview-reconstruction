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
package net.preibisch.mvrecon.process.interestpointdetection.methods;

import java.util.Collection;

import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.ViewDescription;
import net.preibisch.mvrecon.process.interestpointdetection.OverlappingRegions;

public class InterestPointParameters
{
	public Collection< ViewDescription > toProcess;
	public ImgLoader imgloader;

	public double minIntensity = Double.NaN;
	public double maxIntensity = Double.NaN;

	// downsampleXY == 0 : a bit less then z-resolution
	// downsampleXY == -1 : a bit more then z-resolution
	public int downsampleXY = 1, downsampleZ = 1;

	public double showProgressMin = Double.NaN;
	public double showProgressMax = Double.NaN;

	/** the regions of the views to detect in (OverlappingRegions, "only in regions overlapping other views"); null = the whole view */
	public OverlappingRegions regions = null;

	public InterestPointParameters() {}

	public InterestPointParameters(
			final Collection< ViewDescription > toProcess,
			final ImgLoader imgloader )
	{
		this.toProcess = toProcess;
		this.imgloader = imgloader;
	}

	public boolean showProgress() { return !Double.isNaN( showProgressMin ) && !Double.isNaN( showProgressMax ); }

	/**
	 * 
	 * @param min - if not Double.NaN show progress, e.g. this is 0 (for multiple timepoints e.g. 0.25)
	 * @param max - if not Double.NaN show progress, e.g. this is 1 (for multiple timepoints e.g. 0.50)
	 */
	public void showProgress( final double min, final double max )
	{
		this.showProgressMin = min;
		this.showProgressMax = max;
	}
}
