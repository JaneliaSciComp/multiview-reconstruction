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
package net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions;

import static net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.XmlKeysPointSpreadFunctions.PSFS_TAG;
import static net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.XmlKeysPointSpreadFunctions.PSF_FILE_TAG;
import static net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.XmlKeysPointSpreadFunctions.PSF_SETUP_ATTRIBUTE_NAME;
import static net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.XmlKeysPointSpreadFunctions.PSF_TAG;
import static net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.XmlKeysPointSpreadFunctions.PSF_TIMEPOINT_ATTRIBUTE_NAME;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;

import org.jdom2.Element;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.base.XmlIoSingleton;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;

public class XmlIoPointSpreadFunctions extends XmlIoSingleton< PointSpreadFunctions >
{
	public XmlIoPointSpreadFunctions()
	{
		super( PSFS_TAG, PointSpreadFunctions.class );
		handledTags.add( PSF_TAG );
	}

	public Element toXml( final PointSpreadFunctions pointSpreadFunctions )
	{
		final Element elem = super.toXml();

		// sort all entries by timepoint and viewsetupid so that it is possible to edit XML by hand
		final ArrayList< ViewId > views = new ArrayList<>();
		views.addAll( pointSpreadFunctions.getPointSpreadFunctions().keySet() );
		Collections.sort( views );

		for ( final ViewId v : views )
			elem.addContent( psfToXml( pointSpreadFunctions.getPointSpreadFunctions().get( v ), v ) );

		return elem;
	}

	public PointSpreadFunctions fromXml( final Element allPSFs, final URI basePathURI ) throws SpimDataException
	{
		final PointSpreadFunctions pointSpreadFunctions = super.fromXml( allPSFs );

		for ( final Element psfElement : allPSFs.getChildren( PSF_TAG ) )
		{
			final int tpId = Integer.parseInt( psfElement.getAttributeValue( PSF_TIMEPOINT_ATTRIBUTE_NAME ) );
			final int vsId = Integer.parseInt( psfElement.getAttributeValue( PSF_SETUP_ATTRIBUTE_NAME ) );

			final String file = psfElement.getChildText( PSF_FILE_TAG );

			pointSpreadFunctions.addPSF( new ViewId( tpId, vsId ), new PointSpreadFunction( basePathURI, file ) );
		}

		return pointSpreadFunctions;
	}

	protected Element psfToXml( final PointSpreadFunction psf, final ViewId viewId )
	{
		final Element elem = new Element( PSF_TAG );

		elem.setAttribute( PSF_TIMEPOINT_ATTRIBUTE_NAME, Integer.toString( viewId.getTimePointId() ) );
		elem.setAttribute( PSF_SETUP_ATTRIBUTE_NAME, Integer.toString( viewId.getViewSetupId() ) );

		elem.addContent( new Element( PSF_FILE_TAG ).addContent( psf.getFile() ) );

		// option to save ahead of time in parallel
		// save PSFs if modified
		if ( psf.isModified() )
		{
			if ( !psf.save() )
				IOFunctions.println( "ERROR: Could not save PSF '" + psf.getFile() + "'" );
			else
				IOFunctions.println( "Saved PSF '" + psf.getFile() + "'" );
		}

		return elem;
	}
}
