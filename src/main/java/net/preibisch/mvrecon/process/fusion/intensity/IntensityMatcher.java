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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import mpicbg.models.PointMatch;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Cursor;
import net.imglib2.Dimensions;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RealInterval;
import net.imglib2.RealPoint;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.blocks.BlockInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.basictypeaccess.array.ArrayDataAccess;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Cast;
import net.imglib2.util.IntervalIndexer;
import net.imglib2.util.Intervals;
import net.imglib2.util.LinAlgHelpers;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.FastAffineModel1D;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.FlattenedMatches;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.imglib2.util.Intervals.intersect;
import static net.imglib2.util.Intervals.isEmpty;
import static net.imglib2.view.fluent.RandomAccessibleIntervalView.Extension.border;
import static net.imglib2.view.fluent.RandomAccessibleView.Interpolation.nLinear;

class IntensityMatcher {

	private static final Logger LOG = LoggerFactory.getLogger(IntensityMatcher.class);

	final AbstractSpimData<?> spimData;

	/**
	 * Transforms global coordinates into render space (which is rasterized for sampling mask and intensity voxels).
	 */
	private final AffineTransform3D renderScale;

	private final int[] numCoefficients;

	private final Map<ViewId, TileInfo> tileInfos = new ConcurrentHashMap<>();

	private final UnbleachFunction unbleach;

	/**
	 * @param spimData
	 * @param renderScale
	 * 		at which scale to sample images. For example, {@code renderScale = 0.25} means using 4 x downsampled images.
	 * @param coefficientsSize
	 * @param unbleach
	 */
	IntensityMatcher(
			final AbstractSpimData<?> spimData,
			final double renderScale,
			final int[] coefficientsSize,
			final UnbleachFunction unbleach
	) {
		this.spimData = spimData;
		this.numCoefficients = coefficientsSize;
		this.unbleach = unbleach;
		this.renderScale = new AffineTransform3D();
		this.renderScale.scale(renderScale);
	}

	IntensityMatcher(
			final AbstractSpimData<?> spimData,
			final double renderScale,
			final int[] coefficientsSize
	) {
		this(spimData, renderScale, coefficientsSize, (v, i) -> v);
	}

    /**
     * Match using RANSAC
     *
     * @param view1
     * @param view1Bleachers
     * @param view2
     * @param view2Bleachers
     * @param minIntensity threshold for intensities to consider for RANSAC, anything below that value will be discarded
     * @param maxIntensity threshold for intensities to consider for RANSAC, anything above that value will be discarded
     * @param minNumCandidates minimum number of (non-discarded) overlapping pixels required to consider two coefficient regions overlapping
     * @param iterations number of RANSAC iterations (only for RANSAC)
     * @param maxEpsilon maximal allowed transfer error (only for RANSAC)
     * @param minInlierRatio minimal number of inliers to number of candidates (only for RANSAC)
     * @param minNumInliers minimally required absolute number of inliers (only for RANSAC)
     * @param maxTrust reject candidates with a cost larger than maxTrust * median cost (only for RANSAC)
     * @return
     */
    public List<CoefficientMatch> match(
            final ViewId view1,
            final List<ViewId> view1Bleachers,
            final ViewId view2,
            final List<ViewId> view2Bleachers,
            final double minIntensity,
            final double maxIntensity,
            final int minNumCandidates,
            final int iterations,
            final double maxEpsilon,
            final double minInlierRatio,
            final int minNumInliers,
            final double maxTrust
    ) {
        final IntensityMatchingFilter filter = new RansacIntensityMatchingFilter(
                new FastAffineModel1D(),
                iterations, maxEpsilon, minInlierRatio, minNumInliers, maxTrust);
        return match(view1, view1Bleachers, view2, view2Bleachers, minIntensity, maxIntensity, minNumCandidates, filter);
    }

