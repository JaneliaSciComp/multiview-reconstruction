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

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.universe.metadata.axes.Axis;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.OmeNgffMetadata;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.OmeNgffMultiScaleMetadata;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.OmeNgffMultiScaleMetadata.OmeNgffDataset;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.coordinateTransformations.CoordinateTransformation;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.coordinateTransformations.ScaleCoordinateTransformation;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.coordinateTransformations.TranslationCoordinateTransformation;

import bdv.img.n5.N5Properties;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.AllenOMEZarrLoader.OMEZARREntry;

public class AllenOMEZarrProperties implements N5Properties
{
	private final AbstractSequenceDescription< ?, ?, ? > sequenceDescription;

	// mapping of viewIDs to corresponding OME-ZARRs
	private final Map< ViewId, OMEZARREntry > viewIdToOmeZarrPath;

	// cache of multiscale metadata keyed by the zarr dataset path. The metadata for a
	// given zarr array is invariant across the (setupId, timepointId) combinations that
	// resolve to the same path, so we read it from N5 at most once per distinct path.
	// This matters most for split datasets: ~N split setups delegate to the same
	// underlying zarr, and every getMipmapResolutions/getDataType/getDimensions/
	// getDatasetPath call would otherwise trigger a fresh N5 attribute read. This
	// AllenOMEZarrProperties instance is a per-loader singleton (created once by
	// N5ImageLoader.createN5PropertiesInstance()), so the cache is shared across all
	// setups and all split tiles.
	private final ConcurrentHashMap< String, OmeNgffMultiScaleMetadata > multiscaleCache = new ConcurrentHashMap<>();

	// only warn once per opened XML if TranslationCoordinateTransformation is missing
	private boolean warnedMissingTranslation = false;

	// only warn once per opened XML if a level's translation is inconsistent with the
	// detected downsampling mode (e.g. a producer that wrote the half-pixel shift without
	// applying the anisotropy/calibration factor). The translation is only validated here,
	// not used to build the returned downsampling factors, so we warn instead of aborting.
	private boolean warnedInconsistentTranslation = false;

	public AllenOMEZarrProperties(
			final AbstractSequenceDescription< ?, ?, ? > sequenceDescription,
			final Map< ViewId, OMEZARREntry > viewIdToOmeZarrPath)
	{
		this.sequenceDescription = sequenceDescription;
		this.viewIdToOmeZarrPath = viewIdToOmeZarrPath;
	}

	private String getPath( final int setupId, final int timepointId )
	{
		return viewIdToOmeZarrPath.get( new ViewId( timepointId, setupId ) ).getPath();
	}

	@Override
	public String getDatasetPath( final N5Reader n5, final int setupId, final int timepointId, final int level )
	{
		return getMultiscaleDatasetPathOrDefault( n5, timepointId, setupId, level );
	}

	@Override
	public DataType getDataType( final N5Reader n5, final int setupId )
	{
		return getDataType( this, n5, setupId );
	}

	@Override
	public double[][] getMipmapResolutions( final N5Reader n5, final int setupId )
	{
		return getMipMapResolutions( this, n5, setupId );
	}

	@Override
	public long[] getDimensions( final N5Reader n5, final int setupId, final int timepointId, final int level )
	{
		final String path = getMultiscaleDatasetPathOrDefault(n5, timepointId, setupId, level);
		final long[] dimensions = n5.getDatasetAttributes( path ).getDimensions();

		final int[] xyz = getRawSpatialAxesIndices( n5, setupId, timepointId );
		if ( xyz == null )
			return Arrays.copyOf( dimensions, 3 ); // axes metadata missing/incomplete: assume standard TCZYX/XYZCT order

		return new long[] { dimensions[ xyz[ 0 ] ], dimensions[ xyz[ 1 ] ], dimensions[ xyz[ 2 ] ] };
	}

	/**
	 * Index of the x, y, and z axes (in that order) within the RAW dimension order used
	 * by the underlying N5/n5-zarr {@code CellGrid} / {@code RandomAccessibleInterval},
	 * which is the reverse of the declared OME-NGFF {@code axes} metadata order (e.g.
	 * metadata axes=[t,c,z,y,x] is reported as data dimensions=[x,y,z,c,t]) - and the
	 * same reversal applies to the per-axis {@code scale}/{@code translation} arrays in
	 * the coordinateTransformations metadata (see {@link #getMipMapResolutions}).
	 * Producers that declare a different (but equally NGFF-valid) axes order - e.g.
	 * axes=[x,y,z] instead of [z,y,x] - would otherwise have X and Z silently swapped by
	 * callers that assume the standard TCZYX/XYZCT position (0,1,2), which shows up as a
	 * rotated/transposed volume, or (for scale/translation) a squished/stretched one.
	 * Look up the x/y/z entries by name instead.
	 *
	 * @return {xIndex, yIndex, zIndex} into the raw (reversed) per-axis array, or
	 *         {@code null} if the axes metadata is missing or does not name all three
	 *         spatial axes (callers should then assume the standard {0,1,2} order).
	 */
	public int[] getRawSpatialAxesIndices( final N5Reader n5, final int setupId, final int timepointId )
	{
		return xyzAxesIndices( getViewSetupMultiscaleMetadata( n5, timepointId, setupId ).axes );
	}

