/*-
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
package net.preibisch.mvrecon.fiji.spimdata;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.stream.Collectors;

import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.jdom2.Element;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.XmlIoAbstractSpimData;
import mpicbg.spim.data.registration.XmlIoViewRegistrations;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.XmlIoSequenceDescription;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBoxes;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.XmlIoBoundingBoxes;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.XmlIoIntensityAdjustments;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.XmlIoViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunction;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.XmlIoPointSpreadFunctions;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.XmlIoStitchingResults;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import util.URITools;

public class XmlIoSpimData2 extends XmlIoAbstractSpimData< SequenceDescription, SpimData2 >
{
	final XmlIoViewInterestPoints xmlViewsInterestPoints;
	final XmlIoBoundingBoxes xmlBoundingBoxes;
	final XmlIoPointSpreadFunctions xmlPointSpreadFunctions;
	final XmlIoStitchingResults xmlStitchingResults;
	final XmlIoIntensityAdjustments xmlIntensityAdjustments;

	URI lastURI;
	public static int numBackups = 5;
	public static boolean initN5Writing = true;

	public XmlIoSpimData2()
	{
		super( SpimData2.class, new XmlIoSequenceDescription(), new XmlIoViewRegistrations() );

		this.xmlViewsInterestPoints = new XmlIoViewInterestPoints();
		this.handledTags.add( xmlViewsInterestPoints.getTag() );

		this.xmlBoundingBoxes = new XmlIoBoundingBoxes();
		this.handledTags.add( xmlBoundingBoxes.getTag() );

		this.xmlPointSpreadFunctions = new XmlIoPointSpreadFunctions();
		this.handledTags.add( xmlPointSpreadFunctions.getTag() );

		this.xmlStitchingResults = new XmlIoStitchingResults();
		this.handledTags.add( xmlStitchingResults.getTag() );

		this.xmlIntensityAdjustments = new XmlIoIntensityAdjustments();
		this.handledTags.add( xmlIntensityAdjustments.getTag() );

		if ( initN5Writing )
		{
			try
			{
				// trigger the N5-blosc error, because if it is triggered for the first
				// time inside Spark, everything crashes
				new N5FSWriter(null).close();;
			}
			catch (Exception e ) {}
		}
	}

	public URI lastURI() { return lastURI; }

	@Deprecated
	@Override
	public void save( final SpimData2 spimData, String xmlPath ) throws SpimDataException
	{
		// old loading code with copying files
		this.lastURI = URITools.toURI( xmlPath );

		try
		{
			// fist make a copy of the XML and save it to not loose it
			if ( new File( xmlPath ).exists() )
			{
				int maxExistingBackup = 0;
				for ( int i = 1; i < numBackups; ++i )
					if ( new File( xmlPath + "~" + i ).exists() )
						maxExistingBackup = i;
					else
						break;
	
				// copy the backups
				for ( int i = maxExistingBackup; i >= 1; --i )
					URITools.copyFile( new File( xmlPath + "~" + i ), new File( xmlPath + "~" + (i + 1) ) );

				URITools.copyFile( new File( xmlPath ), new File( xmlPath + "~1" ) );
			}
		}
		catch ( Exception e )
		{
			throw new SpimDataException( "Could not save backup of XML file for '" + lastURI() + "': " + e );
		}

		try
		{
			XmlIoSpimData2.saveInterestPointsInParallel( spimData );
		}
		catch ( Exception e )
		{
			throw new SpimDataException( "Could not interest points for '" + lastURI() + "' in paralell: " + e );
		}

		try
		{
			XmlIoSpimData2.savePSFsInParallel( spimData );
		}
		catch ( Exception e )
		{
			throw new SpimDataException( "Could not point spread function for '" + lastURI() + "' in paralell: " + e );
		}

		try
		{
			super.save( spimData, xmlPath );

			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Saved xml '" + lastURI() + "'." );
		}
		catch ( Exception e )
		{
			e.printStackTrace();
			throw new SpimDataException( "Could not save xml '" + lastURI() + "': " + e );
		}
	}

	public boolean save( final SpimData2 spimData, URI xmlURI )
	{
		try
		{
			URITools.saveSpimData( spimData, xmlURI, this );
		}
		catch (SpimDataException e)
		{
			IOFunctions.println( "Failed to write XML / Copy backups / save interestpoints: " + e );
			e.printStackTrace();
			return false;
		}

		this.lastURI = xmlURI;
		// save also as zarr metadata object

		return true;
	}

	/**
	 * Saves a SpimData2 object using its own URI into a filename specified by xmlFilename
	 * 
	 * @param data - SpimData2 object (contains basePath)
	 * @param xmlFileName - the filename (e.g. dataset.xml)
	 * @return the assembled, full path it was saved as
	 */
	public URI saveWithFilename( final SpimData2 data, final String xmlFileName )
	{
		final URI xml = URITools.xmlFilenameToFullPath(data, xmlFileName);

		this.save( data, xml );

		return xml;
	}

	public SpimData2 load( final URI xmlURI ) throws SpimDataException
	{
		//IOFunctions.println( "Loading: " + xmlURI.toString() );

		return URITools.loadSpimData( xmlURI, this );
	}

	@Override
	public SpimData2 fromXml( final Element root, final URI xmlFile ) throws SpimDataException
	{
		final SpimData2 spimData = super.fromXml( root, xmlFile );
		final SequenceDescription seq = spimData.getSequenceDescription();

		final ViewInterestPoints viewsInterestPoints;
		Element elem = root.getChild( xmlViewsInterestPoints.getTag() );
		if ( elem == null )
		{
			viewsInterestPoints = new ViewInterestPoints();
			//viewsInterestPoints.createViewInterestPoints( seq.getViewDescriptions() );
		}
		else
		{
			viewsInterestPoints = xmlViewsInterestPoints.fromXml( elem, spimData.getBasePathURI(), seq.getViewDescriptions() );
		}
		spimData.setViewsInterestPoints( viewsInterestPoints );

		final BoundingBoxes boundingBoxes;
		elem = root.getChild( xmlBoundingBoxes.getTag() );
		if ( elem == null )
			boundingBoxes = new BoundingBoxes();
		else
			boundingBoxes = xmlBoundingBoxes.fromXml( elem );
		spimData.setBoundingBoxes( boundingBoxes );

		final PointSpreadFunctions psfs;
		elem = root.getChild( xmlPointSpreadFunctions.getTag() );
		if ( elem == null )
			psfs = new PointSpreadFunctions();
		else
			psfs = xmlPointSpreadFunctions.fromXml( elem, spimData.getBasePathURI() );
		spimData.setPointSpreadFunctions( psfs );

		final StitchingResults stitchingResults;
		elem = root.getChild( xmlStitchingResults.getTag() );
		if ( elem == null )
			stitchingResults = new StitchingResults();
		else
			stitchingResults = xmlStitchingResults.fromXml( elem );
		spimData.setStitchingResults( stitchingResults );

		final IntensityAdjustments intensityAdjustments;
		elem = root.getChild( xmlIntensityAdjustments.getTag() );
		if ( elem == null )
			intensityAdjustments = new IntensityAdjustments();
		else
			intensityAdjustments = xmlIntensityAdjustments.fromXml( elem );
		spimData.setIntensityAdjustments( intensityAdjustments );

		return spimData;
	}

	@Override
	public Element toXml( final SpimData2 spimData, final File xmlFileDirectory ) throws SpimDataException
	{
		return toXml(spimData, xmlFileDirectory.toURI() );
	}

	@Override
	public Element toXml( final SpimData2 spimData, final URI xmlFileDirectory ) throws SpimDataException
	{
		final Element root = super.toXml( spimData, xmlFileDirectory );

		root.addContent( xmlViewsInterestPoints.toXml( spimData.getViewInterestPoints() ) );
		root.addContent( xmlBoundingBoxes.toXml( spimData.getBoundingBoxes() ) );
		root.addContent( xmlPointSpreadFunctions.toXml( spimData.getPointSpreadFunctions() ) );
		root.addContent( xmlStitchingResults.toXml( spimData.getStitchingResults() ) );
		root.addContent( xmlIntensityAdjustments.toXml( spimData.getIntensityAdjustments() ) );

		return root;
	}

	public static void savePSFsInParallel( final SpimData2 spimData )
	{
		IOFunctions.println( "Saving PSFs multi-threaded ... " );

		spimData.getPointSpreadFunctions().getPointSpreadFunctions().values().parallelStream().forEach( psf ->
		{
			if ( psf.isModified() )
			{
				if ( !psf.save() )
					IOFunctions.println( "ERROR: Could not save PSF '" + psf.getFile() + "'" );
				else
					IOFunctions.println( "Saved PSF '" + psf.getFile() + "'" );
			}
		});
	}

	public static void saveInterestPointsInParallel( final SpimData2 spimData )
	{
		IOFunctions.println( "Saving interest points multi-threaded ... " );

		// collect first to avoid nested parallel streams
		final ArrayList< InterestPoints > allIPs = new ArrayList<>();

		spimData.getViewInterestPoints().getViewInterestPoints().values().forEach( vipl ->
			allIPs.addAll( vipl.getHashMap().values() ) );

		allIPs.parallelStream().forEach( ipl ->
		{
			try
			{
				ipl.saveInterestPoints( false );
				ipl.saveCorrespondingInterestPoints( false );
			}
			catch ( Exception e )
			{
				IOFunctions.println( "Could not save interest points for (trying to skip): " + ipl.getXMLRepresentation()  );
			}
		});
	}
}
