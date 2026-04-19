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
package net.preibisch.mvrecon.fiji.spimdata.explorer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;

import bdv.AbstractSpimSource;
import bdv.tools.transformation.TransformedSource;
import bdv.viewer.OverlayRenderer;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.TransformListener;
import bdv.viewer.ViewerPanel;
import bdv.viewer.ViewerState;
import net.imglib2.Interval;
import net.imglib2.realtransform.AffineTransform3D;

public class ViewSetupIdOverlay implements OverlayRenderer, TransformListener< AffineTransform3D >
{
	private final ViewerPanel viewer;
	private final AffineTransform3D viewerTransform;
	private volatile boolean visible = true;
	
	// For consistent positioning across frames
	private java.util.Map< Integer, OverlayInfo > overlayMemory = new java.util.concurrent.ConcurrentHashMap<>();
	
	// Performance optimization caches
	private final double[] cachedGlobalPlanePoint = new double[3];
	private final double[] cachedGlobalNormal = new double[3];
	private boolean cacheValid = false;
	private int lastTimepoint = -1;
	private long lastTransformHash = 0;
	
	// Reusable objects to avoid garbage collection
	private final double[] tempSourceCenter = new double[3];
	private final double[] tempGlobalCenter = new double[3];
	private final double[] tempScreenCenter = new double[3];
	private final double[] tempSourceCorner = new double[3];
	private final double[] tempGlobalCorner = new double[3];

	public ViewSetupIdOverlay( final ViewerPanel viewer )
	{
		this.viewer = viewer;
		this.viewerTransform = new AffineTransform3D();
	}

	public void setVisible( final boolean visible )
	{
		this.visible = visible;
	}

	public boolean isVisible()
	{
		return visible;
	}

