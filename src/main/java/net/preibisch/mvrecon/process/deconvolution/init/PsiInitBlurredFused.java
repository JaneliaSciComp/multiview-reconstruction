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
import java.util.concurrent.ExecutorService;

import net.imglib2.util.Util;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.RealSum;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.process.deconvolution.DeconView;
import net.preibisch.mvrecon.process.deconvolution.util.FusedNonZeroRandomAccess;
import net.preibisch.mvrecon.process.deconvolution.util.FusedNonZeroRandomAccessibleInterval;
import net.preibisch.mvrecon.process.fusion.FusionTools;

public class PsiInitBlurredFused implements PsiInit
{
	final double sigma;

	double avg = -1;
	float[] max = null;

	public PsiInitBlurredFused( final double sigma )
	{
		this.sigma = sigma;
	}

	public PsiInitBlurredFused()
	{
		this( 5.0 );
	}

	@Override
	public boolean runInitialization( final Img< FloatType > psi, final List< DeconView > views, final ExecutorService service )
	{
		final ArrayList< RandomAccessibleInterval< FloatType > > images = new ArrayList<>();
		final ArrayList< RandomAccessibleInterval< FloatType > > weights = new ArrayList<>();

		for ( final DeconView view : views )
		{
			images.add( view.getImage() );
			weights.add( view.getWeight() );
		}

		IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Setting virtually fused image" );

		final FusedNonZeroRandomAccessibleInterval fused = new FusedNonZeroRandomAccessibleInterval( new FinalInterval( psi ), images, weights );

		IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Fusing estimate of deconvolved image ..." );

		FusionTools.copyImg( fused, psi, service, true );

		final RealSum s = new RealSum();
		long count = 0;
		this.max = new float[ views.size() ];

		for ( final FusedNonZeroRandomAccess ra : fused.getAllAccesses() )
		{
			for ( int i = 0; i < max.length; ++i )
				max[ i ] = Math.max( max[ i ], ra.getMax()[ i ] );

			s.add( ra.getRealSum().getSum() );
			count += ra.numContributingPixels();
		}

		if ( count == 0 )
		{
			IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): ERROR! None of the views covers the deconvolved area, did you set the bounding box right? Exiting." );
			return false;
		}

		avg = s.getSum() / (double)count;

		if ( Double.isNaN( avg ) )
		{
			avg = 1.0;
			IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): ERROR! Computing average FAILED, is NaN, setting it to: " + avg );
		}

		IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Average intensity in overlapping area: " + avg );

		IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Blurring input image with sigma = " + sigma + " to use as input");

		try
		{
			Gauss3.gauss( Util.getArrayFromValue( sigma, psi.numDimensions() ), Views.extendMirrorSingle( psi ), psi, service );
		}
		catch ( IncompatibleTypeException e )
		{
			e.printStackTrace();
			IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): ERROR, Couldn't convolve image: " + e );
			return false;
		}

		//DisplayImage.getImagePlusInstance( psi, false, "psi", Double.NaN, Double.NaN ).show();

		return true;
	}

	@Override
	public double getAvg() { return avg; }

	@Override
	public float[] getMax() { return max; }
}
