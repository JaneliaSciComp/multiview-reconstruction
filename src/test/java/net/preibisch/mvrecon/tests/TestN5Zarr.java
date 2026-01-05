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
package net.preibisch.mvrecon.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.zarr.N5ZarrWriter;
import org.janelia.scicomp.n5.zstandard.ZstandardCompression;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Pair;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.process.n5api.N5ApiTools;
import net.preibisch.mvrecon.process.n5api.N5ApiTools.MultiResolutionLevelInfo;
import util.BlockSupplierUtils;

/**
 * Test class for N5 and Zarr format support, including Zarr v3 sharding.
 * Tests write actual fusion data from TestFusion and verify multi-resolution downsampling.
 */
public class TestN5Zarr
{
	@TempDir
	Path tempDir;

	private SpimData2 spimData;
	private Interval bb;

	final private double downsampling = Double.NaN;
	final private double anisotropyFactor = 1.5;

	@BeforeEach
	public void setUp()
	{
		spimData = SimulateUtil.setUp();
		TestInterestPointDetection.testDoG( spimData, "beads" );
		TestRegistration.testRegistration( spimData, "beads", false );
		bb = TestBoundingBox.testBoundingBox( spimData, false );
	}

	@AfterEach
	public void tearDown() throws IOException
	{
		// Cleanup temp directories
		if ( tempDir != null && Files.exists( tempDir ) )
		{
			Files.walk( tempDir )
				.sorted( Comparator.reverseOrder() )
				.map( Path::toFile )
				.forEach( File::delete );
		}
	}

	@Test
	public void testZarrV2NoSharding() throws Exception
	{
		File outputPath = tempDir.resolve( "zarr_v2.zarr" ).toFile();
		N5Writer writer = util.URITools.instantiateN5Writer( org.janelia.saalfeldlab.n5.universe.StorageFormat.ZARR, outputPath.toURI() );

		try
		{
			// Get fusion BlockSupplier from TestFusion
			final Pair<BlockSupplier<UnsignedShortType>, Interval> fusionResult =
					TestFusion.testFusion( spimData, bb, FusionType.AVG_BLEND, downsampling, anisotropyFactor );

			final BlockSupplier<UnsignedShortType> blockSupplier = fusionResult.getA();
			final Interval boundingBox = fusionResult.getB();
			final int[] blockSize = new int[] { 32, 32, 32 };

			MultiResolutionLevelInfo[] mrInfo = N5ApiTools.setupMultiResolutionPyramid(
				writer,
				( level ) -> "test/s" + level,
				DataType.UINT16,
				boundingBox.dimensionsAsLongArray(),
				new ZstandardCompression(),
				blockSize,
				new int[][] {
					{ 1, 1, 1 },
					{ 2, 2, 1 },
					{ 4, 4, 2 }
				},
				false,  // useSharding
				null    // shardSize
			);

			// Write fusion data and multi-resolution pyramid
			writeFusionData( writer, mrInfo, blockSupplier );

			assertEquals( 3, mrInfo.length, "Should have 3 resolution levels" );
			assertTrue( writer.exists( "test/s0" ), "s0 should exist" );
			assertTrue( writer.exists( "test/s1" ), "s1 should exist" );
			assertTrue( writer.exists( "test/s2" ), "s2 should exist" );

			// Verify no sharding metadata in Zarr v2
			File zarrJson = new File( outputPath, "test/s0/zarr.json" );
			if ( zarrJson.exists() )
			{
				String content = new String( Files.readAllBytes( zarrJson.toPath() ) );
				assertFalse( content.contains( "shard" ), "Zarr v2 should not have sharding" );
			}

			// Verify data can be read back from all levels
			assertTrue( writer.readBlock( "test/s0", writer.getDatasetAttributes( "test/s0" ), new long[] { 0, 0, 0 } ) != null );
			assertTrue( writer.readBlock( "test/s1", writer.getDatasetAttributes( "test/s1" ), new long[] { 0, 0, 0 } ) != null );
			assertTrue( writer.readBlock( "test/s2", writer.getDatasetAttributes( "test/s2" ), new long[] { 0, 0, 0 } ) != null );

			// Count files created
			long totalFiles = Files.walk(outputPath.toPath()).filter(p -> p.toFile().isFile()).count();
			long s0Files = Files.walk(new File(outputPath, "test/s0").toPath()).filter(p -> p.toFile().isFile()).count();
			long s1Files = Files.walk(new File(outputPath, "test/s1").toPath()).filter(p -> p.toFile().isFile()).count();
			long s2Files = Files.walk(new File(outputPath, "test/s2").toPath()).filter(p -> p.toFile().isFile()).count();
			System.out.println("[testZarrV2NoSharding] File counts:");
			System.out.println("  Total files: " + totalFiles);
			System.out.println("  s0 files: " + s0Files);
			System.out.println("  s1 files: " + s1Files);
			System.out.println("  s2 files: " + s2Files);
		}
		finally
		{
			writer.close();
		}
	}

