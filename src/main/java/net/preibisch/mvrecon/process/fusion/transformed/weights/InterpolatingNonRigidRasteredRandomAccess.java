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
package net.preibisch.mvrecon.process.fusion.transformed.weights;

import mpicbg.models.AffineModel3D;
import net.imglib2.AbstractLocalizableInt;
import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.ModelGrid;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.NumericAffineModel3D;

public class InterpolatingNonRigidRasteredRandomAccess< T > extends AbstractLocalizableInt implements RandomAccess< T >
{
	final RealRandomAccessible< T > realRandomAccessible;
	final RealRandomAccess< T > realRandomAccess;
	final int[] offset;
	final T zero;

	// to interpolate transformations
	final ModelGrid grid;
	final RealRandomAccess< NumericAffineModel3D > interpolatedModel;
	final AffineModel3D invertedModelOpener;

	final double[] s;
	final protected int offsetX, offsetY, offsetZ;

	public InterpolatingNonRigidRasteredRandomAccess(
			final RealRandomAccessible< T > realRandomAccessible,
			final T zero,
			final ModelGrid grid,
			final AffineModel3D invertedModelOpener,
			final int[] offset )
	{
		super( realRandomAccessible.numDimensions() );

		this.zero = zero;
		this.realRandomAccessible = realRandomAccessible;
		this.grid = grid;
		this.invertedModelOpener = invertedModelOpener;
		this.interpolatedModel = grid.realRandomAccess();
		this.offset = new int[ offset.length ];

		for ( int d = 0; d < n; ++d )
			this.offset[ d ] = offset[ d ];

		this.realRandomAccess = realRandomAccessible.realRandomAccess();

		this.offsetX = (int)offset[ 0 ];
		this.offsetY = (int)offset[ 1 ];
		this.offsetZ = (int)offset[ 2 ];

		this.s = new double[ n ];
	}

	@Override
	public T get()
	{
		s[ 0 ] = position[ 0 ] + offsetX;
		s[ 1 ] = position[ 1 ] + offsetY;
		s[ 2 ] = position[ 2 ] + offsetZ;

		// get the right interpolated affine
		interpolatedModel.setPosition( s );

		// transform the coordinates
		if ( invertedModelOpener == null )
		{
			interpolatedModel.get().getModel().applyInPlace( s );
		}
		else
		{
			final AffineModel3D model = interpolatedModel.get().getModel();
			model.preConcatenate( invertedModelOpener );
			model.applyInPlace( s );
		}

		realRandomAccess.setPosition( s );

		return realRandomAccess.get();
	}

	@Override
	public void fwd( final int d ) { ++this.position[ d ]; }

	@Override
	public void bck( final int d ) { --this.position[ d ]; }

	@Override
	public void move( final int distance, final int d ) { this.position[ d ] += distance; }

	@Override
	public void move( final long distance, final int d ) { this.position[ d ] += (int)distance; }

	@Override
	public void move( final Localizable localizable )
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] += localizable.getIntPosition( d );
	}

	@Override
	public void move( final int[] distance )
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] += distance[ d ];
	}

	@Override
	public void move( final long[] distance )
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] += (int)distance[ d ];
	}

	@Override
	public void setPosition( final Localizable localizable )
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] = localizable.getIntPosition( d );
	}

	@Override
	public void setPosition( final int[] position )
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] = position[ d ];
	}

	@Override
	public void setPosition( final long[] position )
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] = (int)position[ d ];
	}

	@Override
	public void setPosition( final int position, final int d ) { this.position[ d ] = position; }

	@Override
	public void setPosition( final long position, final int d ) { this.position[ d ] = (int)position; }

	@Override
	public InterpolatingNonRigidRasteredRandomAccess< T > copy() { return new InterpolatingNonRigidRasteredRandomAccess< T >( realRandomAccessible, zero, grid, invertedModelOpener, offset ); }

	@Override
	public InterpolatingNonRigidRasteredRandomAccess<T> copyRandomAccess() { return copy(); }
}
