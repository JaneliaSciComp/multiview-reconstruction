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

import mpicbg.models.AffineModel3D;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.process.fusion.transformed.AbstractTransformedImgRandomAccessible;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.ModelGrid;

public class InterpolatingNonRigidRandomAccessible< T extends RealType< T > > extends AbstractTransformedImgRandomAccessible< T >
{
	final ModelGrid grid;
	final AffineModel3D invertedModelOpener;

	public InterpolatingNonRigidRandomAccessible(
		final RandomAccessibleInterval< T > img, // from ImgLoader
		final ModelGrid grid,
		final AffineModel3D invertedModelOpener,
		final boolean hasMinValue,
		final float minValue,
		final FloatType outsideValue,
		final Interval boundingBox )
	{
		super( img, hasMinValue, minValue, outsideValue, boundingBox );

		this.grid = grid;
		this.invertedModelOpener = invertedModelOpener;
		//this.grid = new ModelGrid( controlPointDistance, boundingBox, ips );

		/*
		final RealRandomAccess< NumericAffineModel3D > model = this.grid.realRandomAccess();
		model.setPosition( new long[] { boundingBox.min( 0 ), boundingBox.min( 1 ), boundingBox.min( 2 ) } );
		System.out.println( model.get().getModel() );

		model.setPosition( new long[] { boundingBox.min( 0 ) + 1, boundingBox.min( 1 ), boundingBox.min( 2 ) } );
		System.out.println( model.get().getModel() );

		SimpleMultiThreading.threadHaltUnClean(); */
	}

	public InterpolatingNonRigidRandomAccessible(
			final RandomAccessibleInterval< T > img, // from ImgLoader
			final ModelGrid grid,
			final AffineModel3D modelOpener,
			final Interval boundingBox )
	{
		this( img, grid, modelOpener, false, 0.0f, new FloatType( 0 ), boundingBox );
	}

	@Override
	public RandomAccess< FloatType > randomAccess()
	{
		return new InterpolatingNonRigidRandomAccess< T >( img, grid, invertedModelOpener, interpolatorFactory, hasMinValue, minValue, outsideValue, boundingBoxOffset );
	}
}
