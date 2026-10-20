/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2026 Multiview Reconstruction developers.
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
package net.preibisch.mvrecon.fiji.spimdata.interestpoints;

import java.io.Serializable;

import mpicbg.models.Point;
import net.imglib2.RealLocalizable;

/**
 * Single interest point, extends mpicbg Point by an id and the attributes of its detection: the DoG response, the
 * sigma of the DoG it was detected with and the image intensity at its position. The legacy DoG (DoGImgLib2) and
 * the scale space (DoGScaleSpace) create the same points, the parameter string of the label tells them apart. An
 * attribute is NaN when it is unknown (points of labels stored before the attributes existed, synthetic points).
 * The attributes are floats (in memory and in the N5): millions of points of split views must stay small.
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 */
public class InterestPoint extends Point implements RealLocalizable, Serializable
{
	private static final long serialVersionUID = 5615112297702152070L;

	protected final int id;

	/** the DoG response at the (refined) position, signed (the legacy DoG without subpixel localization stores |response|) */
	protected final float response;

	/**
	 * the lower sigma of the DoG the point was detected with, in x pixels of the image it was detected in; once the
	 * point is mapped to full resolution (DownsampleTools.correctForDownsampling) in full-resolution pixels of the
	 * view (geometric mean over x and y). For the scale space the sigma in z is this value divided by the anisotropy
	 * the scale space was computed with (z voxel / x voxel), in z pixels.
	 */
	protected float sigma;

	/** the intensity of the image the point was detected in at its (refined) position, linearly interpolated, in the units of the image (not normalized) */
	protected float intensity;

	public InterestPoint( final int id, final double[] l )
	{
		this( id, l, Float.NaN, Float.NaN, Float.NaN );
	}

	public InterestPoint( final int id, final double[] l, final float response )
	{
		this( id, l, response, Float.NaN, Float.NaN );
	}

	public InterestPoint( final int id, final double[] l, final float response, final float sigma, final float intensity )
	{
		super( l );
		this.id = id;
		this.response = response;
		this.sigma = sigma;
		this.intensity = intensity;
	}

	public int getId() { return id; }

	public float getResponse() { return response; }

	public float getSigma() { return sigma; }
	public void setSigma( final float sigma ) { this.sigma = sigma; }

	public float getIntensity() { return intensity; }
	public void setIntensity( final float intensity ) { this.intensity = intensity; }

	@Override
	public int numDimensions() { return l.length; }

	@Override
	public void localize( final float[] position )
	{
		for ( int d = 0; d < l.length; ++d )
			position[ d ] = (float)w[ d ];
	}

	@Override
	public void localize( final double[] position )
	{
		for ( int d = 0; d < l.length; ++d )
			position[ d ] = w[ d ];
	}

	@Override
	public float getFloatPosition( final int d ) { return (float)w[ d ]; }

	@Override
	public double getDoublePosition( final int d ) { return w[ d ]; }

	/**
	 * @return a new point with this id and position and no attributes (e.g. a center of mass)
	 */
	public InterestPoint newInstance( final int id, final double[] l ) { return new InterestPoint( id, l ); }

	/**
	 * @return a new point with another id and position that keeps the attributes of this one (a transformed, shifted or renumbered copy)
	 */
	public InterestPoint derive( final int id, final double[] l ) { return new InterestPoint( id, l, response, sigma, intensity ); }

	public InterestPoint duplicate() { return clone(); }

	@Override
	public InterestPoint clone() { return new InterestPoint( this.id, this.l.clone(), response, sigma, intensity ); }

	@Override
	public int hashCode()
	{
		return Integer.hashCode( id );
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
		final InterestPoint other = (InterestPoint) obj;

		if ( other.id != id )
			return false;

		for ( int d = 0; d < numDimensions(); ++d )
			if ( other.getDoublePosition( d ) != getDoublePosition( d ) )
				return false;

		return true;
	}

}
