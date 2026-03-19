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

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.OmeNgffMultiScaleMetadata;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.OmeNgffMultiScaleMetadata.OmeNgffDataset;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.coordinateTransformations.CoordinateTransformation;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.coordinateTransformations.ScaleCoordinateTransformation;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.v04.coordinateTransformations.TranslationCoordinateTransformation;

import bdv.img.n5.N5Properties;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.AllenOMEZarrLoader.OMEZARREntry;

public class AllenOMEZarrProperties implements N5Properties
{
	private final AbstractSequenceDescription< ?, ?, ? > sequenceDescription;

	// mapping of viewIDs to corresponding OME-ZARRs
	private final Map< ViewId, OMEZARREntry > viewIdToOmeZarrPath;

	// N5Properties.getDatasetPath should require an N5Reader so that the dataset path could always be retrieved correctly (e.g., "s0", "s1", "s2" or "0", "1", "2")
	// To work around this problem for now, first time we retrieve the view setup we cache it so that next time
	// the dataset path is needed - we use the cached value.
	// TODO: Remove this and the method that populates it, once the signature for N5Properties.getDatasetPath was updated to use the N5 Reader
	private final ConcurrentMap< ViewId, OmeNgffMultiScaleMetadata > viewIdToOmeMetadata = new ConcurrentHashMap<>();

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
	public String getDatasetPath( final int setupId, final int timepointId, final int level )
	{
		// Note: if the OME metadata has not been cached yet this method will return the default path, because the reader is not available
		return getMultiscaleDatasetPathOrDefault(null, timepointId, setupId, level);
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
		// dataset dimensions is 5D, remove the channel and time dimensions
		return Arrays.copyOf( dimensions, 3 );
	}

	//
	// static methods
	//

	private static int getFirstAvailableTimepointId( final AbstractSequenceDescription< ?, ?, ? > seq, final int setupId )
	{
		for ( final TimePoint tp : seq.getTimePoints().getTimePointsOrdered() )
		{
			if ( seq.getMissingViews() == null || seq.getMissingViews().getMissingViews() == null || !seq.getMissingViews().getMissingViews().contains( new ViewId( tp.getId(), setupId ) ) )
				return tp.getId();
		}

		throw new IllegalStateException( "All timepoints for setupId " + setupId + " are declared missing. Stopping." );
	}

	private static DataType getDataType( final AllenOMEZarrProperties n5properties, final N5Reader n5, final int setupId )
	{
		final int timePointId = getFirstAvailableTimepointId( n5properties.sequenceDescription, setupId );
		String datasetPath = n5properties.getMultiscaleDatasetPathOrDefault(n5, timePointId, setupId, 0);
		return n5.getDatasetAttributes( datasetPath ).getDataType();
	}

	private static double[][] getMipMapResolutions( final AllenOMEZarrProperties n5properties, final N5Reader n5, final int setupId )
	{
		final int timePointId = getFirstAvailableTimepointId( n5properties.sequenceDescription, setupId );

		// multiresolution pyramid
		OmeNgffMultiScaleMetadata multiScaleMetadata = n5properties.getViewSetupMultiscaleMetadata(n5, timePointId, setupId);

		double[][] mipMapResolutions = new double[ multiScaleMetadata.datasets.length ][ 3 ];
		double[] firstScale = null;

		for ( int i = 0; i < multiScaleMetadata.datasets.length; ++i )
		{
			final OmeNgffDataset ds = multiScaleMetadata.datasets[ i ];

			for ( final CoordinateTransformation< ? > c : ds.coordinateTransformations )
			{
				if ( c instanceof ScaleCoordinateTransformation )
				{
					final ScaleCoordinateTransformation s = ( ScaleCoordinateTransformation ) c;

					if ( firstScale == null )
						firstScale = s.getScale().clone();

					for ( int d = 0; d < mipMapResolutions[ i ].length; ++d )
					{
						mipMapResolutions[ i ][ d ] = s.getScale()[ d ] / firstScale[ d ];
						mipMapResolutions[ i ][ d ] = Math.round(mipMapResolutions[ i ][ d ]*10000)/10000d; // round to the 5th digit
					}
				}
				if ( c instanceof TranslationCoordinateTransformation)
				{
					final TranslationCoordinateTransformation t = ( TranslationCoordinateTransformation ) c;

					if (firstScale == null) {
						throw new IllegalStateException("Expected first scale to be set before the translation for level " + i + " dataset is processed");
					}

					for ( int d = 0; d < mipMapResolutions[ i ].length; ++d )
					{
						// at this point firstScale should be available
						double pxTranslation = t.getTranslation()[ d ] / firstScale[ d ];
						double pxTranslationCorrection = (pxTranslation + 0.5) / mipMapResolutions[i][d] - 0.5;
						if (Math.abs(pxTranslationCorrection) >= 0.5) {
							System.out.printf("Pixel translation[%d][%d]=%f (=%fpx) and the pixel correction %f is more than 0.5px\n",
									i, d, t.getTranslation()[ d ], pxTranslation, pxTranslationCorrection);
						}
					}
				}
			}
		}

		return mipMapResolutions;
	}

	private String getMultiscaleDatasetPathOrDefault( N5Reader n5, int timepointId, int setupId, int level )
	{
		OmeNgffMultiScaleMetadata omeNgffMultiScaleMetadata = getViewSetupMultiscaleMetadata(n5, timepointId, setupId);

		if (omeNgffMultiScaleMetadata == null) {
			throw new IllegalStateException("OME multiscale metadata could not be cached for (tp, setup) = (" +
					timepointId + "," + setupId + ") - current N5Reader is " + n5);
		}

		String viewSetupPath = getPath( setupId, timepointId );
		// get the first scale path from the metadata
		String datasetPath = omeNgffMultiScaleMetadata.datasets[level].path;
		return String.format( "%s/%s", viewSetupPath, datasetPath);
	}

	// retrieve and cache the multiscale metadata
	private OmeNgffMultiScaleMetadata getViewSetupMultiscaleMetadata(N5Reader n5, int timePointId, int setupId) {
		ViewId viewId = new ViewId(timePointId, setupId);

		return viewIdToOmeMetadata.computeIfAbsent(viewId, k -> {
			if (n5 == null) {
				return null; // no mapping will be cached
			}

			final OmeNgffMultiScaleMetadata[] multiscales = n5.getAttribute( getPath( setupId, timePointId ), "multiscales", OmeNgffMultiScaleMetadata[].class );

			if ( multiscales == null || multiscales.length == 0 )
				throw new IllegalStateException( "Could not parse OME-ZARR multiscales object. stopping." );

			if ( multiscales.length > 1 )
				System.out.println( "This dataset has " + multiscales.length + " objects, we expected 1. Picking the first one." );

			return multiscales[0];
		});
	}
}
