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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import ij.io.OpenDialog;
import loci.formats.FormatException;
import loci.formats.IFormatReader;
import loci.formats.ImageReader;
import loci.formats.Modulo;
import loci.formats.in.ND2Reader;
import loci.formats.in.ZeissCZIReader;
import loci.formats.meta.MetadataRetrieve;
import loci.formats.ome.OMEXMLMetadataImpl;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.datasetmanager.metadatarefinement.CZITileOrAngleRefiner;
import net.preibisch.mvrecon.fiji.datasetmanager.metadatarefinement.NikonND2TileOrAngleRefiner;
import net.preibisch.mvrecon.fiji.datasetmanager.metadatarefinement.TileOrAngleRefiner;
import net.preibisch.mvrecon.fiji.datasetmanager.patterndetector.FilenamePatternDetector;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.filemap2.FileMapEntry;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.util.BioformatsReaderUtils;
import ome.units.quantity.Length;


public class FileListDatasetDefinitionUtil
{
	
	private static final Map<Class<? extends IFormatReader>, TileOrAngleRefiner> tileOrAngleRefiners = new HashMap<>();	
	static {
		tileOrAngleRefiners.put(ZeissCZIReader.class , new CZITileOrAngleRefiner() );
		tileOrAngleRefiners.put( ND2Reader.class, new NikonND2TileOrAngleRefiner() );
	}
	
	
	public static class ChannelInfo
	{
		
		public String name;
		public String fluorophore;
		public Double wavelength;
		@Override
		public int hashCode()
		{
			final int prime = 31;
			int result = 1;
			result = prime * result + ( ( fluorophore == null ) ? 0 : fluorophore.hashCode() );
			result = prime * result + ( ( name == null ) ? 0 : name.hashCode() );
			result = prime * result + ( ( wavelength == null ) ? 0 : wavelength.hashCode() );
			return result;
		}
		
		@Override
		public boolean equals(Object obj)
		{
			if ( this == obj )
				return true;
			if ( obj == null )
				return false;
			if ( getClass() != obj.getClass() )
				return false;
			ChannelInfo other = (ChannelInfo) obj;
			if ( fluorophore == null )
			{
				if ( other.fluorophore != null )
					return false;
			}
			else if ( !fluorophore.equals( other.fluorophore ) )
				return false;
			if ( name == null )
			{
				if ( other.name != null )
					return false;
			}
			else if ( !name.equals( other.name ) )
				return false;
			if ( wavelength == null )
			{
				if ( other.wavelength != null )
					return false;
			}
			else if ( !wavelength.equals( other.wavelength ) )
				return false;
			return true;
		}
		
		public String toString()
		{
			return "Channel name:" + name + " fluorophore:" + fluorophore + " wavelength:" + wavelength;
		}
				
	}
	
	public static class TileInfo
	{
		public Double locationX;
		public Double locationY;
		public Double locationZ;
		
		public String toString()
		{
			return locationX + "," + locationY + "," + locationZ;
		}
		
		@Override
		public int hashCode()
		{
			final int prime = 31;
			int result = 1;
			result = prime * result + ( ( locationX == null ) ? 0 : locationX.hashCode() );
			result = prime * result + ( ( locationY == null ) ? 0 : locationY.hashCode() );
			result = prime * result + ( ( locationZ == null ) ? 0 : locationZ.hashCode() );
			return result;
		}
		@Override
		public boolean equals(Object obj)
		{
			if ( this == obj )
				return true;
			if ( obj == null )
				return false;
			if ( getClass() != obj.getClass() )
				return false;
			TileInfo other = (TileInfo) obj;
			if ( locationX == null )
			{
				if ( other.locationX != null )
					return false;
			}
			else if ( !locationX.equals( other.locationX ) )
				return false;
			if ( locationY == null )
			{
				if ( other.locationY != null )
					return false;
			}
			else if ( !locationY.equals( other.locationY ) )
				return false;
			if ( locationZ == null )
			{
				if ( other.locationZ != null )
					return false;
			}
			else if ( !locationZ.equals( other.locationZ ) )
				return false;
			return true;
		}
	}
	
	public static class AngleInfo
	{
		public Double angle;
		public Integer axis;
		
		@Override
		public int hashCode()
		{
			final int prime = 31;
			int result = 1;
			result = prime * result + ( ( angle == null ) ? 0 : angle.hashCode() );
			result = prime * result + ( ( axis == null ) ? 0 : axis.hashCode() );
			return result;
		}
		@Override
		public boolean equals(Object obj)
		{
			if ( this == obj )
				return true;
			if ( obj == null )
				return false;
			if ( getClass() != obj.getClass() )
				return false;
			AngleInfo other = (AngleInfo) obj;
			if ( angle == null )
			{
				if ( other.angle != null )
					return false;
			}
			else if ( !angle.equals( other.angle ) )
				return false;
			if ( axis == null )
			{
				if ( other.axis != null )
					return false;
			}
			else if ( !axis.equals( other.axis ) )
				return false;
			return true;
		}
	}
	
	public static class TileOrAngleInfo
	{
		public Double locationX;
		public Double locationY;
		public Double locationZ;
		public Double angle;
		public Integer axis;
		public Integer index;
		public Integer channelCount;
				
		public String toString()
		{
			return "TileOrAngleInfo idx:" + index + ", x:" + locationX + ", y:" + locationY + ", z:" + locationZ
					+ ", angle:" + angle + ", axis:" + axis;
		}
	}
	
	
	public static class ChannelOrIlluminationInfo
	{
		public Integer index;
		public Integer modStep;
		public String name;
		public String fluorophore;
		public Double wavelength;
		
		public String toString()
		{
			return "ChannelOrIlluminationInfo idx:" + index + ", modStep:" + modStep + ", name:" + name + ", fluo:" + fluorophore
					+ ", wavelength:" + wavelength;
		}
	}
	
	public enum CheckResult{
		SINGLE,
		MUlTIPLE_NAMED,
		MULTIPLE_INDEXED
	}
	
	public static CheckResult checkMultipleTimepoints(Map<Integer, List<Pair< Integer, Integer >>> tpMap)
	{
		if (tpMap.size() > 1)
			System.out.println( "WARNING: inconsistent timepoint number within file " );
		
		for (Integer tps: tpMap.keySet())
			if (tps > 1)
				return CheckResult.MULTIPLE_INDEXED;
		return CheckResult.SINGLE;
	}
	
