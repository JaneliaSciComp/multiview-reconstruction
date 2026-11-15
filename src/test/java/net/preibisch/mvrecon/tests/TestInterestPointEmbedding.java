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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ij.Macro;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.VoxelDimensions;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.fiji.plugin.Embed_Interest_Points;
import net.preibisch.mvrecon.fiji.plugin.Interest_Point_Registration;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.pairwise.EmbeddingsGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.pairwise.RGLDMGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.parameters.BasicRegistrationParameters;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionRecord;
import net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint.InterestPointTableModel;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays.Attributes;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays.Vectors;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.VirtualFilteredInterestPoints;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.interestpointembedding.DetectionScale;
import net.preibisch.mvrecon.process.interestpointembedding.EmbeddingModel;
import net.preibisch.mvrecon.process.interestpointembedding.EmbeddingModel.Contract;
import net.preibisch.mvrecon.process.interestpointembedding.InterestPointEmbedding;
import net.preibisch.mvrecon.process.interestpointembedding.InterestPointEmbedding.Result;
import net.preibisch.mvrecon.process.interestpointembedding.PatchExtractor;
import net.preibisch.mvrecon.process.interestpointembedding.PatchExtractor.Pyramid;

/**
 * The embedding of interest points in Java: the detection scale of a label's parameters string, the sampling of the
 * cubes (pcmatch's rules on a synthetic image), and the whole chain on the simulated beads: the points of a label embedded
 * block by block (sequentially and on a pool) and stored as the per-point array the matcher reads back by the count rule,
 * the replacement of an array of another model, several views on one pool, a filtered label through its base, and the plugin
 * with a tiny ONNX model.
 */
public class TestInterestPointEmbedding
{
	@TempDir
	Path tempDir;

	static final String LABEL = "beads";

	@Test
	public void detectionScale()
	{
		final FinalVoxelDimensions ds1 = new FinalVoxelDimensions( "um", 1.1, 1.1, 3.512 );

		// master's DoG: downsampleXYIndex 0 = "a bit less than the z resolution" from the XML's voxel size (ds1: 2)
		DetectionScale s = DetectionScale.of( LABEL, "DOG s=1.7999999523162842 t=0.00800000037997961 min=false max=true imageSigmaX=0.5 downsampleXYIndex=0 downsampleZ=1 minIntensity=16.0 maxIntensity=255.0", ds1 );
		assertEquals( 2, s.downsampleXY() );
		assertEquals( 3.6, s.sigma(), 1e-6 );
		assertTrue( s.fromCalibration() );
		// -1 = "a bit more": 4
		assertEquals( 4, DetectionScale.of( LABEL, "DOG s=1.8 t=0.008 downsampleXYIndex=-1 downsampleZ=1", ds1 ).downsampleXY() );
		// a factor as index
		s = DetectionScale.of( LABEL, "DOG s=1.8 t=0.004 min=false max=true downsampleXYIndex=2 downsampleZ=1", ds1 );
		assertEquals( 2, s.downsampleXY() );
		assertFalse( s.fromCalibration() );
		// the corrected calibration of ds1 (0.45 0.45 2.0) turns the derived factor into 4: the string has to be pinned
		assertEquals( 4, DetectionScale.of( LABEL, "DOG s=1.8 t=0.008 downsampleXYIndex=0 downsampleZ=1", new FinalVoxelDimensions( "um", 0.45, 0.45, 2.0 ) ).downsampleXY() );
		assertEquals( 2, DetectionScale.of( LABEL, "DOG s=1.8 t=0.008 downsampleXYIndex=2 downsampleZ=1", new FinalVoxelDimensions( "um", 0.45, 0.45, 2.0 ) ).downsampleXY() );

		// BigStitcher-Spark's scale space (the embryo)
		s = DetectionScale.of( LABEL, "DOG-SS (Spark) s=1.6 steps=4 octaves=-1 finestLevel=true t=4.4E-4 overlappingOnly=true min=false max=true downsampleXY=8 downsampleZ=2 anisotropy=4.0 minIntensity=0.0 maxIntensity=2600.0", ds1 );
		assertEquals( 8, s.downsampleXY() );
		assertEquals( 12.8, s.sigma(), 1e-6 );

		// the GUI since MVR 10: per-axis factors
		s = DetectionScale.of( LABEL, "DOG s=1.5 t=0.01 min=false max=true downsampleX=4 downsampleY=4 downsampleZ=2 anisotropy=1.0", ds1 );
		assertEquals( 4, s.downsampleXY() );
		assertEquals( 6.0, s.sigma(), 1e-6 );

		// pcmatch's detector: the sigma is already in full-resolution pixels
		s = DetectionScale.of( LABEL, "LEARNED model=x sigma=2.5 downsampleXY=2", ds1 );
		assertEquals( 2.5, s.sigma(), 1e-9 );
		assertEquals( 2, s.downsampleXY() );

		// no detection, no scale
		assertThrows( IllegalArgumentException.class, () -> DetectionScale.of( "beads_r0.008", "filtered from 'beads': |response| >= 0.008", ds1 ) );
		assertThrows( IllegalArgumentException.class, () -> DetectionScale.of( LABEL, null, ds1 ) );
		assertThrows( IllegalArgumentException.class, () -> DetectionScale.of( LABEL, "DOG t=0.008 downsampleXY=2", ds1 ) );
	}

	static Contract contract( final double[] extents, final int patch, final int outputs, final String fingerprint, final String readFloor ) throws Exception
	{
		return EmbeddingModel.contractOf( TestOnnxModels.sidecar( "test.onnx", extents, patch, outputs, fingerprint, readFloor ), "test.onnx", "test" );
	}

	@Test
	public void sigmaLevelsAndSpacing() throws Exception
	{
		// ds1's pyramid and detection: the five extents of the real model read levels 1, 1, 1, 2, 2
		final Pyramid pyramid = new Pyramid( new double[][] { { 1, 1, 1 }, { 2, 2, 1 }, { 4, 4, 2 } }, level -> null );
		final Contract contract = contract( new double[] { 5, 8, 13, 20, 30 }, 16, 64, "fp", "detection" );
		final PatchExtractor floored = new PatchExtractor( pyramid, new double[] { 0.45, 0.45, 2.0 }, new DetectionScale( 3.6, 2 ), contract, false );
		assertEquals( 1, floored.getFloorLevel() );
		final int[] levels = new int[ 5 ];
		for ( int c = 0; c < 5; ++c )
			levels[ c ] = floored.levelOf( floored.spacing( contract.extents()[ c ], 3.6 ) );
		assertArrayEquals( new int[] { 1, 1, 1, 2, 2 }, levels );
		assertEquals( 1.125, floored.spacing( 5, 3.6 ), 1e-9 );
		assertEquals( 6.75, floored.spacing( 30, 3.6 ), 1e-9 );

		// read floor "full": the finest level that fits
		final PatchExtractor full = new PatchExtractor( pyramid, new double[] { 0.45, 0.45, 2.0 }, new DetectionScale( 3.6, 2 ), contract( new double[] { 5, 8, 13, 20, 30 }, 16, 64, "fp", "full" ), false );
		assertEquals( 0, full.getFloorLevel() );
		assertEquals( 0, full.levelOf( 1.125 ) );
		assertEquals( 0, full.levelOf( 1.8 ) );
		assertEquals( 1, full.levelOf( 2.925 ) );
		assertEquals( 2, full.levelOf( 4.5 ) );

		// a detection coarser than every level: the coarsest
		assertEquals( 2, new PatchExtractor( pyramid, new double[] { 1, 1, 1 }, new DetectionScale( 16, 8 ), contract, false ).getFloorLevel() );

		// the sigma of a point: the label's without the attribute, max( own, label's ) quantised to 2^(1/12) with it
		final InterestPoint small = new InterestPoint( 0, new double[] { 1, 1, 1 }, 0.1f, 2.0f, 100f );
		final InterestPoint large = new InterestPoint( 1, new double[] { 1, 1, 1 }, 0.1f, 20.0f, 100f );
		final InterestPoint none = new InterestPoint( 2, new double[] { 1, 1, 1 }, 0.1f, Float.NaN, 100f );
		assertEquals( 3.6, floored.sigmaOf( small ), 1e-9 );
		assertEquals( 3.6, floored.sigmaOf( large ), 1e-9 );
		final PatchExtractor perPoint = new PatchExtractor( pyramid, new double[] { 0.45, 0.45, 2.0 }, new DetectionScale( 3.6, 2 ), contract, true );
		assertEquals( PatchExtractor.quantise( 3.6 ), perPoint.sigmaOf( small ), 1e-9 );
		assertEquals( PatchExtractor.quantise( 20 ), perPoint.sigmaOf( large ), 1e-9 );
		assertEquals( PatchExtractor.quantise( 3.6 ), perPoint.sigmaOf( none ), 1e-9 );
		assertEquals( Math.pow( 2, 22 / 12.0 ), PatchExtractor.quantise( 3.6 ), 1e-9 ); // 12 log2 3.6 = 22.17 -> 22
		assertEquals( 16.0, PatchExtractor.quantise( 16 ), 1e-9 );
	}

