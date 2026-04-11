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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.OverlapDetection;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.SimpleBoundingBoxOverlap;

public abstract class PairwiseSetup< V extends Comparable< V > >
{
	protected List< V > views;
	protected Set< Group< V > > groups;
	protected List< Pair< V, V > > pairs;
	protected ArrayList< Subset< V > > subsets;

	/**
	 * Sets up all pairwise comparisons
	 * 
	 * 1) definePairs()
	 * 2) removeNonOverlappingPairs() - if wanted
	 * 3) reorderPairs() - if wanted
	 * 4) detectSubsets()
	 * 5) sortSubsets() - if wanted
	 * 6) getSubsets()
	 * 
	 * within each subset
	 * 
	 * 7) subset.fixViews( this.getDefaultFixedViews() )
	 * 8) subset.fixViews() - fixed some of the views necessary for the strategy to work
	 * 
	 * @param views - all the views to include
	 * @param groups - view groups to include
	 */
	public PairwiseSetup( final List< V > views, final Set< Group< V > > groups )
	{
		this.views = views;

		// if there are views inside groups that are not in List< V > views, remove them from groups
		this.groups = removeNonExistentViewsInGroups( views, groups );
	}

	public PairwiseSetup( final List< V > views ) { this( views, views.stream().map( v -> new Group< V >(v) ).collect( Collectors.toSet() ) ); }

	public List< V > getViews() { return views; }
	public Set< Group< V > > getGroups() { return groups; }
	public List< Pair< V, V > > getPairs() { return pairs; }
	public ArrayList< Subset< V > > getSubsets() { return subsets; }

	/**
	 * Directly set the pairs list (used for optimized pair generation that bypasses definePairs()).
	 * After calling this, you should call detectSubsets() to build the subset structure.
	 *
	 * @param pairs - the list of pairs to set
	 */
	public void setPairs( final List< Pair< V, V > > pairs ) { this.pairs = pairs; }

	/**
	 * Given a list of views and their grouping, identify all pairs that need to be compared
	 * 
	 * @return - redundant pairs that were removed
	 */
	public ArrayList< Pair< V, V > > definePairs()
	{
		// define all pairs
		this.pairs = definePairsAbstract();

		// removed those who were in the same group
		final ArrayList< Pair< V, V > > removed = removeRedundantPairs( pairs, groups );

		// return the removed ones
		return removed;
	}

	/**
	 * abstract method called by the public definePairs method
	 * @return - the list of pairs
	 */
	protected abstract List< Pair< V, V > > definePairsAbstract();

	/**
	 * Remove pairs that are not overlapping.
	 * Uses optimized parallel implementation for SimpleBoundingBoxOverlap with ViewId.
	 *
	 * @param ovlp - implementation of {@link OverlapDetection}
	 * @return a list of pairs that were removed (not stored in this object)
	 */
	@SuppressWarnings("unchecked")
	public ArrayList< Pair< V, V > > removeNonOverlappingPairs( final OverlapDetection< V > ovlp )
	{
		// Use optimized parallel implementation for SimpleBoundingBoxOverlap with ViewId
		if (ovlp instanceof SimpleBoundingBoxOverlap && !views.isEmpty() && views.get(0) instanceof ViewId)
		{
			final SimpleBoundingBoxOverlap<ViewId> sbbo = (SimpleBoundingBoxOverlap<ViewId>) ovlp;
			final List<Pair<ViewId, ViewId>> viewIdPairs = (List<Pair<ViewId, ViewId>>) (List<?>) pairs;
			final Collection<ViewId> viewIdViews = (Collection<ViewId>) (Collection<?>) views;

			final ArrayList<Pair<ViewId, ViewId>> removed = sbbo.removeNonOverlappingPairsOptimized(viewIdPairs, viewIdViews);
			return (ArrayList<Pair<V, V>>) (ArrayList<?>) removed;
		}

		// Fallback to sequential implementation for other OverlapDetection types
		final ArrayList< Pair< V, V > > removed = removeNonOverlappingPairs( pairs, ovlp );
		return removed;
	}

	/**
	 * Reorder the pairs so that the "smaller" view comes first
	 */
	public void reorderPairs() { reorderPairs( pairs ); }

	/**
	 * Given a list of pairs of views that need to be compared, find subsets that are not overlapping
	 */
	public void detectSubsets() { this.subsets = detectSubsets( views, pairs, groups ); }

	/**
	 * Sorts each subset by comparing the first view of each pair, and then all subsets according to their first pair
	 *
	 * 
	 */
	public void sortSubsets()
	{
		sortSets( subsets, new Comparator< Pair< V, V > >()
		{
			@Override
			public int compare( final Pair< V, V > o1, final Pair< V, V > o2 ) { return o1.getA().compareTo( o2.getA() ); }
		} );
	}

