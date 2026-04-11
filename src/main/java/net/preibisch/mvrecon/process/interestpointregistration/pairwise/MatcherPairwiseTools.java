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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.GroupedInterestPoint;

public class MatcherPairwiseTools
{
	public static < V > HashSet< V > allViews( final Group< ? extends V > a, final Group< ? extends V > b )
	{
		final HashSet< V > all = new HashSet<>();
		all.addAll( a.getViews() );
		all.addAll( b.getViews() );

		return all;
	}

	public static < V extends ViewId > Map< V, HashMap< String, List< CorrespondingInterestPoints > > > clearCorrespondences(
			final Collection< V > viewIds,
			final Map< V, ViewInterestPointLists > interestpoints,
			final Map< V, HashMap< String, Double > > labelMap )
	{
		final Map< V, HashMap< String, InterestPoints > > map = new HashMap<>();

		for ( final V view : viewIds )
		{
			final HashMap< String, InterestPoints > mapPerLabel = new HashMap<>();
			labelMap.get( view ).forEach( (label,weight ) -> mapPerLabel.put( label, interestpoints.get( view ).getInterestPointList( label ) ) );
			map.put( view, mapPerLabel );
			//map.put( view, interestpoints.get( view ).getInterestPointList( labelMap.get( view ) ) );
		}

		return clearCorrespondences( map );
	}

	public static < V extends ViewId > Map< V, HashMap< String, List< CorrespondingInterestPoints > > > clearCorrespondences(
			final Map< V, ? extends Map< String, ? extends InterestPoints > > map )
	{
		final Map< V, HashMap< String, List< CorrespondingInterestPoints > > > cMap = new HashMap<>();

		for ( final V viewId : map.keySet() )
		{
			final HashMap< String, List< CorrespondingInterestPoints > > mapPerLabel = new HashMap<>();

			map.get( viewId ).forEach( (label, list ) -> {
				
				final ArrayList< CorrespondingInterestPoints > cList = new ArrayList<>();
				list.setCorrespondingInterestPoints( cList );
				mapPerLabel.put(label, cList);
			} );

			cMap.put( viewId, mapPerLabel );
		}

		return cMap;
	}

