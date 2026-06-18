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

import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.outofbounds.OutOfBoundsFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;

public class TransformView
{
	/**
	 * Creates a virtual construct that transforms and zero-mins.
	 * 
	 * Note: we do not use a general outofbounds strategy so that when using linear interpolation there are no
	 *       half-correct pixels at the interface between image/background and so that we can clearly know which
	 *       parts are from image data and where there is no data
	 * 
	 * @param input - the input image
	 * @param transform - the affine transformation
	 * @param boundingBox - the bounding box (after transformation)
	 * @param minValue - the minimal value inside the image
	 * @param outsideValue - the value that is returned if it does not intersect with the input image
	 * @param interpolation - 0=nearest neighbor, 1=linear interpolation
	 * @param <T> - type
	 * @return transformed image
	 */
	public static < T extends RealType< T > > RandomAccessibleInterval< FloatType > transformView(
			final RandomAccessibleInterval< T > input,
			final AffineTransform3D transform,
			final Interval boundingBox,
			final float minValue,
			final float outsideValue,
			final int interpolation )
	{
		final TransformedInputRandomAccessible< T > virtual = new TransformedInputRandomAccessible< T >( input, transform, true, minValue, new FloatType( outsideValue ), boundingBox );

		if ( interpolation == 0 )
			virtual.setNearestNeighborInterpolation();
		else
			virtual.setLinearInterpolation();

		return Views.interval( virtual, new FinalInterval( boundingBox.dimensionsAsLongArray() ) );
	}

	/**
	 * Creates a virtual construct that transforms and zero-mins.
	 * 
	 * Note: we do not use a general outofbounds strategy so that when using linear interpolation there are no
	 *       half-correct pixels at the interface between image/background and so that we can clearly know which
	 *       parts are from image data and where there is no data
	 * 
	 * @param input - the input image
	 * @param transform - the affine transformation
	 * @param boundingBox - the bounding box (after transformation)
	 * @param outsideValue - the value that is returned if it does not intersect with the input image
	 * @param interpolation - 0=nearest neighbor, 1=linear interpolation
	 * @param <T> type
	 * @return transformed image
	 */
	public static < T extends RealType< T > > RandomAccessibleInterval< FloatType > transformView(
			final RandomAccessibleInterval< T > input,
			final AffineTransform3D transform,
			final Interval boundingBox,
			final float outsideValue,
			final int interpolation )
	{
		final TransformedInputRandomAccessible< T > virtual = new TransformedInputRandomAccessible< T >( input, transform, false, 0.0f, new FloatType( outsideValue ), boundingBox );

		if ( interpolation == 0 )
			virtual.setNearestNeighborInterpolation();
		else
			virtual.setLinearInterpolation();

		return Views.interval( virtual, new FinalInterval( boundingBox.dimensionsAsLongArray() ) );
	}

	public static < T extends RealType< T > > RandomAccessibleInterval< FloatType > transformView(
			final ImgLoader imgloader,
			final ViewId viewId,
			final AffineTransform3D transform,
			final Interval boundingBox,
			final float outsideValue,
			final int interpolation )
	{
		final RandomAccessibleInterval inputImg = DownsampleTools.openDownsampled( imgloader, viewId, transform );//imgloader.getSetupImgLoader( viewId.getViewSetupId() ).getImage( viewId.getTimePointId() );

		return transformView( inputImg, transform, boundingBox, outsideValue, interpolation );
	}

	public static < T extends RealType< T > > RandomAccessibleInterval< FloatType > transformView(
			final ImgLoader imgloader,
			final ViewId viewId,
			final AffineTransform3D transform,
			final FinalInterval boundingBox,
			final OutOfBoundsFactory< T, RandomAccessible< T > > outOfBoundsFactory,
			final InterpolatorFactory< FloatType, RandomAccessible< FloatType > > interpolatorFactory )
	{
		final RandomAccessibleInterval inputImg = DownsampleTools.openDownsampled( imgloader, viewId, transform );//imgloader.getSetupImgLoader( viewId.getViewSetupId() ).getImage( viewId.getTimePointId() );

		return transformView( inputImg, transform, boundingBox, outOfBoundsFactory, interpolatorFactory );
	}

	public static < T extends RealType< T > > RandomAccessibleInterval< FloatType > transformView(
			final SpimData data,
			final ViewId viewId,
			final Interval boundingBox,
			final float outsideValue,
			final int interpolation )
	{
		final ImgLoader il = data.getSequenceDescription().getImgLoader();
		final ViewRegistration vr = data.getViewRegistrations().getViewRegistration( viewId );
		vr.updateModel();
		
		return transformView( il, viewId, vr.getModel(), boundingBox, outsideValue, interpolation );
	}

	/**
	 * Creates a virtual construct that transforms and zero-mins using a user-defined outofbounds &amp; interpolation.
	 * 
	 * @param input - the input image
	 * @param transform - the affine transformation
	 * @param boundingBox - the bounding box (after transformation)
	 * @param outOfBoundsFactory - outofboundsfactory
	 * @param interpolatorFactory - any interpolatorfactory
	 * @param <T> type
	 * @return transformed image
	 */
	public static < T extends RealType< T > > RandomAccessibleInterval< FloatType > transformView(
			final RandomAccessibleInterval< T > input,
			final AffineTransform3D transform,
			final Interval boundingBox,
			final OutOfBoundsFactory< T, RandomAccessible< T > > outOfBoundsFactory,
			final InterpolatorFactory< FloatType, RandomAccessible< FloatType > > interpolatorFactory )
	{
		final long[] offset = new long[ input.numDimensions() ];
		final long[] size = new long[ input.numDimensions() ];

		for ( int d = 0; d < offset.length; ++d )
		{
			offset[ d ] = boundingBox.min( d );
			size[ d ] = boundingBox.dimension( d );
		}

		final TransformedInputGeneralRandomAccessible< T > virtual = new TransformedInputGeneralRandomAccessible< T >( input, transform, outOfBoundsFactory, interpolatorFactory, offset );

		return Views.interval( virtual, new FinalInterval( size ) );
	}

	public static < T extends RealType< T > > RandomAccessibleInterval< FloatType > transformView(
			final SpimData data,
			final ViewId viewId,
			final FinalInterval boundingBox,
			final OutOfBoundsFactory< T, RandomAccessible< T > > outOfBoundsFactory,
			final InterpolatorFactory< FloatType, RandomAccessible< FloatType > > interpolatorFactory )
	{
		final ImgLoader il = data.getSequenceDescription().getImgLoader();
		final ViewRegistration vr = data.getViewRegistrations().getViewRegistration( viewId );
		vr.updateModel();
		
		return transformView( il, viewId, vr.getModel(), boundingBox, outOfBoundsFactory, interpolatorFactory );
	}
}