	/**
	 * Get a list of fixed views necessary for the specific strategy to work
	 * @return list of default fixed views
	 */
	public abstract List< V > getDefaultFixedViews();

	/**
	 * Fix an additional list of views (removes them from pairs and subsets)
	 * 
	 * @param fixedViews the fixed views (will be modified)
	 * @return - all the removed views
	 */
	public ArrayList< Pair< V, V > > fixViewsInAllSubsets( final List< V > fixedViews )
	{
		final ArrayList< Pair< V, V > > removed = new ArrayList<>();

		for ( final Subset< V > subset : subsets )
			removed.addAll( subset.fixViews( fixedViews ) );

		return removed;
	}

	/**
	 * Remove all views in groups that do not exist in the views list - called from constructor
	 * 
	 * @param views the views to keep
	 * @param groups the groups
	 * @param <V> anything, but most likely extending ViewId
	 * @return new set of groups
	 */
	public static < V > Set< Group< V > > removeNonExistentViewsInGroups(
			final List< V > views,
			final Set< Group< V > > groups )
	{
		if ( groups.size() == 0 )
			return groups;

		final HashSet< V > viewsSet = new HashSet<>();
		viewsSet.addAll( views );

		final HashSet< Group< V > > newGroups = new HashSet<>();

		for ( final Group< V > group : groups )
		{
			final ArrayList< V > removeGroup = new ArrayList<>();

			for ( final V view : group )
			{
				if ( !viewsSet.contains( view ) )
					removeGroup.add( view );
			}

			group.getViews().removeAll( removeGroup );

			if ( group.size() > 0 )
				newGroups.add( group );
		}

		return newGroups;
	}

	/**
	 * Checks all pairs if both views are contained in the same group,
	 * and if so removes the pair from the list - called from definePairs()
	 * 
	 * @param pairs - the pairs, will me modified
	 * @param groups - the groups
	 * @param <V> view id type
	 * @return - removed pairs
	 */
	public static < V > ArrayList< Pair< V, V > > removeRedundantPairs(
			final List< Pair< V, V > > pairs,
			final Set< Group< V > > groups )
	{
		final ArrayList< Pair< V, V > > removed = new ArrayList<>();

		for ( int i = pairs.size() - 1; i >= 0; --i )
		{
			final Pair< V, V > pair = pairs.get( i );

			final V viewA = pair.getA();
			final V viewB = pair.getB();

			// if both views of a pair are contained in the same group
			// we can safely remove this pair

			boolean removedPair = false;

			for ( final Group< V > group : groups )
			{
				if ( group.contains( viewA ) && group.contains( viewB ) )
				{
					pairs.remove( i );
					removed.add( pair );
					removedPair = true;

					break;
				}
			}
			
			// we removed pair on first test, nothing more to be done

			if ( removedPair )
				continue;

			// now test if the groups that both views belong to overlap,
			// because if they do, there is no point in comparing this pair
			final ArrayList< Group< V > > memberA = Group.memberOf( viewA, groups );
			final ArrayList< Group< V > > memberB = Group.memberOf( viewB, groups );

			for ( int a = 0; a < memberA.size() && !removedPair; ++a )
				for ( int b = 0; b < memberB.size() && !removedPair; ++b )
				{
					if ( Group.overlaps( memberA.get( a ), memberB.get( b ) ) )
					{
						pairs.remove( i );
						removed.add( pair );
						removedPair = true;
					}
				}
		}

		return removed;
	}

	/**
	 * Remove pairs that are not overlapping
	 * 
	 * @param pairs - the pairs, will be modified
	 * @param ovlp - implementation of {@link OverlapDetection}
	 * @param <V> view id type
	 * @return a list of pairs that were removed
	 */
	public static < V > ArrayList< Pair< V, V > > removeNonOverlappingPairs(
			final List< Pair< V, V > > pairs,
			final OverlapDetection< V > ovlp )
	{
		final ArrayList< Pair< V, V > > removed = new ArrayList<>();

		for ( int i = pairs.size() - 1; i >= 0; --i )
		{
			final Pair< V, V > pair = pairs.get( i );

			if ( !ovlp.overlaps( pair.getA(), pair.getB() ) )
			{
				pairs.remove( i );
				removed.add( pair );
			}
		}

		return removed;
	}

	/*
	 * Reorder the pairs so that the "smaller" view comes first
	 */
	public static < V extends Comparable< V > > void reorderPairs( final List< Pair< V, V > > pairs )
	{
		for ( int i = 0; i < pairs.size(); ++i )
		{
			final Pair< V, V > pair = pairs.get( i );

			final V v1 = pair.getA();
			final V v2 = pair.getB();

			if ( v1.compareTo( v2 ) <= 0 )
				pairs.set( i, pair );
			else
				pairs.set( i, new ValuePair< V, V >( v2, v1 ) );
		}
	}

