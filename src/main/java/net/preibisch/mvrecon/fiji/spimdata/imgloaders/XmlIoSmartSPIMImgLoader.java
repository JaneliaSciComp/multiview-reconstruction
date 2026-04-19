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
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.jdom2.Element;

import bdv.img.n5.XmlIoN5ImageLoader;
import mpicbg.spim.data.XmlHelpers;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.generic.sequence.ImgLoaderIo;
import mpicbg.spim.data.generic.sequence.XmlIoBasicImgLoader;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.fiji.datasetmanager.SmartSPIM.SmartSPIMMetaData;

@ImgLoaderIo( format = "bigstitcher.smartspim", type = SmartSPIMImgLoader.class )
public class XmlIoSmartSPIMImgLoader implements XmlIoBasicImgLoader< SmartSPIMImgLoader >
{
	public static final String METADATAFILE_TAG = "metadatafile";

	public static final String X_TILES_TAG = "xTileLocations";
	public static final String Y_TILES_TAG = "yTileLocations";
	public static final String Z_OFFSETS_TAG = "zOffsets";

	public static final String CHANNELS_TAG = "channels";
	public static final String FILTERS_TAG = "filters";

	public static final String Z_LAYERS = "zLayers";

	@Override
	public Element toXml( final SmartSPIMImgLoader imgLoader, final File basePath )
	{
		return toXml( imgLoader, basePath == null ? null : basePath.toURI() );
	}

	@Override
	public Element toXml( final SmartSPIMImgLoader imgLoader, final URI basePath )
	{
		final Element elem = new Element( "ImageLoader" );
		elem.setAttribute( IMGLOADER_FORMAT_ATTRIBUTE_NAME, this.getClass().getAnnotation( ImgLoaderIo.class ).format() );

		elem.addContent( XmlHelpers.pathElementURI( METADATAFILE_TAG, imgLoader.metadata.metadataFile, basePath ) );

		elem.addContent( XmlHelpers.longArrayElement( X_TILES_TAG,
				imgLoader.metadata.xTileLocations.stream().mapToLong(l -> l).toArray() ) );

		elem.addContent( XmlHelpers.longArrayElement( Y_TILES_TAG,
				imgLoader.metadata.yTileLocations.stream().mapToLong(l -> l).toArray() ) );

		elem.addContent( XmlHelpers.longArrayElement( Z_OFFSETS_TAG,
				imgLoader.metadata.zOffsets.stream().mapToLong(l -> l).toArray() ) );

		elem.addContent( XmlHelpers.intArrayElement( CHANNELS_TAG,
				imgLoader.metadata.channels.stream().mapToInt( p -> p.getA() ).toArray() ) );

		elem.addContent( XmlHelpers.intArrayElement( FILTERS_TAG,
				imgLoader.metadata.channels.stream().mapToInt( p -> p.getB() ).toArray() ) );

		final Element elemFileNames = new Element( Z_LAYERS );
		for ( final String fn : imgLoader.metadata.sortedFileNames )
		{
			Element eFN = new Element( "z" );
			eFN.addContent( fn );
			elemFileNames.addContent( eFN );
		}
		elem.addContent( elemFileNames );

		return elem;
	}

	@Override
	public SmartSPIMImgLoader fromXml( final Element elem, final File basePath, final AbstractSequenceDescription< ?, ?, ? > sequenceDescription )
	{
		return fromXml( elem, basePath.toURI(), sequenceDescription );
	}

	@Override
	public SmartSPIMImgLoader fromXml(
			final Element elem, URI basePath,
			final AbstractSequenceDescription<?, ?, ?> sequenceDescription )
	{
		try
		{
			final URI md = XmlHelpers.loadPathURI( elem, METADATAFILE_TAG, basePath );

			final SmartSPIMMetaData metadata = new SmartSPIMMetaData( md );
			final BasicViewSetup vs = sequenceDescription.getViewSetups().values().iterator().next();

			metadata.xyRes = vs.getVoxelSize().dimension( 0 );
			metadata.zRes = vs.getVoxelSize().dimension( 2 );
			metadata.dimensions = vs.getSize().dimensionsAsLongArray();

			metadata.xTileLocations = 
					Arrays.stream( XmlHelpers.getLongArray( elem, X_TILES_TAG )).boxed().collect(Collectors.toList());
			metadata.yTileLocations = 
					Arrays.stream( XmlHelpers.getLongArray( elem, Y_TILES_TAG )).boxed().collect(Collectors.toList());
			metadata.zOffsets = 
					Arrays.stream( XmlHelpers.getLongArray( elem, Z_OFFSETS_TAG )).boxed().collect(Collectors.toList());

			final int[] channels = XmlHelpers.getIntArray( elem, CHANNELS_TAG );
			final int[] filters = XmlHelpers.getIntArray( elem, FILTERS_TAG );

			metadata.channels = new ArrayList<>();
			for ( int i = 0; i < channels.length; ++i )
				metadata.channels.add( new ValuePair<>( channels[i], filters[i] ));

			metadata.sortedFileNames = new ArrayList<>();
			final Element elemFileNames = elem.getChild( Z_LAYERS );
			elemFileNames.getChildren().forEach( child -> metadata.sortedFileNames.add( child.getText() ) );

			return new SmartSPIMImgLoader( metadata, sequenceDescription );
		}
		catch ( final Exception e )
		{
			throw new RuntimeException( e );
		}
	}

}
