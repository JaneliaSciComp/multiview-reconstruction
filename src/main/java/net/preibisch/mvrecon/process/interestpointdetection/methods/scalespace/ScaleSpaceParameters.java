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
	 * the initial blur of the scale space in pixels of the image that is handed in (octave 0), i.e. the
	 * finest scale at which peaks are detected (Lowe 2004: 1.6), like the sigma of the single-scale DoG:
	 * the finest detectable DoG level is the one of sigmaMin and k*sigmaMin, the DoG level below it
	 * (sigmaMin/k) exists only as its scale neighbor
	 */
	public double sigmaMin = 1.6;

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
	 * the intensity range used for the normalization to [0,1]; it must be set before DoGScaleSpace runs
	 * (the driver ScaleSpace computes it over the whole view when the user did not set one, so that all
	 * blocks of a view normalize identically and a block never scans the entire image)
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

	/**
	 * estimate the size of the finest-level detections (they are no extremum in scale, so sigmaMin is only
	 * an upper bound of their scale) by fitting a Gaussian blob to the responses of the first finestFitLevels
	 * DoG levels at the peak (DoGScaleSpace.fitBlobSize); their sigma is then the scale of maximal response
	 * of that blob, size * sqrt( 2 / n ) (never above sigmaMin); if the fit is unreliable (relative residual
	 * above finestFitMaxResidual, or the size at the end of the tried range) they get finestFallbackSigma, a
	 * best guess a bit below sigmaMin (also never above it)
	 */
	public boolean fitFinestSize = true;
	public int finestFitLevels = 4;
	public double finestFitMaxResidual = 0.5;
	public double finestFallbackSigma = 1.4;

	/** The defaults (this class is the single source of them) */
	public ScaleSpaceParameters() {}

	/**
	 * A copy of all fields (anisotropy and cellSize are cloned), e.g. to run with a different threshold without changing the original
	 */
	public ScaleSpaceParameters( final ScaleSpaceParameters p )
	{
		this.sigmaMin = p.sigmaMin;
		this.steps = p.steps;
		this.octaves = p.octaves;
		this.threshold = p.threshold;
		this.findMin = p.findMin;
		this.findMax = p.findMax;
		this.detectFinestLevel = p.detectFinestLevel;
		this.localization = p.localization;
		this.minIntensity = p.minIntensity;
		this.maxIntensity = p.maxIntensity;
		this.imageSigma = p.imageSigma;
		this.anisotropy = p.anisotropy == null ? null : p.anisotropy.clone();
		this.cellSize = p.cellSize.clone();
		this.combineDistance = p.combineDistance;
		this.refineMargin = p.refineMargin;
		this.fitFinestSize = p.fitFinestSize;
		this.finestFitLevels = p.finestFitLevels;
		this.finestFitMaxResidual = p.finestFitMaxResidual;
		this.finestFallbackSigma = p.finestFallbackSigma;
	}

	/**
	 * The defaults with another initial blur and threshold
	 */
	public ScaleSpaceParameters( final double sigmaMin, final double threshold )
	{
		this.sigmaMin = sigmaMin;
		this.threshold = threshold;
	}

	/**
	 * The defaults with another initial blur, steps per octave, number of octaves (-1 = as many as the image allows) and threshold
	 */
	public ScaleSpaceParameters( final double sigmaMin, final int steps, final int octaves, final double threshold )
	{
		this.sigmaMin = sigmaMin;
		this.steps = steps;
		this.octaves = octaves;
		this.threshold = threshold;
	}
}
