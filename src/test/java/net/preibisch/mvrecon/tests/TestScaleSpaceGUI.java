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
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.DifferenceOfGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.ScaleSpaceGUI;

/**
 * The static helpers of the detection dialogs (DifferenceOfGUI, shared by the DoG and the scale space): the default thresholds of a
 * multi-threshold run, the label suffixes and the dialog entries; and the specification entries of the scale-space dialog
 */
public class TestScaleSpaceGUI
{
	@Test
	public void testDefaultThresholds()
	{
		// geometric from very low to very high, two significant digits
		assertArrayEquals( new double[] { 0.001, 0.1 }, DifferenceOfGUI.defaultThresholds( 2 ), 0.0 );
		assertArrayEquals( new double[] { 0.001, 0.01, 0.1 }, DifferenceOfGUI.defaultThresholds( 3 ), 0.0 );
		assertArrayEquals( new double[] { 0.001, 0.0046, 0.022, 0.1 }, DifferenceOfGUI.defaultThresholds( 4 ), 0.0 );
		assertArrayEquals( new double[] { 0.001, 0.0032, 0.01, 0.032, 0.1 }, DifferenceOfGUI.defaultThresholds( 5 ), 0.0 );
		// from six on the range starts at 0.0001
		assertArrayEquals( new double[] { 0.0001, 0.0004, 0.0016, 0.0063, 0.025, 0.1 }, DifferenceOfGUI.defaultThresholds( 6 ), 0.0 );

		// N: still ascending between the bounds
		final double[] eight = DifferenceOfGUI.defaultThresholds( 8 );
		assertEquals( 8, eight.length );
		assertEquals( 0.0001, eight[ 0 ], 0.0 );
		assertEquals( 0.1, eight[ 7 ], 0.0 );
		for ( int i = 1; i < eight.length; ++i )
			assertEquals( true, eight[ i ] > eight[ i - 1 ] );

		// a multi-threshold run has at least two
		assertThrows( IllegalArgumentException.class, () -> DifferenceOfGUI.defaultThresholds( 1 ) );

		// the dialog entries
		assertEquals( "Single threshold", DifferenceOfGUI.thresholdCountChoice[ 0 ] );
		assertEquals( "6 thresholds", DifferenceOfGUI.thresholdCountChoice[ 5 ] );
		assertEquals( "N thresholds", DifferenceOfGUI.thresholdCountChoice[ 6 ] );
		assertEquals( 0, DifferenceOfGUI.defaultThresholdCountChoice );
	}

	@Test
	public void testSuffix()
	{
		assertEquals( "_t0.004", DifferenceOfGUI.thresholdSuffix( 0.004 ) );
		assertEquals( "_t0.02", DifferenceOfGUI.thresholdSuffix( 0.02 ) );
		assertEquals( "_t0.0001", DifferenceOfGUI.thresholdSuffix( 1e-4 ) );
		assertEquals( "_t0.1", DifferenceOfGUI.thresholdSuffix( 0.1 ) );
		assertEquals( "_t1", DifferenceOfGUI.thresholdSuffix( 1.0 ) );
	}

	@Test
	public void testSpecificationChoices()
	{
		// no presets: Advanced and Interactive only, Interactive preselected
		assertArrayEquals( new String[] { "Advanced ...", "Interactive ..." }, ScaleSpaceGUI.specificationChoice );
		assertEquals( 1, ScaleSpaceGUI.defaultSpecification );
	}
}
