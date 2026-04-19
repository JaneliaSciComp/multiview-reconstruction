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
package net.preibisch.legacy.registration.bead.laplace;

import java.util.ArrayList;
import java.util.Iterator;

import Jama.EigenvalueDecomposition;
import Jama.Matrix;
import mpicbg.models.NoninvertibleModelException;
import net.imglib2.RandomAccess;
import net.imglib2.type.numeric.real.FloatType;

final public class LaPlaceFunctions 
{
	public static void analyzeMaximum( final RandomAccess<FloatType> cursor, final float minPeakValue, final int width, final int height, final int depth, 
									   final float sigma, final float identityRadius, final float maximaTolerance, final RejectStatistics rs,
									   final ArrayList<DoGMaximum> localMaxima)
	{
		final int x = cursor.getIntPosition( 0 );
		final int y = cursor.getIntPosition( 1 );
		final int z = cursor.getIntPosition( 2 );

		int xs = x;
		int ys = y;
		int zs = z;

		boolean foundStableMaxima = true, pointsValid = false;
		int count = 0;

		//final FloatNeighborhood3DAccess neighborAccessor = new FloatNeighborhood3DAccess(cursor);
		
		DoGMaximum resultVoxel = null;

		// find a fitted extremum and if the extremum is shifted more than 0.5
		// in one or more direction we test
		// wheather it is better there
		do
		{
			final DoGMaximum currentVoxel = new DoGMaximum(xs, ys, zs, count);

			count++;

			// find a fitted extremum and if the extremum is shifted more than
			// 0.5 in one or more direction we test
			// wheather it is better there
			// this did not work out to be very stable, that's why we just take
			// those positions which are
			// within a range of 0...1,5 of the found maxima in the laplcae
			// space

			//
			// fill hessian matrix with second derivatives
			//
			currentVoxel.hessianMatrix3x3 = computeHessianMatrix3x3( cursor );

			//
			// Invert hessian Matrix
			//
			try 
			{
				invert( currentVoxel.hessianMatrix3x3 );
				currentVoxel.A = new Matrix( currentVoxel.hessianMatrix3x3, 3);
			} 
			catch (NoninvertibleModelException e1) 
			{
				currentVoxel.A = null;
			}

			// cannot inverse matrix properly
			if (currentVoxel.A == null)
			{
				rs.noInverseOfHessianMatrix++;				
				continue;				
			}

			//
			// fill first derivate vector
			//
			currentVoxel.derivativeVector = computeDerivativeVector3( cursor );
			currentVoxel.B = new Matrix( currentVoxel.derivativeVector, 3 );

			//
			// compute the extremum of the quadratic fit
			//
			currentVoxel.X = (currentVoxel.A.uminus()).times(currentVoxel.B);

			currentVoxel.xd = currentVoxel.X.get(0, 0);
			currentVoxel.yd = currentVoxel.X.get(1, 0);
			currentVoxel.zd = currentVoxel.X.get(2, 0);
			
			//
			// check all directions for changes
			//
			foundStableMaxima = true;

			if (Math.abs(currentVoxel.xd) > 0.5 + count * maximaTolerance)
			//if (Math.abs(currentVoxel.xd) > 1)
			{				
				xs += Math.signum( currentVoxel.xd );
				foundStableMaxima = false;
			}

			if (Math.abs(currentVoxel.yd) > 0.5 + count * maximaTolerance)
			//if (Math.abs(currentVoxel.yd) > 1)
			{
				ys += Math.signum( currentVoxel.yd );
				foundStableMaxima = false;
			}

			if (Math.abs(currentVoxel.zd) > 0.5 + count * maximaTolerance)
			//if (Math.abs(currentVoxel.zd) > 1)
			{
				zs += Math.signum( currentVoxel.zd );
				foundStableMaxima = false;
			}
			
			if ( !foundStableMaxima )
			{
				cursor.setPosition( xs, 0 );
				cursor.setPosition( ys, 1 );
				cursor.setPosition( zs, 2 );
			}

			//
			// check validity of new point
			//
			pointsValid = true;

			if (!foundStableMaxima) 
				if (xs <= 0 || xs >= width - 1 || ys <= 0 || ys >= height - 1 || zs <= 0 || zs >= depth - 1) 
					pointsValid = false;

			resultVoxel = currentVoxel;
		} while (count <= 10 && !foundStableMaxima && pointsValid);

		// could not invert hessian matrix properly
		if (resultVoxel == null || resultVoxel.A == null)
		{
			cursor.setPosition( x, 0 );
			cursor.setPosition( y, 1 );
			cursor.setPosition( z, 2 );
			return;
		}

		// did not found a stable maxima
		if (!foundStableMaxima)
		{
			rs.noStableMaxima++;
			
			cursor.setPosition( x, 0 );
			cursor.setPosition( y, 1 );
			cursor.setPosition( z, 2 );
			return;
		}

		resultVoxel.quadrFuncValue = 0;

		for (int j = 0; j < 3 ; j++)
			resultVoxel.quadrFuncValue += resultVoxel.X.get(j, 0) * resultVoxel.derivativeVector[j];
		resultVoxel.quadrFuncValue /= 2d;

		resultVoxel.laPlaceValue = cursor.get().get();
		resultVoxel.sumValue = resultVoxel.quadrFuncValue + resultVoxel.laPlaceValue;
		
		if (Math.abs(resultVoxel.sumValue) < minPeakValue)
		{
			rs.peakTooLow++;
			resultVoxel = null;
			
			cursor.setPosition( x, 0 );
			cursor.setPosition( y, 1 );
			cursor.setPosition( z, 2 );
			return;
		}
		
		/*
		// now reject where curvatures are not equal enough in all directions
		EigenvalueDecomposition e = computeEigenDecomposition( resultVoxel.hessianMatrix3x3 );

		if (e == null)
		{
			resultVoxel.eigenValues = null;
			resultVoxel.eigenVectors = null;
		}
		else
		{
			resultVoxel.eigenVectors = e.getV().getArray();
			resultVoxel.eigenValues = e.getRealEigenvalues();
		}

		// there were imaginary numbers for the eigenvectors
		// -> bad!
		if (resultVoxel.eigenValues == null)
		{
 			//.imaginaryEigenValues++;
			resultVoxel = null;
			
			cursor.setPosition(x, y, z);
			return;
		}

		// compute ratios of the eigenvalues
		if (resultVoxel.eigenValues[0] >= resultVoxel.eigenValues[1]) resultVoxel.EVratioA = resultVoxel.eigenValues[1] / resultVoxel.eigenValues[0];
		else resultVoxel.EVratioA = resultVoxel.eigenValues[0] / resultVoxel.eigenValues[1];

		if (resultVoxel.eigenValues[0] >= resultVoxel.eigenValues[2]) resultVoxel.EVratioB = resultVoxel.eigenValues[2] / resultVoxel.eigenValues[0];
		else resultVoxel.EVratioB = resultVoxel.eigenValues[0] / resultVoxel.eigenValues[2];

		if (resultVoxel.eigenValues[1] >= resultVoxel.eigenValues[2]) resultVoxel.EVratioC = resultVoxel.eigenValues[2] / resultVoxel.eigenValues[1];
		else resultVoxel.EVratioC = resultVoxel.eigenValues[1] / resultVoxel.eigenValues[2];

		// these ratios describe the shape of the 3D elipsoid fitted
		// for now, we only use a lower barrier

		resultVoxel.minEVratio = Math.min(Math.min(resultVoxel.EVratioA, resultVoxel.EVratioB), resultVoxel.EVratioC);

		IOFunctions.println("Eigenvalue ratios:");
		IOFunctions.println(resultVoxel.EVratioA);
		IOFunctions.println(resultVoxel.EVratioB);
		IOFunctions.println(resultVoxel.EVratioC);
		IOFunctions.println(resultVoxel.minEVratio);
		*/
		
		resultVoxel.sigma = sigma; 
		
		localMaxima.add(resultVoxel);
		
		cursor.setPosition( x, 0 );
		cursor.setPosition( y, 1 );
		cursor.setPosition( z, 2 );
	}

