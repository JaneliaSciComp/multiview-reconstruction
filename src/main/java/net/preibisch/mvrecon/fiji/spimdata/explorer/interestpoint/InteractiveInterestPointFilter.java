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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.IntervalMarker;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.StandardXYBarPainter;
import org.jfree.chart.renderer.xy.XYBarRenderer;
import org.jfree.chart.ui.Layer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import bdv.ui.rangeslider.RangeSlider;
import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.Filter_Detections;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter.Axis;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;

/**
 * The interactive filter of the interest point explorer: one histogram per attribute (|response|, intensity, sigma) of
 * the points of a label in the selected views, each with a range slider and two bound fields (a knob at the end of the
 * slider or an empty field = no bound), the points drawn in BigDataViewer follow the filter live
 * (InterestPointTableModel.setPreviewFilter). The histograms can be linked ("Link histograms", off by default): each one is
 * then counted from the points that pass the bounds of the other two axes, so that narrowing one range shows the distribution
 * of what is left in the others (the attributes are kept as flat arrays per axis with a bin and a pass flag per point, so a change is a few
 * passes over primitive arrays, milliseconds for a million points). The current ranges are remembered as the defaults of
 * the "Filter interest points" dialog (Filter_Detections), so that they can be applied to any views later, and
 * "Create filtered label ..." creates the filtered label (or a plain copy) for the selected or for all views right away.
 *
 * @author Stephan Preibisch
 */
public class InteractiveInterestPointFilter extends JFrame
{
	private static final long serialVersionUID = 1L;

	public static int numBins = 100;
	public static int sliderResolution = 1000;
	public static int chartWidth = 520;
	public static boolean[] defaultLog = new boolean[] { true, true, false };
	/** whether the histograms are linked (each shows only the points that pass the bounds of the other two), the last choice is kept */
	public static boolean defaultLinked = false;

	final SpimData2 data;
	final String label;
	final List< ViewId > views;
	final int numPoints;
	final float[][] values; // per axis the value of every point (|response| for the response), NaN = not stored
	final Consumer< InterestPointFilter > preview;
	final Runnable onCreated;
	final AxisPanel[] axisPanels;
	final JCheckBox linkBox;
	final JLabel summary, suffixLabel;

	InterestPointFilter filter = new InterestPointFilter( Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN );

	/**
	 * @param panel - the explorer panel (its table model draws the preview in BigDataViewer, its explorer is refreshed after creating a label)
	 * @param label - the label
	 * @param viewCollection - the views, the ones lacking the label are ignored
	 */
	public InteractiveInterestPointFilter( final InterestPointExplorerPanel panel, final String label, final Collection< ? extends ViewId > viewCollection )
	{
		this(
				panel.viewSetupExplorer.getSpimData(),
				label,
				viewCollection,
				filter -> panel.getTableModel().setPreviewFilter( filter ),
				() -> panel.viewSetupExplorer.getPanel().updateContent() );
	}

