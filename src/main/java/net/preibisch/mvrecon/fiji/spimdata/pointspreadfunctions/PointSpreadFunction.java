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
package net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions;

import java.io.File;
import java.net.URI;

import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Cast;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import util.URITools;

public class PointSpreadFunction
{
	private final static String subDirFile = "psf";
	private final static String subPath = "psf.n5";

	private URI xmlBasePath;
	private final String file;
	private Img< FloatType > img;
	private boolean modified;

	public PointSpreadFunction( final URI xmlBasePath, final String file, final Img< FloatType > img )
	{
		this.xmlBasePath = xmlBasePath;
		this.file = file;

		if ( img != null )
			this.img = copy( img ); // avoid changes to the PSF if an actual image is provided

		this.modified = true; // not initialized from disc, needs to be saved
	}

	public PointSpreadFunction( final SpimData2 spimData, final ViewId viewId, final Img< FloatType > img )
	{
		this( spimData.getBasePathURI(), PointSpreadFunction.createPSFFilePath( viewId ), img );
	}

	public PointSpreadFunction( final URI xmlBasePath, final String file )
	{
		this( xmlBasePath, file, null );
		this.modified = false;
	}

	public void setPSF( final Img< FloatType > img )
	{
		this.modified = true;
		this.img = img;
	}

	public URI getBaseDir() { return xmlBasePath; }
	public void setBaseDir( final URI baseDir )
	{
		this.xmlBasePath = baseDir;
		this.modified = true;
	}

	public String getFile() { return file; }
	public boolean isModified() { return modified; }
	public synchronized boolean isLoaded() { return img != null; }

	public Img< FloatType > getPSFCopy()
	{
		if ( img == null )
			img = load();

		return copy( img );
	}

	// this is required for CUDA stuff
	public ArrayImg< FloatType, ? > getPSFCopyArrayImg()
	{
		final ArrayImg< FloatType, ? > arrayImg;

		if ( img == null )
			img = load();

		if ( ArrayImg.class.isInstance( img ) )
			arrayImg = (ArrayImg< FloatType, ? >)img;
		else
			arrayImg = copy( img );

		return arrayImg;
	}

	public synchronized Img< FloatType > load()
	{
		if ( file.endsWith( ".tif" ) )
		{
			// compatibility with old code
			return IOFunctions.openAs32Bit( new File( URITools.appendName( xmlBasePath, subDirFile ), file ), new ArrayImgFactory<>( new FloatType() ) );
		}
		else
		{
			// load the .n5
			final N5Reader n5Reader = URITools.instantiateN5Reader( StorageFormat.N5, URITools.toURI( URITools.appendName( xmlBasePath, subPath ) ) );
			final Img<FloatType> img = Cast.unchecked( N5Utils.open( n5Reader, file ) );

			n5Reader.close();

			return img;
		}
	}

	public synchronized boolean save()
	{
		if ( img == null )
			return false;

		final N5Writer n5Writer = URITools.instantiateN5Writer( StorageFormat.N5, URITools.toURI( URITools.appendName( xmlBasePath, subPath ) ) );

		if ( n5Writer.datasetExists( file ) )
			n5Writer.remove( file );

		try
		{
			N5Utils.save( img, n5Writer, file, new int[] { 128, 128, 128 }, new GzipCompression( 1 ) );
			n5Writer.close();
			modified = false;

			return true;
		}
		catch ( Exception e )
		{
			IOFunctions.println( "Error saving PSF '" + file + "' into container '" + URITools.appendName( xmlBasePath, subPath ) + "': " + e );
			e.printStackTrace();
			return false;
		}
	}

	private static ArrayImg< FloatType, ? > copy( final Img< FloatType > img )
	{
		final ArrayImg< FloatType, ? > arrayImg = ArrayImgs.floats( img.dimensionsAsLongArray() );

		FusionTools.copyImg( img, arrayImg, null );

		return arrayImg;
	}

	private static String createPSFFilePath( final ViewId viewId )
	{
		return "psf_t" + viewId.getTimePointId() + "_v" + viewId.getViewSetupId();
	}
}
