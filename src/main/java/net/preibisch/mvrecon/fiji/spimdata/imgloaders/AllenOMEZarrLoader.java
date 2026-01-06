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

import java.io.Serializable;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;

import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import bdv.ViewerSetupImgLoader;
import bdv.img.n5.N5ImageLoader;
import bdv.img.n5.N5Properties;
import ij.ImageJ;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicMultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.NativeType;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ViewSetupExplorer;
import util.URITools;

public class AllenOMEZarrLoader extends N5ImageLoader
{
	private final AbstractSequenceDescription< ?, ?, ? > sequenceDescription;

	private final Map< ViewId, OMEZARREntry > viewIdToPath;

	/**
	 * either StorageFormat.ZARR (v3) or StorageFormat.ZARR2 (v2)
	 */
	private final StorageFormat format;

	public AllenOMEZarrLoader(
			final URI n5URI,
			final StorageFormat format,
			final AbstractSequenceDescription< ?, ?, ? > sequenceDescription,
			final Map< ViewId, OMEZARREntry > viewIdToPath )
	{
		super( URITools.instantiateN5Reader( format, n5URI ), n5URI, sequenceDescription );
		this.sequenceDescription = sequenceDescription;
		this.format = format;
		this.viewIdToPath = viewIdToPath;
	}

	public Map< ViewId, OMEZARREntry > getViewIdToPath()
	{
		return viewIdToPath;
	}

	@Override
	protected N5Properties createN5PropertiesInstance()
	{
		return new AllenOMEZarrProperties( sequenceDescription, viewIdToPath );
	}

	public StorageFormat getFormat() { return format; }

	public static class OMEZARREntry implements Serializable
	{
		private static final long serialVersionUID = -709235111470115483L;

		private final String path;
		private final int[] higherDimensionIndicies;

		/**
		 * @param path                    - path of the individual OME-ZARR
		 * @param higherDimensionIndicies - an index for extracting the correct 3d
		 *                                volume if dimensionality is greater than 3,
		 *                                e.g [1,2] could mean channel 1, timepoint 2 of
		 *                                a 5d OME-ZARR. It can be null if the volume is
		 *                                3d, or if it is 4d/5d and the size in both
		 *                                dimensions is 1
		 */
		public OMEZARREntry( final String path, final int[] higherDimensionIndicies )
		{
			this.path = path;
			this.higherDimensionIndicies = higherDimensionIndicies == null ? null : higherDimensionIndicies.clone();
		}

		public String getPath() { return path; }
		public int[] getHigherDimensionIndicies() { return higherDimensionIndicies == null ? null : higherDimensionIndicies.clone(); }

		public < T extends NativeType< T > > RandomAccessibleInterval< T > extract3DVolume( final RandomAccessibleInterval< T > omeZarrVolume )
		{
			if ( omeZarrVolume.numDimensions() <= 3 ) // 3d volume, return 3d volume
				return omeZarrVolume;

			if ( higherDimensionIndicies == null || higherDimensionIndicies.length == 0 )
			{
				if ( omeZarrVolume.numDimensions() == 4 && omeZarrVolume.dimension( 3 ) == 1 ) // 4d volume with size 1, return 3d volume
					return Views.hyperSlice( omeZarrVolume, 4, 0 );
				else if ( omeZarrVolume.numDimensions() == 5 && omeZarrVolume.dimension( 4 ) == 1 && omeZarrVolume.dimension( 3 ) == 1 )  // 5d volume with size 1 in c and t, return 3d volume
					return Views.hyperSlice( Views.hyperSlice( omeZarrVolume, 4, 0 ), 3, 0 );
				else
				{
					throw new RuntimeException( "Cannot handle OME-ZARR with dimensionality " + omeZarrVolume.numDimensions() + " without specifying which hyperslice to extract." );
				}
			}
			else
			{
				RandomAccessibleInterval< T > out = omeZarrVolume;

				for ( int d = 3 + higherDimensionIndicies.length - 1; d >= 3; --d )
					out = Views.hyperSlice( out, d, higherDimensionIndicies[ d - 3 ] );

				return out; //Views.hyperSlice( Views.hyperSlice( omeZarrVolume, 4, 0 ), 3, 0 );
			}
		}

		@Override
		public String toString()
		{
			return path + " " + (higherDimensionIndicies == null ? "[0,0]" : Arrays.toString( higherDimensionIndicies ) );
		}
	}

	@Override
	protected < T extends NativeType< T > > RandomAccessibleInterval< T > prepareCachedImage(
			final String datasetPath,
			final int setupId, final int timepointId, final int level,
			final CacheHints cacheHints,
			final T type )
	{
		return viewIdToPath.get( new ViewId(timepointId, setupId) ).extract3DVolume( super.prepareCachedImage( datasetPath, setupId, timepointId, level, cacheHints, type ) );
		//return Views.hyperSlice( Views.hyperSlice( super.prepareCachedImage( datasetPath, setupId, 0, level, cacheHints, type ), 4, 0 ), 3, 0);
		/*
		return super.prepareCachedImage( datasetPath, setupId, 0, level, cacheHints, type ).view()
				.slice( 4, 0 )
				.slice( 3, 0 );*/
	}

	public static void main( String[] args ) throws SpimDataException
	{
		URI xml = URITools.toURI( "/Users/preibischs/Documents/Janelia/Projects/BigStitcher/Allen/bigstitcher_708373/708373.split.xml" );
		//URI xml = URITools.toURI( "/Users/preibischs/Documents/Janelia/Projects/BigStitcher/Allen/bigstitcher_708373/708373.xml" );
		//URI xml = URITools.toURI( "s3://janelia-bigstitcher-spark/Stitching/dataset.xml" );
		//URI xml = URITools.toURI( "gs://janelia-spark-test/I2K-test/dataset.xml" );
		//URI xml = URITools.toURI( "/Users/preibischs/SparkTest/IP/dataset.xml" );

		XmlIoSpimData2 io = new XmlIoSpimData2();
		SpimData2 data = io.load( xml );

		System.out.println( "Setting cloud fetcher threads to: " + URITools.cloudThreads );
		URITools.setNumFetcherThreads( data.getSequenceDescription().getImgLoader(), URITools.cloudThreads );

		System.out.println( "Prefetching with threads: " + URITools.cloudThreads );
		URITools.prefetch( data.getSequenceDescription().getImgLoader(), URITools.cloudThreads );

		//imgL.setNumFetcherThreads( cloudThreads );
		ViewerSetupImgLoader sil = (ViewerSetupImgLoader)data.getSequenceDescription().getImgLoader().getSetupImgLoader( 0 );

		final int tp = data.getSequenceDescription().getTimePoints().getTimePointsOrdered().get( 0 ).getId();

		//RandomAccessibleInterval img = sil.getImage( tp, sil.getMipmapResolutions().length - 1 );
		RandomAccessibleInterval vol = sil.getVolatileImage( tp, ((BasicMultiResolutionSetupImgLoader)sil).getMipmapResolutions().length - 1 );

		new ImageJ();
		//ImageJFunctions.show( img );
		ImageJFunctions.show( vol );

		final ViewSetupExplorer< SpimData2 > explorer = new ViewSetupExplorer<>( data, xml, io );
		explorer.getFrame().toFront();
	}
}