	private static int[] xyzAxesIndices( final Axis[] axes )
	{
		if ( axes == null )
			return null;

		final String[] xyz = { "x", "y", "z" };
		final int[] idx = { -1, -1, -1 };

		for ( int i = 0; i < axes.length; ++i )
			for ( int d = 0; d < 3; ++d )
				if ( xyz[ d ].equalsIgnoreCase( axes[ i ].getName() ) )
					idx[ d ] = axes.length - 1 - i; // dimensions[]/scale[]/translation[] are axes[] reversed

		return ( idx[ 0 ] < 0 || idx[ 1 ] < 0 || idx[ 2 ] < 0 ) ? null : idx;
	}

	//
	// static methods
	//

	/**
	 * Get the first available (non-missing) timepoint for a setup.
	 * @return timepoint ID, or -1 if all timepoints are missing for this setup
	 */
	private static int getFirstAvailableTimepointId( final AbstractSequenceDescription< ?, ?, ? > seq, final int setupId )
	{
		for ( final TimePoint tp : seq.getTimePoints().getTimePointsOrdered() )
		{
			if ( seq.getMissingViews() == null || seq.getMissingViews().getMissingViews() == null || !seq.getMissingViews().getMissingViews().contains( new ViewId( tp.getId(), setupId ) ) )
				return tp.getId();
		}

		// All timepoints are missing for this setup
		return -1;
	}

	private static DataType getDataType( final AllenOMEZarrProperties n5properties, final N5Reader n5, final int setupId )
	{
		final int timePointId = getFirstAvailableTimepointId( n5properties.sequenceDescription, setupId );

		// If all timepoints are missing, return a default data type
		if ( timePointId < 0 )
			return DataType.UINT16;

		final String path = n5properties.getMultiscaleDatasetPathOrDefault(n5, timePointId, setupId, 0);
		final DatasetAttributes attributes = n5.getDatasetAttributes( path );

		if ( attributes == null )
			throw new RuntimeException( "Could not find dataset attributes for '" + path + "'. Please check if the OME-Zarr data is valid." );

		return attributes.getDataType();
	}

