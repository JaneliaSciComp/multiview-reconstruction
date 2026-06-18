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

public class TranslationInvariantLocalCoordinateSystemPointDescriptor < P extends Point > extends AbstractPointDescriptor< P, TranslationInvariantLocalCoordinateSystemPointDescriptor<P> >
		implements RealLocalizable
{
	public double ax, ay, az, bx, by, bz, cx, cy, cz;
	
	public TranslationInvariantLocalCoordinateSystemPointDescriptor( final P basisPoint, final P point1, final P point2, final P point3 ) throws NoSuitablePointsException 
	{
		super( basisPoint, toList( point1, point2, point3 ), null, null );
		
		if ( numDimensions != 3 )
			throw new NoSuitablePointsException( "LocalCoordinateSystemPointDescriptor does not support dim = " + numDimensions + ", only dim = 3 is valid." );

		buildLocalCoordinateSystem( descriptorPoints );
	}

	private static < P extends Point > ArrayList< P > toList( final P point1, final P point2, final P point3 )
	{
		final ArrayList< P > list = new ArrayList<>();
		list.add( point1 );
		list.add( point2 );
		list.add( point3 );
		return list;
	}

	@Override
	public double descriptorDistance( final TranslationInvariantLocalCoordinateSystemPointDescriptor< P > pointDescriptor )
	{ 
		double difference = 0;

		difference += ( ax - pointDescriptor.ax ) * ( ax - pointDescriptor.ax );
		difference += ( ay - pointDescriptor.ay ) * ( ay - pointDescriptor.ay );
		difference += ( az - pointDescriptor.az ) * ( az - pointDescriptor.az );
		difference += ( bx - pointDescriptor.bx ) * ( bx - pointDescriptor.bx );
		difference += ( by - pointDescriptor.by ) * ( by - pointDescriptor.by );
		difference += ( bz - pointDescriptor.bz ) * ( bz - pointDescriptor.bz );
		difference += ( cx - pointDescriptor.cx ) * ( cx - pointDescriptor.cx );
		difference += ( cy - pointDescriptor.cy ) * ( cy - pointDescriptor.cy );
		difference += ( cz - pointDescriptor.cz ) * ( cz - pointDescriptor.cz );

		return difference / numDimensions; // (numDimensions)
	}
	
	/**
	 * Not necessary as the main matching method is overwritten
	 */
	@Override
	public Object fitMatches( final ArrayList<PointMatch> matches )  { return null; }
	
	public void buildLocalCoordinateSystem( final ArrayList< LinkedPoint< P > > neighbors )
	{
		this.ax = neighbors.get( 0 ).getL()[ 0 ];
		this.ay = neighbors.get( 0 ).getL()[ 1 ];
		this.az = neighbors.get( 0 ).getL()[ 2 ];

		this.bx = neighbors.get( 1 ).getL()[ 0 ];
		this.by = neighbors.get( 1 ).getL()[ 1 ];
		this.bz = neighbors.get( 1 ).getL()[ 2 ];

		this.cx = neighbors.get( 2 ).getL()[ 0 ];
		this.cy = neighbors.get( 2 ).getL()[ 1 ];
		this.cz = neighbors.get( 2 ).getL()[ 2 ];
	}

	@Override
	public int numDimensions() { return 9; }

	@Override
	public boolean resetWorldCoordinatesAfterMatching() { return true; }

	@Override
	public boolean useWorldCoordinatesForDescriptorBuildUp() { return false; }

	@Override
	public void localize( final float[] position )
	{
		position[ 0 ] = (float)ax;
		position[ 1 ] = (float)ay;
		position[ 2 ] = (float)az;
		position[ 3 ] = (float)bx;
		position[ 4 ] = (float)by;
		position[ 5 ] = (float)bz;
		position[ 6 ] = (float)cx;
		position[ 7 ] = (float)cy;
		position[ 8 ] = (float)cz;
	}

	@Override
	public void localize( final double[] position )
	{
		position[ 0 ] = ax;
		position[ 1 ] = ay;
		position[ 2 ] = az;
		position[ 3 ] = bx;
		position[ 4 ] = by;
		position[ 5 ] = bz;
	}

	@Override
	public double getDoublePosition( final int d )
	{
		switch ( d )
		{
		case 0:
			return ax;
		case 1:
			return ay;
		case 2:
			return az;
		case 3:
			return bx;
		case 4:
			return by;
		case 5:
			return bz;
		case 6:
			return cx;
		case 7:
			return cy;
		case 8:
			return cz;
		default:
			return 0;
		}
	}

	@Override
	public float getFloatPosition( final int d ) { return (float)getDoublePosition( d ); }
}
