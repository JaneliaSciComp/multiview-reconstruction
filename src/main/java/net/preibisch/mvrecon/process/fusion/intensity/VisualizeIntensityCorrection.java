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
package net.preibisch.mvrecon.process.fusion.intensity;

import bdv.cache.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.Interpolation;
import bdv.viewer.MaskUtils;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.render.DefaultMipmapOrdering;
import bdv.viewer.render.MipmapOrdering;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.Volatile;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.converter.Converter;
import net.imglib2.img.cell.AbstractCellImg;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.mask.Masked;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.util.Cast;

import static bdv.viewer.Interpolation.NEARESTNEIGHBOR;
import static net.imglib2.view.fluent.RandomAccessibleIntervalView.Extension.zero;
import static net.imglib2.view.fluent.RandomAccessibleView.Interpolation.clampingNLinear;
import static net.imglib2.view.fluent.RandomAccessibleView.Interpolation.nearestNeighbor;

public class VisualizeIntensityCorrection {

	private VisualizeIntensityCorrection() {
		// static utility methods. should not be instantiated.
	}

	/**
	 * Wrap the given {@code SourceAndConverter}, allowing to switch between unmodified and on-the-fly intensity-corrected images.
	 *
	 * @param soc
	 * 		the source to wrap
	 * @param sharedQueue
	 * 		queue for volatile block loading
	 * @param timepointToCoefficients
	 * 		provides intensity correction {@code Coefficients} for each timepoint index.
	 * @param enableIntensityCorrection
	 * 		if {@code true} (when asking for an image) intensity-correction is used.
	 * @param <T>
	 * 		pixel type
	 * @param <V>
	 * 		volatile pixel type
	 *
	 * @return wrapped {@code SourceAndConverter}.
	 */
	@SuppressWarnings("unchecked")
	public static <T extends NativeType<T> & NumericType<T>, V extends Volatile<T> & NumericType<V>> SourceAndConverter<T> wrapWithIntensityCorrectedSource(// TODO rename?
			final SourceAndConverter<?> soc,
			final SharedQueue sharedQueue,
			final IntFunction<Coefficients> timepointToCoefficients,
			final BooleanSupplier enableIntensityCorrection
	) {
		final Source<T> source = (Source<T>) soc.getSpimSource();
		final Converter<T, ARGBType> converter = (Converter<T, ARGBType>) soc.getConverter();
		final Source<T> ics = new IntensityCorrectedSource<>(source, timepointToCoefficients, enableIntensityCorrection);
		if (soc.asVolatile() == null) {
			return new SourceAndConverter<>(ics, converter);
		} else {
			final SharedQueue queue = sharedQueue != null ? sharedQueue : new SharedQueue(Runtime.getRuntime().availableProcessors());
			final Source<V> vsource = (Source<V>)(Object) soc.asVolatile().getSpimSource();
			final Converter<V, ARGBType> vconverter = (Converter<V, ARGBType>)(Object) soc.asVolatile().getConverter();
			final Source<V> vics = new VolatileIntensityCorrectedSource<>(source, vsource, sharedQueue, timepointToCoefficients, enableIntensityCorrection);
			return new SourceAndConverter<>(ics, converter, new SourceAndConverter<>(vics, vconverter));
		}
	}