	public static < T, S> CheckResult checkMultiplicity (Map<T,List<S>> map)
	{
		if (map.size() > 1)
			return CheckResult.MUlTIPLE_NAMED;
		else if (map.values().iterator().next().size() > 1)			
			return CheckResult.MULTIPLE_INDEXED;
		else
			return CheckResult.SINGLE;
	}

	protected static HashSet<Integer> extractFullResSeries( IFormatReader r, final int nSeries )
	{
		HashSet<Integer> fullResSeries = null;

		// TODO: do not do a hack specific to Bitplane Imaris 5.5 (HDF)
		if ( r.getFormat().contains( "Bitplane Imaris" ) && r.getFormat().contains("(HDF)" ))
		{
			int maxSizeX = -1;

			IOFunctions.println( "Detected Bitplane Imaris format, trying to ignore multi-resolution pyramid for input");
			for (int i = 0; i < nSeries; i++)
			{
				r.setSeries( i );
				final int size = r.getSizeX();
				maxSizeX = Math.max( size, maxSizeX );

				IOFunctions.println( "SizeX = " + size + " for series " + (i+1) + "/" + nSeries );
			}

			IOFunctions.println( "Keeping only images with full-res size " + maxSizeX );

			fullResSeries = new HashSet<>();

			for (int i = 0; i < nSeries; i++)
			{
				r.setSeries( i );
				if ( r.getSizeX() == maxSizeX )
					fullResSeries.add( i );
			}
		}

		return fullResSeries;
	}

	public static List<TileOrAngleInfo> predictTilesAndAngles( IFormatReader r )
	{
		final int nSeries = r.getSeriesCount();
		final MetadataRetrieve mr = (MetadataRetrieve) r.getMetadataStore();

		// TODO: right now this is a hack specific to Bitplane Imaris format
		final HashSet<Integer> fullResSeries = extractFullResSeries(r, nSeries);

		final List<TileOrAngleInfo> result = new ArrayList<>();
		
		for (int i = 0; i < nSeries; i++)
		{
			if ( fullResSeries != null && !fullResSeries.contains( i ) )
				continue;

			r.setSeries( i );
			final TileOrAngleInfo infoI = new TileOrAngleInfo();
			infoI.index = i;

			//System.out.println( r.getCurrentFile() );
			//System.out.println( r.getFormat() );
			//System.out.println( r.getSizeX() );

			// query x position
			Length posX = null;
			try {
				posX = mr.getPlanePositionX( i, 0);
			}
			catch (IndexOutOfBoundsException e)
			{				
			}
			infoI.locationX = posX != null ? posX.value().doubleValue() : null ;

			// query y position
			Length posY = null;
			try {
				posY = mr.getPlanePositionY( i, 0);
			}
			catch (IndexOutOfBoundsException e)
			{				
			}
			infoI.locationY = posY != null ? posY.value().doubleValue() : null;
			
			// query z position
			Length posZ = null;
			try {
				posZ = mr.getPlanePositionZ( i, 0);
			}
			catch (IndexOutOfBoundsException e)
			{				
			}
			infoI.locationZ = posZ != null ? posZ.value().doubleValue() : null;
			
			// keep track of "channel" number in series, makes stuff easier elswhere
			infoI.channelCount = r.getSizeC();
			
			result.add( infoI );
			
			IOFunctions.println("" + new Date(System.currentTimeMillis()) + ": Detecting Tiles and Angles in Series " + (i+1) + " of " + nSeries );
		}
		
		return result;
	}
	
	
	public static List<Pair<Integer, List<ChannelOrIlluminationInfo>>> predictTimepointsChannelsAndIllums( IFormatReader r )
	{
		final int nSeries = r.getSeriesCount();

		// TODO: right now this is a hack specific to Bitplane Imaris format
		final HashSet<Integer> fullResSeries = extractFullResSeries(r, nSeries);

		final Modulo cMod = r.getModuloC();			
		final boolean hasModulo = cMod != null && (cMod.start != cMod.end);
		final int cModStep = hasModulo ? (int) cMod.step : r.getSizeC();
		
		final MetadataRetrieve mr = (MetadataRetrieve) r.getMetadataStore();
		
		final List<Pair<Integer, List<ChannelOrIlluminationInfo>>>result = new ArrayList<>();
		
		for (int i = 0; i < nSeries; i++)
		{
			if ( fullResSeries != null && !fullResSeries.contains( i ) )
				continue;

			r.setSeries( i );
			final List<ChannelOrIlluminationInfo> channelandIllumInfos = new ArrayList<>();
			for (int c = 0; c < r.getSizeC(); c++)
			{
				final ChannelOrIlluminationInfo infoI = new ChannelOrIlluminationInfo();
				infoI.index = c;
				infoI.modStep = cModStep;
				
				// query channel Name
				infoI.name =  mr.getChannelName( i, c % cModStep );
					
				// query channel fluor
				infoI.fluorophore =  mr.getChannelFluor( i, c % cModStep );
					
				// query channel emission
				Length channelEmissionWavelength = mr.getChannelEmissionWavelength( i, c % cModStep);
				infoI.wavelength = channelEmissionWavelength != null ? channelEmissionWavelength.value().doubleValue() : null ;
				
				channelandIllumInfos.add( infoI );
			}

			// FIX for XYT stacks that should be XYZ (default if order is not certain)
			// assume time points are actually z planes
			final int numTPs = (!r.isOrderCertain() && r.getSizeZ() <= 1 && r.getSizeT() > 1 ) ? r.getSizeZ() : r.getSizeT();
			result.add( new ValuePair<>( numTPs, channelandIllumInfos ));
			
			IOFunctions.println("" + new Date(System.currentTimeMillis()) + ": Detecting Channels and Illuminations in Series " + (i+1) + " of " + nSeries );
		}
		
		return result;
	}
	
