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
package net.preibisch.mvrecon.fiji.spimdata.explorer.bdv;

import bdv.AbstractSpimSource;
import bdv.BigDataViewer;
import bdv.tools.brightness.ConverterSetup;
import bdv.tools.transformation.TransformedSource;
import bdv.viewer.ConverterSetups;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerState;
import java.util.Collection;
import java.util.function.BiConsumer;

public class BDVUtils
{
	/**
	 * Multiply the display-range <em>maximum</em> (white point) of BDV sources by {@code factor},
	 * leaving the minimum (black point) untouched. A factor &lt; 1 lowers the max (image gets
	 * brighter); a factor &gt; 1 raises it (image gets darker).
	 *
	 * @param bdv         the viewer (no-op if {@code null})
	 * @param factor      multiplicative step applied to each source's current max
	 * @param visibleOnly if {@code true} only currently active (visible) sources are changed;
	 *                    if {@code false} every source in the viewer is changed
	 */
	public static void scaleDisplayRangeMax(
			final BigDataViewer bdv, final double factor, final boolean visibleOnly )
	{
		if ( bdv == null )
			return;

		final ConverterSetups setups = bdv.getConverterSetups();
		final ViewerState state = bdv.getViewer().state();
		synchronized ( state )
		{
			for ( final SourceAndConverter< ? > soc : state.getSources() )
			{
				if ( visibleOnly && !state.isSourceActive( soc ) )
					continue;
				final ConverterSetup cs = setups.getConverterSetup( soc );
				if ( cs == null )
					continue;
				final double min = cs.getDisplayRangeMin();
				double newMax = cs.getDisplayRangeMax() * factor;
				// Keep the range valid and non-collapsed (handles max == 0 and max == min).
				if ( factor > 1.0 && newMax <= cs.getDisplayRangeMax() )
					newMax = cs.getDisplayRangeMax() + 1.0;
				if ( newMax <= min )
					newMax = min + 1.0;
				cs.setDisplayRange( min, newMax );
			}
		}
		bdv.getViewer().requestRepaint();
	}

	public static void forEachAbstractSpimSource(
			final Collection< ? extends SourceAndConverter< ? > > sources,
			final BiConsumer< ? super SourceAndConverter< ? >, ? super AbstractSpimSource< ? > > action )
	{
		for ( final SourceAndConverter< ? > soc : sources )
		{
			Source< ? > source = soc.getSpimSource();

			if ( source instanceof TransformedSource )
				source = ( ( TransformedSource< ? > ) source ).getWrappedSource();

			if ( source instanceof AbstractSpimSource )
				action.accept( soc, ( AbstractSpimSource< ? > ) source );
		}
	}

	public static void forEachTransformedSource(
			final Collection< ? extends SourceAndConverter< ? > > sources,
			final BiConsumer< ? super SourceAndConverter< ? >, ? super TransformedSource< ? > > action )
	{
		for ( final SourceAndConverter< ? > soc : sources )
		{
			Source< ? > source = soc.getSpimSource();

			if ( source instanceof TransformedSource )
				action.accept( soc, ( TransformedSource< ? > ) source );
		}
	}
}
