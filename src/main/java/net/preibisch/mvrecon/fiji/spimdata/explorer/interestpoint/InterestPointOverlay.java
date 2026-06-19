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

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import net.imglib2.RealLocalizable;
import net.imglib2.realtransform.AffineTransform3D;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import bdv.viewer.OverlayRenderer;
import bdv.viewer.TransformListener;
import bdv.viewer.ViewerPanel;
import mpicbg.spim.data.sequence.ViewId;

public class InterestPointOverlay implements OverlayRenderer, TransformListener< AffineTransform3D >
{
	public static interface InterestPointSource
	{
		public HashMap< ? extends ViewId, ? extends Collection< ? extends RealLocalizable > > getLocalCoordinates( final int timepointIndex );
		public void getLocalToGlobalTransform( final ViewId viewId, final int timepointIndex, final AffineTransform3D transform );
		public int getCorrespondenceColorId( final ViewId viewId, final int detectionId, final int timepointIndex );
		public int getShapeType( final ViewId viewId, final int timepointIndex );
		public double getDistanceFade();
		public boolean isFilterMode();
		public double getPointSizeScale();
		public double getPlaneThickness();
	}

	private final Collection< ? extends InterestPointSource > interestPointSources;

	private final AffineTransform3D viewerTransform;

	private final ViewerPanel viewer;

	private final HashMap< ViewId, Color > viewColors = new HashMap<>();

	// Shuffled color palette for consensus sets - randomized on each overlay creation
	private final List< Float > shuffledHues;

	private Color getGreenShadeForView( final ViewId viewId )
	{
		if ( !viewColors.containsKey( viewId ) )
		{
			// Use view setup ID to generate consistent but distinct green shades
			final int id = viewId.getViewSetupId();
			// Generate green variations using HSB color space
			// Vary hue in wide range: from yellow through green to cyan
			final float hue = 0.15f + (id * 0.11f) % 0.40f; // 0.15-0.55 (yellow to cyan)
			final float saturation = 0.7f; // Keep saturation consistent
			final float brightness = 0.8f; // Keep brightness consistent
			viewColors.put( viewId, Color.getHSBColor( hue, saturation, brightness ) );
		}
		return viewColors.get( viewId );
	}

	private Color getColorForCorrespondence( final int corrId )
	{
		// Use shuffled predefined colors for first 8 consensus sets
		// Palette is randomized on overlay creation, so restarting gives different colors
		final float hue;
		if ( corrId < shuffledHues.size() )
		{
			// Use shuffled predefined highly contrasting color
			hue = shuffledHues.get( corrId );
		}
		else
		{
			// Fall back to golden ratio spacing for many sets
			hue = 0.15f + (corrId * 0.618033988749895f) % 0.70f;
		}

		final float saturation = 0.85f;
		final float brightness = 0.95f;
		return Color.getHSBColor( hue, saturation, brightness );
	}