	private static double[][] getMipMapResolutions( final AllenOMEZarrProperties n5properties, final N5Reader n5, final int setupId )
	{
		final int timePointId = getFirstAvailableTimepointId( n5properties.sequenceDescription, setupId );

		// If all timepoints are missing, return default single-level resolution
		if ( timePointId < 0 )
			return new double[][] { { 1.0, 1.0, 1.0 } };

		// multiresolution pyramid
		final OmeNgffMultiScaleMetadata multiScaleMetadata = n5properties.getViewSetupMultiscaleMetadata(n5, timePointId, setupId);

		final double[][] mipMapResolutions = new double[ multiScaleMetadata.datasets.length ][ 3 ];
		double[] scaleS0 = null;
		//org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.OmeNgffMetadata
		// for this to work you need to register an adapter in the N5Factory class
		// final GsonBuilder builder = new GsonBuilder().registerTypeAdapter( CoordinateTransformation.class, new CoordinateTransformationAdapter() );
		final String path = n5properties.getPath( setupId, timePointId );

		// index of x/y/z within the raw (reversed) scale/translation arrays - see
		// getRawSpatialAxesIndices(); falls back to the standard TCZYX/XYZCT position
		// (0,1,2) if the axes metadata is missing or incomplete
		final int[] xyzIdx = xyzAxesIndices( multiScaleMetadata.axes );
		final int[] raw = xyzIdx != null ? xyzIdx : new int[] { 0, 1, 2 };

		// iterate over all resolution levels for scale
		for ( int s = 0; s < multiScaleMetadata.datasets.length; ++s )
		{
			final OmeNgffDataset ds = multiScaleMetadata.datasets[ s ];

			for ( final CoordinateTransformation< ? > c : ds.coordinateTransformations )
			{
				if ( c instanceof ScaleCoordinateTransformation )
				{
					final ScaleCoordinateTransformation scale = ( ScaleCoordinateTransformation ) c;

					if ( scaleS0 == null )
						scaleS0 = scale.getScale().clone();

					for ( int d = 0; d < mipMapResolutions[ s ].length; ++d )
					{
						mipMapResolutions[ s ][ d ] = scale.getScale()[ raw[ d ] ] / scaleS0[ raw[ d ] ];
						mipMapResolutions[ s ][ d ] = Math.round(mipMapResolutions[ s ][ d ]*10000)/10000d; // round to the 5th digit
					}
				}
			}
		}

		// iterate over all resolution levels for translation (to make sure scaleS0 is assigned if it existed)
		// OME-Zarr 0.4: physical = pixel * scale + translation
		// For averaging downsampling by factor r: calculates translation for r in pixels
		// For non-averaging (strided) downsampling: expected translation = 0
		// capture s0's translation explicitly. OME-Zarr allows s0 to omit the
		// translation transform entirely, in which case its origin is implicitly
		// zero. We must NOT lazily grab the first translation we encounter, since
		// that would be a downsampled level (e.g. s1) and corrupt the s0 reference.
		double[] translationS0 = new double[ mipMapResolutions[ 0 ].length ]; // zeros
		for ( final CoordinateTransformation< ? > c : multiScaleMetadata.datasets[ 0 ].coordinateTransformations )
			if ( c instanceof TranslationCoordinateTransformation )
				translationS0 = ( ( TranslationCoordinateTransformation ) c ).getTranslation().clone();

		Boolean isAveraging = null; // determined from first downsampled level with r>1, then verified for subsequent levels

		//System.out.println( "\nsetup " + setupId );

		for ( int s = 0; s < multiScaleMetadata.datasets.length; ++s )
		{
			final OmeNgffDataset ds = multiScaleMetadata.datasets[ s ];

			boolean foundTranslation = false;

			for ( final CoordinateTransformation< ? > c : ds.coordinateTransformations )
			{
				if ( c instanceof TranslationCoordinateTransformation )
				{
					final TranslationCoordinateTransformation t = ( TranslationCoordinateTransformation ) c;
					foundTranslation = true;

					if ( scaleS0 == null )
						throw new IllegalStateException( "Expected first scale to be set before the translation for level " + s + " dataset is processed" );

					//System.out.println( "s=" + s + ", translation: " + Arrays.toString( t.getTranslation() ));

					for ( int d = 0; d < mipMapResolutions[ s ].length; ++d )
					{
						final double r = mipMapResolutions[ s ][ d ];

						// skip dimensions that are not downsampled (r=1), both modes have shift=0
						if ( Math.abs( r - 1.0 ) < 0.01 )
							continue;

						final double pxTranslation = (t.getTranslation()[ raw[ d ] ] - translationS0[ raw[ d ] ]) / scaleS0[ raw[ d ] ];
                        final double logScale = Math.log( r ) / Math.log(2);
						final double expectedAveraging = Math.pow(2, logScale - 1) - 0.5; // 0.5, 1.5, 3.5, 7.5, ...
						final boolean matchesAveraging = Math.abs( pxTranslation - expectedAveraging ) < 0.05;
						final boolean matchesNonAveraging = Math.abs( pxTranslation ) < 0.01;

						if ( isAveraging == null )
						{
							// determine mode from first downsampled dimension.
							if ( matchesAveraging )
							{
								isAveraging = true;
							}
							else if ( matchesNonAveraging )
							{
								// translation=0 indicates strided/non-averaging downsampling.
								isAveraging = false;
								if ( !n5properties.warnedInconsistentTranslation )
								{
									IOFunctions.println( "WARNING: non-averaging (strided) downsampling detected (translation=0 for level " + s + " dim " + d + "). Proceeding; downsampling factors are taken from the scales." );
									n5properties.warnedInconsistentTranslation = true;
								}
							}
							else
							{
								// matches neither mode (e.g. a producer that did not apply the
								// anisotropy factor). Default to averaging.
								isAveraging = true;
								if ( !n5properties.warnedInconsistentTranslation )
								{
									IOFunctions.println( "WARNING: unsupported translation for level " + s + " dim " + d + ": relative pixel translation=" + pxTranslation + ", expected " + expectedAveraging + " for averaging or 0.0 for non-averaging (producer likely did not apply the anisotropy factor). Assuming averaging; downsampling factors are taken from the scales." );
									n5properties.warnedInconsistentTranslation = true;
								}
							}
						}
						else
						{
							// verify consistency with detected mode
							final double expected = isAveraging ? expectedAveraging : 0.0;
							if ( Math.abs( pxTranslation - expected ) >= 0.05 )
							{
								if ( !n5properties.warnedInconsistentTranslation )
								{
									IOFunctions.println( "WARNING: inconsistent translation for level " + s + " dim " + d + ": relative pixel translation=" + pxTranslation + ", expected " + expected + " based on detected " + ( isAveraging ? "averaging" : "non-averaging" ) + " downsampling (producer likely did not apply the anisotropy factor). Ignoring; downsampling factors are taken from the scales." );
									n5properties.warnedInconsistentTranslation = true;
								}
							}
						}
					}
				}
			}

			if ( !foundTranslation && s > 0 && !n5properties.warnedMissingTranslation )
			{
				IOFunctions.println( "WARNING: No TranslationCoordinateTransformation found, assuming half-pixel shifts for averaging-based downsampling." );
				n5properties.warnedMissingTranslation = true;
			}
		}

		return mipMapResolutions;
	}

