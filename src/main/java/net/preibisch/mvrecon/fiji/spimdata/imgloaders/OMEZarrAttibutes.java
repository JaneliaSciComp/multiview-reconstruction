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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.function.Function;

import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.janelia.saalfeldlab.n5.universe.metadata.axes.Axis;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.OmeNgffMultiScaleMetadata;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.OmeNgffMultiScaleMetadata.OmeNgffDataset;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.OmeNgffMultiScaleMetadata.OmeNgffDownsamplingMetadata;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.coordinateTransformations.CoordinateTransformation;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.coordinateTransformations.ScaleCoordinateTransformation;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.coordinateTransformations.TranslationCoordinateTransformation;

import net.imglib2.realtransform.AffineTransform3D;
import util.URITools;

public class OMEZarrAttibutes
{
	/*
	 * 
	 * @param n - num dimensions, 3-5 makes sense here (zyx to tczyx; TODO: which order?)
	 * @param numResolutionLevels - number of multiresolution levels (e.g. s0, s1, s2 would be 3), always includes full res
	 * @return
	 */
	public static OmeNgffMultiScaleMetadata[] createOMEZarrMetadata(
			final int n,
			final String name, // I also saw "/"
			final double[] resolutionS0,
			final String unitXYZ, // e.g micrometer
			final int numResolutionLevels,
			final Function<Integer, String> levelToName,
			final Function<Integer, AffineTransform3D > levelToMipmapTransform )
	{
		final OmeNgffMultiScaleMetadata[] meta = new OmeNgffMultiScaleMetadata[ 1 ];

		// dataset name and co
		final String path = "";
		final String type = null;

		// axis descriptions
		// TODO: seem to be TCZYX order
		final Axis[] axes = new Axis[ n ];

		int index = 0;

		if ( n >= 5 )
			axes[ index++ ] = new Axis( "time", "t", "millisecond" );

		if ( n >= 4 )
			axes[ index++ ] = new Axis( "channel", "c", null );

		String unit = adaptSpatialUnit( unitXYZ );
		axes[ index ] = new Axis( "space", "z", unit );
		axes[ index + 1 ] = new Axis( "space", "y", unit );
		axes[ index + 2 ] = new Axis( "space", "x", unit );

		// multiresolution-pyramid
		// TODO: seem to be in XYZCT order (but in the file it seems reversed)
		final OmeNgffDataset[] datasets = new OmeNgffDataset[ numResolutionLevels ];

		for ( int s = 0; s < numResolutionLevels; ++s )
		{
			datasets[ s ] = new OmeNgffDataset();

			datasets[ s ].path = levelToName.apply( s );

			final AffineTransform3D m = levelToMipmapTransform.apply( s );

			final double[] translation = new double[ n ];
			final double[] scale = new double[ n ];

			for ( int d = 0; d < 3; ++d )
			{
				translation[ d ] = resolutionS0[d] * m.getTranslation()[ d ];
				scale[ d ] = resolutionS0[d] * m.get( d, d );
			}

			// if 4d and 5d, add 1's for C and T
			for ( int d = 3; d < n; ++d )
			{
				translation[ d ] = 0.0;
				scale[ d ] = 1.0;
			}

			datasets[ s ].coordinateTransformations = new CoordinateTransformation[ 2 ];
			datasets[ s ].coordinateTransformations[ 0 ] = new ScaleCoordinateTransformation( scale ); // TODO: check
			datasets[ s ].coordinateTransformations[ 1 ] = new TranslationCoordinateTransformation( translation ); // TODO: check
		}

		final double[] scale = new double[ n ];

		for ( int d = 0; d < n; ++d )
			scale[ d ] = 1.0;

		// just saw these being null everywhere
		final DatasetAttributes[] childrenAttributes = null;
		final CoordinateTransformation<?>[] coordinateTransformations = new CoordinateTransformation[] { new  ScaleCoordinateTransformation( scale ) }; // I also saw null
		final OmeNgffDownsamplingMetadata metadata = null;
		
		meta[ 0 ] = new OmeNgffMultiScaleMetadata( n, path, name, type, "0.4", axes, datasets, childrenAttributes, coordinateTransformations, metadata );

		return meta;
	}

