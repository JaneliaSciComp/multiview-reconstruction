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
package net.preibisch.mvrecon.process.interestpointremoval.histogram;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

import org.jfree.chart.ChartMouseEvent;
import org.jfree.chart.ChartMouseListener;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.ui.RectangleAnchor;
import org.jfree.chart.ui.TextAnchor;

public class MouseListenerValue implements ChartMouseListener
{
	final ChartPanel panel;
	ValueMarker valueMarker;
	
	
	MouseListenerValue( final ChartPanel panel, final double startValue )
	{
		this.panel = panel;
		this.valueMarker = makeMarker( startValue );
		((XYPlot)panel.getChart().getPlot()).addDomainMarker( valueMarker );
	}

	protected ValueMarker makeMarker( final double value )
	{
		final ValueMarker valueMarker = new ValueMarker( value );
		valueMarker.setStroke( new BasicStroke ( 2f ) );
		valueMarker.setPaint( new Color( 0f/255f, 0f/255f, 255f/255f ) );
		valueMarker.setLabel( " Distance=" + value );
		valueMarker.setLabelPaint( Color.BLUE );
		valueMarker.setLabelAnchor( RectangleAnchor.TOP );
		valueMarker.setLabelTextAnchor( TextAnchor.TOP_LEFT );
		
		return valueMarker;
	}

	@Override
	public void chartMouseClicked( final ChartMouseEvent e )
	{
		// left mouse click
		if ( e.getTrigger().getButton() == MouseEvent.BUTTON1 )
		{
			double value = getChartXLocation( e.getTrigger().getPoint(), panel );
			
			valueMarker.setValue( value );
			valueMarker.setLabel(  " Distance=" + value );
		}
	}
	
	public static int getChartXLocation( final Point point, final ChartPanel panel )
	{
		final Point2D p = panel.translateScreenToJava2D( point );
		final Rectangle2D plotArea = panel.getScreenDataArea();
		final XYPlot plot = (XYPlot) panel.getChart().getPlot();
		final double chartX = plot.getDomainAxis().java2DToValue( p.getX(), plotArea, plot.getDomainAxisEdge() );
		//final double chartY = plot.getRangeAxis().java2DToValue( p.getY(), plotArea, plot.getRangeAxisEdge() );
		
		return (int)Math.round( chartX );			
	}

	@Override
	public void chartMouseMoved( ChartMouseEvent e )
	{
	}
}
