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

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.realtransform.AffineTransform;
import net.imglib2.realtransform.AffineTransform3D;

public class TransformVirtual
{
	/**
	 * Collects all ViewRegistrations, updates them, and potentially adds anisotropy and downsampling transformations
	 * 
	 * @param viewIds - all ViewIds to process
	 * @param registrations - all ViewRegistrations
	 * @param anisotropyFactor - a factor applied to z only (e.g. 3, or Double.NaN)
	 * @param downsampling - a factor applied to xyz (e.g. 3, or Double.NaN)
	 *
	 * @return adjusted transformations
	 */
	public static HashMap< ViewId, AffineTransform3D > adjustAllTransforms(
			final Collection< ? extends ViewId > viewIds,
			final Map< ViewId, ? extends ViewRegistration > registrations,
			final double anisotropyFactor,
			final double downsampling )
	{
		final HashMap< ViewId, AffineTransform3D > updatedRegistrations = new HashMap<>();

		// get updated registration for views to fuse AND all other views that may influence the fusion
		for ( final ViewId viewId : viewIds )
		{
			final ViewRegistration vr = registrations.get( viewId );
			vr.updateModel();
			final AffineTransform3D model = vr.getModel().copy();

			// preserve anisotropy
			if ( !Double.isNaN( anisotropyFactor ) )
				TransformVirtual.scaleTransform( model, new double[] { 1.0, 1.0, 1.0/anisotropyFactor } );

			//System.out.println( model );
			// downsampling
			if ( !Double.isNaN( downsampling ) )
				TransformVirtual.scaleTransform( model, 1.0 / downsampling );
			//System.out.println( model );
			//System.out.println();

			updatedRegistrations.put( viewId, model );
		}

		return updatedRegistrations;
	}

	/**
	 * Updates existing transformations with anisotropy and downsampling transformations
	 * 
	 * @param registrations - all registrations
	 * @param anisotropyFactor - a factor applied to z only (e.g. 3, or Double.NaN)
	 * @param downsampling - a factor applied to xyz (e.g. 3, or Double.NaN)
	 *
	 * @return a copy of potentially updated transformations
	 */
	public static HashMap< ViewId, AffineTransform3D > adjustAllTransforms(
			final Map< ViewId, ? extends AffineTransform3D > registrations,
			final double anisotropyFactor,
			final double downsampling )
	{
		final HashMap< ViewId, AffineTransform3D > updatedRegistrations = new HashMap<>();

		// get updated registration for views to fuse AND all other views that may influence the fusion
		for ( final ViewId viewId : registrations.keySet() )
		{
			final AffineTransform3D model = registrations.get( viewId ).copy();

			// preserve anisotropy
			if ( !Double.isNaN( anisotropyFactor ) )
				TransformVirtual.scaleTransform( model, new double[] { 1.0, 1.0, 1.0/anisotropyFactor } );

			// downsampling
			if ( !Double.isNaN( downsampling ) )
				TransformVirtual.scaleTransform( model, 1.0 / downsampling );

			updatedRegistrations.put( viewId, model );
		}

		return updatedRegistrations;
	}

	/**
	 * Scale the affine transform (use with scaleBoundingBox so it is the right image, but just smaller)
	 * 
	 * @param t transform
	 * @param factor scaling factor
	 */
	public static void scaleTransform( final AffineTransform3D t, final double factor )
	{
		final AffineTransform3D at = new AffineTransform3D();
		at.scale( factor );
		t.preConcatenate( at );
	}
	
	public static void scaleTransform( final AffineTransform3D t, final double[] factors )
	{
		final AffineTransform at = new AffineTransform(t.numDimensions());
		for (int d = 0; d < at.numDimensions(); d++)
			at.set( factors[d], d, d );
			
		t.preConcatenate( at );
	}

	/**
	 * Scale the bounding box (use with scaleTransform so it is the right image, but just smaller)
	 * 
	 * @param boundingBox the bounding box
	 * @param factor scaling factor
	 * @return scaled bounding box
	 */
	public static Interval scaleBoundingBox( final Interval boundingBox, final double factor )
	{
		return scaleBoundingBox( boundingBox, factor, null );
	}

	public static Interval scaleBoundingBox( final Interval boundingBox, final double factor, final double[] offset )
	{
		final int n = boundingBox.numDimensions();
		final long[] min = new long[ n ];
		final long[] max = new long[ n ];

		for ( int d = 0; d < min.length; ++ d )
		{
			final double minValue = boundingBox.min( d ) * factor;

			min[ d ] = Math.round( minValue );
			max[ d ] = Math.round( boundingBox.max( d ) * factor );

			if ( offset != null )
				offset[ d ] = minValue - min[ d ];
		}

		return new FinalInterval( min, max );
	}
	
	
	public static Interval scaleBoundingBox( final Interval boundingBox, final double[] factors )
	{
		final int n = boundingBox.numDimensions();
		final long[] min = new long[ n ];
		final long[] max = new long[ n ];

		for ( int d = 0; d < min.length; ++ d )
		{
			min[ d ] = Math.round( boundingBox.min( d ) * factors[d] );
			max[ d ] = Math.round( boundingBox.max( d ) * factors[d] );
		}

		return new FinalInterval( min, max );
	}
	
}
