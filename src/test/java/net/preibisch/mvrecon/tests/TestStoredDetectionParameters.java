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
package net.preibisch.mvrecon.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.fiji.plugin.Interest_Point_Detection;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.DifferenceOfGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.DifferenceOfGaussianGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.ScaleSpaceGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.StoredDetectionParameters;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter.Axis;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;

/**
 * The parameters string of a label parsed back (StoredDetectionParameters.parse) and written into the defaults of the
 * detection dialog (preset), what "Re-detect interest points with attributes" of the ViewSetup Explorer does
 */
public class TestStoredDetectionParameters
{
	// the strings as the GUIs write them since 2026-10-27 (DifferenceOfGUI.commonParameters)
	static final String DOG = "DOG s=1.8 t=0.008 min=false max=true downsampleX=2 downsampleY=2 downsampleZ=1 anisotropy=3.5 minIntensity=0.0 maxIntensity=1137.0 localization=QUADRATIC groupTiles=true groupIllums=false thresholdType=noisefloor noiseFactor=3.0";
	static final String SS = "DOG-SS s=1.6 steps=4 octaves=-1 finestLevel=true t=0.01 min=false max=true downsampleX=1 downsampleY=1 downsampleZ=1 anisotropy=1.0 minIntensity=NaN maxIntensity=NaN localization=NONE groupTiles=false groupIllums=true thresholdType=fixed";
	// the strings before that (no localization, grouping, threshold type)
	static final String DOG_2026_10 = "DOG s=1.8 t=0.008 min=false max=true downsampleX=2 downsampleY=2 downsampleZ=1 anisotropy=3.5 minIntensity=0.0 maxIntensity=1137.0";
	static final String SPARK_DOG = "DOG (Spark) s=1.8 t=0.008 overlappingOnly=true min=false max=true downsampleXY=4 downsampleZ=2 minIntensity=0.0 maxIntensity=2048.0";
	static final String MASTER_DOG = "DOG s=1.8 t=0.008 min=true max=true imageSigmaX=0.5 imageSigmaY=0.5 imageSigmaZ=0.5 downsampleXYIndex=4 downsampleZ=2 minIntensity=0.0 maxIntensity=65535.0";
	static final String MASTER_DOG_MATCH_Z = "DOG s=1.8 t=0.008 min=false max=true imageSigmaX=0.5 imageSigmaY=0.5 imageSigmaZ=0.5 downsampleXYIndex=0 downsampleZ=2 minIntensity=0.0 maxIntensity=65535.0";
	static final String EARLY_SS = "DOG-SS s=1.6 steps=3 octaves=2 finestLevel=false t=0.02 min=false max=true downsampleXY=2 downsampleXYIndex=2 downsampleZ=1 minIntensity=NaN maxIntensity=NaN";

	// the statics preset writes, saved before and restored after each test (they are shared by all tests of the JVM)
	String label;
	int algorithm, resolutionIndex, brightness, thresholdChoice, filterChoice, ssSteps, ssOctaves, ssSpecification, localization;
	boolean setMinMax, manual, dogMin, dogMax, ssMin, ssMax, groupTiles, groupIllums;
	long[] manualDownsampling;
	double anisotropyZ, anisotropyCalibration, minIntensity, maxIntensity, dogSigma, dogThreshold, ssSigma, ssThreshold, noiseFactor;
	Boolean finest;

