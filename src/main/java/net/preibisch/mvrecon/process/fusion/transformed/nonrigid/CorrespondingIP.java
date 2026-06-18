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
package net.preibisch.mvrecon.process.fusion.transformed.nonrigid;

import java.util.Arrays;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.realtransform.AffineTransform3D;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;

public class CorrespondingIP extends SimpleReferenceIP
{
	final double[] corrL;
	final double[] corrW;
	final InterestPoint ip, corrIp;
	final ViewId viewId, corrViewId;

	public CorrespondingIP( final InterestPoint ip, final ViewId viewId, final InterestPoint corrIp, final ViewId corrViewId )
	{
		super( ip.getL().clone(), ip.getL().clone() );
		this.corrL = corrIp.getL().clone();
		this.corrW = corrIp.getL().clone();
		this.ip = ip;
		this.corrIp = corrIp;
		this.viewId = viewId;
		this.corrViewId = corrViewId;
	}

	public double[] getCorrL() { return corrL; }
	public double[] getCorrW() { return corrW; }
	public InterestPoint getIP() { return ip; }
	public InterestPoint getCorrIP() { return corrIp; }
	public ViewId getViewId() { return viewId; }
	public ViewId getCorrViewId() { return corrViewId; }

	public void transform( final AffineTransform3D t, final AffineTransform3D corrT )
	{
		t.apply( l, w );
		corrT.apply( corrL, corrW );
	}

	public CorrespondingIP copy()
	{
		return new CorrespondingIP( ip, viewId, corrIp, corrViewId );
	}

	@Override
	public int hashCode()
	{
		final int prime = 31;
		int result = 1;
		result = prime * result
				+ ( ( corrIp == null ) ? 0 : corrIp.hashCode() );
		result = prime * result + Arrays.hashCode( corrL );
		result = prime * result
				+ ( ( corrViewId == null ) ? 0 : corrViewId.hashCode() );
		result = prime * result + ( ( ip == null ) ? 0 : ip.hashCode() );
		result = prime * result + Arrays.hashCode( l );
		result = prime * result
				+ ( ( viewId == null ) ? 0 : viewId.hashCode() );
		return result;
	}

	@Override
	public boolean equals( Object obj )
	{
		if ( this == obj )
			return true;
		if ( obj == null )
			return false;
		if ( getClass() != obj.getClass() )
			return false;
		CorrespondingIP other = (CorrespondingIP) obj;
		if ( corrIp == null )
		{
			if ( other.corrIp != null )
				return false;
		} else if ( !corrIp.equals( other.corrIp ) )
			return false;
		if ( !Arrays.equals( corrL, other.corrL ) )
			return false;
		if ( corrViewId == null )
		{
			if ( other.corrViewId != null )
				return false;
		} else if ( !corrViewId.equals( other.corrViewId ) )
			return false;
		if ( ip == null )
		{
			if ( other.ip != null )
				return false;
		} else if ( !ip.equals( other.ip ) )
			return false;
		if ( !Arrays.equals( l, other.l ) )
			return false;
		if ( viewId == null )
		{
			if ( other.viewId != null )
				return false;
		} else if ( !viewId.equals( other.viewId ) )
			return false;
		return true;
	}
}
