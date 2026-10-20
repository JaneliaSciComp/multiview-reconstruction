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
package net.preibisch.mvrecon.fiji.spimdata.interestpoints;

public class XmlKeysInterestPoints
{
	public static final String VIEWINTERESTPOINTS_TAG = "ViewInterestPoints";
	public static final String VIEWINTERESTPOINTSFILE_TAG = "ViewInterestPointsFile";
	public static final String VIEWINTERESTPOINTS_TIMEPOINT_ATTRIBUTE_NAME = "timepoint";
	public static final String VIEWINTERESTPOINTS_SETUP_ATTRIBUTE_NAME = "setup";
	public static final String VIEWINTERESTPOINTS_LABEL_ATTRIBUTE_NAME = "label";
	public static final String VIEWINTERESTPOINTS_PARAMETERS_ATTRIBUTE_NAME = "params";
	// a filtered label (VirtualFilteredInterestPointsN5) only: the label it filters and the filter (InterestPointFilter.toString)
	public static final String VIEWINTERESTPOINTS_BASE_ATTRIBUTE_NAME = "base";
	public static final String VIEWINTERESTPOINTS_FILTER_ATTRIBUTE_NAME = "filter";
}
