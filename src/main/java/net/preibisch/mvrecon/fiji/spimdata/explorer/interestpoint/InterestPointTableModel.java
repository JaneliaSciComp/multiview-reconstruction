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
package net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.swing.table.AbstractTableModel;

import bdv.BigDataViewer;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RealLocalizable;
import net.imglib2.realtransform.AffineTransform3D;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ViewSetupExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint.InterestPointOverlay.InterestPointSource;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BasicBDVPopup;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays.Attributes;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter.Axis;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.VirtualFilteredInterestPoints;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import util.BDVTools;

public class InterestPointTableModel extends AbstractTableModel implements InterestPointSource
{
	private static final long serialVersionUID = -1263388435427674269L;

	/** the column that shows which attributes the points of a label store (attributes( row )), where the renderer marks an old label */
	public static final int ATTRIBUTES_COLUMN = 5;
	public static final int PARAMETERS_COLUMN = 6;

	ViewInterestPoints viewInterestPoints;
	final ArrayList< String > columnNames;

	List< BasicViewDescription< ? > > currentVDs;
	final InterestPointExplorerPanel panel;

	private int selectedRow = -1;
	private int selectedCol = -1;
	private int selectedState = 0; // 0=none, 1=red/all-corresponding, 2=green/inter-visible
	private double distanceFade = 0.125; // 0.0 = no fade, 1.0 = full fade (default: 0.5^3 = 0.125)
	private boolean filterMode = false; // true = only draw points on current plane (performance mode)
	private double pointSizeScale = 1.0; // scaling factor for point size (1.0 = default size of 3.0)
	private double planeThickness = 3.0; // thickness of current plane in screen pixels

	final ArrayList< InterestPointSource > interestPointSources;
	volatile InterestPointOverlay interestPointOverlay = null;

	HashMap< ViewId, Collection< ? extends RealLocalizable > > points = new HashMap<>();

	// the labels whose points could not be read (e.g. a filtered label of a base label that stores no response): logged once, the cell says so, the label can still be deleted
	private final HashSet< String > failed = new HashSet<>();

	// which attributes (DoG response, intensity, sigma) the points of a label store in the selected views and which per-point arrays (embeddings) it has, computed once per label and selection (it loads the points)
	private final HashMap< String, LabelAttributes > attributes = new HashMap<>();

	// the preview of the interactive filter (InteractiveInterestPointFilter): only the points that pass it are drawn
	private volatile InterestPointFilter previewFilter = null;
	private HashMap< ViewId, Collection< ? extends RealLocalizable > > previewPoints = null, previewSource = null;
	private InterestPointFilter previewWith = null;

	public InterestPointTableModel( final ViewInterestPoints viewInterestPoints, final InterestPointExplorerPanel panel )
	{
		this.columnNames = new ArrayList< String >();

		this.columnNames.add( "Interest Point Label" );
		this.columnNames.add( "#Detections" );
		this.columnNames.add( "#Corresponding" );
		this.columnNames.add( "#Correspondences" );
		this.columnNames.add( "Present in Views" );
		this.columnNames.add( "Attributes" );
		this.columnNames.add( "Parameters" );

		this.viewInterestPoints = viewInterestPoints;
		this.currentVDs = new ArrayList<>();
		this.panel = panel;

		this.interestPointSources = new ArrayList< InterestPointSource >();
		this.interestPointSources.add( this );

	}

	protected void update( final ViewInterestPoints viewInterestPoints )
	{
		this.viewInterestPoints = viewInterestPoints;

		// the labels may have changed (detected again, filtered labels created)
		this.failed.clear();
		this.attributes.clear();
	}
	protected ViewInterestPoints getViewInterestPoints() { return viewInterestPoints; }
	protected List< BasicViewDescription< ? > > getCurrentViewDescriptions() { return currentVDs; } 
	
	protected void updateViewDescription( final List< BasicViewDescription< ? > > vds )
	{
		this.currentVDs = vds;
		this.failed.clear();
		this.attributes.clear();

		// update everything
		fireTableDataChanged();

		setSelected( selectedRow, selectedCol );
	}

	@Override
	public int getColumnCount() { return columnNames.size(); }
	
	@Override
	public int getRowCount()
	{
		if ( currentVDs.size() == 0 )
			return 1;
		else
			return Math.max( 1, InterestPointTools.getAllInterestPointMap( viewInterestPoints, currentVDs ).keySet().size() );
	}

	@Override
	public boolean isCellEditable( final int row, final int column )
	{
		if ( column == PARAMETERS_COLUMN )
			return true;
		else
			return false;
	}

	@Override
	public void setValueAt( final Object value, final int row, final int column ) {}

	public static String label( final HashMap< String, Integer > labelMap, final int row )
	{
		// the same natural order as the label drop-downs of the dialogs (InterestPointTools.getAllInterestPointLabels)
		final ArrayList< String > labels = InterestPointTools.sortLabels( labelMap.keySet() );

		if ( row >= labels.size() )
			return null;
		else
			return labels.get( row );
	}

