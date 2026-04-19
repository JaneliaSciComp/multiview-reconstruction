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

import java.util.HashMap;
import java.util.HashSet;

import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.PointMatchCreator;

import mpicbg.models.Model;
import mpicbg.models.Tile;
import mpicbg.spim.data.sequence.ViewId;

public abstract class WeakLinkPointMatchCreator< M extends Model< M > > implements PointMatchCreator
{
	final HashMap< ViewId, Tile< M > > models1;
	final HashSet< ViewId > allViews;

	/**
	 * @param models1 - the models from the first round of global optimization
	 */
	public WeakLinkPointMatchCreator( final HashMap< ViewId, Tile< M > > models1 )
	{
		this.models1 = models1;
		this.allViews = new HashSet<>();

		this.allViews.addAll( models1.keySet() );
	}

	@Override
	public HashSet< ViewId > getAllViews() { return allViews; }
}
