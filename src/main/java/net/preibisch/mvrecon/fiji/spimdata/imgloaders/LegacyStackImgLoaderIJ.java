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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders;

import java.io.File;
import java.util.Date;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.io.Opener;
import ij.process.ImageProcessor;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.RealUnsignedShortConverter;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.planar.PlanarImg;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.datasetmanager.StackListImageJ;
import net.preibisch.mvrecon.fiji.plugin.resave.Generic_Resave_HDF5;
import net.preibisch.mvrecon.fiji.plugin.resave.Generic_Resave_HDF5.ParametersResaveHDF5;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import util.ImgLib2Tools;

public class LegacyStackImgLoaderIJ extends LegacyStackImgLoader
{
	ParametersResaveHDF5 params = null;

	public LegacyStackImgLoaderIJ(
			final File path, final String fileNamePattern,
			final int layoutTP, final int layoutChannels, final int layoutIllum, final int layoutAngles, final int layoutTiles,
			final AbstractSequenceDescription< ?, ?, ? > sequenceDescription )
	{
		super( path, fileNamePattern, layoutTP, layoutChannels, layoutIllum, layoutAngles, layoutTiles, sequenceDescription );
	}

	public static ImagePlus open( File file )
	{
		final ImagePlus imp = new Opener().openImage( file.getAbsolutePath() );

		if ( imp == null )
		{
			IOFunctions.println( "Could not open file with ImageJ TIFF reader: '" + file.getAbsolutePath() + "'" );
			return null;
		}

		return imp;
	}

	/**
	 * Get {@link FloatType} image normalized to the range [0,1].
	 *
	 * @param view
	 *            timepoint and setup for which to retrieve the image.
	 * @param normalize
	 * 			  if the image should be normalized to [0,1] or not
	 * @return {@link FloatType} image normalized to range [0,1]
	 */
	@Override
	public RandomAccessibleInterval< FloatType > getFloatImage( final ViewId view, final boolean normalize )
	{
		if ( normalize )
			return ImgLib2Tools.normalizeVirtualRAI( getImage( view ) );
		else
			return ImgLib2Tools.convertVirtualRAI( getImage( view ) );
	}

	/**
	 * Get {@link UnsignedShortType} un-normalized image.
	 *
	 * @param view
	 *            timepoint and setup for which to retrieve the image.
	 * @return {@link UnsignedShortType} image.
	 */
	@Override
	public RandomAccessibleInterval< UnsignedShortType > getImage( final ViewId view )
	{
		final File file = getFile( view );

		if ( file == null )
			throw new RuntimeException( "Could not find file '" + file + "'." );

		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Loading '" + file + "' ..." );

		final ImagePlus imp = open( file );

		if ( imp == null )
			throw new RuntimeException( "Could not load '" + file + "'." );

		final boolean is32bit;
		final RealUnsignedShortConverter< FloatType > converter;

		if ( imp.getType() == ImagePlus.GRAY32 )
		{
			is32bit = true;
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Image '" + file + "' is 32bit, opening as 16bit with scaling" );

			if ( params == null )
				params = queryParameters();

			if ( params == null )
				return null;

			final double[] minmax = updateAndGetMinMax( ImageJFunctions.wrapFloat( imp ), params );
			converter = new RealUnsignedShortConverter< FloatType >( minmax[ 0 ], minmax[ 1 ] );
		}
		else
		{
			is32bit = false;
			converter = null;
		}

		final long[] dim = new long[]{ imp.getWidth(), imp.getHeight(), imp.getStack().getSize() };
		final Img< UnsignedShortType > img = instantiateImg( dim, new UnsignedShortType() );

		if ( img == null )
			throw new RuntimeException( "Could not instantiate " + getImgFactory().getClass().getSimpleName() + " for '" + file + "', most likely out of memory." );
		else
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Opened '" + file + "' [" + dim[ 0 ] + "x" + dim[ 1 ] + "x" + dim[ 2 ] + " image=" + img.getClass().getSimpleName() + "<UnsignedShortType>]" );

		final ImageStack stack = imp.getStack();
		final int sizeZ = imp.getStack().getSize();

		if ( img instanceof ArrayImg || img instanceof PlanarImg )
		{
			final Cursor< UnsignedShortType > cursor = img.cursor();
			final int sizeXY = imp.getWidth() * imp.getHeight();

			for ( int z = 0; z < sizeZ; ++z )
			{
				final ImageProcessor ip = stack.getProcessor( z + 1 );

				if( is32bit )
				{
					final FloatType input = new FloatType();
					final UnsignedShortType output = new UnsignedShortType();

					for ( int i = 0; i < sizeXY; ++i )
					{
						input.set( ip.getf( i ) );
						converter.convert( input, output );
						cursor.next().set( output.get() );
					}
				}
				else
				{
					for ( int i = 0; i < sizeXY; ++i )
						cursor.next().set( ip.get( i ) );
				}
			}
		}
		else
		{
			final int width = imp.getWidth();

			for ( int z = 0; z < sizeZ; ++z )
			{
				final Cursor< UnsignedShortType > cursor = Views.iterable( Views.hyperSlice( img, 2, z ) ).localizingCursor();
				final ImageProcessor ip = stack.getProcessor( z + 1 );

				if ( is32bit )
				{
					final FloatType input = new FloatType();
					final UnsignedShortType output = new UnsignedShortType();

					while ( cursor.hasNext() )
					{
						cursor.fwd();
						input.set( ip.getf( cursor.getIntPosition( 0 ) + cursor.getIntPosition( 1 ) * width ) );
						converter.convert( input, output );
						cursor.get().set( output );
					}
				}
				else
				{
					while ( cursor.hasNext() )
					{
						cursor.fwd();
						cursor.get().set( ip.get( cursor.getIntPosition( 0 ) + cursor.getIntPosition( 1 ) * width ) );
					}
				}
			}
		}

		// update the MetaDataCache of the AbstractImgLoader
		// this does not update the XML ViewSetup but has to be called explicitly before saving
		updateMetaDataCache( view, imp.getWidth(), imp.getHeight(), imp.getStack().getSize(),
				imp.getCalibration().pixelWidth, imp.getCalibration().pixelHeight, imp.getCalibration().pixelDepth );

		imp.close();

		return img;
	}

