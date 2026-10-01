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

/**
 * Interest point detected in a scale space (DoGScaleSpace), carries the DoG response (which is the
 * intensity of {@link InterestPointValue}) and the sigma of the DoG level it was detected at
 *
 * @author Stephan Preibisch
 */
public class InterestPointSS extends InterestPointValue
{
	private static final long serialVersionUID = 2905481226397218331L;

	/**
	 * the lower sigma of the DoG level, in pixels of the image the point was detected in; once the
	 * point is mapped to full resolution (DownsampleTools.correctForDownsampling) in full-resolution
	 * pixels of the view (geometric mean over x and y)
	 */
	private double sigma;

	public InterestPointSS( final int id, final double[] l, final double response, final double sigma )
	{
		super( id, l, response );
		this.sigma = sigma;
	}

	public double getResponse() { return getIntensity(); }

	public double getSigma() { return sigma; }

	public void setSigma( final double sigma ) { this.sigma = sigma; }

	@Override
	public InterestPointSS clone() { return new InterestPointSS( this.id, this.l.clone(), getIntensity(), sigma ); }
}
