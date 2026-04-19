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

import net.imglib2.RealInterval;
import net.imglib2.realtransform.AffineGet;

public class Link<T>
{
	private final T first;
	private final T second;
	private final AffineGet transform;
	private final double linkQuality;
	private final RealInterval boundingBox;
	
	public Link(final T fst, final T snd, final RealInterval boundingBox, final AffineGet transform, final double linkQuality)
	{
		this.first = fst;
		this.second = snd;
		this.transform = transform;
		this.linkQuality = linkQuality;
		this.boundingBox = boundingBox;
	}
	
	public RealInterval getBoundingBox() { return boundingBox; }
	public T getFirst() { return first; }
	public T getSecond() { return second; }
	public AffineGet getTransform() { return transform; }
	public double getLinkQuality() { return linkQuality; }

	@Override
	public String toString()
	{ return "("+ first.toString() + ", " + second.toString() + ")"; }
}
