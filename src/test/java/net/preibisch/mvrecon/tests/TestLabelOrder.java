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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint.InterestPointTableModel;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;

/**
 * The natural order of the interest point labels in the dialogs and the explorer table: numbers by value (also decimal
 * fractions, as in the multi-threshold labels), text case-insensitively, and a total order
 */
public class TestLabelOrder
{
	@Test
	public void testCompareNatural()
	{
		// multi-threshold labels ascend by threshold, the plain label first
		final List< String > thresholds = Arrays.asList( "beads_t0.1", "beads_t0.022", "beads", "beads_t0.0046", "beads_t0.001", "beads_t0.0001" );
		assertEquals( Arrays.asList( "beads", "beads_t0.0001", "beads_t0.001", "beads_t0.0046", "beads_t0.022", "beads_t0.1" ), InterestPointTools.sortLabels( thresholds ) );

		// integers by value, not alphabetically; numbers before text; case ignored
		assertEquals( Arrays.asList( "nuclei_t1", "nuclei_t2", "nuclei_t10", "nuclei_t100" ), InterestPointTools.sortLabels( Arrays.asList( "nuclei_t100", "nuclei_t10", "nuclei_t2", "nuclei_t1" ) ) );
		assertEquals( Arrays.asList( "10", "a", "B", "c" ), InterestPointTools.sortLabels( Arrays.asList( "c", "B", "a", "10" ) ) );
		assertTrue( InterestPointTools.compareNatural( "Beads", "beads_t0.1" ) < 0 );

		// thresholds >= 1 and mixed fractions
		assertEquals( Arrays.asList( "x_t0.5", "x_t1", "x_t1.5", "x_t2", "x_t10" ), InterestPointTools.sortLabels( Arrays.asList( "x_t10", "x_t2", "x_t1.5", "x_t1", "x_t0.5" ) ) );

		// a total order: equal numbers with different spelling are distinct and consistently ordered
		assertTrue( InterestPointTools.compareNatural( "t007", "t7" ) != 0 );
		assertEquals( -Integer.signum( InterestPointTools.compareNatural( "t007", "t7" ) ), Integer.signum( InterestPointTools.compareNatural( "t7", "t007" ) ) );
		assertEquals( 0, InterestPointTools.compareNatural( "beads_t0.004", "beads_t0.004" ) );
	}

	@Test
	public void testDialogAndTableAgree()
	{
		final HashMap< String, Integer > labels = new HashMap<>();
		labels.put( "beads_t0.1", 2 );
		labels.put( "beads_t0.022", 2 );
		labels.put( "beads", 1 ); // incomplete: present in one of two views
		labels.put( "beads_t0.0046", 2 );

		final List< ViewId > views = new ArrayList<>();
		views.add( new ViewId( 0, 0 ) );
		views.add( new ViewId( 0, 1 ) );

		// the dialog list: natural order, the warning appended to the incomplete label
		final String[] dialog = InterestPointTools.getAllInterestPointLabels( labels, views );
		assertEquals( 4, dialog.length );
		assertTrue( dialog[ 0 ].startsWith( "beads" + InterestPointTools.warningLabel ) );
		assertArrayEquals( new String[] { "beads_t0.0046", "beads_t0.022", "beads_t0.1" }, Arrays.copyOfRange( dialog, 1, 4 ) );

		// the explorer table uses the same order
		for ( int row = 0; row < 4; ++row )
			assertEquals( Arrays.asList( "beads", "beads_t0.0046", "beads_t0.022", "beads_t0.1" ).get( row ), InterestPointTableModel.label( labels, row ) );
	}
}