	@Override
	public void drawOverlays( final Graphics g )
	{
		if ( !visible )
			return;

		final Graphics2D g2d = ( Graphics2D ) g;
		g2d.setRenderingHint( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );

		final ViewerState state = viewer.state();
		final List< SourceAndConverter< ? > > sources = state.getSources();
		final int timepoint = state.getCurrentTimepoint();

		// Update plane cache once per frame for performance
		updatePlaneCache( timepoint );
		
		// Collect all visible sources and their screen positions
		final java.util.List< OverlayInfo > overlayInfos = new java.util.ArrayList<>();
		final java.util.Set< Integer > currentViewSetupIds = new java.util.HashSet<>();
		
		for ( int sourceIndex = 0; sourceIndex < sources.size(); ++sourceIndex )
		{
			final SourceAndConverter< ? > sourceAndConverter = sources.get( sourceIndex );
			
			if ( state.isSourceVisible( sourceAndConverter ) )
			{
				final Source< ? > source = sourceAndConverter.getSpimSource();
				
				// Get ViewSetupId from AbstractSpimSource
				int viewSetupId = -1;
				if ( source instanceof AbstractSpimSource )
				{
					viewSetupId = ( ( AbstractSpimSource< ? > ) source ).getSetupId();
				}
				else if ( source instanceof TransformedSource )
				{
					final Source< ? > wrappedSource = ( ( TransformedSource< ? > ) source ).getWrappedSource();
					if ( wrappedSource instanceof AbstractSpimSource )
					{
						viewSetupId = ( ( AbstractSpimSource< ? > ) wrappedSource ).getSetupId();
					}
				}
				
				if ( viewSetupId >= 0 )
				{
					currentViewSetupIds.add( viewSetupId );
					
					// Calculate the center of the image in screen coordinates
					final double[] screenCenter = calculateImageCenter( source, timepoint );
					
					if ( screenCenter != null )
					{
						// Get or create overlay info from memory
						OverlayInfo info = overlayMemory.get( viewSetupId );
						if ( info == null )
						{
							info = new OverlayInfo( viewSetupId, screenCenter[0], screenCenter[1] );
							overlayMemory.put( viewSetupId, info );
						}
						else
						{
							// Update original position but preserve display positions for interpolation
							info.x = screenCenter[0];
							info.y = screenCenter[1];
						}
						
						overlayInfos.add( info );
					}
				}
			}
		}
		
		// Remove overlays that are no longer visible
		overlayMemory.keySet().retainAll( currentViewSetupIds );

		// Calculate zoom level and crowding
		final double zoomLevel = calculateZoomLevel();
		final boolean isCrowded = detectCrowding( overlayInfos );
		
		// Calculate deterministic display positions
		calculateDisplayPositions( overlayInfos, zoomLevel );
		
		// Determine font size based on zoom level
		final int fontSize = calculateFontSize( zoomLevel );
		g2d.setFont( new Font( Font.SANS_SERIF, Font.BOLD, fontSize ) );
		
		// Draw overlays
		for ( final OverlayInfo info : overlayInfos )
		{
			// Choose text format based on crowding and zoom
			final String overlayText;
			if ( isCrowded || zoomLevel < 0.5 )
			{
				overlayText = String.valueOf( info.viewSetupId ); // Just the number
			}
			else
			{
				overlayText = "ViewSetup: " + info.viewSetupId; // Full text
			}
			
			// Calculate text position to center it
			final FontMetrics fm = g2d.getFontMetrics();
			final int textWidth = fm.stringWidth( overlayText );
			final int textHeight = fm.getHeight();
			
			final int textX = ( int ) ( info.displayX - textWidth / 2.0 );
			final int textY = ( int ) ( info.displayY + textHeight / 4.0 ); // Adjust for baseline
			
			// Draw connection line if the display position differs from original position
			if ( Math.abs( info.displayX - info.x ) > 1 || Math.abs( info.displayY - info.y ) > 1 )
			{
				g2d.setColor( Color.YELLOW );
				
				// Scale line thickness and circle size with zoom level
				final float lineWidth = ( float ) Math.max( 1.0, Math.min( 4.0, 1.5 * Math.sqrt( zoomLevel ) ) );
				final int circleSize = ( int ) Math.max( 3, Math.min( 8, 4 * Math.sqrt( zoomLevel ) ) );
				
				g2d.setStroke( new BasicStroke( lineWidth ) );
				g2d.drawLine( ( int ) info.x, ( int ) info.y, ( int ) info.displayX, ( int ) info.displayY );
				
				// Draw a small circle at the original center point
				final int halfCircle = circleSize / 2;
				g2d.fillOval( ( int ) info.x - halfCircle, ( int ) info.y - halfCircle, circleSize, circleSize );
			}
			
			// Draw text with background for better visibility
			g2d.setColor( new Color( 0, 0, 0, 128 ) ); // Semi-transparent black background
			g2d.fillRect( textX - 2, textY - textHeight + 2, textWidth + 4, textHeight );
			
			g2d.setColor( Color.YELLOW );
			g2d.drawString( overlayText, textX, textY );
		}
	}

	private static class OverlayInfo
	{
		final int viewSetupId;
		double x, y; // Original center position
		double displayX, displayY; // Final display position (deterministic based on zoom level)

		OverlayInfo( final int viewSetupId, final double x, final double y )
		{
			this.viewSetupId = viewSetupId;
			this.x = x;
			this.y = y;
			this.displayX = x;
			this.displayY = y;
		}
	}

	private double calculateZoomLevel()
	{
		// Extract zoom level from the viewer transform
		// The viewer transform contains scaling information
		synchronized ( viewerTransform )
		{
			// Get the scale from the transform matrix - use determinant for more accurate scaling
			final double m00 = viewerTransform.get( 0, 0 );
			final double m01 = viewerTransform.get( 0, 1 );
			final double m10 = viewerTransform.get( 1, 0 );
			final double m11 = viewerTransform.get( 1, 1 );
			
			// Calculate the geometric mean of the scaling factors
			// This gives us the overall scaling factor
			final double scaleX = Math.sqrt( m00 * m00 + m10 * m10 );
			final double scaleY = Math.sqrt( m01 * m01 + m11 * m11 );
			
			return Math.sqrt( scaleX * scaleY ); // Geometric mean
		}
	}