	/**
	 * Views with unequal pixel sizes in x and y (either one the coarser), and detections that downsampled x and y
	 * differently: the detection sigma is in full-resolution pixels of the finer lateral axis, the pyramid level and the
	 * read floor are chosen per lateral axis, and the cubes are isotropic in physical units
	 */
	@Test
	public void unequalPixelSizesInXAndY() throws Exception
	{
		final String tail = " anisotropy=1.0 minIntensity=0.0 maxIntensity=1137.0 localization=QUADRATIC groupTiles=false groupIllums=false overlappingOnly=false thresholdType=fixed";
		final VoxelDimensions coarseX = new FinalVoxelDimensions( "um", 2, 1, 4 ), coarseY = new FinalVoxelDimensions( "um", 1, 2, 4 ), square = new FinalVoxelDimensions( "um", 1, 1, 4 );

		// the same downsampling in x and y: the detection ran with s in pixels of the finer axis, downsampled by 2
		for ( final VoxelDimensions v : new VoxelDimensions[] { coarseX, coarseY, square, null } )
		{
			final DetectionScale scale = DetectionScale.of( "beads", "DOG s=1.5 t=0.008 min=false max=true downsampleX=2 downsampleY=2 downsampleZ=1" + tail, v );
			assertEquals( 3.0, scale.sigma(), 0.0 );
			assertEquals( 2, scale.downsampleX() );
			assertEquals( 2, scale.downsampleY() );
			assertEquals( 2, scale.downsampleXY() );
		}

		// only the finer axis downsampled by 2: square pixels of twice its size, either way
		DetectionScale scale = DetectionScale.of( "beads", "DOG s=1.1 t=0.008 min=false max=true downsampleX=1 downsampleY=2 downsampleZ=1" + tail, coarseX );
		assertEquals( 2.2, scale.sigma(), 0.0 );
		assertEquals( 1, scale.downsampleX() );
		assertEquals( 2, scale.downsampleY() );
		assertEquals( 1, scale.downsampleXY() );

		scale = DetectionScale.of( "beads", "DOG-SS s=1.6 steps=3 octaves=-1 finestLevel=true t=0.01 min=false max=true downsampleX=2 downsampleY=1 downsampleZ=1" + tail, coarseY );
		assertEquals( 3.2, scale.sigma(), 0.0 );
		assertEquals( 2, scale.downsampleX() );
		assertEquals( 1, scale.downsampleY() );

		// square pixels, only x downsampled: s is in pixels of y, which are full-resolution pixels
		scale = DetectionScale.of( "beads", "DOG s=1.8 t=0.008 min=false max=true downsampleX=2 downsampleY=1 downsampleZ=1" + tail, square );
		assertEquals( 1.8, scale.sigma(), 0.0 );

		// y pixels twice as large as x pixels, a pyramid that first halves x: a level is used once it is no coarser than the
		// sample spacing in x AND in y (the spacing d is in pixels of x, d / 2 in pixels of y)
		final Pyramid pyramid = new Pyramid( new double[][] { { 1, 1, 1 }, { 2, 1, 1 }, { 2, 2, 1 }, { 4, 2, 1 } }, level -> null );
		final Contract full = contract( new double[] { 5 }, 16, 64, "fp", "full" );
		final PatchExtractor yCoarse = new PatchExtractor( pyramid, new double[] { 1, 2, 2 }, new DetectionScale( 2, 1 ), full, false );
		assertEquals( 0, yCoarse.levelOf( 1.5 ) );
		assertEquals( 1, yCoarse.levelOf( 2.0 ) ); // (2, 1, 1): square pixels
		assertEquals( 1, yCoarse.levelOf( 3.9 ) );
		assertEquals( 3, yCoarse.levelOf( 4.0 ) ); // (4, 2, 1)
		assertEquals( 3, yCoarse.levelOf( 100 ) );

		// x pixels twice as large: d is in pixels of y, x is never read coarser than d / 2
		final PatchExtractor xCoarse = new PatchExtractor( pyramid, new double[] { 2, 1, 2 }, new DetectionScale( 2, 1 ), full, false );
		assertEquals( 0, xCoarse.levelOf( 3.9 ) );
		assertEquals( 2, xCoarse.levelOf( 4.0 ) ); // (2, 2, 1); (2, 1, 1) is as good for x but finer than needed in y
		assertEquals( 3, xCoarse.levelOf( 8.0 ) );

		// square pixels: a level with different factors in x and y is used once both fit; (2, 1, 1) never is, (2, 2, 1) fits whenever it does
		final PatchExtractor squarePixels = new PatchExtractor( pyramid, new double[] { 1, 1, 2 }, new DetectionScale( 2, 1 ), full, false );
		assertEquals( 0, squarePixels.levelOf( 1.9 ) );
		assertEquals( 2, squarePixels.levelOf( 2.0 ) );
		assertEquals( 2, squarePixels.levelOf( 3.9 ) );
		assertEquals( 3, squarePixels.levelOf( 4.0 ) );

		// the read floor: the first level that is no finer than the detection in x and in y
		final Contract floored = contract( new double[] { 5 }, 16, 64, "fp", "detection" );
		assertEquals( 1, new PatchExtractor( pyramid, new double[] { 1, 2, 2 }, new DetectionScale( 4, 2, 1, false ), floored, false ).getFloorLevel() );
		assertEquals( 2, new PatchExtractor( pyramid, new double[] { 1, 2, 2 }, new DetectionScale( 4, 2, 2, false ), floored, false ).getFloorLevel() );
		assertEquals( 2, new PatchExtractor( pyramid, new double[] { 2, 1, 2 }, new DetectionScale( 4, 1, 2, false ), floored, false ).getFloorLevel() );
		assertEquals( 0, new PatchExtractor( pyramid, new double[] { 2, 1, 2 }, new DetectionScale( 4, 1, 1, false ), floored, false ).getFloorLevel() );

		// the cubes: a linear image, value = x + 10 y + 100 z, so every trilinear sample inside is exact. Extent 4 x sigma 2 is a
		// cube of 8 pixels of the finer lateral axis with 4 samples per edge: 2 of its pixels apart, 1 pixel in the coarser axis and in z
		final long[] size = { 20, 20, 12 };
		final ArrayImg< FloatType, FloatArray > img = ArrayImgs.floats( size );
		final net.imglib2.Cursor< FloatType > cursor = img.localizingCursor();
		while ( cursor.hasNext() )
		{
			cursor.fwd();
			cursor.get().set( cursor.getIntPosition( 0 ) + 10 * cursor.getIntPosition( 1 ) + 100 * cursor.getIntPosition( 2 ) );
		}

		final Contract contract = contract( new double[] { 4 }, 4, 2, "fp", "detection" );

		for ( final double[] voxelSize : new double[][] { { 2, 1, 2 }, { 1, 2, 2 } } )
		{
			final double stepX = voxelSize[ 0 ] == 2 ? 1 : 2, stepY = voxelSize[ 1 ] == 2 ? 1 : 2;
			final PatchExtractor extractor = new PatchExtractor( new Pyramid( new double[][] { { 1, 1, 1 } }, level -> img ), voxelSize, new DetectionScale( 2, 1 ), contract, false );

			final float[] patches = new float[ contract.patchSize() ];
			extractor.sampler().extract( new InterestPoint( 0, new double[] { 10.25, 9.5, 6.0 } ), patches, 0 );

			int i = 0;
			for ( int iz = 0; iz < 4; ++iz )
				for ( int iy = 0; iy < 4; ++iy )
					for ( int ix = 0; ix < 4; ++ix )
					{
						final double x = 10.25 + ( ix - 1.5 ) * stepX, y = 9.5 + ( iy - 1.5 ) * stepY, z = 6.0 + ( iz - 1.5 ) * 1;
						assertEquals( x + 10 * y + 100 * z, patches[ i++ ], 1e-3, "voxel size " + Arrays.toString( voxelSize ) + ", sample " + ix + "," + iy + "," + iz );
					}
		}
	}

