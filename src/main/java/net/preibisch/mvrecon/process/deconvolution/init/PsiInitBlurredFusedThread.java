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
package net.preibisch.mvrecon.process.deconvolution.init;

import java.util.ArrayList;
import java.util.concurrent.Callable;

import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.RealSum;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.fiji.ImgLib2Temp.Triple;
import net.preibisch.mvrecon.fiji.ImgLib2Temp.ValueTriple;
import net.preibisch.mvrecon.process.deconvolution.MultiViewDeconvolution;
import net.preibisch.mvrecon.process.fusion.ImagePortion;

/**
 * Fuse one portion of a paralell fusion, supports no weights
 *
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 */
public class PsiInitBlurredFusedThread implements Callable< Triple< RealSum, Long, float[] > >
{
	final ImagePortion portion;
	final RandomAccessibleInterval< FloatType > psi;
	final ArrayList< RandomAccessibleInterval< FloatType > > imgs;

	final IterableInterval< FloatType > psiIterable;
	final ArrayList< IterableInterval< FloatType > > iterableImgs;

	final RealSum realSum;

	boolean compatibleIteration;

	public PsiInitBlurredFusedThread(
			final ImagePortion portion,
			final RandomAccessibleInterval< FloatType > psi,
			final ArrayList< RandomAccessibleInterval< FloatType > > imgs )
	{
		this.portion = portion;
		this.psi = psi;
		this.imgs = imgs;

		this.psiIterable = Views.iterable( psi );
		this.iterableImgs = new ArrayList< IterableInterval< FloatType > >();

		this.realSum = new RealSum();

		compatibleIteration = true;

		for ( final RandomAccessibleInterval< FloatType > img : imgs )
		{
			final IterableInterval< FloatType > imgIterable = Views.iterable( img );

			if ( !psiIterable.iterationOrder().equals( imgIterable.iterationOrder() ) )
				compatibleIteration = false;

			this.iterableImgs.add( imgIterable );
		}
	}

	@Override
	public Triple< RealSum, Long, float[] > call()
	{
		final Cursor< FloatType > psiCursor = psiIterable.localizingCursor();
		psiCursor.jumpFwd( portion.getStartPosition() );

		final int m = iterableImgs.size();
		long count = 0;

		final float[] max = new float[ imgs.size() ];
		for ( int i = 0; i < max.length; ++i )
			max[ i ] = 0;

		if ( compatibleIteration )
		{
			final ArrayList< Cursor< FloatType > > cursorImgs = new ArrayList< Cursor< FloatType > >();

			for ( final IterableInterval< FloatType > img : iterableImgs )
			{
				final Cursor< FloatType > imgCursor = img.cursor();
				imgCursor.jumpFwd( portion.getStartPosition() );
				cursorImgs.add( imgCursor );
			}

			for ( int j = 0; j < portion.getLoopSize(); ++j )
				if ( compatibleLoop( psiCursor, cursorImgs, max, realSum, m ) > 0 )
					++count;
		}
		else
		{
			final ArrayList< RandomAccess< FloatType > > randomAccessImgs = new ArrayList< RandomAccess< FloatType > >();

			for ( final RandomAccessibleInterval< FloatType > img : imgs )
				randomAccessImgs.add( img.randomAccess() );

			for ( int j = 0; j < portion.getLoopSize(); ++j )
				if ( incompatibleLoop( psiCursor, randomAccessImgs, max, realSum, m ) > 0 )
					++count;
		}

		return new ValueTriple< RealSum, Long, float[] >( realSum, new Long( count ), max );
	}

	private static final int compatibleLoop(
			final Cursor< FloatType > psiCursor,
			final ArrayList< Cursor< FloatType > > cursorImgs,
			final float[] max,
			final RealSum realSum,
			final int m )
	{
		double sum = 0;
		int count = 0;

		for ( int j = 0; j < m; ++j )
		{
			final float i = cursorImgs.get( j ).next().get();

			if ( i > 0 )
			{
				max[ j ] = Math.max( max[ j ], i );
				sum += i;
				++count;
			}
		}

		if ( count > 0 )
		{
			final double i = sum / count;
			realSum.add( i );
			psiCursor.next().set( (float)i ); // has fused data from n views
		}
		else
		{
			psiCursor.next().set( MultiViewDeconvolution.minValue ); // minimal deconvolution value
		}

		return count;
	}

	private static final int incompatibleLoop(
			final Cursor< FloatType > psiCursor,
			final ArrayList< RandomAccess< FloatType > > randomAccessImgs,
			final float[] max,
			final RealSum realSum,
			final int m )
	{
		final FloatType p = psiCursor.next();
		double sum = 0;
		int count = 0;

		for ( int j = 0; j < m; ++j )
		{
			final RandomAccess< FloatType > randomAccessImg = randomAccessImgs.get( j );
			randomAccessImg.setPosition( psiCursor );

			final float i = randomAccessImg.get().get();

			if ( i > 0 )
			{
				max[ j ] = Math.max( max[ j ], i );
				sum += i;
				++count;
			}
		}

		if ( count > 0 )
		{
			final double i = sum / count;
			realSum.add( i );
			p.set( (float)i ); // has fused data from n views
		}
		else
		{
			p.set( 0 ); // minimal deconvolution value
		}

		return count;
	}
}
