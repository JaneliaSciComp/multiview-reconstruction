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
package net.preibisch.mvrecon.process.interestpointdetection;

import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.ExecutorService;

import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.Point;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.localextrema.RefinedPeak;
import net.imglib2.algorithm.localextrema.SubpixelLocalization;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.legacy.segmentation.SimplePeak;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointValue;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;

public class Localization
{
	public static ArrayList< InterestPoint > noLocalization( final ArrayList< SimplePeak > peaks, final boolean findMin, final boolean findMax, final boolean keepIntensity )
	{
		if ( !DoGImgLib2.silent )
			IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): NO subpixel localization" );

		if ( peaks == null || peaks.size() == 0 )
			return new ArrayList<>();

		final int n = peaks.get( 0 ).location.length;
		final ArrayList< InterestPoint > peaks2 = new ArrayList< InterestPoint >();
		
		int id = 0;
		
		for ( final SimplePeak peak : peaks )
		{
			if ( ( peak.isMax && findMax ) || ( peak.isMin && findMin ) )
			{
				final double[] pos = new double[ n ];
				
				for ( int d = 0; d < n; ++d )
					pos[ d ] = peak.location[ d ];
				
				if ( keepIntensity )
					peaks2.add( new InterestPointValue( id++, pos, peak.intensity ) );
				else
					peaks2.add( new InterestPoint( id++, pos ) );
			}
		}
		
		return peaks2;
	}

	public static ArrayList< InterestPoint > computeQuadraticLocalization(
			final ArrayList< SimplePeak > peaks,
			final RandomAccessible< FloatType > dogImg,
			final Interval validInterval,
			final boolean findMin,
			final boolean findMax,
			final float threshold,
			final boolean keepIntensity,
			final ExecutorService ex )
	{
		if ( !DoGImgLib2.silent )
			IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): Subpixel localization using quadratic n-dimensional fit");

		final ArrayList< Point > peakList = new ArrayList<>();

		//for ( final SimplePeak peak : peaks )
		//	if ( ( peak.isMax && findMax ) || ( peak.isMin && findMin ) )
		//		peakList.add( new DifferenceOfGaussianPeak<FloatType>( peak.location, new FloatType( peak.intensity ), SpecialPoint.MAX ) );

		for ( final SimplePeak peak : peaks )
			if ( ( peak.isMax && findMax ) || ( peak.isMin && findMin ) )
				peakList.add( new Point( peak.location ) );

		final int n = dogImg.numDimensions();

		final SubpixelLocalization<Point, FloatType> spl = new SubpixelLocalization<>( n );
		spl.setAllowMaximaTolerance( true );
		spl.setMaxNumMoves( 10 );

		final ArrayList<RefinedPeak<Point>> refinedPeaks =
				SubpixelLocalization.refinePeaks(
						peakList,
						dogImg,
						validInterval,
						spl.getReturnInvalidPeaks(),
						spl.getMaxNumMoves(),
						spl.getAllowMaximaTolerance(),
						spl.getMaximaTolerance(),
						spl.getAllowedToMoveInDim(),
						( int ) Math.min( peakList.size(), Threads.numThreads() * 20 ),
						ex );

		final ArrayList< InterestPoint > peaks2 = new ArrayList< InterestPoint >();

		int id = 0;

		for ( final RefinedPeak<Point> r : refinedPeaks )
		{
			if ( Math.abs( r.getValue() ) > threshold )
			{
				final double[] tmp = new double[ n ];
				for ( int d = 0; d < n; ++d )
					tmp[ d ] = r.getDoublePosition( d );

				if ( keepIntensity )
					peaks2.add( new InterestPointValue( id++, tmp, r.getValue() ) );
				else
					peaks2.add( new InterestPoint( id++, tmp ) );
			}
		}

		/*
		for ( DifferenceOfGaussianPeak<FloatType> detection : peakList )
		{
			if ( Math.abs( detection.getValue().get() ) > threshold )
			{
				final double[] tmp = new double[ n ];
				for ( int d = 0; d < n; ++d )
					tmp[ d ] = detection.getSubPixelPosition( d );

				if ( keepIntensity )
					peaks2.add( new InterestPointValue( id++, tmp, detection.getValue().get() ) );
				else
					peaks2.add( new InterestPoint( id++, tmp ) );
			}
		}
		*/

		return peaks2;
	}
	
	public static ArrayList< InterestPoint > computeGaussLocalization( final ArrayList< SimplePeak > peaks, final RandomAccessibleInterval< FloatType > domImg, final double sigma, final boolean findMin, final boolean findMax, final float threshold, final boolean keepIntensity )
	{
		IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): Subpixel localization using Gaussian Mask Localization");

		// TODO: implement gauss fit
		throw new RuntimeException( "Gauss fit not implemented yet" );
	}	
}
