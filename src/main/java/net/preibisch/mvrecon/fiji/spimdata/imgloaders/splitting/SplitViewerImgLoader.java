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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import bdv.ViewerImgLoader;
import bdv.ViewerSetupImgLoader;
import bdv.cache.CacheControl;
import bdv.img.n5.N5ImageLoader;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.SequenceDescription;
import net.imglib2.Dimensions;
import net.imglib2.Interval;
import net.imglib2.util.Cast;

public class SplitViewerImgLoader implements ViewerImgLoader, MultiResolutionImgLoader
{
	final ViewerImgLoader underlyingImgLoader;

	/**
	 * Maps the newly assigned ViewSetupId to the old ViewSetupId
	 */
	final Map< Integer, Integer > new2oldSetupId;

	/**
	 * Maps the newly assigned ViewSetupId to Interval inside the old ViewSetupId
	 */
	final Map< Integer, Interval > newSetupId2Interval;

	/**
	 * The old SequenceDescription is be needed for the underlying imgloader
	 */
	final SequenceDescription oldSD;

	/**
	 * Remembers instances of SplitSetupImgLoader. ConcurrentHashMap so the
	 * per-split-id lookup doesn't serialise through a single monitor (with
	 * 100k split-ids this is on the BDV render hot path).
	 */
	private final ConcurrentHashMap< Integer, SplitViewerSetupImgLoader<?,?> > splitSetupImgLoaders;

	/**
	 * Per old-setup-id, the underlying image's dimensions at every mipmap level
	 * ({@code dims[level][dim]}). Computed lazily on first access via
	 * {@link ViewerSetupImgLoader#getImageSize(int, int)} on the underlying loader,
	 * then shared across all splits of the same underlying view (typically ~100
	 * splits share one entry). Used to eagerly clamp {@code scaledIntervals[]}
	 * inside {@link SplitViewerSetupImgLoader}'s constructor.
	 */
	private final ConcurrentHashMap< Integer, long[][] > oldSetupLevelDims;

	public SplitViewerImgLoader(
			final ViewerImgLoader underlyingImgLoader,
			final Map< Integer, Integer > new2oldSetupId,
			final Map< Integer, Interval > newSetupId2Interval,
			final SequenceDescription oldSD )
	{
		this.underlyingImgLoader = underlyingImgLoader;
		this.new2oldSetupId = new2oldSetupId;
		this.newSetupId2Interval = newSetupId2Interval;
		this.oldSD = oldSD;
		this.splitSetupImgLoaders = new ConcurrentHashMap<>();
		this.oldSetupLevelDims = new ConcurrentHashMap<>();
	}

	public Map< Integer, Integer > new2oldSetupId() { return new2oldSetupId; }
	public Map< Integer, Interval > newSetupId2Interval() { return newSetupId2Interval; }
	public SequenceDescription underlyingSequenceDescription() { return oldSD; }

	@Override
	public SplitViewerSetupImgLoader<?,?> getSetupImgLoader( final int setupId )
	{
		return getSplitViewerSetupImgLoader( underlyingImgLoader, new2oldSetupId.get( setupId ), setupId, newSetupId2Interval.get( setupId ) );
	}

	private final SplitViewerSetupImgLoader<?,?> getSplitViewerSetupImgLoader( final ViewerImgLoader underlyingImgLoader, final int oldSetupId, final int newSetupId, final Interval interval )
	{
		return splitSetupImgLoaders.computeIfAbsent( newSetupId, id ->
				createNewSetupImgLoader(
						(ViewerSetupImgLoader<?,?>)underlyingImgLoader.getSetupImgLoader( oldSetupId ),
						interval,
						getOldSetupLevelDims( oldSetupId ) ) );
	}

	private final SplitViewerSetupImgLoader<?,?> createNewSetupImgLoader(
			final ViewerSetupImgLoader<?,?> setupImgLoader,
			final Interval interval,
			final long[][] levelDims )
	{
		return new SplitViewerSetupImgLoader<>( Cast.unchecked( setupImgLoader ), interval, levelDims );
	}

	/**
	 * Lazy + cached: per old-setup, query the underlying loader once for the
	 * level dimensions and stash them. All splits sharing this old-setup-id then
	 * reuse the same {@code long[level][dim]} array.
	 *
	 * Uses the first ordered timepoint of the underlying SequenceDescription;
	 * assumes per-setup level dims are timepoint-invariant (true for N5 / OME-Zarr).
	 */
	private long[][] getOldSetupLevelDims( final int oldSetupId )
	{
		return oldSetupLevelDims.computeIfAbsent( oldSetupId, this::computeOldSetupLevelDims );
	}

	private long[][] computeOldSetupLevelDims( final int oldSetupId )
	{
		final ViewerSetupImgLoader<?,?> sil = (ViewerSetupImgLoader<?,?>) underlyingImgLoader.getSetupImgLoader( oldSetupId );
		final MultiResolutionSetupImgLoader<?> mrsil = (MultiResolutionSetupImgLoader<?>) sil;
		final int firstTp = oldSD.getTimePoints().getTimePointsOrdered().get( 0 ).getId();
		final int levels = sil.numMipmapLevels();
		final long[][] dims = new long[ levels ][];
		for ( int level = 0; level < levels; ++level )
		{
			final Dimensions d = mrsil.getImageSize( firstTp, level );
			if ( d == null )
			{
				// Underlying loader has no size info for this level; use sentinel
				// so clamping in SplitViewerSetupImgLoader is skipped (max is unreachable).
				final long[] arr = new long[ 3 ];
				java.util.Arrays.fill( arr, Long.MAX_VALUE );
				dims[ level ] = arr;
			}
			else
			{
				final long[] arr = new long[ d.numDimensions() ];
				d.dimensions( arr );
				dims[ level ] = arr;
			}
		}
		return dims;
	}

	public ViewerImgLoader getUnderlyingImgLoader()
	{
		return underlyingImgLoader;
	}

	public Future< Void > prefetch( final int parallelism )
	{
		if ( N5ImageLoader.class.isInstance( underlyingImgLoader ) )
			return ( (N5ImageLoader)underlyingImgLoader).prefetch( parallelism );
		else if ( SplitViewerImgLoader.class.isInstance( underlyingImgLoader ) )
			return ( (SplitViewerImgLoader) underlyingImgLoader ).prefetch( parallelism );
		else
			return null;
	}

	@Override
	public void setNumFetcherThreads( final int n )
	{
		underlyingImgLoader.setNumFetcherThreads( n );
	}

	@Override
	public CacheControl getCacheControl()
	{
		// Delegate to the underlying loader. Splits don't have their own cells —
		// every getVolatileImage() call returns a CachedCellImg backed by the
		// underlying loader's VolatileGlobalCellCache. Returning a parallel cache
		// here (as the previous implementation did) just spawned an idle fetcher
		// pool and made BDV's cache pumping/invalidation aim at the wrong cache.
		return underlyingImgLoader.getCacheControl();
	}
}
