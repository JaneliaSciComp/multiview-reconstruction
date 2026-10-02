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
package net.preibisch.mvrecon.fiji.spimdata.interestpoints;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

/**
 * A list of interest points for a certain label, can save and load from textfile as specified in the XML
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 */
public abstract class InterestPoints
{
	URI basePath;
	String parameters;

	boolean modifiedInterestPoints, modifiedCorrespondingInterestPoints;

	protected InterestPoints(final URI basePath)
	{
		this.basePath = basePath;
		this.modifiedInterestPoints = false;
		this.modifiedCorrespondingInterestPoints = false;
	}

	/** @return an {@link InterestPointsZarr} for paths of the form {@code tpId_X_viewSetupId_Y/label}, else an {@link InterestPointsN5} */
	public static InterestPoints instantiatefromXML( final URI baseDir, final String fromXMLInfo )
	{
		if ( fromXMLInfo.trim().toLowerCase().startsWith("interestpoints/") )
			throw new RuntimeException( "text-file based interest points not supported anymore.");
		else if ( InterestPointsZarr.supports( fromXMLInfo ) )
			return new InterestPointsZarr( baseDir, fromXMLInfo );
		else
			return new InterestPointsN5( baseDir, fromXMLInfo );
	}

	public static InterestPoints newInstance( final URI baseDir, final ViewId viewId, final String label )
	{
		return new InterestPointsZarr( baseDir, viewId, label );
	}

	public boolean hasModifiedInterestPoints() { return modifiedInterestPoints; }
	public boolean hasModifiedCorrespondingInterestPoints() { return modifiedCorrespondingInterestPoints; }

	public URI getBasePath() { return basePath; }
	public void setBasePath( final URI basePath )
	{
		this.basePath = basePath;
		this.modifiedCorrespondingInterestPoints = true;
		this.modifiedInterestPoints = true;
	}

	public abstract boolean deleteInterestPoints();
	public abstract boolean deleteCorrespondingInterestPoints();

	/** 
	 * @return the parameters used for segmenting these points
	 */
	public String getParameters() { return parameters; }
	public void setParameters( final String parameters ) { this.parameters = parameters; }

	/**
	 * @return a string to be stored in the XML, also used to instantiate this object (e.g. file path)
	 */
	public abstract String getXMLRepresentation();

	/**
	 * @param viewId the viewId
	 * @param label the label
	 * 
	 * @return a string that is stored in the XML and that is used to load/save interestpoints and corresponding interest points
	 */
	// public abstract String createXMLRepresentation( final ViewId viewId, final String label );

	/**
	 * @return - a map from interest point ID to interest points (copied), tries to load from disc if null
	 */
	public abstract Map< Integer, InterestPoint > getInterestPointsCopy();

	/**
	 * @return - the collection of corresponding interest points (copied), tries to load from disc if null
	 */
	public abstract Collection< CorrespondingInterestPoints > getCorrespondingInterestPointsCopy();

	/** @return the correspondences to one partner (view, label); a backend with a pair index reads only those */
	public Collection< CorrespondingInterestPoints > getCorrespondingInterestPointsCopy( final ViewId partnerView, final String partnerLabel )
	{
		final ArrayList< CorrespondingInterestPoints > pair = new ArrayList<>();
		for ( final CorrespondingInterestPoints correspondence : getCorrespondingInterestPointsCopy() )
			if ( correspondence.getCorrespodingLabel().equals( partnerLabel ) && correspondence.getCorrespondingViewId().equals( partnerView ) )
				pair.add( correspondence );

		return pair;
	}

	/** @return the (view, label)s this list has correspondences with */
	public Set< Pair< ViewId, String > > getCorrespondingViews()
	{
		final Set< Pair< ViewId, String > > partners = new HashSet<>();
		for ( final CorrespondingInterestPoints correspondence : getCorrespondingInterestPointsCopy() )
			partners.add( new ValuePair<>( correspondence.getCorrespondingViewId(), correspondence.getCorrespodingLabel() ) );

		return partners;
	}

	public void setInterestPoints( final Collection< InterestPoint > list )
	{
		this.modifiedInterestPoints = true;
		setInterestPointsLocal( list );
	}
	public void setCorrespondingInterestPoints( final Collection< CorrespondingInterestPoints > list )
	{
		this.modifiedCorrespondingInterestPoints = true;
		setCorrespondingInterestPointsLocal( list );
	}

	protected abstract void setInterestPointsLocal( final Collection< InterestPoint > list );
	protected abstract void setCorrespondingInterestPointsLocal( final Collection< CorrespondingInterestPoints > list );

	public abstract boolean saveInterestPoints( final boolean forceWrite );
	public abstract boolean saveCorrespondingInterestPoints( final boolean forceWrite );

	protected abstract boolean loadCorrespondences();
	protected abstract boolean loadInterestPoints();
}