	final public static EigenvalueDecomposition computeEigenDecomposition( final double[] matrix )
	{
		final Matrix M = new Matrix(matrix, 3);		
		final EigenvalueDecomposition E = new EigenvalueDecomposition(M);

		final double[] result = E.getImagEigenvalues();

		boolean found = false;

		for (double im : result)
			if (im > 0) found = true;

		if (found) return null;
		else return E;
	}

	final static public double det( final double[] a )
	{
		assert a.length == 9 : "Matrix3x3 supports 3x3 double[][] only.";
		
		return
			a[ 0 ] * a[ 4 ] * a[ 8 ] +
			a[ 3 ] * a[ 7 ] * a[ 2 ] +
			a[ 6 ] * a[ 1 ] * a[ 5 ] -
			a[ 2 ] * a[ 4 ] * a[ 6 ] -
			a[ 5 ] * a[ 7 ] * a[ 0 ] -
			a[ 8 ] * a[ 1 ] * a[ 3 ];
	}
	
	final static public void invert( double[] a ) throws NoninvertibleModelException
	{
		assert a.length == 9 : "Matrix3x3 supports 3x3 double[][] only.";
		
		final double det = det( a );
		if ( det == 0 ) throw new NoninvertibleModelException( "Matrix not invertible." );
		
		final double i00 = ( a[ 4 ] * a[ 8 ] - a[ 5 ] * a[ 7 ] ) / det;
		final double i01 = ( a[ 2 ] * a[ 7 ] - a[ 1 ] * a[ 8 ] ) / det;
		final double i02 = ( a[ 1 ] * a[ 5 ] - a[ 2 ] * a[ 4 ] ) / det;
		
		final double i10 = ( a[ 5 ] * a[ 6 ] - a[ 3 ] * a[ 8 ] ) / det;
		final double i11 = ( a[ 0 ] * a[ 8 ] - a[ 2 ] * a[ 6 ] ) / det;
		final double i12 = ( a[ 2 ] * a[ 3 ] - a[ 0 ] * a[ 5 ] ) / det;
		
		final double i20 = ( a[ 3 ] * a[ 7 ] - a[ 4 ] * a[ 6 ] ) / det;
		final double i21 = ( a[ 1 ] * a[ 6 ] - a[ 0 ] * a[ 7 ] ) / det;
		final double i22 = ( a[ 0 ] * a[ 4 ] - a[ 1 ] * a[ 3 ] ) / det;
		
		a[ 0 ] = i00;
		a[ 1 ] = i01;
		a[ 2 ] = i02;

		a[ 3 ] = i10;
		a[ 4 ] = i11;
		a[ 5 ] = i12;

		a[ 6 ] = i20;
		a[ 7 ] = i21;
		a[ 8 ] = i22;
	}
	
