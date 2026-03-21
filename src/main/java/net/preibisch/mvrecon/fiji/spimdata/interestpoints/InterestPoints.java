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
import java.util.Collection;
import java.util.Map;

import mpicbg.spim.data.sequence.ViewId;

/**
 * A list of interest points for a certain label, can save and load from textfile as specified in the XML
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 */
public abstract class InterestPoints
{
	URI baseDir;
	String parameters;

	boolean modifiedInterestPoints, modifiedCorrespondingInterestPoints;

	protected InterestPoints(final URI baseDir)
	{
		this.baseDir = baseDir;
		this.modifiedInterestPoints = false;
		this.modifiedCorrespondingInterestPoints = false;
	}

	public static InterestPoints instantiatefromXML( final URI baseDir, final String fromXMLInfo )
	{
		if ( fromXMLInfo.trim().toLowerCase().startsWith("interestpoints/") )
			throw new RuntimeException( "text-file based interest points not supported anymore.");
		else
			return new InterestPointsN5( baseDir, fromXMLInfo );
	}

	public static InterestPoints newInstance( final URI baseDir, final ViewId viewId, final String label )
	{
		final InterestPoints list;

		final String n5path = new InterestPointsN5( null, null ).createXMLRepresentation( viewId, label );
		list = new InterestPointsN5( baseDir, n5path );

		return list;
	}

	public boolean hasModifiedInterestPoints() { return modifiedInterestPoints; }
	public boolean hasModifiedCorrespondingInterestPoints() { return modifiedCorrespondingInterestPoints; }

	public URI getBaseDir() { return baseDir; }
	public void setBaseDir( final URI baseDir )
	{
		this.baseDir = baseDir;
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
	public abstract String createXMLRepresentation( final ViewId viewId, final String label );

	/**
	 * @return - a map from interest point ID to interest points (copied), tries to load from disc if null
	 */
	public abstract Map< Integer, InterestPoint > getInterestPointsCopy();

	/**
	 * @return - the collection of corresponding interest points (copied), tries to load from disc if null
	 */
	public abstract Collection< CorrespondingInterestPoints > getCorrespondingInterestPointsCopy();

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