	/** screen pixels [x,y,z] **/
	private Color getColor( final double[] gPos, final ViewId viewId, final int detectionId, final InterestPointSource pointSource, final int t )
	{
		// In filter mode, don't color points red - show them in their normal colors
		if ( !pointSource.isFilterMode() && Math.abs( gPos[ 2 ] ) < pointSource.getPlaneThickness() )
			return Color.red;

		// Apply distance fade factor with exponential decay
		final double fadeFactor = pointSource.getDistanceFade();
		final int alpha;

		// In filter mode, disable distance fade (all points within plane thickness should be fully visible)
		if ( pointSource.isFilterMode() || fadeFactor == 0 )
		{
			// No fade - all points fully opaque
			alpha = 255;
		}
		else
		{
			// Exponential decay: alpha drops off exponentially with distance
			// Scale factor 0.3 makes points at distance ~10 nearly invisible at max fade
			final double distance = Math.abs( gPos[ 2 ] );
			alpha = (int)( 255.0 * Math.exp( -distance * fadeFactor * 0.3 ) );
		}

		// Check if we should use correspondence-based coloring
		final int corrId = pointSource.getCorrespondenceColorId( viewId, detectionId, t );
		final Color baseColor;
		if ( corrId >= 0 )
		{
			// Use correspondence-based color
			baseColor = getColorForCorrespondence( corrId );
		}
		else
		{
			// Use view-based color
			baseColor = getGreenShadeForView( viewId );
		}

		return new Color( baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), alpha );
	}

	private double getPointSize( final InterestPointSource pointSource )
	{
		return 3.0 * pointSource.getPointSizeScale();
	}

	private void drawCross( final Graphics2D graphics, final int x, final int y, final int size )
	{
		// Draw horizontal and vertical lines (+ shape)
		graphics.drawLine( x - size, y, x + size, y );
		graphics.drawLine( x, y - size, x, y + size );
	}

	private void drawDiagonalCross( final Graphics2D graphics, final int x, final int y, final int size )
	{
		// Draw diagonal lines at 45° (× shape)
		graphics.drawLine( x - size, y - size, x + size, y + size );
		graphics.drawLine( x - size, y + size, x + size, y - size );
	}

	public InterestPointOverlay( final ViewerPanel viewer, final Collection< ? extends InterestPointSource > interestPointSources )
	{
		this.viewer = viewer;
		this.interestPointSources = interestPointSources;
		viewerTransform = new AffineTransform3D();

		// Initialize shuffled color palette - randomized each time overlay is created
		// This allows users to restart overlay if colors are too similar
		final Float[] predefinedHues = {
			0.15f,  // Yellow
			0.50f,  // Cyan
			0.83f,  // Magenta
			0.33f,  // Green
			0.60f,  // Blue
			0.08f,  // Orange
			0.48f,  // Teal
			0.25f   // Lime
		};
		shuffledHues = new ArrayList<>( Arrays.asList( predefinedHues ) );
		Collections.shuffle( shuffledHues, new Random() );
	}

	@Override
	public void transformChanged( final AffineTransform3D transform )
	{
		viewerTransform.set( transform );
	}

	@Override
	public void drawOverlays( final Graphics g )
	{
		final Graphics2D graphics = ( Graphics2D ) g;
		final int t = viewer.getState().getCurrentTimepoint();
		final double[] lPos = new double[ 3 ];
		final double[] gPos = new double[ 3 ];
		final AffineTransform3D transform = new AffineTransform3D();

		for ( final InterestPointSource pointSource : interestPointSources )
		{
			final HashMap< ? extends ViewId, ? extends Collection< ? extends RealLocalizable > > coordinates = pointSource.getLocalCoordinates( t );

			final boolean filterMode = pointSource.isFilterMode();

			for ( final ViewId viewId : coordinates.keySet() )
			{
				pointSource.getLocalToGlobalTransform( viewId, t, transform );
				transform.preConcatenate( viewerTransform );

				final int shapeType = pointSource.getShapeType( viewId, t );

				for ( final RealLocalizable p : coordinates.get( viewId ) )
				{
					p.localize( lPos );
					transform.apply( lPos, gPos );

					// In filter mode, skip points that are not on the current plane (performance optimization)
					if ( filterMode && Math.abs( gPos[ 2 ] ) >= pointSource.getPlaneThickness() )
						continue;

					final double size = getPointSize( pointSource );
					final int x = ( int ) ( gPos[ 0 ] - 0.5 * size );
					final int y = ( int ) ( gPos[ 1 ] - 0.5 * size );
					final int w = ( int ) size;

					// Get detection ID if this is an InterestPoint
					final int detectionId = ( p instanceof InterestPoint ) ?
						((InterestPoint)p).getId() : -1;

					graphics.setColor( getColor( gPos, viewId, detectionId, pointSource, t ) );

					// Draw shape based on type
					if ( shapeType == 1 )
					{
						// Cross (+) - 3x larger than default
						drawCross( graphics, x + w / 2, y + w / 2, (w * 2) / 2 );
					}
					else if ( shapeType == 2 )
					{
						// Diagonal cross (×) - 3x larger than default
						drawDiagonalCross( graphics, x + w / 2, y + w / 2, (w * 2) / 2 );
					}
					else
					{
						// Circle (default)
						graphics.fillOval( x, y, w, w );
					}
				}
			}
		}
	}

	@Override
	public void setCanvasSize( final int width, final int height )
	{}
}