	public static Pair<Map<TileInfo, List<Pair<Integer, Integer>>>, Map<AngleInfo, List<Pair<Integer, Integer>>>> mapTilesAndAnglesToSeries(List<TileOrAngleInfo> infos)
	{
		final Map<TileInfo, List<Pair<Integer, Integer>>> tileMap = new HashMap<>();
		final Map<AngleInfo, List<Pair<Integer, Integer>>> angleMap = new HashMap<>();
		for (int i = 0; i < infos.size(); i++)
		{
			final TileOrAngleInfo info = infos.get( i );
			
			final TileInfo tI = new TileInfo();
			tI.locationX = info.locationX;
			tI.locationY = info.locationY;
			tI.locationZ = info.locationZ;
			
			if (!tileMap.containsKey( tI ))
				tileMap.put( tI, new ArrayList<>() );
			for (int j = 0; j < info.channelCount; j++)
				tileMap.get( tI ).add( new ValuePair< Integer, Integer >( i, j ) );
			
			final AngleInfo aI = new AngleInfo();
			aI.angle = info.angle;
			aI.axis = info.axis;
			
			if (!angleMap.containsKey( aI ))
				angleMap.put( aI, new ArrayList<>() );
			for (int j = 0; j < info.channelCount; j++)
				angleMap.get( aI ).add( new ValuePair< Integer, Integer >( i, j ) );
		}
		
		return new ValuePair< Map<TileInfo,List<Pair<Integer, Integer>>>, Map<AngleInfo,List<Pair<Integer, Integer>>> >( tileMap, angleMap );
	}
	
	public static Pair<Map<Integer, List<Pair<Integer, Integer>>>, Pair<Map<ChannelInfo, List<Pair<Integer, Integer>>>, Map<Integer, List<Pair<Integer, Integer>>>>> mapTimepointsChannelsAndIlluminations(List<Pair<Integer, List<ChannelOrIlluminationInfo>>> infos)
	{
		// map the number of timepoints to series
		// we know nothing about timepoints a.t.m.
		final Map<Integer, List<Pair<Integer, Integer>>> timepointNumberMap = new HashMap<>();
		
		// map ChannelInfos to series,channel indices
		final Map<ChannelInfo, List<Pair<Integer, Integer>>> channelMap = new HashMap<>();
		
		// map Illumination index to series,channel indices
		final Map<Integer, List<Pair<Integer, Integer>>> illumMap = new HashMap<>();
		
		for (int i = 0 ; i < infos.size(); i++)
		{
			Pair< Integer, List< ChannelOrIlluminationInfo > > seriesInfo = infos.get( i );
			
				
			for (int j = 0 ; j < seriesInfo.getB().size(); j++)
			{
				ChannelOrIlluminationInfo chAndIInfo = seriesInfo.getB().get( j );
				
				final ChannelInfo chI = new ChannelInfo();
				chI.fluorophore = chAndIInfo.fluorophore;
				chI.name = chAndIInfo.name;
				chI.wavelength = chAndIInfo.wavelength;
				
				final Integer illIdx = chAndIInfo.index / chAndIInfo.modStep;
				
				// map channel info
				if (!channelMap.containsKey( chI ))
					channelMap.put( chI, new ArrayList<>() );
				channelMap.get( chI ).add( new ValuePair< Integer, Integer >( i, j ) );
				
				// map illumIdx
				if (!illumMap.containsKey( illIdx ))
					illumMap.put( illIdx, new ArrayList<>() );
				illumMap.get( illIdx ).add( new ValuePair< Integer, Integer >( i, j ) );
				
				// map timepoint number
				final Integer tpCount = seriesInfo.getA();
				if (!timepointNumberMap.containsKey( tpCount ))
					timepointNumberMap.put( tpCount, new ArrayList<>() );
				timepointNumberMap.get( tpCount ).add( new ValuePair< Integer, Integer >(i,j ) );
			}			
		}
		
		return new ValuePair< Map<Integer,List<Pair<Integer, Integer>>>, Pair<Map<ChannelInfo,List<Pair<Integer,Integer>>>,Map<Integer,List<Pair<Integer,Integer>>>> >
					( timepointNumberMap, new ValuePair<Map<ChannelInfo,List<Pair<Integer,Integer>>>,Map<Integer,List<Pair<Integer,Integer>>>> ( channelMap, illumMap ) );
		
	}

	public static <T> List<T> listIntersect(List<T> a, List<T> b)
	{
		List<T> result = new ArrayList<>();
		for (T t : a)
			if (b.contains( t ))
				result.add( t );
		return result;
	}
	
	
	public static void resolveAmbiguity(Map<Class<? extends Entity>, CheckResult> checkResults,
													boolean channelIllumAmbiguous,
													boolean preferChannel,
													boolean angleTileAmbiguous,
													boolean preferTile)
	{
		if (channelIllumAmbiguous){
			if (preferChannel)
				checkResults.put( Channel.class, CheckResult.MULTIPLE_INDEXED );
			else
				checkResults.put( Illumination.class, CheckResult.MULTIPLE_INDEXED );			
		}
		
		if (angleTileAmbiguous){
			if (preferTile)
				checkResults.put( Tile.class, CheckResult.MULTIPLE_INDEXED );
			else
				checkResults.put( Angle.class, CheckResult.MULTIPLE_INDEXED );
		}
	}

