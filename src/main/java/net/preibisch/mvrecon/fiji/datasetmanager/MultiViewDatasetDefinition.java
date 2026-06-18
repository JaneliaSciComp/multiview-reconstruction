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
package net.preibisch.mvrecon.fiji.datasetmanager;

import net.preibisch.mvrecon.fiji.spimdata.SpimData2;

public interface MultiViewDatasetDefinition
{
	/**
	 * Defines the title under which it will be displayed in the list
	 * of available multi-view dataset definitions
	 * 
	 * @return the title
	 */
	public String getTitle();
	
	/**
	 * An explanation for the user what exactly this {@link MultiViewDatasetDefinition}
	 * supports and how it needs to be stored. Up to 15 lines will be displayed with
	 * 80 characters each. No newline characters are allowed.
	 * 
	 * @return description
	 */
	public String getExtendedDescription();
	
	/**
	 * This method is supposed to (interactively, ideally ImageJ-macroscriptable)
	 * query all necessary data from the user to build up a SpimData object and
	 * save it as an XML file.
	 * 
	 * @param xmlFileName - the filename of the XML that was selected (no path!)
	 * @return - the saved {@link SpimData2} object
	 */
	public SpimData2 createDataset( final String xmlFileName );
	
	/**
	 * @return - a new instance of this implementation
	 */
	public MultiViewDatasetDefinition newInstance();

	/**
	 * @return if the XML can be stored somewhere else but where the data is located
	 */
	public default boolean supportsRemoteXMLLocation() { return false; }
}
