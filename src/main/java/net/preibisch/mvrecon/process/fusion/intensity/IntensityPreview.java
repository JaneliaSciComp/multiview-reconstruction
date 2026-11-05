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
package net.preibisch.mvrecon.process.fusion.intensity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;


import bdv.ViewerImgLoader;
import bdv.ViewerSetupImgLoader;
import bdv.cache.CacheControl;
import bdv.cache.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.ImgLoaderHint;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.AbstractInterval;
import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.ReadOnlyCachedCellImgFactory;
import net.imglib2.cache.img.ReadOnlyCachedCellImgOptions;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.img.cell.AbstractCellImg;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.logic.BoolType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.util.Cast;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;

/**
 * Switchable intensity-correction preview for the explorer's BigDataViewer.
 * <p>
 * BigDataViewer's sources are built while the dataset's img loader is {@link #install installed} as a wrapper that
 * hands out images which, every time they are accessed, show either the raw data or, while the preview is on
 * ({@link #refresh}), the data corrected with the coefficients stored in the dataset. Every voxel is then shown from the closest view only, as fused with
 * {@code CLOSEST_PIXEL_WINS} (anisotropy from the metadata, as with {@code --preserveAnisotropy}). The sources
 * themselves stay what the explorer expects, so coloring, selection and overlays keep working, and toggling only
 * needs a repaint. One preview per dataset.
 */
public class IntensityPreview
{
	private static final Map< AbstractSpimData< ? >, IntensityPreview > previews = Collections.synchronizedMap( new WeakHashMap<>() );

	/** @return the preview of {@code spimData}, or null if BigDataViewer was never opened for it */
	public static IntensityPreview get( final AbstractSpimData< ? > spimData )
	{
		return previews.get( spimData );
	}

	private static IntensityPreview of( final AbstractSpimData< ? > spimData )
	{
		return previews.computeIfAbsent( spimData, k -> new IntensityPreview() );
	}

	/**
	 * Makes the dataset's img loader the preview wrapper, so that BigDataViewer sources built from {@code spimData}
	 * until {@link #restore} can show the preview.
	 *
	 * @return the img loader to {@link #restore}
	 */
	public static BasicImgLoader install( final AbstractSpimData< ? > spimData )
	{
		final AbstractSequenceDescription< ?, ?, ? > seq = spimData.getSequenceDescription();
		final BasicImgLoader original = seq.getImgLoader();
		if ( original instanceof ViewerImgLoader && !( original instanceof PreviewImgLoader ) )
			seq.setImgLoader( Cast.unchecked( of( spimData ).wrapper( ( ViewerImgLoader ) original ) ) );
		return original;
	}

	/** Puts back the img loader {@link #install} replaced; the dataset must not keep the wrapper (it is saved). */
	public static void restore( final AbstractSpimData< ? > spimData, final BasicImgLoader original )
	{
		spimData.getSequenceDescription().setImgLoader( Cast.unchecked( original ) );
	}

	private PreviewImgLoader wrapper;

	private SharedQueue queue;

	private volatile Config config; // null if there is nothing to show

	private volatile boolean enabled;

	private synchronized PreviewImgLoader wrapper( final ViewerImgLoader original )
	{
		if ( wrapper == null || wrapper.delegate != original )
			wrapper = new PreviewImgLoader( original );
		return wrapper;
	}

	private synchronized SharedQueue queue()
	{
		if ( queue == null )
			queue = new SharedQueue( Math.max( 1, Runtime.getRuntime().availableProcessors() - 1 ) );
		return queue;
	}

	/**
	 * Shows the coefficients stored in {@code spimData} (computed or imported), with the current registrations; the
	 * preview is on if there are any.
	 *
	 * @param onlyAboveThreshold correct only voxels above the threshold stored with each view's coefficients
	 * @return whether the preview is on
	 */
	public static boolean refresh( final SpimData2 spimData, final boolean onlyAboveThreshold )
	{
		final Map< ViewId, Coefficients > coefficients = spimData.getIntensityAdjustments().getCoefficients( onlyAboveThreshold );
		final IntensityPreview preview = of( spimData );
		synchronized ( preview )
		{
			preview.config = coefficients.isEmpty() ? null : new Config( spimData, coefficients );
			preview.enabled = preview.config != null;
			return preview.enabled;
		}
	}

	public boolean isEnabled()
	{
		return enabled;
	}

	public synchronized void disable()
	{
		enabled = false;
	}