	@Override
	public Object getValueAt( final int row, final int column )
	{
		if ( currentVDs == null || currentVDs.size() == 0 )
			return column == 0 ? "No View Description selected" : "";

		final HashMap< String, Integer > labels = InterestPointTools.getAllInterestPointMap( viewInterestPoints, currentVDs );

		if ( labels.keySet().size() == 0 )
		{
			return column == 0 ? "No interest points segmented" : "";
		}
		else
		{
			final String label = label( labels, row );

			if ( column == 0 )
				return virtualList( viewInterestPoints, currentVDs, label ) == null ? label : label + " (virtual)";
			else if ( column == 1 )
			{
				try
				{
					return numDetections( viewInterestPoints, currentVDs, label );
				}
				catch ( final RuntimeException e )
				{
					if ( failed.add( label ) )
						IOFunctions.println( "Cannot read the interest points '" + label + "': " + e.getMessage() );

					return "ERROR (see log)";
				}
			}
			else if ( column == 2 )
			{
				// Show fraction when in green state (state 2) for the selected row
				if ( selectedState == 2 && selectedRow == row )
				{
					final int interVisible = numCorrespondingBetweenVisible( viewInterestPoints, currentVDs, label );

				// Count unique consensus sets when displaying 2 views
				if ( currentVDs.size() == 2 )
				{
					final int numSets = numConsensusSetsBetweenVisible( viewInterestPoints, currentVDs, label );
					return interVisible + " (" + numSets + " set" + (numSets == 1 ? "" : "s") + ")";
				}
				else
				{
					final int total = numCorresponding( viewInterestPoints, currentVDs, label );
					return interVisible + "/" + total;
				}
				}
				else
				{
					return numCorresponding( viewInterestPoints, currentVDs, label );
				}
			}
			else if ( column == 3 )
				return numCorrespondences( viewInterestPoints, currentVDs, label );
			else if ( column == 4 )
				return findNumPresent( labels, currentVDs, label );
			else if ( column == ATTRIBUTES_COLUMN )
				return attributes( label ).text;
			else if ( column == PARAMETERS_COLUMN )
				return getParameters( viewInterestPoints, currentVDs, label );
			else
				return -1;
		}
	}

	/**
	 * @return the label's list of the first of the views where it is a virtual label (VirtualFilteredInterestPoints: filters
	 * its base label on the fly, has no points of its own), null if it is a plain label everywhere
	 */
	protected static VirtualFilteredInterestPoints virtualList( final ViewInterestPoints vip, final List< ? extends ViewId > views, final String label )
	{
		for ( final ViewId v : views )
		{
			final InterestPoints ips = vip.getViewInterestPointLists( v ).getInterestPointList( label );

			if ( ips instanceof VirtualFilteredInterestPoints )
				return (VirtualFilteredInterestPoints)ips;
		}

		return null;
	}

	/**
	 * @return whether the row shows a virtual label (for the renderer, which marks it)
	 */
	public boolean isVirtual( final int row )
	{
		return virtualTooltip( row ) != null;
	}

	/**
	 * @return the tooltip of a row that shows a virtual label (its base label and filter), null for a plain label
	 */
	public String virtualTooltip( final int row )
	{
		if ( currentVDs == null || currentVDs.isEmpty() )
			return null;

		final String label = label( InterestPointTools.getAllInterestPointMap( viewInterestPoints, currentVDs ), row );
		final VirtualFilteredInterestPoints virtual = label == null ? null : virtualList( viewInterestPoints, currentVDs, label );

		return virtual == null ? null : "Virtual label, no points of its own: " + virtual.getFilter().describe( virtual.getBaseLabel() );
	}

	/**
	 * Which of the attributes of its points (the DoG response, the intensity and the sigma, see {@link InterestPoint}; NaN =
	 * unknown) a label stores in the selected views: all of them for a label detected since they are stored, none for an
	 * old one, some for one stored in between, or differing between the views (a label detected again for some of them).
	 * An old label registers like any other, but it cannot be filtered by an attribute it lacks (the filter dialogs and the
	 * interactive filter refuse), so the table shows it. After a "+" the per-point arrays of the label
	 * ({@link InterestPointArrays}, e.g. the embeddings pcmatch writes): name and width, and whether every selected view
	 * has one row per point.
	 */
	public static class LabelAttributes
	{
		/** the text of the cell, e.g. "resp, int, sigma", "none (old)", "int only" or "mixed", followed by e.g. " + embedding 64-D" or " + embedding 64-D (1/4 views)" */
		public final String text;

		/** the tooltip of the cell: what is stored or missing and what to do about it, null when there is nothing to say */
		public final String tooltip;

		/** false if an attribute is missing in any of the views, or a per-point array lacks rows for a view or has another number of rows than the view has points (the renderer marks the cell) */
		public final boolean complete;

