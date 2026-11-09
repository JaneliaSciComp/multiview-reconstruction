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
package net.preibisch.mvrecon.process.interestpointregistration;

/**
 * Verbosity of the log of the interest point registration (matching, global optimization, final models).
 *
 * Restricted means summaries only: no line per view, per pair, per group or per iteration of the
 * global optimization, however many there are. The per-pair and per-view caps
 * ({@link net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.strong.InterestPointMatchCreator#maxPerPairLog},
 * {@link net.preibisch.mvrecon.process.interestpointregistration.pairwise.PairwiseResult#maxPerPairCorrLog},
 * {@link TransformationTools#maxPerViewTransformLog}) bound the same lines when the log is not restricted;
 * a restricted log prints none of them. Set by the "Large dataset" dialog of the Data Explorer and
 * BigStitcher ("Restrict alignment log outputs", off by default).
 */
public class AlignmentLog
{
	/** true = summaries only, see the class comment */
	public static boolean restricted = false;
}