	/**
	 * Given a list of views, pairs of views that need to be compared, and groups - find subsets that are not overlapping
	 * 
	 * @param views - all views involved
	 * @param pairs - all pairs that need to be compared
	 * @param groups - all groups of views (transformed together)
	 * @param <V> - view id type
	 * @return - the found subsets
	 */
	public static < V > ArrayList< Subset< V > > detectSubsets(
			final List< V > views,
			final List< Pair< V, V > > pairs,
			final Set< Group< V > > groups )
	{
		// list of subset-precursors
		final ArrayList< HashSet< V > > vSets = new ArrayList<>();

		// list of pairs within subset-precursors (1st ArrayList-index is the same as in vSets)
		final ArrayList< ArrayList< Pair< V, V > > > pairSets = new ArrayList<>();

		// group all views by which will be compared
		for ( final Pair< V, V > pair : pairs )
		{
			final V v1 = pair.getA();
			final V v2 = pair.getB();

			final int i1 = setId( v1, vSets );
			final int i2 = setId( v2, vSets );

			// both are not contained in any subset-precursor HashSet
			if ( i1 == -1 && i2 == -1 )
			{
				// make a new subset-precursor HashSet and List of view-pairs
				final HashSet< V > vSet = new HashSet<>();
				final ArrayList< Pair< V, V > > pairSet = new ArrayList<>();

				pairSet.add( pair );
				vSet.add( v1 );
				vSet.add( v2 );

				vSets.add( vSet );
				pairSets.add( pairSet );
			}
			else if ( i1 == -1 && i2 >= 0 ) // the first view is not present anywhere, but the second is
			{
				// add the first view to the second subset-precursor HashSet and to the list of view-pairs
				vSets.get( i2 ).add( v1 );
				pairSets.get( i2 ).add( pair );
			}
			else if ( i1 >= 0 && i2 == -1 ) // the second view is not present anywhere, but the first is
			{
				// add the second view to the first subset-precursor HashSet and to the list of view-pairs
				vSets.get( i1 ).add( v2 );
				pairSets.get( i1 ).add( pair );
			}
			else if ( i1 == i2 ) // both are already present in the same set
			{
				// add the new pair to the subset-precursor
				pairSets.get( i1 ).add( pair );
			}
			else // both are present in different sets, the sets need to be merged
			{
				mergeSets( vSets, pairSets, i1, i2 );

				// The merged set is now at the end, add the current pair to it
				pairSets.get( pairSets.size() - 1 ).add( pair );
			}
		}

		// find individual views that are not part of a subset-precursor
		for ( final V v : views )
		{
			boolean isPresent = false;

			for ( final HashSet< V > subsetPrecursor : vSets )
				if ( subsetPrecursor.contains( v ) )
					isPresent = true;

			// add a new subset-precursor that only contains a single view, no pairs
			if ( !isPresent )
			{
				final HashSet< V > vSet = new HashSet<>();
				final ArrayList< Pair< V, V > > pairSet = new ArrayList<>();

				vSet.add( v );

				vSets.add( vSet );
				pairSets.add( pairSet );
			}
		}

		// now check if some of the sets are linked by grouping
		for ( final Group< V > group : groups )
			mergeSets( vSets, pairSets, subsetsLinkedByGroup( vSets, group ) );

		// make the final subsets containing the list of views, list of pairs, and list of groups contained in this subset
		final ArrayList< Subset< V > > subsets = new ArrayList<>();

		for ( int i = 0; i < vSets.size(); ++i )
		{
			final ArrayList< Pair< V, V > > setPairs = pairSets.get( i );
			final HashSet< V > setsViews = vSets.get( i );

			subsets.add( new Subset<>( setsViews, setPairs, findGroupsAssociatedWithSubset( setsViews, groups ) ) );
		}

		return subsets;
	}

	protected static < V > int setId( final V v, final ArrayList< HashSet< V > > vSets )
	{
		int i = -1;

		// does any of the subset-precursor HashSets contain the view?
		for ( int j = 0; j < vSets.size(); ++j )
		{
			if ( vSets.get( j ).contains( v ) )
				i = j;
		}

		return i;
	}

	/*
	 * Find all groups that contain views from this "potential" subset
	 * 
	 * @param setsViews
	 * @param groups
	 * @return 
	 */
	public static < V > HashSet< Group< V > > findGroupsAssociatedWithSubset( final HashSet< V > setsViews, final Set< Group< V > > groups )
	{
		final HashSet< Group< V > > associated = new HashSet<>();

		for ( final V view : setsViews )
		{
			for ( final Group< V > group : groups )
			{
				if ( group.contains( view ) )
				{
					associated.add( group );
					break;
				}
			}
		}

		return associated;
	}

