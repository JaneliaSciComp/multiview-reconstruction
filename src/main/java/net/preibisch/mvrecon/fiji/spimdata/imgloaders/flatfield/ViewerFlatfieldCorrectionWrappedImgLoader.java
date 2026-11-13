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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import bdv.ViewerImgLoader;
import bdv.ViewerSetupImgLoader;
import bdv.cache.CacheControl;
import bdv.cache.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import mpicbg.spim.data.generic.sequence.ImgLoaderHint;
import mpicbg.spim.data.generic.sequence.ImgLoaderHints;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.converter.RealTypeConverters;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.process.fusion.intensity.IntensityPreview;

/**
 * Flatfield correction wrapper for ViewerImgLoader.
 *
 * This class wraps a ViewerImgLoader and applies flatfield (bright/dark image) correction
 * on-the-fly. It implements both ViewerImgLoader and MultiResolutionImgLoader interfaces,
 * making it compatible with BigDataViewer's caching and async loading infrastructure.
 *
 * The correction formula is:
 *   corrected = (source - dark) * mean(bright - dark) / (bright - dark)
 *
 * Usage in decorator chain:
 *   N5ImageLoader (ViewerImgLoader)
 *       -> ViewerFlatfieldCorrectionWrappedImgLoader (ViewerImgLoader)
 *           -> SplitViewerImgLoader (ViewerImgLoader)
 */
