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
package net.preibisch.mvrecon.headless.definedataset;

import java.io.File;

import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.SequenceDescription;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.datasetmanager.DatasetCreationUtils;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBoxes;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.LegacyStackImgLoaderLOCI;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.StackImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.StackImgLoaderLOCI;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;

/**
 * DataSet definition for LOCI
 */
public class StackListLOCI extends StackList
{
	public static SpimData2 createDataset( final String file, final StackListParameters params )
	{
		// assemble timepints, viewsetups, missingviews and the imgloader
		final SequenceDescription sequenceDescription = createSequenceDescription( params.timepoints, params.channels, params.illuminations, params.angles, params.tiles, loadCalibration(new File(file)) );
		final ImgLoader imgLoader = createAndInitImgLoader( ".", new File( params.directory ), sequenceDescription, params );
		sequenceDescription.setImgLoader( imgLoader );

		// get the minimal resolution of all calibrations
		final double minResolution = DatasetCreationUtils.minResolution(
				sequenceDescription,
				sequenceDescription.getViewDescriptions().values() );

		IOFunctions.println( "Minimal resolution in all dimensions over all views is: " + minResolution );
		IOFunctions.println( "(The smallest resolution in any dimension; the distance between two pixels in the output image will be that wide)" );

		// create the initial view registrations (they are all the identity transform)
		final ViewRegistrations viewRegistrations = createViewRegistrations( sequenceDescription.getViewDescriptions(), minResolution );

		// create the initial view interest point object
		final ViewInterestPoints viewInterestPoints = new ViewInterestPoints();
		//viewInterestPoints.createViewInterestPoints( sequenceDescription.getViewDescriptions() );

		// finally create the SpimData itself based on the sequence description and the view registration
		final SpimData2 spimData = new SpimData2( new File( params.directory ).toURI(), sequenceDescription, viewRegistrations, viewInterestPoints, new BoundingBoxes(), new PointSpreadFunctions(), new StitchingResults(), new IntensityAdjustments() );

		return spimData;
	}

	static StackImgLoader createAndInitImgLoader( final String path, final File basePath, final SequenceDescription sequenceDescription, final StackListParameters params )
	{
		int hasMultipleAngles = 0, hasMultipleTimePoints = 0, hasMultipleChannels = 0, hasMultipleIlluminations = 0, hasMultipleTiles = 0;

		switch ( params.multipleAngleOption )
		{
			case OneAngle: hasMultipleAngles = 0; break;
			case OneFilePerAngle: hasMultipleAngles = 1; break;
			case AllAnglesInOneFile: hasMultipleAngles = 2; break;
		}
		switch ( params.multipleTimePointOption )
		{
			case OneTimePoint: hasMultipleTimePoints = 0; break;
			case OneFilePerTimePoint: hasMultipleTimePoints = 1; break;
			case AllTimePointsInOneFile: hasMultipleTimePoints = 2; break;
		}
		switch ( params.multipleChannelOption )
		{
			case OneChannel: hasMultipleChannels = 0; break;
			case OneFilePerChannel: hasMultipleChannels = 1; break;
			case AllChannelsInOneFile: hasMultipleChannels = 2; break;
		}
		switch ( params.multipleIlluminationOption )
		{
			case OneIllumination: hasMultipleIlluminations = 0; break;
			case OneFilePerIllumination: hasMultipleIlluminations = 1; break;
			case AllIlluminationsInOneFile: hasMultipleIlluminations = 2; break;
		}
		switch ( params.multipleTileOption )
		{
			case OneTile: hasMultipleTiles = 0; break;
			case OneFilePerTile: hasMultipleTiles = 1; break;
			case AllTilesInOneFile: hasMultipleTiles = 2; break;
		}

		String fileNamePattern = assembleDefaultPattern( hasMultipleTimePoints, hasMultipleChannels, hasMultipleIlluminations, hasMultipleAngles, hasMultipleTiles );

		// TODO: Tiles are missing
		return new StackImgLoaderLOCI(
				new File( basePath.getAbsolutePath(), path ),
				fileNamePattern,
				hasMultipleTimePoints, hasMultipleChannels, hasMultipleIlluminations, hasMultipleAngles, hasMultipleTiles,
				sequenceDescription );
	}

	static Calibration loadCalibration( final File file )
	{
		IOFunctions.println( "Loading calibration for: " + file.getAbsolutePath() );

		if ( !file.exists() )
		{
			IOFunctions.println( "File '" + file + "' does not exist. Stopping." );
			return null;
		}

		final net.preibisch.mvrecon.fiji.spimdata.imgloaders.Calibration cal = LegacyStackImgLoaderLOCI.loadMetaData( file );

		if ( cal == null )
			return null;

		final double calX = cal.getCalX();
		final double calY = cal.getCalY();
		final double calZ = cal.getCalZ();

		return new Calibration( calX, calY, calZ );
	}
}
