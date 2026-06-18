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
package net.preibisch.mvrecon.process.deconvolution.iteration.mul;

import java.util.ArrayList;
import java.util.List;

import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

public class DeconvolutionMethods
{
	/**
	 * One thread of a method to compute the quotient between two images of the multiview deconvolution
	 * 
	 * @param start - the start position in pixels for this thread
	 * @param loopSize - how many consecutive pixels to process
	 * @param psiBlurred - the blurred psi input
	 * @param observedImg - the observed image
	 */
	protected static final void computeQuotient(
			final long start,
			final long loopSize,
			final RandomAccessibleInterval< FloatType > psiBlurred,
			final RandomAccessibleInterval< FloatType > observedImg )
	{
		final IterableInterval< FloatType > psiBlurredIterable = Views.iterable( psiBlurred );
		final IterableInterval< FloatType > observedImgIterable = Views.iterable( observedImg );

		if ( psiBlurredIterable.iterationOrder().equals( observedImgIterable.iterationOrder() ) )
		{
			final Cursor< FloatType > cursorPsiBlurred = psiBlurredIterable.cursor();
			final Cursor< FloatType > cursorImg = observedImgIterable.cursor();
	
			cursorPsiBlurred.jumpFwd( start );
			cursorImg.jumpFwd( start );
	
			for ( long l = 0; l < loopSize; ++l )
			{
				cursorPsiBlurred.fwd();
				cursorImg.fwd();
	
				final float psiBlurredValue = cursorPsiBlurred.get().get();
				final float imgValue = cursorImg.get().get();

				if ( imgValue > 0 )
					cursorPsiBlurred.get().set( imgValue / psiBlurredValue );
				else
					cursorPsiBlurred.get().set( 1 ); // no image data, quotient=1
			}
		}
		else
		{
			final RandomAccess< FloatType > raPsiBlurred = psiBlurred.randomAccess();
			final Cursor< FloatType > cursorImg = observedImgIterable.localizingCursor();

			cursorImg.jumpFwd( start );

			for ( long l = 0; l < loopSize; ++l )
			{
				cursorImg.fwd();
				raPsiBlurred.setPosition( cursorImg );
	
				final float psiBlurredValue = raPsiBlurred.get().get();
				final float imgValue = cursorImg.get().get();
	
				if ( imgValue > 0 )
					raPsiBlurred.get().set( imgValue / psiBlurredValue );
				else
					raPsiBlurred.get().set( 1 ); // no image data, quotient=1
			}
		}
	}

	/*
	 * One thread of a method to compute the final values of one iteration of the multiview deconvolution
	 */
	protected static final void computeFinalValues(
			final long start,
			final long loopSize,
			final RandomAccessibleInterval< FloatType > psi,
			final RandomAccessibleInterval< FloatType > integral,
			final RandomAccessibleInterval< FloatType > weight,
			final double lambda,
			final float minIntensity,
			final float maxIntensity,
			final double[] sumMax )
	{
		double sumChange = 0;
		double maxChange = -1;

		final IterableInterval< FloatType > psiIterable = Views.iterable( psi );
		final IterableInterval< FloatType > integralIterable = Views.iterable( integral );
		final IterableInterval< FloatType > weightIterable = Views.iterable( weight );

		if (
			psiIterable.iterationOrder().equals( integralIterable.iterationOrder() ) && 
			psiIterable.iterationOrder().equals( weightIterable.iterationOrder() ) )
		{
			final Cursor< FloatType > cursorPsi = psiIterable.cursor();
			final Cursor< FloatType > cursorIntegral = integralIterable.cursor();
			final Cursor< FloatType > cursorWeight = weightIterable.cursor();

			cursorPsi.jumpFwd( start );
			cursorIntegral.jumpFwd( start );
			cursorWeight.jumpFwd( start );

			for ( long l = 0; l < loopSize; ++l )
			{
				cursorPsi.fwd();
				cursorIntegral.fwd();
				cursorWeight.fwd();
	
				// get the final value
				final float lastPsiValue = cursorPsi.get().get();
				final float nextPsiValue = computeNextValue( lastPsiValue, cursorIntegral.get().get(), cursorWeight.get().get(), lambda, minIntensity, maxIntensity );
				
				// store the new value
				cursorPsi.get().set( (float)nextPsiValue );

				// statistics
				final float change = change( lastPsiValue, nextPsiValue );
				sumChange += change;
				maxChange = Math.max( maxChange, change );
			}
		}
		else
		{
			final Cursor< FloatType > cursorPsi = psiIterable.localizingCursor();
			final RandomAccess< FloatType > raIntegral = integral.randomAccess();
			final RandomAccess< FloatType > raWeight = weight.randomAccess();

			cursorPsi.jumpFwd( start );

			for ( long l = 0; l < loopSize; ++l )
			{
				cursorPsi.fwd();
				raIntegral.setPosition( cursorPsi );
				raWeight.setPosition( cursorPsi );

				// get the final value
				final float lastPsiValue = cursorPsi.get().get();
				float nextPsiValue = computeNextValue( lastPsiValue, raIntegral.get().get(), raWeight.get().get(), lambda, minIntensity, maxIntensity );

				// store the new value
				cursorPsi.get().set( (float)nextPsiValue );

				// statistics
				final float change = change( lastPsiValue, nextPsiValue );
				sumChange += change;
				maxChange = Math.max( maxChange, change );
			}
		}

		sumMax[ 0 ] = sumChange;
		sumMax[ 1 ] = maxChange;
	}

