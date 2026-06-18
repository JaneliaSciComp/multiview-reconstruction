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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import ij.IJ;
import ij.ImagePlus;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

public abstract class LazyLoadingFlatFieldCorrectionMap<IL extends ImgLoader> implements FlatfieldCorrectionWrappedImgLoader< IL >
{
	
	protected final Map< File, RandomAccessibleInterval< FloatType > > raiMap;
	protected final Map<ViewId, Pair<File, File>> fileMap;
	
	public LazyLoadingFlatFieldCorrectionMap()
	{
		raiMap = new HashMap<>();
		fileMap = new HashMap<>();
	}
	
	@Override
	public void setBrightImage(ViewId vId, File imgFile)
	{
		if (!fileMap.containsKey( vId ))
			fileMap.put( vId, new ValuePair< File, File >( null, null ) );

		final Pair< File, File > oldPair = fileMap.get( vId );
		fileMap.put( vId, new ValuePair< File, File >( imgFile, oldPair.getB() ) );
	}

	@Override
	public void setDarkImage(ViewId vId, File imgFile)
	{
		if (!fileMap.containsKey( vId ))
			fileMap.put( vId, new ValuePair< File, File >( null, null ) );

		final Pair< File, File > oldPair = fileMap.get( vId );
		fileMap.put( vId, new ValuePair< File, File >( oldPair.getA(), imgFile ) );
	}
	
	protected RandomAccessibleInterval< FloatType > getBrightImg(ViewId vId)
	{
		if (!fileMap.containsKey( vId ))
			return null;

		final File fileToLoad = fileMap.get( vId ).getA();

		if (fileToLoad == null)
			return null;

		loadFileIfNecessary( fileToLoad );
		return raiMap.get( fileToLoad );
	}

	protected RandomAccessibleInterval< FloatType > getDarkImg(ViewId vId)
	{
		if (!fileMap.containsKey( vId ))
			return null;

		final File fileToLoad = fileMap.get( vId ).getB();

		if (fileToLoad == null)
			return null;

		loadFileIfNecessary( fileToLoad );
		return raiMap.get( fileToLoad );
	}
	
	protected void loadFileIfNecessary(File file)
	{
		if (raiMap.containsKey( file ))
			return;
		
		final ImagePlus imp = IJ.openImage( file.getAbsolutePath() );
		final RandomAccessibleInterval< FloatType > img = ImageJFunctions.convertFloat( imp ).copy();
		
		raiMap.put( file, img );
	}
	
	public static void main(String[] args)
	{
		DefaultFlatfieldCorrectionWrappedImgLoader testImgLoader = new DefaultFlatfieldCorrectionWrappedImgLoader( null );
		testImgLoader.setBrightImage( new ViewId(0,0), new File("/Users/David/Desktop/ell2.tif" ));
		RandomAccessibleInterval< FloatType > brightImg = testImgLoader.getBrightImg( new ViewId( 0, 0 ) );
		
		ImageJFunctions.show( brightImg );
		
	}

	public Map< ViewId, Pair< File, File > > getFileMap()
	{
		return fileMap;
	}
	

}
