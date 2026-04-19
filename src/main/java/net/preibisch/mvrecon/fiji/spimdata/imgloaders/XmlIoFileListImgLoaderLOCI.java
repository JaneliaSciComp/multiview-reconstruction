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
import java.util.HashMap;
import java.util.Map;

import org.jdom2.Element;

import mpicbg.spim.data.XmlHelpers;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.ImgLoaderIo;
import mpicbg.spim.data.generic.sequence.XmlIoBasicImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.filemap2.FileMapEntry;

@ImgLoaderIo( format = "spimreconstruction.filelist", type = FileMapImgLoaderLOCI.class )
public class XmlIoFileListImgLoaderLOCI implements XmlIoBasicImgLoader< FileMapImgLoaderLOCI >
{

	public static final String DIRECTORY_TAG = "imagedirectory";
	public static final String FILES_TAG = "files";
	public static final String FILE_MAPPING_TAG = "FileMapping";
	public static final String ZGROUPED_TAG = "ZGrouped";
	public static final String MAPPING_VS_TAG = "view_setup";
	public static final String MAPPING_TP_TAG = "timepoint";
	public static final String MAPPING_FILE_TAG = "file";
	public static final String MAPPING_SERIES_TAG = "series";
	public static final String MAPPING_C_TAG = "channel";

	@Override
	public Element toXml(FileMapImgLoaderLOCI imgLoader, File basePath)
	{
		final Element wholeElem = new Element( "ImageLoader" );
		wholeElem.setAttribute( IMGLOADER_FORMAT_ATTRIBUTE_NAME, this.getClass().getAnnotation( ImgLoaderIo.class ).format() );
		wholeElem.addContent( XmlHelpers.booleanElement( ZGROUPED_TAG, imgLoader.zGrouped ) );

		final Element filesElement = new Element( FILES_TAG );
		final Map< ViewId, FileMapEntry > fileMap = imgLoader.getFileMap();
		fileMap.forEach( ( vid, entry ) -> {
			final Element fileMappingElement = new Element( FILE_MAPPING_TAG );
			fileMappingElement.setAttribute( MAPPING_VS_TAG, Integer.toString( vid.getViewSetupId() ) );
			fileMappingElement.setAttribute( MAPPING_TP_TAG, Integer.toString( vid.getTimePointId() ) );
			fileMappingElement.addContent( XmlHelpers.pathElement( MAPPING_FILE_TAG, entry.file(), basePath ) );
			fileMappingElement.setAttribute( MAPPING_SERIES_TAG, Integer.toString( entry.series() ) );
			fileMappingElement.setAttribute( MAPPING_C_TAG, Integer.toString( entry.channel() ) );
			filesElement.addContent( fileMappingElement );
		} );

		//elem.addContent( XmlHelpers.pathElement( DIRECTORY_TAG, imgLoader.getCZIFile().getParentFile(), basePath ) );
		//wholeElem.addContent( XmlHelpers.textElement( MASTER_FILE_TAG, imgLoader.getCZIFile().getName() ) );

		wholeElem.addContent( filesElement );

		return wholeElem;
	}

	@Override
	public FileMapImgLoaderLOCI fromXml(Element elem, File basePath,
			AbstractSequenceDescription< ?, ?, ? > sequenceDescription)
	{
		//final File path = loadPath( elem, DIRECTORY_TAG, basePath );
		final Element fileMapElement = elem.getChild( FILES_TAG );
		final boolean zGrouped = XmlHelpers.getBoolean( elem, ZGROUPED_TAG, false );

		final Map< ViewId, FileMapEntry > fileMap = new HashMap<>();

		for (Element e : fileMapElement.getChildren( FILE_MAPPING_TAG )){
			int vs = Integer.parseInt( e.getAttribute( MAPPING_VS_TAG ).getValue());
			int tp = Integer.parseInt( e.getAttribute( MAPPING_TP_TAG ).getValue());
			int series = Integer.parseInt( e.getAttribute( MAPPING_SERIES_TAG ).getValue());
			int channel = Integer.parseInt( e.getAttribute( MAPPING_C_TAG ).getValue());
			File f = XmlHelpers.loadPath( e, MAPPING_FILE_TAG, basePath );

			fileMap.put( new ViewId( tp, vs ), new FileMapEntry( f, series, channel ) );
		}

		return new FileMapImgLoaderLOCI( fileMap, sequenceDescription, zGrouped );
	}

}