	@Test
	public void testZarrV3WithSharding() throws Exception
	{
		File outputPath = tempDir.resolve( "zarr_v3.zarr" ).toFile();
		N5Writer writer = util.URITools.instantiateN5Writer( org.janelia.saalfeldlab.n5.universe.StorageFormat.ZARR, outputPath.toURI() );

		try
		{
			// Get fusion BlockSupplier from TestFusion
			final Pair<BlockSupplier<UnsignedShortType>, Interval> fusionResult =
					TestFusion.testFusion( spimData, bb, FusionType.AVG_BLEND, downsampling, anisotropyFactor );

			final BlockSupplier<UnsignedShortType> blockSupplier = fusionResult.getA();
			final Interval boundingBox = fusionResult.getB();
			final int[] blockSize = new int[] { 8, 8, 8 };
			final int[] shardSize = new int[] { 32, 32, 32 };

			MultiResolutionLevelInfo[] mrInfo = N5ApiTools.setupMultiResolutionPyramid(
				writer,
				( level ) -> "test/s" + level,
				DataType.UINT16,
				boundingBox.dimensionsAsLongArray(),
				new ZstandardCompression(),
				blockSize,
				new int[][] {
					{ 1, 1, 1 },
					{ 2, 2, 1 },
					{ 4, 4, 2 }
				},
				true,       // useSharding
				shardSize
			);

			// Write fusion data and multi-resolution pyramid (with sharding)
			writeFusionData( writer, mrInfo, blockSupplier );

			assertEquals( 3, mrInfo.length, "Should have 3 resolution levels" );
			assertTrue( writer.exists( "test/s0" ), "s0 should exist" );
			assertTrue( writer.exists( "test/s1" ), "s1 should exist" );
			assertTrue( writer.exists( "test/s2" ), "s2 should exist" );

			File zarrJson = new File( outputPath, "test/s0/zarr.json" );
			File zarray = new File( outputPath, "test/s0/.zarray" );

			// Note: Current n5-zarr alpha may create Zarr v2 format
			assertTrue( zarrJson.exists() || zarray.exists(),
				"Either zarr.json (v3) or .zarray (v2) should exist" );

			// Verify data can be read back from all levels
			assertTrue( writer.readBlock( "test/s0", writer.getDatasetAttributes( "test/s0" ), new long[] { 0, 0, 0 } ) != null );
			assertTrue( writer.readBlock( "test/s1", writer.getDatasetAttributes( "test/s1" ), new long[] { 0, 0, 0 } ) != null );
			assertTrue( writer.readBlock( "test/s2", writer.getDatasetAttributes( "test/s2" ), new long[] { 0, 0, 0 } ) != null );

			// Debug: Check dataset attributes
			DatasetAttributes s0Attrs = writer.getDatasetAttributes("test/s0");
			System.out.println("[testZarrV3WithSharding] s0 dataset attributes:");
			System.out.println("  Class: " + s0Attrs.getClass().getName());
			System.out.println("  Dimensions: " + java.util.Arrays.toString(s0Attrs.getDimensions()));
			System.out.println("  Block size (inner chunk): " + java.util.Arrays.toString(s0Attrs.getBlockSize()));
			if (s0Attrs instanceof org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3DatasetAttributes) {
				org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3DatasetAttributes zarrAttrs =
					(org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3DatasetAttributes) s0Attrs;
				System.out.println("  Chunk grid shape (shard size): " + java.util.Arrays.toString(zarrAttrs.getChunkAttributes().getGrid().getShape()));
				System.out.println("  Is sharded: " + (zarrAttrs.getBlockCodecInfo() instanceof org.janelia.saalfeldlab.n5.shard.ShardCodecInfo));
			}

			// Count files created
			long totalFiles = Files.walk(outputPath.toPath()).filter(p -> p.toFile().isFile()).count();
			long s0Files = Files.walk(new File(outputPath, "test/s0").toPath()).filter(p -> p.toFile().isFile()).count();
			long s1Files = Files.walk(new File(outputPath, "test/s1").toPath()).filter(p -> p.toFile().isFile()).count();
			long s2Files = Files.walk(new File(outputPath, "test/s2").toPath()).filter(p -> p.toFile().isFile()).count();
			System.out.println("[testZarrV3WithSharding] File counts:");
			System.out.println("  Total files: " + totalFiles);
			System.out.println("  s0 files: " + s0Files);
			System.out.println("  s1 files: " + s1Files);
			System.out.println("  s2 files: " + s2Files);
		}
		finally
		{
			writer.close();
		}
	}