    /**
     * Match using Histograms
     *
     * @param view1
     * @param view1Bleachers
     * @param view2
     * @param view2Bleachers
     * @param minIntensity threshold for intensities to consider for RANSAC, anything below that value will be discarded
     * @param maxIntensity threshold for intensities to consider for RANSAC, anything above that value will be discarded
     * @param minNumCandidates minimum number of (non-discarded) overlapping pixels required to consider two coefficient regions overlapping
     * @return
     */
    public List<CoefficientMatch> match(
            final ViewId view1,
            final List<ViewId> view1Bleachers,
            final ViewId view2,
            final List<ViewId> view2Bleachers,
            final double minIntensity,
            final double maxIntensity,
            final int minNumCandidates
    ) {
        final IntensityMatchingFilter filter = new HistogramIntensityMatchingFilter(new FastAffineModel1D());
        return match(view1, view1Bleachers, view2, view2Bleachers, minIntensity, maxIntensity, minNumCandidates, filter);
    }

    /**
     *
     * @param view1
     * @param view1Bleachers
     * @param view2
     * @param view2Bleachers
     * @param minIntensity threshold for intensities to consider for RANSAC, anything below that value will be discarded
     * @param maxIntensity threshold for intensities to consider for RANSAC, anything above that value will be discarded
     * @param minNumCandidates minimum number of (non-discarded) overlapping pixels required to consider two coefficient regions overlapping
     * @param filter {@link RansacIntensityMatchingFilter} or {@link HistogramIntensityMatchingFilter}
     * @return
     */
    public List<CoefficientMatch> match(
            final ViewId view1,
            final List<ViewId> view1Bleachers,
            final ViewId view2,
            final List<ViewId> view2Bleachers,
            final double minIntensity,
            final double maxIntensity,
            final int minNumCandidates,
            final IntensityMatchingFilter filter
    ) {
        final TileInfo t1 = getTileInfo(view1);
        final TileInfo t2 = getTileInfo(view2);

        // Find the overlap between the ViewIds (in global coordinates).
        // This is where we need to look for overlapping CoefficientRegions.
        final RealInterval overlap = getOverlap(t1, t2);
        if (Intervals.isEmpty(overlap))
            return Collections.emptyList();

        // Now find out for which CoefficientRegions (transformed into global
        // coordinates) the intersection with overlap is non-empty. Those are
        // candidate regions for matching between tiles.
        final List<CoefficientRegion> r1s = overlappingCoefficientRegions(t1, overlap);
        final List<CoefficientRegion> r2s = overlappingCoefficientRegions(t2, overlap);

        // Find intersecting CoefficientRegion pairs.
        final List<Pair<CoefficientRegion, CoefficientRegion>> pairs = new ArrayList<>();
        for (final CoefficientRegion r1 : r1s) {
            for (final CoefficientRegion r2 : r2s) {
                if (!isEmpty(intersect(r1.wbounds, r2.wbounds))) {
                    pairs.add(new ValuePair<>(r1, r2));
                }
            }
        }

        LOG.debug("found {} CoefficientRegions of t1 in overlap.", r1s.size());
        LOG.debug("found {} CoefficientRegions of t2 in overlap.", r2s.size());
        LOG.debug("found {} CoefficientRegion pairs.", pairs.size());

        final List<RandomAccess<UnsignedByteType>> view1BleacherMasks = view1Bleachers.stream()
                .map(v -> scaleTileBounds(renderScale, getTileInfo(v)))
                .map(RandomAccessible::randomAccess)
                .collect(Collectors.toList());
        final List<RandomAccess<UnsignedByteType>> view2BleacherMasks = view2Bleachers.stream()
                .map(v -> scaleTileBounds(renderScale, getTileInfo(v)))
                .map(RandomAccessible::randomAccess)
                .collect(Collectors.toList());

        // Next steps:
        // [ ] blk optimizations? E.g., render and match single line from both coefficient masks?

        final int mipmapLevel1 = bestMipmapLevel(renderScale, t1);
        final int mipmapLevel2 = bestMatchingMipmapLevel(renderScale, t2, getPixelSize(mipmapToRenderCoordinates(t1, mipmapLevel1, renderScale)));
        LOG.debug("using mipmapLevel {} for t1, mipmapLevel {} for t2", mipmapLevel1, mipmapLevel2);

        final RandomAccessible<? extends RealType<?>> scaledTile1 = Cast.unchecked(scaleTile(t1, mipmapLevel1, renderScale));
        final RandomAccessible<? extends RealType<?>> scaledTile2 = Cast.unchecked(scaleTile(t2, mipmapLevel2, renderScale));

        final List<CoefficientMatch> coefficientMatches = new ArrayList<>();
        for (final Pair<CoefficientRegion, CoefficientRegion> pair : pairs) {
            final CoefficientRegion r1 = pair.getA();
            final CoefficientRegion r2 = pair.getB();

            final FinalRealInterval intersection = intersect(r1.wbounds, r2.wbounds);
            final FinalRealInterval scaledIntersection = renderScale.estimateBounds(intersection);
            final Interval renderInterval = Intervals.smallestContainingInterval(scaledIntersection);
            final int numElements = (int) Intervals.numElements(renderInterval);
            final FlattenedMatches flatCandidates = new FlattenedMatches(1, numElements);
            flatCandidates.setWeighted(false);
//			final List<PointMatch> candidates = new ArrayList<>(numElements);

            final RandomAccessible<UnsignedByteType> mask1 = scaleTileCoefficient(renderScale, t1, r1.index);
            final RandomAccessible<UnsignedByteType> mask2 = scaleTileCoefficient(renderScale, t2, r2.index);

            final Cursor<UnsignedByteType> cMask1 = mask1.view().interval(renderInterval).flatIterable().cursor();
            final Cursor<UnsignedByteType> cMask2 = mask2.view().interval(renderInterval).flatIterable().cursor();
            final RandomAccess<? extends RealType<?>> raTile1 = scaledTile1.randomAccess();
            final RandomAccess<? extends RealType<?>> raTile2 = scaledTile2.randomAccess();

            final boolean hasMinIntensity = Double.isFinite( minIntensity );
            final boolean hasMaxIntensity = Double.isFinite( maxIntensity );

            while (cMask1.hasNext()) {
                final boolean m1 = cMask1.next().get() != 0;
                final boolean m2 = cMask2.next().get() != 0;
                if (m1 && m2) {
                    raTile1.setPosition(cMask1);
                    double p = raTile1.get().getRealDouble();
                    if ( ( hasMinIntensity && p < minIntensity ) || ( hasMaxIntensity && p > maxIntensity ) )
                        continue;

                    raTile2.setPosition(cMask1);
                    double q = raTile2.get().getRealDouble();
                    if ( ( hasMinIntensity && q < minIntensity ) || ( hasMaxIntensity && q > maxIntensity ) )
                        continue;

                    int numBleaches1 = 0;
                    for (final RandomAccess<UnsignedByteType> b : view1BleacherMasks) {
                        if (b.setPositionAndGet(cMask1).get() != 0)
                            ++numBleaches1;
                    }
                    p = unbleach.unbleach(p, numBleaches1);

                    int numBleaches2 = 0;
                    for (final RandomAccess<UnsignedByteType> b : view2BleacherMasks) {
                        if (b.setPositionAndGet(cMask1).get() != 0)
                            ++numBleaches2;
                    }
                    q = unbleach.unbleach(q, numBleaches2);

                    flatCandidates.put(p, q, 1);
                }
            }
            flatCandidates.flip();

            final List<PointMatch> reducedMatches = new ArrayList<>();
            if (flatCandidates.size() > minNumCandidates) {
                filter.filter(flatCandidates, reducedMatches);
                if (reducedMatches.isEmpty()) {
                    LOG.debug("({}, {}) not matched", r1.index, r2.index);
                } else {
                    LOG.debug("({}, {}) matched: {}", r1.index, r2.index, filter.model());
                }
            }
            coefficientMatches.add(new CoefficientMatch(r1.index, r2.index, flatCandidates.size(), reducedMatches));
        }
        return coefficientMatches;
    }

