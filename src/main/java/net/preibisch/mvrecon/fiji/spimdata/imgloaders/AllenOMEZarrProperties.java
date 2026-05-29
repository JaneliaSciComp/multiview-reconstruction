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
import java.util.HashMap;
import java.util.Map;

import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.OmeNgffMultiScaleMetadata;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.OmeNgffMultiScaleMetadata.OmeNgffDataset;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.coordinateTransformations.CoordinateTransformation;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.coordinateTransformations.ScaleCoordinateTransformation;

import bdv.img.n5.N5Properties;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.AllenOMEZarrLoader.OMEZARREntry;

public class AllenOMEZarrProperties implements N5Properties
{
	private final AbstractSequenceDescription< ?, ?, ? > sequenceDescription;

	private final Map< ViewId, OMEZARREntry > viewIdToPath;

	// Cache for actual dataset paths from multiscales metadata: setupId -> (level -> datasetPath)
	private final Map< Integer, String[] > levelPathCache = new HashMap<>();

	public AllenOMEZarrProperties(
			final AbstractSequenceDescription< ?, ?, ? > sequenceDescription,
			final Map< ViewId, OMEZARREntry > viewIdToPath )
	{
		this.sequenceDescription = sequenceDescription;
		this.viewIdToPath = viewIdToPath;
	}

	private String getPath( final int setupId, final int timepointId )
	{
		return viewIdToPath.get( new ViewId( timepointId, setupId ) ).getPath();
	}

	@Override
	public String getDatasetPath( final int setupId, final int timepointId, final int level )
	{
		// Check if we have cached the actual paths from multiscales metadata
		final String[] cachedPaths = levelPathCache.get( setupId );
		if ( cachedPaths != null && level < cachedPaths.length )
		{
			return getPath( setupId, timepointId ) + "/" + cachedPaths[ level ];
		}
		// Fallback to numeric level (for v0.4 or if cache not populated)
		return String.format( getPath( setupId, timepointId ) + "/%d", level );
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
		final String path = getDatasetPath( setupId, timepointId, level );
		final long[] dimensions = n5.getDatasetAttributes( path ).getDimensions();
		// dataset dimensions is 5D, remove the channel and time dimensions
		return Arrays.copyOf( dimensions, 3 );
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
		// we need to make sure the cache is populated, otherwise getDatasetPath(...) might return the wrong path
		if ( n5properties.levelPathCache.get( setupId ) == null )
			getMipMapResolutions( n5properties, n5, setupId );

		final int timePointId = getFirstAvailableTimepointId( n5properties.sequenceDescription, setupId );

		// If all timepoints are missing, return a default data type
		if ( timePointId < 0 )
			return DataType.UINT16;

		final String path = n5properties.getDatasetPath( setupId, timePointId, 0 );
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

		//org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.OmeNgffMetadata
		// for this to work you need to register an adapter in the N5Factory class
		// final GsonBuilder builder = new GsonBuilder().registerTypeAdapter( CoordinateTransformation.class, new CoordinateTransformationAdapter() );
		final String path = n5properties.getPath( setupId, timePointId );

		// Try v0.4 structure first (multiscales at root), then v0.5/Zarr v3 structure (nested under ome)
		OmeNgffMultiScaleMetadata[] multiscales = n5.getAttribute( path, "multiscales", OmeNgffMultiScaleMetadata[].class );

		if ( multiscales == null || multiscales.length == 0 )
			multiscales = n5.getAttribute( path, "ome/multiscales", OmeNgffMultiScaleMetadata[].class );

		if ( multiscales == null || multiscales.length == 0 )
			throw new RuntimeException( "Could not parse OME-ZARR multiscales object (tried 'multiscales' and 'ome/multiscales'). stopping." );

		if ( multiscales.length != 1 )
			System.out.println( "This dataset has " + multiscales.length + " objects, we expected 1. Picking the first one." );

		//System.out.println( "AllenOMEZarrLoader.getMipmapResolutions() for " + setupId + " using " + n5properties.getPath( setupId, timePointId ) + ": found " + multiscales[ 0 ].datasets.length + " multi-resolution levels." );

		double[][] mipMapResolutions = new double[ multiscales[ 0 ].datasets.length ][ 3 ];
		double[] firstScale = null;

		// Cache the actual dataset paths from metadata (they may not be just "0", "1", "2")
		final String[] levelPaths = new String[ multiscales[ 0 ].datasets.length ];

		for ( int i = 0; i < multiscales[ 0 ].datasets.length; ++i )
		{
			final OmeNgffDataset ds = multiscales[ 0 ].datasets[ i ];

			// Store the actual path for this resolution level
			levelPaths[ i ] = ds.path;

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
					//System.out.println( "AllenOMEZarrLoader.getMipmapResolutions(), level " + i + ": " + Arrays.toString( s.getScale() ) + " >> " + Arrays.toString( mipMapResolutions[ i ] ) );
				}
			}
		}

		// Cache the level paths for this setup
		n5properties.levelPathCache.put( setupId, levelPaths );

		return mipMapResolutions;
	}
}
