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
package net.preibisch.mvrecon.process.interestpointregistration.global;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import mpicbg.models.Affine3D;
import mpicbg.models.AffineModel3D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.RigidModel3D;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.global.convergence.ConvergenceStrategy;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.PointMatchCreator;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

/**
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 */
public class GlobalOpt
{
	public static < M extends Model< M > > HashMap< ViewId, M > computeModels(
			final M model,
			final boolean preAlign,
			final PointMatchCreator pmc,
			final ConvergenceStrategy cs,
			final Collection< ViewId > fixedViews,
			final Collection< Group< ViewId > > groupsIn )
	{
		return toModels( computeTiles( model, preAlign, pmc, cs, fixedViews, groupsIn ) );
	}

	/*
	 * Computes a global optimization based on the corresponding points
	 * 
	 */
	public static < M extends Model< M > > HashMap< ViewId, Tile< M > > computeTiles(
			final M model,
			final boolean preAlign,
			final PointMatchCreator pmc,
			final ConvergenceStrategy cs,
			final Collection< ViewId > fixedViews,
			final Collection< Group< ViewId > > groupsIn )
	{
		final Pair< HashMap< ViewId, Tile< M > >, ArrayList< Group< ViewId > > > globalOpt = initGlobalOpt( model, pmc, fixedViews, groupsIn );

		// assign ViewIds to the individual Tiles (either one tile per view or one tile per group)
		final HashMap< ViewId, Tile< M > > map = globalOpt.getA();

		// Groups are potentially modfied (merged, empty ones removed)
		final ArrayList< Group< ViewId > > groups = globalOpt.getB();

		// all views sorted (optional, but nice for user feedback)
		final ArrayList< ViewId > views = new ArrayList<>( map.keySet() );
		Collections.sort( views );

		// add and fix tiles as defined in the GlobalOptimizationType
		final TileConfiguration tc = addAndFixTiles( views, map, fixedViews, groups );
		
		if ( tc.getTiles().size() == 0 )
		{
			IOFunctions.println( "There are no connected tiles, cannot do an optimization. Quitting." );
			return null;
		}
		
		// now perform the global optimization
		try
		{
			if ( preAlign )
			{
				int unaligned = tc.preAlign().size();
				if ( unaligned > 0 )
					IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): pre-aligned all tiles but " + unaligned );
				else
					IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): prealigned all tiles" );
			}

			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): starting solve ..." );

			//tc.optimizeSilently(new ErrorStatistic( cs.getMaxPlateauWidth() + 1 ), cs.getMaxError(), cs.getMaxIterations(), cs.getMaxPlateauWidth() );
			//tc.optimize( cs.getMaxError(), cs.getMaxIterations(), cs.getMaxPlateauWidth() );
			//tc.optimizeSilentlyConcurrent(null, 0, 0, 0, 0);
			TileUtil.optimizeConcurrently(
					new ErrorStatistic( cs.getMaxPlateauWidth() + 1 ),
					cs.getMaxError(),
					cs.getMaxIterations(),
					cs.getMaxPlateauWidth(),
					1.0, // damp
					tc, tc.getTiles(), tc.getFixedTiles(),
					Threads.numThreads(),
					true ); //verbose

			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Global optimization of " + 
				tc.getTiles().size() +  " view-tiles (Model=" + model.getClass().getSimpleName()  + "):" );
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "):    Avg Error: " + tc.getError() + "px" );
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "):    Min Error: " + tc.getMinError() + "px" );
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "):    Max Error: " + tc.getMaxError() + "px" );
		}
		catch (Exception e)
		{
			IOFunctions.println( "Global optimization failed: " + e );
			e.printStackTrace();
		}
		
		// TODO: We assume it is Affine3D here
		TransformationTools.printAndSummarizeTransformations( views, map );

		return map;
	}

	public static < M extends Model< M > > Pair< HashMap< ViewId, Tile< M > >, ArrayList< Group< ViewId > > > initGlobalOpt(
			final M model,
			final PointMatchCreator pmc,
			final Collection< ViewId > fixedViews,
			final Collection< Group< ViewId > > groupsIn )
	{
		// merge overlapping groups if necessary
		final ArrayList< Group< ViewId > > groups = Group.mergeAllOverlappingGroups( groupsIn );

		// remove empty groups
		Group.removeEmptyGroups( groups );

		// assemble all views
		final HashSet< ViewId > tmpSet = pmc.getAllViews();

		// views that are part of a group but not of a pair and will thus be transformed as well
		for ( final Group< ViewId > group : groups )
				tmpSet.addAll( group.getViews() );

		final List< ViewId > views = new ArrayList< ViewId >();
		views.addAll( tmpSet );
		Collections.sort( views );

		// assign ViewIds to the individual Tiles (either one tile per view or one tile per group)
		final HashMap< ViewId, Tile< M > > map = assignViewsToTiles( model, views, groups );

		// assign weights per group
		pmc.assignWeights( map, groups, fixedViews );

		// assign the pointmatches to all the tiles
		pmc.assignPointMatches( map, groups, fixedViews );

		return new ValuePair<>( map, groups );
	}

	protected static < M extends Model< M > > TileConfiguration addAndFixTiles(
			final Collection< ViewId > views,
			final HashMap< ViewId, Tile< M > > map,
			final Collection< ViewId > fixedViews,
			final Collection< ? extends Group < ViewId > > groups )
	{
		// create a new tileconfiguration organizing the global optimization
		final TileConfiguration tc = new TileConfiguration();
		
		// assemble a list of all tiles and set them fixed if desired
		final HashSet< Tile< M > > tiles = new HashSet< Tile< M > >();
		
		for ( final ViewId viewId : views )
		{
			final Tile< M > tile = map.get( viewId );

			// if one of the views that maps to this tile is fixed, fix this tile if it is not already fixed
			if ( fixedViews.contains( viewId ) && !tc.getFixedTiles().contains( tile ) )
			{
				final Group< ViewId > fixedGroup = Group.isContained( viewId, groups );
				if ( fixedGroup != null )
					IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Fixing group-tile [" + fixedGroup + "]" );
				else
					IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Fixing view-tile [" + Group.pvid( viewId ) + "]" );
				tc.fixTile( tile );
			}

			// add it if it is not already there
			tiles.add( tile );
		}	
		
		// now add connected tiles to the tileconfiguration
		for ( final Tile< M > tile : tiles )
			if ( tile.getConnectedTiles().size() > 0 || tc.getFixedTiles().contains( tile ) )
				tc.addTile( tile );
		
		return tc;
	}
	
	protected static < M extends Model< M > > HashMap< ViewId, Tile< M > > assignViewsToTiles(
			final M model,
			final List< ViewId > views,
			final List< Group< ViewId > > groups )
	{
		final HashMap< ViewId, Tile< M > > map = new HashMap< ViewId, Tile< M > >();

		if ( groups != null && groups.size() > 0 )
		{
			//
			// we make only mpicbg-Tile per group since all views are transformed together
			//

			// remember those who are not part of a group
			final HashSet< ViewId > remainingViews = new HashSet< ViewId >();
			remainingViews.addAll( views );

			// for all groups find the viewIds that belong to this timepoint
			for ( final Group< ViewId > viewIds : groups )
			{
				// one tile per group
				final Tile< M > tileGroup = new Tile< M >( model.copy() );

				// all viewIds of one group map to the same tile (see main method for test, that works)
				for ( final ViewId viewId : viewIds )
				{
					map.put( viewId, tileGroup );

					// just to make sure that there are no views part of two groups or present twice
					if ( !remainingViews.contains( viewId ) )
						throw new RuntimeException(
								"ViewSetupID:" + viewId.getViewSetupId() + ", timepointId: " + viewId.getTimePointId() +
								" is part of two groups, this is a bug since those should have been merged." ); 

					remainingViews.remove( viewId );
				}
			}

			// add all remaining views
			for ( final ViewId viewId : remainingViews )
				map.put( viewId, new Tile< M >( model.copy() ) );
		}
		else
		{
			// there is one tile per view
			for ( final ViewId viewId : views )
				map.put( viewId, new Tile< M >( model.copy() ) );
		}
		
		return map;
	}

	public static < M extends Model< M > > HashMap< ViewId, M > toModels( final HashMap< ViewId, Tile< M > > map )
	{
		final HashMap< ViewId, M > finalRelativeModels = new HashMap<>();
		map.forEach( ( viewId, tile ) -> finalRelativeModels.put( viewId, tile.getModel() ) );

		return finalRelativeModels;
	}

	public static void main( String[] args )
	{
		// multiple keys can map to the same value
		final HashMap< ViewId, Tile< AffineModel3D > > map = new HashMap<ViewId, Tile<AffineModel3D>>();
		
		final AffineModel3D m1 = new AffineModel3D();
		final AffineModel3D m2 = new AffineModel3D();

		final Tile< AffineModel3D > tile1 = new Tile<AffineModel3D>( m1 );
		final Tile< AffineModel3D > tile2 = new Tile<AffineModel3D>( m2 );
		
		final ViewId v11 = new ViewId( 1, 1 );
		final ViewId v21 = new ViewId( 2, 1 );
		final ViewId v12 = new ViewId( 1, 2 );
		final ViewId v22 = new ViewId( 2, 2 );
		
		map.put( v11, tile1 );
		map.put( v21, tile2 );

		map.put( v12, tile1 );
		map.put( v22, tile2 );
		
		m1.set( 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 );
		m2.set( 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2 );

		System.out.println( map.get( v11 ).getModel() );
		System.out.println( map.get( v21 ).getModel() );
		
		System.out.println( map.get( v12 ).getModel() );
		System.out.println( map.get( v22 ).getModel() );		
	}
}