    private static RandomAccessible<UnsignedByteType> scaleTileCoefficient(final AffineTransform3D renderScale, final TileInfo tile, final int coeff) {
		final int[] coeffPos = new int[3];
		IntervalIndexer.indexToPosition(coeff, tile.numCoeffs, coeffPos);
		return scaleTileCoefficient(renderScale, tile, coeffPos);
	}

	private static RandomAccessible<UnsignedByteType> scaleTileCoefficient(final AffineTransform3D renderScale, final TileInfo tile, final int[] coeffPos) {
		final AffineTransform3D scaleToGrid = new AffineTransform3D();
		scaleToGrid.set(tile.coeffBoundsToWorldTransform.inverse());
		scaleToGrid.concatenate(renderScale.inverse());
		final Supplier<BiConsumer<Localizable, ? super UnsignedByteType>> supplier = () -> {
			final RealPoint gridRealPos = new RealPoint(3);
			return (pos, value) -> {
				scaleToGrid.apply(pos, gridRealPos);
				for (int d = 0; d < 3; ++d) {
					if (coeffPos[d] != (int) Math.floor(gridRealPos.getDoublePosition(d))) {
						value.set(0);
						return;
					}
				}
				value.set(1);
			};
		};
		return new FunctionRandomAccessible<>(3, supplier, UnsignedByteType::new);
	}