	@Test
	public void samplesCubes() throws Exception
	{
		// a linear image: value = x + 10 y + 100 z, so every trilinear sample inside is exact
		final long[] size = { 20, 20, 12 };
		final ArrayImg< FloatType, FloatArray > img = ArrayImgs.floats( size );
		final net.imglib2.Cursor< FloatType > cursor = img.localizingCursor();
		while ( cursor.hasNext() )
		{
			cursor.fwd();
			cursor.get().set( cursor.getIntPosition( 0 ) + 10 * cursor.getIntPosition( 1 ) + 100 * cursor.getIntPosition( 2 ) );
		}
		final Pyramid pyramid = new Pyramid( new double[][] { { 1, 1, 1 } }, level -> img );

		// extent 4 x sigma 2 = cube of 8 px, 4 samples per edge: spacing 2 px in x and y, 1 voxel in z (voxel size 1, 1, 2)
		final Contract contract = contract( new double[] { 4 }, 4, 2, "fp", "detection" );
		final PatchExtractor extractor = new PatchExtractor( pyramid, new double[] { 1, 1, 2 }, new DetectionScale( 2, 1 ), contract, false );
		final PatchExtractor.Sampler sampler = extractor.sampler();

		final float[] patches = new float[ 2 * contract.patchSize() ];
		sampler.extract( new InterestPoint( 0, new double[] { 10.25, 9.5, 6.0 } ), patches, 0 );
		int i = 0;
		for ( int iz = 0; iz < 4; ++iz )
			for ( int iy = 0; iy < 4; ++iy )
				for ( int ix = 0; ix < 4; ++ix )
				{
					final double x = 10.25 + ( ix - 1.5 ) * 2, y = 9.5 + ( iy - 1.5 ) * 2, z = 6.0 + ( iz - 1.5 ) * 1;
					assertEquals( x + 10 * y + 100 * z, patches[ i++ ], 1e-3, "sample " + ix + "," + iy + "," + iz );
				}

		// near the corner: samples outside [0, size - 1] are NaN, the ones inside exact
		sampler.extract( new InterestPoint( 1, new double[] { 2.0, 18.0, 0.5 } ), patches, contract.patchSize() );
		i = contract.patchSize();
		int nan = 0;
		for ( int iz = 0; iz < 4; ++iz )
			for ( int iy = 0; iy < 4; ++iy )
				for ( int ix = 0; ix < 4; ++ix )
				{
					final double x = 2.0 + ( ix - 1.5 ) * 2, y = 18.0 + ( iy - 1.5 ) * 2, z = 0.5 + ( iz - 1.5 ) * 1;
					final float v = patches[ i++ ];
					if ( x < 0 || y > 19 || z < 0 )
					{
						assertTrue( Float.isNaN( v ), "outside at " + x + "," + y + "," + z );
						++nan;
					}
					else
						assertEquals( x + 10 * y + 100 * z, v, 1e-3 );
				}
		assertTrue( nan > 0 && nan < 64 );

		// the same cube through the orchestration, in blocks on a pool and sequentially
		final List< InterestPoint > points = List.of( new InterestPoint( 0, new double[] { 10.25, 9.5, 6.0 } ), new InterestPoint( 1, new double[] { 2.0, 18.0, 0.5 } ), new InterestPoint( 2, new double[] { 5, 5, 5 } ) );
		final MeanModel model = new MeanModel( contract );
		final float[] sequential = InterestPointEmbedding.embed( points, extractor, model, 2, null );
		final ExecutorService pool = Executors.newFixedThreadPool( 3 );
		final float[] parallel = InterestPointEmbedding.embed( points, extractor, model, 1, pool );
		pool.shutdown();
		assertArrayEquals( sequential, parallel );
		assertEquals( 6, sequential.length );
	}

	/** A model for the tests: the vector of a point is (mean of the finite samples per channel, 1) normalised, so it depends on the cubes and is a unit vector. */
	static class MeanModel implements EmbeddingModel
	{
		final Contract contract;

		MeanModel( final Contract contract ) { this.contract = contract; }

		@Override
		public Contract contract() { return contract; }

		@Override
		public String backend() { return "mean of the cubes (test)"; }

		@Override
		public float[] embed( final float[] patches, final int n )
		{
			final int C = contract.channels(), V = contract.patch() * contract.patch() * contract.patch(), D = contract.outputs();
			assertEquals( C + 1, D );
			final float[] out = new float[ n * D ];
			for ( int i = 0; i < n; ++i )
			{
				double norm = 1;
				for ( int c = 0; c < C; ++c )
				{
					double sum = 0;
					int count = 0;
					for ( int v = 0; v < V; ++v )
					{
						final float x = patches[ ( i * C + c ) * V + v ];
						if ( Float.isFinite( x ) )
						{
							sum += x;
							++count;
						}
					}
					out[ i * D + c ] = count == 0 ? 0 : (float) ( sum / count / 1000.0 );
					norm += out[ i * D + c ] * out[ i * D + c ];
				}
				out[ i * D + C ] = 1;
				for ( int d = 0; d < D; ++d )
					out[ i * D + d ] /= (float) Math.sqrt( norm );
			}
			return out;
		}
	}

