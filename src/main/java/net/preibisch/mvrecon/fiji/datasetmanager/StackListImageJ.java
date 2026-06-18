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
package net.preibisch.mvrecon.fiji.datasetmanager;

import ij.ImagePlus;
import ij.io.Opener;

import java.io.File;

import mpicbg.spim.data.sequence.SequenceDescription;
import net.imglib2.img.ImgFactory;
import net.imglib2.type.NativeType;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.StackImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.StackImgLoaderIJ;

public class StackListImageJ extends StackList
{
	public static int defaultAngleChoice = 1;
	public static int defaultTimePointChoice = 1;
	public static int defaultChannelleChoice = 0;
	public static int defaultIlluminationChoice = 0;
	public static int defaultTileChoice = 0;

	@Override
	public String getTitle() 
	{
		return "Manual Loader (TIFF only, ImageJ Opener)";
	}

	@Override
	public String getExtendedDescription()
	{
		return "This dataset definition supports a series of three-dimensional (3d) image stacks\n" +  
				 "all present in the same folder. The filename of each file must define timepoint,\n" +  
				 "angle, channel and illumination direction (or a subset of those).\n" + 
				 "The 3d image stacks must be stored as TIFF that can be opened by ImageJ natively.\n" + 
				 "\n" + 
				 "\n" + 
				 "The filenames of the 3d image stacks could be for example:\n" +
				 "\n" + 
				 "spim_TL1_Channel1_Illum1_Angle0.tif ... spim_TL100_Channel2_Illum2_Angle315.tif\n" + 
				 "data_TP01_Angle000.lsm ... data_TP70_Angle180.lsm\n" +
				 "Angle0.ome.tiff ... Angle288.ome.tiff\n" +
				 "\n" +
				 "Note: this definition can be used for OpenSPIM data if saved as plain TIFF.";
	}

	@Override
	protected Calibration loadCalibration( final File file ) 
	{
		try
		{
			IOFunctions.println( "Loading calibration for: " + file.getAbsolutePath() );
			
			if ( !file.exists() )
			{
				IOFunctions.println( "File '" + file + "' does not exist. Stopping." );
				return null;
			}
			
			final ImagePlus imp = new Opener().openImage( file.getAbsolutePath() );

			if ( imp == null )
			{
				IOFunctions.println( "Could not open file: '" + file.getAbsolutePath() + "'" );
				return null;				
			}
			
			final ij.measure.Calibration c = imp.getCalibration();
			
			final double calX = c.pixelWidth;
			final double calY = c.pixelHeight;
			final double calZ = c.pixelDepth;
			
			String calUnit = c.getUnit();
			
			if ( calUnit.contains( "µ" ) )
				calUnit = calUnit.replace( 'µ', 'u' );
			
			imp.close();
			
			return new Calibration( calX, calY, calZ, calUnit );
		}
		catch ( Exception e )
		{
			IOFunctions.println( "Could not open file: '" + file.getAbsolutePath() + "'" );
			return null;
		}
	}

	@Override
	protected double[] loadTileLocationFromMetaData(File file, int seriesOffset) { return null; }
	
	@Override
	protected StackImgLoader createAndInitImgLoader( final String path, final File basePath, SequenceDescription sequenceDescription )
	{
		return new StackImgLoaderIJ(
				new File( basePath.getAbsolutePath(), path ),
				fileNamePattern,
				hasMultipleTimePoints, hasMultipleChannels, hasMultipleIlluminations, hasMultipleAngles, hasMultipleTiles,
				sequenceDescription );
	}
	
	@Override
	protected boolean canLoadTileLocationFromMeta() { return false; }

	@Override
	protected boolean supportsMultipleTimepointsPerFile() { return false; }

	@Override
	protected boolean supportsMultipleChannelsPerFile() { return false; }

	@Override
	protected boolean supportsMultipleAnglesPerFile() { return false; }

	@Override
	protected boolean supportsMultipleIlluminationsPerFile() { return false; }
	
	@Override
	protected boolean supportsMultipleTilesPerFile() { return false; }

	@Override
	protected int getDefaultMultipleAngles() { return defaultAngleChoice; }

	@Override
	protected int getDefaultMultipleTimepoints() { return defaultTimePointChoice; }

	@Override
	protected int getDefaultMultipleChannels() { return defaultChannelleChoice; }

	@Override
	protected int getDefaultMultipleIlluminations() { return defaultIlluminationChoice; }
	
	@Override
	protected int getDefaultMultipleTiles() { return defaultTileChoice; }

	@Override
	protected void setDefaultMultipleAngles( final int a ) { defaultAngleChoice = a; }

	@Override
	protected void setDefaultMultipleTimepoints( final int t ) { defaultTimePointChoice = t; }

	@Override
	protected void setDefaultMultipleChannels( final int c ) { defaultChannelleChoice = c; }

	@Override
	protected void setDefaultMultipleIlluminations( final int i ) { defaultIlluminationChoice = i; }
	
	@Override
	protected void setDefaultMultipleTiles( final int ti ) { defaultTileChoice = ti; }

	@Override
	public StackListImageJ newInstance() { return new StackListImageJ(); }
}
