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

import java.util.Random;
import java.util.concurrent.Callable;

import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.RealSum;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.process.deconvolution.DeconView;

public class PsiInitAvgApproxThread implements Callable< Pair< double[], Integer > >
{
	final DeconView mvdecon;
	final int listId;
	final int numPixels;
	final Random rnd;

	public PsiInitAvgApproxThread( final DeconView mvdecon, final int listId, final int numPixels )
	{
		this.mvdecon = mvdecon;
		this.listId = listId;
		this.numPixels = numPixels;
		this.rnd = new Random( 34556 + listId ); // not the same pseudo-random numbers for each thread
	}

	public PsiInitAvgApproxThread( final DeconView mvdecon, final int listId )
	{
		this( mvdecon, listId, 1000 );
	}

	@Override
	public Pair< double[], Integer > call() throws Exception
	{
		final RandomAccessibleInterval< FloatType > img = mvdecon.getImage();

		// run threads and combine results
		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		final RealSum realSum = new RealSum( numPixels );

		long numPixels = 0;

		for ( int d = 0; d < img.numDimensions(); ++d )
		{
			final IterableInterval< FloatType > iterable = Views.iterable( Views.hyperSlice( img, 0, img.dimension( 0 ) / 2 ) );
			numPixels += iterable.size();

			for ( final FloatType t : iterable )
			{
				final double v = t.getRealDouble();

				min = Math.min( min, v );
				max = Math.max( max, v );
				realSum.add( v );
			}
		}

		return new ValuePair<>( new double[]{ min, max, realSum.getSum() / (double)numPixels }, listId );
	}
}