	/** the registration's check before its dialog: every selected view needs one row per point, otherwise prepareQuery is false and no dialog is shown */
	@Test
	public void registrationNeedsEmbeddingsInEveryView() throws Exception
	{
		final File xml = tempDir.resolve( "dataset.xml" ).toFile();
		final SpimData2 data = beads( xml );
		final List< ViewId > views = viewsWith( data, LABEL );
		assertTrue( views.size() >= 2 );
		final Path onnx = tempDir.resolve( "tiny.onnx" );
		TestOnnxModels.write( onnx, new double[] { 3, 6 }, 4, 3, 5, 21 );

		final Map< ViewId, HashMap< String, Double > > labelMap = new HashMap<>();
		for ( final ViewId v : views )
			labelMap.put( v, new HashMap<>( Map.of( LABEL, 1.0 ) ) );
		final EmbeddingsGUI gui = new EmbeddingsGUI( data );

		// the registration dialog offers the matcher at all (the entry was lost once, with a merge that kept the other side's file)
		assertTrue( Interest_Point_Registration.staticPairwiseAlgorithms.stream().anyMatch( a -> a instanceof EmbeddingsGUI ),
				"'" + gui.getDescription() + "' is not among the algorithms of the registration dialog" );

		// no arrays at all: not the default algorithm, and no dialog
		assertFalse( EmbeddingsGUI.hasEmbeddings( data, views ) );
		assertFalse( gui.prepareQuery( data, views, labelMap ) );

		// one view embedded: the default algorithm sees embeddings, registering all views stops, registering that view alone goes on
		final List< ViewId > first = views.subList( 0, 1 );
		assertTrue( Embed_Interest_Points.embed( data, first, LABEL, onnx, "embedding", 50 ) );
		assertTrue( EmbeddingsGUI.hasEmbeddings( data, views ) );
		assertFalse( gui.prepareQuery( data, views, labelMap ) );
		assertTrue( gui.prepareQuery( data, first, labelMap ) );

		// every view embedded
		assertTrue( Embed_Interest_Points.embed( data, views, LABEL, onnx, "embedding", 50 ) );
		assertTrue( gui.prepareQuery( data, views, labelMap ) );
	}

	static final String MULTIPLE = "Select multiple interestpoints [extra dialog]";

	/** the beads with two arrays of the label, and a filtered label (which has its base's arrays) in the first view only */
	static String filteredLabelInFirstView( final SpimData2 data, final List< ViewId > views )
	{
		final String filtered = LABEL + "_i500";
		data.getViewInterestPoints().getViewInterestPointLists( views.get( 0 ) ).addInterestPointList( filtered,
				VirtualFilteredInterestPoints.create( data.getBasePathURI(), views.get( 0 ), filtered, LABEL, InterestPointFilter.fromString( "intensity=500.." ) ) );
		return filtered;
	}

	/**
	 * The label choice of the registration dialog: " [N embeddings]" after the labels with stored embeddings (a filtered
	 * label has its base's), a label with embeddings as the default when the dialog suggests the embeddings matcher, and
	 * the entries a macro gets: the labels as they are, or as the macro names them
	 */
	@Test
	public void registrationDialogShowsEmbeddings() throws Exception
	{
		final File xml = tempDir.resolve( "dataset.xml" ).toFile();
		final SpimData2 data = beads( xml );
		final List< ViewId > views = viewsWith( data, LABEL );
		assertTrue( views.size() >= 2 );
		final Path onnx = tempDir.resolve( "tiny.onnx" );
		TestOnnxModels.write( onnx, new double[] { 3, 6 }, 4, 3, 5, 21 );

		final String filtered = filteredLabelInFirstView( data, views );
		final String warning = InterestPointTools.warningLabel + "1/" + views.size() + " Views!)";
		final String[] labels = { LABEL, filtered + warning, MULTIPLE };
		assertArrayEquals( new String[] { LABEL, filtered + warning }, InterestPointTools.getAllInterestPointLabels( data, views ) );

		// no embeddings: the choice as it always was, the first label that all views have
		int[] n = Interest_Point_Registration.numEmbeddings( data, views, labels );
		assertArrayEquals( new int[] { 0, 0, 0 }, n );
		assertArrayEquals( labels, Interest_Point_Registration.withEmbeddings( labels, n ) );
		assertEquals( 0, Interest_Point_Registration.labelDefault( labels, n, -1, false, true ) );
		assertEquals( Map.of( LABEL, Set.of(), filtered, Set.of() ), InterestPointArrays.namesByLabel( data.getViewInterestPoints(), views ) );

		// one array (in one view: an array counts, whether or not it covers the views), then a second one
		assertTrue( Embed_Interest_Points.embed( data, views.subList( 1, 2 ), LABEL, onnx, "translation-invariant", 50 ) );
		n = Interest_Point_Registration.numEmbeddings( data, views, labels );
		assertArrayEquals( new int[] { 1, 1, 0 }, n );
		assertArrayEquals( new String[] { LABEL + " [1 embedding]", filtered + " [1 embedding]" + warning, MULTIPLE }, Interest_Point_Registration.withEmbeddings( labels, n ) );

		assertTrue( Embed_Interest_Points.embed( data, views, LABEL, onnx, "rotation-invariant", 50 ) );
		n = Interest_Point_Registration.numEmbeddings( data, views, labels );
		assertArrayEquals( new int[] { 2, 2, 0 }, n );
		final String[] shown = Interest_Point_Registration.withEmbeddings( labels, n );
		assertArrayEquals( new String[] { LABEL + " [2 embeddings]", filtered + " [2 embeddings]" + warning, MULTIPLE }, shown );

		final SortedSet< String > both = new TreeSet<>( List.of( "rotation-invariant", "translation-invariant" ) );
		assertEquals( Map.of( LABEL, both, filtered, both ), InterestPointArrays.namesByLabel( data.getViewInterestPoints(), views ) );
		// the views without the filtered label: only the label they have
		assertEquals( Map.of( LABEL, both ), InterestPointArrays.namesByLabel( data.getViewInterestPoints(), views.subList( 1, views.size() ) ) );
		assertTrue( EmbeddingsGUI.hasEmbeddings( data, views ) );

		// the label an entry names, whatever the dialog appended
		assertEquals( LABEL, Interest_Point_Registration.labelOf( shown[ 0 ] ) );
		assertEquals( filtered, Interest_Point_Registration.labelOf( shown[ 1 ] ) );
		assertEquals( filtered, Interest_Point_Registration.labelOf( labels[ 1 ] ) );
		assertEquals( MULTIPLE, Interest_Point_Registration.labelOf( MULTIPLE ) );
		assertEquals( "spots [raw]", Interest_Point_Registration.labelOf( "spots [raw]" ) );

		// the label the dialog starts with: a (complete), b (2 of 4 views), c (complete)
		final String[] abc = { "a", "b" + InterestPointTools.warningLabel + "2/4 Views!)", "c", MULTIPLE };
		final int[] none = { 0, 0, 0, 0 }, bc = { 0, 1, 2, 0 }, b = { 0, 3, 0, 0 };
		assertEquals( 0, Interest_Point_Registration.labelDefault( abc, none, -1, false, true ) );  // RGLDM: the first complete label
		assertEquals( 2, Interest_Point_Registration.labelDefault( abc, bc, -1, true, true ) );     // embeddings: the first complete label that has them
		assertEquals( 1, Interest_Point_Registration.labelDefault( abc, b, -1, true, true ) );      // or else an incomplete one
		assertEquals( 0, Interest_Point_Registration.labelDefault( abc, bc, -1, false, false ) );   // another algorithm chosen last: as before
		assertEquals( 2, Interest_Point_Registration.labelDefault( abc, bc, 0, true, true ) );      // a label chosen elsewhere gives way to the suggestion
		assertEquals( 1, Interest_Point_Registration.labelDefault( abc, bc, 1, true, true ) );      // unless it has embeddings
		assertEquals( 0, Interest_Point_Registration.labelDefault( abc, bc, 0, true, false ) );     // the user's algorithm and label stay
		assertEquals( 3, Interest_Point_Registration.labelDefault( abc, bc, 3, false, false ) );
		assertEquals( 2, Interest_Point_Registration.labelDefault( abc, bc, 9, true, false ) );     // an index that no longer exists
		assertEquals( 0, Interest_Point_Registration.labelDefault( abc, bc, 9, false, false ) );
		assertEquals( 0, Interest_Point_Registration.labelDefault( new String[] { abc[ 1 ], MULTIPLE }, new int[ 2 ], -1, false, true ) );

		// a macro: the labels as they are, unless its text names a label as the dialog showed it at some time
		assertSame( labels, Interest_Point_Registration.itemsForMacro( labels, null ) );
		assertSame( labels, Interest_Point_Registration.itemsForMacro( labels, LABEL ) );
		assertSame( labels, Interest_Point_Registration.itemsForMacro( labels, MULTIPLE ) );
		assertSame( labels, Interest_Point_Registration.itemsForMacro( labels, "nuclei" ) );
		assertArrayEquals( new String[] { shown[ 0 ], labels[ 1 ], MULTIPLE }, Interest_Point_Registration.itemsForMacro( labels, shown[ 0 ] ) );
		assertArrayEquals( new String[] { LABEL + " [7 embeddings]", labels[ 1 ], MULTIPLE }, Interest_Point_Registration.itemsForMacro( labels, LABEL + " [7 embeddings]" ) );
		assertArrayEquals( new String[] { LABEL, shown[ 1 ], MULTIPLE }, Interest_Point_Registration.itemsForMacro( labels, shown[ 1 ] ) );
		assertArrayEquals( new String[] { LABEL, filtered, MULTIPLE }, Interest_Point_Registration.itemsForMacro( labels, filtered ) );
	}