	/*
	 * One thread of a method to compute the final values of one iteration of the multiview deconvolution
	 */
	protected static final void computeFinalValuesMul(
			final long start,
			final long loopSize,
			final RandomAccessibleInterval< FloatType > psi,
			final List< ? extends RandomAccessibleInterval< FloatType > > integral,
			final List< ? extends RandomAccessibleInterval< FloatType > > weight,
			final double lambda,
			final float minIntensity,
			final float maxIntensity,
			final double[] sumMax )
	{
		final int numViews = weight.size();

		final float[] integralValues = new float[ numViews ];
		final float[] weights = new float[ numViews ];

		double sumChange = 0;
		double maxChange = -1;

		final IterableInterval< FloatType > psiIterable = Views.iterable( psi );
		final ArrayList< IterableInterval< FloatType > > integralIterable = new ArrayList<>(); 
		final ArrayList< IterableInterval< FloatType > > weightIterable = new ArrayList<>();

		boolean sameIteration = true;

		for ( int i = 0; i < numViews; ++i )
		{
			integralIterable.add( Views.iterable( integral.get( i ) ) );
			weightIterable.add( Views.iterable( weight.get( i ) ) );

			sameIteration &= psiIterable.iterationOrder().equals( integralIterable.get( i ).iterationOrder() );
			sameIteration &= psiIterable.iterationOrder().equals( weightIterable.get( i ).iterationOrder() );
		}

		if ( sameIteration )
		{
			final Cursor< FloatType > cursorPsi = psiIterable.cursor();
			final ArrayList< Cursor< FloatType > > cursorIntegral = new ArrayList<>();
			final ArrayList< Cursor< FloatType > > cursorWeight = new ArrayList<>();

			for ( int i = 0; i < numViews; ++i )
			{
				cursorIntegral.add( integralIterable.get( i ).cursor() );
				cursorWeight.add( weightIterable.get( i ).cursor() );

				cursorIntegral.get( i ).jumpFwd( start );
				cursorWeight.get( i ).jumpFwd( start );
			}

			cursorPsi.jumpFwd( start );

			for ( long l = 0; l < loopSize; ++l )
			{
				cursorPsi.fwd();
				for ( int i = 0; i < numViews; ++i )
				{
					cursorIntegral.get( i ).fwd();
					cursorWeight.get( i ).fwd();
					
					integralValues[ i ] = cursorIntegral.get( i ).get().get();
					weights[ i ] = cursorWeight.get( i ).get().get();
				}

				// get the final value
				final float lastPsiValue = cursorPsi.get().get();
				final float nextPsiValue = computeNextValueMul( lastPsiValue, integralValues, weights, lambda, minIntensity, maxIntensity );

				// store the new value
				cursorPsi.get().set( (float)nextPsiValue );

				// statistics
				final float change = change( lastPsiValue, nextPsiValue );
				sumChange += change;
				maxChange = Math.max( maxChange, change );
			}
		}
		else
		{
			final Cursor< FloatType > cursorPsi = psiIterable.localizingCursor();
			final ArrayList< RandomAccess< FloatType > > raIntegral = new ArrayList<>();
			final ArrayList< RandomAccess< FloatType > > raWeight = new ArrayList<>();

			for ( int i = 0; i < numViews; ++i )
			{
				raIntegral.add( integral.get( i ).randomAccess() );
				raWeight.add( weight.get( i ).randomAccess() );
			}

			cursorPsi.jumpFwd( start );

			for ( long l = 0; l < loopSize; ++l )
			{
				cursorPsi.fwd();

				for ( int i = 0; i < numViews; ++i )
				{
					raIntegral.get( i ).setPosition( cursorPsi );
					raWeight.get( i ).setPosition( cursorPsi );

					integralValues[ i ] = raIntegral.get( i ).get().get();
					weights[ i ] = raWeight.get( i ).get().get();
				}

				// get the final value
				final float lastPsiValue = cursorPsi.get().get();
				float nextPsiValue = computeNextValueMul( lastPsiValue, integralValues, weights, lambda, minIntensity, maxIntensity );

				// store the new value
				cursorPsi.get().set( (float)nextPsiValue );

				// statistics
				final float change = change( lastPsiValue, nextPsiValue );
				sumChange += change;
				maxChange = Math.max( maxChange, change );
			}
		}

		sumMax[ 0 ] = sumChange;
		sumMax[ 1 ] = maxChange;
	}