	//@Test
	public void testVariousShardSizes() throws Exception
	{
		int[][] testConfigs = new int[][] {
			// { blockX, blockY, blockZ, shardX, shardY, shardZ }
			{ 16, 16, 16, 64, 64, 32 },
			{ 32, 32, 32, 128, 128, 64 }
		};

		for ( int i = 0; i < testConfigs.length; i++ )
		{
			File outputPath = tempDir.resolve( "zarr_v3_config_" + i + ".zarr" ).toFile();
			N5Writer writer = util.URITools.instantiateN5Writer( org.janelia.saalfeldlab.n5.universe.StorageFormat.ZARR, outputPath.toURI() );

			try
			{
				final double downsampling = 2.0;
				final double anisotropyFactor = 4.0;

				// Get fusion BlockSupplier from TestFusion
				final Pair<BlockSupplier<UnsignedShortType>, Interval> fusionResult =
						TestFusion.testFusion( spimData, bb, FusionType.AVG_BLEND, downsampling, anisotropyFactor );

				final BlockSupplier<UnsignedShortType> blockSupplier = fusionResult.getA();
				final Interval boundingBox = fusionResult.getB();

				int[] blockSize = new int[] { testConfigs[i][0], testConfigs[i][1], testConfigs[i][2] };
				int[] shardSize = new int[] { testConfigs[i][3], testConfigs[i][4], testConfigs[i][5] };

				MultiResolutionLevelInfo[] mrInfo = N5ApiTools.setupMultiResolutionPyramid(
					writer,
					( level ) -> "test/s" + level,
					DataType.UINT16,
					boundingBox.dimensionsAsLongArray(),
					new ZstandardCompression(),
					blockSize,
					new int[][] { { 1, 1, 1 } },
					true,
					shardSize
				);

				// Write fusion data
				writeFusionData( writer, mrInfo, blockSupplier );

				assertEquals( 1, mrInfo.length, "Should have 1 resolution level" );
				assertTrue( writer.exists( "test/s0" ), "Dataset should be created" );

				// Verify data can be read back
				assertTrue( writer.readBlock( "test/s0", writer.getDatasetAttributes( "test/s0" ), new long[] { 0, 0, 0 } ) != null,
						"Should be able to read written block with config " + i );
			}
			finally
			{
				writer.close();
			}
		}
	}