	private static RandomAccessible<UnsignedByteType> scaleTileBounds(final AffineTransform3D renderScale, final TileInfo tile) {
		final AffineTransform3D scaleToUnit = new AffineTransform3D();
		scaleToUnit.set(tile.unitBoundsToWorldTransform.inverse());
		scaleToUnit.concatenate(renderScale.inverse());
		final Supplier<BiConsumer<Localizable, ? super UnsignedByteType>> supplier = () -> {
			final RealPoint gridRealPos = new RealPoint(3);
			return (pos, value) -> {
				scaleToUnit.apply(pos, gridRealPos);
				for (int d = 0; d < 3; ++d) {
					if (0 != (int) Math.floor(gridRealPos.getDoublePosition(d))) {
						value.set(0);
						return;
					}
				}
				value.set(1);
			};
		};
		return new FunctionRandomAccessible<>(3, supplier, UnsignedByteType::new);
	}

	private static RandomAccessible<IntType> scaleTileCoefficients(final AffineTransform3D renderScale, final TileInfo tile) {
		final AffineTransform3D scaleToGrid = new AffineTransform3D();
		scaleToGrid.set(tile.coeffBoundsToWorldTransform.inverse());
		scaleToGrid.concatenate(renderScale.inverse());
		final Supplier<BiConsumer<Localizable, ? super IntType>> supplier = () -> {
			final int[] cpos = new int[3];
			final BiConsumer<Localizable, int[]> toCoeffGrid = toGrid(scaleToGrid);
			return (pos, value) -> {
				toCoeffGrid.accept(pos, cpos);
				for (int d = 0; d < 3; ++d) {
					if (cpos[d] < 0 || cpos[d] >= tile.numCoeffs[d]) {
						value.set(-1);
						return;
					}
				}
				final int ci = IntervalIndexer.positionToIndex(cpos, tile.numCoeffs);
				value.set(ci);
			};
		};
		return new FunctionRandomAccessible<>(3, supplier, IntType::new);
	}

	private static BiConsumer<Localizable, int[]> toGrid(AffineTransform3D toGridTransform) {
		final RealPoint gridRealPos = new RealPoint(3);
		return (pos, cpos) -> {
			toGridTransform.apply(pos, gridRealPos);
			cpos[0] = (int) Math.floor(gridRealPos.getDoublePosition(0));
			cpos[1] = (int) Math.floor(gridRealPos.getDoublePosition(1));
			cpos[2] = (int) Math.floor(gridRealPos.getDoublePosition(2));
		};
	}