		public LabelAttributes( final String text, final String tooltip, final boolean complete )
		{
			this.text = text;
			this.tooltip = tooltip;
			this.complete = complete;
		}
	}

	/** nothing to show: the label has no points in the selected views, or they cannot be read (the detections column says so) */
	public static final LabelAttributes noAttributes = new LabelAttributes( "", null, true );

	/**
	 * @return the attributes the label stores in the selected views (see {@link LabelAttributes}), computed once per label
	 *         and selection since it loads the points; nothing if they cannot be read (the detections column reports that)
	 */
	protected LabelAttributes attributes( final String label )
	{
		LabelAttributes result = attributes.get( label );

		if ( result == null )
		{
			try
			{
				result = computeAttributes( viewInterestPoints, currentVDs, label );
			}
			catch ( final RuntimeException e )
			{
				if ( failed.add( label ) )
					IOFunctions.println( "Cannot read the interest points '" + label + "': " + e.getMessage() );

				result = noAttributes;
			}

			attributes.put( label, result );
		}

		return result;
	}

	/**
	 * @return the attributes the label of a row stores (for the renderer, which marks an old label and shows the tooltip), null if the row shows no label
	 */
	public LabelAttributes attributes( final int row )
	{
		if ( currentVDs == null || currentVDs.isEmpty() )
			return null;

		final String label = label( InterestPointTools.getAllInterestPointMap( viewInterestPoints, currentVDs ), row );

		return label == null ? null : attributes( label );
	}

	/**
	 * Loads the points of the label in every view that has it and counts, per attribute, the views with points whose
	 * points all store it ({@link InterestPointFilter#missingAttributes}: one NaN and the view lacks it); then appends
	 * the embeddings of the label ({@link #withArrays})
	 */
	public static LabelAttributes computeAttributes( final ViewInterestPoints vip, final List< ? extends ViewId > views, final String label )
	{
		final EnumSet< Axis > all = EnumSet.allOf( Axis.class );
		final int[] storing = new int[ Axis.values().length ]; // per attribute the number of views with points that store it
		final LinkedHashMap< ViewId, Integer > pointsPerView = new LinkedHashMap<>(); // the views with the label and at least one point
		int numViews = 0; // the views with the label and at least one point

		for ( final ViewId v : views )
		{
			if ( !vip.getViewInterestPointLists( v ).getHashMap().containsKey( label ) )
				continue;

			final Collection< InterestPoint > points = vip.getViewInterestPointLists( v ).getInterestPointList( label ).getInterestPointsCopy().values();

			if ( points.isEmpty() )
				continue;

			++numViews;
			pointsPerView.put( new ViewId( v.getTimePointId(), v.getViewSetupId() ), points.size() );

			final EnumSet< Axis > missing = InterestPointFilter.missingAttributes( points, all );

			for ( final Axis axis : all )
				if ( !missing.contains( axis ) )
					++storing[ axis.ordinal() ];
		}

		if ( numViews == 0 )
			return noAttributes;

		final ArrayList< Axis > everywhere = new ArrayList<>(), nowhere = new ArrayList<>(), someViews = new ArrayList<>();

		for ( final Axis axis : all )
		{
			if ( storing[ axis.ordinal() ] == numViews )
				everywhere.add( axis );
			else if ( storing[ axis.ordinal() ] == 0 )
				nowhere.add( axis );
			else
				someViews.add( axis );
		}

		final String why = " (detected before the attributes were stored, or derived rather than detected, e.g. from correspondences).";
		final String detectAgain = " It cannot be filtered by them; a new detection stores them.";
		final LabelAttributes points;

		if ( !someViews.isEmpty() )
		{
			// e.g. "the response is stored in 2 of 4 views, the intensity in 4 of 4, the sigma in 2 of 4"
			final StringBuilder counts = new StringBuilder();
			int i = 0;

			for ( final Axis axis : all )
			{
				counts.append( i == 0 ? "the " : ", the " ).append( axis.key ).append( i == 0 ? " is stored in " : " in " ).append( storing[ axis.ordinal() ] ).append( " of " ).append( numViews );

				if ( i++ == 0 )
					counts.append( " views" );
			}

			points = new LabelAttributes( "mixed", "The views differ: " + counts + " (the label was detected again for some of the views, the others are old). It cannot be filtered by an attribute that any view lacks; a new detection of all views stores them everywhere.", false );
		}
		else if ( nowhere.isEmpty() )
		{
			points = new LabelAttributes( shortNames( everywhere ), "The points store the DoG response, the intensity and the sigma, the label can be filtered by them.", true );
		}
		else if ( everywhere.isEmpty() )
		{
			points = new LabelAttributes( "none (old)", "Old label: its points store no DoG response, intensity or sigma" + why + detectAgain, false );
		}
		else
		{
			points = new LabelAttributes( shortNames( everywhere ) + " only", "Old label: its points store no " + names( nowhere ) + why + detectAgain, false );
		}

		return withArrays( points, vip, pointsPerView, label );
	}

