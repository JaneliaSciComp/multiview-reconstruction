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
package net.preibisch.mvrecon.process.fusion.blk.tps;

import static net.imglib2.algorithm.blocks.dfield.DisplacementFieldTransform.displacementFieldAffine;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Random;

import org.junit.jupiter.api.Test;

import net.imglib2.FinalDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.algorithm.blocks.dfield.DisplacementField;
import net.imglib2.algorithm.blocks.dfield.DisplacementFieldBlockSupplier;
import net.imglib2.algorithm.blocks.dfield.DisplacementFields.TransformedDisplacementField;
import net.imglib2.algorithm.blocks.transform.Transform.Interpolation;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

/**
 * {@link TpsPositionField} against the imglib2-algorithm operators it replaces, on a random displacement field.
 */
public class TpsPositionFieldTest
{
	private static final double[] spacing = { 8, 8, 8 };

	private static final double[] offset = { -30.5, -12.25, -17 }; // the field covers the target intervals below

	@Test
	public void axisAligned()
	{
		final AffineTransform3D fromField = new AffineTransform3D();
		fromField.set( spacing[ 0 ], 0, 0, offset[ 0 ], 0, spacing[ 1 ], 0, offset[ 1 ], 0, 0, spacing[ 2 ], offset[ 2 ] );
		assertSameAsDisplacementFieldOperators( fromField );
	}

	@Test
	public void sheared()
	{
		// target X depends on grid Y, target Y on grid Z: X lines are still X lines, so still separable
		final AffineTransform3D fromField = new AffineTransform3D();
		fromField.set( spacing[ 0 ], 0.5, 0, offset[ 0 ], 0, spacing[ 1 ], 0.25, offset[ 1 ], 0, 0, spacing[ 2 ], offset[ 2 ] );
		assertSameAsDisplacementFieldOperators( fromField );
	}

	@Test
	public void rotatedIsNotSeparable()
	{
		final AffineTransform3D fromField = new AffineTransform3D();
		fromField.set( spacing[ 0 ], 0, 0, offset[ 0 ], 0, spacing[ 1 ], 0, offset[ 1 ], 0, 0, spacing[ 2 ], offset[ 2 ] );
		fromField.rotate( 2, 0.1 );
		assertNull( TpsPositionField.of( new TransformedDisplacementField<>( fromField, randomDisplacements( new Random( 42 ) ) ) ) );
	}

	/** normalized displacements (in grid units) of up to +-2 grid points, layout [3, x, y, z] */
	private static DisplacementField< DoubleType > randomDisplacements( final Random rnd )
	{
		final ArrayImg< DoubleType, DoubleArray > disp = ArrayImgs.doubles( 3, 30, 28, 22 );
		disp.forEach( t -> t.set( 4 * rnd.nextDouble() - 2 ) );
		return new DisplacementField<>( BlockSupplier.of( disp ), spacing, offset );
	}

	private static void assertSameAsDisplacementFieldOperators( final AffineTransform3D fromField )
	{
		final Random rnd = new Random( 42 );
		final TransformedDisplacementField< DoubleType > tdf = new TransformedDisplacementField<>( fromField, randomDisplacements( rnd ) );

		final ArrayImg< FloatType, FloatArray > img = ArrayImgs.floats( 160, 150, 110 );
		img.forEach( t -> t.set( rnd.nextFloat() * 1000 ) );
		final BlockSupplier< FloatType > input = BlockSupplier.of( Views.extendBorder( img ) );
		final DistanceFunction3D< DoubleType, double[] > distance = DistanceFunction3D.of( new DoubleType(), new FinalDimensions( 160, 150, 110 ) );

		final TpsPositionField field = TpsPositionField.of( tdf );
		assertNotNull( field );
		final BlockSupplier< FloatType > weights = field.weights( distance );
		final BlockSupplier< FloatType > weightsRef = DisplacementFieldBlockSupplier.create( tdf.transformFromField(), tdf.displacementField(), distance );

		for ( final Interpolation interpolation : Interpolation.values() )
		{
			final BlockSupplier< FloatType > image = field.image( input, interpolation );
			final BlockSupplier< FloatType > imageRef = input.andThen( displacementFieldAffine( tdf.transformFromField(), tdf.displacementField(), interpolation ) );

			for ( final FinalInterval interval : new FinalInterval[] {
					FinalInterval.createMinSize( 0, 0, 0, 64, 64, 64 ),
					FinalInterval.createMinSize( 37, 5, 21, 33, 17, 9 ),
					FinalInterval.createMinSize( 100, 90, 60, 50, 40, 30 ) } )
			{
				final int n = ( int ) interval.dimension( 0 ) * ( int ) interval.dimension( 1 ) * ( int ) interval.dimension( 2 );
				final float[] actual = new float[ n ], expected = new float[ n ];

				// image, then weights for the same interval: the weights reuse the image's positions
				image.copy( interval, actual );
				imageRef.copy( interval, expected );
				assertArrayEquals( expected, actual, interpolation == Interpolation.NLINEAR ? 1e-3f : 0, interpolation + " image" );

				weights.copy( interval, actual );
				weightsRef.copy( interval, expected );
				assertArrayEquals( expected, actual, 1e-3f, interpolation + " weights" );
			}
		}
	}
}