	public static void groupZPlanes(FileListViewDetectionState state, FilenamePatternDetector patternDetector, List<Integer> variablesToUse)
	{
		final Map< FileMapEntry, Pair< Dimensions, VoxelDimensions > > dimensionMap = state.getDimensionMap();
		for (final Class<? extends Entity> cl: state.getIdMap().keySet())
		{
			final Map< Integer, List< FileMapEntry > > idMapForClass = state.getIdMap().get( cl );
			for (final List< FileMapEntry > fileList : idMapForClass.values())
			{
				// construct Map (invariant idxes, series, channel) -> (varying idxes)
				final Map< Pair< List< Integer >, Pair< Integer, Integer > >, List< List< Integer > > > zGroupedMap = new HashMap<>();
				final Map< Pair< List< Integer >, Pair< Integer, Integer > >, Pair< Dimensions, VoxelDimensions > > dimensionGroupedMap = new HashMap<>();
				for (final FileMapEntry file : fileList)
				{
					final Pair< Integer, Integer > seriesChannel = new ValuePair<>( file.series(), file.channel() );
					final List<Integer> variables = new ArrayList<>();
					final List<Integer> invariants = new ArrayList<>();
					final Matcher m = patternDetector.getPatternAsRegex().matcher( file.file().getAbsolutePath() );

					if (!m.matches())
						IOFunctions.printErr( "ERROR grouping z planes" );

					for (int i = 1; i<=m.groupCount(); i++)
					{
						if (variablesToUse.contains( i-1 ))
							variables.add( Integer.parseInt( m.group( i ) ) );
						else
							invariants.add( Integer.parseInt( m.group( i ) ) );
					}

					final  Pair<List<Integer>, Pair<Integer, Integer>> key = new ValuePair<>( invariants, seriesChannel );
					if (!zGroupedMap.containsKey( key ))
						zGroupedMap.put( key, new ArrayList<>() );
					zGroupedMap.get( key ).add( variables );

					// accumulate dimensions along z
					if (!dimensionGroupedMap.containsKey( key ))
						dimensionGroupedMap.put( key, dimensionMap.get( file ) );
					else
					{
						final Dimensions dimNew = dimensionMap.get( file ).getA();
						final Dimensions dimAccu = dimensionGroupedMap.get( key ).getA();
						final long[] dim = new long[dimAccu.numDimensions()];
						dimAccu.dimensions( dim );
						dim[2] += dimNew.dimension( 2 );
						dimensionGroupedMap.put( key, new ValuePair<>(new FinalDimensions( dim ), dimensionGroupedMap.get( key ).getB() ) );
					}

					// remove old dimensions from dimensionMap
					//dimensionMap.remove( file );
				}

				// clear old fileList
				fileList.clear();

				zGroupedMap.forEach( (k,v) -> {
					final List< Integer > invariants = k.getA();

					// sort variables 
					v.forEach( l -> Collections.sort( l ) );

					// construct pattern
					final String patternPath = getPatternFile( patternDetector, variablesToUse, invariants, v );
					System.out.println( patternPath );
					final FileMapEntry fileMapEntry = new FileMapEntry( new File( patternPath ), k.getB().getA(), k.getB().getB() );
					fileList.add( fileMapEntry );
					dimensionMap.put( fileMapEntry, dimensionGroupedMap.get( k ) );
				});
			}
		}
		state.setWasZGrouped( true );
	}

	public static String getPatternFile(FilenamePatternDetector patternDetector, List<Integer> variableIdxes, List<Integer> invariants, List<List<Integer>> variables)
	{
		final StringBuilder sb = new StringBuilder();
		Iterator< Integer > invIterator = invariants.iterator();
		final AtomicInteger varIdx = new AtomicInteger( 0 );
		for (int i = 0; i < patternDetector.getNumVariables(); i++)
		{
			sb.append( patternDetector.getInvariant( i ) );
			if (variableIdxes.contains( i ))
			{

				// get actual strings for variable (because of leading zeroes)
				List< String > valuesForVariable = patternDetector.getValuesForVariable( i );

				// get present ints
				Set< Integer > presentValuesForVariable = variables.stream().map( j -> j.get( varIdx.get() ) ).collect( Collectors.toSet() );
				// get present str
				List< String > presentValuesStrings = new ArrayList<>(valuesForVariable.stream().filter( s -> presentValuesForVariable.contains( Integer.parseInt( s ) ) ).collect( Collectors.toSet() ) );
				Collections.sort( presentValuesStrings );

				String pattern = String.join( ",", presentValuesStrings );
				varIdx.incrementAndGet();
				sb.append( "<" + pattern + ">" );
			}
			else
			{
				sb.append( invIterator.next() );
			}
		}
		sb.append( patternDetector.getInvariant( patternDetector.getNumVariables() ) );
		return sb.toString();
	}

	public static void expandAccumulatedViewInfos
	(
			final Map<Class<? extends Entity>, List<Integer>> fileVariableToUse,
			final FilenamePatternDetector patternDetector,
			FileListViewDetectionState state			
	)
	{
				
		
		for (Class<? extends Entity> cl: state.getIdMap().keySet())
		{
			state.getIdMap().get( cl ).clear();
			state.getDetailMap().get( cl ).clear();
			Boolean singleEntityPerFile = state.getMultiplicityMap().get( cl ) == CheckResult.SINGLE;

			if ( singleEntityPerFile && fileVariableToUse.get( cl ).size() > 0 )
			{
				Pair< Map< Integer, Object >, Map< Integer, List< FileMapEntry > > > expandedMap;

				if (state.getGroupedFormat())
				{
					Map< String, Pair< File, Integer > > groupUsageMap = state.getGroupUsageMap();
					expandedMap = expandMapSingleFromFileGroupedFormat( 
							state.getAccumulateMap( cl ), patternDetector, fileVariableToUse.get( cl ), groupUsageMap );
				}
				
				else
				{
					expandedMap = expandMapSingleFromFile(
						state.getAccumulateMap( cl ), patternDetector, fileVariableToUse.get( cl ) );
				}
				state.getIdMap().get( cl ).putAll( expandedMap.getB() );
				state.getDetailMap().get( cl ).putAll( expandedMap.getA() );
			}

			else if ( singleEntityPerFile )
			{
				// FIXME: this is a hacky fix for the case of single, instances of attribute PER FILE
				// in this case, multiplicity will be SINGLE (even though it should be MULTIPLE_NAMED )
				// TODO: this should probably be fixed upstream
				// At the moment, all instances of this attribute will get id 0
				// NB: this throws away metadata
				final ArrayList< FileMapEntry > allViews = state.getAccumulateMap( cl ).values().stream().collect(
						ArrayList::new,
						ArrayList::addAll,
						ArrayList::addAll );
				state.getIdMap().get( cl ).put( 0, allViews );
			}

			else if ( state.getMultiplicityMap().get( cl ) == CheckResult.MULTIPLE_INDEXED )
			{
				if (cl.equals( TimePoint.class ))
					state.getIdMap().get( cl ).putAll( expandTimePointMapIndexed( state.getAccumulateMap( cl )) );
				else
					state.getIdMap().get( cl ).putAll( expandMapIndexed( state.getAccumulateMap( cl ), cl.equals( Angle.class ) || cl.equals( Tile.class) ) );
			}

			else if ( state.getMultiplicityMap().get( cl ) == CheckResult.MUlTIPLE_NAMED )
			{
				Pair< Map< Integer, Object >, Map< Integer, List< FileMapEntry > > > resortMapNamed = resortMapNamed(
						state.getAccumulateMap( cl ) );
				state.getDetailMap().get( cl ).putAll( resortMapNamed.getA() );
				state.getIdMap().get( cl ).putAll( resortMapNamed.getB() );
			}
			
		}
		
	}