	/** the full names of attributes for the tooltips, e.g. "response, intensity, sigma" */
	protected static String names( final Collection< Axis > axes )
	{
		return axes.stream().map( a -> a.key ).collect( Collectors.joining( ", " ) );
	}

	/** the short names of attributes for the cell, e.g. "resp, int, sigma" (the tooltip spells them out) */
	protected static String shortNames( final Collection< Axis > axes )
	{
		return axes.stream().map( a -> a == Axis.RESPONSE ? "resp" : a == Axis.INTENSITY ? "int" : a.key ).collect( Collectors.joining( ", " ) );
	}

	/** the state of one per-point array over the selected views with points */
	private static class ArrayState
	{
		final Attributes array;
		int matching = 0; // views with one row per point
		final ArrayList< String > stale = new ArrayList<>(); // views with another number of rows than points, as text

		ArrayState( final Attributes array ) { this.array = array; }
	}

	/**
	 * Appends the embeddings of the label (its per-point arrays, {@link InterestPointArrays}; a filtered label has its base
	 * label's) to the attributes of its points: " + embedding 64-D" when every selected view with points has one row per
	 * point of the (base) label, " + embedding 64-D (1/4 views)" and a mark when views have no rows or another number of
	 * rows than points (the label was detected again since the rows were written, or only some of its points were
	 * embedded); an array not named {@link InterestPointArrays#EMBEDDING} shows its name ("embedding 'descriptor' 64-D");
	 * nothing when the label has no arrays.
	 *
	 * @param points the attributes of the points
	 * @param vip all lists
	 * @param pointsPerView the selected views with the label and at least one point, with their number of points
	 * @param label the label
	 */
	protected static LabelAttributes withArrays( final LabelAttributes points, final ViewInterestPoints vip, final Map< ViewId, Integer > pointsPerView, final String label )
	{
		if ( pointsPerView.isEmpty() )
			return points;

		// the views by timepoint (the arrays are per label and timepoint)
		final TreeMap< Integer, ArrayList< ViewId > > byTimepoint = new TreeMap<>();
		for ( final ViewId v : pointsPerView.keySet() )
			byTimepoint.computeIfAbsent( v.getTimePointId(), t -> new ArrayList<>() ).add( v );

		final TreeMap< String, ArrayState > arrays = new TreeMap<>();
		String base = label;

		for ( final Map.Entry< Integer, ArrayList< ViewId > > e : byTimepoint.entrySet() )
		{
			final ViewInterestPointLists lists = vip.getViewInterestPointLists( e.getValue().get( 0 ) );
			base = InterestPointArrays.baseLabel( lists, label );
			final URI baseDir = lists.getInterestPointList( label ).getBasePath();

			for ( final Attributes array : InterestPointArrays.list( baseDir, base, e.getKey() ) )
			{
				final ArrayState state = arrays.computeIfAbsent( array.name(), n -> new ArrayState( array ) );
				final SortedMap< Integer, Long > rows = InterestPointArrays.rows( baseDir, array );

				for ( final ViewId v : e.getValue() )
				{
					final Long r = rows.get( v.getViewSetupId() );
					if ( r == null )
						continue; // no rows for this view

					// the array has to describe the base label, so a filtered label compares the base's points
					final int expected = base.equals( label ) ? pointsPerView.get( v ) : vip.getViewInterestPointLists( v ).getInterestPointList( base ).getInterestPointsCopy().size();

					if ( r == expected )
						++state.matching;
					else
						state.stale.add( Group.pvid( v ) + ": " + r + " rows, " + expected + " points" );
				}
			}
		}

		if ( arrays.isEmpty() )
			return points;

		final int numViews = pointsPerView.size();
		final StringBuilder text = new StringBuilder( points.text ).append( " + " );
		final StringBuilder tooltip = new StringBuilder( points.tooltip == null ? "" : points.tooltip );
		boolean complete = points.complete;
		int i = 0;

		for ( final ArrayState state : arrays.values() )
		{
			final Attributes array = state.array;
			final String writer = metaString( array.meta(), "writer" ), model = metaString( array.meta(), "model", "file" ), date = metaString( array.meta(), "date" );
			final String written = writer == null ? "" : ", written by " + writer + ( model == null ? "" : " with the model " + model ) + ( date == null ? "" : " on " + date );
			final String under = base.equals( label ) ? "" : " They are stored under the base label '" + base + "'.";

			text.append( i++ == 0 ? "" : ", " ).append( "embedding" ).append( array.name().equals( InterestPointArrays.EMBEDDING ) ? "" : " '" + array.name() + "'" ).append( ' ' ).append( array.columns() ).append( "-D" );

			if ( state.matching == numViews )
			{
				tooltip.append( tooltip.length() == 0 ? "" : " " ).append( "The embeddings in the per-point array '" ).append( array.name() ).append( "' (" ).append( array.columns() ).append( " values per point" ).append( written )
						.append( ") cover every point of the label in the selected views; 'Embeddings' in Register using Interest Points matches on them." ).append( under );
			}
			else
			{
				final int noRows = numViews - state.matching - state.stale.size();
				text.append( " (" ).append( state.matching ).append( '/' ).append( numViews ).append( " views)" );
				complete = false;

				tooltip.append( tooltip.length() == 0 ? "" : " " ).append( "The embeddings in the per-point array '" ).append( array.name() ).append( "' (" ).append( array.columns() ).append( " values per point" ).append( written )
						.append( ") are incomplete for the selected views: " );
				if ( noRows > 0 )
					tooltip.append( noRows ).append( noRows == 1 ? " view has" : " views have" ).append( " no rows" ).append( state.stale.isEmpty() ? "" : ", " );
				if ( !state.stale.isEmpty() )
					tooltip.append( state.stale.size() ).append( state.stale.size() == 1 ? " view has" : " views have" ).append( " another number of rows than points (e.g. " )
							.append( String.join( "; ", state.stale.subList( 0, Math.min( 3, state.stale.size() ) ) ) ).append( "), the label was detected again since the rows were written or only some of its points were embedded" );
				tooltip.append( "; run pcmatch embed again for label '" ).append( base ).append( "'." ).append( under );
			}
		}

		return new LabelAttributes( text.toString(), tooltip.toString(), complete );
	}

