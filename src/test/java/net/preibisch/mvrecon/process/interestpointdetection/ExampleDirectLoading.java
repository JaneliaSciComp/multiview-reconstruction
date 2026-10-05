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
package net.preibisch.mvrecon.process.interestpointdetection;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Key;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Points;

/** Reads the interest points of one (view, label) straight from a dataset's {@code interestpoints.zarr}, without the XML. */
public class ExampleDirectLoading
{
	public static void main( String[] args )
	{
		final URI dataset = URI.create( "/nrs/tavakoli/MirrorScope/20260831_ExpID99_BIS_0.03/intensity_correct_ipstore_zarr3/" );
		final Key key = Key.of( new ViewId( 0, 124 ), "beads" );

		final InterestPointsZarrStore store = InterestPointsZarrStore.get( dataset );
		final Points points = store.points( key );
		if ( points == null )
		{
			System.out.println( "no entry " + key.path() + " in " + dataset );
			return;
		}

		final int[] ids = points.ids();
		final double[] loc = points.loc();
		for ( int i = 0; i < ids.length; ++i )
			System.out.println( ids[ i ] + " " + Arrays.toString( Arrays.copyOfRange( loc, 3 * i, 3 * i + 3 ) ) );

		for ( final Map.Entry< String, double[] > attribute : points.attributes().entrySet() )
			System.out.println( attribute.getKey() + ": " + attribute.getValue().length + " values" );
	}
}
