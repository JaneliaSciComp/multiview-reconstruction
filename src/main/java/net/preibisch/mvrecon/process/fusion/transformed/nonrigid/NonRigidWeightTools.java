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

import java.util.Collection;

import mpicbg.models.AffineModel3D;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.ModelGrid;
import net.preibisch.mvrecon.process.fusion.transformed.weights.InterpolatingNonRigidRasteredRandomAccessible;
import net.preibisch.mvrecon.process.fusion.transformed.weights.NonRigidRasteredRandomAccessible;

public class NonRigidWeightTools
{
	public static RandomAccessibleInterval< FloatType > transformWeightNonRigidInterpolated(
			final RealRandomAccessible< FloatType > rra,
			final ModelGrid grid,
			final AffineModel3D invertedModelOpener,
			final Interval boundingBox )
	{
		final long[] offset = new long[ rra.numDimensions() ];
		final long[] size = new long[ rra.numDimensions() ];

		for ( int d = 0; d < offset.length; ++d )
		{
			offset[ d ] = boundingBox.min( d );
			size[ d ] = boundingBox.dimension( d );
		}

		// the virtual weight construct
		final RandomAccessible< FloatType > virtualBlending =
				new InterpolatingNonRigidRasteredRandomAccessible< FloatType >(
					rra,
					new FloatType(),
					grid,
					invertedModelOpener,
					offset );

		final RandomAccessibleInterval< FloatType > virtualBlendingInterval = Views.interval( virtualBlending, new FinalInterval( size ) );

		return virtualBlendingInterval;
	}

	public static RandomAccessibleInterval< FloatType > transformWeightNonRigid(
			final RealRandomAccessible< FloatType > rra,
			final Collection< ? extends NonrigidIP > ips,
			final double alpha,
			final AffineModel3D invertedModelOpener,
			final Interval boundingBox )
	{
		final long[] offset = new long[ rra.numDimensions() ];
		final long[] size = new long[ rra.numDimensions() ];

		for ( int d = 0; d < offset.length; ++d )
		{
			offset[ d ] = boundingBox.min( d );
			size[ d ] = boundingBox.dimension( d );
		}

		// the virtual weight construct
		final RandomAccessible< FloatType > virtualBlending =
				new NonRigidRasteredRandomAccessible< FloatType >(
					rra,
					new FloatType(),
					ips,
					alpha,
					invertedModelOpener,
					offset );

		final RandomAccessibleInterval< FloatType > virtualBlendingInterval = Views.interval( virtualBlending, new FinalInterval( size ) );

		return virtualBlendingInterval;
	}

}
