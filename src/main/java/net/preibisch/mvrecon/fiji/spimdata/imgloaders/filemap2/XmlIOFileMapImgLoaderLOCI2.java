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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders.filemap2;

import static mpicbg.spim.data.XmlKeys.IMGLOADER_FORMAT_ATTRIBUTE_NAME;

import java.io.File;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.jdom2.Element;

import mpicbg.spim.data.XmlHelpers;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.ImgLoaderIo;
import mpicbg.spim.data.generic.sequence.XmlIoBasicImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import util.URITools;


@ImgLoaderIo( format = "spimreconstruction.filemap2", type = FileMapImgLoaderLOCI2.class )
public class XmlIOFileMapImgLoaderLOCI2 implements XmlIoBasicImgLoader< FileMapImgLoaderLOCI2 >
{
	public static final String DIRECTORY_TAG = "imagedirectory";
	public static final String FILES_TAG = "files";
	public static final String FILE_MAPPING_TAG = "FileMapping";
	public static final String MAPPING_VS_TAG = "view_setup";
	public static final String MAPPING_TP_TAG = "timepoint";
	public static final String MAPPING_FILE_TAG = "file";
	public static final String MAPPING_SERIES_TAG = "series";
	public static final String MAPPING_C_TAG = "channel";
	public static final String ZGROUPED_TAG = "ZGrouped";

	@Override
	public Element toXml( final FileMapImgLoaderLOCI2 imgLoader, final URI basePathURI )
	{
		return toXml(imgLoader, new File( URITools.fromURI( basePathURI ) ) );
	}

	@Override
	public Element toXml(FileMapImgLoaderLOCI2 imgLoader, File basePath)
	{
		final Element wholeElem = new Element( "ImageLoader" );
		wholeElem.setAttribute( IMGLOADER_FORMAT_ATTRIBUTE_NAME,
				this.getClass().getAnnotation( ImgLoaderIo.class ).format() );
		wholeElem.addContent( XmlHelpers.booleanElement( ZGROUPED_TAG, imgLoader.zGrouped ) );

		final Map< ViewId, FileMapEntry > fileMap = imgLoader.getFileMap();
		final Element filesElement = new Element( FILES_TAG );
		fileMap.forEach( ( vid, entry ) -> {
			final Element fileMappingElement = new Element( FILE_MAPPING_TAG );
			fileMappingElement.setAttribute( MAPPING_VS_TAG, Integer.toString( vid.getViewSetupId() ) );
			fileMappingElement.setAttribute( MAPPING_TP_TAG, Integer.toString( vid.getTimePointId() ) );
			fileMappingElement.addContent( XmlHelpers.pathElement( MAPPING_FILE_TAG, entry.file(), basePath ) );
			fileMappingElement.setAttribute( MAPPING_SERIES_TAG, Integer.toString( entry.series() ) );
			fileMappingElement.setAttribute( MAPPING_C_TAG, Integer.toString( entry.channel() ) );

			filesElement.addContent( fileMappingElement );
		} );

		// elem.addContent( XmlHelpers.pathElement( DIRECTORY_TAG,
		// imgLoader.getCZIFile().getParentFile(), basePath ) );
		// wholeElem.addContent( XmlHelpers.textElement( MASTER_FILE_TAG, imgLoader.getCZIFile().getName() ) );
		
		wholeElem.addContent( filesElement );
		
		return wholeElem;
	}

	@Override
	public FileMapImgLoaderLOCI2 fromXml(Element elem, final URI basePathURI,
			AbstractSequenceDescription< ?, ?, ? > sequenceDescription)
	{
		return fromXml(elem, new File( URITools.fromURI( basePathURI ) ), sequenceDescription);
	}

	@Override
	public FileMapImgLoaderLOCI2 fromXml(Element elem, File basePath,
			AbstractSequenceDescription< ?, ?, ? > sequenceDescription)
	{
		final boolean zGrouped = XmlHelpers.getBoolean( elem, ZGROUPED_TAG, false );

		// final File path = loadPath( elem, DIRECTORY_TAG, basePath );
		final Element fileMapElement = elem.getChild( FILES_TAG );

		final Map< ViewId, FileMapEntry > fileMap = new HashMap<>();

		for ( Element e : fileMapElement.getChildren( FILE_MAPPING_TAG ) )
		{
			int vs = Integer.parseInt( e.getAttribute( MAPPING_VS_TAG ).getValue() );
			int tp = Integer.parseInt( e.getAttribute( MAPPING_TP_TAG ).getValue() );
			int series = Integer.parseInt( e.getAttribute( MAPPING_SERIES_TAG ).getValue() );
			int channel = Integer.parseInt( e.getAttribute( MAPPING_C_TAG ).getValue() );
			File f = XmlHelpers.loadPath( e, MAPPING_FILE_TAG, basePath );

			ViewId vd = new ViewId( tp, vs );
			fileMap.put( vd, new FileMapEntry( f, series, channel ) );
		}

		return new FileMapImgLoaderLOCI2( fileMap, sequenceDescription, zGrouped );
	}

}
