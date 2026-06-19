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
package net.preibisch.mvrecon.fiji.spimdata.explorer.popup;

import java.util.List;

import bdv.BigDataViewer;
import bdv.SpimSource;
import bdv.VolatileSpimSource;
import bdv.tools.brightness.ConverterSetup;
import bdv.viewer.SourceAndConverter;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import net.imglib2.Volatile;
import net.imglib2.type.numeric.NumericType;

import bdv.ViewerImgLoader;
import bdv.ViewerSetupImgLoader;

/**
 * Source-name utilities used by both {@link BDVPopup} (eager) and
 * {@link LazyBDVPopup} so they label sources identically as
 * {@code "VS: <viewSetupId> TP: <timepointId>"}.
 *
 * BDV models one source per {@code viewSetupId}, so the TP in the name is the
 * first ordered timepoint id (representative; the TP slider is ambient).
 */
public class BDVSourceNaming
{
	public static String viewIdSourceName( final BasicViewSetup setup,
			final AbstractSequenceDescription< ?, ?, ? > seq )
	{
		final int setupId = setup.getId();
		final int tpId = seq.getTimePoints().getTimePointsOrdered().get( 0 ).getId();
		return "VS: " + setupId + " TP: " + tpId;
	}

	/**
	 * Mirrors BDV-core's private {@code BigDataViewer.initSetupNumericType} but
	 * uses {@link #viewIdSourceName(BasicViewSetup, AbstractSequenceDescription)}
	 * for the source name. Appends the resulting {@link SourceAndConverter}
	 * (wrapped with a {@code TransformedSource}) and matching
	 * {@link ConverterSetup} to the given lists.
	 */
	public static < T extends NumericType< T >, V extends Volatile< T > & NumericType< V > > void initSetupViewIdName(
			final AbstractSpimData< ? > spimData,
			final BasicViewSetup setup,
			final List< ConverterSetup > converterSetups,
			final List< SourceAndConverter< ? > > sources )
	{
		final int setupId = setup.getId();
		final ViewerImgLoader imgLoader = ( ViewerImgLoader ) spimData.getSequenceDescription().getImgLoader();
		@SuppressWarnings( "unchecked" )
		final ViewerSetupImgLoader< T, V > setupImgLoader = ( ViewerSetupImgLoader< T, V > ) imgLoader.getSetupImgLoader( setupId );
		final T type = setupImgLoader.getImageType();
		final V volatileType = setupImgLoader.getVolatileImageType();

		if ( !( type instanceof NumericType ) )
			throw new IllegalArgumentException( "ImgLoader of type " + type.getClass() + " not supported." );

		final String setupName = viewIdSourceName( setup, spimData.getSequenceDescription() );

		SourceAndConverter< V > vsoc = null;
		if ( volatileType != null )
		{
			final VolatileSpimSource< V > vs = new VolatileSpimSource<>( spimData, setupId, setupName );
			vsoc = new SourceAndConverter<>( vs, BigDataViewer.< V >createConverterToARGB( volatileType ) );
		}

		final SpimSource< T > s = new SpimSource<>( spimData, setupId, setupName );
		final SourceAndConverter< T > soc = new SourceAndConverter<>( s, BigDataViewer.< T >createConverterToARGB( type ), vsoc );
		final SourceAndConverter< T > tsoc = BigDataViewer.< T, V >wrapWithTransformedSource( soc );
		sources.add( tsoc );

		final ConverterSetup converterSetup = BigDataViewer.createConverterSetup( tsoc, setupId );
		if ( converterSetup != null )
			converterSetups.add( converterSetup );
	}
}