	final public static double[] computeDerivativeVector3( final RandomAccess<FloatType> cursor )
	{
		final double[] derivativeVector = new double[3];

		// x
		// derivativeVector[0] = (accessor.getRelative(1, 0, 0) - accessor.getRelative(-1, 0, 0)) / 2;
		//
		cursor.fwd( 0 );
		// we are now at (1, 0, 0)
		derivativeVector[0] = cursor.get().get();
		cursor.bck( 0 );
		cursor.bck( 0 );
		// we are now at (-1, 0, 0)
		derivativeVector[0] -= cursor.get().get();
		derivativeVector[0] /= 2.0f;
		
		// y
		// derivativeVector[1] = (accessor.getRelative(0, 1, 0) - accessor.getRelative(0, -1, 0)) / 2;
		//
		cursor.fwd( 0 );
		cursor.fwd( 1 );
		// we are now at (0, 1, 0)
		derivativeVector[1] = cursor.get().get();
		cursor.bck( 1 );
		cursor.bck( 1 );
		// we are now at (0, -1, 0)
		derivativeVector[1] -= cursor.get().get();
		derivativeVector[1] /= 2.0f;

		// z
		// derivativeVector[2] = (accessor.getRelative(0, 0, 1) - accessor.getRelative(0, 0, -1)) / 2;
		//
		cursor.fwd( 1 );
		cursor.fwd( 2 );
		// we are now at (0, 0, 1)
		derivativeVector[2] = cursor.get().get();
		cursor.bck( 2 );
		cursor.bck( 2 );
		// we are now at (0, 0, -1)
		derivativeVector[2] -= cursor.get().get();
		derivativeVector[2] /= 2.0f;
		
		// we are now at (0, 0, 0)
		cursor.fwd( 2 );

		return derivativeVector;

	}
	
