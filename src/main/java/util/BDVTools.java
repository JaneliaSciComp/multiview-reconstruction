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
package util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import bdv.BigDataViewer;
import bdv.tools.brightness.ConverterSetup;
import bdv.viewer.DisplayMode;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerState;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.explorer.bdv.BDVUtils;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

/**
 * Utility methods for BigDataViewer operations.
 * Consolidates BDV helper methods from ViewSetupExplorerPanel and FilteredAndGroupedExplorerPanel.
 */
public class BDVTools
{
	// ==================== Display Mode ====================

	/**
	 * Sets BDV to FUSED display mode and deactivates all sources.
	 *
	 * @param bdv the BigDataViewer instance
	 * @param data the SpimData (unused but kept for API compatibility)
	 */
	public static void setFusedModeSimple( final BigDataViewer bdv, final AbstractSpimData< ? > data )
	{
		if ( bdv == null )
			return;

		final ViewerState state = bdv.getViewer().state();
		if ( state.getDisplayMode() != DisplayMode.FUSED )
		{
			setVisibleSources( state, state.getSources().subList( 0, 0 ) );
			state.setDisplayMode( DisplayMode.FUSED );
		}
	}

	// ==================== Source Visibility ====================

	/**
	 * Sets source visibility using batch API.
	 *
	 * @param state the ViewerState
	 * @param active collection of sources to make active (all others will be deactivated)
	 */
	public static void setVisibleSources( final ViewerState state, final Collection< ? extends SourceAndConverter< ? > > active )
	{
		final List< SourceAndConverter< ? > > inactive = new ArrayList<>( state.getSources() );
		inactive.removeAll( active );
		state.setSourcesActive( inactive, false );
		state.setSourcesActive( active, true );
	}

	/**
	 * Sets source visibility by setup ID using BDV's batch API.
	 * Uses O(1) batch calls instead of O(n) per-source calls.
	 *
	 * @param bdv the BigDataViewer instance
	 * @param activeSetupIds set of setup IDs that should be visible
	 */
	public static void setVisibleSourcesBatch( final BigDataViewer bdv, final Set<Integer> activeSetupIds )
	{
		final ViewerState state = bdv.getViewer().state();

		final List< SourceAndConverter< ? > > active = new ArrayList<>();
		synchronized ( state )
		{
			BDVUtils.forEachAbstractSpimSource(
					state.getSources(),
					( soc, source ) -> {
						if ( activeSetupIds.contains( source.getSetupId() ) )
							active.add( soc );
					} );
		}

		final List< SourceAndConverter< ? > > inactive = new ArrayList<>( state.getSources() );
		inactive.removeAll( active );

		state.setSourcesActive( inactive, false );
		state.setSourcesActive( active, true );

		// IOFunctions.println( "PERF: [setVisibleSourcesBatch] deactivated " + inactive.size() + ", activated " + active.size() + " sources" );
	}

	// ==================== Source Coloring ====================

	/**
	 * Sets all sources to white color using the proper ConverterSetups API.
	 *
	 * @param bdv the BigDataViewer instance
	 */
	public static void whiteSourcesBatch( final BigDataViewer bdv )
	{
		final long start = System.currentTimeMillis();
		final ARGBType white = new ARGBType( ARGBType.rgba( 255, 255, 255, 255 ) );
		final bdv.viewer.ConverterSetups converterSetups = bdv.getViewerFrame().getConverterSetups();
		final ViewerState state = bdv.getViewer().state();

		int count = 0;
		synchronized ( state )
		{
			for ( final SourceAndConverter< ? > soc : state.getSources() )
			{
				final ConverterSetup cs = converterSetups.getConverterSetup( soc );
				if ( cs != null )
				{
					cs.setColor( white );
					count++;
				}
			}
		}
		// IOFunctions.println( "PERF: [whiteSourcesBatch] set " + count + " sources to white in " + (System.currentTimeMillis() - start) + " ms" );
	}

	/**
	 * Colors sources sequentially using the proper ConverterSetups API.
	 *
	 * @param bdv the BigDataViewer instance
	 * @param colorOffset offset into the color stream
	 */
	public static void colorSourcesBatch( final BigDataViewer bdv, final long colorOffset )
	{
		final long start = System.currentTimeMillis();
		final bdv.viewer.ConverterSetups converterSetups = bdv.getViewerFrame().getConverterSetups();
		final ViewerState state = bdv.getViewer().state();

		int count = 0;
		synchronized ( state )
		{
			for ( final SourceAndConverter< ? > soc : state.getSources() )
			{
				final ConverterSetup cs = converterSetups.getConverterSetup( soc );
				if ( cs != null )
				{
					cs.setColor( new ARGBType( ColorStream.get( count + colorOffset ) ) );
					count++;
				}
			}
		}
		// IOFunctions.println( "PERF: [colorSourcesBatch] colored " + count + " sources in " + (System.currentTimeMillis() - start) + " ms" );
	}

