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
package net.preibisch.mvrecon.fiji.spimdata.intensityadjust;

import java.util.HashMap;

import mpicbg.models.AffineModel1D;
import mpicbg.spim.data.sequence.ViewId;

public class IntensityAdjustments
{
	private HashMap< ViewId, AffineModel1D > intensityAdjustment;

	public IntensityAdjustments()
	{
		this.intensityAdjustment = new HashMap<>();
	}

	public IntensityAdjustments( final HashMap< ViewId, AffineModel1D > models )
	{
		this();
		this.intensityAdjustment.putAll( models );
	}

	public HashMap< ViewId, AffineModel1D > getIntensityAdjustments() { return intensityAdjustment; }
	public void addIntensityAdjustments( final ViewId viewId, final AffineModel1D model ) { this.intensityAdjustment.put( viewId, model ); }
}
