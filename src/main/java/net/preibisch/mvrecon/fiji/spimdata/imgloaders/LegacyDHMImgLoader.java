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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import ij.ImagePlus;
import ij.io.Opener;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayCursor;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.legacy.io.IOFunctions;
import util.ImgLib2Tools;

public class LegacyDHMImgLoader extends AbstractImgLoader
{
	final File directory;
	final AbstractSequenceDescription< ?, ?, ? > sd;
	final List< String > timepoints;
	final List< String > zPlanes;
	final String stackDir;
	final String amplitudeDir;
	final String phaseDir;
	final String extension;
	final int ampChannelId;
	final int phaseChannelId;

	public LegacyDHMImgLoader(
			final File directory,
			final String stackDir,
			final String amplitudeDir,
			final String phaseDir,
			final List< String > timepoints,
			final List< String > zPlanes,
			final String extension,
			final int ampChannelId,
			final int phaseChannelId,
			final AbstractSequenceDescription< ?, ?, ? > sd )
	{
		this.directory = directory;
		this.stackDir = stackDir;
		this.amplitudeDir = amplitudeDir;
		this.phaseDir = phaseDir;
		this.timepoints = timepoints;
		this.zPlanes = zPlanes;
		this.extension = extension;
		this.ampChannelId = ampChannelId;
		this.phaseChannelId = phaseChannelId;
		this.sd = sd;
	}

	public String getStackDir() { return stackDir; }
	public String getAmplitudeDir() { return amplitudeDir; }
	public String getPhaseDir() { return phaseDir; }
	public List< String > getZPlanes() { return zPlanes; }
	public List< String > getTimepoints() { return timepoints; }
	public int getAmpChannelId() { return ampChannelId; }
	public int getPhaseChannelId() { return phaseChannelId; }
	public String getExt() { return extension; }

	@Override
	public RandomAccessibleInterval< FloatType > getFloatImage( final ViewId view, final boolean normalize )
	{
		if ( normalize )
			return ImgLib2Tools.normalizeVirtualRAI( getImage( view ) );
		else
			return ImgLib2Tools.convertVirtualRAI( getImage( view ) );
	}

	@Override
	public RandomAccessibleInterval< UnsignedShortType > getImage( final ViewId view )
	{
		final BasicViewDescription< ? > vd = sd.getViewDescriptions().get( view );
		final Dimensions d = vd.getViewSetup().getSize();
		final VoxelDimensions dv = vd.getViewSetup().getVoxelSize();

		final ArrayImg< UnsignedShortType, ? > img = ArrayImgs.unsignedShorts( d.dimension( 0 ), d.dimension( 1 ), d.dimension( 2 ) );

		final String ampOrPhaseDir;

		if ( vd.getViewSetup().getAttribute( Channel.class ).getId() == ampChannelId )
			ampOrPhaseDir = amplitudeDir;
		else if ( vd.getViewSetup().getAttribute( Channel.class ).getId() ==  phaseChannelId )
			ampOrPhaseDir = phaseDir;
		else
			throw new RuntimeException( "viewSetupId=" + view.getViewSetupId() + " is not Amplitude nor phase." );

		populateImage( img, directory, stackDir, ampOrPhaseDir, zPlanes, timepoints.get( view.getTimePointId() ), extension );

		updateMetaDataCache( view, (int)d.dimension( 0 ), (int)d.dimension( 1 ), (int)d.dimension( 2 ), dv.dimension( 0 ), dv.dimension( 1 ), dv.dimension( 2 ) );

		return img;
	}

	@Override
	protected void loadMetaData( ViewId view )
	{
		final BasicViewDescription< ? > vd = sd.getViewDescriptions().get( view );
		final Dimensions d = vd.getViewSetup().getSize();
		final VoxelDimensions dv = vd.getViewSetup().getVoxelSize();

		updateMetaDataCache( view, (int)d.dimension( 0 ), (int)d.dimension( 1 ), (int)d.dimension( 2 ), dv.dimension( 0 ), dv.dimension( 1 ), dv.dimension( 2 ) );
	}

	final public static < T extends RealType< T > & NativeType< T > > void populateImage(
			final ArrayImg< T, ? > img,
			final File directory,
			final String stackDir,
			final String ampOrPhaseDirectory,
			final List< String > zPlanes,
			final String timepoint,
			final String extension )
	{
		final Opener io = new Opener();
		final ArrayCursor< T > cursor = img.cursor();

		int countDroppedFrames = 0;
		ArrayList< Integer > slices = null;

		for ( int z = 0; z < zPlanes.size(); ++z )
		{
			final File imgF = new File( new File( new File( new File( directory.getAbsolutePath(), stackDir ), ampOrPhaseDirectory ), zPlanes.get( z ) ), timepoint + extension );
			final ImagePlus imp = io.openImage( imgF.getAbsolutePath() );

			if ( imp == null )
			{
				++countDroppedFrames;
				if ( slices == null )
					slices = new ArrayList< Integer >();
				slices.add( z );

				// leave the slice empty
				for ( int j = 0; j < img.dimension( 0 ) * img.dimension( 1 ); ++j )
					cursor.next();

				continue;
			}

			final Object o = imp.getProcessor().getPixels();

			if ( o instanceof byte[] )
				for ( final byte b : (byte[])o )
					cursor.next().setReal( UnsignedByteType.getUnsignedByte( b ) );
			else if ( o instanceof short[] )
				for ( final short s : (short[])o )
					cursor.next().setReal( UnsignedShortType.getUnsignedShort( s ) );
			else if ( o instanceof float[] )
				for ( final float s : (float[])o )
					cursor.next().setReal( s );
		}

		if ( countDroppedFrames > 0 )
		{
			IOFunctions.println(
					"(" + new Date( System.currentTimeMillis() ) + "): WARNING!!! " + countDroppedFrames +
					" DROPPED FRAME(s) in timepoint="  + timepoint + " channel=" + ampOrPhaseDirectory + " following slices:" );

			for ( final int z : slices )
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): sliceindex=" + z + ", slice=" + zPlanes.get( z ));
		}
	}
}