	/** @return the string at a path of the writer's meta (e.g. "model", "file"), or null if it is not there */
	private static String metaString( final com.google.gson.JsonObject meta, final String... path )
	{
		com.google.gson.JsonElement element = meta;
		for ( final String key : path )
		{
			if ( element == null || !element.isJsonObject() )
				return null;
			element = element.getAsJsonObject().get( key );
		}
		return element == null || !element.isJsonPrimitive() ? null : element.getAsString();
	}

	protected String getParameters( final ViewInterestPoints vip, final List< ? extends ViewId > views, final String label )
	{
		if ( !vip.getViewInterestPointLists( views.get( 0 ) ).getHashMap().containsKey( label ) )
			return "Not present in all views.";

		final String parameters = vip.getViewInterestPointLists( views.get( 0 ) ).getInterestPointList( label ).getParameters();

		for ( final ViewId v : views )
		{
			if ( !vip.getViewInterestPointLists( v ).getHashMap().containsKey( label ) )
			{
				return "Not present in all views.";
			}

			if ( !vip.getViewInterestPointLists( v ).getInterestPointList( label ).getParameters().equals( parameters ) )
			{
				return "Different types of parameters used for detection, cannot display.";
			}
		}

		return parameters;
	}

	protected int numCorresponding( final ViewInterestPoints vip, final List< ? extends ViewId > views, final String label )
	{
		int sum = 0;

		for ( final ViewId v : views )
		{
			final HashSet< Integer > cips = new HashSet< Integer >();

			if ( vip.getViewInterestPointLists( v ).getHashMap().containsKey( label ) )
				for ( final CorrespondingInterestPoints c : vip.getViewInterestPointLists( v ).getInterestPointList( label ).getCorrespondingInterestPointsCopy() )
					cips.add( c.getDetectionId() );

			sum += cips.size();
		}

		return sum;
	}

	protected int numCorrespondingBetweenVisible( final ViewInterestPoints vip, final List< ? extends ViewId > views, final String label )
	{
		int sum = 0;
		final HashSet< ViewId > visibleViewSet = new HashSet<>( views );

		for ( final ViewId v : views )
		{
			final HashSet< Integer > cips = new HashSet< Integer >();

			if ( vip.getViewInterestPointLists( v ).getHashMap().containsKey( label ) )
			{
				for ( final CorrespondingInterestPoints c : vip.getViewInterestPointLists( v ).getInterestPointList( label ).getCorrespondingInterestPointsCopy() )
				{
					// Only count if correspondence is to another visible view
					if ( visibleViewSet.contains( c.getCorrespondingViewId() ) )
						cips.add( c.getDetectionId() );
				}
			}

			sum += cips.size();
		}

		return sum;
	}

	protected int numConsensusSetsBetweenVisible( final ViewInterestPoints vip, final List< ? extends ViewId > views, final String label )
	{
		final HashSet< ViewId > visibleViewSet = new HashSet<>( views );
		final HashSet< Integer > uniqueSetIds = new HashSet<>();

		for ( final ViewId v : views )
		{
			if ( vip.getViewInterestPointLists( v ).getHashMap().containsKey( label ) )
			{
				for ( final CorrespondingInterestPoints c : vip.getViewInterestPointLists( v ).getInterestPointList( label ).getCorrespondingInterestPointsCopy() )
				{
					// Only count if correspondence is to another visible view
					if ( visibleViewSet.contains( c.getCorrespondingViewId() ) )
						uniqueSetIds.add( c.getConsensusSetId() );
				}
			}
		}

		return uniqueSetIds.size();
	}

