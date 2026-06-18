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
package net.preibisch.mvrecon.fiji.spimdata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;

public class GroupedViews extends ViewId
{
	final List< ViewId > group;

	/**
	 * A group of ViewIds that represents itself as the first ViewId
	 * 
	 * @param group - the ViewIds
	 */
	public GroupedViews( final List< ViewId > group )
	{
		super( group.get( 0 ).getTimePointId(), group.get( 0 ).getViewSetupId() );

		this.group = group;
	}


	public List< ViewId > getViewIds() { return group; }

	public static ArrayList< GroupedViews > groupByChannel( final List< ViewId > viewIds, final SequenceDescription sd )
	{
		final ArrayList< ViewId > input = new ArrayList<>();
		final ArrayList< GroupedViews > grouped = new ArrayList<>();

		input.addAll( viewIds );

		while ( input.size() > 0 )
		{
			final ViewDescription vd1 = sd.getViewDescription( input.get( 0 ) );
			final ArrayList< ViewId > localGroup = new ArrayList<>();
			localGroup.add( vd1 );
			input.remove( 0 );

			for ( int i = input.size() - 1; i >=0; --i )
			{
				boolean attributesSame = true;

				final ViewDescription vd2 = sd.getViewDescription( input.get( i ) );

				// same timepoint, different channel
				if ( vd1.getTimePointId() == vd2.getTimePointId() && vd1.getViewSetup().getChannel().getId() != vd2.getViewSetup().getChannel().getId() )
				{
					final Map< String, Entity > map1 = vd1.getViewSetup().getAttributes();
					final Map< String, Entity > map2 = vd2.getViewSetup().getAttributes();

					for ( final String key : map1.keySet() )
					{
						if ( key.toLowerCase().equals( "channel" ) )
							continue;

						if ( map1.containsKey( key ) && map2.containsKey( key ) )
						{
							if ( !map1.get( key ).equals( map2.get( key ) ) )
								attributesSame = false;
						}
						else
						{
							attributesSame = false;
						}
					}
				}
				else
				{
					attributesSame = false;
				}
				
				if ( attributesSame )
				{
					localGroup.add( vd2 );
					input.remove( i );
				}
			}

			// sort by channel, so it is always the same order
			Collections.sort( localGroup, new Comparator< ViewId >()
			{
				@Override
				public int compare( final ViewId o1, final ViewId o2 )
				{
					return sd.getViewDescription( o1 ).getViewSetup().getChannel().getId() - sd.getViewDescription( o2 ).getViewSetup().getChannel().getId();
				}
			} );

			grouped.add( new GroupedViews( localGroup ) );
		}

		return grouped;
	}
}
