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

import java.util.Arrays;

import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicSetupImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Dimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Cast;

/**
 * Holds sizes and transformations related to one view (a {@code ViewId} from a
 * {@code AbstractSpimData}) that is split into coefficient-regions.
 * <p>
 * Used by {@link IntensityMatcher}.
 */
class TileInfo {

	final int[] numCoeffs;

	/**
	 * Image dimensions (at full resolution)
	 */
	final Dimensions dimensions;

	/**
	 * Image-to-world transform (at full resolution)
	 */
	final AffineTransform3D model;

	/**
	 * coefficient coordinate to coefficient region center in image coordinates
	 */
	final AffineTransform3D coeffCenterTransform;

	/**
	 * coefficient coordinate to min bound of coefficient region in image coordinates
	 */
	final AffineTransform3D coeffBoundsTransform;

	/**
	 * coefficient coordinate to min bound of coefficient region in world coordinates
	 * (i.e., {@code model * coeffBoundsTransform})
	 */
	final AffineTransform3D coeffBoundsToWorldTransform;

	/**
	 * [0,1] to image region in world coordinates
	 * (i.e., map 0 to 0, 1 to dimension[d], then map to world coordinates
	 */
	final AffineTransform3D unitBoundsToWorldTransform;

	private final MultiResolutionSetupImgLoader<?> setupImgLoader;
	private final int timepointId;

	/**
	 * Create a TileInfo for the given ViewId
	 *
	 * @param coefficientsSize
	 * 		dimensions of the coefficients field
	 * @param spimData
	 * 		spimdata that provides images and registration
	 * @param view
	 * 		ViewId that the new TileInfo represents
	 */
	TileInfo(final int[] coefficientsSize, final AbstractSpimData<?> spimData, final ViewId view) {
		this.numCoeffs = coefficientsSize;

		final BasicImgLoader imgLoader = spimData.getSequenceDescription().getImgLoader();
		final BasicSetupImgLoader<?> sil = imgLoader.getSetupImgLoader(view.getViewSetupId());
		if (!(sil instanceof MultiResolutionSetupImgLoader)) {
			throw new IllegalArgumentException();
		}
		setupImgLoader = Cast.unchecked(sil);
		timepointId = view.getTimePointId();
		this.dimensions = setupImgLoader.getImageSize(timepointId);
		this.model = spimData.getViewRegistrations().getViewRegistration(view).getModel();

		final double[] scale = new double[numCoeffs.length];
		Arrays.setAll(scale, d -> (double) dimensions.dimension(d) / numCoeffs[d]);

		coeffCenterTransform = new AffineTransform3D();
		coeffCenterTransform.set(
				scale[0], 0, 0, scale[0] * 0.5 - 0.5,
				0, scale[1], 0, scale[1] * 0.5 - 0.5,
				0, 0, scale[2], scale[2] * 0.5 - 0.5
		);

		coeffBoundsTransform = new AffineTransform3D();
		coeffBoundsTransform.set(
				scale[0], 0, 0, -0.5,
				0, scale[1], 0, -0.5,
				0, 0, scale[2], -0.5
		);

		coeffBoundsToWorldTransform = new AffineTransform3D();
		coeffBoundsToWorldTransform.set(model);
		coeffBoundsToWorldTransform.concatenate(coeffBoundsTransform);

		final AffineTransform3D unitBoundsTransform = new AffineTransform3D();
		Arrays.setAll(scale, d -> (double) dimensions.dimension(d));
		unitBoundsTransform.set(
				scale[0], 0, 0, -0.5,
				0, scale[1], 0, -0.5,
				0, 0, scale[2], -0.5
		);

		unitBoundsToWorldTransform = new AffineTransform3D();
		unitBoundsToWorldTransform.set(model);
		unitBoundsToWorldTransform.concatenate(unitBoundsTransform);
	}

	/**
	 * Get the subsampling factors, indexed by resolution level and dimension.
	 * For example, a subsampling factor of 2 means the respective resolution
	 * level is scaled by 0.5 in the respective dimension.
	 *
	 * @return subsampling factors, indexed by resolution level and dimension.
	 */
	double[][] getMipmapResolutions() {
		return setupImgLoader.getMipmapResolutions();
	}

	/**
	 * Get the transformation from coordinates of the sub-sampled image of a a
	 * resolution level to coordinates of the full resolution image. The array
	 * of transforms is indexed by resolution level.
	 *
	 * @return array with one transformation for each mipmap level.
	 */
	AffineTransform3D[] getMipmapTransforms() {
		return setupImgLoader.getMipmapTransforms();
	}

	/**
	 * Get number of resolution levels.
	 *
	 * @return number of resolution levels.
	 */
	int numMipmapLevels() {
		return setupImgLoader.numMipmapLevels();
	}

	<T extends RealType<T>> RandomAccessibleInterval<T> getImage(final int mipmapLevel) {
		return Cast.unchecked(setupImgLoader.getImage(timepointId, mipmapLevel));
	}
}