	public static < T > Pair< Map< Integer, T >, Map< Integer, List< FileMapEntry > > > expandMapSingleFromFile(
			Map< T, List< FileMapEntry > > map,
			FilenamePatternDetector det,
			List< Integer > patternIdx )
	{
		Map<Integer, List<FileMapEntry>> res = new HashMap<>();
		Map<Integer, T> res2 = new HashMap<>();
		SortedMap< FileMapEntry, T > invertedMap = invertMapSortValue( map );

		Map<List<Integer>, Integer> multiIdxMap = new HashMap<>();

		for (FileMapEntry fileInfo : invertedMap.keySet())
		{
			int id = -1;
			T attribute = invertedMap.get( fileInfo );
			//System.out.println( fileInfo.getA().getAbsolutePath() );

			Matcher m = det.getPatternAsRegex().matcher( fileInfo.file().getAbsolutePath() );

			// we have one numerical group describing this attribute -> use it as id
			if (patternIdx.size() == 1)
			{
				if (m.matches())
					id = Integer.parseInt( m.group( patternIdx.get( 0 ) + 1 ));
				else
					System.out.println( "WARNING: something went wrong while matching filenames" );
			}
			// we have more than one group describing attribute -> use increasing indices
			else
			{
				if(!m.matches() || m.groupCount() < patternIdx.stream().reduce( Integer.MIN_VALUE, Math::max ))
					System.out.println( "WARNING: something went wrong while matching filenames" );
				else
				{
					List< Integer > multiIdx = patternIdx.stream().map( idx -> Integer.parseInt( m.group( idx + 1 )  ) ).collect( Collectors.toList() );
					if (!multiIdxMap.containsKey( multiIdx ))
						multiIdxMap.put( multiIdx, multiIdxMap.size() );
					id = multiIdxMap.get( multiIdx );
				}
				
			}
			
			res2.put( id, attribute );
			
			if (!res.containsKey( id ))
				res.put( id, new ArrayList<>() );
			res.get( id ).add( fileInfo );
			
		}

		return new ValuePair<>( res2, res );
	}

	public static < T > Pair< Map< Integer, T >, Map< Integer, List< FileMapEntry > > > expandMapSingleFromFileGroupedFormat(
			Map< T, List< FileMapEntry > > map,
			FilenamePatternDetector det,
			List< Integer > patternIdx,
			Map< String, Pair< File, Integer > > groupUsageMap )
	{
		Map< Integer, List< FileMapEntry > > res = new HashMap<>();
		Map<Integer, T> res2 = new HashMap<>();
		SortedMap< FileMapEntry, T > invertedMap = invertMapSortValue( map );

		//
		Map<List<Integer>, Integer> multiIdxMap = new HashMap<>();

		for (FileMapEntry fileInfo : invertedMap.keySet())
		{
			int id = -1;
			T attribute = invertedMap.get( fileInfo );
			//System.out.println( fileInfo.getA().getAbsolutePath() );
			
			
			// find the actual used file
			String seriesFile = null;
			for (Entry< String, Pair< File, Integer > > e : groupUsageMap.entrySet())
			{
				if (new ValuePair<>( fileInfo.file(), fileInfo.series() ).equals( e.getValue() ))
					seriesFile = e.getKey();
			}
			
			Matcher m = det.getPatternAsRegex().matcher( seriesFile );
			
			// we have one numerical group describing this attribute -> use it as id
			if ( patternIdx.size() == 1 )
			{
				if ( m.matches() )
					id = Integer.parseInt( m.group( patternIdx.get( 0 ) + 1 ) );
				else
					System.out.println( "WARNING: something went wrong while matching filenames" );
			}
			// we have more than one group describing attribute -> use
			// increasing indices
			else
			{
				if ( !m.matches() || m.groupCount() < patternIdx.stream().reduce( Integer.MIN_VALUE, Math::max ) )
					System.out.println( "WARNING: something went wrong while matching filenames" );
				else
				{
					List< Integer > multiIdx = patternIdx.stream().map( idx -> Integer.parseInt( m.group( idx + 1 ) ) )
							.collect( Collectors.toList() );
					if ( !multiIdxMap.containsKey( multiIdx ) )
						multiIdxMap.put( multiIdx, multiIdxMap.size() );
					id = multiIdxMap.get( multiIdx );
				}

			}

			res2.put( id, attribute );

			if ( !res.containsKey( id ) )
				res.put( id, new ArrayList< >() );
			res.get( id ).add( fileInfo );
		}
		return new ValuePair<>( res2, res );

	}

	public static < T > Pair< Map< Integer, T >, Map< Integer, List< FileMapEntry > > > resortMapNamed(
			Map< T, List< FileMapEntry > > map )
	{

		Map< Integer, List< FileMapEntry > > res = new HashMap<>();
		Map< Integer, T > res2 = new HashMap<>();
		SortedMap< FileMapEntry, T > invertedMap = invertMapSortValue( map );
		int maxId = 0;
		for ( FileMapEntry fileInfo : invertedMap.keySet() )
		{
			int id = 0;
			T attribute = invertedMap.get( fileInfo );
			if ( !res2.values().contains( attribute ) )
			{
				res2.put( maxId, attribute );
				id = maxId;
				maxId++;
			}
			else
			{
				for ( Integer i : res2.keySet() )
					if ( res2.get( i ).equals( attribute ) )
						id = i;
			}

			if ( !res.containsKey( id ) )
				res.put( id, new ArrayList<>() );
			res.get( id ).add( fileInfo );
		}
		return new ValuePair<>( res2, res );
	}


	public static < T > Map< Integer, List< FileMapEntry > > expandMapIndexed(
			Map< T, List< FileMapEntry > > map, boolean useSeries )
	{
		Map< Integer, List< FileMapEntry > > res = new HashMap<>();
		SortedMap< FileMapEntry, T > invertedMap = invertMapSortValue( map );
		for ( FileMapEntry fileInfo : invertedMap.keySet() )
		{
			int id = useSeries ? fileInfo.series() : fileInfo.channel();
			if (!res.containsKey( id ))
				res.put( id, new ArrayList<>() );
			res.get( id ).add( fileInfo );
		}
		return res;
	}

