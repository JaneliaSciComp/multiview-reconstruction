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

import static mpicbg.spim.data.XmlHelpers.loadPath;
import static mpicbg.spim.data.XmlKeys.IMGLOADER_FORMAT_ATTRIBUTE_NAME;

import java.io.File;

import org.jdom2.Element;

import mpicbg.spim.data.XmlHelpers;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.ImgLoaderIo;
import mpicbg.spim.data.generic.sequence.XmlIoBasicImgLoader;

public abstract class XmlIoStackImgLoader< T extends StackImgLoader< ? > > implements XmlIoBasicImgLoader< T >
{
	public static final String DIRECTORY_TAG = "imagedirectory";
	public static final String FILE_PATTERN_TAG = "filePattern";

	public static final String LAYOUT_TP_TAG = "layoutTimepoints";
	public static final String LAYOUT_CHANNEL_TAG = "layoutChannels";
	public static final String LAYOUT_ILLUMINATION_TAG = "layoutIlluminations";
	public static final String LAYOUT_ANGLE_TAG = "layoutAngles";
	public static final String LAYOUT_TILE_TAG = "layoutTiles";

	@Override
	public Element toXml( final T imgLoader, final File basePath )
	{
		final Element elem = new Element( "ImageLoader" );
		elem.setAttribute( IMGLOADER_FORMAT_ATTRIBUTE_NAME, this.getClass().getAnnotation( ImgLoaderIo.class ).format() );
		elem.addContent( XmlHelpers.pathElement( DIRECTORY_TAG, imgLoader.getPath(), basePath ) );

		elem.addContent( XmlHelpers.textElement( FILE_PATTERN_TAG, imgLoader.getFileNamePattern() ) );
		elem.addContent( XmlHelpers.intElement( LAYOUT_TP_TAG, imgLoader.getLayoutTimePoints() ) );
		elem.addContent( XmlHelpers.intElement( LAYOUT_CHANNEL_TAG, imgLoader.getLayoutChannels() ) );
		elem.addContent( XmlHelpers.intElement( LAYOUT_ILLUMINATION_TAG, imgLoader.getLayoutIlluminations() ) );
		elem.addContent( XmlHelpers.intElement( LAYOUT_ANGLE_TAG, imgLoader.getLayoutAngles() ) );
		elem.addContent( XmlHelpers.intElement( LAYOUT_TILE_TAG, imgLoader.getLayoutTiles() ) );

		return elem;
	}

	@Override
	public T fromXml( final Element elem, final File basePath, final AbstractSequenceDescription< ?, ?, ? > sequenceDescription )
	{
		try
		{
			File path = loadPath( elem, DIRECTORY_TAG, basePath );
			String fileNamePattern = XmlHelpers.getText( elem, FILE_PATTERN_TAG );

			int layoutTP = XmlHelpers.getInt( elem, LAYOUT_TP_TAG );
			int layoutChannels = XmlHelpers.getInt( elem, LAYOUT_CHANNEL_TAG );
			int layoutIllum = XmlHelpers.getInt( elem, LAYOUT_ILLUMINATION_TAG );
			int layoutAngles = XmlHelpers.getInt( elem, LAYOUT_ANGLE_TAG );
			int layoutTiles = 0;

			try { layoutTiles = XmlHelpers.getInt( elem, LAYOUT_TILE_TAG ); } catch (Exception e) {}

			return createImgLoader( path, fileNamePattern, layoutTP, layoutChannels, layoutIllum, layoutAngles, layoutTiles, sequenceDescription );
		}
		catch ( final Exception e )
		{
			e.printStackTrace();
			throw new RuntimeException( e );
		}
	}

	protected abstract T createImgLoader(
			final File path, final String fileNamePattern,
			final int layoutTP, final int layoutChannels, final int layoutIllum, final int layoutAngles, final int layoutTiles,
			final AbstractSequenceDescription< ?, ?, ? > sequenceDescription );
}
