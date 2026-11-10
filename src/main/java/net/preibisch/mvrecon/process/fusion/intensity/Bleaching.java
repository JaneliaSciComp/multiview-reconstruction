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
import java.util.List;

import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Dimensions;
import net.imglib2.FinalRealInterval;
import net.imglib2.RealInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Intervals;

/**
 * How often the voxels of one view had been imaged before it: by the earlier views of the same timepoint (acquisition
 * order = setup id order) whose footprint covers them, exactly what {@link IntensityMatcher} counts while matching with
 * an {@link UnbleachFunction#bleachFactor}. A voxel imaged {@code n} times before is unbleached as
 * {@code v / (1 - bleachFactor)^n}, the same in matching, preview and fusion.
 */
public final class Bleaching {

	private final double retained;

	/** image coordinates of the view (at the level given to {@link #of}) to full-resolution pixels of each earlier view */
	private final AffineTransform3D[] toEarlier;

	/** footprint of each earlier view: pixel coordinates in [-0.5, max) */
	private final double[][] max;

	Bleaching(final double bleachFactor, final List<AffineTransform3D> toEarlier, final List<Dimensions> earlierDimensions) {
		if (!(bleachFactor > 0 && bleachFactor < 1))
			throw new IllegalArgumentException("bleachFactor must be in (0, 1), but was " + bleachFactor);
		this.retained = 1 - bleachFactor;
		this.toEarlier = toEarlier.toArray(new AffineTransform3D[0]);
		this.max = earlierDimensions.stream()
				.map(dims -> new double[] { dims.dimension(0) - 0.5, dims.dimension(1) - 0.5, dims.dimension(2) - 0.5 })
				.toArray(double[][]::new);
	}

	/**
	 * @param imageToGlobal
	 *            image coordinates of {@code view} to global coordinates, i.e. its registration concatenated with the
	 *            mipmap transform of the level the corrected image is built at
	 * @return the bleaching of {@code view}, or null if {@code bleachFactor} is 0 or no earlier view overlaps it
	 */
	public static Bleaching of(final AbstractSpimData<?> spimData, final ViewId view, final AffineTransform3D imageToGlobal,
			final double bleachFactor) {
		if (bleachFactor == 0)
			return null;

		final RealInterval bounds = globalBounds(spimData, view);
		final List<AffineTransform3D> toEarlier = new ArrayList<>();
		final List<Dimensions> earlierDimensions = new ArrayList<>();
		for (final BasicViewDescription<?> vd : spimData.getSequenceDescription().getViewDescriptions().values()) {
			if (!vd.isPresent() || vd.getTimePointId() != view.getTimePointId() || vd.getViewSetupId() >= view.getViewSetupId())
				continue;
			if (Intervals.isEmpty(Intervals.intersect(bounds, globalBounds(spimData, vd))))
				continue;
			// inverse() is the registration's cached inverse, recomputed in place whenever its model is updated: copy it
			final AffineTransform3D t = model(spimData, vd).inverse().copy();
			t.concatenate(imageToGlobal);
			toEarlier.add(t);
			earlierDimensions.add(vd.getViewSetup().getSize());
		}
		return toEarlier.isEmpty() ? null : new Bleaching(bleachFactor, toEarlier, earlierDimensions);
	}

	/**
	 * As {@link #of(AbstractSpimData, ViewId, AffineTransform3D, double)}, from the registrations and sizes a fusion
	 * holds: the earlier views are those of {@code views} (the views being fused; an earlier view that is not among
	 * them is not counted).
	 */
	public static Bleaching of(final ViewId view, final AffineTransform3D imageToGlobal, final double bleachFactor,
			final java.util.Collection<? extends ViewId> views,
			final java.util.Map<ViewId, ? extends AffineTransform3D> registrations,
			final java.util.Map<ViewId, ? extends Dimensions> dimensions) {
		if (bleachFactor == 0)
			return null;

		final RealInterval bounds = globalBounds(registrations.get(view), dimensions.get(view));
		final List<AffineTransform3D> toEarlier = new ArrayList<>();
		final List<Dimensions> earlierDimensions = new ArrayList<>();
		for (final ViewId v : views) {
			if (v.getTimePointId() != view.getTimePointId() || v.getViewSetupId() >= view.getViewSetupId())
				continue;
			if (Intervals.isEmpty(Intervals.intersect(bounds, globalBounds(registrations.get(v), dimensions.get(v)))))
				continue;
			final AffineTransform3D t = registrations.get(v).inverse().copy(); // inverse() may be a cached object: copy it
			t.concatenate(imageToGlobal);
			toEarlier.add(t);
			earlierDimensions.add(dimensions.get(v));
		}
		return toEarlier.isEmpty() ? null : new Bleaching(bleachFactor, toEarlier, earlierDimensions);
	}

	private static AffineTransform3D model(final AbstractSpimData<?> spimData, final ViewId view) {
		final ViewRegistration reg = spimData.getViewRegistrations().getViewRegistration(view);
		reg.updateModel();
		return reg.getModel();
	}

	private static RealInterval globalBounds(final AbstractSpimData<?> spimData, final ViewId view) {
		return globalBounds(model(spimData, view), spimData.getSequenceDescription().getViewDescriptions().get(view).getViewSetup().getSize());
	}

	private static RealInterval globalBounds(final AffineTransform3D model, final Dimensions dims) {
		final double[] min = { -0.5, -0.5, -0.5 };
		final double[] max = { dims.dimension(0) - 0.5, dims.dimension(1) - 0.5, dims.dimension(2) - 0.5 };
		return model.estimateBounds(new FinalRealInterval(min, max));
	}

	/** @return the number of earlier views */
	int size() {
		return toEarlier.length;
	}

	/** @return the factor that unbleaches a voxel imaged {@code n} times before */
	double gain(final int n) {
		return Math.pow(retained, -n);
	}

	/**
	 * Sets {@code count[i]} to the number of earlier views covering image voxel {@code start + (i, 0, 0)},
	 * {@code 0 <= i < len}. A footprint intersects a line in one interval, so this costs one solve per earlier view.
	 */
	void countLine(final long[] start, final int len, final int[] count) {
		java.util.Arrays.fill(count, 0, len, 0);
		for (int j = 0; j < toEarlier.length; ++j) {
			final AffineTransform3D t = toEarlier[j];
			double lo = Double.NEGATIVE_INFINITY, hi = Double.POSITIVE_INFINITY; // i in [lo, hi)
			for (int d = 0; d < 3; ++d) {
				final double q0 = t.get(d, 0) * start[0] + t.get(d, 1) * start[1] + t.get(d, 2) * start[2] + t.get(d, 3);
				final double dq = t.get(d, 0);
				if (dq == 0) {
					if (q0 < -0.5 || q0 >= max[j][d]) {
						lo = hi = 0;
						break;
					}
				} else {
					final double a = (-0.5 - q0) / dq, b = (max[j][d] - q0) / dq;
					lo = Math.max(lo, Math.min(a, b));
					hi = Math.min(hi, Math.max(a, b));
				}
			}
			final int from = (int) Math.max(0, Math.ceil(lo));
			final int to = (int) Math.min(len, Math.ceil(hi));
			for (int i = from; i < to; ++i)
				++count[i];
		}
	}
}