	/** runs the basic registration dialog as a macro does: ImageJ hands the options to threads named "Run$_..." and shows nothing */
	static BasicRegistrationParameters dialogByMacro( final String options, final SpimData2 data, final List< ViewId > views ) throws Exception
	{
		final BasicRegistrationParameters[] result = new BasicRegistrationParameters[ 1 ];
		final Throwable[] failure = new Throwable[ 1 ];

		final Thread thread = new Thread( () -> {
			try
			{
				Macro.setOptions( options );
				result[ 0 ] = new Interest_Point_Registration().basicRegistrationParameters(
						SpimData2.getAllTimePointsSorted( data, views ), data.getSequenceDescription().getTimePoints().size(), data, new ArrayList<>( views ) );
			}
			catch ( final Throwable t )
			{
				failure[ 0 ] = t;
			}
			finally
			{
				Macro.setOptions( null );
			}
		}, "Run$_registration" );

		thread.start();
		thread.join();

		if ( failure[ 0 ] != null )
			throw new AssertionError( "the dialog failed for '" + options + "'", failure[ 0 ] );
		assertNotNull( result[ 0 ], "the dialog was cancelled for '" + options + "'" );
		return result[ 0 ];
	}

	/**
	 * ImageJ's own dialog, run by macros (skipped without a display): a macro that names a label by its name goes on
	 * selecting it when the label has embeddings, and so does the text the recorder writes from the displayed choice
	 */
	@Test
	public void registrationDialogByMacro() throws Exception
	{
		assumeFalse( GraphicsEnvironment.isHeadless() );

		final File xml = tempDir.resolve( "dataset.xml" ).toFile();
		final SpimData2 data = beads( xml );
		final List< ViewId > views = viewsWith( data, LABEL );
		final Path onnx = tempDir.resolve( "tiny.onnx" );
		TestOnnxModels.write( onnx, new double[] { 3, 6 }, 4, 3, 5, 21 );
		assertTrue( Embed_Interest_Points.embed( data, views, LABEL, onnx, "rotation-invariant", 50 ) );

		final String filtered = filteredLabelInFirstView( data, views );
		final String warning = InterestPointTools.warningLabel + "1/" + views.size() + " Views!)";

		final int algorithm = Interest_Point_Registration.defaultAlgorithm, label = Interest_Point_Registration.defaultLabel;
		final int overlap = Interest_Point_Registration.defaultOverlapType, ipOverlap = Interest_Point_Registration.defaultInterestpointOverlapType;
		final boolean tiles = Interest_Point_Registration.defaultGroupTiles, illums = Interest_Point_Registration.defaultGroupIllums, channels = Interest_Point_Registration.defaultGroupChannels;

		try
		{
			// nothing chosen yet, a macro that says nothing: the embeddings matcher with the first label that has embeddings
			Interest_Point_Registration.defaultAlgorithm = Interest_Point_Registration.defaultLabel = -1;
			BasicRegistrationParameters brp = dialogByMacro( "registration_in_between_views=[Compare all views against each other]", data, views );
			assertTrue( brp.pwr instanceof EmbeddingsGUI, brp.pwr.getDescription() );
			assertEquals( Set.of( LABEL ), brp.labelMap.get( views.get( 0 ) ).keySet() );

			// the label by its name while the default is another one: the label with embeddings, and the one with a warning
			Interest_Point_Registration.defaultLabel = 1;
			brp = dialogByMacro( "registration_algorithm=[" + new RGLDMGUI().getDescription() + "] interest_points=" + LABEL, data, views );
			assertTrue( brp.pwr instanceof RGLDMGUI, brp.pwr.getDescription() );
			assertEquals( Set.of( LABEL ), brp.labelMap.get( views.get( 0 ) ).keySet() );

			Interest_Point_Registration.defaultLabel = 0;
			brp = dialogByMacro( "interest_points=" + filtered, data, views );
			assertEquals( Set.of( filtered ), brp.labelMap.get( views.get( 0 ) ).keySet() );

			// as recorded from the displayed choice, the brackets inside the brackets included
			Interest_Point_Registration.defaultLabel = 1;
			brp = dialogByMacro( "interest_points=[" + LABEL + " [1 embedding]]", data, views );
			assertEquals( Set.of( LABEL ), brp.labelMap.get( views.get( 0 ) ).keySet() );

			Interest_Point_Registration.defaultLabel = 0;
			brp = dialogByMacro( "interest_points=[" + filtered + " [1 embedding]" + warning + "] registration_algorithm=[" + new EmbeddingsGUI( null ).getDescription() + "]", data, views );
			assertTrue( brp.pwr instanceof EmbeddingsGUI, brp.pwr.getDescription() );
			assertEquals( Set.of( filtered ), brp.labelMap.get( views.get( 0 ) ).keySet() );
		}
		finally
		{
			Interest_Point_Registration.defaultAlgorithm = algorithm;
			Interest_Point_Registration.defaultLabel = label;
			Interest_Point_Registration.defaultOverlapType = overlap;
			Interest_Point_Registration.defaultInterestpointOverlapType = ipOverlap;
			Interest_Point_Registration.defaultGroupTiles = tiles;
			Interest_Point_Registration.defaultGroupIllums = illums;
			Interest_Point_Registration.defaultGroupChannels = channels;
		}
	}