	TileInfo getTileInfo(final ViewId viewId) {
		return tileInfos.computeIfAbsent(viewId, v -> new TileInfo(numCoefficients, spimData, v));
	}

	/**
	 * Return a linearly interpolated view of the {@code tile} at the specified {@code renderScale}.
	 *
	 * @param tile
	 * 		tile to take the source image from
	 * @param mipmapLevel
	 * 		resolution level of the source image to use
	 * @param renderScale
	 * 		transforms global coordinates to coordinates of the returned RandomAccessible
	 *
	 * @return transformed view
	 */
	private static RandomAccessible<?> scaleTile(final TileInfo tile, final int mipmapLevel, final AffineTransform3D renderScale) {
		final AffineTransform3D transform = mipmapToRenderCoordinates(tile, mipmapLevel, renderScale);
		return RealViews.affine(tile.getImage(mipmapLevel).view()
						.extend(border()).interpolate(nLinear()),
				transform);
	}

	/**
	 * Find the mipmap level with {@link #getPixelSize pixel-size} closest to {@code targetPixelSize}.
	 *
	 * @param renderScale
	 * 		transforms global coordinates to target coordinates (where pixel-size is measured).
	 * @param tile
	 * 		tile to take mipmap levels from
	 * @param targetPixelSize
	 * 		desired pixel size
	 *
	 * @return mipmap level index
	 */
	private static int bestMatchingMipmapLevel(final AffineTransform3D renderScale, final TileInfo tile, final double targetPixelSize) {
		int bestLevel = 0;
		double bestDiff = Double.POSITIVE_INFINITY;
		for (int l = 0; l < tile.numMipmapLevels(); l++) {
			final double pixelSize = getPixelSize(mipmapToRenderCoordinates(tile, l, renderScale));
			final double diff = Math.abs(targetPixelSize - pixelSize);
			if (diff < bestDiff) {
				bestDiff = diff;
				bestLevel = l;
			}
		}
		return bestLevel;
	}

	// TODO: Think about this again!
	//
	//       What is a good mipmap level to use? Closest to render resolution. Better too coarse then too fine.
	//       What about anisotropy?
	//       What about adding our own downsampling on top of the existing mipmap
	//
	//       For now, I just take the smallest side lengths of a transformed source voxel in render space.
	//       That should be larger than 1, but as small as possible.

	/**
	 * Find the mipmap level with smallest {@link #getPixelSize pixel-size} larger than 1.
	 *
	 * @param tile
	 * 		tile to take mipmap levels from
	 * @param renderTransform
	 * 		transforms global coordinates to target coordinates (where pixel-size is measured).
	 *
	 * @return mipmap level index
	 */
	private static int bestMipmapLevel(final AffineTransform3D renderTransform, final TileInfo tile) {
		final float acceptedError = 0.02f;
		int bestLevel = 0;
		double bestSize = 0;
		for (int i = 0; i < tile.numMipmapLevels(); i++) {
			final AffineTransform3D transform = mipmapToRenderCoordinates(tile, i, renderTransform);
			final double pixelSize = getPixelSize(transform);
			if (bestSize < (1 - acceptedError) && pixelSize > bestSize) {
				bestSize = pixelSize;
				bestLevel = i;
			} else if (pixelSize > (1 - acceptedError) && pixelSize < bestSize) {
				bestSize = pixelSize;
				bestLevel = i;
			}
		}
		return bestLevel;
	}

	/**
	 * Compute the pixel size at the given transform. That is, when moving by 1
	 * in each dimension of the source space, how far do we move (at least) in
	 * the target space of the transform.
	 */
	private static double getPixelSize(final AffineTransform3D model) {
		final double[] step = getStepSize(model);
		return Math.min(step[0], Math.min(step[1], step[2]));
	}

