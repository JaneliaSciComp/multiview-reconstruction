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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting;

import static mpicbg.spim.data.XmlKeys.IMGLOADER_FORMAT_ATTRIBUTE_NAME;
import static mpicbg.spim.data.XmlKeys.IMGLOADER_TAG;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import org.jdom2.Element;

import mpicbg.spim.data.SpimDataIOException;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.ImgLoaderIo;
import mpicbg.spim.data.generic.sequence.ImgLoaders;
import mpicbg.spim.data.generic.sequence.XmlIoBasicImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.XmlIoSequenceDescription;
import net.imglib2.Interval;
import net.preibisch.legacy.io.IOFunctions;

@ImgLoaderIo( format = "split.multiresolutionimgloader", type = SplitMultiResolutionImgLoader.class )
public class XmlIoSplitMultiResolutionImgLoader implements XmlIoBasicImgLoader< SplitMultiResolutionImgLoader >
{
	@Override
	public Element toXml( final SplitMultiResolutionImgLoader imgLoader, final File basePath ) 
	{
		final Element elem = new Element( "ImageLoader" );
		elem.setAttribute( IMGLOADER_FORMAT_ATTRIBUTE_NAME, this.getClass().getAnnotation( ImgLoaderIo.class ).format() );

		try
		{
			final XmlIoBasicImgLoader< ? > imgLoaderIo = ImgLoaders.createXmlIoForImgLoaderClass( imgLoader.underlyingImgLoader.getClass() );
			elem.addContent( XmlIoSplitViewerImgLoader.createImgLoaderElement( imgLoaderIo, imgLoader.underlyingImgLoader, basePath ) );
		}
		catch( Exception e )
		{
			IOFunctions.println( "Unable to save underlying ImgLoader [" + imgLoader.underlyingImgLoader.getClass().getName() + "], stopping. Please resave project to continue." );
			e.printStackTrace();
			return null;
		}

		try
		{
			elem.addContent( new XmlIoSequenceDescription().toXml( imgLoader.oldSD, basePath ) );
		}
		catch ( Exception e )
		{
			IOFunctions.println( "Unable to save old sequence description, stopping. Please resave project to continue." );
			e.printStackTrace();
			return null;
		}

		final ArrayList< Integer > newSetupIds = new ArrayList<>( imgLoader.new2oldSetupId.keySet() );
		Collections.sort( newSetupIds );

		final Element setupIds = new Element( XmlIoSplitViewerImgLoader.SETUPIDS_NAME );
		for ( final int newSetupId : newSetupIds )
			setupIds.addContent( XmlIoSplitViewerImgLoader.setupToXml( newSetupId, imgLoader.new2oldSetupId.get( newSetupId ), imgLoader.newSetupId2Interval.get( newSetupId ) ) );

		elem.addContent( setupIds );

		return elem;
	}

	@Override
	public SplitMultiResolutionImgLoader fromXml(
			final Element elem, URI basePath,
			final AbstractSequenceDescription<?, ?, ?> sequenceDescription )
	{
		final HashMap< Integer, Integer > new2oldSetupId = new HashMap<>();
		final HashMap< Integer, Interval > newSetupId2Interval = new HashMap<>();

		for ( final Element setup : elem.getChild( XmlIoSplitViewerImgLoader.SETUPIDS_NAME ).getChildren( XmlIoSplitViewerImgLoader.SETUPIDS_TAG ) )
			XmlIoSplitViewerImgLoader.setupFromXML( setup, new2oldSetupId, newSetupId2Interval );

		try
		{
			final XmlIoSequenceDescription xmlIoSequenceDescription = new XmlIoSequenceDescription();
			final Element sdElem = elem.getChild( xmlIoSequenceDescription.getTag() );
			if ( sdElem == null )
				throw new SpimDataIOException( "no <" + xmlIoSequenceDescription.getTag() + "> element found." );
			final SequenceDescription oldSD = xmlIoSequenceDescription.fromXml( sdElem, basePath );

			MultiResolutionImgLoader underlyingImgLoader = null;

			final Element imgLoaderElem = elem.getChild( IMGLOADER_TAG );
			final String format = imgLoaderElem.getAttributeValue( IMGLOADER_FORMAT_ATTRIBUTE_NAME );
			final XmlIoBasicImgLoader< ? > imgLoaderIo = ImgLoaders.createXmlIoForFormat( format );
			underlyingImgLoader = (MultiResolutionImgLoader)imgLoaderIo.fromXml( imgLoaderElem, basePath, oldSD );

			return new SplitMultiResolutionImgLoader( underlyingImgLoader, new2oldSetupId, newSetupId2Interval, oldSD );
		}
		catch( Exception e )
		{
			IOFunctions.println( "Unable to load underlying Sequence Description & ImgLoader, stopping." );
			e.printStackTrace();
			System.exit( 0 );
			return null;
		}
	}

	@Override
	public SplitMultiResolutionImgLoader fromXml(
			final Element elem, File basePath,
			final AbstractSequenceDescription<?, ?, ?> sequenceDescription )
	{
		final HashMap< Integer, Integer > new2oldSetupId = new HashMap<>();
		final HashMap< Integer, Interval > newSetupId2Interval = new HashMap<>();

		for ( final Element setup : elem.getChild( XmlIoSplitViewerImgLoader.SETUPIDS_NAME ).getChildren( XmlIoSplitViewerImgLoader.SETUPIDS_TAG ) )
			XmlIoSplitViewerImgLoader.setupFromXML( setup, new2oldSetupId, newSetupId2Interval );

		try
		{
			final XmlIoSequenceDescription xmlIoSequenceDescription = new XmlIoSequenceDescription();
			final Element sdElem = elem.getChild( xmlIoSequenceDescription.getTag() );
			if ( sdElem == null )
				throw new SpimDataIOException( "no <" + xmlIoSequenceDescription.getTag() + "> element found." );
			final SequenceDescription oldSD = xmlIoSequenceDescription.fromXml( sdElem, basePath );

			MultiResolutionImgLoader underlyingImgLoader = null;

			final Element imgLoaderElem = elem.getChild( IMGLOADER_TAG );
			final String format = imgLoaderElem.getAttributeValue( IMGLOADER_FORMAT_ATTRIBUTE_NAME );
			final XmlIoBasicImgLoader< ? > imgLoaderIo = ImgLoaders.createXmlIoForFormat( format );
			underlyingImgLoader = (MultiResolutionImgLoader)imgLoaderIo.fromXml( imgLoaderElem, basePath, oldSD );

			return new SplitMultiResolutionImgLoader( underlyingImgLoader, new2oldSetupId, newSetupId2Interval, oldSD );
		}
		catch( Exception e )
		{
			IOFunctions.println( "Unable to load underlying Sequence Description & ImgLoader, stopping." );
			e.printStackTrace();
			System.exit( 0 );
			return null;
		}
	}
}