	@Override
	protected void loadMetaData( final ViewId view )
	{
		final File file = getFile( view );
		final ImagePlus imp = open( file );

		if ( imp == null )
			throw new RuntimeException( "Could not load '" + file + "'." );

		// update the MetaDataCache of the AbstractImgLoader
		// this does not update the XML ViewSetup but has to be called explicitly before saving
		updateMetaDataCache( view, imp.getWidth(), imp.getHeight(), imp.getStack().getSize(),
				imp.getCalibration().pixelWidth, imp.getCalibration().pixelHeight, imp.getCalibration().pixelDepth );

		imp.close();
	}

	@Override
	public String toString()
	{
		return new StackListImageJ().getTitle() + ", ImgFactory=" + getImgFactory().getClass().getSimpleName();
	}

	protected static ParametersResaveHDF5 queryParameters()
	{
		final GenericDialog gd = new GenericDialog( "Opening 32bit TIFF as 16bit" );

		gd.addMessage( "You are trying to open 32-bit images as 16-bit (resaving as HDF5 maybe). Please define how to convert to 16bit.", GUIHelper.mediumstatusfont );
		gd.addMessage( "Note: This dialog will only show up once for the first image.", GUIHelper.mediumstatusfont );
		gd.addChoice( "Convert_32bit", Generic_Resave_HDF5.convertChoices, Generic_Resave_HDF5.convertChoices[ Generic_Resave_HDF5.defaultConvertChoice ] );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return null;

		Generic_Resave_HDF5.defaultConvertChoice = gd.getNextChoiceIndex();

		if ( Generic_Resave_HDF5.defaultConvertChoice == 2 )
		{
			if ( Double.isNaN( Generic_Resave_HDF5.defaultMin ) )
				Generic_Resave_HDF5.defaultMin = 0;

			if ( Double.isNaN( Generic_Resave_HDF5.defaultMax ) )
				Generic_Resave_HDF5.defaultMax = 5;

			final GenericDialog gdMinMax = new GenericDialog( "Define min/max" );

			gdMinMax.addNumericField( "Min_Intensity_for_16bit_conversion", Generic_Resave_HDF5.defaultMin, 1 );
			gdMinMax.addNumericField( "Max_Intensity_for_16bit_conversion", Generic_Resave_HDF5.defaultMax, 1 );
			gdMinMax.addMessage( "Note: the typical range for multiview deconvolution is [0 ... 10] & for fusion the same as the input intensities., ",GUIHelper.mediumstatusfont );

			gdMinMax.showDialog();

			if ( gdMinMax.wasCanceled() )
				return null;

			Generic_Resave_HDF5.defaultMin = gdMinMax.getNextNumber();
			Generic_Resave_HDF5.defaultMax = gdMinMax.getNextNumber();
		}
		else
		{
			Generic_Resave_HDF5.defaultMin = Generic_Resave_HDF5.defaultMax = Double.NaN;
		}

		return new ParametersResaveHDF5( false, null, null, null, null, false, false, 0, 0, false, 0, Generic_Resave_HDF5.defaultConvertChoice, Generic_Resave_HDF5.defaultMin, Generic_Resave_HDF5.defaultMax );
	}

	public static < T extends RealType< T > > double[] updateAndGetMinMax( final RandomAccessibleInterval< T > img, final ParametersResaveHDF5 params )
	{
		double min, max;

		if ( params == null || params.getConvertChoice() == 0 || Double.isNaN( params.getMin() ) || Double.isNaN( params.getMin() ) )
		{
			final float[] minmax = FusionTools.minMax( img );
			min = minmax[ 0 ];
			max = minmax[ 1 ];

			min = Math.max( 0, min - ((min+max)/2.0) * 0.1 );
			max = max + ((min+max)/2.0) * 0.1;

			if ( params != null )
			{
				params.setMin( min );
				params.setMax( max );
			}
		}
		else
		{
			min = params.getMin();
			max = params.getMax();
		}

		IOFunctions.println( "Min intensity for 16bit conversion: " + min );
		IOFunctions.println( "Max intensity for 16bit conversion: " + max );

		return new double[]{ min, max };
	}

}