	/**
	 * @param data - the dataset
	 * @param label - the label
	 * @param viewCollection - the views, the ones lacking the label are ignored
	 * @param preview - called with the current filter on every change and with null when the window closes (the preview in BigDataViewer), may be null
	 * @param onCreated - called after a label was created (refreshes the explorer), may be null
	 */
	public InteractiveInterestPointFilter(
			final SpimData2 data,
			final String label,
			final Collection< ? extends ViewId > viewCollection,
			final Consumer< InterestPointFilter > preview,
			final Runnable onCreated )
	{
		super( "Filter interest points '" + label + "'" );

		this.data = data;
		this.label = label;
		this.views = viewsWithLabel( data, label, viewCollection );
		this.preview = preview;
		this.onCreated = onCreated;

		// the attributes as one flat array per axis, the point objects are not kept (a split dataset has millions)
		final ArrayList< InterestPoint > points = new ArrayList<>();

		for ( final ViewId viewId : views )
			points.addAll( data.getViewInterestPoints().getViewInterestPointLists( viewId ).getInterestPointList( label ).getInterestPointsCopy().values() );

		this.numPoints = points.size();
		this.values = new float[ Axis.values().length ][];

		for ( final Axis axis : Axis.values() )
			values[ axis.ordinal() ] = values( points, axis );

		final JPanel content = new JPanel();
		content.setLayout( new BoxLayout( content, BoxLayout.Y_AXIS ) );
		content.setBorder( BorderFactory.createEmptyBorder( 5, 5, 5, 5 ) );

		final JLabel header = new JLabel( "<html><body style='width: " + chartWidth + "px'><b>" + numPoints + " points of '" + label + "' in " + views.size() + " view" + ( views.size() == 1 ? "" : "s" ) +
				"</b><br>Move the knobs or type bounds (a knob at the end or an empty field = no bound), the points drawn in BigDataViewer follow. " +
				"The ranges become the defaults of 'Filter interest points' (also for other views).</body></html>" );
		header.setFont( GUIHelper.smallStatusFont );
		header.setBorder( BorderFactory.createEmptyBorder( 0, 0, 5, 0 ) );
		content.add( header );

		this.linkBox = new JCheckBox( "Link histograms (each shows only the points that pass the bounds of the other two)", defaultLinked );
		linkBox.setFont( GUIHelper.smallStatusFont );
		linkBox.setToolTipText( "<html>On: whenever a bound changes, the histograms of the other two attributes are counted again from the points that pass it,<br>" +
				"so each histogram shows the distribution of what is left. Off: every histogram shows all points that have the attribute.</html>" );

		final JPanel linkRow = new JPanel( new FlowLayout( FlowLayout.LEFT, 0, 0 ) );
		linkRow.add( linkBox );
		content.add( linkRow );

		this.axisPanels = new AxisPanel[ Axis.values().length ];

		for ( final Axis axis : Axis.values() )
		{
			axisPanels[ axis.ordinal() ] = new AxisPanel( axis );
			content.add( axisPanels[ axis.ordinal() ] );
		}

		// a linked histogram needs the pass flags of the other panels, so they are binned once all exist
		for ( final AxisPanel a : axisPanels )
			a.rebin();

		linkBox.addActionListener( e -> linkChanged() );

		this.summary = new JLabel( " " );
		summary.setFont( GUIHelper.mediumstatusNonItalicfont );
		summary.setBorder( BorderFactory.createEmptyBorder( 5, 0, 0, 0 ) );
		content.add( summary );

		// plain text (the underscores of the suffix are hard to see in an HTML label)
		this.suffixLabel = new JLabel( " " );
		suffixLabel.setFont( GUIHelper.smallStatusFont );
		suffixLabel.setBorder( BorderFactory.createEmptyBorder( 0, 0, 5, 0 ) );
		content.add( suffixLabel );

		final JPanel buttons = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );
		final JButton reset = new JButton( "Reset" );
		final JButton create = new JButton( "Create filtered label ..." );
		final JButton close = new JButton( "Close" );

		reset.addActionListener( e -> { for ( final AxisPanel a : axisPanels ) a.reset(); } );
		create.addActionListener( e -> create() );
		close.addActionListener( e -> dispose() );

		buttons.add( reset );
		buttons.add( create );
		buttons.add( close );
		content.add( buttons );

		add( content, BorderLayout.CENTER );

		setDefaultCloseOperation( DISPOSE_ON_CLOSE );