	private int calculateFontSize( final double zoomLevel )
	{
		// More responsive font scaling for both zoom in and zoom out
		// Use a more direct scaling relationship
		int fontSize;
		
		if ( zoomLevel >= 1.0 )
		{
			// Zoomed in: scale more aggressively
			fontSize = ( int ) Math.round( 16.0 * Math.pow( zoomLevel, 0.6 ) );
			fontSize = Math.max( 16, Math.min( 48, fontSize ) ); // Allow larger fonts when zoomed in
		}
		else
		{
			// Zoomed out: scale down but keep readable
			fontSize = ( int ) Math.round( 16.0 * Math.pow( zoomLevel, 0.4 ) );
			fontSize = Math.max( 8, Math.min( 16, fontSize ) );
		}
		
		return fontSize;
	}

	private boolean detectCrowding( final java.util.List< OverlayInfo > overlayInfos )
	{
		if ( overlayInfos.size() <= 2 )
			return false;

		// Check if any two overlays are too close to each other
		final double minDistance = 80.0; // Minimum distance in pixels
		
		for ( int i = 0; i < overlayInfos.size(); i++ )
		{
			final OverlayInfo info1 = overlayInfos.get( i );
			for ( int j = i + 1; j < overlayInfos.size(); j++ )
			{
				final OverlayInfo info2 = overlayInfos.get( j );
				final double distance = Math.sqrt( 
					( info1.x - info2.x ) * ( info1.x - info2.x ) + 
					( info1.y - info2.y ) * ( info1.y - info2.y ) 
				);
				
				if ( distance < minDistance )
				{
					return true; // Crowded
				}
			}
		}
		
		return false; // Not crowded
	}

	private void calculateDisplayPositions( final java.util.List< OverlayInfo > overlayInfos, final double zoomLevel )
	{
		if ( overlayInfos.size() <= 1 )
		{
			// Single overlay - display at original position
			if ( overlayInfos.size() == 1 )
			{
				final OverlayInfo info = overlayInfos.get( 0 );
				info.displayX = info.x;
				info.displayY = info.y;
			}
			return;
		}

		// Group overlays by their position (within a small tolerance)
		final java.util.Map< String, java.util.List< OverlayInfo > > positionGroups = new java.util.HashMap<>();
		
		// Scale tolerance with zoom level - more precision when zoomed in
		final double tolerance = Math.max( 2.0, 10.0 / Math.max( 1.0, zoomLevel ) );
		
		for ( final OverlayInfo info : overlayInfos )
		{
			// Create a position key by rounding to tolerance
			final String positionKey = Math.round( info.x / tolerance ) + "," + Math.round( info.y / tolerance );
			
			positionGroups.computeIfAbsent( positionKey, k -> new java.util.ArrayList<>() ).add( info );
		}
		
		// Calculate deterministic display positions for each group
		for ( final java.util.List< OverlayInfo > group : positionGroups.values() )
		{
			if ( group.size() > 1 )
			{
				calculateGroupDisplayPositions( group, zoomLevel );
			}
			else
			{
				// Single overlay - display at original position
				final OverlayInfo info = group.get( 0 );
				info.displayX = info.x;
				info.displayY = info.y;
			}
		}
	}