	@BeforeEach
	public void save()
	{
		label = Interest_Point_Detection.defaultLabel;
		algorithm = Interest_Point_Detection.defaultAlgorithm;
		setMinMax = Interest_Point_Detection.defaultSetMinMax;
		groupTiles = Interest_Point_Detection.defaultGroupTiles;
		groupIllums = Interest_Point_Detection.defaultGroupIllums;
		localization = DifferenceOfGUI.defaultLocalization;
		noiseFactor = DifferenceOfGUI.defaultNoiseFactor;
		resolutionIndex = DifferenceOfGUI.defaultResolutionIndex;
		manual = DifferenceOfGUI.defaultManual;
		manualDownsampling = DifferenceOfGUI.defaultManualDownsampling;
		anisotropyZ = DifferenceOfGUI.defaultAnisotropyZ;
		anisotropyCalibration = DifferenceOfGUI.defaultAnisotropyCalibration;
		brightness = DifferenceOfGUI.defaultBrightness;
		minIntensity = DifferenceOfGUI.defaultMinIntensity;
		maxIntensity = DifferenceOfGUI.defaultMaxIntensity;
		thresholdChoice = DifferenceOfGUI.defaultThresholdChoice;
		filterChoice = DifferenceOfGUI.defaultFilterChoice;
		dogSigma = DifferenceOfGaussianGUI.defaultSigma;
		dogThreshold = DifferenceOfGaussianGUI.defaultThreshold;
		dogMin = DifferenceOfGaussianGUI.defaultFindMin;
		dogMax = DifferenceOfGaussianGUI.defaultFindMax;
		ssSigma = ScaleSpaceGUI.defaultSigma;
		ssThreshold = ScaleSpaceGUI.defaultThreshold;
		ssSteps = ScaleSpaceGUI.defaultSteps;
		ssOctaves = ScaleSpaceGUI.defaultOctaves;
		ssMin = ScaleSpaceGUI.defaultFindMin;
		ssMax = ScaleSpaceGUI.defaultFindMax;
		finest = ScaleSpaceGUI.lastDetectFinestLevel;
		ssSpecification = ScaleSpaceGUI.defaultSpecification;
	}

	@AfterEach
	public void restore()
	{
		Interest_Point_Detection.defaultLabel = label;
		Interest_Point_Detection.defaultAlgorithm = algorithm;
		Interest_Point_Detection.defaultSetMinMax = setMinMax;
		Interest_Point_Detection.defaultGroupTiles = groupTiles;
		Interest_Point_Detection.defaultGroupIllums = groupIllums;
		DifferenceOfGUI.defaultLocalization = localization;
		DifferenceOfGUI.defaultNoiseFactor = noiseFactor;
		DifferenceOfGUI.defaultResolutionIndex = resolutionIndex;
		DifferenceOfGUI.defaultManual = manual;
		DifferenceOfGUI.defaultManualDownsampling = manualDownsampling;
		DifferenceOfGUI.defaultAnisotropyZ = anisotropyZ;
		DifferenceOfGUI.defaultAnisotropyCalibration = anisotropyCalibration;
		DifferenceOfGUI.defaultBrightness = brightness;
		DifferenceOfGUI.defaultMinIntensity = minIntensity;
		DifferenceOfGUI.defaultMaxIntensity = maxIntensity;
		DifferenceOfGUI.defaultThresholdChoice = thresholdChoice;
		DifferenceOfGUI.defaultFilterChoice = filterChoice;
		DifferenceOfGaussianGUI.defaultSigma = dogSigma;
		DifferenceOfGaussianGUI.defaultThreshold = dogThreshold;
		DifferenceOfGaussianGUI.defaultFindMin = dogMin;
		DifferenceOfGaussianGUI.defaultFindMax = dogMax;
		ScaleSpaceGUI.defaultSigma = ssSigma;
		ScaleSpaceGUI.defaultThreshold = ssThreshold;
		ScaleSpaceGUI.defaultSteps = ssSteps;
		ScaleSpaceGUI.defaultOctaves = ssOctaves;
		ScaleSpaceGUI.defaultFindMin = ssMin;
		ScaleSpaceGUI.defaultFindMax = ssMax;
		ScaleSpaceGUI.lastDetectFinestLevel = finest;
		ScaleSpaceGUI.defaultSpecification = ssSpecification;
	}

