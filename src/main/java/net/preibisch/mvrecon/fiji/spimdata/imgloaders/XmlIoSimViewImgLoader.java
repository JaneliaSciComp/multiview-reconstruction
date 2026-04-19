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

@ImgLoaderIo( format = "spimreconstruction.simview", type = SimViewImgLoader.class )
public class XmlIoSimViewImgLoader implements XmlIoBasicImgLoader< SimViewImgLoader >
{
	public static final String DIRECTORY_TAG = "imagedirectory";
	public static final String FILEPATTERN_TAG = "filepattern";
	public static final String PIXELTYPE_TAG = "pixeltype";
	public static final String LITTLEENDIAN_TAG = "littleendian";

	@Override
	public Element toXml( final SimViewImgLoader imgLoader, final File basePath )
	{
		final Element elem = new Element( "ImageLoader" );
		elem.setAttribute( IMGLOADER_FORMAT_ATTRIBUTE_NAME, this.getClass().getAnnotation( ImgLoaderIo.class ).format() );

		elem.addContent( XmlHelpers.pathElement( DIRECTORY_TAG, imgLoader.expDir, basePath ) );
		elem.addContent( XmlHelpers.textElement( FILEPATTERN_TAG, imgLoader.filePattern ) );
		elem.addContent( XmlHelpers.intElement( PIXELTYPE_TAG, imgLoader.type ) );
		elem.addContent( XmlHelpers.booleanElement( LITTLEENDIAN_TAG, imgLoader.littleEndian ) );
		
		return elem;
	}

	@Override
	public SimViewImgLoader fromXml(
			final Element elem, File basePath,
			final AbstractSequenceDescription<?, ?, ?> sequenceDescription )
	{
		try
		{
			final File path = loadPath( elem, DIRECTORY_TAG, basePath );
			final String filePattern = XmlHelpers.getText( elem, FILEPATTERN_TAG );
			final int type = XmlHelpers.getInt( elem, PIXELTYPE_TAG );
			final boolean littleEndian = XmlHelpers.getBoolean( elem, LITTLEENDIAN_TAG );

			return new SimViewImgLoader( sequenceDescription, path, filePattern, type, littleEndian );
		}
		catch ( final Exception e )
		{
			throw new RuntimeException( e );
		}
	}

}