	/**
	 * Compute the projected voxel size at the given transform. That is, when
	 * moving by 1 in each dimension of the source space, how far do we move in
	 * the target space of the transform.
	 */
	// TODO: This is copied from DownsampleTools. Make DownsampleTools::getStepSize public?
	private static double[] getStepSize(final AffineTransform3D model) {
		final double[] size = new double[3];
		final double[] tmp = new double[3];
		for (int d = 0; d < 3; ++d) {
			for (int i = 0; i < 3; ++i)
				tmp[i] = model.get(i, d);
			size[d] = LinAlgHelpers.length(tmp);
		}
		return size;
	}

	/**
	 * Compute the transform from the given {@code mipmapLevel} of the {@code
	 * tile} into render space, where {@code renderTransform} transforms global
	 * coordinates into render coordinates.
	 * <p>
	 * The returned transform is concatenated by first transforming the given
	 * mipmap level into full resolution tile coordinates, then applying the
	 * tile's model to transform into global coordinates, then applying {@code
	 * renderTransform} to transform into render coordinates.
	 *
	 * @param tile
	 * 		tile to take the source image from
	 * @param mipmapLevel
	 * 		resolution level of the source image to use
	 * @param renderTransform
	 * 		transforms global coordinates to render coordinates
	 *
	 * @return transform from mipmap to render coordinates
	 */
	private static AffineTransform3D mipmapToRenderCoordinates(final TileInfo tile, final int mipmapLevel, final AffineTransform3D renderTransform) {
		final AffineTransform3D transform = new AffineTransform3D();
		transform.set(renderTransform);
		transform.concatenate(tile.model);
		transform.concatenate(tile.getMipmapTransforms()[mipmapLevel]);
		return transform;
	}

	/**
	 * Loop over num of coefficient regions.
	 * Use {@code IntervalIndexer} to get un-flattened coordinate c.
	 * Make (c,c+1) interval and transform to world coordinates.
	 * Intersect with {@code bounds} and collect overlapping coefficients into List.
	 */
	private static List<CoefficientRegion> overlappingCoefficientRegions(
			final TileInfo tile,
			final RealInterval bounds // in world coordinates
	) {
		final int ncoeff = (int) Intervals.numElements(tile.numCoeffs);
		final long[] cmin = new long[3];
		final int[] csize = {2, 2, 2}; // cbounds is used as a RealInterval and we want cmax[d] == cmin[d] + 1
		final Interval cbounds = BlockInterval.wrap(cmin, csize);

		final List<CoefficientRegion> coefficientRegions = new ArrayList<>();
		for (int i = 0; i < ncoeff; ++i) {
			IntervalIndexer.indexToPosition(i, tile.numCoeffs, cmin);
			final FinalRealInterval wbounds = tile.coeffBoundsToWorldTransform.estimateBounds(cbounds);
			if (!isEmpty(intersect(bounds, wbounds))) {
				coefficientRegions.add(new CoefficientRegion(i, wbounds));
			}
		}
		return coefficientRegions;
	}

	static class CoefficientRegion {

		final int index;
		final RealInterval wbounds;

		CoefficientRegion(final int index, final RealInterval wbounds) {
			this.index = index;
			this.wbounds = wbounds;
		}

		@Override
		public String toString() {
			return "CoefficientRegion{index=" + index + ", wbounds=" + wbounds + '}';
		}
	}

	/**
	 * A matched pair of coefficient regions between two tiles.
	 * <p>
	 * Comprises the flattened indices of the matches coefficients in both
	 * tiles, the number of overlapping voxels used to estimate the intensity
	 * model, and a collection of point-matches that minimally describe the
	 * model (i.e., two matches for 1D affine intensity model).
	 */
	public static class CoefficientMatch implements Serializable {

		private final int coeff1;
		private final int coeff2;
		private final int numVoxels;
		private final Collection<PointMatch> matches;

