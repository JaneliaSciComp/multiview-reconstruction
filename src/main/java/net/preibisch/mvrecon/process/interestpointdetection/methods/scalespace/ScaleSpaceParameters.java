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
package net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace;

import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;

/**
 * Parameters of the scale-space DoG ({@link DoGScaleSpace}). Octave 0 is the image that is handed in
 * (the "starting position", typically a downsampled version of the view), every further octave
 * subsamples all axes by 2.
 *
 * @author Stephan Preibisch
 */
public class ScaleSpaceParameters
{
	/**
	 * the finest scale at which peaks are detected, in pixels of the image that is handed in (octave 0),
	 * like the sigma of the single-scale DoG: the finest detectable DoG level is the one of sigmaMin
	 * and k*sigmaMin, the DoG level below it (sigmaMin/k) exists only as its scale neighbor
	 */
	public double sigmaMin = 1.8;

	/**
	 * intra-octave steps, k = 2^(1/steps); every octave has steps+3 Gaussians and steps+2 DoG levels,
	 * of which the levels 1..steps have both scale neighbors and can contain extrema (level 1 = sigmaMin)
	 */
	public int steps = 4;

	/**
	 * number of octaves, -1 = as many as the image allows (an octave must be larger than its longest
	 * Gaussian kernel in every dimension)
	 */
	public int octaves = -1;

	/**
	 * minimal |response| of a refined peak, the DoG is weighted by 1/(k-1) so that this has the
	 * same meaning at every scale
	 */
	public double threshold = 0.01;

	public boolean findMin = false;
	public boolean findMax = true;

	/**
	 * also keep all spatial extrema of the finest detectable level (sigmaMin), i.e. the detections of
	 * the single-scale DoG at sigmaMin, even if they are no extremum in scale: structures smaller than
	 * sigmaMin (point-like beads whose response keeps growing towards finer scales) and structures
	 * that merge with a neighbor at the next coarser level (close beads). Lowe's scale space (false)
	 * does not report them. They are refined in space only and reported with sigma = sigmaMin.
	 */
	public boolean detectFinestLevel = true;

	/**
	 * 0 = none (integer position and level)
	 * 1 = quadratic fit in space and scale
	 */
	public int localization = 1;

	/**
	 * global intensity range used for the normalization to [0,1], NaN = compute from the image
	 * (always from the entire image, never from the block that is processed)
	 */
	public double minIntensity = Double.NaN;
	public double maxIntensity = Double.NaN;

	/**
	 * blur that the image already carries (in pixels of octave 0), it is subtracted from the
	 * sigmas of octave 0
	 */
	public double imageSigma = 0.5;

	/**
	 * voxel size of octave 0 (the image handed in, NOT full resolution) per dimension relative to x,
	 * e.g. { 1, 1, 1.6 }; the sigma of each level in dimension d is sigma_i / anisotropy[ d ] so that
	 * the Gaussians are isotropic in physical units; null = isotropic in pixels
	 */
	public double[] anisotropy = null;

	/**
	 * cell size of the lazy Gaussian and DoG levels
	 */
	public int[] cellSize = DoGImgLib2.blockSize.clone();

	/**
	 * peaks closer than combineDistance * 2^octave pixels of the base image (octave 0) are
	 * duplicates, the one with the higher |response| is kept, 0 = keep all
	 */
	public double combineDistance = 0.5;

	/**
	 * how far (in pixels of the respective octave) the quadratic refinement may move a peak out
	 * of the block that is processed before it is discarded
	 */
	public int refineMargin = 2;

	public ScaleSpaceParameters() {}

	public ScaleSpaceParameters( final double sigmaMin, final double threshold )
	{
		this.sigmaMin = sigmaMin;
		this.threshold = threshold;
	}

	public ScaleSpaceParameters( final double sigmaMin, final int steps, final int octaves, final double threshold )
	{
		this.sigmaMin = sigmaMin;
		this.steps = steps;
		this.octaves = octaves;
		this.threshold = threshold;
	}
}
