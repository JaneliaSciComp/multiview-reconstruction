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
import java.util.function.Function;

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

	/**
	 * @return an {@link InterestPointsZarr} for the XML text {@code tpId_X_viewSetupId_Y/label}, the only form in use
	 *         (the points are in the dataset's interestpoints.zarr, or still in the N5 group of the same name)
	 */
	public static InterestPoints instantiatefromXML( final URI baseDir, final String fromXMLInfo )
	{
		if ( fromXMLInfo.trim().toLowerCase().startsWith("interestpoints/") )
			throw new RuntimeException( "text-file based interest points not supported anymore.");

		if ( !InterestPointsZarr.supports( fromXMLInfo ) )
			throw new RuntimeException( "interest point path not of the form tpId_X_viewSetupId_Y/label: " + fromXMLInfo );

		return new InterestPointsZarr( baseDir, fromXMLInfo );
	}

	/**
	 * @param baseLabel - the label a filtered label filters (XML attribute 'base'), null for a plain label
	 * @param filter - the filter of a filtered label (XML attribute 'filter', InterestPointFilter.toString), null for a plain label
	 * @return a plain label (InterestPointsZarr) or a filtered one (VirtualFilteredInterestPoints)
	 */
	public static InterestPoints instantiatefromXML( final URI baseDir, final String fromXMLInfo, final String baseLabel, final String filter )
	{
		if ( baseLabel == null && filter == null )
			return instantiatefromXML( baseDir, fromXMLInfo );

		if ( baseLabel == null || filter == null )
			throw new RuntimeException( "A filtered label needs both the base label and the filter in the XML, got base='" + baseLabel + "', filter='" + filter + "' for '" + fromXMLInfo + "'." );

		return new VirtualFilteredInterestPoints( baseDir, fromXMLInfo, baseLabel, InterestPointFilter.fromString( filter ) );
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
	 * Recreates this label elsewhere: under another base path, for another view and/or under another name, e.g. when a
	 * dataset is copied (SpimData2Tools.reduceSpimData2) or its views are split (SplittingTools). A plain label copies its
	 * points through the point mapping, a filtered label stays a filter of the mapped base label, so that callers need not
	 * know which kind of label they hold. The copy carries the parameters of this label (adapted to the mapped base label
	 * for a filtered one) and is not saved.
	 *
	 * @param basePath - the base path of the copy
	 * @param viewId - the view of the copy
	 * @param label - the name of the copy
	 * @param labelMapping - how the labels of this view map to the labels of the copy's view (the base label of a filtered label), e.g. l -&gt; l or l -&gt; l + "_split"
	 * @param pointMapping - the point of the copy for a point of this label, null drops the point; ip -&gt; ip copies
	 * @param copyCorrespondences - whether the copy gets the correspondences of this label (otherwise none): the ones of
	 *        dropped points are dropped, the detection ids follow the point mapping and the corresponding labels the label
	 *        mapping; the other end of a correspondence (corresponding view and detection id) lives in another view and is
	 *        NOT checked, so a caller that drops or renumbers points must only copy correspondences when the corresponding
	 *        points of the other views survive the same way (SplittingTools copies none)
	 * @return the copy
	 */
	public abstract InterestPoints copyTo(
			final URI basePath,
			final ViewId viewId,
			final String label,
			final Function< String, String > labelMapping,
			final Function< InterestPoint, InterestPoint > pointMapping,
			final boolean copyCorrespondences );

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

	/**
	 * @return the correspondences (copied) to one partner (view, label); a storage that has an index of the partners
	 *         reads only those, this default filters the whole list
	 */
	public Collection< CorrespondingInterestPoints > getCorrespondingInterestPointsCopy( final ViewId partnerView, final String partnerLabel )
	{
		final ArrayList< CorrespondingInterestPoints > list = new ArrayList<>();

		for ( final CorrespondingInterestPoints c : getCorrespondingInterestPointsCopy() )
			if ( c.getCorrespondingViewId().equals( partnerView ) && c.getCorrespodingLabel().equals( partnerLabel ) )
				list.add( c );

		return list;
	}

	/**
	 * @return the (view, label)s this list has correspondences with; a storage that has an index of the partners
	 *         answers without loading the list, this default derives them from the whole list
	 */
	public Set< Pair< ViewId, String > > getCorrespondingViews()
	{
		final HashSet< Pair< ViewId, String > > partners = new HashSet<>();

		for ( final CorrespondingInterestPoints c : getCorrespondingInterestPointsCopy() )
			partners.add( new ValuePair<>( c.getCorrespondingViewId(), c.getCorrespodingLabel() ) );

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
