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
package net.preibisch.mvrecon.process.fusion.transformed;

import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.process.fusion.lazy.LazyFusionTools;
import net.preibisch.mvrecon.process.fusion.transformed.weights.BlendingRealRandomAccessible;
import net.preibisch.mvrecon.process.fusion.transformed.weights.ContentBasedRealRandomAccessible;
import net.preibisch.mvrecon.process.fusion.transformed.weights.TransformedRasteredRandomAccessible;
import util.RealViews;

public class TransformWeight
{
	public static < T extends RealType< T > > RandomAccessibleInterval< FloatType > transformContentBased(
			final RandomAccessibleInterval< T > inputImg,
			final double[] sigma1,
			final double[] sigma2,
			final int[] blocksize,
			final float scale,
			final AffineTransform3D transform,
			final Interval boundingBox )
	{
		if ( inputImg.dimension( 2 ) == 1 && inputImg.min( 2 ) == 0 )
		{
			final double[] sigma1_2d = new double[]{ sigma1[ 0 ], sigma1[ 1 ] };
			final double[] sigma2_2d = new double[]{ sigma2[ 0 ], sigma2[ 1 ] };

			final int[] blocksize_2d;

			if ( blocksize.length != 2 )
				blocksize_2d = LazyFusionTools.defaultBlockSize2d;
			else
				blocksize_2d = blocksize;

			final ContentBasedRealRandomAccessible content =
					new ContentBasedRealRandomAccessible(
							Views.hyperSlice( inputImg, 2, 0 ),
							sigma1_2d,
							sigma2_2d,
							blocksize_2d,
							scale );

			return transformWeight( RealViews.addDimension( content ), transform, boundingBox );
		}
		else
		{
			final RealRandomAccessible< FloatType > entropyRRA =
					new ContentBasedRealRandomAccessible( inputImg, sigma1, sigma2, blocksize, scale );

			return transformWeight( entropyRRA, transform, boundingBox );
		}
	}

	public static RandomAccessibleInterval< FloatType > transformBlending(
			final Interval inputImgInterval,
			final float[] border,
			final float[] blending,
			final AffineTransform3D transform,
			final Interval boundingBox )
	{
		if ( inputImgInterval.dimension( 2 ) == 1 && inputImgInterval.min( 2 ) == 0 )
		{
			final float[] border2d = new float[]{ border[ 0 ], border[ 1 ] };
			final float[] blending2d = new float[]{ blending[ 0 ], blending[ 1 ] };
			final long[] min = new long[]{ inputImgInterval.min( 0 ), inputImgInterval.min( 1 ) };
			final long[] max = new long[]{ inputImgInterval.max( 0 ), inputImgInterval.max( 1 ) };

			final BlendingRealRandomAccessible blend = new BlendingRealRandomAccessible( new FinalInterval( min, max ), border2d, blending2d );

			return transformWeight( RealViews.addDimension( blend ), transform, boundingBox );
		}
		else
		{
			return transformWeight( new BlendingRealRandomAccessible( new FinalInterval( inputImgInterval ), border, blending ), transform, boundingBox );
		}
	}

	/**
	 * create a transformed, rastered image
	 *
	 * @param rra - a real random accessible
	 * @param transform - the affine transformation
	 * @param boundingBox - the interval in which to create a transformed, rastered image
	 * @return a zero-min RandomAccessibleInterval
	 */
	public static RandomAccessibleInterval< FloatType > transformWeight(
			final RealRandomAccessible< FloatType > rra,
			final AffineTransform3D transform,
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
				new TransformedRasteredRandomAccessible< FloatType >(
					rra,
					new FloatType(),
					transform,
					offset );

		final RandomAccessibleInterval< FloatType > virtualBlendingInterval = Views.interval( virtualBlending, new FinalInterval( size ) );

		return virtualBlendingInterval;
	}

}