	final public static double[] computeHessianMatrix3x3( final RandomAccess<FloatType> cursor )
	{
		final double[] hessianMatrix = new double[9];

		final float temp = 2 * cursor.get().get();

		//
		// xx
		//
		//hessianMatrix[0] = (1, 0, 0) - temp + (-1, 0, 0);
		cursor.fwd( 0 );
		// we are now at (1, 0, 0)
		hessianMatrix[ 0 ] = cursor.get().get() - temp;
		
		cursor.bck( 0 );
		cursor.bck( 0 );
		// we are now at (-1, 0, 0)
		hessianMatrix[ 0 ] += cursor.get().get();
		
		//
		// yy
		//
		//hessianMatrix[4] = (0, 1, 0) - temp + (0, -1, 0);
		cursor.fwd( 0 );
		cursor.fwd( 1 );
		// we are now at (0, 1, 0)
		hessianMatrix[ 4 ] = cursor.get().get() - temp;
		
		cursor.bck( 1 );
		cursor.bck( 1 );
		// we are now at (0, -1, 0)
		hessianMatrix[ 4 ] += cursor.get().get();
		
		//
		// zz
		//
		//hessianMatrix[8] =(0, 0, 1) - temp + (0, 0, -1);
		cursor.fwd( 1 );
		cursor.fwd( 2 );
		// we are now at (0, 0, 1)
		hessianMatrix[ 8 ] = cursor.get().get() - temp;
		
		cursor.bck( 2 );
		cursor.bck( 2 );
		// we are now at (0, 0, -1)
		hessianMatrix[ 8 ] += cursor.get().get();

		// yz
		// hessianMatrix[5] = hessianMatrix[7] = (((0, 1, 1) - (0, -1, 1)) / 2 - ((0, 1, -1) - (0, -1, -1)) / 2) / 2;
		//
		final float a, b, c, d;
		
		cursor.bck( 1 );		
		// we are now at (0, -1, -1)
		d = cursor.get().get();

		cursor.fwd( 1 );		
		cursor.fwd( 1 );		
		// we are now at (0, 1, -1)
		c = cursor.get().get();

		cursor.fwd( 2 );		
		cursor.fwd( 2 );		
		// we are now at (0, 1, 1)
		a = cursor.get().get();
		
		cursor.bck( 1 );
		cursor.bck( 1 );
		// we are now at (0, -1, 1)
		b = cursor.get().get();
		
		hessianMatrix[5] = hessianMatrix[7] = ((a - b) / 2 - (c - d) / 2) / 2;

		// xz
		// hessianMatrix[2] = hessianMatrix[6] = (((1, 0, 1) - (-1, 0, 1)) / 2 - ((1, 0, -1) - (-1, 0, -1)) / 2) / 2;
		//
		final float e, f, g, h;
		
		cursor.fwd( 1 );
		cursor.fwd( 0 );
		// we are now at (1, 0, 1)		
		e = cursor.get().get();
		
		cursor.bck( 0 );
		cursor.bck( 0 );
		// we are now at (-1, 0, 1)		
		f = cursor.get().get();
		
		cursor.bck( 2 );
		cursor.bck( 2 );
		// we are now at (-1, 0, -1)
		h = cursor.get().get();
		
		cursor.fwd( 0 );
		cursor.fwd( 0 );
		// we are now at (1, 0, -1)
		g = cursor.get().get();
		
		hessianMatrix[2] = hessianMatrix[6] = ((e - f) / 2 - (g - h) / 2) / 2;
		
		// xy
		// hessianMatrix[1] = hessianMatrix[3] = (((1, 1, 0) - (-1, 1, 0)) / 2 - ((1, -1, 0) - (-1, -1, 0)) / 2) / 2;
		//
		final float i, j, k, l;
		
		cursor.fwd( 2 );
		cursor.fwd( 1 ); 
		// we are now at (1, 1, 0)
		i = cursor.get().get();
		
		cursor.bck( 0 );
		cursor.bck( 0 );
		// we are now at (-1, 1, 0)
		j = cursor.get().get();
		
		cursor.bck( 1 );
		cursor.bck( 1 );
		// we are now at (-1, -1, 0)
		l = cursor.get().get();
		
		cursor.fwd( 0 );		
		cursor.fwd( 0 );
		// we are now at (1, -1, 0)
		k = cursor.get().get();
		
		hessianMatrix[1] = hessianMatrix[3] = ((i - j) / 2 - (k - l) / 2) / 2;
		
		cursor.bck( 0 );
		cursor.fwd( 1 );
		// we are now at (0, 0, 0)
		
		return hessianMatrix;
	}
	/*
	public static boolean isSpecialPointMin( final RandomAccess<FloatType> cursor, final float minInitialPeakValue )
	{
		final float value = cursor.get().get();

		if ( Math.abs(value) < minInitialPeakValue ) 
			return false;

		// instantiate a RectangleShape to access rectangular local neighborhoods
		// of radius 1 (that is 3x3x...x3 neighborhoods), skipping the center pixel
		// (this corresponds to an 8-neighborhood in 2d or 26-neighborhood in 3d, ...)
		final RectangleShape shape = new RectangleShape( 1, true );

		shape.neighborhoods( source )
		// we have to compare 26 neighbors relative to the current position
		final LocalNeighborhoodCursor3D<FloatType> neighborhoodCursor = new LocalNeighborhoodCursor3D<FloatType>( cursor );

		boolean isMin = true;
		
		while ( isMin && neighborhoodCursor.hasNext() )
		{
			neighborhoodCursor.fwd();
			isMin = (cursor.get().get() >= value);			
			//if ( cursor.getType().get() < value) isMin = false;
		}

		neighborhoodCursor.reset();
		neighborhoodCursor.close();

		// weil minima (min in 2.abl = maximum) oft durch schattenwurf der
		// zellkerne erzeugt
		return isMin;
	}

	public static void subtractImagesInPlace( final Img<FloatType> img1, final Img<FloatType> img2, final float norm )
	{
		if ( !img1.getContainer().compareStorageContainerDimensions( img2.getContainer() ))
		{
			IOFunctions.println( "Containers are not of the same size, quitting.");
			return;
		}
		
		if ( img1.getContainer().getClass().isInstance( img2.getContainer()) )
		{
			final Cursor<FloatType> c1 = img1.createCursor();
			final Cursor<FloatType> c2 = img2.createCursor();
			
			while ( c1.hasNext() )
			{
				c1.fwd(); c2.fwd();				
				c1.getType().sub( c2.getType() );
				c1.getType().mul( norm );
			}
			
			c1.close();
			c2.close();
		}
		else
		{
			IOFunctions.println( "Containers are not of the same type, more complicated");
			
			final LocalizableCursor<FloatType> c1 = img1.createLocalizableCursor();
			final LocalizableByDimCursor<FloatType> c2 = img2.createLocalizableByDimCursor();
			
			final int pos[] = new int[ img1.getNumDimensions() ]; 
			while ( c1.hasNext() )
			{
				c1.fwd(); 
				c1.getPosition( pos );
				c2.setPosition( pos );				
				c1.getType().sub( c2.getType() );
			}
			
			c1.close();
			c2.close();
		}
	}
	*/
	public static float[] computeSigma(final int steps, final float k, final float initialSigma)
	{
		final float[] sigma = new float[steps + 1];

		sigma[0] = initialSigma;

		for (int i = 1; i <= steps; i++)
		{
			// sigma[i] = initialSigma * (float)Math.pow( 2, ( float )i/( float
			// )OCT_STEPS );
			sigma[i] = sigma[i - 1] * k;
		}

		return sigma;
	}