	/**
	 * Colors sources based on grouping factors (Tile, Illumination, etc.).
	 * Each unique combination of grouping factors gets a distinct color.
	 *
	 * @param bdv the BigDataViewer instance
	 * @param data the SpimData
	 * @param groupingFactors the Entity classes to group by
	 * @param colorOffset offset into the color stream
	 */
	public static void colorByFactors( final BigDataViewer bdv, final AbstractSpimData< ? > data,
			final Set<Class<? extends Entity>> groupingFactors, final long colorOffset )
	{
		final long start = System.currentTimeMillis();

		List<BasicViewDescription< ? > > vds = new ArrayList<>();
		Map<BasicViewDescription< ? >, ConverterSetup> vdToCs = new HashMap<>();

		final bdv.viewer.ConverterSetups converterSetups = bdv.getViewerFrame().getConverterSetups();
		final ViewerState state = bdv.getViewer().state();

		synchronized ( state )
		{
			final Integer timepointId = data.getSequenceDescription().getTimePoints().getTimePointsOrdered().get( state.getCurrentTimepoint() ).getId();
			for ( final SourceAndConverter< ? > soc : state.getSources() )
			{
				final ConverterSetup cs = converterSetups.getConverterSetup( soc );
				if ( cs != null )
				{
					final BasicViewDescription< ? > vd = data.getSequenceDescription().getViewDescriptions().get( new ViewId( timepointId, cs.getSetupId() ) );
					if ( vd != null )
					{
						vds.add( vd );
						vdToCs.put( vd, cs );
					}
				}
			}
		}

		List< Group< BasicViewDescription< ? > > > vdGroups = Group.combineBy( vds, groupingFactors );

		if (vdGroups.size() < 1)
			return;

		if (vdGroups.size() == 1)
		{
			whiteSourcesBatch( bdv );
			return;
		}

		List<ArrayList<ConverterSetup>> groups = new ArrayList<>();

		for (Group< BasicViewDescription< ? > > lVd : vdGroups)
		{
			ArrayList< ConverterSetup > lCs = new ArrayList<>();
			for (BasicViewDescription< ? > vd : lVd)
				lCs.add( vdToCs.get( vd ) );
			groups.add( lCs );
		}

		Iterator< ARGBType > colorIt = ColorStream.iterator();
		for (int i = 0; i < colorOffset; ++i)
			colorIt.next();

		int colorCount = 0;
		for (ArrayList< ConverterSetup > csg : groups)
		{
			ARGBType color = colorIt.next();
			for (ConverterSetup cs : csg)
			{
				cs.setColor( color );
				colorCount++;
			}
		}

		// IOFunctions.println( "PERF: [colorByFactors] colored " + colorCount + " sources in " + groups.size() + " groups in " + (System.currentTimeMillis() - start) + " ms" );
	}

	/**
	 * Sets all sources to white using legacy API.
	 * @deprecated Use {@link #whiteSourcesBatch(BigDataViewer)} instead
	 */
	@Deprecated
	public static void whiteSources( final List< ConverterSetup > cs )
	{
		sameColorSources( cs, 255, 255, 255, 255 );
	}

	/**
	 * Sets all sources to the same color using legacy API.
	 * @deprecated Use the batch methods instead
	 */
	@Deprecated
	public static void sameColorSources( final List< ConverterSetup > cs, final int r, final int g, final int b, final int a )
	{
		final ARGBType color = new ARGBType( ARGBType.rgba( r, g, b, a ) );
		cs.forEach( c -> c.setColor( color ) );
	}

	// ==================== Transform Control ====================

	/**
	 * Resets manual transformations for all views to identity.
	 *
	 * @param bdv the BigDataViewer instance
	 */
	public static void resetBDVManualTransformations( final BigDataViewer bdv )
	{
		if ( bdv == null )
			return;

		final AffineTransform3D identity = new AffineTransform3D();
		final ViewerState state = bdv.getViewer().state();
		synchronized ( state )
		{
			BDVUtils.forEachTransformedSource(
					state.getSources(),
					( soc, source ) -> {
						source.setFixedTransform( identity );
						source.setIncrementalTransform( identity );
					} );
		}
	}

	// ==================== Index Utilities ====================

	/**
	 * Gets the BDV display index for a TimePoint.
	 *
	 * @param t the TimePoint
	 * @param data the SpimData
	 * @return the display index in BDV
	 */
	public static int getBDVTimePointIndex( final TimePoint t, final AbstractSpimData< ? > data )
	{
		final List< TimePoint > list = data.getSequenceDescription().getTimePoints().getTimePointsOrdered();

		for ( int i = 0; i < list.size(); ++i )
			if ( list.get( i ).getId() == t.getId() )
				return i;

		return 0;
	}

	/**
	 * Gets the BDV display index for a ViewSetup.
	 *
	 * @param vs the ViewSetup
	 * @param data the SpimData
	 * @return the display index in BDV
	 */
	public static int getBDVSourceIndex( final BasicViewSetup vs, final AbstractSpimData< ? > data )
	{
		final List< ? extends BasicViewSetup > list = data.getSequenceDescription().getViewSetupsOrdered();

		for ( int i = 0; i < list.size(); ++i )
			if ( list.get( i ).getId() == vs.getId() )
				return i;

		return 0;
	}
}