	public static < T > Map< Integer, List< FileMapEntry > > expandTimePointMapIndexed(
			Map< T, List< FileMapEntry > > map )
	{
		Map< Integer, List< FileMapEntry > > res = new HashMap<>();
		SortedMap< FileMapEntry, T > invertedMap = invertMapSortValue( map );
		for ( FileMapEntry fileInfo : invertedMap.keySet() )
		{
			// TODO: can we get around this dirty cast?
			Integer numTP = (Integer) invertedMap.get( fileInfo );
			for (int i = 0; i < numTP; i++)
			{
				if (!res.containsKey( i ))
					res.put( i, new ArrayList<>() );
				res.get( i ).add( fileInfo );
			}
		}
		return res;
	}


	public static < T > SortedMap< FileMapEntry, T > invertMapSortValue( Map< T, List< FileMapEntry > > map )
	{

		SortedMap< FileMapEntry, T > res = new TreeMap<>( new Comparator< FileMapEntry >()
		{

			@Override
			public int compare(FileMapEntry o1, FileMapEntry o2)
			{
				int filecompare = o1.file().getAbsolutePath().compareTo( o2.file().getAbsolutePath() ) ;
				if (filecompare != 0)
					return filecompare;

				int seriescompare = Integer.compare( o1.series(), o2.series() );
				if (seriescompare != 0)
					return seriescompare;

				return Integer.compare( o1.channel(), o2.channel() );
			}
		} );

		for (T key : map.keySet())
		{
			for (FileMapEntry vI : map.get( key ))
			{
				//System.out.println( vI.getB().getA() + "" + vI.getB().getB() );
				//System.out.println( key );
				res.put( vI, key );
			}
		}
		//System.out.println( res.size() );
		return res;
	}

	public static void detectDimensionsInFile( File file, Map< FileMapEntry, Pair< Dimensions, VoxelDimensions > > dimensionMaps, ImageReader reader )
	{

		//System.out.println( file );

		if (reader == null)
		{
			reader = BioformatsReaderUtils.createImageReaderWithSetupHooks();
			reader.setMetadataStore( new OMEXMLMetadataImpl());
		}

		try
		{
			// only switch file if it is not one of the already opened files
			if (reader.getCurrentFile() == null || !(Arrays.asList( reader.getUsedFiles()).contains( file.getAbsolutePath())))
			{
				reader.setId( file.getAbsolutePath() );
			}

		// only use the 'master' file of a group in grouped data
		final File currentFile = new File( reader.getCurrentFile() );

		for (int i = 0 ; i < reader.getSeriesCount(); i++)
		{
			reader.setSeries( i );
			MetadataRetrieve meta = (MetadataRetrieve)reader.getMetadataStore();

			if (!reader.isOrderCertain() && reader.getSizeZ() <= 1 && reader.getSizeT() > 1 ){
				IOFunctions.println( new Date(System.currentTimeMillis()) + ": WARNING: Uncertain XZY/XZT order in File " + file.getAbsolutePath() + 
						", Image " + i);
				IOFunctions.println( new Date(System.currentTimeMillis()) + ": Assuming XYZ. For XYT, please resave the data as "
						+ "separate 2D images for each time point or set the metadata for the third dimesion." );
			}

			double sizeX = 1;
			double sizeY = 1;
			double sizeZ = 1;
			
			Length pszX = null;
			try {
				pszX = meta.getPixelsPhysicalSizeX( i );
			}
			catch (IndexOutOfBoundsException e)
			{				
			}

			//System.out.println( pszX );
			sizeX = pszX != null ? pszX.value().doubleValue() : 1 ;
			
			Length pszY = null;
			try {
				pszY = meta.getPixelsPhysicalSizeY( i );
			}
			catch (IndexOutOfBoundsException e)
			{				
			}
			sizeY = pszY != null ? pszY.value().doubleValue() : 1 ;
			
			Length pszZ = null;
			try {
				pszZ = meta.getPixelsPhysicalSizeZ( i );
			}
			catch (IndexOutOfBoundsException e)
			{				
			}
			sizeZ = pszZ != null ? pszZ.value().doubleValue() : 1 ;

			// get view dimensions
			int dimX = reader.getSizeX();
			int dimY = reader.getSizeY();
			// FIX for XYT stacks that should be XYZ (default if order is not certain)
			// assume time points are actually z planes
			int dimZ  = (!reader.isOrderCertain() && reader.getSizeZ() <= 1 && reader.getSizeT() > 1 ) ? reader.getSizeT() : reader.getSizeZ();

			// get pixel units from size
			String unit = pszX != null ? pszX.unit().getSymbol() : "pixels";
			
			FinalVoxelDimensions finalVoxelDimensions = new FinalVoxelDimensions( unit, sizeX, sizeY, sizeZ );
			FinalDimensions finalDimensions = new FinalDimensions( dimX, dimY, dimZ );
			
			for (int j = 0; j < reader.getSizeC(); j++)
			{
				FileMapEntry key = new FileMapEntry( currentFile, i, j );
				dimensionMaps.put( key, new ValuePair<>( finalDimensions, finalVoxelDimensions ) );
			}

		}

		reader.close();
		}
		catch ( FormatException | IOException e ){ e.printStackTrace(); }
	}