	private abstract static class AbstractIntensityCorrectedSource<S extends NativeType<S> & NumericType<S>, T extends NumericType<T>> implements Source<T>,
			MipmapOrdering {

		private final Source<S> imageSource;

		private final Source<T> delegate;

		/**
		 * This is either the {@link #delegate} itself, if it implements
		 * {@link MipmapOrdering}, or a {@link DefaultMipmapOrdering}.
		 */
		private final MipmapOrdering mipmapOrdering;

		/**
		 * Maps timepoint index to coefficients
		 */
		private final IntFunction<Coefficients> timepointToCoefficients;

		private final BooleanSupplier enableIntensityCorrection;

		private int currentTimePointIndex = -1;

		private boolean currentTimePointIsPresent;

		final RandomAccessibleInterval<S>[] correctedImgs;

		final RandomAccessibleInterval<T>[] currentImgs;

		/**
		 * Wraps another {@code Source} and allows to switch between that source
		 * and an intensity-corrected version.
		 *
		 * @param imageSource
		 * 		provides images to intensity-correct.
		 * @param delegate
		 * 		everything except requests for intensity-corrected images is
		 * 		delegated to this {@code Source}. This is either {@code imageSource}
		 * 		or the volatile version thereof.
		 * @param timepointToCoefficients
		 * 		provides intensity correction {@code Coefficients} for each timepoint index.
		 * @param enableIntensityCorrection
		 * 		if {@code true} (when asking for an image) intensity-correction is used.
		 */
		AbstractIntensityCorrectedSource(
				final Source<S> imageSource,
				final Source<T> delegate,
				final IntFunction<Coefficients> timepointToCoefficients,
				final BooleanSupplier enableIntensityCorrection
		) {
			this.imageSource = imageSource;
			this.delegate = delegate;
			this.mipmapOrdering = delegate instanceof MipmapOrdering ?
					(MipmapOrdering) delegate : new DefaultMipmapOrdering(delegate);
			this.timepointToCoefficients = timepointToCoefficients;
			this.correctedImgs = new RandomAccessibleInterval[delegate.getNumMipmapLevels()];
			this.currentImgs = new RandomAccessibleInterval[delegate.getNumMipmapLevels()];
			this.enableIntensityCorrection = enableIntensityCorrection;
		}

		/**
		 * Subclasses override loadTimepoint() to call super.loadTimepoint() and
		 * then create currentImgs[] from correctedImgs[]
		 */
		void loadTimepoint(final int timepointIndex) {

			currentTimePointIndex = timepointIndex;
			currentTimePointIsPresent = delegate.isPresent(timepointIndex);

			if (currentTimePointIsPresent) {
				final Coefficients coefficients = timepointToCoefficients.apply(timepointIndex);
				for (int level = 0; level < correctedImgs.length; ++level) {
					final RandomAccessibleInterval<S> image = imageSource.getSource(timepointIndex, level);
					final int[] cellDimensions;
					if (image instanceof AbstractCellImg) {
						cellDimensions = ((AbstractCellImg<?, ?, ?, ?>) image).getCellGrid().getCellDimensions();
					} else {
						cellDimensions = new int[] {64};
					}
					final RandomAccessibleInterval<S> corrected = BlockSupplier.of(image)
							.andThen(FastLinearIntensityMap.linearIntensityMap(coefficients, image))
							.toCellImg(image.dimensionsAsLongArray(), cellDimensions);
					correctedImgs[level] = corrected;
				}
			}
		}

		@Override
		public synchronized RandomAccessibleInterval<T> getSource(final int t, final int level) {
			if (enableIntensityCorrection.getAsBoolean()) {
				if (t != currentTimePointIndex)
					loadTimepoint(t);
				return currentImgs[level];
			} else {
				return delegate.getSource(t, level);
			}
		}

		@Override
		public synchronized RealRandomAccessible<T> getInterpolatedSource(final int t, final int level, final Interpolation method) {
			if (enableIntensityCorrection.getAsBoolean()) {
				if (t != currentTimePointIndex)
					loadTimepoint(t);
				if (!currentTimePointIsPresent)
					return null;
				return getSource(t, level)
						.view()
						.extend(zero())
						.interpolate(method == NEARESTNEIGHBOR ? nearestNeighbor() : clampingNLinear());
			} else {
				return delegate.getInterpolatedSource(t, level, method);
			}
		}

		@Override
		public synchronized RandomAccessibleInterval<? extends Masked<T>> getMaskedSource(final int t, final int level) {
			if (enableIntensityCorrection.getAsBoolean()) {
				return Source.super.getMaskedSource(t, level);
			} else {
				return delegate.getMaskedSource(t, level);
			}
		}

		@Override
		public synchronized RealRandomAccessible<? extends Masked<T>> getInterpolatedMaskedSource(final int t, final int level, final Interpolation method) {
			if (enableIntensityCorrection.getAsBoolean()) {
				return MaskUtils.extendAndInterpolateMasked( Cast.unchecked( getMaskedSource( t, level ) ), method );
			} else {
				return delegate.getInterpolatedMaskedSource(t, level, method);
			}
		}

		@Override
		public MipmapHints getMipmapHints(final AffineTransform3D screenTransform, final int timepoint, final int previousTimepoint) {
			return mipmapOrdering.getMipmapHints(screenTransform, timepoint, previousTimepoint);
		}

		@Override
		public T getType() {
			return delegate.getType();
		}

		@Override
		public boolean isPresent(final int t) {
			return delegate.isPresent(t);
		}

		@Override
		public boolean doBoundingBoxCulling() {
			return delegate.doBoundingBoxCulling();
		}

		@Override
		public void getSourceTransform(final int t, final int level, final AffineTransform3D transform) {
			delegate.getSourceTransform(t, level, transform);
		}

		@Override
		public String getName() {
			return delegate.getName();
		}

		@Override
		public VoxelDimensions getVoxelDimensions() {
			return delegate.getVoxelDimensions();
		}

		@Override
		public int getNumMipmapLevels() {
			return delegate.getNumMipmapLevels();
		}
	}

	private static class IntensityCorrectedSource<T extends NativeType<T> & NumericType<T>> extends AbstractIntensityCorrectedSource<T,T> {

		IntensityCorrectedSource(
				final Source<T> source,
				final IntFunction<Coefficients> timepointToCoefficients,
				final BooleanSupplier enableIntensityCorrection) {

			super(source, source, timepointToCoefficients, enableIntensityCorrection);
		}

		@Override
		void loadTimepoint(final int timepointIndex) {
			super.loadTimepoint(timepointIndex);
			Arrays.setAll(currentImgs, i -> correctedImgs[i]);
		}
	}

	private static class VolatileIntensityCorrectedSource<T extends NativeType<T> & NumericType<T>, V extends Volatile<T> & NumericType<V>> extends AbstractIntensityCorrectedSource<T, V>
			implements Source<V> {

		private final SharedQueue sharedQueue;

		VolatileIntensityCorrectedSource(
				final Source<T> delegate,
				final Source<V> volatileDelegate,
				final SharedQueue sharedQueue,
				final IntFunction<Coefficients> timepointToCoefficients,
				final BooleanSupplier enableIntensityCorrection) {

			super(delegate, volatileDelegate, timepointToCoefficients, enableIntensityCorrection);
			this.sharedQueue = sharedQueue;

			sharedQueue.ensureNumPriorities(delegate.getNumMipmapLevels());
		}

		@Override
		void loadTimepoint(final int timepointIndex) {

			super.loadTimepoint(timepointIndex);

			final int maxLevel = currentImgs.length - 1;
			Arrays.setAll(currentImgs, i -> {
				if (correctedImgs[i] == null) {
					return null;
				} else {
					final int priority = maxLevel - i;
					final CacheHints hints = new CacheHints(LoadingStrategy.BUDGETED, priority, false);
					return VolatileViews.wrapAsVolatile(correctedImgs[i], sharedQueue, hints);
				}
			});
		}
	}
}