	/** 
	 * which subsets are part of a group - called by detectSubsets()
	 * 
	 * @param group a group of views
	 * @param vSets list of view sets
	 * @param <V> view id type
	 * @return set of indices of sets in vSets that contain a view of group
	 */
	public static < V > HashSet< Integer > subsetsLinkedByGroup( final List< ? extends Set< V > > vSets, final Group< V > group )
	{
		final HashSet< Integer > contained = new HashSet<>();

		// for each view of the group
		for ( final V view : group )
		{
			// is it present in more than one set?
			for ( int j = 0; j < vSets.size(); ++j )
			{
				if ( vSets.get( j ).contains( view ) )
				{
					// if so, remember this set-id for merging
					contained.add( j );
				}
			}
		}

		return contained;
	}

	/**
	 * Merge two sets with indices i1, i2
	 *
	 * @param vSets - subset precursors
	 * @param pairSets - sets of pairs to compare
	 * @param i1 - first index to merge
	 * @param i2 - second index to merge
	 * @param <V> view id type
	 */
	public static < V > void mergeSets(
			final ArrayList< HashSet< V > > vSets,
			final ArrayList< ArrayList< Pair< V, V > > > pairSets,
			final int i1, final int i2 )
	{
		final ArrayList< Integer > mergeIndicies = new ArrayList<>();
		mergeIndicies.add( i1 );
		mergeIndicies.add( i2 );
		mergeSets( vSets, pairSets, mergeIndicies );
	}

	/**
	 * Merge N sets with indices
	 *
	 * Note: This method collects all views and pairs from the sets being merged,
	 * removes the old sets from vSets and pairSets, and adds the merged set at the END.
	 * After this method completes, the merged set will be at index (pairSets.size() - 1).
	 *
	 * @param vSets - subset precursors
	 * @param pairSets - sets of pairs to compare
	 * @param mergeIndicies - indices to merge
	 * @param <V> view id type
	 */
	public static < V > void mergeSets(
			final ArrayList< HashSet< V > > vSets,
			final ArrayList< ArrayList< Pair< V, V > > > pairSets,
			final Collection< Integer > mergeIndicies )
	{
		if ( mergeIndicies.size() <= 1 )
			return;

		final ArrayList< Integer > list = new ArrayList<>();
		list.addAll( mergeIndicies );
		Collections.sort( list ); // sort indicies from small to large

		final ArrayList< Pair< V, V > > pairSet = new ArrayList<>();
		final HashSet< V > vSet = new HashSet<>();

		// Collect all pairs and views from the sets being merged
		for ( int i = 0; i < list.size(); ++i )
		{
			pairSet.addAll( pairSets.get( list.get( i ) ) );
			vSet.addAll( vSets.get( list.get( i ) ) );
		}

		// remove indicies from large down to small (to avoid index shifting issues)
		for ( int i = list.size() - 1; i >= 0; --i )
		{
			pairSets.remove( (int)list.get( i ) );
			vSets.remove( (int)list.get( i ) );
		}

		// Add the merged set at the END of the lists
		pairSets.add( pairSet );
		vSets.add( vSet );
	}

	/**
	 * Sorts each list using a given comparator, and then the lists according to their first element
	 *
	 * @param subsets the subsets to be sorted
	 * @param comp the comparator
	 * @param <V> viewId type
	 */
	public static < V > void sortSets( final ArrayList< Subset< V > > subsets, final Comparator< Pair< V, V > > comp )
	{
		for ( final Subset< V > set : subsets )
			Collections.sort( set.getPairs(), comp );

		final Comparator< Subset< V > > listComparator = new Comparator< Subset< V > >()
		{
			@Override
			public int compare( final Subset< V > o1, final Subset< V > o2 )
			{
				if ( o1.getPairs().size() == 0 && o2.getPairs().size() == 0 )
					return 0;
				else if ( o1.getPairs().size() == 0 )
					return -1;
				else if ( o2.getPairs().size() == 0 )
					return 1;
				else
					return comp.compare( o1.getPairs().get( 0 ), o2.getPairs().get( 0 ) );
			}
		};

		Collections.sort( subsets, listComparator );
	}

	public static void main( String[] args )
	{
		ArrayList< Integer > list = new ArrayList<>();
		list.add( 2 );
		list.add( 10 );
		list.add( 5 );

		Collections.sort( list );

		for ( int i = 0; i < list.size(); ++i )
			System.out.println( list.get( i ) );
	}
}
