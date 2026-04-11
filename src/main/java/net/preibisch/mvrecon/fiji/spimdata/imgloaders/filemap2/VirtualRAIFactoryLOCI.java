/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2026 Multiview Reconstruction developers.
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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders.filemap2;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static net.imglib2.cache.img.ReadOnlyCachedCellImgOptions.options;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.function.Supplier;

import loci.formats.AxisGuesser;
import loci.formats.FileStitcher;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.IFormatReader;
import loci.formats.Memoizer;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.img.ReadOnlyCachedCellImgFactory;
import net.imglib2.cache.img.optional.CacheOptions.CacheType;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.ShortType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedIntType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Cast;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.util.BioformatsReaderUtils;

class VirtualRAIFactoryLOCI
{
	static < T extends RealType< T > & NativeType< T > > T getType(
			final Supplier< IFormatReader > threadLocalReader,
			final File file,
			final int series )
	{
		try
		{
			final IFormatReader reader = threadLocalReader.get();
			setReaderFileAndSeriesIfNecessary( reader, file, series );

			final int pixelType = reader.getPixelType();
			switch ( pixelType )
			{
			case FormatTools.UINT8:
				return Cast.unchecked( new UnsignedByteType() );
			case FormatTools.UINT16:
				return Cast.unchecked( new UnsignedShortType() );
			case FormatTools.INT16:
				return Cast.unchecked( new ShortType() );
			case FormatTools.UINT32:
				return Cast.unchecked( new UnsignedIntType() );
			case FormatTools.FLOAT:
				return Cast.unchecked( new FloatType() );
			default:
				return null;
			}
		}
		catch ( IOException | FormatException e )
		{
			throw new RuntimeException( e );
		}
	}

	static < T extends RealType< T > & NativeType< T > > RandomAccessibleInterval< T > createVirtualCached(
			final Supplier<IFormatReader> threadLocalReader,
			final File file,
			final int series,
			final int channel,
			final int timepoint ) throws IncompatibleTypeException
	{
		final IFormatReader reader = threadLocalReader.get();
		try
		{
			setReaderFileAndSeriesIfNecessary( reader, file, series );
		}
		catch ( IOException | FormatException e )
		{
			throw new RuntimeException( e );
		}

		final long[] dims = { reader.getSizeX(), reader.getSizeY(), reader.getSizeZ() };
		final int[] cellDims = { ( int ) dims[ 0 ], ( int ) dims[ 1 ], 1 };
		// TODO: just use an ArrayImg/CellImg, no cache for re-saving
		final ReadOnlyCachedCellImgFactory factory = new ReadOnlyCachedCellImgFactory( options().cellDimensions( cellDims ).cacheType( CacheType.BOUNDED ).maxCacheSize( 1 ) );

		//System.out.println( "new cache ... " );

		final ByteOrder byteOrder = reader.isLittleEndian() ? LITTLE_ENDIAN : BIG_ENDIAN;

		final int pixelType = reader.getPixelType();
		switch ( pixelType )
		{
		case FormatTools.UINT8:
			return Cast.unchecked( factory.create( dims, new UnsignedByteType(),
					cell -> {
						final int z = ( int ) cell.min( 2 );
						final ByteBuffer bytes = readIntoBuffer( threadLocalReader.get(), file, series, channel, timepoint, z );
						bytes.position( 0 );
						bytes.get( ( byte[] ) cell.getStorageArray() );
					} ) );
		case FormatTools.UINT16:
			return Cast.unchecked( factory.create( dims, new UnsignedShortType(),
					cell -> {
						final int z = ( int ) cell.min( 2 );
						final ByteBuffer bytes = readIntoBuffer( threadLocalReader.get(), file, series, channel, timepoint, z );
						final ShortBuffer shorts = bytes.order( byteOrder ).asShortBuffer();
						shorts.position( 0 );
						shorts.get( ( short[] ) cell.getStorageArray() );
					} ) );
		case FormatTools.INT16:
			return Cast.unchecked( factory.create( dims, new ShortType(),
					cell -> {
						final int z = ( int ) cell.min( 2 );
						final ByteBuffer bytes = readIntoBuffer( threadLocalReader.get(), file, series, channel, timepoint, z );
						final ShortBuffer shorts = bytes.order( byteOrder ).asShortBuffer();
						shorts.position( 0 );
						shorts.get( ( short[] ) cell.getStorageArray() );
					} ) );
		case FormatTools.UINT32:
			return Cast.unchecked( factory.create( dims, new UnsignedIntType(),
					cell -> {
						final int z = ( int ) cell.min( 2 );
						final ByteBuffer bytes = readIntoBuffer( threadLocalReader.get(), file, series, channel, timepoint, z );
						final IntBuffer ints = bytes.order( byteOrder ).asIntBuffer();
						ints.position( 0 );
						ints.get( ( int[] ) cell.getStorageArray() );
					} ) );
		case FormatTools.FLOAT:
			return Cast.unchecked( factory.create( dims, new FloatType(),
					cell -> {
						final int z = ( int ) cell.min( 2 );
						final ByteBuffer bytes = readIntoBuffer( threadLocalReader.get(), file, series, channel, timepoint, z );
						final FloatBuffer floats = bytes.order( byteOrder ).asFloatBuffer();
						floats.position( 0 );
						floats.get( ( float[] ) cell.getStorageArray() );
					} ) );
		default:
			throw new IncompatibleTypeException( new VirtualRAIFactoryLOCI(), "cannot create virtual image for this pixel type: " + pixelType );
		}
	}

