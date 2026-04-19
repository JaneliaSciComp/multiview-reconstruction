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

import bdv.AbstractSpimSource;
import bdv.tools.transformation.TransformedSource;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import java.util.Collection;
import java.util.function.BiConsumer;

public class BDVUtils
{
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
