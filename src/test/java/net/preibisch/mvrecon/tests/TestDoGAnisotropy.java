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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;

import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.img.Img;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.legacy.registration.bead.laplace.LaPlaceFunctions;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.DifferenceOfGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.DifferenceOfGaussianGUI;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionRecord;
import net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionToSparkCli;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoG;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGParameters;

/**
 * The anisotropy of the single-scale Difference-of-Gaussian (the convention it shares with the scale space): the sigmas
 * per dimension, the detection on an anisotropic grid, the driver, the dialog and the estimate from the calibration
 */
public class TestDoGAnisotropy
{
	/**
	 * Without anisotropy (or with 1) the sigmas are bit-identical to the ones before the anisotropy existed; with it the
	 * targets are divided by the anisotropy, the image blur is subtracted in quadrature and a target below it gives 0
	 */
	@Test
	public void testSigmas()
	{
		final Pair< double[][], Float > legacy = DoGImgLib2.computeSigmas( 1.8f, 3 );
		final Pair< double[][], Float > none = DoGImgLib2.computeSigmas( 1.8f, null, 3 );
		final Pair< double[][], Float > one = DoGImgLib2.computeSigmas( 1.8f, new double[] { 1, 1, 1 }, 3 );

		for ( int i = 0; i < 2; ++i )
		{
			assertArrayEquals( legacy.getA()[ i ], none.getA()[ i ], 0.0 );
			assertArrayEquals( legacy.getA()[ i ], one.getA()[ i ], 0.0 );
		}

		assertEquals( legacy.getB(), none.getB() );
		assertEquals( legacy.getB(), one.getB() );

		final float k = LaPlaceFunctions.computeK( 4 );
		assertEquals( 1.0 / ( k - 1 ), legacy.getB(), 1e-6 );

		// twice the voxel size in z: half the target sigma in z, x and y unchanged
		final double[][] two = DoGImgLib2.computeSigmas( 1.8f, new double[] { 1, 1, 2 }, 3 ).getA();

		for ( int i = 0; i < 2; ++i )
		{
			assertEquals( legacy.getA()[ i ][ 0 ], two[ i ][ 0 ], 0.0 );
			assertEquals( legacy.getA()[ i ][ 1 ], two[ i ][ 1 ], 0.0 );
		}

		assertEquals( Math.sqrt( 0.9 * 0.9 - 0.25 ), two[ 0 ][ 2 ], 1e-5 );
		assertEquals( Math.sqrt( ( 1.8 * k / 2 ) * ( 1.8 * k / 2 ) - 0.25 ), two[ 1 ][ 2 ], 1e-5 );

		// z better resolved than x (after downsampling xy): a larger sigma in z
		final double[][] half = DoGImgLib2.computeSigmas( 1.8f, new double[] { 1, 1, 0.5 }, 3 ).getA();
		assertEquals( Math.sqrt( 3.6 * 3.6 - 0.25 ), half[ 0 ][ 2 ], 1e-5 );

		// a target below the blur the image carries: nothing is applied in that dimension
		final double[][] eight = DoGImgLib2.computeSigmas( 1.8f, new double[] { 1, 1, 8 }, 3 ).getA();
		assertEquals( 0.0, eight[ 0 ][ 2 ], 0.0 );
		assertEquals( 0.0, eight[ 1 ][ 2 ], 0.0 );
		assertEquals( legacy.getA()[ 0 ][ 0 ], eight[ 0 ][ 0 ], 0.0 );

		assertThrows( IllegalArgumentException.class, () -> DoGImgLib2.computeSigmas( 1.8f, new double[] { 1, 1 }, 3 ) );
		assertThrows( IllegalArgumentException.class, () -> DoGImgLib2.computeSigmas( 1.8f, new double[] { 1, 1, 0 }, 3 ) );
	}

	/**
	 * Physically isotropic blobs on a grid with twice the z voxel size: with the anisotropy the DoG finds them with the
	 * same response and position (in physical units) as on an isotropic grid, pixel-isotropic Gaussians give another response
	 */
	@Test
	public void testAnisotropicGrid()
	{
		final double a = 2.0;
		final double s = 4.0;
		final double[][] isoCenters = new double[][] { { 40.3, 44.2, 43.6 }, { 90.7, 42.4, 44.3 } };
		final double[][] anisoCenters = new double[][] { { 40.3, 44.2, 43.6 / a }, { 90.7, 42.4, 44.3 / a } };

		// the same physical blobs; the image carries 0.5 px of blur per axis by definition
		final double sz = Math.sqrt( ( s * s - 0.25 ) / ( a * a ) + 0.25 );
		final double[][] isoStds = new double[][] { { s, s, s }, { s, s, s } };
		final double[][] anisoStds = new double[][] { { s, s, sz }, { s, s, sz } };

		final Img< FloatType > iso = TestDoGScaleSpace.blobs( new long[] { 128, 88, 88 }, isoCenters, isoStds );
		final Img< FloatType > aniso = TestDoGScaleSpace.blobs( new long[] { 128, 88, 44 }, anisoCenters, anisoStds );

		final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );
		final double sigma = 3.0, threshold = 0.01;