	/**
	 * The embeddings are held in memory like the interest points and written when the dataset is saved (Stephan, 2026-11-14):
	 * detections that are not saved yet can be embedded, every reader of this JVM has the vectors at once, the store gets
	 * them with the save, and a detection that replaces a label drops its embeddings, in the store with the next save.
	 */
	@Test
	public void embeddingsAreSavedWithTheDataset() throws Exception
	{
		final File xml = tempDir.resolve( "dataset.xml" ).toFile();
		final SpimData2 data = unsavedBeads( xml );
		final List< ViewId > views = viewsWith( data, LABEL );
		assertTrue( views.size() >= 2 );
		final URI baseDir = data.getBasePathURI();
		final int tp = views.get( 0 ).getTimePointId();
		final File array = new File( new File( baseDir ), InterestPointsZarrStore.ZARR_CONTAINER + "/" + LABEL + "/tp" + tp + "/" + InterestPointArrays.ARRAYS + "/embedding" );
		final Path onnx = tempDir.resolve( "tiny.onnx" );
		TestOnnxModels.write( onnx, new double[] { 3, 6 }, 4, 3, 5, 21 );

		final Embed_Interest_Points.Parameters p = new Embed_Interest_Points.Parameters();
		p.label = LABEL;
		p.model = onnx.toString();
		p.arrayName = "embedding";
		p.blockSize = 50;
		p.viewsInFlight = 2;

		// detections that are not saved yet: there is no store, the embedding runs on them and writes nothing
		assertFalse( InterestPointsZarrStore.get( baseDir ).exists() );
		assertFalse( InterestPointArrays.hasStaged( baseDir ) );
		assertTrue( Embed_Interest_Points.embed( data, views, p ) );
		assertFalse( InterestPointsZarrStore.get( baseDir ).exists() );
		assertTrue( InterestPointArrays.hasStaged( baseDir ) );

		// every reader has them: the matcher's vectors, the explorer's column, the registration dialog
		final HashMap< ViewId, float[] > inMemory = new HashMap<>();
		for ( final ViewId v : views )
		{
			final ViewInterestPointLists lists = data.getViewInterestPoints().getViewInterestPointLists( v );
			final Vectors vectors = InterestPointArrays.vectors( lists, LABEL, "embedding" );
			assertEquals( lists.getInterestPointList( LABEL ).getInterestPointsCopy().size(), vectors.size() + vectors.missing() );
			inMemory.put( v, vectors.values() );
		}
		assertEquals( 1, InterestPointArrays.list( baseDir, LABEL, tp ).size() );
		assertEquals( views.size(), InterestPointArrays.rows( baseDir, InterestPointArrays.attributes( baseDir, LABEL, tp, "embedding" ) ).size() );
		assertEquals( Set.of( "embedding" ), InterestPointArrays.namesByLabel( data.getViewInterestPoints(), views ).get( LABEL ) );
		assertTrue( InterestPointTableModel.computeAttributes( data.getViewInterestPoints(), views, LABEL ).text.endsWith( "+ embedding 5-D" ) );

		// the save writes the points and their embeddings: one table for all views (one generation), no tables per view
		new XmlIoSpimData2().save( data, xml.toURI() );
		assertFalse( InterestPointArrays.hasStaged( baseDir ) );
		for ( final ViewId v : views )
		{
			assertTrue( stored( baseDir, tp, v ) );
			assertFalse( new File( array, InterestPointArrays.SETUP_PREFIX + v.getViewSetupId() ).exists() );
		}
		assertEquals( 1, array.list( ( dir, name ) -> name.matches( "g\\d+" ) ).length );

		final SpimData2 loaded = new XmlIoSpimData2().load( xml.toURI() );
		for ( final ViewId v : views )
			assertArrayEquals( inMemory.get( v ), InterestPointArrays.vectors( loaded.getViewInterestPoints().getViewInterestPointLists( v ), LABEL, "embedding" ).values() );

		// a detection replaces the label of the first view: its embedding is gone for the readers at once ...
		final ViewId first = views.get( 0 ), second = views.get( 1 );
		final String parameters = data.getViewInterestPoints().getViewInterestPointLists( first ).getInterestPointList( LABEL ).getParameters();
		final HashMap< ViewId, List< InterestPoint > > redetected = new HashMap<>();
		redetected.put( first, TestInterestPointStorage.points( 7, 1 ) );
		InterestPointTools.removeReferences( data, LABEL, Collections.singletonList( first ) );
		InterestPointTools.addInterestPoints( data, LABEL, redetected, parameters );

		assertNull( InterestPointArrays.read( baseDir, LABEL, tp, first.getViewSetupId(), "embedding" ) );
		assertEquals( views.size() - 1, InterestPointArrays.rows( baseDir, InterestPointArrays.attributes( baseDir, LABEL, tp, "embedding" ) ).size() );
		assertThrows( IllegalStateException.class, () -> InterestPointArrays.vectors( data.getViewInterestPoints().getViewInterestPointLists( first ), LABEL, "embedding" ) );
		assertTrue( InterestPointTableModel.computeAttributes( data.getViewInterestPoints(), views, LABEL ).text.endsWith( "(" + ( views.size() - 1 ) + "/" + views.size() + " views)" ) );

		// ... and in the store with the save; the other views keep theirs
		assertTrue( stored( baseDir, tp, first ) );
		new XmlIoSpimData2().save( data, xml.toURI() );
		assertFalse( stored( baseDir, tp, first ) );
		assertTrue( stored( baseDir, tp, second ) );

		// the new points embedded and saved: one row per new point
		assertTrue( Embed_Interest_Points.embed( data, Collections.singletonList( first ), p ) );
		assertEquals( 7, InterestPointArrays.read( baseDir, LABEL, tp, first.getViewSetupId(), "embedding" ).size() );
		assertFalse( stored( baseDir, tp, first ) );
		new XmlIoSpimData2().save( data, xml.toURI() );
		assertTrue( stored( baseDir, tp, first ) );
		assertEquals( 7, InterestPointArrays.read( baseDir, LABEL, tp, first.getViewSetupId(), "embedding" ).size() );

		// embedded, then detected again before the save: nothing of the old points reaches the store
		assertTrue( Embed_Interest_Points.embed( data, Collections.singletonList( second ), p ) );
		redetected.clear();
		redetected.put( second, TestInterestPointStorage.points( 9, 2 ) );
		InterestPointTools.removeReferences( data, LABEL, Collections.singletonList( second ) );
		InterestPointTools.addInterestPoints( data, LABEL, redetected, parameters );
		assertNull( InterestPointArrays.read( baseDir, LABEL, tp, second.getViewSetupId(), "embedding" ) );
		new XmlIoSpimData2().save( data, xml.toURI() );
		assertFalse( stored( baseDir, tp, second ) );

		// all views detected again: the array goes as a whole
		InterestPointTools.removeReferences( data, LABEL, views );
		assertTrue( InterestPointArrays.list( baseDir, LABEL, tp ).isEmpty() || InterestPointArrays.rows( baseDir, InterestPointArrays.attributes( baseDir, LABEL, tp, "embedding" ) ).isEmpty() );
		assertTrue( array.isDirectory() );
		new XmlIoSpimData2().save( data, xml.toURI() );
		assertFalse( array.exists() );
		assertTrue( InterestPointArrays.list( baseDir, LABEL, tp ).isEmpty() );
	}

	/** @return whether the store holds rows of the array "embedding" for a view (whatever this JVM holds for the next save) */
	static boolean stored( final URI baseDir, final int tp, final ViewId view )
	{
		return InterestPointArrays.rowsInStore( baseDir, LABEL, tp, "embedding" ).containsKey( view.getViewSetupId() );
	}

