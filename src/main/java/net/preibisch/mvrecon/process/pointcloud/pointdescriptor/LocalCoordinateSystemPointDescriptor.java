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
package net.preibisch.mvrecon.process.pointcloud.pointdescriptor;

import java.util.ArrayList;

import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import net.imglib2.RealLocalizable;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.exception.NoSuitablePointsException;
import net.preibisch.mvrecon.vecmath.Matrix3d;
import net.preibisch.mvrecon.vecmath.Vector3d;

public class LocalCoordinateSystemPointDescriptor < P extends Point > extends AbstractPointDescriptor< P, LocalCoordinateSystemPointDescriptor<P> >
		implements RealLocalizable
{
	final protected boolean normalize;
	public float ax = 1, bx, by, cx, cy, cz;
	
	public LocalCoordinateSystemPointDescriptor( final P basisPoint, final ArrayList<P> orderedNearestNeighboringPoints,  
												 final boolean normalize ) throws NoSuitablePointsException 
	{
		super( basisPoint, orderedNearestNeighboringPoints, null, null );
		
		if ( numDimensions != 3 )
			throw new NoSuitablePointsException( "LocalCoordinateSystemPointDescriptor does not support dim = " + numDimensions + ", only dim = 3 is valid." );

		/* check that number of points is at least model.getMinNumMatches() */
		if ( numNeighbors() != 3 )
			throw new NoSuitablePointsException( "Only 3 nearest neighbors is supported by a LocalCoordinateSystemPointDescriptor : num neighbors = " + numNeighbors() );
	
		this.normalize = normalize;
		
		buildLocalCoordinateSystem( descriptorPoints, normalize );
	}

	@Override
	public double descriptorDistance( final LocalCoordinateSystemPointDescriptor< P > pointDescriptor ) 
	{ 
		double difference = 0;
		
		if ( !normalize )
			difference += ( ax - pointDescriptor.ax ) * ( ax - pointDescriptor.ax );  	
		
		difference += ( bx - pointDescriptor.bx ) * ( bx - pointDescriptor.bx );  
		difference += ( by - pointDescriptor.by ) * ( by - pointDescriptor.by );  
		difference += ( cx - pointDescriptor.cx ) * ( cx - pointDescriptor.cx );  
		difference += ( cy - pointDescriptor.cy ) * ( cy - pointDescriptor.cy );  
		difference += ( cz - pointDescriptor.cz ) * ( cz - pointDescriptor.cz );
		
		return difference;// / 3.0;	
	}
	
	/**
	 * Not necessary as the main matching method is overwritten
	 */
	@Override
	public Object fitMatches( final ArrayList<PointMatch> matches )  { return null; }
	
	public void buildLocalCoordinateSystem( final ArrayList< LinkedPoint< P > > neighbors, final boolean normalize ) throws NoSuitablePointsException 
	{
		// most distant point		
		final Vector3d b = new Vector3d( neighbors.get( 0 ).getL() );
		final Vector3d c = new Vector3d( neighbors.get( 1 ).getL() );
		final Vector3d d = new Vector3d( neighbors.get( 2 ).getL() );
		
		final Vector3d x = new Vector3d( d );
		x.normalize();			

//		IOFunctions.println( "Input" );
//		IOFunctions.println( b );
//		IOFunctions.println( c );
//		IOFunctions.println( d );

		if ( normalize )
		{			
			final double lengthD = 1.0 / d.length();

			b.scale(lengthD);
			c.scale(lengthD);
			d.scale(lengthD);
			
//			IOFunctions.println( "Scaled" );
//			IOFunctions.println( b + "(" + b.length() + ")");
//			IOFunctions.println( c + "(" + c.length() + ")");
//			IOFunctions.println( d + "(" + d.length() + ")");
		}
		else
		{
			ax = (float)d.length();
		}
		
		// get normal vector of ab and ad ( which will be the z-axis)
		final Vector3d n = new Vector3d();
		n.cross(b, x);		
		n.normalize();
		
//		IOFunctions.println( "Normal vector (z-axis)" );
//		IOFunctions.println( n );

		if ( !Double.isFinite( n.x ) )
			throw new NoSuitablePointsException( "Cannot compute cross product. You most likely have two or more identical points in the point cloud: " + b );

		// check if the normal vector points into the direction of point c
		if ( n.dot( c ) < 0 )
		{
			n.negate();
//			IOFunctions.println( "Negated normal vector (z-axis)" );
//			IOFunctions.println( n );
		}

		// get the inverse of the matrix that maps the vectors into the local coordinate system
		// where the x-axis is vector(ad), the z-axis is n and the y-axis is cross-product(x,z)
		final Vector3d y = new Vector3d();
		y.cross( n, x );
		y.normalize();
		
//		IOFunctions.println( "X - axis" );		
//		IOFunctions.println( x );
//
//		IOFunctions.println( "Y - axis" );
//		IOFunctions.println( y );
		
		final Matrix3d m = new Matrix3d();
		m.m00 = x.x; m.m01 = y.x; m.m02 = n.x;  
		m.m10 = x.y; m.m11 = y.y; m.m12 = n.y; 
		m.m20 = x.z; m.m21 = y.z; m.m22 = n.z;
		
		try
		{
			m.invert();
		}
		catch ( Exception e )
		{
			bx = by = cx = cy = cz = 0;
			return;
		}
		
		
		// get the positions in the local coordinate system
		final Vector3d bl = new Vector3d( b );
		final Vector3d cl = new Vector3d( c );

		m.transform( bl );
		m.transform( cl );
		
//		IOFunctions.println( "In local coordinate system" );
//		IOFunctions.println( bl );
//		IOFunctions.println( cl );
		
		bx = (float)bl.x;
		by = (float)bl.y;
		cx = (float)cl.x;
		cy = (float)cl.y;
		cz = (float)cl.z;
		
//		System.out.println( "NEW" );
//		System.out.println( ax );
//		System.out.println( bx );
//		System.out.println( by );
//		System.out.println( cx );
//		System.out.println( cy );
//		System.out.println( cz );
//		
//		System.exit( 0 );
	}

	@Override
	public int numDimensions()
	{
		if ( normalize )
			return 5;
		else
			return 6;
	}

	@Override
	public boolean resetWorldCoordinatesAfterMatching() { return true; }

	@Override
	public boolean useWorldCoordinatesForDescriptorBuildUp() { return false; }

	@Override
	public void localize( final float[] position )
	{
		if ( normalize )
		{
			position[ 0 ] = bx;
			position[ 1 ] = by;
			position[ 2 ] = cx;
			position[ 3 ] = cy;
			position[ 4 ] = cz;
		}
		else
		{
			position[ 0 ] = ax;
			position[ 1 ] = bx;
			position[ 2 ] = by;
			position[ 3 ] = cx;
			position[ 4 ] = cy;
			position[ 5 ] = cz;
		}
	}

	@Override
	public void localize( final double[] position )
	{
		if ( normalize )
		{
			position[ 0 ] = bx;
			position[ 1 ] = by;
			position[ 2 ] = cx;
			position[ 3 ] = cy;
			position[ 4 ] = cz;
		}
		else
		{
			position[ 0 ] = ax;
			position[ 1 ] = bx;
			position[ 2 ] = by;
			position[ 3 ] = cx;
			position[ 4 ] = cy;
			position[ 5 ] = cz;
		}
	}

	@Override
	public float getFloatPosition( final int d )
	{
		if ( normalize )
		{
			if ( d == 0 )
				return bx;
			else if ( d == 1 )
				return by;
			else if ( d == 2 )
				return cx;
			else if ( d == 3 )
				return cy;
			else
				return cz;
		}
		else
		{
			if ( d == 0 )
				return ax;
			else if ( d == 1 )
				return bx;
			else if ( d == 2 )
				return by;
			else if ( d == 3 )
				return cx;
			else if ( d == 4 )
				return cy;
			else
				return cz;
		}
	}

	@Override
	public double getDoublePosition( final int d ) { return getFloatPosition( d ); }
}
