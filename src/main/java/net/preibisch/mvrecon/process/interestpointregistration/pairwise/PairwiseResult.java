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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import mpicbg.models.PointMatch;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;

public class PairwiseResult< I extends InterestPoint >
{
	/**
	 * Maximum number of per-pair "[…] >>> […]: Loaded N corresponding…" / "Not enough…" lines
	 * to print across one batch of pairwise tasks before per-pair output is suppressed.
	 * Set to {@link Integer#MAX_VALUE} to print everything (legacy behavior).
	 * The end-of-batch summary printed by {@code MatcherPairwiseTools.computePairs} is
	 * emitted regardless of this threshold.
	 *
	 * The counter is reset by {@link #resetLogCounters()} at the start of each
	 * {@code computePairs} invocation.
	 */
	public static int maxPerPairCorrLog = 100;

	private static final java.util.concurrent.atomic.AtomicInteger printedCount = new java.util.concurrent.atomic.AtomicInteger( 0 );
	private static volatile boolean noticeEmitted = false;

	/** Reset the per-pair load-log counter and one-shot notice flag. */
	public static void resetLogCounters()
	{
		printedCount.set( 0 );
		noticeEmitted = false;
	}

	private List< PointMatchGeneric< I > > candidates, inliers;
	private List< Integer > inlierSetIds = null;  // parallel to inliers list, null = single-consensus
	private double error = Double.NaN;
	private long time = 0;
	private String result = "", desc = "";
	private String labelA, labelB;

	// only used temporarily in InterestPointMatchCreator to remember the flipped pointmatches
	// between assignPointMatches() and assignWeights()
	private Collection< PointMatch > flippedMatches = new ArrayList<>();

	boolean printout = false, storeCorrespondences = true;

	public PairwiseResult( final boolean storeCorrespondences )
	{
		this.storeCorrespondences = storeCorrespondences;
		this.printout = true;
	}

	/** Gate {@link #setResult}/{@link #setDescription} prints by the per-batch cap. */
	private static void cappedPrintln( final String line )
	{
		final int n = printedCount.getAndIncrement();
		if ( n < maxPerPairCorrLog )
		{
			IOFunctions.println( line );
		}
		else if ( !noticeEmitted )
		{
			synchronized ( PairwiseResult.class )
			{
				if ( !noticeEmitted )
				{
					noticeEmitted = true;
					IOFunctions.println( "(further per-pair correspondence-load lines suppressed; --maxPerPairCorrLog=" + maxPerPairCorrLog + ". Summary follows.)" );
				}
			}
		}
	}

	public void setLabelA( final String labelA ) { this.labelA = labelA; }
	public void setLabelB( final String labelB ) { this.labelB = labelB; }

	public String getLabelA() { return labelA; }
	public String getLabelB() { return labelB; }

	public boolean storeCorrespondences() { return storeCorrespondences; }
	public void setPrintOut( final boolean printOut ) { this.printout = printOut; }
	public void setResult( final long time, final String result )
	{
		this.time = time;
		this.result = result;
		if ( printout && desc.length() > 0 ) cappedPrintln( getFullDesc() );
	}
	public void setDescriptions( final String desc ) { this.desc = desc; }
	public List< PointMatchGeneric< I > > getCandidates() { return candidates; }
	public List< PointMatchGeneric< I > > getInliers() { return inliers; }
	public String getDescription() { return desc; }
	public void setDescription( final String desc )
	{
		this.desc = desc;
		if ( printout && result.length() > 0 ) cappedPrintln( getFullDesc() );
	}
	public double getError() { return error; }
	public void setCandidates( final List< PointMatchGeneric< I > > candidates ) { this.candidates = candidates; }

	public void setInliers( final List< PointMatchGeneric< I > > inliers, final double error )
	{
		this.inliers = inliers;
		this.error = error;
		this.inlierSetIds = null;  // Backward compatible: no set IDs
	}

	public void setInliers( final List< PointMatchGeneric< I > > inliers, final double error, final List< Integer > setIds )
	{
		this.inliers = inliers;
		this.error = error;
		this.inlierSetIds = setIds;
	}

	public List< Integer > getInlierSetIds() { return inlierSetIds; }

	public void setFlippedMatches( final Collection< PointMatch > flippedMatches ) { this.flippedMatches = flippedMatches; };
	public Collection< PointMatch > getFlippedMatches() { return flippedMatches; };

	public String getFullDesc() { return "(" + new Date( time ) + "): " + desc + ": " + result; }
}