	public static < V extends ViewId, P extends PairwiseResult< GroupedInterestPoint< V > > >
		List< Pair< Pair< V, V >, PairwiseResult< GroupedInterestPoint< V > > > >
			addCorrespondencesFromGroups(
			final Collection< ? extends Pair< ?, P > > resultGroup,
			final Map< V, ViewInterestPointLists > interestpoints,
			final Map< V, HashMap< String, List< CorrespondingInterestPoints > > > cMap
			//final Map< V, ? extends List< CorrespondingInterestPoints > > cMap
			)
	{
		// we transform HashMap< Pair< Group < V >, Group< V > >, PairwiseResult > to HashMap< Pair< V, V >, PairwiseResult >
		final HashMap< Pair< V, V >, PairwiseResult< GroupedInterestPoint< V > > > transformedMap = new HashMap<>();

		// it doesn't matter which pair of groups it comes from: ?
		for ( final Pair< ?, P > p : resultGroup )
		{
			final List< Integer > setIds = p.getB().getInlierSetIds();
			int idx = 0;  // Track index for setIds list

			for ( final PointMatchGeneric< GroupedInterestPoint< V > > pm : p.getB().getInliers() )
			{
				// assign correspondences
				final GroupedInterestPoint< V > gpA = pm.getPoint1();
				final GroupedInterestPoint< V > gpB = pm.getPoint2();

				final V viewIdA = gpA.getV();
				final V viewIdB = gpB.getV();

				if ( p.getB().storeCorrespondences() )
				{
					final String labelA = p.getB().getLabelA();//labelMap.get( viewIdA );
					final String labelB = p.getB().getLabelB();//labelMap.get( viewIdB );

					// Get setId from list or default to -1 if null/out of bounds
					final int setId = (setIds != null && setIds.size() > idx) ? setIds.get(idx) : -1;

					final CorrespondingInterestPoints correspondingToA = new CorrespondingInterestPoints( gpA.getId(), viewIdB, labelB, gpB.getId(), setId );
					final CorrespondingInterestPoints correspondingToB = new CorrespondingInterestPoints( gpB.getId(), viewIdA, labelA, gpA.getId(), setId );

					cMap.get( viewIdA ).get( labelA ).add( correspondingToA );
					cMap.get( viewIdB ).get( labelB ).add( correspondingToB );
				}

				++idx;  // Increment index for next inlier

				// update transformedMap
				final Pair< V, V > pair = new ValuePair<>( viewIdA, viewIdB );

				final PairwiseResult< GroupedInterestPoint< V > > pwr;

				if ( transformedMap.containsKey( pair ) )
					pwr = transformedMap.get( pair );
				else
				{
					pwr = new PairwiseResult<>( p.getB().storeCorrespondences() );
					pwr.setInliers( new ArrayList<>(), p.getB().getError() );
					pwr.setCandidates( new ArrayList<>() );
					pwr.setLabelA( p.getB().getLabelA() );
					pwr.setLabelB( p.getB().getLabelB() );
					transformedMap.put( pair, pwr );
				}

				pwr.getInliers().add( pm );
			}

			for ( final PointMatchGeneric< GroupedInterestPoint< V > > pm : p.getB().getCandidates() )
			{
				// assign correspondences
				final GroupedInterestPoint< V > gpA = pm.getPoint1();
				final GroupedInterestPoint< V > gpB = pm.getPoint2();

				final V viewIdA = gpA.getV();
				final V viewIdB = gpB.getV();

				// update transformedMap
				final Pair< V, V > pair = new ValuePair<>( viewIdA, viewIdB );

				final PairwiseResult< GroupedInterestPoint< V > > pwr;

				if ( transformedMap.containsKey( pair ) )
					pwr = transformedMap.get( pair );
				else
				{
					pwr = new PairwiseResult<>( p.getB().storeCorrespondences() );
					pwr.setInliers( new ArrayList<>(), p.getB().getError() );
					pwr.setCandidates( new ArrayList<>() );
					pwr.setLabelA( p.getB().getLabelA() );
					pwr.setLabelB( p.getB().getLabelB() );
					transformedMap.put( pair, pwr );
				}

				pwr.getCandidates().add( pm );
			}

		}

		cMap.forEach( (viewId, label2corr) -> {
			label2corr.forEach( (label, corr ) -> {
				interestpoints.get( viewId ).getInterestPointList( label ).setCorrespondingInterestPoints( corr );
			} );
		});
		/*
		for ( final V viewId : cMap.keySet() )
		{
			// TODO: for each label
			interestpoints.get( viewId ).getInterestPointList( labelMap.get( viewId ) ).setCorrespondingInterestPoints( cMap.get( viewId ) );
		}
		*/
		final ArrayList< Pair< Pair< V, V >, PairwiseResult< GroupedInterestPoint< V > > > > transformedList = new ArrayList<>();

		for ( final Pair< V, V > pair : transformedMap.keySet() )
			transformedList.add( new ValuePair<>( pair, transformedMap.get( pair ) ) );

		return transformedList;
	}

	/*
	 * Add correspondences to the interestpointlists
	 */
	public static < I extends InterestPoint > void addCorrespondences(
			final List< PointMatchGeneric< I > > correspondences,
			final List< Integer > setIds,
			final ViewId viewIdA,
			final ViewId viewIdB,
			final String labelA,
			final String labelB,
			final InterestPoints listA,
			final InterestPoints listB )
	{

		final Collection< CorrespondingInterestPoints > corrListA = listA.getCorrespondingInterestPointsCopy();
		final Collection< CorrespondingInterestPoints > corrListB = listB.getCorrespondingInterestPointsCopy();

		for ( int idx = 0; idx < correspondences.size(); ++idx )
		{
			final PointMatchGeneric< I > pm = correspondences.get( idx );
			final I pA = pm.getPoint1();
			final I pB = pm.getPoint2();

			// Get setId from list or default to -1 if null/out of bounds
			final int setId = (setIds != null && setIds.size() > idx) ? setIds.get(idx) : -1;

			final CorrespondingInterestPoints correspondingToA = new CorrespondingInterestPoints( pA.getId(), viewIdB, labelB, pB.getId(), setId );
			final CorrespondingInterestPoints correspondingToB = new CorrespondingInterestPoints( pB.getId(), viewIdA, labelA, pA.getId(), setId );

			corrListA.add( correspondingToA );
			corrListB.add( correspondingToB );
		}

		listA.setCorrespondingInterestPoints( corrListA );
		listB.setCorrespondingInterestPoints( corrListB );
	}

