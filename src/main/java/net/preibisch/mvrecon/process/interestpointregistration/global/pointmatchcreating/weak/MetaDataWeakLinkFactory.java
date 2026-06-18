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
import java.util.Map;

import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.OverlapDetection;

import mpicbg.models.Model;
import mpicbg.models.Tile;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewId;

public class MetaDataWeakLinkFactory implements WeakLinkFactory
{
	final OverlapDetection< ViewId > overlapDetection;
	final Map< ViewId, ViewRegistration > viewRegistrations;

	public MetaDataWeakLinkFactory(
			final Map< ViewId, ViewRegistration > viewRegistrations,
			final OverlapDetection< ViewId > overlapDetection )
	{
		this.viewRegistrations = viewRegistrations;
		this.overlapDetection = overlapDetection;
	}

	@Override
	public < M extends Model< M > > WeakLinkPointMatchCreator< M > create(
			final HashMap< ViewId, Tile< M > > models )
	{
		return new MetaDataWeakLinkCreator<>( models, overlapDetection, viewRegistrations );
	}

}