	protected String findNumPresent( final HashMap< String, Integer > labels, final List< ? extends ViewId > views, final String label )
	{
		final int num = labels.get( label );
		final int total = views.size();

		return num + "/" + total;
	}

	protected int numCorrespondences( final ViewInterestPoints vip, final List< ? extends ViewId > views, final String label )
	{
		int sum = 0;

		for ( final ViewId v : views )
			if ( vip.getViewInterestPointLists( v ).getHashMap().containsKey( label ) )
				sum += vip.getViewInterestPointLists( v ).getInterestPointList( label ).getCorrespondingInterestPointsCopy().size();

		return sum;
	}

	protected int numDetections( final ViewInterestPoints vip, final List< ? extends ViewId > views, final String label )
	{
		int sum = 0;

		for ( final ViewId v : views )
			if ( vip.getViewInterestPointLists( v ).getHashMap().containsKey( label ) )
				sum += vip.getViewInterestPointLists( v ).getInterestPointList( label ).getInterestPointsCopy().size();

		return sum;
	}

	@Override
	public String getColumnName( final int column )
	{
		return columnNames.get( column );
	}

	public int getState( final int row, final int column )
	{
		if ( row == selectedRow && column == selectedCol )
			return selectedState;
		else
			return 0;
	}

	public void setSelected( final int row, final int col )
	{
		// Clear correspondence color map cache when selection changes
		correspondenceColorMap = null;

		final BasicBDVPopup bdvPopup = panel.viewSetupExplorer.getPanel().runningBdvPopup();

		if ( currentVDs != null && currentVDs.size() != 0 && bdvPopup != null && bdvPopup.bdvRunning() && row >= 0 && row < getRowCount() && col >= 1 && col <= 2  )
		{
			// Handle state cycling when clicking the same cell again
			if ( row == selectedRow && col == selectedCol )
			{
				if ( col == 2 )
				{
					// Column 2: Cycle through states 1 (red) -> 2 (green) -> 0 (none)
					selectedState = (selectedState % 2) + 1; // cycles: 1->2, 2->1 (we'll handle 2->0 below)
					if ( selectedState == 2 )
					{
						// Moving from red to green
						selectedState = 2;
					}
					else
					{
						// Moving from green back to none
						selectedState = 0;
						this.selectedRow = this.selectedCol = -1;
						this.points = new HashMap<>();
						if ( bdvPopup != null && bdvPopup.bdvRunning() )
							bdvPopup.updateBDV();
						return;
					}
				}
				else if ( col == 1 )
				{
					// Column 1: Toggle between selected (1) and unselected (0)
					selectedState = 0;
					this.selectedRow = this.selectedCol = -1;
					this.points = new HashMap<>();
					if ( bdvPopup != null && bdvPopup.bdvRunning() )
						bdvPopup.updateBDV();
					return;
				}
			}
			else
			{
				this.selectedRow = row;
				this.selectedCol = col;
				if ( col == 2 )
					selectedState = 1; // Start with red (all corresponding)
				else
					selectedState = 1; // Column 1 also uses state 1
			}

			final String label = label( InterestPointTools.getAllInterestPointMap( viewInterestPoints, currentVDs ), row );

			if ( label == null )
			{
				this.selectedRow = this.selectedCol = -1;
				this.selectedState = 0;
				this.points = new HashMap<>();
			}
			else if ( col == 1 )
			{
				this.points = new HashMap<>();

				for ( final ViewId v : currentVDs )
					this.points.put( v, viewInterestPoints.getViewInterestPointLists( v ).getInterestPointList( label ).getInterestPointsCopy().values() );
			}
			else // col == 2
			{
				this.points = new HashMap<>();

				if ( selectedState == 1 )
				{
					// State 1: Show all interest points with any correspondences
					for ( final ViewId v : currentVDs )
					{
						final InterestPoints ipList = viewInterestPoints.getViewInterestPointLists( v ).getInterestPointList( label );
						final Map< Integer, InterestPoint > map = ipList.getInterestPointsCopy();
						final Collection< InterestPoint > tmp = new HashSet<>();

						for ( final CorrespondingInterestPoints ip : ipList.getCorrespondingInterestPointsCopy() )
						{
							if ( !map.containsKey( ip.getDetectionId() ) )
							{
								IOFunctions.println( "Inconsistency in the interest points of view: " + Group.pvid( v ) );
								IOFunctions.println( "Cannot find interestpoint for id = " + ip.getDetectionId() );
							}
							else
							{
								tmp.add( map.get( ip.getDetectionId() ) );
							}
						}

						points.put( v, tmp );
					}
				}
				else // selectedState == 2
				{
					// State 2: Show only interest points with correspondences between currently visible views
					final HashSet< ViewId > visibleViewSet = new HashSet<>( currentVDs );

					for ( final ViewId v : currentVDs )
					{
						final InterestPoints ipList = viewInterestPoints.getViewInterestPointLists( v ).getInterestPointList( label );
						final Map< Integer, InterestPoint > map = ipList.getInterestPointsCopy();
						final Collection< InterestPoint > tmp = new HashSet<>();

						for ( final CorrespondingInterestPoints ip : ipList.getCorrespondingInterestPointsCopy() )
						{
							// Only include if correspondence is to another visible view
							if ( visibleViewSet.contains( ip.getCorrespondingViewId() ) )
							{
								if ( !map.containsKey( ip.getDetectionId() ) )
								{
									IOFunctions.println( "Inconsistency in the interest points of view: " + Group.pvid( v ) );
									IOFunctions.println( "Cannot find interestpoint for id = " + ip.getDetectionId() );
								}
								else
								{
									tmp.add( map.get( ip.getDetectionId() ) );
								}
							}
						}

						points.put( v, tmp );
					}
				}
			}

			// Always recreate overlay to get new randomized colors each time
			final BigDataViewer bdv = bdvPopup.getBDV();

			// Remove old overlay if it exists
			if ( interestPointOverlay != null )
			{
				bdv.getViewer().renderTransformListeners().remove( interestPointOverlay );
				bdv.getViewer().getDisplay().overlays().remove( interestPointOverlay );
			}

			// Create new overlay with fresh randomized color palette
			interestPointOverlay = new InterestPointOverlay( bdv.getViewer(), interestPointSources );
			bdv.getViewer().renderTransformListeners().add( interestPointOverlay );
			bdv.getViewer().getDisplay().overlays().add( interestPointOverlay );
			bdvPopup.updateBDV();
		}
		else
		{
			this.selectedRow = this.selectedCol = -1;
			this.selectedState = 0;
			this.points = new HashMap<>();
		}

		if ( bdvPopup != null && bdvPopup.bdvRunning() )
			bdvPopup.updateBDV();
	}