	private String getMultiscaleDatasetPathOrDefault( N5Reader n5, int timepointId, int setupId, int level )
	{
		OmeNgffMultiScaleMetadata omeNgffMultiScaleMetadata = getViewSetupMultiscaleMetadata(n5, timepointId, setupId);

		String viewSetupPath = getPath( setupId, timepointId );
		// get the first scale path from the metadata
		String datasetPath = omeNgffMultiScaleMetadata.datasets[level].path;
		return String.format( "%s/%s", viewSetupPath, datasetPath);
	}

	// retrieve and cache the multiscale metadata (keyed by zarr dataset path, so it is
	// read from N5 at most once per distinct path regardless of how many setups /
	// split tiles resolve to it)
	private OmeNgffMultiScaleMetadata getViewSetupMultiscaleMetadata(N5Reader n5, int timePointId, int setupId) {
		final String datasetPath = getPath( setupId, timePointId );
		return multiscaleCache.computeIfAbsent( datasetPath, path -> loadMultiscaleMetadata( n5, path ) );
	}

	private OmeNgffMultiScaleMetadata loadMultiscaleMetadata(N5Reader n5, String datasetPath) {
		System.out.println( "Getting multiscale metadata for " + datasetPath );
		OmeNgffMetadata omeMetadata = n5.getAttribute(datasetPath, "ome", OmeNgffMetadata.class);

		final OmeNgffMultiScaleMetadata[] multiscales;
		if  (omeMetadata != null) {
			System.out.println( "Found OME metadata at " + datasetPath + ": " + omeMetadata);
			multiscales = omeMetadata.multiscales;
		} else {
			multiscales = n5.getAttribute( datasetPath, "multiscales", OmeNgffMultiScaleMetadata[].class );
			System.out.println( "Retrieved OME multiscales at " + datasetPath + ": " + summarize( multiscales ) );
		}

		if ( multiscales == null || multiscales.length == 0 )
			throw new IllegalStateException( "Could not parse OME-ZARR multiscales object. stopping." );

		if ( multiscales.length > 1 )
			System.out.println( "This dataset has " + multiscales.length + " objects, we expected 1. Picking the first one." );

		return multiscales[0];
	}

	// short, human-readable summary of the multiscales metadata for logging (the default array toString() is useless)
	private static String summarize( final OmeNgffMultiScaleMetadata[] multiscales )
	{
		if ( multiscales == null )
			return "null";

		final StringBuilder sb = new StringBuilder();
		sb.append( multiscales.length ).append( " multiscale(s)" );

		for ( final OmeNgffMultiScaleMetadata ms : multiscales )
		{
			if ( ms == null )
			{
				sb.append( " {null}" );
				continue;
			}

			sb.append( " {" );
			if ( ms.name != null )
				sb.append( "name='" ).append( ms.name ).append( "', " );
			if ( ms.type != null )
				sb.append( "type='" ).append( ms.type ).append( "', " );

			if ( ms.axes != null )
			{
				sb.append( "axes=[" );
				for ( int i = 0; i < ms.axes.length; ++i )
					sb.append( i == 0 ? "" : "," ).append( ms.axes[ i ].getName() );
				sb.append( "], " );
			}

			final int levels = ms.datasets == null ? 0 : ms.datasets.length;
			sb.append( levels ).append( " level(s)" );
			if ( ms.datasets != null )
			{
				sb.append( " paths=[" );
				for ( int i = 0; i < ms.datasets.length; ++i )
					sb.append( i == 0 ? "" : "," ).append( ms.datasets[ i ].path );
				sb.append( "]" );
			}
			sb.append( "}" );
		}

		return sb.toString();
	}
}