		final ArrayList< InterestPoint > isoPeaks = DoGImgLib2.computeDoG( Views.extendMirrorSingle( iso ), null, iso, sigma, null, threshold, 1, false, true, 0, 1, service );
		final ArrayList< InterestPoint > anisoPeaks = DoGImgLib2.computeDoG( Views.extendMirrorSingle( aniso ), null, aniso, sigma, new double[] { 1, 1, a }, threshold, 1, false, true, 0, 1, service );
		final ArrayList< InterestPoint > pixelIsoPeaks = DoGImgLib2.computeDoG( Views.extendMirrorSingle( aniso ), null, aniso, sigma, null, threshold, 1, false, true, 0, 1, service );

		service.shutdown();

		for ( final InterestPoint peak : isoPeaks )
			IOFunctions.println( "isotropic grid: " + Util.printCoordinates( peak.getL() ) + " response=" + peak.getResponse() + " sigma=" + peak.getSigma() );
		for ( final InterestPoint peak : anisoPeaks )
			IOFunctions.println( "anisotropic grid, anisotropy " + a + ": " + Util.printCoordinates( peak.getL() ) + " response=" + peak.getResponse() + " sigma=" + peak.getSigma() );
		for ( final InterestPoint peak : pixelIsoPeaks )
			IOFunctions.println( "anisotropic grid, pixel-isotropic: " + Util.printCoordinates( peak.getL() ) + " response=" + peak.getResponse() + " sigma=" + peak.getSigma() );

		assertEquals( 2, isoPeaks.size() );
		assertEquals( 2, anisoPeaks.size() );

		isoPeaks.sort( Comparator.comparingDouble( peak -> peak.getL()[ 0 ] ) );
		anisoPeaks.sort( Comparator.comparingDouble( peak -> peak.getL()[ 0 ] ) );
		pixelIsoPeaks.sort( Comparator.comparingDouble( peak -> peak.getL()[ 0 ] ) );

		for ( int i = 0; i < 2; ++i )
		{
			final InterestPoint pi = isoPeaks.get( i );
			final InterestPoint pa = anisoPeaks.get( i );

			assertEquals( pi.getResponse(), pa.getResponse(), 0.1 * Math.abs( pi.getResponse() ), "response of blob " + i );
			assertEquals( pi.getL()[ 0 ], pa.getL()[ 0 ], 0.2, "x of blob " + i );
			assertEquals( pi.getL()[ 1 ], pa.getL()[ 1 ], 0.2, "y of blob " + i );
			assertEquals( pi.getL()[ 2 ], pa.getL()[ 2 ] * a, 0.4, "z of blob " + i );

			// the stored sigma is the x sigma on both grids
			assertEquals( (float)sigma, pi.getSigma(), 0f );
			assertEquals( (float)sigma, pa.getSigma(), 0f );
		}

		// pixel-isotropic Gaussians see blobs that are squeezed in z and respond differently
		assertTrue( pixelIsoPeaks.size() >= 2, "pixel-isotropic peaks: " + pixelIsoPeaks.size() );