	public int getSelectedRow() { return selectedRow; }
	public int getSelectedCol() { return selectedCol; }

	public List< BasicViewDescription< ? > > filteredViewIdsCurrentTimepoint( final int timepointIndex )
	{
		final ArrayList< BasicViewDescription< ? > > currentlyVisible = new ArrayList<>();

		for ( final BasicViewDescription< ? > viewId : currentVDs )
			if ( timepointIndex == BDVTools.getBDVTimePointIndex( viewId.getTimePoint(), panel.viewSetupExplorer.getSpimData() ) )
				currentlyVisible.add( viewId );

		return currentlyVisible;
	}

	@Override
	public HashMap< ? extends ViewId, ? extends Collection< ? extends RealLocalizable > > getLocalCoordinates( final int timepointIndex )
	{
		final HashMap< ViewId, Collection< ? extends RealLocalizable > > coords = new HashMap<>();
		final List< BasicViewDescription< ? > > currentlyVisible = filteredViewIdsCurrentTimepoint( timepointIndex );

		if ( currentlyVisible == null || currentlyVisible.size() == 0 )
			return coords;

		final HashMap< ViewId, Collection< ? extends RealLocalizable > > displayed = displayedPoints();

		for ( final ViewId viewId : currentlyVisible )
			if ( displayed.containsKey( viewId ) )
				coords.put( viewId, displayed.get( viewId ) );

		return coords;
	}

	/**
	 * Sets the filter the drawn points are previewed with (null = none) and repaints BigDataViewer
	 */
	public void setPreviewFilter( final InterestPointFilter filter )
	{
		this.previewFilter = filter;

		final BasicBDVPopup bdvPopup = panel.viewSetupExplorer.getPanel().runningBdvPopup();
		if ( bdvPopup != null && bdvPopup.bdvRunning() )
			bdvPopup.updateBDV();
	}

	public InterestPointFilter getPreviewFilter() { return previewFilter; }

	/**
	 * @return the points to draw: the selected ones, or of those the ones that pass the preview filter (recomputed only
	 *         when the selection or the filter changed; a point without the attribute does not pass)
	 */
	protected HashMap< ViewId, Collection< ? extends RealLocalizable > > displayedPoints()
	{
		final InterestPointFilter filter = previewFilter;
		final HashMap< ViewId, Collection< ? extends RealLocalizable > > source = points;

		if ( filter == null || filter.isEmpty() )
			return source;

		if ( previewPoints == null || previewSource != source || previewWith != filter )
		{
			final HashMap< ViewId, Collection< ? extends RealLocalizable > > filtered = new HashMap<>();

			for ( final Map.Entry< ViewId, Collection< ? extends RealLocalizable > > e : source.entrySet() )
			{
				// the interest points of the view through the filter (ranges per point, a count limit per view), anything else passes
				final ArrayList< InterestPoint > ips = new ArrayList<>();
				final ArrayList< RealLocalizable > pass = new ArrayList<>();

				for ( final RealLocalizable p : e.getValue() )
					if ( p instanceof InterestPoint )
						ips.add( (InterestPoint)p );
					else
						pass.add( p );

				pass.addAll( filter.filterKnown( ips ) );
				filtered.put( e.getKey(), pass );
			}

			previewPoints = filtered;
			previewSource = source;
			previewWith = filter;
		}

		return previewPoints;
	}

