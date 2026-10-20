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
package net.preibisch.mvrecon.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.DifferenceOfGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.DifferenceOfGaussianGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.ScaleSpaceGUI;

/**
 * The detection dialogs (DifferenceOfGUI, shared by the DoG and the scale space): the specification entries of the
 * scale-space dialog and the filtered labels they offer
 */
public class TestScaleSpaceGUI
{
	@Test
	public void testSpecificationChoices()
	{
		// no presets: Advanced and Interactive only, Interactive preselected
		assertArrayEquals( new String[] { "Advanced ...", "Interactive ..." }, ScaleSpaceGUI.specificationChoice );
		assertEquals( 1, ScaleSpaceGUI.defaultSpecification );
	}

	@Test
	public void testFilters()
	{
		// no filter unless chosen in the dialog, "Do not filter interest points" is the default of both methods
		assertEquals( 3, DifferenceOfGUI.filterChoice.length );
		assertEquals( "Do not filter interest points", DifferenceOfGUI.filterChoice[ DifferenceOfGUI.filterChoice.length - 1 ] );
		assertNull( new DifferenceOfGaussianGUI( null, null ).getFilter() );
		assertNull( new ScaleSpaceGUI( null, null ).getFilter() );
	}
}