	public static double[] getResolutionS0( final double[] cal, final double anisoF, final double downsamplingF )
	{
		double[] resolutionS0 = Arrays.copyOf( cal, cal.length );

		if ( !Double.isNaN( anisoF ) ) {
			// preserving anisotropy
			resolutionS0[2] = cal[2] * anisoF;
		}

		// downsampling
		if ( !Double.isNaN( downsamplingF ) )
			Arrays.setAll( resolutionS0, d -> resolutionS0[ d ] * downsamplingF );

		return resolutionS0;
	}

	/**
	 * Adapt various space unit namings to the units supported by Neuroglancer.
	 * OME NGFF spec does not have any restrictions on units but Neuroglancer only supports the ones that end in meter or the US customary units.
	 * @param unit
	 * @return
	 */
	private static String adaptSpatialUnit(String unit)
	{
		if ( unit == null )
			return "micrometer";

		switch ( unit.toLowerCase() ) {
			case "angstrom":
			case "ångström":
			case "ångströms":
				return "angstrom";
			case "nm":
			case "nanometers":
			case "nanometer":
				return "nanometer";
			case "mm":
			case "millimeters":
			case "millimeter":
				return "millimeter";
			case "m":
			case "meters":
			case "meter":
				return "meter";
			case "km":
			case "kilometer":
			case "kilometers":
				return "kilometer";
			case "inch":
			case "inches":
				return "inch";
			case "foot":
			case "feet":
				return "foot";
			case "yard":
			case "yards":
				return "yard";
			case "mile":
			case "miles":
				return "mile";
			case "um":
			case "μm":
			case "microns":
			case "micron":
			default:
				return "micrometer";
		}
	}

	public static void loadOMEZarr( final N5Reader n5, final String dataset )
	{
		//org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.OmeNgffMetadata
		// for this to work you need to register an adapter in the N5Factory class
		// final GsonBuilder builder = new GsonBuilder().registerTypeAdapter( CoordinateTransformation.class, new CoordinateTransformationAdapter() );
		final OmeNgffMultiScaleMetadata[] multiscales = n5.getAttribute( dataset, "multiscales", OmeNgffMultiScaleMetadata[].class );

		if ( multiscales == null || multiscales.length == 0 )
			throw new RuntimeException( "Could not parse OME-ZARR multiscales object. stopping." );

		if ( multiscales.length != 1 )
			System.out.println( "This dataset has " + multiscales.length + " objects, we expected 1. Picking the first one." );

		//System.out.println( "AllenOMEZarrLoader.getMipmapResolutions() for " + setupId + " using " + n5properties.getPath( setupId, timePointId ) + ": found " + multiscales[ 0 ].datasets.length + " multi-resolution levels." );

		//double[][] mipMapResolutions = new double[ multiscales[ 0 ].datasets.length ][ 3 ];
		//double[] firstScale = null;

		for ( int i = 0; i < multiscales[ 0 ].datasets.length; ++i )
		{
			final OmeNgffDataset ds = multiscales[ 0 ].datasets[ i ];
			System.out.println( ds.coordinateTransformations.length );
		}
	}

	public static void main( String[] args ) throws URISyntaxException
	{
		final URI uri2 = URITools.toURI("https://keller-data.int.janelia.org/s12a/samples_for_stitching/Live%20zebra%20fish%20stitched/Live%20zebra%20plane%206/dataset.n5");///setup0/time;t0/");
		System.out.println( uri2.toString() );

		//FileSystemKeyValueAccess kva = new FileSystemKeyValueAccess( FileSystems.getDefault() );
		
		String s = uri2.toString();
		N5Factory f = new N5Factory();
		N5Reader r = f.openFileSystemReader( s );
		r.close();
		System.exit( 0 );

		//final URI uri = URITools.toURI( "https://storage.googleapis.com/jax-public-ngff/KOMP/adult_lacZ/ndp/Moxd1/23420_K35061_FGut.zarr/0/" );
		//final String dataset = "/";

		final URI uri = URITools.toURI( "s3://aind-open-data/exaSPIM_708373_2024-04-02_19-49-38/SPIM.ome.zarr/" );
		final String dataset = "tile_x_0001_y_0001_z_0000_ch_488.zarr";

		//final URI uri = URITools.toURI( "/nrs/cellmap/data/jrc_cos7-11/jrc_cos7-11.zarr/" );
		//final String dataset = "recon-2/lm/er_palm/";
		//final String dataset = "recon-1/em/fibsem-uint16/";

		final N5Reader n5 = URITools.instantiateN5Reader( StorageFormat.ZARR, uri );

		loadOMEZarr(n5, dataset);
	}
}