	/** null when the preview is off */
	private RandomAccessibleInterval< ? > corrected( final int setupId, final int timepointId, final int level, final boolean isVolatile, final ViewerSetupImgLoader< ?, ? > raw )
	{
		final Config c = config;
		if ( !enabled || c == null )
			return null;
		final RandomAccessibleInterval< ? > img = c.images.computeIfAbsent( List.of( setupId, timepointId, level, false ),
				k -> c.correct( setupId, timepointId, level, Cast.unchecked( raw ) ) );
		if ( !isVolatile )
			return img;
		return c.images.computeIfAbsent( List.of( setupId, timepointId, level, true ), k -> {
			final SharedQueue q = queue();
			q.ensureNumPriorities( raw.numMipmapLevels() );
			return VolatileViews.wrapAsVolatile( img, q, new CacheHints( LoadingStrategy.BUDGETED, raw.numMipmapLevels() - 1 - level, false ) );
		} );
	}

	/** What the preview shows: coefficients, where the views are, and the corrected images built from them. */
	private static final class Config
	{
		final SpimData2 spimData;
		final List< Integer > timepointIds = new ArrayList<>();
		final Map< Integer, Integer > setupIndex = new HashMap<>();
		final Map< ViewId, Coefficients > coefficients;
		final ClosestViewSource.Layout layout;
		final Map< List< Object >, RandomAccessibleInterval< ? > > images = new ConcurrentHashMap<>();

		Config( final SpimData2 spimData, final Map< ViewId, Coefficients > coefficients )
		{
			this.spimData = spimData;
			this.coefficients = coefficients;
			final List< Integer > setupIds = new ArrayList<>();
			spimData.getSequenceDescription().getViewSetupsOrdered().forEach( s -> setupIds.add( s.getId() ) );
			spimData.getSequenceDescription().getTimePoints().getTimePointsOrdered().forEach( t -> timepointIds.add( t.getId() ) );
			for ( int i = 0; i < setupIds.size(); ++i )
				setupIndex.put( setupIds.get( i ), i );
			final double anisotropy = TransformationTools.getAverageAnisotropyFactor( spimData, spimData.getSequenceDescription().getViewDescriptions().values() );
			layout = new ClosestViewSource.Layout( spimData, setupIds, timepointIds, anisotropy );
		}

		/**
		 * The corrected image, with every voxel where another view's center is closer set to zero (BigDataViewer adds
		 * up the sources, so each voxel shows one view).
		 * <p>
		 * ponytail: decides at voxel centers, so at coarse levels a seam can be off by a voxel; sampling at the
		 * rendered position (as ClosestViewSource does with masks) needs source-level wrapping.
		 */
		< T extends NativeType< T > & NumericType< T > > RandomAccessibleInterval< T > correct( final int setupId, final int timepointId, final int level, final ViewerSetupImgLoader< T, ? > raw )
		{
			final RandomAccessibleInterval< T > image = raw.getImage( timepointId, level );
			final int t = timepointIds.indexOf( timepointId );
			final Coefficients stored = this.coefficients.get( new ViewId( timepointId, setupId ) );
			final ViewRegistration reg = spimData.getViewRegistrations().getViewRegistration( new ViewId( timepointId, setupId ) );
			reg.updateModel();
			final AffineTransform3D sourceToGlobal = reg.getModel().copy();
			sourceToGlobal.concatenate( raw.getMipmapTransforms()[ level ] );

			// coefficients matched on unbleached intensities apply to unbleached intensities: undo the bleaching of
			// each voxel by the earlier views first
			final Coefficients coefficients = stored == null
					? IntensityCorrectionViewer.IDENTITY
					: stored.withBleaching( Bleaching.of( spimData, new ViewId( timepointId, setupId ), sourceToGlobal, stored.bleachFactor() ) );
			final ClosestViewSource.Geometry geometry = layout.geometry( t );
			final int index = setupIndex.get( setupId );

			final BlockSupplier< T > supplier = BlockSupplier.of( image )
					.andThen( FastLinearIntensityMap.linearIntensityMap( coefficients, image ) )
					.threadSafe();

			final CellLoader< T > loader = cell -> {
				supplier.copy( cell, cell.getStorageArray() );
				final BiConsumer< RealLocalizable, BoolType > wins = geometry.closest( index, sourceToGlobal );
				final BoolType w = new BoolType();
				for ( final Cursor< T > c = cell.localizingCursor(); c.hasNext(); )
				{
					final T v = c.next();
					wins.accept( c, w );
					if ( !w.get() )
						v.setZero();
				}
			};

			final int[] cellDimensions = image instanceof AbstractCellImg
					? ( ( AbstractCellImg< ?, ?, ?, ? > ) image ).getCellGrid().getCellDimensions()
					: new int[] { 64, 64, 64 };
			return new ReadOnlyCachedCellImgFactory().create( image.dimensionsAsLongArray(), image.getType().createVariable(), loader,
					ReadOnlyCachedCellImgOptions.options().cellDimensions( cellDimensions ) );
		}
	}