	/** the simulated beads with a DoG label whose parameters string the embedding can read, saved so that the store exists */
	static SpimData2 beads( final File xml ) throws Exception
	{
		final SpimData2 data = unsavedBeads( xml );
		new XmlIoSpimData2().save( data, xml.toURI() );
		return data;
	}

	/** as {@link #beads(File)}, but the detections are not saved yet (the XML without points was, which creates no store) */
	static SpimData2 unsavedBeads( final File xml ) throws Exception
	{
		final SpimData2 data = SimulateUtil.setUp();
		new XmlIoSpimData2().save( data, xml.toURI() );
		TestInterestPointDetection.testDoG( data, LABEL );
		for ( final ViewInterestPointLists lists : data.getViewInterestPoints().getViewInterestPoints().values() )
			if ( lists.contains( LABEL ) )
				lists.getInterestPointList( LABEL ).setParameters( "DOG s=" + TestInterestPointDetection.STANDARD_SIGMA + " t=" + TestInterestPointDetection.STANDARD_THRESHOLD + " min=false max=true downsampleX=" + TestInterestPointDetection.STANDARD_DOWNSAMPLE_XY + " downsampleY=" + TestInterestPointDetection.STANDARD_DOWNSAMPLE_XY + " downsampleZ=" + TestInterestPointDetection.STANDARD_DOWNSAMPLE_Z + " anisotropy=1.0" );
		return data;
	}

	static List< ViewId > viewsWith( final SpimData2 data, final String label )
	{
		final List< ViewId > views = new ArrayList<>();
		for ( final ViewId v : data.getViewInterestPoints().getViewInterestPoints().keySet() )
			if ( data.getViewInterestPoints().getViewInterestPointLists( v ).contains( label ) )
				views.add( new ViewId( v.getTimePointId(), v.getViewSetupId() ) );
		views.sort( ( a, b ) -> Integer.compare( a.getViewSetupId(), b.getViewSetupId() ) );
		return views;
	}

	@Test
	public void embedsAndStoresTheRows() throws Exception
	{
		final File xml = tempDir.resolve( "dataset.xml" ).toFile();
		final SpimData2 data = beads( xml );
		final URI baseDir = data.getBasePathURI();
		final List< ViewId > views = viewsWith( data, LABEL );
		assertEquals( 3, views.size() );

		final Contract contract = contract( new double[] { 2, 4 }, 4, 3, "model-a", "detection" );
		final MeanModel model = new MeanModel( contract );
		final String name = InterestPointArrays.EMBEDDING;

		// every view: all points, by id, unit vectors; stored under the label, read back by the matcher's rule
		int total = 0;
		for ( final ViewId v : views )
		{
			final ViewInterestPointLists lists = data.getViewInterestPoints().getViewInterestPointLists( v );
			final int points = lists.getInterestPointList( LABEL ).getInterestPointsCopy().size();
			assertTrue( points > 10 );

			final Result result = InterestPointEmbedding.embedView( data, v, LABEL, model, 7, null );
			assertEquals( LABEL, result.baseLabel() );
			assertEquals( points, result.size() );
			assertEquals( 3, result.columns() );
			for ( int i = 1; i < result.size(); ++i )
				assertTrue( result.ids()[ i ] > result.ids()[ i - 1 ] );
			for ( int i = 0; i < result.size(); ++i )
			{
				double norm = 0;
				for ( int d = 0; d < 3; ++d )
					norm += result.values()[ i * 3 + d ] * result.values()[ i * 3 + d ];
				assertEquals( 1.0, Math.sqrt( norm ), 1e-5 );
			}

			InterestPointEmbedding.prepareArray( baseDir, LABEL, v.getTimePointId(), name, contract, "test" );
			InterestPointEmbedding.write( baseDir, name, result );

			final Vectors vectors = InterestPointArrays.vectors( lists, LABEL, name );
			assertEquals( points, vectors.size() );
			assertEquals( 0, vectors.missing() );
			assertEquals( "model-a", vectors.attributes().fingerprint() );
			assertEquals( "test", vectors.attributes().meta().get( "writer" ).getAsString() );
			assertEquals( "test.pt", vectors.attributes().meta().getAsJsonObject( "model" ).get( "file" ).getAsString() );
			assertEquals( "test.onnx", vectors.attributes().meta().getAsJsonObject( "model" ).get( "onnx" ).getAsString() );
			assertTrue( vectors.attributes().meta().get( "point_sigma" ).getAsBoolean() );
			for ( int i = 0; i < result.size(); ++i )
				assertEquals( result.values()[ i * 3 ], vectors.values()[ vectors.indexOf( result.ids()[ i ] ) * 3 ] );
			total += points;
		}
		assertTrue( total > 30 );

		final List< Attributes > arrays = InterestPointArrays.list( baseDir, LABEL, 0 );
		assertEquals( 1, arrays.size() );
		assertEquals( 3, arrays.get( 0 ).columns() );
		final SortedMap< Integer, Long > rows = InterestPointArrays.rows( baseDir, arrays.get( 0 ) );
		assertEquals( 3, rows.size() );

		// the same array exists for this model: kept, its setups are replaced one by one
		assertEquals( "model-a", InterestPointEmbedding.prepareArray( baseDir, LABEL, 0, name, contract, "test" ).getAsJsonObject( "model" ).get( "fingerprint" ).getAsString() );
		assertEquals( 3, InterestPointArrays.rows( baseDir, arrays.get( 0 ) ).size() );

		// another model under the same name replaces the array as a whole
		final Contract other = contract( new double[] { 2, 4 }, 4, 3, "model-b", "detection" );
		InterestPointEmbedding.prepareArray( baseDir, LABEL, 0, name, other, "test" );
		final Attributes replaced = InterestPointArrays.attributes( baseDir, LABEL, 0, name );
		assertEquals( "model-b", replaced.fingerprint() );
		assertTrue( InterestPointArrays.rows( baseDir, replaced ).isEmpty() );
		final ViewId first = views.get( 0 );
		InterestPointEmbedding.write( baseDir, name, InterestPointEmbedding.embedView( data, first, LABEL, new MeanModel( other ), 5, null ) );
		assertEquals( 1, InterestPointArrays.rows( baseDir, replaced ).size() );
		assertNotNull( InterestPointArrays.read( baseDir, LABEL, 0, first.getViewSetupId(), name ) );
		// the other views have no rows now: the matcher refuses them
		assertThrows( IllegalStateException.class, () -> InterestPointArrays.vectors( data.getViewInterestPoints().getViewInterestPointLists( views.get( 1 ) ), LABEL, name ) );

		// another width is another array, too
		InterestPointEmbedding.prepareArray( baseDir, LABEL, 0, name, contract( new double[] { 2, 4, 8 }, 4, 4, "model-b", "detection" ), "test" );
		assertEquals( 4, InterestPointArrays.attributes( baseDir, LABEL, 0, name ).columns() );
		assertTrue( InterestPointArrays.rows( baseDir, InterestPointArrays.attributes( baseDir, LABEL, 0, name ) ).isEmpty() );

		// removing
		assertTrue( InterestPointArrays.remove( baseDir, LABEL, 0, name ) );
		assertFalse( InterestPointArrays.remove( baseDir, LABEL, 0, name ) );
		assertTrue( InterestPointArrays.list( baseDir, LABEL, 0 ).isEmpty() );
	}