	// Backward-compatible overload without setIds parameter
	public static < I extends InterestPoint > void addCorrespondences(
			final List< PointMatchGeneric< I > > correspondences,
			final ViewId viewIdA,
			final ViewId viewIdB,
			final String labelA,
			final String labelB,
			final InterestPoints listA,
			final InterestPoints listB )
	{
		addCorrespondences( correspondences, null, viewIdA, viewIdB, labelA, labelB, listA, listB );
	}

	public static void assignLoggingDescriptions(
			final Pair< ?, ? > p,
			final PairwiseResult< ? > pwr )
	{
		if ( ViewId.class.isInstance( p.getA() ) && ViewId.class.isInstance( p.getB() ) )
		{
			final String description =
					"[TP=" + ((ViewId)p.getA()).getTimePointId() +
					" ViewId=" + ((ViewId)p.getA()).getViewSetupId() + " Label=" + pwr.getLabelA() +
					" >>> TP=" + ((ViewId)p.getB()).getTimePointId() +
					" ViewId=" + ((ViewId)p.getB()).getViewSetupId() + " Label=" + pwr.getLabelB() + "]";

			pwr.setDescription( description );
		}
		else if ( Group.class.isInstance( p.getA() ) && Group.class.isInstance( p.getB() ) )
		{
			pwr.setDescription( "[Group {" + p.getA() + "} Label= "+ pwr.getLabelA() + " >>> Group {" + p.getB() + "} Label=" + pwr.getLabelA() + "]" );
		}
		else
		{
			pwr.setDescription( "[" + p.getA() + " Label= " + pwr.getLabelA() + " >>> " + p.getB() + " Label= " + pwr.getLabelB() + "]" );
		}
	}

	public static < V, I extends InterestPoint > List< Pair< Pair< V, V >, PairwiseResult< I > > > computePairs(
			final List< Pair< V, V > > pairs,
			final Map< V, HashMap< String, Collection< I > > > interestpoints,
			final MatcherPairwise< I > matcher,
			final boolean matchAcrossLabels )
	{
		return computePairs( pairs, interestpoints, matcher, matchAcrossLabels, null );
	}

	public static < V, I extends InterestPoint > List< Pair< Pair< V, V >, PairwiseResult< I > > > computePairs(
			final List< Pair< V, V > > pairs,
			final Map< V, ? extends Map<String, ? extends Collection< I > > > interestpoints,
			final MatcherPairwise< I > matcher,
			final boolean matchAcrossLabels,
			final ExecutorService exec )
	{
		final int numThreads = (exec == null) ? net.preibisch.mvrecon.Threads.numThreads() : -1;

		final ExecutorService taskExecutor;

		if ( exec == null )
			taskExecutor = Executors.newFixedThreadPool( numThreads );
		else
			taskExecutor = exec;

		// each pair of Views that will be compared
		final ArrayList<MatchingTask<V>> tasksList = getTasksList( pairs, interestpoints, matchAcrossLabels );

		final ArrayList< Callable< Pair< Pair< V, V >, PairwiseResult< I > > > > tasks = getCallables( tasksList, interestpoints, matcher );

		final List< Pair< Pair< V, V >, PairwiseResult< I > > > r = new ArrayList<>();

		try
		{
			// invokeAll() returns when all tasks are complete
			taskExecutor.invokeAll( tasks ).forEach( future ->
			{
				try
				{
					r.add( future.get() );
				}
				catch (InterruptedException | ExecutionException e)
				{
					e.printStackTrace();
					throw new RuntimeException( e );
				}
			});
		}
		catch ( final Exception e )
		{
			throw new RuntimeException( e );
		}

		if ( exec == null )
			taskExecutor.shutdown();

		return r;
	}

