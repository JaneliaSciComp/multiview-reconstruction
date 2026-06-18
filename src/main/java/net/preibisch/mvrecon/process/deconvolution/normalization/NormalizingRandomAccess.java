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
package net.preibisch.mvrecon.process.deconvolution.normalization;

import java.util.ArrayList;

import net.imglib2.AbstractLocalizableInt;
import net.imglib2.Cursor;
import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;

public class NormalizingRandomAccess< T extends RealType< T > > extends AbstractLocalizableInt implements RandomAccess< T >
{
	final int index, numImgs;
	final ArrayList< RandomAccessibleInterval< FloatType > > originalWeights;
	final ArrayList< RandomAccess< FloatType > > owRA;
	final RandomAccess< FloatType > myRA;
	final T type;
	final double osemspeedup;
	final boolean additionalSmoothBlending;
	final float maxDiffRange;
	final float scalingRange;

	public NormalizingRandomAccess(
			final int index,
			final ArrayList< RandomAccessibleInterval< FloatType > > originalWeights,
			final double osemspeedup,
			final boolean additionalSmoothBlending,
			final float maxDiffRange,
			final float scalingRange,
			final T type )
	{
		super( originalWeights.get( 0 ).numDimensions() );

		this.index = index;
		this.numImgs = originalWeights.size();
		this.originalWeights = originalWeights;
		this.type = type.createVariable();
		this.osemspeedup = osemspeedup;

		this.additionalSmoothBlending = additionalSmoothBlending;
		this.maxDiffRange = maxDiffRange;
		this.scalingRange = scalingRange;

		this.owRA = new ArrayList< RandomAccess< FloatType > >();
		for ( int i = 0; i < numImgs; ++i )
			this.owRA.add( originalWeights.get( i ).randomAccess() );

		this.myRA = this.owRA.get( index );
	}

	@Override
	public T get()
	{
		double sumW = 0;

		float myValue = 0;

		for ( int i = 0; i < numImgs; ++i )
		{
			final RandomAccess< FloatType > ra = this.owRA.get( i );
			ra.setPosition( position );

			final double value = Math.min( 1.0, ra.get().get() );

			if ( index == i )
				myValue = (float)value;

			// if the weight is bigger than 1 it doesn't matter since it is just the fusion
			// of more than one input images from the same group
			sumW += value;
		}

		final double v;

		if ( additionalSmoothBlending )
			v = smoothWeights( myValue, sumW, maxDiffRange, scalingRange );
		else if ( sumW > 1 )
			v =  hardWeights( myValue, sumW );
		else
			v = myValue;

		type.setReal( Math.min( 1, v * osemspeedup ) ); // individual contribution never higher than 1

		return type;
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
	public NormalizingRandomAccess< T > copy() { return new NormalizingRandomAccess< T >( index, originalWeights, osemspeedup, additionalSmoothBlending, maxDiffRange, scalingRange, type ); }

	@Override
	public NormalizingRandomAccess<T> copyRandomAccess() { return copy(); }

	final protected static void applySmooth( final ArrayList< Cursor< FloatType > > cursors, double sumW, final float maxDiffRange, final float scalingRange )
	{
		for ( final Cursor< FloatType > c : cursors )
			c.get().set( smoothWeights( c.get().get(), sumW, maxDiffRange, scalingRange ) );
	}

	final public static float smoothWeights( final float w, final double sumW, final float maxDiffRange, final float scalingRange )
	{
		if ( sumW <= 0 )
			return 0;

		final float idealValue = (float)( w / sumW );

		final float diff = w - idealValue;

		// map diff: 0 ... maxDiffRange >> 1 ...  0, rest negative
		final float y = Math.max( 0, ( maxDiffRange - Math.abs( diff ) ) * ( 1.0f / maxDiffRange ) );

		// scale with the value of w
		final float scale = y * w * scalingRange;

		// final function is a scaling down
		return ( Math.min( w, idealValue ) - (float)scale );
	}

	final protected static void applyHard( final ArrayList< Cursor< FloatType > > cursors, final double sumW )
	{
		if ( sumW > 1 )
		{
			for ( final Cursor< FloatType > c : cursors )
				c.get().set( hardWeights( c.get().get(), sumW )  );
		}
	}

	final public static float hardWeights( final float w, final double sumW )
	{
		return (float)( w / sumW );
	}
}
