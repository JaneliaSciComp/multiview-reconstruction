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
package net.preibisch.mvrecon.process.downsampling;

import java.util.Date;
import java.util.List;

import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.LinAlgHelpers;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.downsampling.lazy.LazyDownsample2x;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;

public class DownsampleTools
{
	protected static final int[] ds = { 1, 2, 4, 8, 16, 32, 64, 128 };

	/**
	 * Opens the image at an appropriate resolution for the provided transformation and concatenates an extra transform 
	 * 
	 * @param imgLoader - the img loader
	 * @param viewId - the view id
	 * @param m - WILL BE MODIFIED IF OPENED DOWNSAMPLED
	 * @return - opened image
	 */
	@SuppressWarnings("rawtypes")
	public static RandomAccessibleInterval openDownsampled( final BasicImgLoader imgLoader, final ViewId viewId, final AffineTransform3D m )
	{
		return openDownsampled( imgLoader, viewId, m, null );
	}

	/**
	 * Opens the image at an appropriate resolution for the provided transformation and concatenates an extra transform 
	 * 
	 * @param imgLoader - the img loader
	 * @param viewId - the view id
	 * @param m - WILL BE MODIFIED IF OPENED DOWNSAMPLED
	 * @param usedDownsampleFactors - which downsample factors were used to open the image (important for weights etc)
	 * @return - opened image
	 */
	@SuppressWarnings("rawtypes")
	public static RandomAccessibleInterval openDownsampled( final BasicImgLoader imgLoader, final ViewId viewId, final AffineTransform3D m, final double[] usedDownsampleFactors )
	{
		final Pair< RandomAccessibleInterval, AffineTransform3D > opened = openDownsampled2( imgLoader, viewId, m, usedDownsampleFactors );

		// concatenate the downsampling transformation model to the affine transform
		if ( opened.getB() != null )
			m.concatenate( opened.getB() );

		return opened.getA();
	}
	
