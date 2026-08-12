package net.preibisch.mvrecon.fiji.spimdata.imgloaders;

/*
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

import static mpicbg.spim.data.XmlKeys.IMGLOADER_FORMAT_ATTRIBUTE_NAME;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.jdom2.Attribute;
import org.jdom2.Element;

import mpicbg.spim.data.XmlHelpers;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.ImgLoaderIo;
import mpicbg.spim.data.generic.sequence.XmlIoBasicImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.AllenOMEZarrLoader.OMEZARREntry;

@ImgLoaderIo( format = "bdv.multimg.zarr", type = AllenOMEZarrLoader.class )
public class XmlIoAllenOMEZarrLoader implements XmlIoBasicImgLoader< AllenOMEZarrLoader >
{
	public static final String ZARR_V2_TAG = "ZarrV2";
	public static final String ZARR_V3_TAG = "ZarrV3";

	@Override
	public Element toXml( final AllenOMEZarrLoader imgLoader, final File basePath )
	{
		return toXml( imgLoader, basePath == null ? null : basePath.toURI() );
	}

	@Override
	public Element toXml( final AllenOMEZarrLoader imgLoader, final URI basePathURI )
	{
		final Element imgLoaderElement = new Element( "ImageLoader" );
		imgLoaderElement.setAttribute( IMGLOADER_FORMAT_ATTRIBUTE_NAME, "bdv.multimg.zarr" );
		imgLoaderElement.setAttribute( "version", "3.1" );
		imgLoaderElement.setAttribute( "zarr.version", (imgLoader.getFormat() == StorageFormat.ZARR) ? ZARR_V3_TAG : ZARR_V2_TAG );

		imgLoaderElement.addContent( XmlHelpers.pathElementURI( "zarr", imgLoader.getN5URI(), basePathURI ));
		
		final Element zgroupsElement = new Element( "zgroups" );

		for ( final Entry<ViewId, OMEZARREntry > entry : imgLoader.getViewIdToPath().entrySet() )
		{
			final Element zgroupElement = new Element("zgroup");
			zgroupElement.setAttribute( "setup", String.valueOf( entry.getKey().getViewSetupId() ) );
			zgroupElement.setAttribute( "tp", String.valueOf( entry.getKey().getTimePointId() ) );
			zgroupElement.setAttribute( "path", String.valueOf( entry.getValue().getPath() ) );
			if ( entry.getValue().getHigherDimensionIndicies() == null || entry.getValue().getHigherDimensionIndicies().length == 0 )
				zgroupElement.setAttribute( "indicies", "[]" );
			else
				zgroupElement.setAttribute( "indicies", XmlHelpers.intArrayElement( "tmp", entry.getValue().getHigherDimensionIndicies()).getText() );

			/*
			final Element pathElement = new Element( "path" );
			pathElement.addContent( entry.getValue() );
			zgroupElement.addContent( pathElement );
			*/

			zgroupsElement.addContent( zgroupElement );
		}

		imgLoaderElement.addContent( zgroupsElement );

		return imgLoaderElement;
	}

	@Override
	public AllenOMEZarrLoader fromXml( final Element elem, final File basePath, final AbstractSequenceDescription< ?, ?, ? > sequenceDescription )
	{
		return fromXml( elem, basePath.toURI(), sequenceDescription );
	}

	@Override
	public AllenOMEZarrLoader fromXml( final Element elem, final URI basePathURI, final AbstractSequenceDescription< ?, ?, ? > sequenceDescription )
	{
		final Map<ViewId, OMEZARREntry> zgroups = new HashMap<>();

		final Attribute ver = elem.getAttribute( "version" );

		final String version;
		if ( ver != null )
			version = ver.getValue();
		else
			version = "";

		final Attribute zarrVer = elem.getAttribute( "zarr.version" );

		final StorageFormat format;
		if ( zarrVer != null && zarrVer.getValue().equals( ZARR_V3_TAG ) )
			format = StorageFormat.ZARR; //v3
		else
			format = StorageFormat.ZARR2; // v2

		final URI uri;

		if ( version.equals( "1.0" ) )
		{
			final Element s3Bucket = elem.getChild( "s3bucket" );
			String bucket, folder;
	
			if (s3Bucket == null)
			{
				uri = XmlHelpers.loadPathURI( elem, "zarr", basePathURI );
	
				bucket = null;
				folder = null;
			}
			else
			{
				// `File` class should not be used for uri manipulation as it replaces slashes
				// with backslashes on Windows
				bucket = s3Bucket.getText();
	
				folder = elem.getChildText( "zarr" );
	
				if ( !folder.endsWith( "/" ) )
					folder = folder + "/";
	
				if ( !folder.startsWith( "/" ) )
					folder = "/" + folder;
	
				try
				{
					uri = new URI( "s3", bucket, folder, null );
				}
				catch ( URISyntaxException e )
				{
					e.printStackTrace();
					throw new RuntimeException( "Could not instantiate OME-ZARR reader for S3 bucket '" + bucket + "'." );
				}
			}

			final Element zgroupsElem = elem.getChild( "zgroups" );
			for ( final Element c : zgroupsElem.getChildren( "zgroup" ) )
			{
				final int timepointId = Integer.parseInt( c.getAttributeValue( "timepoint" ) );
				final int setupId = Integer.parseInt( c.getAttributeValue( "setup" ) );
				final String path = c.getChild( "path" ).getText();
				zgroups.put( new ViewId( timepointId, setupId ), new OMEZARREntry(path, null) );
			}
		}
		else
		{
			uri = XmlHelpers.loadPathURI( elem, "zarr", basePathURI );

			final Element zgroupsElem = elem.getChild( "zgroups" );
			for ( final Element c : zgroupsElem.getChildren( "zgroup" ) )
			{
				final int timepointId = Integer.parseInt( c.getAttributeValue( "tp" ) );
				final int setupId = Integer.parseInt( c.getAttributeValue( "setup" ) );
				final String path = c.getAttributeValue( "path" );
				int[] indicies;

				// written since version 3.0; absent in older XMLs (and then the volume must be 3d,
				// or 4d/5d with size 1 in the higher dimensions)
				final String indiciesString = c.getAttributeValue( "indicies" );

				if ( indiciesString == null || indiciesString.equals( "[]" ) )
				{
					indicies = null;
				}
				else
				{
					String[] split = indiciesString.split( " " );
					indicies = new int[ split.length ];

					for ( int i = 0; i < split.length; ++i )
						indicies[ i ] = Integer.parseInt( split[ i ] );
				}

				//System.out.println( "Loaded indicies: " + Arrays.toString( indicies ));

				zgroups.put( new ViewId( timepointId, setupId ), new OMEZARREntry(path, indicies) );
			}
		}

		try
		{
			try
			{
				return new AllenOMEZarrLoader( uri, format, sequenceDescription, zgroups );
			}
			catch ( Exception e )
			{
				IOFunctions.println( "URI: " + uri );
				IOFunctions.println( "ERROR: cannot instantiate OMEZarrLoader: " + e );
				e.printStackTrace();
				return null;
			}
		}
		catch ( Exception e )
		{
			IOFunctions.println( "ERROR: cannot instantiate OMEZarrLoader: " + e );
			return null;
		}
	}
}
