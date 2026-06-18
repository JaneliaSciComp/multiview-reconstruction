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
import java.util.Date;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.RealSum;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.ImgLib2Temp.Triple;
import net.preibisch.mvrecon.process.deconvolution.DeconView;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.ImagePortion;

public class PsiInitAvgPrecise implements PsiInit
{
	double avg = -1;
	float[] max = null;
	boolean setImgToAvg = true;

	public void setImgToAvg( final boolean setImgToAvg ) { this.setImgToAvg = setImgToAvg; }

	@Override
	public boolean runInitialization( final Img< FloatType > psi, final List< DeconView > views, final ExecutorService service )
	{
		this.max = new float[ views.size() ];

		// split up into many parts for multithreading
		final Vector< ImagePortion > portions = FusionTools.divideIntoPortions( psi.size() );
		final ArrayList< Callable< Triple< RealSum, Long, float[] > > > tasks = new ArrayList< Callable< Triple< RealSum, Long, float[] > > >();

		final ArrayList< RandomAccessibleInterval< FloatType > > imgs = new ArrayList< RandomAccessibleInterval< FloatType > >();

		for ( final DeconView mvdecon : views )
			imgs.add( mvdecon.getImage() );

		for ( final ImagePortion portion : portions )
			tasks.add( new PsiInitAvgPreciseThread( portion, psi, imgs ) );

		final RealSum s = new RealSum();
		long count = 0;

		try
		{
			// invokeAll() returns when all tasks are complete
			final List< Future< Triple< RealSum, Long, float[] > > > imgIntensities = service.invokeAll( tasks );

			for ( final Future< Triple< RealSum, Long, float[] >  > future : imgIntensities )
			{
				s.add( future.get().getA().getSum() );
				count += future.get().getB().longValue();

				if ( max != null )
					for ( int i = 0; i < max.length; ++i )
						max[ i ] = Math.max( max[ i ], future.get().getC()[ i ] );
			}
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "Failed to fuse initial iteration: " + e );
			e.printStackTrace();
			return false;
		}

		avg = s.getSum() / (double)count;

		if ( Double.isNaN( avg ) )
		{
			avg = 1.0;
			IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): ERROR! Computing average FAILED, is NaN, setting it to: " + avg );
		}

		IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Average intensity in overlapping area: " + avg );

		if ( setImgToAvg )
		{
			IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Setting image to average intensity: " + avg );
	
			for ( final FloatType t : psi )
				t.set( (float)avg );
		}

		return true;
	}

	@Override
	public double getAvg() { return avg; }

	@Override
	public float[] getMax() { return max; }
}
