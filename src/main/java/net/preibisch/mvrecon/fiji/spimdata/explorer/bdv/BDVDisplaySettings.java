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
package net.preibisch.mvrecon.fiji.spimdata.explorer.bdv;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import bdv.tools.brightness.ConverterSetup;
import bdv.tools.brightness.MinMaxGroup;
import bdv.tools.brightness.SetupAssignments;
import net.preibisch.mvrecon.fiji.spimdata.displaysettings.DisplaySettings;
import net.preibisch.mvrecon.fiji.spimdata.displaysettings.DisplaySettings.DisplayRange;

/**
 * Between the brightness settings of a running BigDataViewer and the dataset's {@link DisplaySettings}: the displayed
 * range is the converter setup's, the slider limits are those of the brightness group (MinMaxGroup, one row of the
 * brightness dialog) the setup is in.
 */
public class BDVDisplaySettings
{
	/** @return the current range of a converter setup with the slider limits of its group */
	public static DisplayRange range( final ConverterSetup setup, final SetupAssignments assignments )
	{
		final MinMaxGroup group = assignments.getMinMaxGroup( setup );
		final double min = setup.getDisplayRangeMin(), max = setup.getDisplayRangeMax();

		return new DisplayRange( min, max, group == null ? Double.NaN : group.getRangeMin(), group == null ? Double.NaN : group.getRangeMax() ).valid();
	}

	/**
	 * Takes the ranges of the sources BigDataViewer shows right now (all view setups, or the selected ones of the lazy
	 * viewer); setups it does not show keep what they have.
	 *
	 * @param setupIds - the view setups of the dataset, other converter setups (previews) are skipped
	 * @return how many ranges changed
	 */
	public static int capture( final DisplaySettings settings, final SetupAssignments assignments, final Collection< Integer > setupIds )
	{
		int changed = 0;

		for ( final ConverterSetup setup : new ArrayList<>( assignments.getConverterSetups() ) )
			if ( setupIds.contains( setup.getSetupId() ) && settings.set( setup.getSetupId(), range( setup, assignments ) ) )
				++changed;

		return changed;
	}

	/**
	 * Sets the stored ranges: the setups of one range end up in one brightness group that holds no other setup (they are
	 * moved only if they are not yet, so that the common case of one range for all costs no regrouping), the group gets the
	 * limits and the range. Setups without a stored range are left as they are.
	 *
	 * @return for how many setups a range was set
	 */
	public static int apply( final DisplaySettings settings, final SetupAssignments assignments )
	{
		final IdentityHashMap< MinMaxGroup, Integer > groupSizes = new IdentityHashMap<>();
		final LinkedHashMap< DisplayRange, List< ConverterSetup > > setupsPerRange = new LinkedHashMap<>();

		for ( final ConverterSetup setup : new ArrayList<>( assignments.getConverterSetups() ) )
		{
			final MinMaxGroup group = assignments.getMinMaxGroup( setup );
			if ( group != null )
				groupSizes.merge( group, 1, Integer::sum );

			final DisplayRange range = settings.get( setup.getSetupId() );
			if ( range != null && group != null )
				setupsPerRange.computeIfAbsent( range.valid(), r -> new ArrayList<>() ).add( setup );
		}

		int applied = 0;

		for ( final Map.Entry< DisplayRange, List< ConverterSetup > > e : setupsPerRange.entrySet() )
		{
			final List< ConverterSetup > setups = e.getValue();
			final ConverterSetup first = setups.get( 0 );
			MinMaxGroup group = assignments.getMinMaxGroup( first );

			// the group is theirs alone if it had as many setups as they are and all of them are (still) in it; sizes only shrink
			// by the moves below, and a group created below is not in the map
			boolean own = groupSizes.getOrDefault( group, -1 ) == setups.size();
			for ( int i = 1; i < setups.size() && own; ++i )
				own = assignments.getMinMaxGroup( setups.get( i ) ) == group;

			if ( !own )
			{
				assignments.removeSetupFromGroup( first, group ); // into a new group, unless it is alone already
				group = assignments.getMinMaxGroup( first );

				for ( int i = 1; i < setups.size(); ++i )
					assignments.moveSetupToGroup( setups.get( i ), group );
			}

			set( group, e.getKey() );

			for ( final ConverterSetup setup : setups )
			{
				setup.setDisplayRange( e.getKey().min(), e.getKey().max() );
				++applied;
			}
		}

		return applied;
	}

	/** sets the slider limits and the range of a group (and with it of the setups in it) */
	public static void set( final MinMaxGroup group, final DisplayRange range )
	{
		final DisplayRange r = range.valid();

		group.setRange( r.rangeMin(), r.rangeMax() );
		// max first, at the upper limit, so that min is not pushed by an old max
		group.getMaxBoundedValue().setCurrentValue( r.rangeMax() );
		group.getMinBoundedValue().setCurrentValue( r.min() );
		group.getMaxBoundedValue().setCurrentValue( r.max() );
	}
}
