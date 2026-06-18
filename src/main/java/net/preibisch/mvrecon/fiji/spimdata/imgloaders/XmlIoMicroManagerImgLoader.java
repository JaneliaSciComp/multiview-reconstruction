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

import mpicbg.spim.data.XmlHelpers;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.ImgLoaderIo;
import mpicbg.spim.data.generic.sequence.XmlIoBasicImgLoader;
import net.imglib2.img.array.ArrayImgFactory;

import org.jdom2.Element;

@ImgLoaderIo( format = "spimreconstruction.micromanager", type = MicroManagerImgLoader.class )
public class XmlIoMicroManagerImgLoader implements XmlIoBasicImgLoader< MicroManagerImgLoader >
{
	public static final String DIRECTORY_TAG = "imagedirectory";
	public static final String MASTER_FILE_TAG = "masterfile";
	public static final String IMGLIB2CONTAINER_PATTERN_TAG = "imglib2container";

	@Override
	public Element toXml( final MicroManagerImgLoader imgLoader, final File basePath )
	{
		final Element elem = new Element( "ImageLoader" );
		elem.setAttribute( IMGLOADER_FORMAT_ATTRIBUTE_NAME, this.getClass().getAnnotation( ImgLoaderIo.class ).format() );

		elem.addContent( XmlHelpers.pathElement( DIRECTORY_TAG, imgLoader.getFile().getParentFile(), basePath ) );
		elem.addContent( XmlHelpers.textElement( MASTER_FILE_TAG, imgLoader.getFile().getName() ) );
		elem.addContent( XmlHelpers.textElement( IMGLIB2CONTAINER_PATTERN_TAG, ArrayImgFactory.class.getSimpleName() ) );
		
		return elem;
	}

	@Override
	public MicroManagerImgLoader fromXml(
			final Element elem, File basePath,
			final AbstractSequenceDescription<?, ?, ?> sequenceDescription )
	{
		try
		{
			final File path = loadPath( elem, DIRECTORY_TAG, basePath );
			final String masterFile = XmlHelpers.getText( elem, MASTER_FILE_TAG );
			final String container = XmlHelpers.getText( elem, IMGLIB2CONTAINER_PATTERN_TAG );

			if ( container == null )
				System.out.println( "WARNING: No Img implementation defined in XML, using ArrayImg." );
			else if ( !container.toLowerCase().contains( "arrayimg" ) )
				System.out.println( "WARNING: Only ArrayImg supported for MicroManager ImgLoader, using ArrayImg." );

			return new MicroManagerImgLoader( new File( path, masterFile ), sequenceDescription );
		}
		catch ( final Exception e )
		{
			throw new RuntimeException( e );
		}
	}

}