	/**
	 * Opens the image at an appropriate resolution for the provided transformation and concatenates an extra transform 
	 * 
	 * @param imgLoader - the img loader
	 * @param viewId - the view id
	 * @param m - NOT modified
	 * @param usedDownsampleFactors - which downsample factors were used to open the image (important for weights etc)
	 * @return - opened image and the affine transform that needs to be concatenated
	 */
	@SuppressWarnings("rawtypes")
	public static Pair< RandomAccessibleInterval, AffineTransform3D > openDownsampled2( final BasicImgLoader imgLoader, final ViewId viewId, final AffineTransform3D m, final double[] usedDownsampleFactors )
	{
		// have to go from input to output
		// https://github.com/bigdataviewer/bigdataviewer-core/blob/master/src/main/java/bdv/util/MipmapTransforms.java

		// pre-init downsample factors
		if ( usedDownsampleFactors != null )
			for ( int d = 0; d < usedDownsampleFactors.length; ++d )
				usedDownsampleFactors[ d ] = 1.0;

		if ( MultiResolutionImgLoader.class.isInstance( imgLoader ) )
		{
			final MultiResolutionImgLoader mrImgLoader = ( MultiResolutionImgLoader )imgLoader;
			final double[][] mipmapResolutions = mrImgLoader.getSetupImgLoader( viewId.getViewSetupId() ).getMipmapResolutions();

			// best possible step size in the output image when using original data
			final double[] sizeMaxResolution = getStepSize( m );

			//System.out.println( Util.printCoordinates( sizeMaxResolution ) );
			float acceptedError = 0.02f;

			// assuming that this is the best one
			int bestLevel = 0;
			double bestScaling = 0;

			// find the best level
			for ( int level = 0; level < mipmapResolutions.length; ++level )
			{
				final double[] factors = mipmapResolutions[ level ];

				final AffineTransform3D s = new AffineTransform3D();
				s.set(
					factors[ 0 ], 0.0, 0.0, 0.0,
					0.0, factors[ 1 ], 0.0, 0.0,
					0.0, 0.0, factors[ 2 ], 0.0 );
	
				//System.out.println( "testing scale: " + s );
	
				AffineTransform3D model = m.copy();
				model.concatenate( s );

				final double[] size = getStepSize( model );

				boolean isValid = true;
				
				for ( int d = 0; d < 3; ++d )
					if ( !( size[ d ] < 1.0 + acceptedError || Util.isApproxEqual( size[ d ], sizeMaxResolution[ d ], acceptedError ) ) )
						isValid = false;

				if ( isValid )
				{
					final double totalScale = factors[ 0 ] * factors[ 1 ] * factors[ 2 ];
					
					if ( totalScale > bestScaling )
					{
						bestScaling = totalScale;
						bestLevel = level;
					}
				}
				//System.out.println( Util.printCoordinates( size ) + " valid: " + isValid + " bestScaling: " + bestScaling  );
			}

			// now done in the more specific code above
			// concatenate the downsampling transformation model to the affine transform
			// m.concatenate( mrImgLoader.getSetupImgLoader( viewId.getViewSetupId() ).getMipmapTransforms()[ bestLevel ] );

			//System.out.println( "Choosing resolution level s" + bestLevel + ": (" + mipmapResolutions[ bestLevel ][ 0 ] + " x " + mipmapResolutions[ bestLevel ][ 1 ] + " x " + mipmapResolutions[ bestLevel ][ 2 ] + ")" );

			if ( usedDownsampleFactors != null && usedDownsampleFactors.length >= mipmapResolutions[ bestLevel ].length )
				for ( int d = 0; d < mipmapResolutions[ bestLevel ].length; ++d )
					usedDownsampleFactors[ d ] = mipmapResolutions[ bestLevel ][ d ];

			/*
			IOFunctions.println(
					"(" + new Date(System.currentTimeMillis()) + "): "
					+ "Requesting Img from ImgLoader (tp=" + viewId.getTimePointId() + ", setup=" + viewId.getViewSetupId() + "), using level=" + bestLevel + ", [" + mipmapResolutions[ bestLevel ][ 0 ] + " x " + mipmapResolutions[ bestLevel ][ 1 ] + " x " + mipmapResolutions[ bestLevel ][ 2 ] + "]" );
			*/

			return new ValuePair<>(
					mrImgLoader.getSetupImgLoader( viewId.getViewSetupId() ).getImage( viewId.getTimePointId(), bestLevel ),
					mrImgLoader.getSetupImgLoader( viewId.getViewSetupId() ).getMipmapTransforms()[ bestLevel ] );
		}
		else
		{
			/*
			IOFunctions.println(
					"(" + new Date(System.currentTimeMillis()) + "): "
					+ "Requesting Img from ImgLoader (tp=" + viewId.getTimePointId() + ", setup=" + viewId.getViewSetupId() + "), using level=" + 0 + ", [1 x 1 x 1]" );
			*/

			return new ValuePair<>( imgLoader.getSetupImgLoader( viewId.getViewSetupId() ).getImage( viewId.getTimePointId() ), null );
		}
	}

	private static double[] getStepSize( final AffineTransform3D model )
	{
		final double[] size = new double[ 3 ];
		final double[] tmp = new double[ 3 ];
		for ( int d = 0; d < 3; ++d )
		{
			for ( int i = 0; i < 3; ++i )
				tmp[ i ] = model.get( i, d );
			size[ d ] = LinAlgHelpers.length( tmp );
		}
		return size;
	}

	private static double length( final double[] a, final double[] b )
	{
		double l = 0;

		for ( int j = 0; j < a.length; ++j )
			l += ( a[ j ] - b[ j ] ) * ( a[ j ] - b[ j ] );

		return Math.sqrt( l );
	}

	/**
	 * For double-based downsampling Double.NaN means no downsampling to avoid unnecessary computations, here we return a String for that number that
	 * says "None" if it is Double.NaN
	 * 
	 * @param downsampling - the downsampling, Double.NaN means 1.0
	 * @return - a String describing it
	 */
	public static String printDownsampling( final double downsampling )
	{
		if ( Double.isNaN( downsampling ) )
			return "None";
		else
			return Double.toString( downsampling );
	}