	@Test
	public void poolsTheViews() throws Exception
	{
		final File xml = tempDir.resolve( "dataset.xml" ).toFile();
		final SpimData2 data = beads( xml );
		final List< ViewId > views = viewsWith( data, LABEL );
		assertEquals( 3, views.size() );
		final Contract contract = contract( new double[] { 2, 4 }, 4, 3, "model-a", "detection" );
		final MeanModel model = new MeanModel( contract );

		// the reference: one view after the other
		final Map< ViewId, Result > reference = new HashMap<>();
		long points = 0;
		for ( final ViewId v : views )
		{
			reference.put( v, InterestPointEmbedding.embedView( data, v, LABEL, model, 7, null ) );
			points += reference.get( v ).size();
		}

		final ExecutorService pool = Executors.newFixedThreadPool( 3 );
		try
		{
			// one, two, and more views in flight than there are views, and the pool-less path: the same rows, every view handed over once
			for ( final int inFlight : new int[] { 1, 2, 5, 0 } )
			{
				final Map< ViewId, Result > results = new HashMap<>();
				final long total = InterestPointEmbedding.embedViews( data, views, LABEL, model, 7, inFlight == 0 ? null : pool, Math.max( 1, inFlight ), r -> assertNull( results.put( r.view(), r ) ) );
				assertEquals( points, total, "views in flight: " + inFlight );
				assertEquals( reference.keySet(), results.keySet() );
				for ( final ViewId v : views )
				{
					assertEquals( LABEL, results.get( v ).baseLabel() );
					assertArrayEquals( reference.get( v ).ids(), results.get( v ).ids() );
					assertArrayEquals( reference.get( v ).values(), results.get( v ).values() );
				}
			}

			assertThrows( IllegalArgumentException.class, () -> InterestPointEmbedding.embedViews( data, views, LABEL, model, 7, pool, 0, r -> {} ) );

			// a view that cannot be embedded fails the call while the others' blocks are in the pool; the pool stays usable
			final ViewInterestPointLists lists = data.getViewInterestPoints().getViewInterestPointLists( views.get( 1 ) );
			final String parameters = lists.getInterestPointList( LABEL ).getParameters();
			lists.getInterestPointList( LABEL ).setParameters( "manual" );
			final IllegalArgumentException e = assertThrows( IllegalArgumentException.class, () -> InterestPointEmbedding.embedViews( data, views, LABEL, model, 7, pool, 5, r -> {} ) );
			assertTrue( e.getMessage().contains( "no DoG detection parameters" ), e.getMessage() );
			lists.getInterestPointList( LABEL ).setParameters( parameters );
			assertEquals( points, InterestPointEmbedding.embedViews( data, views, LABEL, model, 7, pool, 5, r -> {} ) );
		}
		finally
		{
			pool.shutdown();
		}
	}

	@Test
	public void filteredLabelEmbedsItsBase() throws Exception
	{
		final File xml = tempDir.resolve( "dataset.xml" ).toFile();
		final SpimData2 data = beads( xml );
		final URI baseDir = data.getBasePathURI();
		final ViewId view = viewsWith( data, LABEL ).get( 0 );
		final ViewInterestPointLists lists = data.getViewInterestPoints().getViewInterestPointLists( view );
		final int basePoints = lists.getInterestPointList( LABEL ).getInterestPointsCopy().size();

		final String filtered = LABEL + "_i500";
		lists.addInterestPointList( filtered, VirtualFilteredInterestPoints.create( baseDir, view, filtered, LABEL, InterestPointFilter.fromString( "intensity=500.." ) ) );
		final int filteredPoints = lists.getInterestPointList( filtered ).getInterestPointsCopy().size();
		assertTrue( filteredPoints > 0 && filteredPoints < basePoints, filteredPoints + " of " + basePoints );

		final Contract contract = contract( new double[] { 3 }, 4, 2, "model-f", "detection" );
		final Result result = InterestPointEmbedding.embedView( data, view, filtered, new MeanModel( contract ), 16, null );
		assertEquals( LABEL, result.baseLabel() );
		assertEquals( basePoints, result.size() );

		InterestPointEmbedding.prepareArray( baseDir, LABEL, view.getTimePointId(), "emb", contract, "test" );
		InterestPointEmbedding.write( baseDir, "emb", result );

		// the filtered label reads the base's rows: its points, all with a vector, the count rule satisfied by the base
		final Vectors vectors = InterestPointArrays.vectors( lists, filtered, "emb" );
		assertEquals( filteredPoints, vectors.size() );
		assertEquals( 0, vectors.missing() );
		assertEquals( basePoints, InterestPointArrays.vectors( lists, LABEL, "emb" ).size() );

		// a label without a detection string cannot be embedded
		lists.getInterestPointList( LABEL ).setParameters( "manually placed" );
		final IllegalArgumentException e = assertThrows( IllegalArgumentException.class, () -> InterestPointEmbedding.embedView( data, view, filtered, new MeanModel( contract ), 16, null ) );
		assertTrue( e.getMessage().contains( "no DoG detection parameters" ), e.getMessage() );
	}

	@Test
	public void pluginWithAnOnnxModel() throws Exception
	{
		final File xml = tempDir.resolve( "dataset.xml" ).toFile();
		final SpimData2 data = beads( xml );
		final List< ViewId > views = viewsWith( data, LABEL );
		final Path onnx = tempDir.resolve( "tiny.onnx" );
		final TestOnnxModels.Tiny tiny = TestOnnxModels.write( onnx, new double[] { 3, 6 }, 4, 3, 5, 21 );

		assertTrue( Embed_Interest_Points.embed( data, views, LABEL, onnx, "embedding", 50 ) );

		for ( final ViewId v : views )
		{
			final ViewInterestPointLists lists = data.getViewInterestPoints().getViewInterestPointLists( v );
			final Vectors vectors = InterestPointArrays.vectors( lists, LABEL, "embedding" );
			assertEquals( lists.getInterestPointList( LABEL ).getInterestPointsCopy().size(), vectors.size() + vectors.missing() );
			assertEquals( tiny.fingerprint(), vectors.attributes().fingerprint() );
			assertEquals( 5, vectors.columns() );
			assertEquals( Embed_Interest_Points.WRITER, vectors.attributes().meta().get( "writer" ).getAsString() );
			for ( int i = 0; i < vectors.size(); ++i )
			{
				double norm = 0;
				for ( int d = 0; d < 5; ++d )
					norm += vectors.values()[ i * 5 + d ] * vectors.values()[ i * 5 + d ];
				assertEquals( 1.0, Math.sqrt( norm ), 1e-4 );
			}
		}

		// recorded
		final List< ActionRecord > records = data.getActionHistory().getRecords();
		final ActionRecord last = records.get( records.size() - 1 );
		assertEquals( Embed_Interest_Points.ACTION_ID, last.getActionId() );
		assertEquals( LABEL, last.getParams().get( "label" ) );
		assertEquals( onnx.toAbsolutePath().toString(), last.getParams().get( "model" ) );
		assertEquals( "embedding", last.getParams().get( "arrayName" ) );
		assertEquals( tiny.fingerprint(), last.getParams().get( "fingerprint" ) );

		// a label none of the views has, a model that does not load: nothing is written, no exception
		assertFalse( Embed_Interest_Points.embed( data, views, "nuclei", onnx, "embedding", 50 ) );
		assertFalse( Embed_Interest_Points.embed( data, views, LABEL, tempDir.resolve( "missing.onnx" ), "embedding", 50 ) );
	}
}
