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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.embeddings;

import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays;

import mpicbg.models.Model;

/**
 * The parameters of {@link EmbeddingsPairwise}: the model RANSAC fits, the search radius for partners in the registered
 * frame, the ratio test's threshold, the smallest cosine of a candidate and the name of the per-point array that holds the
 * embeddings. The statics are the dialog's defaults (the last values used).
 *
 * @author Stephan Preibisch
 */
public class EmbeddingsParameters
{
	/** the dialog's "Limit search radius" (off by default: the embeddings alone tell the partners apart) and its radius in global px; the command line limits only when {@code --searchRadius} is given */
	public static boolean defaultLimitSearchRadius = false;
	public static double defaultSearchRadius = 500;
	public static double defaultRatio = EmbeddingsMatcher.defaultRatio;
	/** the dialog's minimal cosine similarity of a candidate (remembered between dialogs); {@link EmbeddingsMatcher#defaultSmin} at first */
	public static double defaultSmin = EmbeddingsMatcher.defaultSmin;
	/** the name pcmatch's {@code embed} writes its embeddings under (its {@code --array-name}); the matcher reads this array unless told otherwise */
	public static final String standardArrayName = InterestPointArrays.EMBEDDING;

	/** the array name the dialog starts with (remembered between dialogs) */
	public static String defaultArrayName = standardArrayName;

	protected final Model< ? > model;
	protected final double searchRadius, ratio, smin;
	protected final String arrayName;

	/**
	 * @param model the transformation model to fit
	 * @param searchRadius the search radius for partners in global coordinates (px), the registration's uncertainty;
	 *            {@link Double#POSITIVE_INFINITY} for no limit (every point of the other view is a possible partner)
	 * @param ratio the ratio test's threshold in similarity space ({@link EmbeddingsMatcher#defaultRatio})
	 * @param smin the smallest cosine of a candidate ({@link EmbeddingsMatcher#defaultSmin})
	 * @param arrayName the per-point array of the interest point store that holds the embeddings
	 */
	public EmbeddingsParameters( final Model< ? > model, final double searchRadius, final double ratio, final double smin, final String arrayName )
	{
		if ( model == null )
			throw new IllegalArgumentException( "The embeddings matcher needs a model to fit." );
		if ( !( searchRadius > 0 ) )
			throw new IllegalArgumentException( "The search radius must be positive (it is the registration's uncertainty in global px), got " + searchRadius + "." );
		if ( !( ratio > 0 && ratio <= 1 ) )
			throw new IllegalArgumentException( "The ratio must be in (0, 1], got " + ratio + "." );
		if ( !( smin >= -1 && smin <= 1 ) )
			throw new IllegalArgumentException( "The smallest cosine must be in [-1, 1], got " + smin + "." );
		if ( arrayName == null || arrayName.isEmpty() )
			throw new IllegalArgumentException( "The name of the array that holds the embeddings is empty." );

		this.model = model;
		this.searchRadius = searchRadius;
		this.ratio = ratio;
		this.smin = smin;
		this.arrayName = arrayName;
	}

	/** @return a copy of the model (matchers run in parallel, a model is stateful) */
	public Model< ? > getModel() { return model.copy(); }
	public double getSearchRadius() { return searchRadius; }
	/** @return false if the search radius is unlimited ({@link Double#POSITIVE_INFINITY}) */
	public boolean limitSearchRadius() { return !Double.isInfinite( searchRadius ); }
	public double getRatio() { return ratio; }
	public double getSmin() { return smin; }
	public String getArrayName() { return arrayName; }

	@Override
	public String toString() { return ( limitSearchRadius() ? "radius " + searchRadius + " px" : "no search radius limit" ) + ", ratio " + ratio + ", smin " + smin + ", array '" + arrayName + "'"; }
}