	@Test
	public void testParse()
	{
		final StoredDetectionParameters dog = StoredDetectionParameters.parse( DOG );
		assertNotNull( dog );
		assertFalse( dog.scaleSpace() );
		assertEquals( 1.8, dog.sigma(), 0.0 );
		assertEquals( 0.008, dog.threshold(), 0.0 );
		assertFalse( dog.findMin() );
		assertTrue( dog.findMax() );
		assertArrayEquals( new long[] { 2, 2, 1 }, dog.downsampling() );
		assertEquals( 3.5, dog.anisotropy(), 0.0 );
		assertEquals( 0.0, dog.minIntensity(), 0.0 );
		assertEquals( 1137.0, dog.maxIntensity(), 0.0 );
		assertEquals( -1, dog.steps() );
		assertEquals( Integer.MIN_VALUE, dog.octaves() );
		assertNull( dog.finestLevel() );
		assertEquals( 1, dog.localization() );
		assertEquals( Boolean.TRUE, dog.groupTiles() );
		assertEquals( Boolean.FALSE, dog.groupIllums() );
		assertTrue( dog.hasGrouping() );
		assertEquals( Boolean.TRUE, dog.thresholdFromNoiseFloor() );
		assertEquals( 3.0, dog.noiseFactor(), 0.0 );

		// the strings before the extension: the new fields are absent
		final StoredDetectionParameters older = StoredDetectionParameters.parse( DOG_2026_10 );
		assertEquals( -1, older.localization() );
		assertNull( older.groupTiles() );
		assertFalse( older.hasGrouping() );
		assertNull( older.thresholdFromNoiseFloor() );
		assertTrue( Double.isNaN( older.noiseFactor() ) );
		assertEquals( 3.5, older.effectiveAnisotropy( 9.0 ), 0.0, "a stored anisotropy wins" );

		// BigStitcher-Spark's DoG: isotropic in pixels of the downsampled image = downsampleXY / downsampleZ at full resolution
		final StoredDetectionParameters spark = StoredDetectionParameters.parse( SPARK_DOG );
		assertNotNull( spark );
		assertFalse( spark.scaleSpace() );
		assertArrayEquals( new long[] { 4, 4, 2 }, spark.downsampling() );
		assertEquals( 2.0, spark.effectiveAnisotropy( 9.0 ), 0.0 );
		assertEquals( 2.0, StoredDetectionParameters.parse( MASTER_DOG ).effectiveAnisotropy( 9.0 ), 0.0 );
		assertEquals( 1.0, StoredDetectionParameters.parse( MASTER_DOG_MATCH_Z ).effectiveAnisotropy( 9.0 ), 0.0, "factors unknown" );
		// the early scale space: the ratio of the calibration
		assertEquals( 9.0, StoredDetectionParameters.parse( EARLY_SS ).effectiveAnisotropy( 9.0 ), 0.0 );
		assertEquals( 0, DifferenceOfGUI.localizationIndex( "NONE" ) );
		assertEquals( 2, DifferenceOfGUI.localizationIndex( "GAUSS_FIT" ) );
		assertEquals( 1, DifferenceOfGUI.localizationIndex( "1" ) );
		assertEquals( -1, DifferenceOfGUI.localizationIndex( "whatever" ) );

		final StoredDetectionParameters ss = StoredDetectionParameters.parse( SS );
		assertNotNull( ss );
		assertTrue( ss.scaleSpace() );
		assertEquals( 1.6, ss.sigma(), 0.0 );
		assertEquals( 0.01, ss.threshold(), 0.0 );
		assertEquals( 4, ss.steps() );
		assertEquals( -1, ss.octaves() );
		assertEquals( Boolean.TRUE, ss.finestLevel() );
		assertArrayEquals( new long[] { 1, 1, 1 }, ss.downsampling() );
		assertEquals( 1.0, ss.anisotropy(), 0.0 );
		assertTrue( Double.isNaN( ss.minIntensity() ) && Double.isNaN( ss.maxIntensity() ) );

		// master's DoG: downsampleXYIndex holds the factor, 0 / -1 meant "match z"; no anisotropy
		final StoredDetectionParameters master = StoredDetectionParameters.parse( MASTER_DOG );
		assertNotNull( master );
		assertArrayEquals( new long[] { 4, 4, 2 }, master.downsampling() );
		assertTrue( master.findMin() );
		assertTrue( Double.isNaN( master.anisotropy() ) );
		assertEquals( 65535.0, master.maxIntensity(), 0.0 );
		assertNull( StoredDetectionParameters.parse( MASTER_DOG_MATCH_Z ).downsampling() );

		// the first scale-space strings: downsampleXY
		final StoredDetectionParameters early = StoredDetectionParameters.parse( EARLY_SS );
		assertNotNull( early );
		assertArrayEquals( new long[] { 2, 2, 1 }, early.downsampling() );
		assertEquals( 3, early.steps() );
		assertEquals( 2, early.octaves() );
		assertEquals( Boolean.FALSE, early.finestLevel() );

		// not detections
		final String filtered = InterestPointFilter.of( Axis.SIGMA, 2, Double.NaN ).describe( "beads" );
		assertNull( StoredDetectionParameters.parse( filtered ) );
		assertNull( StoredDetectionParameters.parse( filtered + " (copied)" ) );
		assertNull( StoredDetectionParameters.parse( "thinned out beads 2.0 .. 5.0" ) );
		assertNull( StoredDetectionParameters.parse( "" ) );
		assertNull( StoredDetectionParameters.parse( null ) );

		// a value that does not parse counts as absent
		final StoredDetectionParameters broken = StoredDetectionParameters.parse( "DOG s=abc t=0.5 downsampleX=x downsampleY=2 downsampleZ=1" );
		assertNotNull( broken );
		assertTrue( Double.isNaN( broken.sigma() ) );
		assertEquals( 0.5, broken.threshold(), 0.0 );
		assertNull( broken.downsampling() );
	}