	private static final float change( final float lastPsiValue, final float nextPsiValue ) { return /*Math.abs*/( ( nextPsiValue - lastPsiValue ) ); }

	/**
	 * compute the next value for a specific pixel
	 * 
	 * @param lastPsiValue - the previous value
	 * @param integralValue - result from the integral
	 * @param lambda - if > 0, regularization
	 * @param minIntensity - the lowest allowed value
	 * @param maxIntensity - to normalize lambda (works between 0...1)
	 * @return
	 */
	private static final float computeNextValue(
			final float lastPsiValue,
			final float integralValue,
			final float weight,
			final double lambda,
			final float minIntensity,
			final float maxIntensity )
	{
		final float value = lastPsiValue * integralValue;
		final float adjustedValue;

		if ( value > 0 )
		{
			//
			// perform Tikhonov regularization if desired
			//
			if ( lambda > 0 )
				adjustedValue = (float)tikhonov( value / maxIntensity, lambda ) * maxIntensity;
			else
				adjustedValue = value;
		}
		else
		{
			adjustedValue = minIntensity;
		}

		//
		// get the final value and some statistics
		//
		final float nextPsiValue;

		if ( Double.isNaN( adjustedValue ) )
			nextPsiValue = (float)minIntensity;
		else
			nextPsiValue = (float)Math.max( minIntensity, adjustedValue );

		// compute the difference between old and new and apply the appropriate amount
		return lastPsiValue + ( ( nextPsiValue - lastPsiValue ) * weight );
	}

	/**
	 * compute the next value for a specific pixel
	 * 
	 * @param lastPsiValue - the previous value
	 * @param integralValue - result from the integral
	 * @param lambda - if > 0, regularization
	 * @param minIntensity - the lowest allowed value
	 * @param maxIntensity - to normalize lambda (works between 0...1)
	 * @return
	 */
	private static final float computeNextValueMul(
			final float lastPsiValue,
			final float[] integralValue,
			final float[] weight,
			final double lambda,
			final float minIntensity,
			final float maxIntensity )
	{
		double sumW = 0;
		double prod = 1;
		for ( int i = 0; i < weight.length; ++i )
		{
			prod *= (double)integralValue[ i ];
			sumW += (double)weight[ i ];
		}

		prod = Math.pow( prod, 1.0 / weight.length );
		sumW = Math.min( 1.0, sumW );

		final float value = lastPsiValue * (float)prod;
		final float adjustedValue;

		if ( value > 0 )
		{
			//
			// perform Tikhonov regularization if desired
			//
			if ( lambda > 0 )
				adjustedValue = (float)tikhonov( value / maxIntensity, lambda ) * maxIntensity;
			else
				adjustedValue = value;
		}
		else
		{
			adjustedValue = minIntensity;
		}

		//
		// get the final value and some statistics
		//
		final float nextPsiValue;

		if ( Double.isNaN( adjustedValue ) )
			nextPsiValue = (float)minIntensity;
		else
			nextPsiValue = (float)Math.max( minIntensity, adjustedValue );

		// compute the difference between old and new and apply the appropriate amount
		return lastPsiValue + ( ( nextPsiValue - lastPsiValue ) * (float)sumW );
	}

	private static final double tikhonov( final double value, final double lambda ) { return ( Math.sqrt( 1.0 + 2.0*lambda*value ) - 1.0 ) / lambda; }

}
