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
package net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions;

import java.util.HashMap;

import mpicbg.spim.data.sequence.ViewId;

public class PointSpreadFunctions
{
	private HashMap< ViewId, PointSpreadFunction > psfs;

	public PointSpreadFunctions()
	{
		this.psfs = new HashMap<>();
	}

	public PointSpreadFunctions( final HashMap< ViewId, PointSpreadFunction > psfs )
	{
		this();
		this.psfs.putAll( psfs );
	}

	public HashMap< ViewId, PointSpreadFunction > getPointSpreadFunctions() { return psfs; }
	public void addPSF( final ViewId viewId, final PointSpreadFunction img ) { this.psfs.put( viewId, img ); }
}
