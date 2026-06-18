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
package net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;

import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

import mpicbg.models.Model;
import mpicbg.models.Tile;
import mpicbg.spim.data.sequence.ViewId;

public interface PointMatchCreator
{
	/**
	 * @return - all views that this class knows and that are part of the global opt, called first
	 */
	public HashSet< ViewId > getAllViews();

	/**
	 * By default all weights are 1, if wanted one can adjust them here, otherwise simply return.
	 * The idea is to modify the weights in the PointMatchGeneric objects, that are later on just added
	 * called second
	 * 
	 * @param tileMap - the map from viewId to Tile
	 * @param groups - which groups exist
	 * @param fixedViews - which views are fixed (one might need it?)
	 * @param <M> model type
	 */
	public < M extends Model< M > > void assignWeights(
			final HashMap< ViewId, Tile< M > > tileMap,
			final ArrayList< Group< ViewId > > groups,
			final Collection< ViewId > fixedViews );

	/**
	 * assign pointmatches for all views that this object knows and that are present in tileMap.keySet(),
	 * which comes from getAllViews() plus what is in the group definition of the globalopt called last
	 *
	 * @param tileMap - the map from viewId to Tile
	 * @param groups - which groups exist (one might need it?)
	 * @param fixedViews - which views are fixed (one might need it?)
	 * @param <M> model type
	 */
	public < M extends Model< M > > void assignPointMatches(
			final HashMap< ViewId, Tile< M > > tileMap,
			final ArrayList< Group< ViewId > > groups,
			final Collection< ViewId > fixedViews );
}