	public static String[] availableDownsamplings( final AbstractSpimData< ? > data, final ViewId viewId )
	{
		final String[] dsStrings;

		if (MultiResolutionImgLoader.class.isInstance( data.getSequenceDescription().getImgLoader() ))
		{
			final MultiResolutionImgLoader mrImgLoader = (MultiResolutionImgLoader) data.getSequenceDescription().getImgLoader();
			final double[][] mipmapResolutions = mrImgLoader.getSetupImgLoader( viewId.getViewSetupId()).getMipmapResolutions();
			dsStrings = new String[mipmapResolutions.length];
			
			for (int i = 0; i<mipmapResolutions.length; i++)
			{
				final String fx = ((Long)Math.round( mipmapResolutions[i][0] )).toString(); 
				final String fy = ((Long)Math.round( mipmapResolutions[i][1] )).toString(); 
				final String fz = ((Long)Math.round( mipmapResolutions[i][2] )).toString();
				final String dsString = String.join( ", ", fx, fy, fz );
				dsStrings[i] = dsString;
			}
		}
		else
		{
			dsStrings = new String[]{ "1, 1, 1" };
		}

		return dsStrings;
	}

	public static long[] parseDownsampleChoice( final String dsChoice )
	{
		final long[] downSamplingFactors = new long[ 3 ];
		final String[] choiceSplit = dsChoice.split( ", " );
		downSamplingFactors[0] = Long.parseLong( choiceSplit[0] );
		downSamplingFactors[1] = Long.parseLong( choiceSplit[1] );
		downSamplingFactors[2] = Long.parseLong( choiceSplit[2] );
		
		return downSamplingFactors;
	}

	public static void correctForDownsampling( final List< InterestPoint > ips, final AffineTransform3D t )
	{
		IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): Correcting coordinates for downsampling using AffineTransform: " + t );

