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

public class Calibration
{
	final int w, h, d;
	final double calX, calY, calZ;
	
	public Calibration( final int w, final int h, final int d, final double calX, final double calY, final double calZ )
	{
		this.w = w;
		this.h = h;
		this.d = d;
		this.calX = calX;
		this.calY = calY;
		this.calZ = calZ;
	}
	
	public double getCalX() { return calX; }
	public double getCalY() { return calY; }
	public double getCalZ() { return calZ; }
	public int getWidth() { return w; }
	public int getHeight() { return h; }
	public int getDepth() { return d; }
}