	public static float getDiffSigma(final float sigma_a, final float sigma_b)
	{
		return (float) Math.sqrt(sigma_b * sigma_b - sigma_a * sigma_a);
	}

	public static float[] computeSigmaDiff(final float[] sigma, final float imageSigma)
	{
		final int steps = sigma.length - 1;
		final float[] sigmaDiff = new float[steps + 1];

		sigmaDiff[0] = getDiffSigma(imageSigma, sigma[0]);

		for (int i = 1; i <= steps; i++)
			sigmaDiff[i] = getDiffSigma(imageSigma, sigma[i]);

		return sigmaDiff;
	}

	public static float computeK(final int stepsPerOctave)
	{
		return (float) Math.pow(2f, 1f / stepsPerOctave);
	}

	public static float computeKWeight(final float k)
	{
		return 1.0f / (k - 1.0f);
	}

	@SuppressWarnings("unchecked")
	public static ArrayList<DoGMaximum> checkMaximaXTree( final ArrayList<DoGMaximum> input, final float identityRadius )
	{
		if ( input.size() == 0 )
			return new ArrayList<DoGMaximum>();
		
		// now we check that this maxima is unique in its identity radius.
		// if there are maxima which are smaller they get removed
		
		double minX = Float.MAX_VALUE;
		double maxX = -Float.MAX_VALUE;
		
		for ( DoGMaximum resultVoxel : input )
		{
			final double x = resultVoxel.x + resultVoxel.xd; 

			if ( x < minX )
				minX = x;
		
			if ( x > maxX )
				maxX = x;
		}
		
		int min = (int)minX;
		int max = (int)maxX + 1;
		
		ArrayList<DoGMaximum>[] maxima = new ArrayList[ max - min + 1 ];
		
		for ( int i = 0; i < maxima.length; i++ )
			maxima[ i ] = new ArrayList<DoGMaximum>();
							
		for ( DoGMaximum resultVoxel : input )
		{
			final double x = resultVoxel.x + resultVoxel.xd;
			
			int startX = (int)( x - identityRadius ) - 1;
			int endX = (int)( x + identityRadius ) + 2;
			
			if ( startX < min )
				startX = min;
			
			if ( endX > max )
				endX = max;
						
			boolean isHighest = true;
	
			final ArrayList<DoGMaximum> removeMaxima = new ArrayList<DoGMaximum>();
			int removeI = -1;
	
			for ( int i = startX - min; i <= endX - min && isHighest; i++)
			{
				for ( final Iterator<DoGMaximum> k = maxima[i].iterator(); k.hasNext() && isHighest;)
				{
					final DoGMaximum otherMaximum = k.next();
					final float distance = resultVoxel.getDistanceTo(otherMaximum);
					
					if ( distance <= identityRadius )
					{
						if ( Math.abs(otherMaximum.sumValue) > Math.abs(resultVoxel.sumValue) )
						{
							isHighest = false;
						}
						else
						{
							removeMaxima.add(otherMaximum);
							removeI = i;
						}
					}
				}
			}
			
			if (isHighest && removeMaxima.size() > 0)
			{
				for (Iterator<DoGMaximum> k = removeMaxima.iterator(); k.hasNext();)
				{
					DoGMaximum otherMaximum = k.next();
					maxima[removeI].remove(otherMaximum);
				}
			}
			
			if ( isHighest )
				maxima[ (int)Math.round( x ) - min ].add( resultVoxel );
		}
		
		// copy into the new structure
		final ArrayList<DoGMaximum> localMaxima = new ArrayList<DoGMaximum>();
				
		for ( int i = 0; i < maxima.length; i++ )
			for( DoGMaximum dg : maxima[ i ])
				localMaxima.add( dg );
			
		return localMaxima;
	}

}