		if ( ips == null || ips.size() == 0 )
		{
			IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): WARNING: List is empty." );
			return;
		}

		final double[] tmp = new double[ ips.get( 0 ).getL().length ];

		for ( final InterestPoint ip : ips )
		{
			t.apply( ip.getL(), tmp );

			ip.getL()[ 0 ] = tmp[ 0 ];
			ip.getL()[ 1 ] = tmp[ 1 ];
			ip.getL()[ 2 ] = tmp[ 2 ];

			t.apply( ip.getW(), tmp );

			ip.getW()[ 0 ] = tmp[ 0 ];
			ip.getW()[ 1 ] = tmp[ 1 ];
			ip.getW()[ 2 ] = tmp[ 2 ];
		}
	}

	public static int downsampleFactor( final int downsampleXY, final int downsampleZ, final VoxelDimensions v )
	{
		final double calXY = Math.min( v.dimension( 0 ), v.dimension( 1 ) );
		final double calZ = v.dimension( 2 ) * downsampleZ;
		final double log2ratio = Math.log( calZ / calXY ) / Math.log( 2 );

		final double exp2;

		if ( downsampleXY == 0 )
			exp2 = Math.pow( 2, Math.floor( log2ratio ) );
		else
			exp2 = Math.pow( 2, Math.ceil( log2ratio ) );

		// in some cases of extreme calibrations exp2 can be smaller than 0.5, which would case exceptions downstream
		return Math.max(1, (int)Math.round( exp2 ) );
	}

	@SuppressWarnings("rawtypes")
	public static RandomAccessibleInterval openAtLowestLevel(
			final ImgLoader imgLoader,
			final ViewId view )
	{
		return openAtLowestLevel( imgLoader, view, null );
	}

	@SuppressWarnings("rawtypes")
	public static RandomAccessibleInterval openAtLowestLevel(
			final ImgLoader imgLoader,
			final ViewId view,
			final AffineTransform3D t )
	{
		final RandomAccessibleInterval input;

		if ( MultiResolutionImgLoader.class.isInstance( imgLoader ) )
		{
			final MultiResolutionImgLoader mrImgLoader = ( MultiResolutionImgLoader ) imgLoader;
			final double[][] mipmapResolutions = mrImgLoader.getSetupImgLoader( view.getViewSetupId() ).getMipmapResolutions();
			final int bestLevel = findLowestResolutionLevel( mrImgLoader, view );

			IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Loading level " + Util.printCoordinates( mipmapResolutions[ bestLevel ] ) );

			input = mrImgLoader.getSetupImgLoader( view.getViewSetupId() ).getImage( view.getTimePointId(), bestLevel );
			if ( t != null )
				t.set( mrImgLoader.getSetupImgLoader( view.getViewSetupId() ).getMipmapTransforms()[ bestLevel ] );
		}
		else
		{
			IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Loading full-resolution images :( " );

			input = imgLoader.getSetupImgLoader( view.getViewSetupId() ).getImage( view.getTimePointId() );
			if ( t != null )
				t.identity();
		}

		return input;
	}

	public static int findLowestResolutionLevel( final MultiResolutionImgLoader mrImgLoader, final ViewId view )
	{
		final double[][] mipmapResolutions = mrImgLoader.getSetupImgLoader( view.getViewSetupId() ).getMipmapResolutions();

		int maxMul = Integer.MIN_VALUE;
		int bestLevel = -1;

		for ( int i = 0; i < mipmapResolutions.length; ++i )
		{
			int mul = 1;

			for ( int d = 0; d < mipmapResolutions[ i ].length; ++d )
				mul *= mipmapResolutions[ i ][ d ];

			if ( mul > maxMul )
			{
				maxMul = mul;
				bestLevel = i;
			}
		}

		return bestLevel;
	}

	/**
	 * Returns the mipmap transform if you were to open that ViewId with this ImgLoader at the specified downsample factors
	 * 
	 * @param imgLoader the imgloader
	 * @param vd the view id
	 * @param downsampleFactors - specify which downsampling in each dimension (e.g. 1,2,4,8 )
	 * @return the mipmap transform
	 */
	public static AffineTransform3D getMipMapTransform(
			final BasicImgLoader imgLoader,
			final ViewId vd,
			final long[] downsampleFactors )
	{
		final AffineTransform3D mmt = new AffineTransform3D();

		openAndDownsample( imgLoader, vd, mmt, downsampleFactors, true, false );

		return mmt;
	}

	/**
	 * Opens the image at a specified downsampling level (e.g. 4,4,1). It finds the closest available mipmap level and then downsamples
	 * to reach the target level
	 *
	 * @param imgLoader the imgloader
	 * @param vd the view id
	 * @param downsampleFactors - specify which downsampling in each dimension (e.g. 1,2,4,8 )
	 * @param virtualDownsampling - use virtual downsampling if the requested downsamplefactors are not precomputed
	 * @return opened image and the mipmap transform
	 */
	@SuppressWarnings({ "rawtypes" })
	public static Pair<RandomAccessibleInterval, AffineTransform3D> openAndDownsample(
			final BasicImgLoader imgLoader,
			final ViewId vd,
			final long[] downsampleFactors,
			final boolean virtualDownsampling )
	{
		final AffineTransform3D mipMapTransform = new AffineTransform3D();

		final RandomAccessibleInterval img = openAndDownsample(imgLoader, vd, mipMapTransform, downsampleFactors, false, virtualDownsampling );

		return new ValuePair<RandomAccessibleInterval, AffineTransform3D>( img, mipMapTransform );
	}

	/**
	 * Opens the image at a specified downsampling level (e.g. 4,4,1). It finds the closest available mipmap level and then downsamples
	 * to reach the target level
	 *
	 * @param imgLoader the imgloader
	 * @param vd the view id
	 * @param mipMapTransform - will be filled if downsampling is performed, otherwise identity transform
	 * @param downsampleFactors - specify which downsampling in each dimension (e.g. 1,2,4,8 )
	 * @param virtualDownsampling - use virtual downsampling if the requested downsamplefactors are not precomputed
	 * @param transformOnly - if true does not open any images but only provides the mipMapTransform (METHOD WILL RETURN NULL!)
	 * @return opened image
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static RandomAccessibleInterval openAndDownsample(
			final BasicImgLoader imgLoader,
			final ViewId vd,
			final AffineTransform3D mipMapTransform,
			long[] downsampleFactors,
			final boolean transformOnly, // only for ImgLib1 legacy code
			final boolean virtualDownsampling )
	{
		long dsx = downsampleFactors[0];
		long dsy = downsampleFactors[1];
		long dsz = (downsampleFactors.length > 2) ? downsampleFactors[ 2 ] : 1;

		RandomAccessibleInterval input = null;

		if ( ( dsx > 1 || dsy > 1 || dsz > 1 ) && MultiResolutionImgLoader.class.isInstance( imgLoader ) )
		{
			MultiResolutionImgLoader mrImgLoader = ( MultiResolutionImgLoader ) imgLoader;

			double[][] mipmapResolutions = mrImgLoader.getSetupImgLoader( vd.getViewSetupId() ).getMipmapResolutions();

			int bestLevel = 0;
			for ( int level = 0; level < mipmapResolutions.length; ++level )
			{
				double[] factors = mipmapResolutions[ level ];
				
				// this fails if factors are not ints
				final int fx = (int)Math.round( factors[ 0 ] );
				final int fy = (int)Math.round( factors[ 1 ] );
				final int fz = (int)Math.round( factors[ 2 ] );
				
				if ( fx <= dsx && fy <= dsy && fz <= dsz && contains( fx, ds ) && contains( fy, ds ) && contains( fz, ds ) )
					bestLevel = level;
			}

			final int fx = (int)Math.round( mipmapResolutions[ bestLevel ][ 0 ] );
			final int fy = (int)Math.round( mipmapResolutions[ bestLevel ][ 1 ] );
			final int fz = (int)Math.round( mipmapResolutions[ bestLevel ][ 2 ] );

			if ( mipMapTransform != null )
				mipMapTransform.set( mrImgLoader.getSetupImgLoader( vd.getViewSetupId() ).getMipmapTransforms()[ bestLevel ] );

			dsx /= fx;
			dsy /= fy;
			dsz /= fz;

			if ( !transformOnly )
			{
				input = mrImgLoader.getSetupImgLoader( vd.getViewSetupId() ).getImage( vd.getTimePointId(), bestLevel );
			}
		}
		else
		{
			if ( !transformOnly )
			{
				input = imgLoader.getSetupImgLoader( vd.getViewSetupId() ).getImage( vd.getTimePointId() );
			}

			if ( mipMapTransform != null )
				mipMapTransform.identity();
		}

		if ( mipMapTransform != null )
		{
			// the additional downsampling (performed below)
			final AffineTransform3D additonalDS = new AffineTransform3D();
			additonalDS.set( dsx, 0.0, 0.0, 0.0, 0.0, dsy, 0.0, 0.0, 0.0, 0.0, dsz, 0.0 );

			// we need to concatenate since when correcting for the downsampling we first multiply by whatever
			// the manual downsampling did, and just then by the scaling+offset of the HDF5
			//
			// Here is an example of what happens (note that the 0.5 pixel shift is not changed)
			// HDF5 MipMap Transform   (2.0, 0.0, 0.0, 0.5, 0.0, 2.0, 0.0, 0.5, 0.0, 0.0, 2.0, 0.5)
			// Additional Downsampling (4.0, 0.0, 0.0, 0.0, 0.0, 4.0, 0.0, 0.0, 0.0, 0.0, 2.0, 0.0)
			// Resulting model         (8.0, 0.0, 0.0, 0.5, 0.0, 8.0, 0.0, 0.5, 0.0, 0.0, 4.0, 0.5)
			mipMapTransform.concatenate( additonalDS );
		}

		if ( !transformOnly )
		{
			if ( virtualDownsampling )
			{
				for ( ;dsx > 1; dsx /= 2 )
					input = LazyDownsample2x.init( Views.extendBorder( input ), input, new FloatType(), DoGImgLib2.blockSize, 0 );

				for ( ;dsy > 1; dsy /= 2 )
					input = LazyDownsample2x.init( Views.extendBorder( input ), input, new FloatType(), DoGImgLib2.blockSize, 1 );

				for ( ;dsz > 1; dsz /= 2 )
					input = LazyDownsample2x.init( Views.extendBorder( input ), input, new FloatType(), DoGImgLib2.blockSize, 2 );
			}
			else
			{
				// note: every pixel is read exactly once, therefore caching the virtual input would not give any advantages
				for ( ;dsx > 1; dsx /= 2 )
					input = Downsample.simple2x( input, new boolean[]{ true, false, false } );

				for ( ;dsy > 1; dsy /= 2 )
					input = Downsample.simple2x( input, new boolean[]{ false, true, false } );

				for ( ;dsz > 1; dsz /= 2 )
					input = Downsample.simple2x( input, new boolean[]{ false, false, true } );
			}
		}

		return input;
	}

	private static final boolean contains( final int i, final int[] values )
	{
		for ( final int j : values )
			if ( i == j )
				return true;

		return false;
	}
}
