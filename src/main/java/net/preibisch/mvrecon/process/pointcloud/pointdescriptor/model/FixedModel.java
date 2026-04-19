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
package net.preibisch.mvrecon.process.pointcloud.pointdescriptor.model;

import java.util.Collection;

import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;

/**
 * This model just applies some other {@link TranslationInvariantModel} without computing anything.
 * This could be considered a translation invariant translation model :)
 */
public class FixedModel extends TranslationInvariantModel<FixedModel> 
{
	static final protected int MIN_NUM_MATCHES = 1;

	protected double
		m00 = 1.0, m01 = 0.0, m02 = 0.0, m03 = 0.0,
		m10 = 0.0, m11 = 1.0, m12 = 0.0, m13 = 0.0,
		m20 = 0.0, m21 = 0.0, m22 = 1.0, m23 = 0.0;
	
	public FixedModel( final double m00, final double m01, final double m02, final double m03,
			           final double m10, final double m11, final double m12, final double m13,
			           final double m20, final double m21, final double m22, final double m23 )
	{
		this.m00 = m00;
		this.m01 = m01;
		this.m02 = m02;
		this.m03 = m03;
		this.m10 = m10;
		this.m11 = m11;
		this.m12 = m12;
		this.m13 = m13;
		this.m20 = m20;
		this.m21 = m21;
		this.m22 = m22;		
		this.m23 = m23;		
	}
	
	@Override
	public boolean canDoNumDimension( final int numDimensions ) { return numDimensions == 3; }

	@Override
	final public <P extends PointMatch> void fit( final Collection< P > matches )
		throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		if ( matches.size() < MIN_NUM_MATCHES )
			throw new NotEnoughDataPointsException( matches.size() + " matches given, we need at least " + MIN_NUM_MATCHES + " data point." );
	}
	
	@Override
	final public void set( final FixedModel m )
	{
		m00 = m.m00;
		m01 = m.m01;
		m02 = m.m02;
		m03 = m.m03;
		m10 = m.m10;
		m11 = m.m11;
		m12 = m.m12;
		m13 = m.m13;
		m20 = m.m20;
		m21 = m.m21;
		m22 = m.m22;		
		m23 = m.m23;		

		cost = m.cost;
	}

	@Override
	public FixedModel copy()
	{
		FixedModel m = new FixedModel( m00, m01, m02, m03, 
		                               m10, m11, m12, m13,
		                               m20, m21, m22, m23 );
	
		m.cost = cost;

		return m;
	}
	
	@Override
	final public int getMinNumMatches(){ return MIN_NUM_MATCHES; }
	
	@Override
	final public double[] apply( final double[] l )
	{
		final double[] transformed = l.clone();
		applyInPlace( transformed );
		return transformed;
	}
	
	@Override
	final public void applyInPlace( final double[] l )
	{
		assert l.length == 3 : "3d 3x3 transformations can be applied to 3d points only.";
		
		final double l0 = l[ 0 ];
		final double l1 = l[ 1 ];
		l[ 0 ] = l0 * m00 + l1 * m01 + l[ 2 ] * m02 + m03;
		l[ 1 ] = l0 * m10 + l1 * m11 + l[ 2 ] * m12 + m13;
		l[ 2 ] = l0 * m20 + l1 * m21 + l[ 2 ] * m22 + m23;
	}
	
	final public String toString()
	{
		return "3d-3x3: (" +
		m00 + ", " + m01 + ", " + m02 + ", " + m03 + ", " +
		m10 + ", " + m11 + ", " + m12 + ", " + m13 + ", " +
		m20 + ", " + m21 + ", " + m22 + ", " + m23 + ")";
	}
	
}
