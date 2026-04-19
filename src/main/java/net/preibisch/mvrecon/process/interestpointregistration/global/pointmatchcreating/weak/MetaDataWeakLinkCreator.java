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
package net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.weak;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import mpicbg.models.Affine3D;
import mpicbg.models.Model;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RealInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.OverlapDetection;

/**
 * Uses the previous state as an approximate knowledge for the metadata of the acquisition. New Groups (connected through strong links) will be transformed together,
 * and are mapped back in average to their positions before the global opt run that aligned them relative to each other.
 */
public class MetaDataWeakLinkCreator< N extends Model< N > > extends WeakLinkPointMatchCreator< N >
{
	final Map< ViewId, ViewRegistration > viewRegistrations;
	final OverlapDetection< ViewId > overlapDetection;

	/**
	 * @param models1 - the models from the first round of global optimization
	 * @param overlapDetection - an interface implementation to identify which views overlap
	 * @param viewRegistrations - the Registrations that are the basis for the whole global optimization (MetaData)
	 */
	public MetaDataWeakLinkCreator(
			final HashMap< ViewId, Tile< N > > models1,
			final OverlapDetection< ViewId > overlapDetection,
			final Map< ViewId, ViewRegistration > viewRegistrations )
	{
		super( models1 );

		this.viewRegistrations = viewRegistrations;
		this.overlapDetection = overlapDetection;
	}

	@Override
	public < M extends Model< M > > void assignPointMatches(
			final HashMap< ViewId, Tile< M > > models2,
			final ArrayList< Group< ViewId > > groups,
			final Collection< ViewId > fixedViews )
	{
		final ArrayList< ViewId > views = new ArrayList<>( this.allViews );
		Collections.sort( views );

		final HashMap< ViewId, Group< ViewId > > groupMap = new HashMap<>();
A:		for ( final ViewId v : views )
			for ( final Group< ViewId > group : groups )
				if ( group.contains( v ) )
				{
					groupMap.put( v, group );
					continue A;
				}

		// identify current transforms (before first round of global opt) as metadata
		for ( final ViewId viewId : allViews )
			viewRegistrations.get( viewId ).updateModel();

		for ( int a = 0; a < this.allViews.size() - 1; ++a )
			for ( int b = a + 1; b < this.allViews.size(); ++b )
			{
				final ViewId viewA = views.get( a );
				final ViewId viewB = views.get( b );

				// they will be part of the same group, no reason to create pointmatches
				if ( groupMap.get( viewA ) == groupMap.get( viewB ) )
					continue;

				final ArrayList< PointMatch > pm = new ArrayList< PointMatch >();

				if ( overlapDetection.overlaps( viewA, viewB ) )
				{
					// we always transform, no matter if the result from the first run was useful or not as it doesn't matter

					// we use the vertices of the intersection cube between the two views, they are
					// in the coordinate system defined by the state of registrations BEFORE the first run of the global opt
					final RealInterval overlap = overlapDetection.getOverlapInterval( viewA, viewB );

					if ( overlap == null )
						continue;

					final double[][] pa = TransformationTools.cubeFor( overlap );
					final double[][] pb = TransformationTools.cubeFor( overlap );

					// and transform them with the respective models from the first round of global optimization,
					// which will make the deviate from one another >> the second run should try to bring this back
					// together as good as possible, but will most likely not be able to succeed since parts of them
					// are now grouped together (the strong links)

					// the result from the first run of the global opt
					final AffineTransform3D tA = TransformationTools.getAffineTransform( (Affine3D< ? >)models1.get( viewA ).getModel() );
					final AffineTransform3D tB = TransformationTools.getAffineTransform( (Affine3D< ? >)models1.get( viewB ).getModel() );

					// the transformed cubes are then our corresponding features stored as pointmatches
					for ( int i = 0; i < pa.length; ++i )
					{
						tA.apply( pa[ i ], pa[ i ] );
						tB.apply( pb[ i ], pb[ i ] );

						pm.add( new PointMatch( new Point( pa[ i ] ), new Point( pb[ i ] ) ) );
					}

					// connect Tiles
					final Tile< M > tileA = models2.get( viewA );
					final Tile< M > tileB = models2.get( viewB );

					tileA.addMatches( pm );
					tileB.addMatches( PointMatch.flip( pm ) );
					tileA.addConnectedTile( tileB );
					tileB.addConnectedTile( tileA );
				}
			}
	}

	@Override
	public < M extends Model< M > > void assignWeights(
			HashMap< ViewId, Tile< M > > tileMap,
			ArrayList< Group< ViewId > > groups,
			Collection< ViewId > fixedViews )
	{
		return;
	}
}
