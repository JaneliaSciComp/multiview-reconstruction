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
package net.preibisch.mvrecon.fiji.spimdata.explorer.bdv;

import java.util.Map;

import bdv.AbstractSpimSource;
import bdv.BigDataViewer;
import bdv.tools.brightness.ConverterSetup;
import bdv.tools.transformation.TransformedSource;
import bdv.viewer.ConverterSetups;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import net.imglib2.type.numeric.ARGBType;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BasicBDVPopup;

/**
 * Shared category-colour utilities for explorer-driven BDV highlights.
 * <p>
 * Used by {@code AnalyzeErrorsResultsWindow} (single-view click cycle) and
 * {@code ViewNeighboursWindow} (Neighbours feature). Both apply per-source
 * colour overrides based on a {@code setupId → ARGBType} map.
 */
public class BDVColors
{
	private BDVColors() {}

	public static final ARGBType GRAY      = new ARGBType( ARGBType.rgba( 160, 160, 160, 255 ) );
	public static final ARGBType GREEN     = new ARGBType( ARGBType.rgba(   0, 255,   0, 255 ) );
	public static final ARGBType MAGENTA   = new ARGBType( ARGBType.rgba( 255,   0, 255, 255 ) );
	public static final ARGBType LIGHTBLUE = new ARGBType( ARGBType.rgba( 135, 206, 250, 255 ) );

	/**
	 * Walk the BDV's active sources and override each one's
	 * {@link ConverterSetup#setColor(ARGBType) ConverterSetup colour} from
	 * {@code colorBySetupId}. Sources whose setupId isn't in the map are left
	 * untouched. Triggers a repaint at the end.
	 *
	 * Safe to call when no BDV is open (no-op).
	 */
	public static void applyCategoryColors(
			final BasicBDVPopup pop,
			final Map< Integer, ARGBType > colorBySetupId )
	{
		if ( pop == null || pop.getBDV() == null )
			return;
		final BigDataViewer bdv = pop.getBDV();
		final ConverterSetups setups = bdv.getConverterSetups();
		for ( final SourceAndConverter< ? > soc : bdv.getViewer().state().getSources() )
		{
			Source< ? > src = soc.getSpimSource();
			if ( src instanceof TransformedSource )
				src = ( ( TransformedSource< ? > ) src ).getWrappedSource();
			if ( !( src instanceof AbstractSpimSource ) )
				continue;
			final int setupId = ( ( AbstractSpimSource< ? > ) src ).getSetupId();
			final ARGBType color = colorBySetupId.get( setupId );
			if ( color == null )
				continue;
			final ConverterSetup cs = setups.getConverterSetup( soc );
			if ( cs != null )
				cs.setColor( color );
		}
		bdv.getViewer().requestRepaint();
	}
}