		for ( int i = 0; i < 2; ++i )
		{
			final InterestPoint pa = anisoPeaks.get( i );
			InterestPoint nearest = null;

			for ( final InterestPoint q : pixelIsoPeaks )
				if ( nearest == null || distance( q, pa ) < distance( nearest, pa ) )
					nearest = q;

			assertTrue( Math.abs( nearest.getResponse() - pa.getResponse() ) > 0.05 * Math.abs( pa.getResponse() ), "pixel-isotropic response " + nearest.getResponse() + " vs " + pa.getResponse() );
		}
	}

	protected static double distance( final InterestPoint a, final InterestPoint b )
	{
		double d2 = 0;

		for ( int d = 0; d < 3; ++d )
			d2 += ( a.getL()[ d ] - b.getL()[ d ] ) * ( a.getL()[ d ] - b.getL()[ d ] );

		return Math.sqrt( d2 );
	}

	/**
	 * The anisotropy of an opened image: the calibration and the mipmap transform, the user's estimate replaces the calibration ratio
	 */
	@Test
	public void testOpenedAnisotropy()
	{
		final SpimData2 spimData = SimulateUtil.setUp();
		final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( 0, 0 );
		final VoxelDimensions v = vd.getViewSetup().hasVoxelSize() ? vd.getViewSetup().getVoxelSize() : null;
		final double vx = v == null ? 1 : v.dimension( 0 ), vy = v == null ? 1 : v.dimension( 1 ), vz = v == null ? 1 : v.dimension( 2 );

		final AffineTransform3D identity = new AffineTransform3D();
		assertArrayEquals( new double[] { 1, vy / vx, vz / vx }, DownsampleTools.anisotropy( vd, identity, Double.NaN ), 1e-12 );
		assertArrayEquals( new double[] { 1, vy / vx, 3.0 }, DownsampleTools.anisotropy( vd, identity, 3.0 ), 1e-12 );

		// xy downsampled by 2, z not: a z voxel is half as large relative to x
		final AffineTransform3D t = new AffineTransform3D();
		t.set( 2, 0, 0 );
		t.set( 2, 1, 1 );
		t.set( 1, 2, 2 );
		assertArrayEquals( new double[] { 1, vy / vx, 1.5 }, DownsampleTools.anisotropy( vd, t, 3.0 ), 1e-12 );
		assertArrayEquals( new double[] { 1, vy / vx, ( vz / vx ) / 2 }, DownsampleTools.anisotropy( vd, t, Double.NaN ), 1e-12 );
	}

	/**
	 * The driver: an anisotropy equal to the calibration ratio at full resolution changes nothing, another one changes the
	 * detections, the attributes are stored either way (the sigma is the x sigma)
	 */
	@Test
	public void testDriver()
	{
		final SpimData2 spimData = SimulateUtil.setUp();
		final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( 0, 0 );
		final double calibration = DifferenceOfGUI.calibrationAnisotropy( vd );

		final DoGParameters dog = new DoGParameters( Arrays.asList( vd ), spimData.getSequenceDescription().getImgLoader(), 1.8, 0.008 );
		dog.localization = 1;
		dog.downsampleXY = 1;
		dog.downsampleZ = 1;
		dog.minIntensity = 0;
		dog.maxIntensity = 1137;

		assertTrue( Double.isNaN( dog.anisotropyZ ) );
		final List< InterestPoint > none = DoG.findInterestPoints( dog ).get( vd );

		dog.anisotropyZ = calibration;
		final List< InterestPoint > same = DoG.findInterestPoints( dog ).get( vd );

		dog.anisotropyZ = 2 * calibration;
		final List< InterestPoint > other = DoG.findInterestPoints( dog ).get( vd );

		IOFunctions.println( "DoG on the simulated view 0 (calibration z/x = " + calibration + "): " + none.size() + " points without anisotropy, " + same.size() + " with the calibration, " + other.size() + " with twice the calibration" );

		assertTrue( none.size() > 50 );
		assertEquals( none.size(), same.size() );

		for ( int i = 0; i < none.size(); ++i )
		{
			assertArrayEquals( none.get( i ).getL(), same.get( i ).getL(), 0.0 );
			assertEquals( none.get( i ).getResponse(), same.get( i ).getResponse(), 0f );
		}

		boolean identical = none.size() == other.size();

		for ( int i = 0; identical && i < none.size(); ++i )
			identical = Arrays.equals( none.get( i ).getL(), other.get( i ).getL() );

		assertFalse( identical, "twice the anisotropy changes the detections" );

		for ( final InterestPoint ip : other )
		{
			assertFalse( Float.isNaN( ip.getResponse() ) );
			assertFalse( Float.isNaN( ip.getIntensity() ) );
			assertEquals( 1.8f, ip.getSigma(), 0f );
		}
	}

	/**
	 * The dialog (headless): the parameter string and the recorded parameters carry the resolution and the anisotropy, the
	 * detection through the dialog is the driver's, and the Spark translation warns about the anisotropy but renders the command
	 */
	@Test
	public void testGUI()
	{
		final SpimData2 spimData = SimulateUtil.setUp();
		final ArrayList< ViewId > views = new ArrayList<>( spimData.getSequenceDescription().getViewDescriptions().keySet() );
		Collections.sort( views );
		final TimePoint tp = spimData.getSequenceDescription().getTimePoints().getTimePointsOrdered().get( 0 );

		final DifferenceOfGaussianGUI gui = new DifferenceOfGaussianGUI( spimData, views )
		{{
			localization = 1;
			downsampling = new long[] { 2, 2, 1 };
			anisotropyZ = 1.5;
			minIntensity = 0.0;
			maxIntensity = 1137.0;
			sigma = 1.8;
			threshold = 0.008;
			findMin = false;
			findMax = true;
		}};

		assertEquals( "DOG s=1.8 t=0.008 min=false max=true downsampleX=2 downsampleY=2 downsampleZ=1 anisotropy=1.5 minIntensity=0.0 maxIntensity=1137.0", gui.getParameters() );

		final Map< String, String > d = gui.describeParameters();
		assertEquals( "2", d.get( "downsampleXY" ) );
		assertEquals( "1", d.get( "downsampleZ" ) );
		assertEquals( "1.5", d.get( "anisotropy" ) );
		assertNull( d.get( "downsampleX" ) );
		assertNull( d.get( "detectionMethod" ) );

		final HashMap< ViewId, List< InterestPoint > > viaGUI = gui.findInterestPoints( tp );

		final DoGParameters dog = new DoGParameters();
		dog.imgloader = spimData.getSequenceDescription().getImgLoader();
		dog.toProcess = SpimData2.getAllViewIdsForTimePointSorted( spimData, views, tp );
		dog.localization = 1;
		dog.downsampleXY = 2;
		dog.downsampleZ = 1;
		dog.anisotropyZ = 1.5;
		dog.minIntensity = 0.0;
		dog.maxIntensity = 1137.0;
		dog.sigma = 1.8;
		dog.threshold = 0.008;
		dog.findMin = false;
		dog.findMax = true;

		final HashMap< ViewId, List< InterestPoint > > viaDriver = DoG.findInterestPoints( dog );

		assertEquals( views.size(), viaGUI.size() );
		assertEquals( views.size(), viaDriver.size() );

		for ( final ViewId view : views )
		{
			final List< InterestPoint > g = viaGUI.get( view ), r = viaDriver.get( view );
			assertTrue( g.size() > 20, "view " + view + ": " + g.size() + " points" );
			assertEquals( r.size(), g.size() );

			for ( int i = 0; i < g.size(); ++i )
				assertArrayEquals( r.get( i ).getL(), g.get( i ).getL(), 0.0 );
		}

		// the Spark translation
		final Map< String, String > params = new java.util.LinkedHashMap<>( d );
		params.put( "label", "beads" );
		final ActionRecord record = new ActionRecord( "detect-interestpoints", System.currentTimeMillis(), DifferenceOfGaussianGUI.class.getName(), params, views, "interestpoints:beads" );
		final List< String > lines = ActionToSparkCli.render( record, "dataset.xml" );

		for ( final String line : lines )
			IOFunctions.println( line );

		assertTrue( lines.stream().noneMatch( l -> l.startsWith( "#" ) && l.contains( "anisotropy" ) ), "no warning: the anisotropy is an option of detect-interestpoints" );
		assertTrue( lines.stream().anyMatch( l -> !l.startsWith( "#" ) && l.contains( "-dsxy 2" ) && l.contains( "-dsz 1" ) && l.contains( "--anisotropy 1.5" ) && !l.contains( "--method" ) ), "the command is rendered with the anisotropy" );

		// different factors for x and y: rendered with the x factor and a warning
		params.remove( "downsampleXY" );
		params.put( "downsampleX", "4" );
		params.put( "downsampleY", "2" );
		final List< String > linesXY = ActionToSparkCli.render( new ActionRecord( "detect-interestpoints", System.currentTimeMillis(), DifferenceOfGaussianGUI.class.getName(), params, views, "interestpoints:beads" ), "dataset.xml" );
		assertTrue( linesXY.stream().anyMatch( l -> l.startsWith( "#" ) && l.contains( "x by 4 and y by 2" ) ) );
		assertTrue( linesXY.stream().anyMatch( l -> !l.startsWith( "#" ) && l.contains( "-dsxy 4" ) ) );
	}

	/**
	 * The estimate of the anisotropy from the calibration: the ratio times 0.8 (Stephan, 2026-10-20), shared by both dialogs
	 */
	@Test
	public void testEstimate()
	{
		assertEquals( 0.8, DifferenceOfGUI.anisotropyEstimateFactor, 0.0 );

		final FinalVoxelDimensions ds1 = new FinalVoxelDimensions( "um", 1.1, 1.1, 3.512 );
		assertEquals( 3.512 / 1.1, DifferenceOfGUI.calibrationAnisotropy( ds1 ), 1e-12 );
		assertEquals( 0.8 * 3.512 / 1.1, DifferenceOfGUI.estimateAnisotropy( ds1 ), 1e-12 );
		assertEquals( 0.8 * 3.512 / 1.1, DifferenceOfGUI.estimateAnisotropy( 3.512 / 1.1 ), 1e-12 );

		assertEquals( 1.0, DifferenceOfGUI.calibrationAnisotropy( (VoxelDimensions)null ), 0.0 );
		assertEquals( 0.8, DifferenceOfGUI.estimateAnisotropy( (VoxelDimensions)null ), 0.0 );
	}
}