		/**
		 * @param coeff1
		 * 		flattened index of matched coefficient in first tile
		 * @param coeff2
		 * 		flattened index of matched coefficient in second tile
		 * @param numVoxels
		 * 		number of overlapping voxels used to estimate the model
		 * @param matches
		 * 		reduced matches (describe the model)
		 */
		CoefficientMatch(final int coeff1, final int coeff2, final int numVoxels, final Collection<PointMatch> matches) {
			this.coeff1 = coeff1;
			this.coeff2 = coeff2;
			this.numVoxels = numVoxels;
			this.matches = matches;
		}

		/**
		 * @return flattened index of the matched coefficient in the first tile
		 */
		public int coeff1() {
			return coeff1;
		}

		/**
		 * @return flattened index of the matched coefficient in the second tile
		 */
		public int coeff2() {
			return coeff2;
		}

		/**
		 * @return the number of overlapping voxels used in the model estimation
		 */
		public int numVoxels() {
			return numVoxels;
		}

		/**
		 * @return point matches minimally describing the model
		 */
		public Collection<PointMatch> matches() {
			return matches;
		}
	}

	// --- - - -  -  -   -   U T I L   -   -  -  - - - ---


	/**
	 * Returns the bounding interval of the specified {@code views} in global coordinates.
	 */
	static RealInterval getOverlap(final TileInfo tile1, final TileInfo tile2) {
		return intersect(getBounds(tile1), getBounds(tile2));
	}

	/**
	 * Returns the bounding interval of the specified {@code view} in global coordinates.
	 * This assumes the bounding box in images space is from min-0.5 to max+0.5.
	 */
	static RealInterval getBounds(final TileInfo tile) {
		return tile.model.estimateBounds(imageBounds(tile.dimensions));
	}

	/**
	 * Convert integer {@code dimensions} into a bounding box {@code RealInterval} from
	 * {@code -0.5} to {@code max + 0.5} in every dimension.
	 */
	static RealInterval imageBounds(final Dimensions dimensions) {
		final int n = dimensions.numDimensions();
		final double[] min = new double[n];
		final double[] max = new double[n];
		Arrays.fill(min, -0.5);
		Arrays.setAll(max, d -> dimensions.dimension(d) - 0.5);
		return FinalRealInterval.wrap(min, max);
	}

	static <T extends NativeType<T>> ArrayImg<T, ?> copyToArrayImg(final BlockSupplier<T> blocks, final Interval interval) {
		final ArrayImg<T, ?> img = new ArrayImgFactory<>(blocks.getType()).create(interval);
		final Object data = ((ArrayDataAccess<?>) img.update(null)).getCurrentStorageArray();
		blocks.copy(interval, data);
		return img;
	}


	// ┌---------------------------
	// │          DEBUG

	RandomAccessible<UnsignedByteType> scaleTileCoefficient(final TileInfo tile, final int... coeffPos) {
		return scaleTileCoefficient(renderScale, tile, coeffPos);
	}

	RandomAccessible<IntType> scaleTileCoefficients(final TileInfo tile) {
		return scaleTileCoefficients(renderScale, tile);
	}

	<T> RandomAccessible<T> scaleTileImage(final TileInfo tile, final int mipmapLevel) {
		return Cast.unchecked(scaleTile(tile, mipmapLevel, renderScale));
	}

	int bestMipmapLevel(final TileInfo tile) {
		return bestMipmapLevel(renderScale, tile);
	}

	static RealInterval getCoefficientWorldBoundingBox(TileInfo tile, final int... coeffPos) {
		if (tile.numCoeffs.length != coeffPos.length)
			throw new IllegalArgumentException();
		final int n = coeffPos.length;
		final double[] cmin = new double[n];
		final double[] cmax = new double[n];
		Arrays.setAll(cmin, d -> coeffPos[d]);
		Arrays.setAll(cmax, d -> coeffPos[d] + 1);
		return tile.coeffBoundsToWorldTransform.estimateBounds(FinalRealInterval.wrap(cmin, cmax));
	}

	// │          DEBUG
	// └---------------------------
}