public class ViewerFlatfieldCorrectionWrappedImgLoader
		implements ViewerImgLoader, MultiResolutionImgLoader {

	private final ViewerImgLoader wrappedImgLoader;
	private boolean active;
	/** kept for the XML attribute only: the corrected image caches its blocks itself */
	private boolean cacheResult;

	/** Helper for loading flatfield images */
	private final FlatfieldImageLoader imageLoader;

	/** Fetches the corrected cells for getVolatileImage(); created on first use */
	private volatile SharedQueue queue;
	private int numFetcherThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

	/** Downsampled bright/dark images for each mipmap level */
	private final Map<Pair<FlatfieldImageInfo, List<Integer>>, RandomAccessibleInterval<FloatType>> dsRaiMap;

	public ViewerFlatfieldCorrectionWrappedImgLoader(final ViewerImgLoader wrappedImgLoader) {
		this(wrappedImgLoader, true);
	}

	public ViewerFlatfieldCorrectionWrappedImgLoader(final ViewerImgLoader wrappedImgLoader, final boolean cacheResult) {
		this.wrappedImgLoader = wrappedImgLoader;
		this.active = true;
		this.cacheResult = cacheResult;
		this.imageLoader = new FlatfieldImageLoader();
		this.dsRaiMap = new HashMap<>();
	}

	// ========== Configuration methods ==========

	public ViewerImgLoader getWrappedImgLoader() {
		return wrappedImgLoader;
	}

	public void setActive(final boolean active) {
		this.active = active;
	}

	public boolean isActive() {
		return active;
	}

	public boolean isCached() {
		return cacheResult;
	}

	public void setCached(final boolean cached) {
		this.cacheResult = cached;
	}

	public void setBrightImage(final ViewId vId, final FlatfieldImageInfo info) {
		imageLoader.setBrightImage(vId, info);
	}

	public void setDarkImage(final ViewId vId, final FlatfieldImageInfo info) {
		imageLoader.setDarkImage(vId, info);
	}

	/**
	 * Get the info map for bright/dark images per view.
	 * @return map from ViewId to (brightInfo, darkInfo) pair
	 */
	public Map<ViewId, Pair<FlatfieldImageInfo, FlatfieldImageInfo>> getInfoMap() {
		return imageLoader.getInfoMap();
	}

	// ========== ViewerImgLoader interface ==========

	@Override
	public ViewerFlatfieldCorrectionWrappedSetupImgLoader<?, ?> getSetupImgLoader(final int setupId) {
		return new ViewerFlatfieldCorrectionWrappedSetupImgLoader<>(setupId);
	}

	@Override
	public CacheControl getCacheControl() {
		final CacheControl wrapped = wrappedImgLoader.getCacheControl();
		return () -> {
			wrapped.prepareNextFrame();
			if (queue != null)
				queue.prepareNextFrame();
		};
	}

	@Override
	public void setNumFetcherThreads(final int n) {
		wrappedImgLoader.setNumFetcherThreads(n);
		numFetcherThreads = n;
	}

	private synchronized SharedQueue queue(final int numLevels) {
		if (queue == null)
			queue = new SharedQueue(numFetcherThreads, numLevels);
		else
			queue.ensureNumPriorities(numLevels);
		return queue;
	}

	// ========== Image loading helpers ==========

	protected RandomAccessibleInterval<FloatType> getBrightImg(final ViewId vId) {
		return imageLoader.getBrightImg(vId);
	}

	protected RandomAccessibleInterval<FloatType> getDarkImg(final ViewId vId) {
		return imageLoader.getDarkImg(vId);
	}

	public RandomAccessibleInterval<FloatType> getOrCreateBrightImgDownsampled(
			final ViewId vId,
			final int[] downsamplingFactors
	) {
		return getOrCreateDownsampledImg(vId, downsamplingFactors, Pair::getA, this::getBrightImg);
	}

	public RandomAccessibleInterval<FloatType> getOrCreateDarkImgDownsampled(
			final ViewId vId,
			final int[] downsamplingFactors
	) {
		return getOrCreateDownsampledImg(vId, downsamplingFactors, Pair::getB, this::getDarkImg);
	}

	/**
	 * Generic method to get a downsampled image or do downsampling on the fly. The bright image
	 * is stored in the A element of the pair, the dark image in B.
	 */
	private synchronized RandomAccessibleInterval<FloatType> getOrCreateDownsampledImg(
			ViewId vId,
			int[] downsamplingFactors,
			Function<Pair<FlatfieldImageInfo, FlatfieldImageInfo>, FlatfieldImageInfo> infoSelector,
			Function<ViewId, RandomAccessibleInterval<FloatType>> imgGetter
	) {
		// no flat-field configured for this view (or not this image) -> no correction
		final Pair<FlatfieldImageInfo, FlatfieldImageInfo> infos = imageLoader.getInfoMap().get(vId);
		if (infos == null || infoSelector.apply(infos) == null)
			return null;

		// Convert to a list here to have a proper hash code for the map key
		List<Integer> dsFactorList = Arrays.stream(downsamplingFactors).boxed().collect(Collectors.toList());
		final ValuePair<FlatfieldImageInfo, List<Integer>> key = new ValuePair<>(infoSelector.apply(infos), dsFactorList);

		if (!dsRaiMap.containsKey(key)) {
			final RandomAccessibleInterval<FloatType> img = imgGetter.apply(vId);

			if (img == null)
				return null;

			final RandomAccessibleInterval<FloatType> downsampled =
					MultiResolutionFlatfieldCorrectionWrappedImgLoader.downsampleHDF5(img, downsamplingFactors);
			dsRaiMap.put(key, downsampled);
		}

		return dsRaiMap.get(key);
	}

	// ========== Inner class: ViewerSetupImgLoader implementation ==========

	public class ViewerFlatfieldCorrectionWrappedSetupImgLoader<T extends RealType<T> & NativeType<T>, V extends Volatile<T> & RealType<V> & NativeType<V>>
			implements ViewerSetupImgLoader<T, V>, MultiResolutionSetupImgLoader<T> {
		private final int setupId;

		ViewerFlatfieldCorrectionWrappedSetupImgLoader(final int setupId) {
			this.setupId = setupId;
		}

		@SuppressWarnings("unchecked")
		private ViewerSetupImgLoader<T, V> getUnderlyingViewerSetupImgLoader() {
			return (ViewerSetupImgLoader<T, V>) wrappedImgLoader.getSetupImgLoader(setupId);
		}

		@SuppressWarnings("unchecked")
		private MultiResolutionSetupImgLoader<T> getUnderlyingMultiResSetupImgLoader() {
			// The wrapped ViewerImgLoader should also be a MultiResolutionImgLoader
			return (MultiResolutionSetupImgLoader<T>) ((MultiResolutionImgLoader) wrappedImgLoader).getSetupImgLoader(setupId);
		}

		// ========== Regular image access ==========

		@Override
		public RandomAccessibleInterval<T> getImage(final int timepointId, final ImgLoaderHint... hints) {
			return getImage(timepointId, 0, hints);
		}

		@Override
		public RandomAccessibleInterval<T> getImage(final int timepointId, final int level, final ImgLoaderHint... hints) {
			if (!active)
				return getUnderlyingViewerSetupImgLoader().getImage(timepointId, level, hints);
			return correctedImage(timepointId, level, hints);
		}

		private RandomAccessibleInterval<T> correctedImage(final int timepointId, final int level, final ImgLoaderHint... hints) {
			final ViewerSetupImgLoader<T, V> viewerSetupIL = getUnderlyingViewerSetupImgLoader();
			final MultiResolutionSetupImgLoader<T> multiResSetupIL = getUnderlyingMultiResSetupImgLoader();

			final int n = multiResSetupIL.getImageSize(timepointId).numDimensions();

			// Calculate downsampling factors for this mipmap level
			final int[] dsFactors = new int[n];
			final double[] dsD = viewerSetupIL.getMipmapResolutions()[level];
			for (int d = 0; d < n; d++)
				dsFactors[d] = (int) dsD[d];
			// Don't downsample z for 2D correction images
			dsFactors[n - 1] = 1;

			RandomAccessibleInterval<T> rai = FlatFieldCorrectedRandomAccessibleIntervals.create(
					viewerSetupIL.getImage(timepointId, level, hints),
					getOrCreateBrightImgDownsampled(new ViewId(timepointId, setupId), dsFactors),
					getOrCreateDarkImgDownsampled(new ViewId(timepointId, setupId), dsFactors));

			// Handle LOAD_COMPLETELY hint
			boolean loadCompletelyRequested = false;
			for (final ImgLoaderHint hint : hints)
                if (hint == ImgLoaderHints.LOAD_COMPLETELY) {
                    loadCompletelyRequested = true;
                    break;
                }

			if (loadCompletelyRequested) {
				long numPx = 1;
				for (int d = 0; d < rai.numDimensions(); d++)
					numPx *= rai.dimension(d);

				final ImgFactory<T> imgFactory;
				if (Math.log(numPx) / Math.log(2) < 31) {
					imgFactory = new ArrayImgFactory<>(getImageType());
				} else {
					imgFactory = new CellImgFactory<>(getImageType());
				}

				final Img<T> loadedImg = imgFactory.create(rai);
				RealTypeConverters.copyFromTo(Views.extendZero(rai), loadedImg);

				rai = loadedImg;
			}

			return rai;
		}

		// ========== Volatile image access ==========

		@Override
		public RandomAccessibleInterval<V> getVolatileImage(final int timepointId, final int level, final ImgLoaderHint... hints) {
			// The correction can't be computed from volatile cells (they may not be loaded yet,
			// and the block algorithms don't support volatile types), so BDV gets the corrected
			// non-volatile image, computed on this loader's fetcher threads.
			// The image follows setActive() whenever an accessor is created, so that BigDataViewer, which keeps
			// the image, shows a toggle after a repaint (also below a split img loader).
			final Supplier<RandomAccessibleInterval<V>> corrected = new Supplier<RandomAccessibleInterval<V>>() {
				private RandomAccessibleInterval<V> img;

				@Override
				public synchronized RandomAccessibleInterval<V> get() {
					if (!active)
						return null;
					if (img == null) {
						final int maxLevel = numMipmapLevels() - 1;
						final CacheHints cacheHints = new CacheHints(LoadingStrategy.BUDGETED, maxLevel - level, false);
						img = VolatileViews.wrapAsVolatile(correctedImage(timepointId, level), queue(maxLevel + 1), cacheHints);
					}
					return img;
				}
			};
			return new IntensityPreview.SwitchingImage<>(
					getUnderlyingViewerSetupImgLoader().getVolatileImage(timepointId, level, hints), corrected);
		}

		// ========== Float image access ==========

		@Override
		public RandomAccessibleInterval<FloatType> getFloatImage(final int timepointId, final boolean normalize, final ImgLoaderHint... hints) {
			return getFloatImage(timepointId, 0, normalize, hints);
		}

		@Override
		public RandomAccessibleInterval<FloatType> getFloatImage(final int timepointId, final int level, final boolean normalize, final ImgLoaderHint... hints) {
			final ViewerSetupImgLoader<T, V> viewerSetupIL = getUnderlyingViewerSetupImgLoader();
			final MultiResolutionSetupImgLoader<T> multiResSetupIL = getUnderlyingMultiResSetupImgLoader();

			if (!active)
				return multiResSetupIL.getFloatImage(timepointId, level, normalize, hints);

			final int n = multiResSetupIL.getImageSize(timepointId).numDimensions();

			final int[] dsFactors = new int[n];
			final double[] dsD = viewerSetupIL.getMipmapResolutions()[level];
			for (int d = 0; d < n; d++)
				dsFactors[d] = (int) dsD[d];
			dsFactors[n - 1] = 1;

			RandomAccessibleInterval<FloatType> rai = FlatFieldCorrectedRandomAccessibleIntervals.create(
					viewerSetupIL.getImage(timepointId, level, hints),
					getOrCreateBrightImgDownsampled(new ViewId(timepointId, setupId), dsFactors),
					getOrCreateDarkImgDownsampled(new ViewId(timepointId, setupId), dsFactors),
					new FloatType());

			if (normalize) {
				rai = new VirtuallyNormalizedRandomAccessibleInterval<>(rai);
			}

			// Handle caching/loading
			boolean loadCompletelyRequested = false;
			for (final ImgLoaderHint hint : hints)
                if (hint == ImgLoaderHints.LOAD_COMPLETELY) {
                    loadCompletelyRequested = true;
                    break;
                }

			if (loadCompletelyRequested) {
				long numPx = 1;
				for (int d = 0; d < rai.numDimensions(); d++)
					numPx *= rai.dimension(d);

				final ImgFactory<FloatType> imgFactory;
				if (Math.log(numPx) / Math.log(2) < 31)
					imgFactory = new ArrayImgFactory<>(new FloatType());
				else
					imgFactory = new CellImgFactory<>(new FloatType());

				final Img<FloatType> loadedImg = imgFactory.create(rai);
				RealTypeConverters.copyFromTo(Views.extendZero(rai), loadedImg);

				rai = loadedImg;
			}

			return rai;
		}

		// ========== Metadata delegation ==========

		@Override
		public T getImageType() {
			return getUnderlyingViewerSetupImgLoader().getImageType();
		}

		@Override
		public V getVolatileImageType() {
			return getUnderlyingViewerSetupImgLoader().getVolatileImageType();
		}

		@Override
		public double[][] getMipmapResolutions() {
			return getUnderlyingViewerSetupImgLoader().getMipmapResolutions();
		}

		@Override
		public AffineTransform3D[] getMipmapTransforms() {
			return getUnderlyingViewerSetupImgLoader().getMipmapTransforms();
		}

		@Override
		public int numMipmapLevels() {
			return getUnderlyingViewerSetupImgLoader().numMipmapLevels();
		}

		@Override
		public Dimensions getImageSize(final int timepointId) {
			return getUnderlyingMultiResSetupImgLoader().getImageSize(timepointId);
		}

		@Override
		public Dimensions getImageSize(final int timepointId, final int level) {
			return getUnderlyingMultiResSetupImgLoader().getImageSize(timepointId, level);
		}

		@Override
		public VoxelDimensions getVoxelSize(final int timepointId) {
			return getUnderlyingMultiResSetupImgLoader().getVoxelSize(timepointId);
		}
	}
}
