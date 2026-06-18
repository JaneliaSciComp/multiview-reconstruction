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

import bdv.ViewerImgLoader;
import mpicbg.spim.data.SpimDataIOException;
import mpicbg.spim.data.XmlHelpers;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.ImgLoaderIo;
import mpicbg.spim.data.generic.sequence.ImgLoaders;
import mpicbg.spim.data.generic.sequence.XmlIoBasicImgLoader;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.XmlIoSequenceDescription;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.preibisch.legacy.io.IOFunctions;

@ImgLoaderIo( format = "split.viewerimgloader", type = SplitViewerImgLoader.class )
public class XmlIoSplitViewerImgLoader implements XmlIoBasicImgLoader< SplitViewerImgLoader >
{
	public static final String SETUPIDS_NAME = "SetupIds";
	public static final String SETUPIDS_TAG = "SetupIdDefinition";
	public static final String SETUPIDS_NEW = "NewId";
	public static final String SETUPIDS_OLD = "OldId";

	public static final String INTERVAL_TAG_MIN = "min";
	public static final String INTERVAL_TAG_MAX = "max";

	public static final String OLDSETUP_NAME = "OldViewSetup";

	@Override
	public Element toXml( final SplitViewerImgLoader imgLoader, final File basePath ) 
	{
		final Element elem = new Element( "ImageLoader" );
		elem.setAttribute( IMGLOADER_FORMAT_ATTRIBUTE_NAME, this.getClass().getAnnotation( ImgLoaderIo.class ).format() );

		try
		{
			final XmlIoBasicImgLoader< ? > imgLoaderIo = ImgLoaders.createXmlIoForImgLoaderClass( imgLoader.underlyingImgLoader.getClass() );
			elem.addContent( createImgLoaderElement( imgLoaderIo, imgLoader.underlyingImgLoader, basePath ) );
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

		final Element setupIds = new Element( SETUPIDS_NAME );
		for ( final int newSetupId : newSetupIds )
			setupIds.addContent( setupToXml( newSetupId, imgLoader.new2oldSetupId.get( newSetupId ), imgLoader.newSetupId2Interval.get( newSetupId ) ) );

		elem.addContent( setupIds );

		return elem;
	}

	protected static Element setupToXml( final int newSetupId, final int oldSetupId, final Interval interval )
	{
		final Element elem = new Element( SETUPIDS_TAG );

		elem.addContent( XmlHelpers.intElement( SETUPIDS_NEW, newSetupId ) );
		elem.addContent( XmlHelpers.intElement( SETUPIDS_OLD, oldSetupId ) );

		final long[] min = new long[ interval.numDimensions() ];
		final long[] max = new long[ interval.numDimensions() ];

		interval.min( min );
		interval.max( max );

		elem.addContent( XmlHelpers.longArrayElement( INTERVAL_TAG_MIN, min ) );
		elem.addContent( XmlHelpers.longArrayElement( INTERVAL_TAG_MAX, max ) );
		
		return elem;
	}

	protected static void setupFromXML( final Element setup, final HashMap< Integer, Integer > new2oldSetupId, final HashMap< Integer, Interval > newSetupId2Interval )
	{
		final int newSetupId = XmlHelpers.getInt( setup, SETUPIDS_NEW );
		final int oldSetupId = XmlHelpers.getInt( setup, SETUPIDS_OLD );
		final long[] min = XmlHelpers.getLongArray( setup, INTERVAL_TAG_MIN );
		final long[] max = XmlHelpers.getLongArray( setup, INTERVAL_TAG_MAX );

		new2oldSetupId.put( newSetupId, oldSetupId );
		newSetupId2Interval.put( newSetupId, new FinalInterval( min, max ) );
	}

	/*
	 * Casting madness.
	 */
	@SuppressWarnings( "unchecked" )
	protected static < L extends BasicImgLoader > Element createImgLoaderElement( final XmlIoBasicImgLoader< L > imgLoaderIo, final BasicImgLoader imgLoader, final File basePath )
	{
		return imgLoaderIo.toXml( ( L ) imgLoader, basePath );
	}

	@Override
	public SplitViewerImgLoader fromXml( final Element elem, final URI basePathURI, AbstractSequenceDescription< ?, ?, ? > sequenceDescription )
	{
		final HashMap< Integer, Integer > new2oldSetupId = new HashMap<>();
		final HashMap< Integer, Interval > newSetupId2Interval = new HashMap<>();

		for ( final Element setup : elem.getChild( SETUPIDS_NAME ).getChildren( SETUPIDS_TAG ) )
			setupFromXML( setup, new2oldSetupId, newSetupId2Interval );

		try
		{
			final XmlIoSequenceDescription xmlIoSequenceDescription = new XmlIoSequenceDescription();
			final Element sdElem = elem.getChild( xmlIoSequenceDescription.getTag() );
			if ( sdElem == null )
				throw new SpimDataIOException( "no <" + xmlIoSequenceDescription.getTag() + "> element found." );
			final SequenceDescription oldSD = xmlIoSequenceDescription.fromXml( sdElem, basePathURI );

			ViewerImgLoader underlyingImgLoader = null;

			final Element imgLoaderElem = elem.getChild( IMGLOADER_TAG );
			final String format = imgLoaderElem.getAttributeValue( IMGLOADER_FORMAT_ATTRIBUTE_NAME );
			final XmlIoBasicImgLoader< ? > imgLoaderIo = ImgLoaders.createXmlIoForFormat( format );
			underlyingImgLoader = (ViewerImgLoader)imgLoaderIo.fromXml( imgLoaderElem, basePathURI, oldSD );

			return new SplitViewerImgLoader( underlyingImgLoader, new2oldSetupId, newSetupId2Interval, oldSD );
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
	public SplitViewerImgLoader fromXml(
			final Element elem, File basePath,
			final AbstractSequenceDescription<?, ?, ?> sequenceDescription )
	{
		final HashMap< Integer, Integer > new2oldSetupId = new HashMap<>();
		final HashMap< Integer, Interval > newSetupId2Interval = new HashMap<>();

		for ( final Element setup : elem.getChild( SETUPIDS_NAME ).getChildren( SETUPIDS_TAG ) )
			setupFromXML( setup, new2oldSetupId, newSetupId2Interval );

		try
		{
			final XmlIoSequenceDescription xmlIoSequenceDescription = new XmlIoSequenceDescription();
			final Element sdElem = elem.getChild( xmlIoSequenceDescription.getTag() );
			if ( sdElem == null )
				throw new SpimDataIOException( "no <" + xmlIoSequenceDescription.getTag() + "> element found." );
			final SequenceDescription oldSD = xmlIoSequenceDescription.fromXml( sdElem, basePath );

			ViewerImgLoader underlyingImgLoader = null;

			final Element imgLoaderElem = elem.getChild( IMGLOADER_TAG );
			final String format = imgLoaderElem.getAttributeValue( IMGLOADER_FORMAT_ATTRIBUTE_NAME );
			final XmlIoBasicImgLoader< ? > imgLoaderIo = ImgLoaders.createXmlIoForFormat( format );
			underlyingImgLoader = (ViewerImgLoader)imgLoaderIo.fromXml( imgLoaderElem, basePath, oldSD );

			return new SplitViewerImgLoader( underlyingImgLoader, new2oldSetupId, newSetupId2Interval, oldSD );
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
