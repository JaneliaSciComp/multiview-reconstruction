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
import ij.VirtualStack;
import ij.io.FileInfo;
import ij.plugin.FileInfoVirtualStack;
import ij.plugin.Raw;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.generic.sequence.ImgLoaderHint;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.SetupImgLoader;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converter;
import net.imglib2.converter.Converters;
import net.imglib2.converter.RealUnsignedShortConverter;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.datasetmanager.SimViewMetaData;
import net.preibisch.mvrecon.fiji.datasetmanager.SimViewMetaData.Pattern;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import util.ImgLib2Tools;

public class SimViewSetupImgLoader implements SetupImgLoader< UnsignedShortType >
{
	public static boolean virtual = true;

	final int setupId;
	final AbstractSequenceDescription<?, ?, ?> sequenceDescription;
	final File expDir;
	final String filePattern;
	final Pattern patternParser;
	final int type;
	final boolean littleEndian;

	public SimViewSetupImgLoader(
			final int setupId,
			final AbstractSequenceDescription<?, ?, ?> sequenceDescription,
			final File expDir,
			final String filePattern,
			final Pattern patternParser,
			final int type,
			final boolean littleEndian )
	{
		this.setupId = setupId;
		this.sequenceDescription = sequenceDescription;
		this.expDir = expDir;
		this.filePattern = filePattern;
		this.type = type;
		this.littleEndian = littleEndian;
		this.patternParser = patternParser;
	}
	
	@Override
	public RandomAccessibleInterval<FloatType> getFloatImage( final int timepointId, final boolean normalize, ImgLoaderHint... hints )
	{
		if ( normalize )
			return ImgLib2Tools.normalizeVirtualRAI( getImage( timepointId, hints ) );
		else
			return ImgLib2Tools.convertVirtualRAI( getImage( timepointId, hints ) );
	}

	@Override
	public RandomAccessibleInterval<UnsignedShortType> getImage( final int timepointId, ImgLoaderHint... hints )
	{
		final ViewId view = new ViewId( timepointId, setupId );
		final BasicViewDescription< ? > vd = sequenceDescription.getViewDescriptions().get( view );
		final BasicViewSetup vs = vd.getViewSetup();

		final TimePoint t = vd.getTimePoint();
		final Angle a = LegacyLightSheetZ1ImgLoader.getAngle( vd );
		final Channel c = LegacyLightSheetZ1ImgLoader.getChannel( vd );

		String[] code = a.getName().split( "-" );
		final int angle = Integer.parseInt( code[ 0 ] );
		final int cam = Integer.parseInt( code[ 1 ] );

		final File tpFolder = new File( expDir, SimViewMetaData.getTimepointString( t.getId() ) );
		final File angleFolder = new File( tpFolder, SimViewMetaData.getAngleString( angle ) );

		final ImagePlus imp;

		final FileInfo fi = new FileInfo();
		if ( type == 0 )
			fi.fileType = FileInfo.GRAY8;
		else if ( type == 1 )
			fi.fileType = FileInfo.GRAY16_SIGNED;
		else
			fi.fileType = FileInfo.GRAY16_UNSIGNED;
			
		fi.width = (int)vs.getSize().dimension( 0 );
		fi.height = (int)vs.getSize().dimension( 1 );
		fi.intelByteOrder = littleEndian;

		if ( patternParser.replacePlanes == null )
		{
			final File rawFile = new File( angleFolder,
					SimViewMetaData.getFileNamesFor(
							this.filePattern,
							patternParser.replaceTimepoints, patternParser.replaceChannels, patternParser.replaceCams, patternParser.replaceAngles, null,
							t.getId(), c.getId(), cam, angle, 0,
							patternParser.numDigitsTimepoints, patternParser.numDigitsChannels, patternParser.numDigitsCams, patternParser.numDigitsAngles, 0)[0] );

			fi.nImages = (int)vs.getSize().dimension( 2 );

			System.out.println( fi );

			if ( virtual )
			{
				IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Loading file " + rawFile.getAbsolutePath() + " (virtually)");

				final VirtualStack stack = new FileInfoVirtualStack( fi );
				imp = new ImagePlus(rawFile.getAbsolutePath(), stack);
			}
			else
			{
				IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Loading file " + rawFile.getAbsolutePath() );

				imp = Raw.open( rawFile.getAbsolutePath(), fi );
			}

			if ( imp == null )
				throw new RuntimeException( "Could not load viewId=" + view.getViewSetupId() + ", tpId=" + view.getTimePointId() + " file=" + rawFile.getAbsolutePath() );
		}
		else
		{
			fi.nImages = 1;
			System.out.println( fi );

			final ImageStack stack = new ImageStack( fi.width, fi.height );

			for ( int plane = 0; plane < vs.getSize().dimension( 2 ); ++plane )
			{
				final File rawFile = new File( angleFolder, 
						SimViewMetaData.getFileNamesFor(
								this.filePattern,
								patternParser.replaceTimepoints, patternParser.replaceChannels, patternParser.replaceCams, patternParser.replaceAngles, patternParser.replacePlanes,
								t.getId(), c.getId(), cam, angle, plane,
								patternParser.numDigitsTimepoints, patternParser.numDigitsChannels, patternParser.numDigitsCams, patternParser.numDigitsAngles, patternParser.numDigitsPlanes )[0] );

				final ImagePlus impPlane = Raw.open( rawFile.getAbsolutePath(), fi );

				if ( impPlane == null )
					throw new RuntimeException( "Could not load viewId=" + view.getViewSetupId() + ", tpId=" + view.getTimePointId() + " file=" + rawFile.getAbsolutePath() );

				stack.addSlice( impPlane.getProcessor() );
			}

			imp = new ImagePlus( "planes", stack );
		}

		if ( type == 0 )
		{
			RandomAccessibleInterval< UnsignedByteType > img = ImageJFunctions.wrap( imp );

			img = fixSizeIfNecessary( img, vs.getSize(), view );

			if ( img == null )
				throw new RuntimeException( "Could not load viewId=" + view.getViewSetupId() + ", tpId=" + view.getTimePointId() );

			final Converter<UnsignedByteType, UnsignedShortType> conv = new RealUnsignedShortConverter<UnsignedByteType>( 0, 255 );
			return Converters.convert( img, conv, new UnsignedShortType() );
		}
		else
		{
			RandomAccessibleInterval< UnsignedShortType > img = ImageJFunctions.wrap( imp );

			img = fixSizeIfNecessary( img, vs.getSize(), view );

			if ( img == null )
				throw new RuntimeException( "Could not load viewId=" + view.getViewSetupId() + ", tpId=" + view.getTimePointId() );

			return img;
		}
	}