	@Override
	public void getLocalToGlobalTransform( final ViewId viewId, final int timepointIndex, final AffineTransform3D transform )
	{
		if ( currentVDs != null )
		{
			final ViewRegistration vr = panel.viewSetupExplorer.getSpimData().getViewRegistrations().getViewRegistration( viewId );
			vr.updateModel();
			transform.set( vr.getModel() );
		}
	}

	private HashMap< ViewId, HashMap< Integer, Integer > > correspondenceColorMap = null;

	@Override
	public int getCorrespondenceColorId( final ViewId viewId, final int detectionId, final int timepointIndex )
	{
		// Only use correspondence coloring in green mode (state 2) with exactly 2 views
		if ( selectedState != 2 || currentVDs == null || currentVDs.size() != 2 )
			return -1;

		// Build correspondence color map if not already done
		if ( correspondenceColorMap == null )
		{
			correspondenceColorMap = new HashMap<>();

			final HashMap< String, Integer > labels = InterestPointTools.getAllInterestPointMap( viewInterestPoints, currentVDs );
			final String label = label( labels, selectedRow );

			if ( label != null )
			{
				final ViewId viewIdA = currentVDs.get( 0 );
				final ViewId viewIdB = currentVDs.get( 1 );

				correspondenceColorMap.put( viewIdA, new HashMap<>() );
				correspondenceColorMap.put( viewIdB, new HashMap<>() );

				if ( viewInterestPoints.getViewInterestPointLists( viewIdA ).getHashMap().containsKey( label ) )
				{
					final InterestPoints ipListA = viewInterestPoints.getViewInterestPointLists( viewIdA ).getInterestPointList( label );

					for ( final CorrespondingInterestPoints cip : ipListA.getCorrespondingInterestPointsCopy() )
					{
						if ( cip.getCorrespondingViewId().equals( viewIdB ) )
						{
							// Use consensus set ID as color ID - all correspondences from the same
							// multi-consensus RANSAC set will have the same color
							// Offset by 1 so -1 (single-consensus) becomes 0, and multi-consensus 0,1,2... become 1,2,3...
							final int colorId = cip.getConsensusSetId() + 1;
							correspondenceColorMap.get( viewIdA ).put( cip.getDetectionId(), colorId );
							correspondenceColorMap.get( viewIdB ).put( cip.getCorrespondingDetectionId(), colorId );
						}
					}
				}
			}
		}

		// Look up color ID for this detection
		if ( correspondenceColorMap.containsKey( viewId ) && correspondenceColorMap.get( viewId ).containsKey( detectionId ) )
			return correspondenceColorMap.get( viewId ).get( detectionId );

		return -1;
	}

	@Override
	public int getShapeType( final ViewId viewId, final int timepointIndex )
	{
		// Only use different shapes in green mode (state 2) with exactly 2 views
		if ( selectedState != 2 || currentVDs == null || currentVDs.size() != 2 )
			return 0; // Circle

		// First view gets cross (+), second view gets diagonal cross (×)
		if ( viewId.equals( currentVDs.get( 0 ) ) )
			return 1; // Cross
		else if ( viewId.equals( currentVDs.get( 1 ) ) )
			return 2; // Diagonal cross

		return 0; // Circle (fallback)
	}

	@Override
	public double getDistanceFade()
	{
		return distanceFade;
	}

	@Override
	public boolean isFilterMode()
	{
		return filterMode;
	}

	public void setDistanceFade( final double distanceFade, final boolean filterMode )
	{
		this.distanceFade = distanceFade;
		this.filterMode = filterMode;

		// Update BDV if it's running
		final BasicBDVPopup bdvPopup = panel.viewSetupExplorer.getPanel().runningBdvPopup();
		if ( bdvPopup != null && bdvPopup.bdvRunning() )
			bdvPopup.updateBDV();
	}

	@Override
	public double getPointSizeScale()
	{
		return pointSizeScale;
	}

	public void setPointSizeScale( final double pointSizeScale )
	{
		this.pointSizeScale = pointSizeScale;

		// Update BDV if it's running
		final BasicBDVPopup bdvPopup = panel.viewSetupExplorer.getPanel().runningBdvPopup();
		if ( bdvPopup != null && bdvPopup.bdvRunning() )
			bdvPopup.updateBDV();
	}

	@Override
	public double getPlaneThickness()
	{
		return planeThickness;
	}

	public void setPlaneThickness( final double planeThickness )
	{
		this.planeThickness = planeThickness;

		// Update BDV if it's running
		final BasicBDVPopup bdvPopup = panel.viewSetupExplorer.getPanel().runningBdvPopup();
		if ( bdvPopup != null && bdvPopup.bdvRunning() )
			bdvPopup.updateBDV();
	}
}
