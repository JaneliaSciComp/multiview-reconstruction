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

import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;

/**
 * 2d-rigid transformation models to be applied to points in 2d-space.
 * This model includes the closed form weighted least squares solution as
 * described by \citet{SchaeferAl06} and implemented by Johannes Schindelin.  
 * 
 * BibTeX:
 * <pre>
 * &#64;article{SchaeferAl06,
 *   author    = {Scott Schaefer and Travis McPhail and Joe Warren},
 *   title     = {Image deformation using moving least squares},
 *   journal   = {ACM Transactions on Graphics},
 *   volume    = {25},
 *   number    = {3},
 *   year      = {2006},
 *   pages     = {533--540},
 *   publisher = {ACM},
 *   address   = {New York, NY, USA},
 *   url       = {http://faculty.cs.tamu.edu/schaefer/research/mls.pdf},
 * }
 * </pre>
 * 
 * @author Stephan Saalfeld (saalfeld@mpi-cbg.de)
 * @version 0.1b
 * 
 */
public class TranslationInvariantRigidModel2D extends TranslationInvariantModel<TranslationInvariantRigidModel2D> 
{
	static final protected int MIN_NUM_MATCHES = 2;
	
	protected double cos = 1.0, sin = 0.0, tx = 0.0, ty = 0.0;
	protected double itx = 0.0, ity = 0.0;
	
	@Override
	public boolean canDoNumDimension( final int numDimensions ) { return numDimensions == 2; }
	
	@Override
	final public int getMinNumMatches(){ return MIN_NUM_MATCHES; }
		
	@Override
	final public double[] apply( final double[] l )
	{
		assert l.length == 2 : "2d rigid transformations can be applied to 2d points only.";
		
		final double[] transformed = l.clone();
		applyInPlace( transformed );
		return transformed;
	}
	
	@Override
	final public void applyInPlace( final double[] l )
	{
		assert l.length == 2 : "2d rigid transformations can be applied to 2d points only.";
		
		final double l0 = l[ 0 ];
		l[ 0 ] = cos * l0 - sin * l[ 1 ] + tx;
		l[ 1 ] = sin * l0 + cos * l[ 1 ] + ty;
	}

	@Override
	public TranslationInvariantRigidModel2D copy()
	{
		final TranslationInvariantRigidModel2D m = new TranslationInvariantRigidModel2D();
		m.cos = cos;
		m.sin = sin;
		m.tx = tx;
		m.ty = ty;
		m.itx = itx;
		m.ity = ity;
		m.cost = cost;
		return m;
	}
	
	@Override
	final public void set( final TranslationInvariantRigidModel2D m )
	{
		cos = m.cos;
		sin = m.sin;
		tx = m.tx;
		ty = m.ty;
		itx = m.itx;
		ity = m.ity;
		cost = m.cost;
	}
	
	/**
	 * Closed form weighted least squares solution as described by
	 * \citet{SchaeferAl06} and implemented by Johannes Schindelin.
	 */
	@Override
	final public < P extends PointMatch >void fit( final Collection< P > matches )
		throws NotEnoughDataPointsException
	{
		if ( matches.size() < MIN_NUM_MATCHES ) throw new NotEnoughDataPointsException( matches.size() + " data points are not enough to estimate a 2d rigid model, at least " + MIN_NUM_MATCHES + " data points required." );
				
		cos = 0;
		sin = 0;
		for ( final P m : matches )
		{
			final double[] p = m.getP1().getL(); 
			final double[] q = m.getP2().getW();
			final double w = m.getWeight();

			final double x1 = p[ 0 ];
			final double y1 = p[ 1 ]; // x2
			final double x2 = q[ 0 ]; // y1
			final double y2 = q[ 1 ]; // y2
			sin += w * ( x1 * y2 - y1 * x2 ); //   x1 * y2 - x2 * y1 // assuming p1 is x1,x2 and p2 is y1,y2
			cos += w * ( x1 * x2 + y1 * y2 ); //   x1 * y1 + x2 * y2
		}
		final double norm = Math.sqrt( cos * cos + sin * sin );
		cos /= norm;
		sin /= norm;		
	}
}
