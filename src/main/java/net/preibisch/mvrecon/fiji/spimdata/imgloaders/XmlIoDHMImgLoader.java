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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders;

import static mpicbg.spim.data.XmlKeys.IMGLOADER_FORMAT_ATTRIBUTE_NAME;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import mpicbg.spim.data.XmlHelpers;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.ImgLoaderIo;
import mpicbg.spim.data.generic.sequence.XmlIoBasicImgLoader;

import org.jdom2.Element;

@ImgLoaderIo( format = "spimreconstruction.dhm", type = DHMImgLoader.class )
public class XmlIoDHMImgLoader implements XmlIoBasicImgLoader< DHMImgLoader >
{
	public static final String STACK_DIR_TAG = "stackDir";
	public static final String AMPLITUDE_DIR_TAG = "amplitudeDir";
	public static final String PHASE_DIR_TAG = "phaseDir";
	public static final String TIMEPOINTS_TAG = "timepoints";
	public static final String ZPLANES_TAG = "zplanes";
	public static final String EXTENSION_TAG = "ext";
	public static final String AMPLITUDE_ID_TAG = "amplitudeId";
	public static final String PHASE_ID_TAG = "phaseId";

	@Override
	public Element toXml( final DHMImgLoader imgLoader, final File basePath )
	{
		final Element elem = new Element( "ImageLoader" );
		elem.setAttribute( IMGLOADER_FORMAT_ATTRIBUTE_NAME, this.getClass().getAnnotation( ImgLoaderIo.class ).format() );

		elem.addContent( XmlHelpers.textElement( STACK_DIR_TAG, imgLoader.getStackDir() ) );
		elem.addContent( XmlHelpers.textElement( AMPLITUDE_DIR_TAG, imgLoader.getAmplitudeDir() ) );
		elem.addContent( XmlHelpers.textElement( PHASE_DIR_TAG, imgLoader.getPhaseDir() ) );
		elem.addContent( XmlHelpers.textElement( TIMEPOINTS_TAG, semicolonSeparatedList( imgLoader.getTimepoints() ) ) );
		elem.addContent( XmlHelpers.textElement( ZPLANES_TAG, semicolonSeparatedList( imgLoader.getZPlanes() ) ) );
		elem.addContent( XmlHelpers.textElement( EXTENSION_TAG, imgLoader.getExt() ) );
		elem.addContent( XmlHelpers.intElement( AMPLITUDE_ID_TAG, imgLoader.getAmpChannelId() ) );
		elem.addContent( XmlHelpers.intElement( PHASE_ID_TAG, imgLoader.getPhaseChannelId() ) );

		return elem;
	}

	@Override
	public DHMImgLoader fromXml(
			final Element elem, File basePath,
			final AbstractSequenceDescription<?, ?, ?> sequenceDescription )
	{
		try
		{
			final String stackDir = XmlHelpers.getText( elem, STACK_DIR_TAG );
			final String amplitudeDir = XmlHelpers.getText( elem, AMPLITUDE_DIR_TAG );
			final String phaseDir = XmlHelpers.getText( elem, PHASE_DIR_TAG );
			final List< String > timepoints = fromSemicolonSeparatedString( XmlHelpers.getText( elem, TIMEPOINTS_TAG ) );
			final List< String > zPlanes = fromSemicolonSeparatedString( XmlHelpers.getText( elem, ZPLANES_TAG ) );
			final String extension = XmlHelpers.getText( elem, EXTENSION_TAG );
			final int ampChannelId = XmlHelpers.getInt( elem, AMPLITUDE_ID_TAG );
			final int phaseChannelId = XmlHelpers.getInt( elem, PHASE_ID_TAG );

			return new DHMImgLoader(
					basePath,
					stackDir,
					amplitudeDir,
					phaseDir,
					timepoints,
					zPlanes,
					extension,
					ampChannelId,
					phaseChannelId,
					sequenceDescription );
		}
		catch ( final Exception e )
		{
			throw new RuntimeException( e );
		}
	}

	public static String semicolonSeparatedList( final List< String > elements )
	{
		if ( elements == null || elements.size() == 0 )
			return "";
		else if ( elements.size() == 1 )
			return elements.get(  0  );
		else
		{
			String s = elements.get( 0  );
			for ( int i = 1; i < elements.size(); ++i )
				s += ";" + elements.get( i );
			return s;
		}
	}

	public static List< String > fromSemicolonSeparatedString( String semicolonList )
	{
		final ArrayList< String > list = new ArrayList< String >();

		for ( final String entry : semicolonList.trim().split(  ";" ) )
			list.add( entry );

		return list;
	}
}