	public static void detectViewsInFiles(List<File> files,
										 FileListViewDetectionState state)
	{
		Map<File, Map<Class<? extends Entity>, CheckResult>> multiplicityMapInner = new HashMap<>();
		List<String> usedFiles = new ArrayList<>();
		
		Collections.sort( files );
		
		for (File file : files)
			if (!usedFiles.contains( file.getAbsolutePath() ))
			{
				ImageReader reader = BioformatsReaderUtils.createImageReaderWithSetupHooks();
				reader.setMetadataStore( new OMEXMLMetadataImpl() );
				detectViewsInFile( 	file,
									multiplicityMapInner,
									state,
									usedFiles,
									reader);

				detectDimensionsInFile (file, state.getDimensionMap(), reader);
				
				try
				{
					reader.close();
				}
				catch ( IOException e )
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		
		
		for (Map<Class<? extends Entity>, CheckResult> cr : multiplicityMapInner.values())
		{
			for (Class<? extends Entity> cl : cr.keySet() )
			{
				if (state.getMultiplicityMap().get( cl ) == CheckResult.SINGLE && cr.get( cl ) == CheckResult.MULTIPLE_INDEXED)
					state.getMultiplicityMap().put( cl, CheckResult.MULTIPLE_INDEXED );
				else if (state.getMultiplicityMap().get( cl ) == CheckResult.SINGLE && cr.get( cl ) == CheckResult.MUlTIPLE_NAMED)
					state.getMultiplicityMap().put( cl, CheckResult.MUlTIPLE_NAMED );
				// TODO: Error here if we have mixed indexed and named
				
				// When looking at files individually, we found a single instance per file, but we have multiple instances across files
				if (state.getMultiplicityMap().get( cl ) == CheckResult.SINGLE && cr.get( cl ) == CheckResult.SINGLE && state.getAccumulateMap( cl ).size() > 1)
					state.getMultiplicityMap().put( cl, CheckResult.MUlTIPLE_NAMED );
			}
		}
	}

	public static void detectViewsInFile(final File file,
										 Map<File, Map<Class<? extends Entity>, CheckResult>> multiplicityMap,
										 FileListViewDetectionState state,
										 List<String> usedFiles,
										 ImageReader reader)
	{
		
		if (reader == null)
		{
			reader = BioformatsReaderUtils.createImageReaderWithSetupHooks();
			reader.setMetadataStore( new OMEXMLMetadataImpl());
		}

		IOFunctions.println("" + new Date(System.currentTimeMillis()) + ": Investigating file " + file.getAbsolutePath() );

		try
		{
			if ( reader.getCurrentFile() == null || !Arrays.asList( reader.getUsedFiles() ).contains( file.getAbsolutePath() ))
				reader.setId( file.getAbsolutePath() );
		}
		catch ( FormatException | IOException e )
		{
			e.printStackTrace();
		}

		// use the master file of group from now on (in case we opened another file before)
		final File currentFile = new File( reader.getCurrentFile() );

		if (reader.getRGBChannelCount() > 1)
		{
			IOFunctions.println("RGB images are not supported at the moment. Please re-save as Composite (Open in Fiji > Image > Color > Make Composite > Save ). Quitting.");
			throw new IllegalArgumentException("RGB images are not supported at the moment. Please re-save as Composite. Quitting.");
		}

		usedFiles.addAll( Arrays.asList( reader.getUsedFiles() ));

		// the format we use employs grouped files
		if (reader.getUsedFiles().length > 1)
			state.setGroupedFormat( true );

		// populate grouped format file usage map
		for (int i = 0; i < reader.getSeriesCount(); i ++)
		{
			reader.setSeries( i );
			for (String usedFileI : reader.getSeriesUsedFiles())
				state.getGroupUsageMap().put( usedFileI , new ValuePair< File, Integer >( currentFile, i ));
		}

		// for each entity class, create a map from identifying object to series
		Map<Class<? extends Entity>, Map< ? extends Object, List< Pair< Integer, Integer > > >> infoMap = new HashMap<>();

		// predict tiles and angles, refine info with format specific refiner
		List< TileOrAngleInfo > predictTilesAndAngles = predictTilesAndAngles( reader);
		TileOrAngleRefiner refiner = tileOrAngleRefiners.get( ((ImageReader)reader).getReader().getClass() );
		if (refiner != null)
			refiner.refineTileOrAngleInfo( reader, predictTilesAndAngles );

		// map to tileMap and angleMap
		Pair< Map< TileInfo, List< Pair< Integer, Integer > > >, Map< AngleInfo, List< Pair< Integer, Integer > > > > mapTilesAngles = mapTilesAndAnglesToSeries( predictTilesAndAngles );
		final Map< TileInfo, List< Pair< Integer, Integer > > > tileMap = mapTilesAngles.getA();
		final Map< AngleInfo, List< Pair< Integer, Integer > > > angleMap = mapTilesAngles.getB();
		infoMap.put( Tile.class, tileMap );
		infoMap.put( Angle.class, angleMap );

		// predict and map timepoints, channels, illuminations
		List< Pair< Integer, List< ChannelOrIlluminationInfo > > > predictTPChannelsIllum = predictTimepointsChannelsAndIllums( reader );
		Pair< Map< Integer, List< Pair< Integer, Integer > > >, Pair< Map< ChannelInfo, List< Pair< Integer, Integer > > >, Map< Integer, List< Pair< Integer, Integer > > > > > mapTimepointsChannelsIlluminations = mapTimepointsChannelsAndIlluminations(predictTPChannelsIllum);
		infoMap.put(TimePoint.class, mapTimepointsChannelsIlluminations.getA());
		infoMap.put(Channel.class, mapTimepointsChannelsIlluminations.getB().getA());
		infoMap.put(Illumination.class, mapTimepointsChannelsIlluminations.getB().getB());

		// check multiplicity of maps
		Map<Class<? extends Entity>, CheckResult> multiplicity = new HashMap<>();

		multiplicity.put( TimePoint.class, checkMultipleTimepoints( (Map< Integer, List< Pair< Integer, Integer > > >) infoMap.get( TimePoint.class ) ));
		multiplicity.put( Channel.class, checkMultiplicity( infoMap.get( Channel.class ) ));
		multiplicity.put( Illumination.class, checkMultiplicity( infoMap.get( Illumination.class ) ));

		// make Maps TileInfo -> Series (is TileInfo -> (Series, Channel) before)
		Map< TileInfo, List< Integer > > tileSeriesMap = tileMap.entrySet().stream().collect(
				Collectors.toMap( Entry::getKey,
						e -> e.getValue().stream().map( Pair::getA ).distinct().collect( Collectors.toList() ) ) );

		// same but for Angles
		Map< AngleInfo, List< Integer > > angleSeriesMap = angleMap.entrySet().stream().collect(
				Collectors.toMap( Entry::getKey,
						e -> e.getValue().stream().map( Pair::getA ).distinct().collect( Collectors.toList() ) ) );

		multiplicity.put( Angle.class, checkMultiplicity( angleSeriesMap ));
		multiplicity.put( Tile.class, checkMultiplicity( tileSeriesMap));

		boolean channelIllumAmbiguous = false;
		// we found multiple illums/channels with metadata for illums -> consider only illums
		if (multiplicity.get( Channel.class ) == CheckResult.MULTIPLE_INDEXED && multiplicity.get( Illumination.class ) == CheckResult.MUlTIPLE_NAMED)
			multiplicity.put( Channel.class, CheckResult.SINGLE );
		// we found multiple illums/channels with metadata for channels -> consider only channels
		else if (multiplicity.get( Channel.class ) == CheckResult.MUlTIPLE_NAMED && multiplicity.get( Illumination.class ) == CheckResult.MULTIPLE_INDEXED)
			multiplicity.put( Illumination.class, CheckResult.SINGLE);
		// we found multiple illums/channels, but no metadata -> ask user to resolve ambiguity later
		else if (multiplicity.get( Channel.class ) == CheckResult.MULTIPLE_INDEXED && multiplicity.get( Illumination.class ) == CheckResult.MULTIPLE_INDEXED)
		{
			multiplicity.put( Channel.class, CheckResult.SINGLE);
			multiplicity.put( Illumination.class, CheckResult.SINGLE);
			channelIllumAmbiguous = true;
		}

		// same as above, but for tiles/angles
		boolean angleTileAmbiguous = false;
		if (multiplicity.get( Tile.class ) == CheckResult.MULTIPLE_INDEXED && multiplicity.get( Angle.class ) == CheckResult.MUlTIPLE_NAMED)
			multiplicity.put( Tile.class, CheckResult.SINGLE);
		else if (multiplicity.get( Tile.class ) == CheckResult.MUlTIPLE_NAMED && multiplicity.get( Angle.class ) == CheckResult.MULTIPLE_INDEXED)
			multiplicity.put( Angle.class, CheckResult.SINGLE);
		else if (multiplicity.get( Tile.class ) == CheckResult.MULTIPLE_INDEXED && multiplicity.get( Angle.class ) == CheckResult.MULTIPLE_INDEXED)
		{
			multiplicity.put( Tile.class, CheckResult.SINGLE);
			multiplicity.put( Angle.class, CheckResult.SINGLE);
			angleTileAmbiguous = true;
		}

		// TODO: handle tiles cleanly
		/*
		else if (tileMultiplicity == CheckResult.MUlTIPLE_NAMED && angleMultiplicity == CheckResult.MUlTIPLE_NAMED && tileMap.size() == angleMap.size())
			tileMultiplicity = CheckResult.SINGLE;
		*/

		// multiplicity of the different entities
		multiplicityMap.put( currentFile, multiplicity );

		for (Class<? extends Entity> cl : infoMap.keySet())
		{
			for (Object id : infoMap.get( cl ).keySet())
			{
				if (!state.getAccumulateMap(cl).containsKey( id ))
					state.getAccumulateMap(cl).put( id, new ArrayList<>() );
				infoMap.get( cl ).get( id ).forEach( series -> state.getAccumulateMap(cl).get( id ).add(
						new FileMapEntry( currentFile, series.getA(), series.getB() ) ) );
			}
		}

//			for (Integer tp : timepointMap.keySet())
//			{
//				if (!state.getAccumulateMap(TimePoint.class).containsKey( tp ))
//					state.getAccumulateMap(TimePoint.class).put( tp, new ArrayList<>() );
//				timepointMap.get( tp ).forEach( series -> state.getAccumulateTPMap().get( tp ).add( new ValuePair< File, Pair< Integer, Integer > >( file, series ) ) );
//			}
//			
//			for (ChannelInfo ch : channelMap.keySet())
//			{
//				//System.out.println( "DEBUG: Processing channel " + ch );
//				//System.out.println( "DEBUG: in file " + file.getAbsolutePath() );
//				if (!state.getAccumulateChannelMap().containsKey( ch ))
//				{
//					//System.out.println( "DEBUG: did not find channel, adding new" );
//					state.getAccumulateChannelMap().put( ch, new ArrayList<>() );
//				}
//				channelMap.get( ch ).forEach( seriesAndIdx -> state.getAccumulateChannelMap().get( ch ).add( new ValuePair< File, Pair<Integer,Integer> >( file, seriesAndIdx) ) );
//			}
//			
//			for (Integer il : illumMap.keySet())
//			{
//				if (!state.getAccumulateIllumMap().containsKey( il ))
//					state.getAccumulateIllumMap().put( il, new ArrayList<>() );
//				illumMap.get( il ).forEach( seriesAndIdx -> state.getAccumulateIllumMap().get( il ).add( new ValuePair< File, Pair<Integer,Integer> >( file, seriesAndIdx) ));
//			}
//			
//			for (TileInfo t : tileMap.keySet())
//			{
//				if(!state.getAccumulateTileMap().containsKey( t ))
//					state.getAccumulateTileMap().put( t, new ArrayList<>() );
//				tileMap.get( t ).forEach( seriesAndIdx -> state.getAccumulateTileMap().get( t ).add( new ValuePair< File, Pair<Integer,Integer> >( file, seriesAndIdx) ) );
//			}
//			
//			for (AngleInfo a : angleMap.keySet())
//			{
//				if(!state.getAccumulateAngleMap().containsKey( a ))
//					state.getAccumulateAngleMap().put( a, new ArrayList<>() );
//				angleMap.get( a ).forEach( seriesAndIdx -> state.getAccumulateAngleMap().get( a ).add( new ValuePair< File, Pair<Integer,Integer> >( file, seriesAndIdx) ) );
//			}
		
		if (!state.getAmbiguousAngleTile() && angleTileAmbiguous)
			state.setAmbiguousAngleTile(true);

		if(!state.getAmbiguousIllumChannel() && channelIllumAmbiguous)
			state.setAmbiguousIllumChannel(true);

		//reader.close();

	}
	
	public static void main(String[] args)
	{
		ImageReader reader = new ImageReader();
		reader.setMetadataStore( new OMEXMLMetadataImpl());
		
		try
		{
			reader.setId( new OpenDialog("pick file").getPath() );		
		
			for (int i = 0; i < reader.getSeriesCount(); i++)
			{
				reader.setSeries( i );
				Arrays.asList( reader.getSeriesUsedFiles() ).forEach( s -> System.out.println( s ) );
				
				System.out.println( ((OMEXMLMetadataImpl)reader.getMetadataStore()).dumpXML());
			}

			reader.close();
		}
		catch ( FormatException | IOException e )
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}
