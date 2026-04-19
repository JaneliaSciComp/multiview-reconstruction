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
package net.preibisch.mvrecon.fiji.plugin.fusion;

import java.util.Collection;
import java.util.List;

import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Interval;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.process.export.ImgExport;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public interface FusionExportInterface
{
	SpimData2 getSpimData();

	/**
	 * 0 == "32-bit floating point",
	 * 1 == "16-bit unsigned integer"
	 *
	 * @return the pixel type
	 */
	int getPixelType();

	/**
	 * 0 == "Each timepoint &amp; channel",
	 * 1 == "Each timepoint, channel &amp; illumination",
	 * 2 == "All views together",
	 * 3 == "Each view"
	 * 
	 * @return - how the views that are processed are split up
	 */
	int getSplittingType();

	Interval getDownsampledBoundingBox();
	Collection< ? extends ViewId > getViews();

	/**
	 * @return the downsampling used for the fusion, or Double.NaN if no downsampling
	 */
	double getDownsampling();

	/**
	 * @return the average anisotropy factor in z of all views used to "flatten" the fused image, or Double.NaN if no change
	 */
	public double getAnisotropyFactor();

	/**
	 * @return - creates a new instance of the exporter object
	 */
	public ImgExport getNewExporterInstance();

	public List<Group<ViewDescription>> getFusionGroups();
}