	/** Passes everything through, except that images switch to the corrected version while the preview is on. */
	private final class PreviewImgLoader implements ViewerImgLoader
	{
		final ViewerImgLoader delegate;

		private final Map< Integer, ViewerSetupImgLoader< ?, ? > > setupImgLoaders = new ConcurrentHashMap<>();

		private final CacheControl.CacheControls cacheControl = new CacheControl.CacheControls();

		PreviewImgLoader( final ViewerImgLoader delegate )
		{
			this.delegate = delegate;
			cacheControl.addCacheControl( delegate.getCacheControl() );
			// BigDataViewer must pump the corrected images' queue too, or cells that missed their first load stay empty
			cacheControl.addCacheControl( queue() );
		}

		@Override
		public ViewerSetupImgLoader< ?, ? > getSetupImgLoader( final int setupId )
		{
			return setupImgLoaders.computeIfAbsent( setupId, id -> new PreviewSetupImgLoader<>( id, delegate.getSetupImgLoader( id ) ) );
		}

		@Override
		public CacheControl getCacheControl()
		{
			return cacheControl;
		}
	}

	private final class PreviewSetupImgLoader< T, V extends net.imglib2.Volatile< T > > implements ViewerSetupImgLoader< T, V >
	{
		private final int setupId;

		private final ViewerSetupImgLoader< T, V > delegate;

		PreviewSetupImgLoader( final int setupId, final ViewerSetupImgLoader< T, V > delegate )
		{
			this.setupId = setupId;
			this.delegate = Cast.unchecked( delegate );
		}

		@Override
		public RandomAccessibleInterval< T > getImage( final int timepointId, final int level, final ImgLoaderHint... hints )
		{
			return new SwitchingImage<>( delegate.getImage( timepointId, level, hints ),
					() -> Cast.unchecked( corrected( setupId, timepointId, level, false, delegate ) ) );
		}

		@Override
		public RandomAccessibleInterval< V > getVolatileImage( final int timepointId, final int level, final ImgLoaderHint... hints )
		{
			return new SwitchingImage<>( delegate.getVolatileImage( timepointId, level, hints ),
					() -> Cast.unchecked( corrected( setupId, timepointId, level, true, delegate ) ) );
		}

		@Override
		public RandomAccessibleInterval< T > getImage( final int timepointId, final ImgLoaderHint... hints )
		{
			return getImage( timepointId, 0, hints );
		}

		@Override
		public T getImageType()
		{
			return delegate.getImageType();
		}

		@Override
		public V getVolatileImageType()
		{
			return delegate.getVolatileImageType();
		}

		@Override
		public double[][] getMipmapResolutions()
		{
			return delegate.getMipmapResolutions();
		}

		@Override
		public AffineTransform3D[] getMipmapTransforms()
		{
			return delegate.getMipmapTransforms();
		}

		@Override
		public int numMipmapLevels()
		{
			return delegate.numMipmapLevels();
		}
	}

	/**
	 * The raw image, or the corrected one while the preview is on, decided whenever an accessor is created
	 * (BigDataViewer creates new ones for every frame, so a toggle needs only a repaint).
	 */
	private static final class SwitchingImage< T > extends AbstractInterval implements RandomAccessibleInterval< T >
	{
		private final RandomAccessibleInterval< T > raw;

		private final Supplier< RandomAccessibleInterval< T > > corrected;

		SwitchingImage( final RandomAccessibleInterval< T > raw, final Supplier< RandomAccessibleInterval< T > > corrected )
		{
			super( raw );
			this.raw = raw;
			this.corrected = corrected;
		}

		private RandomAccessibleInterval< T > current()
		{
			final RandomAccessibleInterval< T > c = corrected.get();
			return c == null ? raw : c;
		}

		@Override
		public RandomAccess< T > randomAccess()
		{
			return current().randomAccess();
		}

		@Override
		public RandomAccess< T > randomAccess( final Interval interval )
		{
			return current().randomAccess( interval );
		}

		@Override
		public T getType()
		{
			return raw.getType();
		}
	}
}
