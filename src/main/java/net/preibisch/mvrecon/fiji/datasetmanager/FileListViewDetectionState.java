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
package net.preibisch.mvrecon.fiji.datasetmanager;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.fiji.datasetmanager.FileListDatasetDefinitionUtil.CheckResult;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.filemap2.FileMapEntry;

public class FileListViewDetectionState
{
	Boolean ambiguousAngleTile;
	Boolean ambiguousIllumChannel;
	Boolean groupedFormat;
	Boolean wasZGrouped;

	Map< Class< ? extends Entity >, FileListDatasetDefinitionUtil.CheckResult > multiplicityMap;
	Map< Class< ? extends Entity >, Map< Object, List< FileMapEntry > > > accumulativeMap;
	Map< Class< ? extends Entity >, Map< Integer, List< FileMapEntry > > > idMap;
	Map< Class< ? extends Entity >, Map< Integer, Object > > detailMap;

	Map< FileMapEntry, Pair< Dimensions, VoxelDimensions > > dimensionMap;
	Map< String, Pair< File, Integer > > groupUsageMap;

	public FileListViewDetectionState()
	{
		multiplicityMap = new HashMap<>();
		accumulativeMap = new HashMap<>();
		idMap = new HashMap<>();
		detailMap = new HashMap<>();
		groupUsageMap = new HashMap<>();

		for (Class<? extends Entity> cl : new Class[] {Angle.class, TimePoint.class, Illumination.class, Tile.class, Channel.class})
		{
			multiplicityMap.put( cl, CheckResult.SINGLE  );
			accumulativeMap.put( cl, new HashMap<>() );
			idMap.put( cl, new HashMap<>() );
			detailMap.put( cl, new HashMap<>() );
		}

		ambiguousAngleTile = false;
		ambiguousIllumChannel = false;

		groupedFormat = false;
		wasZGrouped = false;

		dimensionMap = new HashMap<>();
	}

	/**
	 * @param state the current state, after initial view detection
	 * @return pair min &amp; max number of channels in each (file, series)-combination
	 */
	public static Pair< Integer, Integer > getMinMaxNumChannelsIndexed(final FileListViewDetectionState state)
	{
		if ( state.accumulativeMap.get( Channel.class ).size() < 1 )
			return null;

		final List< FileMapEntry > channelSources = state.accumulativeMap.get( Channel.class )
				.values().iterator().next();
		final Map< Pair< File, Integer >, Integer > counts = new HashMap<>();
		for ( FileMapEntry channelSrc : channelSources )
		{
			Pair< File, Integer > key = new ValuePair< File, Integer >( channelSrc.file(), channelSrc.series() );
			if ( !counts.containsKey( key ) )
				counts.put( key, 0 );
			counts.put( key, counts.get( key ) + 1 );
		}

		Integer min = counts.values().stream().reduce( Integer.MAX_VALUE, (x, y) -> Math.min( x, y ) );
		Integer max = counts.values().stream().reduce( Integer.MIN_VALUE, (x, y) -> Math.max( x, y ) );

		return new ValuePair< Integer, Integer >( min, max );
	}

	public static Pair< Integer, Integer > getMinMaxNumSeriesIndexed(final FileListViewDetectionState state)
	{
		if ( state.accumulativeMap.get( Tile.class ).size() < 1 )
			return null;

		final List< FileMapEntry > channelSources = state.accumulativeMap.get( Tile.class )
				.values().iterator().next();
		final Map< Pair< File, Integer >, Integer > counts = new HashMap<>();
		for ( FileMapEntry channelSrc : channelSources )
		{
			Pair< File, Integer > key = new ValuePair< File, Integer >( channelSrc.file(), channelSrc.channel() );
			if ( !counts.containsKey( key ) )
				counts.put( key, 0 );
			counts.put( key, counts.get( key ) + 1 );
		}

		Integer min = counts.values().stream().reduce( Integer.MAX_VALUE, (x, y) -> Math.min( x, y ) );
		Integer max = counts.values().stream().reduce( Integer.MIN_VALUE, (x, y) -> Math.max( x, y ) );

		return new ValuePair< Integer, Integer >( min, max );
	}

	public static boolean allVoxelSizesTheSame(FileListViewDetectionState state)
	{
		VoxelDimensions last = null;
		final Collection< Pair< Dimensions, VoxelDimensions > > sizes = state.getDimensionMap().values();
		for (final Pair< Dimensions, VoxelDimensions > size : sizes)
		{
			if (last == null)
				last = size.getB();
			if (!equalCalibration(last, size.getB() ))
				return false;
		}
		return true;
	}

	public static boolean equalCalibration(VoxelDimensions a, VoxelDimensions b)
	{
		if (a.numDimensions() != b.numDimensions())
			return false;
		if (!a.unit().equals( b.unit() ))
			return false;
		for (int d = 0; d<a.numDimensions(); d++)
			if (Math.abs( a.dimension( d ) - b.dimension( d ) ) > 1E-5)
				return false;
		return true;
	}

	public Map<Class<? extends Entity>, FileListDatasetDefinitionUtil.CheckResult> getMultiplicityMap()
	{
		return multiplicityMap;
	}

	public Boolean getAmbiguousAngleTile()
	{
		return ambiguousAngleTile;
	}

	public Boolean getAmbiguousIllumChannel()
	{
		return ambiguousIllumChannel;
	}

	public Map< Object, List< FileMapEntry > > getAccumulateMap( Class< ? extends Entity > cl )
	{
		return accumulativeMap.get( cl );
	}

	public Map< FileMapEntry, Pair< Dimensions, VoxelDimensions > > getDimensionMap()
	{
		return dimensionMap;
	}

	public void setAmbiguousAngleTile(Boolean ambiguousAngleTile)
	{
		this.ambiguousAngleTile = ambiguousAngleTile;
	}

	public void setAmbiguousIllumChannel(Boolean ambiguousIllumChannel)
	{
		this.ambiguousIllumChannel = ambiguousIllumChannel;
	}

	public Map< Class< ? extends Entity >, Map< Integer, List< FileMapEntry > > > getIdMap()
	{
		return idMap;
	}

	public Map< Class< ? extends Entity >, Map< Integer, Object > > getDetailMap()
	{
		return detailMap;
	}

	public Boolean getGroupedFormat()
	{
		return groupedFormat;
	}

	public void setGroupedFormat(Boolean groupedFormat)
	{
		this.groupedFormat = groupedFormat;
	}

	public Map< String, Pair< File, Integer > > getGroupUsageMap()
	{
		return groupUsageMap;
	}

	public Boolean getWasZGrouped()
	{
		return wasZGrouped;
	}

	public void setWasZGrouped(Boolean wasZGrouped)
	{
		this.wasZGrouped = wasZGrouped;
	}

}
