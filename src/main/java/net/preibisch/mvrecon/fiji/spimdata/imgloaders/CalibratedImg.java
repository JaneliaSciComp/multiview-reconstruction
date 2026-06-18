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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders;

import net.imglib2.img.Img;

public class CalibratedImg< T > 
{
	final Img< T > image;
	final double calX, calY, calZ;
	
	public CalibratedImg( final Img< T > image )
	{
		this.image = image;
		this.calX = this.calY = this.calZ = -1;
	}
	
	public CalibratedImg( final Img< T > image, final double calX, final double calY, final double calZ )
	{
		this.image = image;
		this.calX = calX;
		this.calY = calY;
		this.calZ = calZ;
	}
	
	public Img< T > getImg(){ return image; }
	public double getCalX() { return calX; }
	public double getCalY() { return calY; }
	public double getCalZ() { return calZ; }
}
