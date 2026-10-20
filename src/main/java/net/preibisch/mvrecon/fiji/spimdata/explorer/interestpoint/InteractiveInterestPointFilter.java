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
 * (InterestPointTableModel.setPreviewFilter). The current ranges are remembered as the defaults of the
 * "Filter interest points" dialog (Filter_Detections), so that they can be applied to any views later, and
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

	final SpimData2 data;
	final String label;
	final List< ViewId > views;
	final ArrayList< InterestPoint > points;
	final Consumer< InterestPointFilter > preview;
	final Runnable onCreated;
	final AxisPanel[] axisPanels;
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
		this.points = new ArrayList<>();
		this.preview = preview;
		this.onCreated = onCreated;

		for ( final ViewId viewId : views )
			points.addAll( data.getViewInterestPoints().getViewInterestPointLists( viewId ).getInterestPointList( label ).getInterestPointsCopy().values() );

		final JPanel content = new JPanel();
		content.setLayout( new BoxLayout( content, BoxLayout.Y_AXIS ) );
		content.setBorder( BorderFactory.createEmptyBorder( 5, 5, 5, 5 ) );

		final JLabel header = new JLabel( "<html><body style='width: " + chartWidth + "px'><b>" + points.size() + " points of '" + label + "' in " + views.size() + " view" + ( views.size() == 1 ? "" : "s" ) +
				"</b><br>Move the knobs or type bounds (a knob at the end or an empty field = no bound), the points drawn in BigDataViewer follow. " +
				"The ranges become the defaults of 'Filter interest points' (also for other views).</body></html>" );
		header.setFont( GUIHelper.smallStatusFont );
		header.setBorder( BorderFactory.createEmptyBorder( 0, 0, 5, 0 ) );
		content.add( header );

		this.axisPanels = new AxisPanel[ Axis.values().length ];

		for ( final Axis axis : Axis.values() )
		{
			axisPanels[ axis.ordinal() ] = new AxisPanel( axis );
			content.add( axisPanels[ axis.ordinal() ] );
		}

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

	/**
	 * Collects the filter of the axis panels, updates the counts, the preview in BigDataViewer and the defaults of Filter_Detections
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

		int pass = 0;

		for ( final InterestPoint ip : points )
			if ( filter.acceptKnown( ip ) )
				++pass;

		final String description = filter.describe( label );

		summary.setText( pass + " of " + points.size() + " points pass" + ( filter.isEmpty() ? " (no bounds set)" : ": " + description.substring( description.indexOf( ':' ) + 2 ) ) );
		suffixLabel.setText( filter.isEmpty() ? " " : "label suffix: " + filter.suffix() + "   (new label: " + label + filter.suffix() + ")" );

		for ( final AxisPanel a : axisPanels )
			a.updateCount();

		if ( preview != null )
			preview.accept( filter );

		Filter_Detections.remember( label, filter );
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
	 * @return the finite values of the axis (|response| for the response), sorted ascending
	 */
	public static float[] sortedValues( final Collection< ? extends InterestPoint > points, final Axis axis )
	{
		final float[] values = new float[ points.size() ];
		int n = 0;

		for ( final InterestPoint ip : points )
		{
			final float v = InterestPointFilter.value( ip, axis );

			if ( !Float.isNaN( v ) && !Float.isInfinite( v ) )
				values[ n++ ] = v;
		}

		final float[] finite = Arrays.copyOf( values, n );
		Arrays.sort( finite );

		return finite;
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
			++counts[ Math.min( numBins - 1, (int)( ( space( v, log ) - lo ) / size * numBins ) ) ];

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
		final float[] sorted;
		final boolean present;
		final boolean constant; // all points have the same value (e.g. the sigma of a single-scale DoG), nothing to filter

		final RangeSlider slider;
		final JTextField minField, maxField;
		final JCheckBox logBox;
		final JLabel count;
		final XYSeriesCollection dataset;
		final IntervalMarker marker;
		final JFreeChart chart;

		// the bounds (NaN = unbounded), the knobs and the fields are views of them
		double min = Double.NaN, max = Double.NaN;
		boolean log;
		double lo, hi; // the ends of the slider in slider space
		boolean updating = false;

		public AxisPanel( final Axis axis )
		{
			this.axis = axis;
			this.sorted = sortedValues( points, axis );
			this.present = sorted.length > 0;
			this.constant = present && sorted[ 0 ] == sorted[ sorted.length - 1 ];
			this.log = defaultLog[ axis.ordinal() ] && present && !constant && sorted[ 0 ] > 0;

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

			if ( !present || constant )
			{
				count.setText( !present ?
						"not stored for this label (detect it again to store it)" :
						"all " + sorted.length + " points have " + axis.description + " = " + InterestPointFilter.plain( round3( sorted[ 0 ] ) ) + ", nothing to filter" );
				slider.setEnabled( false );
				minField.setEnabled( false );
				maxField.setEnabled( false );
				logBox.setEnabled( false );
				rebin();
				return;
			}

			logBox.setEnabled( sorted[ 0 ] > 0 );
			rebin();

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
		 * Bins the values in the current space (linear or log) and sets the chart up for it
		 */
		protected void rebin()
		{
			final XYSeries series = new XYSeries( axis.key );
			double width = Double.NaN;

			if ( constant )
			{
				// one bar in the middle
				final double v = sorted[ 0 ], d = Math.max( Math.abs( v ) * 0.1, 0.5 );
				series.add( v, sorted.length );
				lo = v - d;
				hi = v + d;
				width = d / 10;
			}
			else if ( present )
			{
				final double[][] h = histogram( sorted, log, numBins );

				for ( int b = 0; b < numBins; ++b )
					series.add( h[ 0 ][ b ], h[ 1 ][ b ] );

				lo = space( sorted[ 0 ], log );
				hi = space( sorted[ sorted.length - 1 ], log );

				if ( hi <= lo )
					hi = lo + 1e-6;
			}
			else
			{
				lo = 0;
				hi = 1;
			}

			// the x range first: JFreeChart auto-ranges the counts over the x range the axis shows at the time the data changes
			final NumberAxis domain = (NumberAxis)chart.getXYPlot().getDomainAxis();
			domain.setLabel( axisLabel() );
			domain.setRange( lo - ( hi - lo ) * 0.01, hi + ( hi - lo ) * 0.01 );

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

			updateMarker();
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

			toWidgets();
			updateFilter();
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

		protected void updateCount()
		{
			if ( present && !constant )
				count.setText( count( sorted, min, max ) + " of " + sorted.length + " in range" + ( sorted.length < points.size() ? " (" + ( points.size() - sorted.length ) + " without value)" : "" ) );
		}

		protected void reset()
		{
			min = max = Double.NaN;
			toWidgets();
			updateFilter();
		}
	}
}