	private static ByteBuffer readIntoBuffer(
			final IFormatReader reader,
			final File file,
			final int series,
			final int channel,
			final int timepoint,
			final int z ) throws IOException, FormatException
	{
		setReaderFileAndSeriesIfNecessary( reader, file, series );
//		System.out.println( "reading z plane " + z + " from series " + series + " in file " + file.getAbsolutePath() );

		final int planeSize = BioformatsReaderUtils.getPlaneSizeInBytes( reader );
		final int size = planeSize * reader.getRGBChannelCount();
		final byte[] buffer = new byte[ size ];

		// FIX for XYZ <-> XYT mixup in rare cases
		final boolean flipTAndZ = !reader.isOrderCertain() && reader.getSizeZ() <= 1 && reader.getSizeT() > 1;
		final int actualTP = flipTAndZ ? z : timepoint;
		final int actualZ = flipTAndZ ? timepoint : z;

		final int rgbOffset;
		if ( reader.getRGBChannelCount() == reader.getSizeC() )
		{
			// the image is RGB -> we have to read bytes for all channels at once?
			reader.openBytes( reader.getIndex( actualZ, 0, actualTP ), buffer );
			rgbOffset = channel * planeSize;
		}
		else
		{
			// normal image -> read specified channel
			reader.openBytes( reader.getIndex( actualZ, channel, actualTP ), buffer );
			rgbOffset = 0;
		}

		return ByteBuffer.wrap( buffer, rgbOffset, planeSize );
	}

	/**
	 * ensure that the reader we have is set to the correct file and series
	 * @param reader the reader
	 * @param file the file to point the reader to
	 * @param series the series in the file to point the reader to
	 */
	private static void setReaderFileAndSeriesIfNecessary(final IFormatReader reader, final File file, final int series)
			throws IOException, FormatException
	{

		final boolean isFileStitcher = FileStitcher.class.isInstance( ( (Memoizer) reader).getReader() );
		boolean haveToReadFile = false;
		// did we setId at all?
		haveToReadFile |= (reader.getCurrentFile() == null);

		// we only check whether the set file is the correct one if we have a normal reader with normal filenames
		// FIXME: this would probably crash anyway (also for normal readers) as we setId while reader is not closed
		//    but the way we call it, we never have to re-setID for the reader
		// TODO: investigate
		if ( !isFileStitcher )
		{
			// is the reader set to the right file?
			// we check the canonical path of the file, otherwise something /./ would lead to setId being called
			// again even though the correct file is set already
			if ( !haveToReadFile )
				haveToReadFile = !( new File( reader.getCurrentFile() ).getCanonicalPath().equals( file.getCanonicalPath() ) );
		}

		if ( haveToReadFile )
		{
			reader.close(  );
			reader.setId( file.getAbsolutePath() );

			if ( isFileStitcher )
				( ( FileStitcher ) ( ( Memoizer ) reader ).getReader() ).setAxisTypes( new int[] { AxisGuesser.Z_AXIS } );
			reader.setSeries( series );
		}
		else
		{
			if ( reader.getSeries() != series )
				reader.setSeries( series );
		}
	}
}