	@Test
	public void testPreset()
	{
		final SpimData2 data = SimulateUtil.setUp();
		final List< ViewId > views = new ArrayList<>( data.getSequenceDescription().getViewDescriptions().keySet() );
		Collections.sort( views );
		final String[] resolutions = DownsampleTools.availableDownsamplings( data, views.get( 0 ) );

		int fullResolution = -1;
		for ( int i = 0; i < resolutions.length; ++i )
			if ( Arrays.equals( new long[] { 1, 1, 1 }, DownsampleTools.parseDownsampleChoice( resolutions[ i ] ) ) )
				fullResolution = i;
		assertTrue( fullResolution >= 0, "the full resolution is one of the choices: " + Arrays.toString( resolutions ) );

		// scale space, full resolution, no intensity range
		StoredDetectionParameters.parse( SS ).preset( data, views, "old" );
		assertEquals( "old", Interest_Point_Detection.defaultLabel );
		assertEquals( 1, Interest_Point_Detection.defaultAlgorithm );
		assertFalse( Interest_Point_Detection.defaultSetMinMax );
		assertEquals( fullResolution, DifferenceOfGUI.defaultResolutionIndex );
		assertFalse( DifferenceOfGUI.defaultManual );
		assertEquals( 1.0, DifferenceOfGUI.defaultAnisotropyZ, 0.0 );
		assertEquals( DifferenceOfGUI.calibrationAnisotropy( data.getSequenceDescription().getViewDescription( views.get( 0 ) ) ), DifferenceOfGUI.defaultAnisotropyCalibration, 0.0 );
		assertEquals( 0, DifferenceOfGUI.defaultThresholdChoice );
		assertEquals( DifferenceOfGUI.filterChoice.length - 1, DifferenceOfGUI.defaultFilterChoice );
		assertEquals( 1.6, ScaleSpaceGUI.defaultSigma, 0.0 );
		assertEquals( 0.01, ScaleSpaceGUI.defaultThreshold, 0.0 );
		assertEquals( 4, ScaleSpaceGUI.defaultSteps );
		assertEquals( -1, ScaleSpaceGUI.defaultOctaves );
		assertEquals( Boolean.TRUE, ScaleSpaceGUI.lastDetectFinestLevel );
		assertFalse( ScaleSpaceGUI.defaultFindMin );
		assertTrue( ScaleSpaceGUI.defaultFindMax );
		assertEquals( 0, ScaleSpaceGUI.defaultSpecification );
		assertEquals( 0, DifferenceOfGUI.defaultLocalization );
		assertFalse( Interest_Point_Detection.defaultGroupTiles );
		assertTrue( Interest_Point_Detection.defaultGroupIllums );

		// legacy DoG, a downsampling that is no level of the data (manual), an intensity range
		final String manualDog = DOG.replace( "downsampleX=2 downsampleY=2 downsampleZ=1", "downsampleX=64 downsampleY=64 downsampleZ=64" );
		for ( final String resolution : resolutions )
			assertFalse( Arrays.equals( new long[] { 64, 64, 64 }, DownsampleTools.parseDownsampleChoice( resolution ) ) );
		StoredDetectionParameters.parse( manualDog ).preset( data, views, "beads" );
		assertEquals( "beads", Interest_Point_Detection.defaultLabel );
		assertEquals( 0, Interest_Point_Detection.defaultAlgorithm );
		assertTrue( Interest_Point_Detection.defaultSetMinMax );
		assertEquals( 0.0, DifferenceOfGUI.defaultMinIntensity, 0.0 );
		assertEquals( 1137.0, DifferenceOfGUI.defaultMaxIntensity, 0.0 );
		assertTrue( DifferenceOfGUI.defaultManual );
		assertArrayEquals( new long[] { 64, 64, 64 }, DifferenceOfGUI.defaultManualDownsampling );
		assertEquals( 3.5, DifferenceOfGUI.defaultAnisotropyZ, 0.0 );
		assertEquals( 1.8, DifferenceOfGaussianGUI.defaultSigma, 0.0 );
		assertEquals( 0.008, DifferenceOfGaussianGUI.defaultThreshold, 0.0 );
		assertFalse( DifferenceOfGaussianGUI.defaultFindMin );
		assertTrue( DifferenceOfGaussianGUI.defaultFindMax );
		assertEquals( 4, DifferenceOfGUI.defaultBrightness );
		assertEquals( 1, DifferenceOfGUI.defaultLocalization );
		assertTrue( Interest_Point_Detection.defaultGroupTiles );
		assertFalse( Interest_Point_Detection.defaultGroupIllums );
		assertEquals( 1, DifferenceOfGUI.defaultThresholdChoice, "estimated from the noise floor" );
		assertEquals( 3.0, DifferenceOfGUI.defaultNoiseFactor, 0.0 );

		// an older string: the grouping and the localization stay as they are, the threshold type is fixed, the anisotropy is derived
		DifferenceOfGUI.defaultLocalization = 2;
		Interest_Point_Detection.defaultGroupTiles = true;
		Interest_Point_Detection.defaultGroupIllums = true;
		StoredDetectionParameters.parse( MASTER_DOG ).preset( data, views, "master" );
		assertEquals( 2, DifferenceOfGUI.defaultLocalization );
		assertTrue( Interest_Point_Detection.defaultGroupTiles && Interest_Point_Detection.defaultGroupIllums );
		assertEquals( 0, DifferenceOfGUI.defaultThresholdChoice );
		assertEquals( 2.0, DifferenceOfGUI.defaultAnisotropyZ, 0.0, "4 / 2: isotropic in pixels of the downsampled image" );
		assertEquals( "master", Interest_Point_Detection.defaultLabel );

		// no factors known: anisotropy 1; the resolution default is left as it is
		StoredDetectionParameters.parse( MASTER_DOG_MATCH_Z ).preset( data, views, "master" );
		assertEquals( 1.0, DifferenceOfGUI.defaultAnisotropyZ, 0.0 );
		assertTrue( DifferenceOfGUI.defaultManual, "unchanged from the previous preset" );

		// the early scale space: the calibration ratio of the view
		StoredDetectionParameters.parse( EARLY_SS ).preset( data, views, "early" );
		assertEquals( DifferenceOfGUI.calibrationAnisotropy( data.getSequenceDescription().getViewDescription( views.get( 0 ) ) ), DifferenceOfGUI.defaultAnisotropyZ, 0.0 );
	}
}