	public static < T extends RealType < T > > RandomAccessibleInterval< T > fixSizeIfNecessary(
			RandomAccessibleInterval< T > img,
			final Dimensions dim,
			final ViewId viewId )
	{
		while ( img.numDimensions() < dim.numDimensions() )
		{
			IOFunctions.println( "BIG WARNING: dimensionality of ViewId " + Group.pvid( viewId ) + " is wrong, adjusting it manually." );
			IOFunctions.println( "BIG WARNING: dimensionality should be: " + dim.numDimensions() + ", but is: " + img.numDimensions() );

			final long[] max = new long[ img.numDimensions() + 1 ];

			for ( int d = 0; d < img.numDimensions(); ++d )
				max[ d ] = img.dimension( d ) - 1;

			max[ max.length - 1 ] = 0;

			img = Views.interval( Views.addDimension( img ), new long[ img.numDimensions() + 1 ], max );
		}

		while ( img.numDimensions() > dim.numDimensions())
		{
			throw new RuntimeException( "different dimensionalities not supported." );
		}

		boolean same = true;

		for ( int d = 0; d < img.numDimensions(); ++d )
			if ( img.dimension( d ) != dim.dimension( d ) )
				same = false;

		if ( !same )
		{
			IOFunctions.println( "BIG WARNING: image of ViewId " + Group.pvid( viewId ) + " has a different size, adjusting it manually." );
			IOFunctions.println( "BIG WARNING: dimensions should be: " + printDimensions( dim ) + ", but are: " + printDimensions( img ) );

			final long[] max = new long[ dim.numDimensions() ];

			for ( int d = 0; d < img.numDimensions(); ++d )
				max[ d ] = dim.dimension( d ) - 1;
			
			return Views.interval( Views.extendZero( img ), new long[ dim.numDimensions() ], max );
		}
		else
		{
			return img;
		}
	}

	public static String printDimensions( final Dimensions dim )
	{
		String out = "(Dimensions empty)";

		if ( dim == null || dim.numDimensions() == 0 )
			return out;

		out = "(" + dim.dimension( 0 );

		for ( int i = 1; i < dim.numDimensions(); i++ )
			out += ", " + dim.dimension( i );

		out += ")";

		return out;
	}

	@Override
	public UnsignedShortType getImageType() { return new UnsignedShortType(); }

	@Override
	public Dimensions getImageSize( final int timepointId )
	{
		final ViewId view = new ViewId( timepointId, setupId );
		final BasicViewDescription< ? > vd = sequenceDescription.getViewDescriptions().get( view );
		final BasicViewSetup vs = vd.getViewSetup();
		
		return vs.getSize();
	}

	@Override
	public VoxelDimensions getVoxelSize( final int timepointId )
	{
		final ViewId view = new ViewId( timepointId, setupId );
		final BasicViewDescription< ? > vd = sequenceDescription.getViewDescriptions().get( view );
		final BasicViewSetup vs = vd.getViewSetup();
		
		return vs.getVoxelSize();
	}
}