	public static class MatchingTask< V > implements Serializable
	{
		private static final long serialVersionUID = -6809028286920973919L;

		final public V vA, vB;
		final public String labelA, labelB;

		public MatchingTask( final V vA, final V vB, final String labelA, final String labelB )
		{
			this.vA = vA;
			this.vB = vB;
			this.labelA = labelA;
			this.labelB = labelB;
		}

		public Pair<V,V> getPair() { return new ValuePair<>(vA, vB); }

		public ArrayList<V> viewsAsList()
		{
			final ArrayList<V> list = new ArrayList<>();
			list.add( vA );
			list.add( vB );
			return list;
		}
	}

	public static < V, I extends InterestPoint > ArrayList< MatchingTask< V > > getTasksList(
			final List< Pair< V, V > > pairs,
			final Map< V, ? extends Map<String, ? > > interestpoints,
			final boolean matchAcrossLabels )
	{
		final ArrayList< MatchingTask< V > > taskList = new ArrayList<>();

		// each pair of Views that will be compared
		for ( final Pair< V, V > pair : pairs )
		{
			// each view might have more than one labels associated with it
			final Map<String, ?> mapA = interestpoints.get( pair.getA() );
			final Map<String, ?> mapB = interestpoints.get( pair.getB() );

			final HashMap<String, String > compared = new HashMap<>();

			for ( final String labelA : mapA.keySet() )
				for ( final String labelB : mapB.keySet() )
				{
					if ( !matchAcrossLabels && !labelA.equals( labelB ) )
						continue;

					if ( compared.containsKey( labelA ) && compared.get( labelA ).equals( labelB ) )
						continue;

					// remember what we already compared
					compared.put( labelA, labelB );

					// for matchAcross also the inverse (A>B means we also did B>A)
					if ( matchAcrossLabels && !labelA.equals( labelB ) )
						compared.put( labelB, labelA );

					taskList.add( new MatchingTask<>(pair.getA(), pair.getB(), labelA, labelB ) );
				}
		}

		return taskList;
	}

	public static < V, I extends InterestPoint > ArrayList< Callable< Pair< Pair< V, V >, PairwiseResult< I > > > > getCallables(
			final List< MatchingTask< V > > tasks,
			final Map< V, ? extends Map<String, ? extends Collection< I > > > interestpoints,
			final MatcherPairwise< I > matcher )
	{
		final ArrayList< Callable< Pair< Pair< V, V >, PairwiseResult< I > > > > callables = new ArrayList<>(); // your tasks

		for ( final MatchingTask<V> task : tasks )
		{
			final Map<String, ? extends Collection<I>> mapA = interestpoints.get( task.vA );
			final Map<String, ? extends Collection<I>> mapB = interestpoints.get( task.vB );

			final Collection< I > listA, listB;

			if ( matcher.requiresInterestPointDuplication() )
			{
				listA = new ArrayList<>();
				listB = new ArrayList<>();

				for ( final I ip : mapA.get( task.labelA ) )
					listA.add( (I)ip.clone() );

				for ( final I ip : mapB.get( task.labelB ) )
					listB.add( (I)ip.clone() );
			}
			else
			{
				listA = mapA.get( task.labelA );
				listB = mapB.get( task.labelB );
			}

			callables.add( new Callable< Pair< Pair< V, V >, PairwiseResult< I > > >()
			{
				@Override
				public Pair< Pair< V, V >, PairwiseResult< I > > call() throws Exception
				{
					final PairwiseResult< I > pwr =
							matcher.match(
									listA,
									listB,
									task.getPair().getA(),
									task.getPair().getB(),
									task.labelA,
									task.labelB );

					pwr.setLabelA( task.labelA );
					pwr.setLabelB( task.labelB );
					assignLoggingDescriptions( task.getPair(), pwr );
					return new ValuePair<>( task.getPair(), pwr );
				}
			});
		}

		return callables;
	}
}