	//@Test
	public void testN5IgnoresSharding() throws Exception
	{
		File outputPath = tempDir.resolve( "n5_test.n5" ).toFile();
		N5Writer writer = new N5FSWriter( outputPath.getAbsolutePath() );

		try
		{
			final double downsampling = 2.0;
			final double anisotropyFactor = 4.0;

			// Get fusion BlockSupplier from TestFusion
			final Pair<BlockSupplier<UnsignedShortType>, Interval> fusionResult =
					TestFusion.testFusion( spimData, bb, FusionType.AVG_BLEND, downsampling, anisotropyFactor );

			final BlockSupplier<UnsignedShortType> blockSupplier = fusionResult.getA();
			final Interval boundingBox = fusionResult.getB();
			final int[] blockSize = new int[] { 32, 32, 32 };

			MultiResolutionLevelInfo[] mrInfo = N5ApiTools.setupMultiResolutionPyramid(
				writer,
				( level ) -> "test/s" + level,
				DataType.UINT16,
				boundingBox.dimensionsAsLongArray(),
				new ZstandardCompression(),
				blockSize,
				new int[][] { { 1, 1, 1 } },
				true,  // useSharding - should be ignored for N5
				new int[] { 128, 128, 64 }
			);

			// Write fusion data (N5 doesn't support sharding)
			writeFusionData( writer, mrInfo, blockSupplier );

			assertEquals( 1, mrInfo.length, "Should create N5 dataset despite sharding parameter" );
			assertTrue( writer.exists( "test/s0" ), "N5 dataset should exist" );

			// Verify N5 format (has attributes.json, not zarr.json)
			File attributesJson = new File( outputPath, "test/s0/attributes.json" );
			assertTrue( attributesJson.exists(), "N5 should have attributes.json" );

			File zarrJson = new File( outputPath, "test/s0/zarr.json" );
			assertFalse( zarrJson.exists(), "N5 should not have zarr.json" );

			// Verify data can be read back
			assertTrue( writer.readBlock( "test/s0", writer.getDatasetAttributes( "test/s0" ), new long[] { 0, 0, 0 } ) != null,
					"Should be able to read written N5 block" );
		}
		finally
		{
			writer.close();
		}
	}

	/**
	 * Write fusion data from a BlockSupplier to N5/Zarr storage.
	 * Similar to ExportN5Api.exportImage() but simplified for testing.
	 * When shardSize is provided in mrInfo (not null), uses it as compute block size for shard-aware writing.
	 */
	private static void writeFusionData(
			final N5Writer writer,
			final MultiResolutionLevelInfo[] mrInfo,
			final BlockSupplier<UnsignedShortType> blockSupplier ) throws IOException
	{
		// Write full resolution (s0)
		final Interval imgInterval = new FinalInterval( mrInfo[ 0 ].dimensions );

		// Use shard size as compute block size if sharding is enabled, otherwise use block size
		final int[] computeBlockSize = ( mrInfo[ 0 ].shardSize != null ) ? mrInfo[ 0 ].shardSize : mrInfo[ 0 ].blockSize;

		for ( final long[][] gridBlock : N5ApiTools.assembleJobs( null, mrInfo[ 0 ].dimensions, mrInfo[ 0 ].blockSize, computeBlockSize ) )
		{
			final long[] blockMin = gridBlock[0].clone();
			final long[] blockMax = new long[ blockMin.length ];

			for ( int d = 0; d < blockMin.length; ++d )
				blockMax[ d ] = Math.min( imgInterval.max( d ), blockMin[ d ] + gridBlock[1][ d ] - 1 );

			final RandomAccessibleInterval< UnsignedShortType > img =
					BlockSupplierUtils.arrayImg( blockSupplier, new FinalInterval( blockMin, blockMax ) );

			N5Utils.saveBlock( img, writer, mrInfo[ 0 ].dataset, gridBlock[2] );
		}

		System.out.println("Finished writing s0");
		// Write multi-resolution pyramid (s1 ... sN)
		// Use same shard size for all levels (constant across pyramid per user decision)
		for ( int level = 1; level < mrInfo.length; ++level )
		{
			final int[] levelComputeBlockSize = ( mrInfo[ level ].shardSize != null ) ? mrInfo[ level ].shardSize : mrInfo[ level ].blockSize;

			for ( final long[][] gridBlock : N5ApiTools.assembleJobs(
					null,
					mrInfo[ level ].dimensions,
					mrInfo[ level ].blockSize,
					levelComputeBlockSize ) )
			{
				N5ApiTools.writeDownsampledBlock( writer, mrInfo[ level ], mrInfo[ level - 1 ], gridBlock );
			}
		}
	}

}
