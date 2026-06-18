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
package net.preibisch.mvrecon.process.fusion.intensityadjust;

import mpicbg.models.AffineModel1D;
import net.imglib2.converter.Converter;
import net.imglib2.type.numeric.real.FloatType;

public class IntensityAdjuster implements Converter< FloatType, FloatType >
{
	final double m00, m01;

	public IntensityAdjuster( final AffineModel1D intensityTransform )
	{
		final double[] m = new double[ 2 ];

		intensityTransform.getMatrix( m );
		this.m00 = m[ 0 ];
		this.m01 = m[ 1 ];
	}

	@Override
	public void convert( final FloatType input, final FloatType output )
	{
		// cannot use this because the double[] array l cannot be shared
		//this.intensityTransform.applyInPlace( this.l );

		output.set( (float)apply( input.get() ) );
	}

	final public double apply( final double l )
	{
		return l * m00 + m01;
	}
}