		updateFilter();
		pack();
	}

	/**
	 * Closing the window (the close button or the explorer) ends the preview
	 */
	@Override
	public void dispose()
	{
		super.dispose();

		if ( preview != null )
			preview.accept( null );
	}

	public String getLabel() { return label; }
	public InterestPointFilter getFilter() { return filter; }
	public int getNumPoints() { return numPoints; }
	public boolean isLinked() { return linkBox.isSelected(); }
	public String getSummaryText() { return summary.getText(); }

	/** @return the counts per bin the histogram of the axis shows right now (empty if the attribute is not stored) */
	public double[] getHistogramCounts( final Axis axis ) { return axisPanels[ axis.ordinal() ].shownCounts.clone(); }

	/** @return the count line under the histogram of the axis */
	public String getCountText( final Axis axis ) { return axisPanels[ axis.ordinal() ].count.getText(); }

	/**
	 * Links or unlinks the histograms, as the checkbox does
	 */
	public void setLinked( final boolean linked )
	{
		if ( linkBox.isSelected() != linked )
		{
			linkBox.setSelected( linked );
			linkChanged();
		}
	}

	/**
	 * Sets the bounds of an axis as if they were typed into its fields (NaN = unbounded, swapped bounds are swapped back);
	 * nothing happens for an axis that cannot be filtered (attribute not stored or constant)
	 */
	public void setRange( final Axis axis, final double min, final double max )
	{
		final AxisPanel a = axisPanels[ axis.ordinal() ];

		if ( !a.present || a.constant )
			return;

		a.minField.setText( Double.isNaN( min ) ? "" : InterestPointFilter.plain( min ) );
		a.maxField.setText( Double.isNaN( max ) ? "" : InterestPointFilter.plain( max ) );
		a.fromFields();
	}

	/**
	 * The link checkbox changed: remember it, count the histograms again
	 */
	protected void linkChanged()
	{
		final boolean linked = defaultLinked = linkBox.isSelected();

		Arrays.stream( axisPanels ).parallel().forEach( a -> a.recount( linked ) );

		for ( final AxisPanel a : axisPanels )
			a.showCounts();
	}

	/**
	 * Collects the filter of the axis panels, updates the counts (and the histograms if linked), the preview in BigDataViewer and the defaults of Filter_Detections
	 */
	protected void updateFilter()
	{
		final double[] bounds = new double[ 2 * Axis.values().length ];

		for ( final Axis axis : Axis.values() )
		{
			bounds[ 2 * axis.ordinal() ] = axisPanels[ axis.ordinal() ].min();
			bounds[ 2 * axis.ordinal() + 1 ] = axisPanels[ axis.ordinal() ].max();
		}

		this.filter = new InterestPointFilter( bounds[ 0 ], bounds[ 1 ], bounds[ 2 ], bounds[ 3 ], bounds[ 4 ], bounds[ 5 ] );

		final int pass = countPassing( null, passFlags( null ), numPoints );

		final String description = filter.describe( label );

		summary.setText( pass + " of " + numPoints + " points pass" + ( filter.isEmpty() ? " (no bounds set)" : ": " + description.substring( description.indexOf( ':' ) + 2 ) ) );
		suffixLabel.setText( filter.isEmpty() ? " " : "label suffix: " + filter.suffix() + "   (new label: " + label + filter.suffix() + ")" );

		final boolean linked = linkBox.isSelected();

		// linked histograms: one pass over the points per axis, the three in parallel
		if ( linked )
			Arrays.stream( axisPanels ).parallel().forEach( a -> a.recount( true ) );

		for ( final AxisPanel a : axisPanels )
			a.updateCount( linked );

		if ( preview != null )
			preview.accept( filter );

		Filter_Detections.remember( label, filter );
	}

	/**
	 * @return the pass flags of all axes but one (null = of all), i.e. per point whether it passes the bounds of each of those axes
	 */
	protected boolean[][] passFlags( final AxisPanel except )
	{
		final ArrayList< boolean[] > flags = new ArrayList<>();

		for ( final AxisPanel a : axisPanels )
			if ( a != except )
				flags.add( a.pass );

		return flags.toArray( new boolean[ 0 ][] );
	}

	/**
	 * Asks for the views (selected or all with the label), the new label and whether to copy, then creates the label(s)
	 */
	protected void create()
	{
		if ( filter.isEmpty() )
		{
			IOFunctions.println( "No bounds set, nothing to filter by." );
			return;
		}

		final List< ViewId > all = viewsWithLabel( data, label, data.getSequenceDescription().getViewDescriptions().values() );

		final String[] choices = new String[] { "Selected views (" + views.size() + ")", "All views with '" + label + "' (" + all.size() + ")" };

		final GenericDialog gd = new GenericDialog( "Create filtered label" );
		gd.addMessage( filter.describe( label ), GUIHelper.smallStatusFont );
		gd.addChoice( "Apply_to", choices, choices[ 0 ] );
		gd.addStringField( "New_label", label + filter.suffix(), 30 );
		gd.addCheckbox( "Store_as_plain_label (copy the points instead of filtering on the fly)", Filter_Detections.defaultCopy );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return;

		final List< ViewId > target = gd.getNextChoiceIndex() == 0 ? views : all;
		final String newLabel = gd.getNextString().trim();
		final boolean copy = Filter_Detections.defaultCopy = gd.getNextBoolean();

		createLabel( target, newLabel, copy );
	}

	/**
	 * Creates the filtered label (or a plain copy) with the current filter for the views and refreshes the explorer
	 *
	 * @return whether it was created (not if the filter is empty, the name is empty or the base label, or the label exists in one of the views)
	 */
	public boolean createLabel( final List< ViewId > target, final String newLabel, final boolean copy )
	{
		if ( filter.isEmpty() )
		{
			IOFunctions.println( "No bounds set, nothing to filter by." );
			return false;
		}

		if ( newLabel == null || newLabel.trim().isEmpty() || newLabel.trim().equals( label ) )
		{
			IOFunctions.println( "The new label must be set and differ from '" + label + "'." );
			return false;
		}

		for ( final ViewId viewId : target )
			if ( data.getViewInterestPoints().getViewInterestPointLists( viewId ).contains( newLabel.trim() ) )
			{
				IOFunctions.println( "Label '" + newLabel.trim() + "' exists already in view " + viewId.getTimePointId() + "/" + viewId.getViewSetupId() + ", not creating it." );
				return false;
			}

		Filter_Detections.create( data, target, label, newLabel.trim(), filter, copy );
		Filter_Detections.defaultNewLabel = "";

		if ( onCreated != null )
			onCreated.run();

		return true;
	}

	/**
	 * @return the views (sorted) of the collection that are present and have the label
	 */
	public static List< ViewId > viewsWithLabel( final SpimData2 data, final String label, final Collection< ? extends ViewId > viewCollection )
	{
		final ArrayList< ViewId > views = new ArrayList<>();

		for ( final ViewId viewId : viewCollection )
		{
			final ViewDescription vd = data.getSequenceDescription().getViewDescription( viewId );

			if ( vd == null || !vd.isPresent() )
				continue;

			final ViewInterestPointLists vipl = data.getViewInterestPoints().getViewInterestPointLists( viewId );

			if ( vipl != null && vipl.contains( label ) )
				views.add( new ViewId( viewId.getTimePointId(), viewId.getViewSetupId() ) );
		}

		Collections.sort( views );

		return views;
	}

	/**
	 * @return the value of every point on the axis (|response| for the response), in the order of the collection, NaN where not stored
	 */
	public static float[] values( final Collection< ? extends InterestPoint > points, final Axis axis )
	{
		final float[] values = new float[ points.size() ];
		int i = 0;

		for ( final InterestPoint ip : points )
			values[ i++ ] = InterestPointFilter.value( ip, axis );

		return values;
	}

	/**
	 * @return the finite values of the axis (|response| for the response), sorted ascending
	 */
	public static float[] sortedValues( final Collection< ? extends InterestPoint > points, final Axis axis )
	{
		return sortedFinite( values( points, axis ) );
	}

	/**
	 * @return the finite values, sorted ascending
	 */
	public static float[] sortedFinite( final float[] values )
	{
		final float[] finite = new float[ values.length ];
		int n = 0;

		for ( final float v : values )
			if ( finite( v ) )
				finite[ n++ ] = v;

		final float[] sorted = Arrays.copyOf( finite, n );
		Arrays.sort( sorted );

		return sorted;
	}

	protected static boolean finite( final float v ) { return !Float.isNaN( v ) && !Float.isInfinite( v ); }

	/**
	 * Sets per point whether its value lies in [min, max] (NaN = unbounded, compared at float precision like
	 * InterestPointFilter): without bounds every point passes, with bounds a point without a value does not (as acceptKnown)
	 */
	public static void inRange( final float[] values, final double min, final double max, final boolean[] flags )
	{
		if ( Double.isNaN( min ) && Double.isNaN( max ) )
		{
			Arrays.fill( flags, true );
			return;
		}

		final float lo = Double.isNaN( min ) ? Float.NEGATIVE_INFINITY : (float)min;
		final float hi = Double.isNaN( max ) ? Float.POSITIVE_INFINITY : (float)max;

		for ( int i = 0; i < values.length; ++i )
			flags[ i ] = values[ i ] >= lo && values[ i ] <= hi; // false for NaN
	}

	/**
	 * @return whether all flags of the point are set
	 */
	protected static boolean all( final boolean[][] flags, final int i )
	{
		for ( final boolean[] f : flags )
			if ( !f[ i ] )
				return false;

		return true;
	}

	/**
	 * @param bin - the bin of every point (-1 = no value), null = no condition on it
	 * @param flags - per axis the pass flags of every point
	 * @return the number of points that have a bin (if given) and pass all flags
	 */
	public static int countPassing( final int[] bin, final boolean[][] flags, final int n )
	{
		int count = 0;

		for ( int i = 0; i < n; ++i )
			if ( ( bin == null || bin[ i ] >= 0 ) && all( flags, i ) )
				++count;

		return count;
	}

	/**
	 * Counts the points that have a bin and pass all flags into the bins (the histogram of an axis linked to the others)
	 * and, in the same pass, how many of them lie in the range of the axis itself
	 *
	 * @param bin - the bin of every point (-1 = no value, not counted)
	 * @param flags - per axis the pass flags of every point, only points that pass all of them are counted (e.g. the other axes of a linked histogram), empty = all points
	 * @param own - the pass flags of the axis itself (null = none), they do not thin the histogram
	 * @param counts - the counts per bin, filled
	 * @return the number of counted points that pass the own flags too
	 */
	public static int histogram( final int[] bin, final boolean[][] flags, final boolean[] own, final double[] counts )
	{
		int inRange = 0;

		for ( int i = 0; i < bin.length; ++i )
		{
			if ( bin[ i ] < 0 || !all( flags, i ) )
				continue;

			++counts[ bin[ i ] ];

			if ( own == null || own[ i ] )
				++inRange;
		}

		return inRange;
	}

	/**
	 * @return the bin of a value in numBins equal bins over [lo, lo + size] of the (linear or log) space, -1 if the value is not finite
	 */
	public static int bin( final float v, final double lo, final double size, final boolean log, final int numBins )
	{
		if ( !finite( v ) )
			return -1;

		return Math.max( 0, Math.min( numBins - 1, (int)( ( space( v, log ) - lo ) / size * numBins ) ) );
	}

	/**
	 * @return the number of values in [min, max] (NaN = unbounded) of a sorted array
	 */
	public static int count( final float[] sorted, final double min, final double max )
	{
		final int lo = Double.isNaN( min ) ? 0 : lowerIndex( sorted, (float)min );
		final int hi = Double.isNaN( max ) ? sorted.length : upperIndex( sorted, (float)max );

		return Math.max( 0, hi - lo );
	}

	/** @return the index of the first value &gt;= v */
	protected static int lowerIndex( final float[] sorted, final float v )
	{
		int lo = 0, hi = sorted.length;

		while ( lo < hi )
		{
			final int mid = ( lo + hi ) >>> 1;

			if ( sorted[ mid ] < v )
				lo = mid + 1;
			else
				hi = mid;
		}

		return lo;
	}

	/** @return the index after the last value &lt;= v */
	protected static int upperIndex( final float[] sorted, final float v )
	{
		int lo = 0, hi = sorted.length;

		while ( lo < hi )
		{
			final int mid = ( lo + hi ) >>> 1;

			if ( sorted[ mid ] <= v )
				lo = mid + 1;
			else
				hi = mid;
		}

		return lo;
	}

	/**
	 * @param sorted - the values
	 * @param log - whether to bin log10( value ) (all values must be &gt; 0)
	 * @return { the bin centers (in value units), the counts } of numBins equal bins between the smallest and the largest value (in linear or log space)
	 */
	public static double[][] histogram( final float[] sorted, final boolean log, final int numBins )
	{
		final double[] centers = new double[ numBins ], counts = new double[ numBins ];

		if ( sorted.length == 0 )
			return new double[][] { centers, counts };

		final double lo = space( sorted[ 0 ], log ), hi = space( sorted[ sorted.length - 1 ], log );
		final double size = Math.max( hi - lo, 1e-12 );

		for ( final float v : sorted )
			++counts[ bin( v, lo, size, log, numBins ) ];

		for ( int b = 0; b < numBins; ++b )
			centers[ b ] = lo + size * ( b + 0.5 ) / numBins;

		return new double[][] { centers, counts };
	}

	/** value to slider space */
	protected static double space( final double value, final boolean log ) { return log ? Math.log10( value ) : value; }

	/** slider space to value */
	protected static double value( final double space, final boolean log ) { return log ? Math.pow( 10, space ) : space; }

	/**
	 * @return the value rounded to three significant digits (what a knob position becomes), e.g. 0.00432
	 */
	public static double round3( final double value )
	{
		if ( value == 0 || Double.isNaN( value ) || Double.isInfinite( value ) )
			return value;

		return new BigDecimal( value ).round( new MathContext( 3 ) ).doubleValue();
	}

	/**
	 * One attribute: the histogram with the range marked, the range slider, the bound fields, the log checkbox and the count
	 */
	protected class AxisPanel extends JPanel
	{
		private static final long serialVersionUID = 1L;

		final Axis axis;
		final float[] value; // of every point, NaN = not stored
		final float[] sorted; // the finite values, ascending
		final boolean present;
		final boolean constant; // all points have the same value (e.g. the sigma of a single-scale DoG), nothing to filter

		// per point: the bin in the current space (-1 = no value) and whether it passes the bounds of this axis
		final int[] bin;
		final boolean[] pass;

		final RangeSlider slider;
		final JTextField minField, maxField;
		final JCheckBox logBox;
		final JLabel count;
		final XYSeriesCollection dataset;
		final IntervalMarker marker;
		final JFreeChart chart;
		XYSeries series;
		double[] counts; // the last recount: per bin, and the points in range
		int inRange;
		double[] shownCounts; // what the chart shows, so that nothing is redrawn when the counts did not change

		// the bounds (NaN = unbounded), the knobs and the fields are views of them
		double min = Double.NaN, max = Double.NaN;
		boolean log;
		double lo, hi; // the ends of the slider in slider space
		boolean updating = false;

		public AxisPanel( final Axis axis )
		{
			this.axis = axis;
			this.value = values[ axis.ordinal() ];
			this.sorted = sortedFinite( value );
			this.present = sorted.length > 0;
			this.constant = present && sorted[ 0 ] == sorted[ sorted.length - 1 ];
			this.log = defaultLog[ axis.ordinal() ] && present && !constant && sorted[ 0 ] > 0;
			this.bin = new int[ numPoints ];
			this.pass = new boolean[ numPoints ];
			Arrays.fill( pass, true ); // no bounds yet

			setLayout( new BorderLayout() );
			setBorder( BorderFactory.createTitledBorder( title( axis ) ) );

			this.dataset = new XYSeriesCollection();
			this.chart = ChartFactory.createXYBarChart( null, axisLabel(), false, "count", dataset, PlotOrientation.VERTICAL, false, false, false );
			this.marker = new IntervalMarker( 0, 0, new Color( 60, 120, 220, 60 ) );

			final XYPlot plot = chart.getXYPlot();
			final XYBarRenderer renderer = (XYBarRenderer)plot.getRenderer();
			renderer.setSeriesPaint( 0, Color.gray );
			renderer.setDrawBarOutline( false );
			renderer.setShadowVisible( false );
			renderer.setBarPainter( new StandardXYBarPainter() );
			plot.addDomainMarker( marker, Layer.BACKGROUND );
			plot.setBackgroundPaint( Color.white );

			final ChartPanel chartPanel = new ChartPanel( chart, false );
			chartPanel.setPreferredSize( new Dimension( chartWidth, 150 ) );
			chartPanel.setPopupMenu( null );
			chartPanel.setMouseZoomable( false );
			add( chartPanel, BorderLayout.CENTER );

			this.slider = new RangeSlider( 0, sliderResolution );
			slider.setValue( 0 );
			slider.setUpperValue( sliderResolution );

			this.minField = new JTextField( 7 );
			this.maxField = new JTextField( 7 );
			this.logBox = new JCheckBox( "log", log );
			this.count = new JLabel();
			count.setFont( GUIHelper.smallStatusFont );

			final JPanel controls = new JPanel( new BorderLayout( 5, 0 ) );
			final JPanel fields = new JPanel( new FlowLayout( FlowLayout.LEFT, 3, 0 ) );
			fields.add( new JLabel( "min" ) );
			fields.add( minField );
			fields.add( new JLabel( "max" ) );
			fields.add( maxField );
			fields.add( logBox );
			fields.add( count );
			controls.add( slider, BorderLayout.CENTER );
			controls.add( fields, BorderLayout.SOUTH );
			add( controls, BorderLayout.SOUTH );

			// rebin() is called by the window once all panels exist (a linked histogram needs the pass flags of the others)

			if ( !present || constant )
			{
				count.setText( !present ?
						"not stored for this label (detect it again to store it)" :
						"all " + sorted.length + " points have " + axis.description + " = " + InterestPointFilter.plain( round3( sorted[ 0 ] ) ) + ", nothing to filter" );
				slider.setEnabled( false );
				minField.setEnabled( false );
				maxField.setEnabled( false );
				logBox.setEnabled( false );
				return;
			}

			logBox.setEnabled( sorted[ 0 ] > 0 );

			slider.addChangeListener( e -> { if ( !updating ) fromSlider(); } );

			final ActionListener fieldListener = e -> { if ( !updating ) fromFields(); };
			minField.addActionListener( fieldListener );
			maxField.addActionListener( fieldListener );

			final FocusAdapter focusListener = new FocusAdapter()
			{
				@Override
				public void focusLost( final FocusEvent e ) { if ( !updating ) fromFields(); }
			};
			minField.addFocusListener( focusListener );
			maxField.addFocusListener( focusListener );

			logBox.addActionListener( e -> { log = logBox.isSelected(); rebin(); toWidgets(); } );
		}

		public double min() { return min; }
		public double max() { return max; }

		protected String title( final Axis axis )
		{
			switch ( axis )
			{
			case RESPONSE: return "|DoG response| (image normalized to [0,1])";
			case INTENSITY: return "intensity (image units, at the detection resolution)";
			default: return "sigma (px at full resolution)";
			}
		}

		protected String axisLabel() { return ( log ? "log10 " : "" ) + axis.description; }

		/**
		 * Bins the values in the current space (linear or log), sets the chart up for it and counts (updateHistogram)
		 */
		protected void rebin()
		{
			final double[] centers;
			double width = Double.NaN;

			if ( constant )
			{
				// one bar in the middle
				final double v = sorted[ 0 ], d = Math.max( Math.abs( v ) * 0.1, 0.5 );
				centers = new double[] { v };
				lo = v - d;
				hi = v + d;
				width = d / 10;

				for ( int i = 0; i < numPoints; ++i )
					bin[ i ] = finite( value[ i ] ) ? 0 : -1;
			}
			else if ( present )
			{
				lo = space( sorted[ 0 ], log );
				hi = space( sorted[ sorted.length - 1 ], log );

				if ( hi <= lo )
					hi = lo + 1e-6;

				centers = new double[ numBins ];

				for ( int b = 0; b < numBins; ++b )
					centers[ b ] = lo + ( hi - lo ) * ( b + 0.5 ) / numBins;

				for ( int i = 0; i < numPoints; ++i )
					bin[ i ] = bin( value[ i ], lo, hi - lo, log, numBins );
			}
			else
			{
				lo = 0;
				hi = 1;
				centers = new double[ 0 ];
				Arrays.fill( bin, -1 );
			}

			// the x range first: JFreeChart auto-ranges the counts over the x range the axis shows at the time the data changes
			final NumberAxis domain = (NumberAxis)chart.getXYPlot().getDomainAxis();
			domain.setLabel( axisLabel() );
			domain.setRange( lo - ( hi - lo ) * 0.01, hi + ( hi - lo ) * 0.01 );

			this.series = new XYSeries( axis.key );

			for ( final double c : centers )
				series.add( c, 0 );

			this.shownCounts = null;

			dataset.removeAllSeries();
			dataset.addSeries( series );

			if ( Double.isNaN( width ) )
			{
				dataset.setAutoWidth( true );
			}
			else
			{
				dataset.setAutoWidth( false );
				dataset.setIntervalWidth( width );
			}

			updateHistogram();
			updateMarker();
		}

		/**
		 * Counts the histogram and the points in range again and shows them
		 */
		protected void updateHistogram()
		{
			recount( linkBox.isSelected() );
			showCounts();
		}

		/**
		 * Counts the histogram (all points with a value, or, if linked, only the points that pass the bounds of the other two
		 * axes) and the points in range in one pass over the points; touches no Swing, so the axes can be counted in parallel
		 */
		protected void recount( final boolean linked )
		{
			final boolean[][] others = linked ? passFlags( this ) : new boolean[ 0 ][];

			counts = new double[ series.getItemCount() ];
			inRange = histogram( bin, others, pass, counts );
		}

		/**
		 * Shows the last recount: the chart is only touched when the counts changed, then the count line
		 */
		protected void showCounts()
		{
			int shown = 0;

			for ( final double c : counts )
				shown += (int)c;

			if ( !Arrays.equals( counts, shownCounts ) )
			{
				series.setNotify( false );

				for ( int b = 0; b < counts.length; ++b )
					series.updateByIndex( b, counts[ b ] );

				series.setNotify( true ); // one change event for the chart
				shownCounts = counts;

				// auto-ranging an all-zero histogram gives a y axis of 1e-8
				final NumberAxis range = (NumberAxis)chart.getXYPlot().getRangeAxis();

				if ( shown == 0 )
					range.setRange( 0, 1 );
				else if ( !range.isAutoRange() )
					range.setAutoRange( true );
			}

			if ( present && !constant )
				count.setText( countText( inRange, shown ) );
		}

		protected String countText( final int inRange, final int shown )
		{
			return inRange + " of " + shown + " in range" +
					( shown < sorted.length ? " (" + shown + " of " + sorted.length + " pass the other bounds)" : "" ) +
					( sorted.length < numPoints ? " (" + ( numPoints - sorted.length ) + " without value)" : "" );
		}

		protected void updateMarker()
		{
			final double start = Double.isNaN( min ) ? lo : Math.max( lo, Math.min( hi, space( min, log ) ) );
			final double end = Double.isNaN( max ) ? hi : Math.max( lo, Math.min( hi, space( max, log ) ) );

			marker.setStartValue( start );
			marker.setEndValue( end );
		}

		/**
		 * The knobs moved: a knob at the end is no bound, otherwise the value of its position rounded to three significant digits
		 */
		protected void fromSlider()
		{
			final int l = slider.getValue(), u = slider.getUpperValue();

			min = l <= 0 ? Double.NaN : round3( value( lo + ( hi - lo ) * l / sliderResolution, log ) );
			max = u >= sliderResolution ? Double.NaN : round3( value( lo + ( hi - lo ) * u / sliderResolution, log ) );

			if ( !Double.isNaN( min ) && min < 0 )
				min = 0;

			if ( !Double.isNaN( max ) && max < 0 )
				max = 0;

			// the rounding may push the lower bound above the upper one when the knobs touch
			if ( !Double.isNaN( min ) && !Double.isNaN( max ) && min > max )
				max = min;

			boundsChanged();
		}

		/**
		 * A bound was typed: empty = no bound, the knob follows (clamped to the slider)
		 */
		protected void fromFields()
		{
			final double newMin = parse( minField.getText(), min ), newMax = parse( maxField.getText(), max );

			if ( Double.compare( newMin, min ) == 0 && Double.compare( newMax, max ) == 0 )
			{
				toWidgets();
				return;
			}

			min = newMin;
			max = newMax;

			if ( !Double.isNaN( min ) && !Double.isNaN( max ) && min > max )
			{
				final double tmp = min;
				min = max;
				max = tmp;
			}

			boundsChanged();
		}

		/**
		 * The bounds changed: the pass flags of this axis, the widgets, then the filter (and with it the other histograms if linked)
		 */
		protected void boundsChanged()
		{
			inRange( value, min, max, pass );
			toWidgets();
			updateFilter();
		}

		protected double parse( final String text, final double fallback )
		{
			if ( text == null || text.trim().isEmpty() )
				return Double.NaN;

			try
			{
				final double v = Double.parseDouble( text.trim() );
				return Double.isNaN( v ) || Double.isInfinite( v ) || v < 0 ? fallback : v;
			}
			catch ( final NumberFormatException e )
			{
				return fallback;
			}
		}

		/**
		 * Shows the bounds in the knobs, the fields and the marker (without triggering the listeners)
		 */
		protected void toWidgets()
		{
			updating = true;

			try
			{
				slider.setValue( Double.isNaN( min ) ? 0 : position( min ) );
				slider.setUpperValue( Double.isNaN( max ) ? sliderResolution : position( max ) );
				minField.setText( Double.isNaN( min ) ? "" : InterestPointFilter.plain( min ) );
				maxField.setText( Double.isNaN( max ) ? "" : InterestPointFilter.plain( max ) );
				updateMarker();
			}
			finally
			{
				updating = false;
			}
		}

		protected int position( final double value )
		{
			if ( log && value <= 0 )
				return 0;

			final double p = ( space( value, log ) - lo ) / ( hi - lo ) * sliderResolution;

			return (int)Math.round( Math.max( 0, Math.min( sliderResolution, p ) ) );
		}

		/**
		 * Updates the count line after the filter changed; linked histograms were counted again before (recount) and are shown now
		 */
		protected void updateCount( final boolean linked )
		{
			if ( linked )
				showCounts();
			else if ( present && !constant )
				count.setText( countText( count( sorted, min, max ), sorted.length ) );
		}

		protected void reset()
		{
			min = max = Double.NaN;
			boundsChanged();
		}
	}
}
