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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;

import mpicbg.spim.data.sequence.ViewId;

/**
 * Interface for grouping interest points from multiple views that are in one group
 * 
 * @author spreibi
 */
public abstract class InterestPointGrouping< V extends ViewId > implements Grouping< V, HashMap< String, Collection< GroupedInterestPoint< V > > > >
{
	// all interestpoints
	final Map< V, HashMap< String, Collection< InterestPoint > > > interestpoints;

	final HashMap< String, Long > before = new HashMap<>();
	final HashMap< String, Long > after = new HashMap<>();

	public InterestPointGrouping( final Map< V, HashMap< String, Collection< InterestPoint > > > interestpoints )
	{
		this.interestpoints = interestpoints;
	}

	public HashMap< String, Long >  countBefore() { return before; }
	public HashMap< String, Long >  countAfter() { return after; }

	@Override
	public HashMap< String, Collection< GroupedInterestPoint< V > > > group( final Group< V > group )
	{
		final Map< V, HashMap< String, Collection< InterestPoint > > > toMerge = new HashMap<>();

		this.before.clear();
		this.after.clear();

		// init
		for ( final V view : group )
		{
			toMerge.putIfAbsent( view, new HashMap<>() );
			interestpoints.get( view ).forEach( (label,points) ->
				this.before.putIfAbsent(label, 0l) );
		}

		// prep merge
		for ( final V view : group )
		{
			interestpoints.get( view ).forEach( (label,points) -> {

				if ( points == null )
					throw new RuntimeException( "no interestpoints available" );

				before.put( label, before.get( label ) + points.size() );
		
				toMerge.get( view ).put( label, points );
			});

			/*
			final List< InterestPoint > points = interestpoints.get( view );

			if ( points == null )
				throw new RuntimeException( "no interestpoints available" );

			before += points.size();
	
			toMerge.put( view, points ); */
		}

		// merge
		final HashMap< String, Collection< GroupedInterestPoint< V > > > merged = merge( toMerge );

		// statistics
		for ( final V view : group )
			interestpoints.get( view ).forEach( (label,points) ->
				this.after.put( label, (long)merged.get( label ).size() ) );

		return merged;
	}

	protected abstract HashMap< String, Collection< GroupedInterestPoint< V > > > merge( Map< V, HashMap< String, Collection< InterestPoint > > > toMerge );
}
