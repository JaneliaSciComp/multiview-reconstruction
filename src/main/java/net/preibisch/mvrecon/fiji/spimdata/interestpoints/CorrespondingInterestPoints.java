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
package net.preibisch.mvrecon.fiji.spimdata.interestpoints;

import java.io.Serializable;

import mpicbg.spim.data.sequence.ViewId;

/**
 * Defines a pair of corresponding interest points
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 */
public class CorrespondingInterestPoints implements Comparable< CorrespondingInterestPoints >, Serializable
{
	private static final long serialVersionUID = -3165385812553510745L;

	/**
	 * The detection id of the interest point in this {@link InterestPoints}
	 */
	final int detectionId;
	
	/**
	 * The {@link ViewId} the corresponding interest point belongs to
	 */
	//final ViewId correspondingViewId;
	final int correspondingViewIdTP, correspondingViewIdSetup;
	
	/**
	 * The label of {@link InterestPoints} as stored in the {@link ViewInterestPointLists} HashMap
	 */
	final String correspondingLabel;
	
	/**
	 * The detection id of the corresponding interest point in the {@link InterestPoints}
	 */
	final int correspondingDetectionId;

	/**
	 * The consensus set ID this correspondence belongs to:
	 * -1 = single-consensus mode (no multi-consensus RANSAC used)
	 * 0, 1, 2, ... = multi-consensus set ID
	 */
	final int consensusSetId;

	public CorrespondingInterestPoints( final CorrespondingInterestPoints c )
	{
		this( c.detectionId, c.correspondingViewIdTP, c.correspondingViewIdSetup, c.correspondingLabel, c.correspondingDetectionId, c.consensusSetId );
	}

	public CorrespondingInterestPoints( final int detectionId, final ViewId correspondingViewId, final String correspondingLabel, final int correspondingDetectionId, final int consensusSetId )
	{
		this.detectionId = detectionId;
		//this.correspondingViewId = correspondingViewId;
		this.correspondingViewIdTP = correspondingViewId.getTimePointId();
		this.correspondingViewIdSetup = correspondingViewId.getViewSetupId();
		this.correspondingLabel = correspondingLabel;
		this.correspondingDetectionId = correspondingDetectionId;
		this.consensusSetId = consensusSetId;
	}

	public CorrespondingInterestPoints( final int detectionId, final int correspondingViewIdTP, final int correspondingViewIdSetup, final String correspondingLabel, final int correspondingDetectionId, final int consensusSetId )
	{
		this.detectionId = detectionId;
		//this.correspondingViewId = correspondingViewId;
		this.correspondingViewIdTP = correspondingViewIdTP;
		this.correspondingViewIdSetup = correspondingViewIdSetup;
		this.correspondingLabel = correspondingLabel;
		this.correspondingDetectionId = correspondingDetectionId;
		this.consensusSetId = consensusSetId;
	}

	// Backward-compatible constructors (default consensusSetId to -1)
	public CorrespondingInterestPoints( final int detectionId, final ViewId correspondingViewId, final String correspondingLabel, final int correspondingDetectionId )
	{
		this( detectionId, correspondingViewId, correspondingLabel, correspondingDetectionId, -1 );
	}

	public CorrespondingInterestPoints( final int detectionId, final int correspondingViewIdTP, final int correspondingViewIdSetup, final String correspondingLabel, final int correspondingDetectionId )
	{
		this( detectionId, correspondingViewIdTP, correspondingViewIdSetup, correspondingLabel, correspondingDetectionId, -1 );
	}

	/**
	 * @return The detection id of the interest point in this {@link InterestPoints}
	 */
	final public int getDetectionId() { return detectionId; }

	/**
	 * @return The {@link ViewId} the corresponding interest point belongs to
	 */
	final public ViewId getCorrespondingViewId() { return new ViewId( correspondingViewIdTP, correspondingViewIdSetup ); }
	
	/**
	 * @return The label of {@link InterestPoints} as stored in the {@link ViewInterestPointLists} HashMap
	 */
	final public String getCorrespodingLabel() { return correspondingLabel; }
	
	/**
	 * @return The detection id of the corresponding interest point in the {@link InterestPoints}
	 */
	final public int getCorrespondingDetectionId() { return correspondingDetectionId; }

	/**
	 * @return The consensus set ID (-1 for single-consensus, 0+ for multi-consensus sets)
	 */
	final public int getConsensusSetId() { return consensusSetId; }

//	/**
//	 * Order by {@link #getCorrespondingViewId().getTimePointId()  timepoint} id, then
//	 * {@link #getCorrespondingViewId().getViewSetupId() setup} id, then detection id.
//	 */
	@Override
	public int compareTo( final CorrespondingInterestPoints o )
	{
		/*
		if ( getCorrespondingViewId().getTimePointId() == o.getCorrespondingViewId().getTimePointId() )
		{
			if ( getCorrespondingViewId().getViewSetupId() == o.getCorrespondingViewId().getViewSetupId() )
			{
				return getCorrespondingDetectionId() - o.getCorrespondingDetectionId();
			}
			else
			{
				return getCorrespondingViewId().getViewSetupId() - o.getCorrespondingViewId().getViewSetupId();
			}
		}
		else
		{
			return getCorrespondingViewId().getTimePointId() - o.getCorrespondingViewId().getTimePointId();
		}
		*/
		if ( correspondingViewIdTP == o.correspondingViewIdTP )
		{
			if ( correspondingViewIdSetup == o.correspondingViewIdSetup )
			{
				return getCorrespondingDetectionId() - o.getCorrespondingDetectionId();
			}
			else
			{
				return correspondingViewIdSetup - o.correspondingViewIdSetup;
			}
		}
		else
		{
			return correspondingViewIdTP - o.correspondingViewIdTP;
		}
	}
}
