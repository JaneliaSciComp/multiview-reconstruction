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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders;

import java.io.Serializable;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;

import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.janelia.saalfeldlab.n5.universe.metadata.axes.AxisUtils;

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

	// kept locally (in addition to being handed to the N5ImageLoader superclass) so
	// prepareCachedImage() below can query axes metadata to correct the spatial axis
	// order of the volume built by the superclass, see correctSpatialAxesOrder()
	private final N5Reader n5Reader;

	private AllenOMEZarrProperties allenProperties;

	public AllenOMEZarrLoader(
			final URI n5URI,
			final StorageFormat format,
			final AbstractSequenceDescription< ?, ?, ? > sequenceDescription,
			final Map< ViewId, OMEZARREntry > viewIdToPath )
	{
		this( n5URI, format, sequenceDescription, viewIdToPath, URITools.instantiateN5Reader( format, n5URI ) );
	}

	private AllenOMEZarrLoader(
			final URI n5URI,
			final StorageFormat format,
			final AbstractSequenceDescription< ?, ?, ? > sequenceDescription,
			final Map< ViewId, OMEZARREntry > viewIdToPath,
			final N5Reader n5Reader )
	{
		super( n5Reader, n5URI, sequenceDescription );
		this.sequenceDescription = sequenceDescription;
		this.format = format;
		this.viewIdToPath = viewIdToPath;
		this.n5Reader = n5Reader;
	}

	public Map< ViewId, OMEZARREntry > getViewIdToPath()
	{
		return viewIdToPath;
	}

	@Override
	protected N5Properties createN5PropertiesInstance()
	{
		return allenProperties = new AllenOMEZarrProperties( sequenceDescription, viewIdToPath );
	}

	/**
	 * Permute the raw, n5-zarr-reported volume into canonical X,Y,Z,(C),(T) data order using the
	 * declared axes metadata (spatial axes by name, channel/time by type; see
	 * {@link AllenOMEZarrProperties#getRawAxisIndices}). OME-NGFF does not require the canonical
	 * order, so a producer may declare e.g. axes=[x,y,z] instead of [z,y,x] (looks transposed) or
	 * put channel/time off their usual positions (would swap c/t in the positional hyperslicing of
	 * {@link OMEZARREntry#extract3DVolume}); reordering here makes that downstream slicing correct
	 * by construction.
	 */
	private < T > RandomAccessibleInterval< T > reorderToCanonicalAxes( final RandomAccessibleInterval< T > vol, final int setupId, final int timepointId )
	{
		if ( allenProperties == null || n5Reader == null )
			return vol;

		final int[] raw = allenProperties.getRawAxisIndices( n5Reader, setupId, timepointId ); // { xRaw, yRaw, zRaw, cRaw, tRaw }
		if ( raw == null )
			return vol; // axes metadata missing/incomplete: assume the volume is already canonical

		// desired[p] = raw dim that belongs at output position p, in canonical order X,Y,Z,(C),(T),
		// keeping only declared axes (raw[i] >= 0). Bail unless it maps every dim distinctly (i.e.
		// the metadata agrees with the data), rather than applying a bogus permutation.
		final int n = vol.numDimensions();
		final int[] desired = new int[ n ];
		int m = 0;
		for ( final int r : raw )
			if ( r >= 0 && m < n )
				desired[ m++ ] = r;

		if ( m != n || !isPermutation( desired ) )
			return vol;

		// desired[] is destination->source; AxisUtils.permute() wants source->destination
		return AxisUtils.permute( vol, AxisUtils.invertPermutation( desired ) );
	}

	// true iff a[] is a permutation of 0..a.length-1
	private static boolean isPermutation( final int[] a )
	{
		final boolean[] seen = new boolean[ a.length ];
		for ( final int v : a )
		{
			if ( v < 0 || v >= a.length || seen[ v ] )
				return false;
			seen[ v ] = true;
		}
		return true;
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
				// Smart fallback for data with size 1 in higher dimensions
			if ( omeZarrVolume.numDimensions() == 4 && omeZarrVolume.dimension( 3 ) == 1 ) // 4d volume (xyzc) with size 1 in c, return 3d volume
					return Views.hyperSlice( omeZarrVolume, 3, 0 );
				else if ( omeZarrVolume.numDimensions() == 5 && omeZarrVolume.dimension( 4 ) == 1 && omeZarrVolume.dimension( 3 ) == 1 )  // 5d volume (xyzct) with size 1 in both c and t, return 3d volume
					return Views.hyperSlice( Views.hyperSlice( omeZarrVolume, 4, 0 ), 3, 0 );
				else
				{
					throw new RuntimeException( "Cannot handle OME-ZARR with dimensionality " + omeZarrVolume.numDimensions() +
					" without specifying which hyperslice to extract. Use higherDimensionIndicies parameter." );
				}
			}
			else
			{
				RandomAccessibleInterval< T > out = omeZarrVolume;

				for ( int d = 3 + higherDimensionIndicies.length - 1; d >= 3; --d )
					out = Views.hyperSlice( out, d, higherDimensionIndicies[ d - 3 ] );

				return out;
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
		final RandomAccessibleInterval< T > vol = reorderToCanonicalAxes(
				super.prepareCachedImage( datasetPath, setupId, timepointId, level, cacheHints, type ),
				setupId, timepointId );
		return viewIdToPath.get( new ViewId(timepointId, setupId) ).extract3DVolume( vol );
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
