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
package net.preibisch.mvrecon.process.interestpointregistration.global;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;

import mpicbg.models.Affine3D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.Model;
import mpicbg.models.RigidModel3D;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.global.convergence.IterativeConvergenceStrategy;
import net.preibisch.mvrecon.process.interestpointregistration.global.linkremoval.LinkRemovalStrategy;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.PointMatchCreator;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class GlobalOptIterative
{
	public static < M extends Model< M > > HashMap< ViewId, M > computeModels(
			final M model,
			final boolean preAlign,
			final PointMatchCreator pmc,
			final IterativeConvergenceStrategy ics,
			final LinkRemovalStrategy lms,
			final Collection< ViewId > fixedViews,
			final Collection< Group< ViewId > > groupsIn )
	{
		return computeModels( model, preAlign, pmc, ics, lms, null, fixedViews, groupsIn );
	}

	public static < M extends Model< M > > HashMap< ViewId, M > computeModels(
			final M model,
			final boolean preAlign,
			final PointMatchCreator pmc,
			final IterativeConvergenceStrategy ics,
			final LinkRemovalStrategy lms,
			final Collection< Pair< Group< ViewId >, Group< ViewId > > > removedInconsistentPairs,
			final Collection< ViewId > fixedViews,
			final Collection< Group< ViewId > > groupsIn )
	{
		return GlobalOpt.toModels( computeTiles( model, preAlign, pmc, ics, lms, null, fixedViews, groupsIn ) );
	}

	public static < M extends Model< M > > HashMap< ViewId, Tile< M > > computeTiles(
			final M model,
			final boolean preAlign,
			final PointMatchCreator pmc,
			final IterativeConvergenceStrategy ics,
			final LinkRemovalStrategy lms,
			final Collection< Pair< Group< ViewId >, Group< ViewId > > > removedInconsistentPairs,
			final Collection< ViewId > fixedViews,
			final Collection< Group< ViewId > > groupsIn )
	{

		final Pair< HashMap< ViewId, Tile< M > >, ArrayList< Group< ViewId > > > globalOpt = GlobalOpt.initGlobalOpt( model, pmc, fixedViews, groupsIn );

		// assign ViewIds to the individual Tiles (either one tile per view or one tile per group)
		final HashMap< ViewId, Tile< M > > map = globalOpt.getA();

		// Groups are potentially modfied (merged, empty ones removed)
		final ArrayList< Group< ViewId > > groups = globalOpt.getB();

		// all views sorted (optional, but nice for user feedback)
		final ArrayList< ViewId > views = new ArrayList<>( map.keySet() );
		Collections.sort( views );

		// add and fix tiles as defined in the GlobalOptimizationType
		final TileConfiguration tc = GlobalOpt.addAndFixTiles( views, map, fixedViews, groups );

		if ( tc.getTiles().size() == 0 )
		{
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): No connected tiles, cannot compute optimization." );
			return map;
		}

		// now perform the global optimization
		boolean finished = false;

		while (!finished)
		{
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

				tc.optimizeSilently(new ErrorStatistic( ics.getMaxPlateauWidth() + 1 ), ics.getMaxError(), ics.getMaxIterations(), ics.getMaxPlateauWidth() );
				/*TileUtil.optimizeConcurrently(
						new ErrorStatistic( ics.getMaxPlateauWidth() + 1 ),  ics.getMaxError(), ics.getMaxIterations(), ics.getMaxPlateauWidth(), 1.0f,
						tc, tc.getTiles(), tc.getFixedTiles(), Runtime.getRuntime().availableProcessors());*/

				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Global optimization of " + tc.getTiles().size());
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "):    Avg Error: " + tc.getError() + "px" );
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "):    Min Error: " + tc.getMinError() + "px" );
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "):    Max Error: " + tc.getMaxError() + "px" );

				// give some time for the output
				try { Thread.sleep( 50 ); } catch ( Exception e) {}
			}
			catch (Exception e)
			{
				IOFunctions.println( "Global optimization failed, please report this bug: " + e );
				e.printStackTrace();
				return null;
			}

			finished = true;

			// re-do if errors are too big
			if ( !ics.isConverged( tc ) )
			{
				finished = false;

				// if we cannot remove any link, then we are finished too
				final Pair< Group< ViewId >, Group< ViewId > > removed = lms.removeLink( tc, map );

				if ( removed == null )
					finished = true;
				else if ( removedInconsistentPairs != null )
					removedInconsistentPairs.add( removed );
			}
		}

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Transformation Models:" );

		// TODO: We assume it is Affine3D here
		for ( final ViewId viewId : views )
		{
			final Tile< M > tile = map.get( viewId );

			String output = Group.pvid( viewId ) + ": " + TransformationTools.printAffine3D( (Affine3D<?>)tile.getModel() );

			if ( tile.getModel() instanceof RigidModel3D )
				IOFunctions.println( output + ", " + TransformationTools.getRotationAxis( (RigidModel3D)tile.getModel() ) );
			else
				IOFunctions.println( output + ", " + TransformationTools.getScaling( (Affine3D<?>)tile.getModel() ) );
		}

		return map;
	}
}