	private void calculateGroupDisplayPositions( final java.util.List< OverlayInfo > group, final double zoomLevel )
	{
		if ( group.size() <= 1 )
			return;

		// Use the first overlay's position as the reference center
		final double centerX = group.get( 0 ).x;
		final double centerY = group.get( 0 ).y;

		// Deterministic radius scaling based on zoom level and group size
		final double baseRadius = Math.max( 30, 15 * group.size() );
		final double radius = baseRadius * Math.pow( Math.max( 0.3, zoomLevel ), 0.4 );
		
		// Sort group by viewSetupId to ensure consistent, reproducible ordering
		group.sort( ( a, b ) -> Integer.compare( a.viewSetupId, b.viewSetupId ) );
		
		// Arrange in a circle with equal angular spacing
		final double angleStep = 2.0 * Math.PI / group.size();
		final double startAngle = -Math.PI / 2.0; // Start at top (12 o'clock position)
		
		for ( int i = 0; i < group.size(); i++ )
		{
			final OverlayInfo info = group.get( i );
			final double angle = startAngle + i * angleStep;
			
			// Calculate final display position directly - no animation
			info.displayX = centerX + radius * Math.cos( angle );
			info.displayY = centerY + radius * Math.sin( angle );
		}
	}
	
	private void updatePlaneCache( final int timepoint )
	{
		// Calculate hash of current transform state
		final long currentTransformHash;
		synchronized ( viewerTransform )
		{
			currentTransformHash = java.util.Arrays.hashCode( viewerTransform.getRowPackedCopy() );
		}
		
		// Check if cache needs updating
		if ( cacheValid && lastTimepoint == timepoint && lastTransformHash == currentTransformHash )
		{
			return; // Cache is still valid
		}
		
		synchronized ( viewerTransform )
		{
			// Cache viewer plane position in global coordinates
			final double[] viewerPlanePoint = { 0, 0, 0 };
			viewerTransform.inverse().apply( viewerPlanePoint, cachedGlobalPlanePoint );
			
			// Cache viewer plane normal in global coordinates
			final double[] viewerNormal = { 0, 0, 1 };
			final AffineTransform3D normalTransform = viewerTransform.inverse().copy();
			normalTransform.set( 0, 0, 3 ); // Remove translation for vector transform
			normalTransform.set( 0, 1, 3 );
			normalTransform.set( 0, 2, 3 );
			normalTransform.apply( viewerNormal, cachedGlobalNormal );
			
			// Normalize the normal vector
			final double normalLength = Math.sqrt( 
				cachedGlobalNormal[0] * cachedGlobalNormal[0] + 
				cachedGlobalNormal[1] * cachedGlobalNormal[1] + 
				cachedGlobalNormal[2] * cachedGlobalNormal[2] 
			);
			if ( normalLength > 0 )
			{
				cachedGlobalNormal[0] /= normalLength;
				cachedGlobalNormal[1] /= normalLength;
				cachedGlobalNormal[2] /= normalLength;
			}
		}
		
		cacheValid = true;
		lastTimepoint = timepoint;
		lastTransformHash = currentTransformHash;
	}

	private double[] calculateImageCenter( final Source< ? > source, final int timepoint )
	{
		try
		{
			// Get the source bounds at the finest resolution level (level 0)
			final Interval sourceInterval = source.getSource( timepoint, 0 );
			if ( sourceInterval == null )
				return null;

			// Get the source-to-global transform
			final AffineTransform3D sourceToGlobal = new AffineTransform3D();
			source.getSourceTransform( timepoint, 0, sourceToGlobal );

			// Check if the source intersects with the current viewer plane (using cached data)
			if ( !intersectsCurrentViewerPlaneOptimized( sourceInterval, sourceToGlobal ) )
				return null;

			// Calculate center in source coordinates using reusable array
			for ( int d = 0; d < 3; d++ )
			{
				tempSourceCenter[d] = ( sourceInterval.min( d ) + sourceInterval.max( d ) ) / 2.0;
			}

			// Transform to global coordinates using reusable array
			sourceToGlobal.apply( tempSourceCenter, tempGlobalCenter );

			// Transform to screen coordinates using viewer transform and reusable array
			synchronized ( viewerTransform )
			{
				viewerTransform.apply( tempGlobalCenter, tempScreenCenter );
			}

			// Return a copy since tempScreenCenter is reused
			return new double[] { tempScreenCenter[0], tempScreenCenter[1], tempScreenCenter[2] };
		}
		catch ( Exception e )
		{
			// If anything goes wrong, return null
			return null;
		}
	}
	
	private boolean intersectsCurrentViewerPlaneOptimized( final Interval sourceInterval, final AffineTransform3D sourceToGlobal )
	{
		try
		{
			// Use cached plane data (already computed in updatePlaneCache)
			final double[] sourceMin = { sourceInterval.min( 0 ), sourceInterval.min( 1 ), sourceInterval.min( 2 ) };
			final double[] sourceMax = { sourceInterval.max( 0 ), sourceInterval.max( 1 ), sourceInterval.max( 2 ) };
			
			// Check all 8 corners of the source bounding box using reusable arrays
			boolean hasPositive = false;
			boolean hasNegative = false;
			
			for ( int x = 0; x <= 1; x++ )
			{
				for ( int y = 0; y <= 1; y++ )
				{
					for ( int z = 0; z <= 1; z++ )
					{
						// Use reusable array for corner
						tempSourceCorner[0] = x == 0 ? sourceMin[0] : sourceMax[0];
						tempSourceCorner[1] = y == 0 ? sourceMin[1] : sourceMax[1];
						tempSourceCorner[2] = z == 0 ? sourceMin[2] : sourceMax[2];
						
						// Transform to global coordinates using reusable array
						sourceToGlobal.apply( tempSourceCorner, tempGlobalCorner );
						
						// Calculate signed distance from point to plane using cached normal and plane point
						final double distance = 
							cachedGlobalNormal[0] * ( tempGlobalCorner[0] - cachedGlobalPlanePoint[0] ) +
							cachedGlobalNormal[1] * ( tempGlobalCorner[1] - cachedGlobalPlanePoint[1] ) +
							cachedGlobalNormal[2] * ( tempGlobalCorner[2] - cachedGlobalPlanePoint[2] );
						
						if ( distance > 0 ) hasPositive = true;
						if ( distance < 0 ) hasNegative = true;
						
						// Early exit if we have both positive and negative distances
						if ( hasPositive && hasNegative )
							return true;
					}
				}
			}
			
			// If all corners are on the same side, only show if the image is very close to the plane
			final double threshold = 1.0; // Fixed small threshold in global coordinates
			
			// Find the minimum distance from any corner to the plane
			double minDistance = Double.MAX_VALUE;
			for ( int x = 0; x <= 1; x++ )
			{
				for ( int y = 0; y <= 1; y++ )
				{
					for ( int z = 0; z <= 1; z++ )
					{
						tempSourceCorner[0] = x == 0 ? sourceMin[0] : sourceMax[0];
						tempSourceCorner[1] = y == 0 ? sourceMin[1] : sourceMax[1];
						tempSourceCorner[2] = z == 0 ? sourceMin[2] : sourceMax[2];
						
						sourceToGlobal.apply( tempSourceCorner, tempGlobalCorner );
						
						final double distance = Math.abs(
							cachedGlobalNormal[0] * ( tempGlobalCorner[0] - cachedGlobalPlanePoint[0] ) +
							cachedGlobalNormal[1] * ( tempGlobalCorner[1] - cachedGlobalPlanePoint[1] ) +
							cachedGlobalNormal[2] * ( tempGlobalCorner[2] - cachedGlobalPlanePoint[2] )
						);
						
						minDistance = Math.min( minDistance, distance );
					}
				}
			}
			
			return minDistance <= threshold;
		}
		catch ( Exception e )
		{
			// If plane intersection test fails, default to showing the overlay
			return true;
		}
	}

	@Override
	public void setCanvasSize( final int width, final int height )
	{
		// Not needed for this overlay
	}

	@Override
	public void transformChanged( final AffineTransform3D transform )
	{
		synchronized ( viewerTransform )
		{
			viewerTransform.set( transform );
		}
		// Trigger repaint when transform changes
		viewer.repaint();
	}
}
