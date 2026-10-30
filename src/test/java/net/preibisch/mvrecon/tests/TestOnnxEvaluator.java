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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.process.interestpointembedding.EmbeddingModel;
import net.preibisch.mvrecon.process.interestpointembedding.JavaOnnxEmbeddingModel;
import net.preibisch.mvrecon.process.interestpointembedding.OnnxEvaluator;
import net.preibisch.mvrecon.process.interestpointembedding.OnnxEvaluator.Tensor;
import net.preibisch.mvrecon.process.interestpointembedding.OnnxModel;
import net.preibisch.mvrecon.tests.TestOnnxModels.Proto;
import net.preibisch.mvrecon.tests.TestOnnxModels.Tiny;

/**
 * The ONNX reader and the Java evaluator: a tiny encoder of pcmatch's shape written by {@link TestOnnxModels} is parsed
 * back and evaluated against a direct computation in double; the remaining operators on a second small graph; models the
 * evaluator does not implement are refused by name. If pcmatch's exported model and its ds1 fixture are on this machine,
 * the fixture's patches are run through the real graph and compared with its stored descriptors.
 */
public class TestOnnxEvaluator
{
	@TempDir
	Path tempDir;

	@Test
	public void parsesWhatItWrote()
	{
		final Tiny tiny = TestOnnxModels.tiny( 2, 4, 3, 5, 7 );
		final OnnxModel model = OnnxModel.parse( tiny.bytes() );

		assertEquals( 10, model.getIrVersion() );
		assertEquals( 18L, model.getOpsets().get( "" ) );
		assertEquals( "mvr-test", model.getProducerName() );
		assertEquals( "TestOnnxModels", model.getMetadata().get( "producer" ) );
		assertEquals( 37, model.getGraph().nodes().size() );
		assertEquals( 15, model.getGraph().initializers().size() );
		assertEquals( "patches", model.getGraph().inputs().get( 0 ).name() );
		assertEquals( "descriptor", model.getGraph().outputs().get( 0 ).name() );
		assertEquals( -1, model.getGraph().inputs().get( 0 ).dims()[ 0 ] );
		assertEquals( "N", model.getGraph().inputs().get( 0 ).dimParams()[ 0 ] );
		assertEquals( 4, model.getGraph().inputs().get( 0 ).dims()[ 2 ] );

		final OnnxModel.Tensor w1 = model.getGraph().initializers().get( "w1" );
		assertEquals( OnnxModel.FLOAT, w1.dataType() );
		assertEquals( 3 * 2 * 27, w1.size() );
		assertEquals( tiny.w1()[ 5 ], w1.floats()[ 5 ] );
		assertEquals( -1, model.getGraph().initializers().get( "shape_rows" ).longs()[ 0 ] );
		assertEquals( 64, model.getGraph().initializers().get( "shape_rows" ).longs()[ 1 ] );

		final OnnxModel.Node conv = model.getGraph().nodes().stream().filter( n -> n.opType().equals( "Conv" ) ).findFirst().get();
		assertEquals( List.of( "cube", "w1", "b1" ), conv.inputs() );
		assertEquals( 1, conv.attr( "group", 0 ) );
		assertEquals( "NOTSET", conv.attr( "auto_pad", "" ) );
		assertEquals( 6, conv.attr( "pads", new long[ 0 ] ).length );
		assertEquals( 1.0f, model.getGraph().nodes().stream().filter( n -> n.opType().equals( "Gemm" ) ).findFirst().get().attr( "alpha", 0f ) );
	}

	@Test
	public void evaluatesTheTinyEncoder()
	{
		final Tiny tiny = TestOnnxModels.tiny( 2, 4, 3, 5, 11 );
		final OnnxEvaluator evaluator = new OnnxEvaluator( OnnxModel.parse( tiny.bytes() ) );
		final int n = 9;
		final float[] patches = TestOnnxModels.randomPatches( n, 2, 4, 3 );
		patches[ 0 ] = Float.POSITIVE_INFINITY; // also masked, as a NaN

		final Map< String, Tensor > out = evaluator.run( Map.of( "patches", Tensor.floats( new int[] { n, 2, 4, 4, 4 }, patches ) ) );
		final Tensor descriptor = out.get( "descriptor" );
		assertEquals( OnnxModel.FLOAT, descriptor.type );
		assertEquals( 2, descriptor.rank() );
		assertEquals( n, descriptor.shape[ 0 ] );
		assertEquals( 5, descriptor.shape[ 1 ] );

		final double[] reference = TestOnnxModels.reference( tiny, patches, n );
		double maxDiff = 0;
		for ( int i = 0; i < n * 5; ++i )
			maxDiff = Math.max( maxDiff, Math.abs( descriptor.f[ i ] - reference[ i ] ) );
		assertTrue( maxDiff < 1e-4, "evaluator vs reference: max |diff| " + maxDiff );

		for ( int i = 0; i < n; ++i )
		{
			double norm = 0;
			for ( int o = 0; o < 5; ++o )
				norm += descriptor.f[ i * 5 + o ] * descriptor.f[ i * 5 + o ];
			assertEquals( 1.0, Math.sqrt( norm ), 1e-5 );
		}

		// one point at a time gives the same rows as the batch
		for ( int i = 0; i < n; ++i )
		{
			final float[] one = new float[ 2 * 64 ];
			System.arraycopy( patches, i * one.length, one, 0, one.length );
			final Tensor single = evaluator.run( Map.of( "patches", Tensor.floats( new int[] { 1, 2, 4, 4, 4 }, one ) ) ).get( "descriptor" );
			for ( int o = 0; o < 5; ++o )
				assertEquals( descriptor.f[ i * 5 + o ], single.f[ o ], 1e-6 );
		}
	}

	@Test
	public void evaluatesTheOtherOperators()
	{
		// X [N, 4, 2, 2, 2] -> BatchNormalization -> Flatten -> Identity -> Add(Constant 0.5) -> ReduceMean(axis 1, no keepdims) -> y [N]
		final float[] scale = { 1f, 2f, 0.5f, -1f }, bias = { 0f, 1f, -1f, 0.25f }, mean = { 1f, 2f, 3f, 4f }, var = { 1f, 4f, 0.25f, 9f };
		final List< Proto > init = List.of(
				TestOnnxModels.floatTensor( "scale", new long[] { 4 }, scale ),
				TestOnnxModels.floatTensor( "bias", new long[] { 4 }, bias ),
				TestOnnxModels.floatTensor( "mean", new long[] { 4 }, mean ),
				TestOnnxModels.floatTensor( "var", new long[] { 4 }, var ) );
		final List< Proto > nodes = List.of(
				TestOnnxModels.node( "BatchNormalization", new String[] { "x", "scale", "bias", "mean", "var" }, new String[] { "bn" }, TestOnnxModels.attrFloat( "epsilon", 1e-5f ) ),
				TestOnnxModels.node( "Flatten", new String[] { "bn" }, new String[] { "flat" }, TestOnnxModels.attrInt( "axis", 1 ) ),
				TestOnnxModels.node( "Identity", new String[] { "flat" }, new String[] { "same" } ),
				TestOnnxModels.node( "Constant", new String[ 0 ], new String[] { "half" }, TestOnnxModels.attrTensor( "value", TestOnnxModels.floatTensor( "", new long[ 0 ], new float[] { 0.5f } ) ) ),
				TestOnnxModels.node( "Add", new String[] { "same", "half" }, new String[] { "plus" } ),
				TestOnnxModels.node( "ReduceMean", new String[] { "plus" }, new String[] { "y" }, TestOnnxModels.attrInts( "axes", 1 ), TestOnnxModels.attrInt( "keepdims", 0 ) ) );
		final byte[] bytes = TestOnnxModels.model( nodes, init, List.of( TestOnnxModels.valueInfo( "x", 1, -1, 4, 2, 2, 2 ) ), List.of( TestOnnxModels.valueInfo( "y", 1, -1 ) ), 13 );

		final OnnxEvaluator evaluator = new OnnxEvaluator( OnnxModel.parse( bytes ) );
		final int n = 3;
		final float[] x = new float[ n * 32 ];
		for ( int i = 0; i < x.length; ++i )
			x[ i ] = ( i % 7 ) - 2.5f;
		final Tensor y = evaluator.run( Map.of( "x", Tensor.floats( new int[] { n, 4, 2, 2, 2 }, x ) ) ).get( "y" );
		assertEquals( 1, y.rank() );
		assertEquals( n, y.shape[ 0 ] );

		for ( int i = 0; i < n; ++i )
		{
			double sum = 0;
			for ( int c = 0; c < 4; ++c )
				for ( int v = 0; v < 8; ++v )
					sum += scale[ c ] * ( x[ ( i * 4 + c ) * 8 + v ] - mean[ c ] ) / Math.sqrt( var[ c ] + 1e-5 ) + bias[ c ] + 0.5;
			assertEquals( sum / 32, y.f[ i ], 1e-5 );
		}
	}

	@Test
	public void refusesWhatItCannotRun()
	{
		// an operator outside the set
		final byte[] softmax = TestOnnxModels.model(
				List.of( TestOnnxModels.node( "Softmax", new String[] { "x" }, new String[] { "y" }, TestOnnxModels.attrInt( "axis", 1 ) ) ),
				List.of(), List.of( TestOnnxModels.valueInfo( "x", 1, -1, 4 ) ), List.of( TestOnnxModels.valueInfo( "y", 1, -1, 4 ) ), 18 );
		final IllegalArgumentException e = assertThrows( IllegalArgumentException.class, () -> new OnnxEvaluator( OnnxModel.parse( softmax ) ) );
		assertTrue( e.getMessage().contains( "Softmax" ) && e.getMessage().contains( "ONNX Runtime" ), e.getMessage() );

		// an opset outside the range
		final byte[] old = TestOnnxModels.model(
				List.of( TestOnnxModels.node( "Relu", new String[] { "x" }, new String[] { "y" } ) ),
				List.of(), List.of( TestOnnxModels.valueInfo( "x", 1, -1, 4 ) ), List.of( TestOnnxModels.valueInfo( "y", 1, -1, 4 ) ), 9 );
		assertTrue( assertThrows( IllegalArgumentException.class, () -> new OnnxEvaluator( OnnxModel.parse( old ) ) ).getMessage().contains( "opset 9" ) );

		// a form of a supported operator that is not implemented: a grouped convolution
		final byte[] grouped = TestOnnxModels.model(
				List.of( TestOnnxModels.node( "Conv", new String[] { "x", "w" }, new String[] { "y" }, TestOnnxModels.attrInts( "kernel_shape", 1, 1, 1 ), TestOnnxModels.attrInt( "group", 2 ) ) ),
				List.of( TestOnnxModels.floatTensor( "w", new long[] { 2, 1, 1, 1, 1 }, new float[] { 1f, 1f } ) ),
				List.of( TestOnnxModels.valueInfo( "x", 1, -1, 2, 2, 2, 2 ) ), List.of( TestOnnxModels.valueInfo( "y", 1, -1, 2, 2, 2, 2 ) ), 18 );
		final OnnxEvaluator evaluator = new OnnxEvaluator( OnnxModel.parse( grouped ) );
		final IllegalArgumentException g = assertThrows( IllegalArgumentException.class, () -> evaluator.run( Map.of( "x", Tensor.floats( new int[] { 1, 2, 2, 2, 2 }, new float[ 16 ] ) ) ) );
		assertTrue( g.getMessage().contains( "group" ), g.getMessage() );

		// a model whose graph does not match its sidecar
		assertThrows( java.io.IOException.class, () ->
		{
			final Path onnx = tempDir.resolve( "mismatch.onnx" );
			Files.write( onnx, TestOnnxModels.tiny( 2, 4, 3, 5, 1 ).bytes() );
			Files.writeString( EmbeddingModel.sidecar( onnx ), TestOnnxModels.sidecar( "mismatch.onnx", new double[] { 5, 8, 13 }, 4, 5, "fp", "detection" ).toString() );
			EmbeddingModel.load( onnx );
		} );
	}

	@Test
	public void loadsAModelWithItsSidecar() throws Exception
	{
		final Path onnx = tempDir.resolve( "tiny.onnx" );
		final Tiny tiny = TestOnnxModels.write( onnx, new double[] { 5, 13 }, 4, 3, 6, 5 );

		final EmbeddingModel model = EmbeddingModel.load( onnx );
		assertTrue( model instanceof JavaOnnxEmbeddingModel );
		assertEquals( tiny.fingerprint(), model.contract().fingerprint() );
		assertEquals( 2, model.contract().channels() );
		assertEquals( 4, model.contract().patch() );
		assertEquals( 6, model.contract().outputs() );
		assertEquals( 2 * 64, model.contract().patchSize() );
		assertEquals( "tiny.pt", model.contract().checkpoint() );
		assertTrue( Float.isNaN( model.contract().outside() ) );
		assertEquals( "tiny.onnx", model.contract().metaModel().get( "onnx" ).getAsString() );
		assertEquals( 13.0, model.contract().metaModel().getAsJsonObject( "config" ).getAsJsonArray( "extents" ).get( 1 ).getAsDouble() );

		final float[] patches = TestOnnxModels.randomPatches( 4, 2, 4, 9 );
		final float[] vectors = model.embed( patches, 4 );
		final double[] reference = TestOnnxModels.reference( tiny, patches, 4 );
		for ( int i = 0; i < 4 * 6; ++i )
			assertEquals( reference[ i ], vectors[ i ], 1e-4 );

		// without the sidecar nothing loads
		Files.delete( EmbeddingModel.sidecar( onnx ) );
		final java.io.IOException e = assertThrows( java.io.IOException.class, () -> EmbeddingModel.load( onnx ) );
		assertTrue( e.getMessage().contains( "sidecar" ), e.getMessage() );
	}

	/** pcmatch's exported model on its ds1 fixture, when both are on this machine (otherwise skipped) */
	@Test
	public void reproducesPcmatchsFixture() throws Exception
	{
		final Path onnx = Paths.get( System.getProperty( "user.home" ), "python/pcmatch/models/translation-stack_sigma5-30_mask_floor_ds1345678_p2000_s0_local_neg3r500.onnx" );
		final Path fixture = Paths.get( System.getProperty( "user.home" ), "Documents/pcmatch_data/ds1_java_matching/_java_fixture/onnx_ds1_setup0.n5" );
		assumeTrue( Files.isRegularFile( onnx ) && Files.exists( EmbeddingModel.sidecar( onnx ) ) && Files.isDirectory( fixture ), "pcmatch's model and fixture are not on this machine" );

		final EmbeddingModel model = EmbeddingModel.load( onnx );
		assertEquals( "6844e0e3f80c8eb2217a2021ab0c3215cddc49e11cd78269e47c47fc2ff0cb75", model.contract().fingerprint() );
		assertEquals( 5, model.contract().channels() );
		assertEquals( 16, model.contract().patch() );
		assertEquals( 64, model.contract().outputs() );

		try ( final N5FSReader n5 = new N5FSReader( fixture.toString() ) )
		{
			final RandomAccessibleInterval< FloatType > patches = N5Utils.open( n5, "patches" ); // java order [x, y, z, c, n]
			final RandomAccessibleInterval< FloatType > descriptor = N5Utils.open( n5, "descriptor" ); // [d, n]
			final int n = (int) patches.dimension( 4 );
			final float[] in = new float[ n * model.contract().patchSize() ];
			final RandomAccess< FloatType > ra = patches.randomAccess();
			int i = 0;
			for ( int pn = 0; pn < n; ++pn )
				for ( int c = 0; c < 5; ++c )
					for ( int z = 0; z < 16; ++z )
						for ( int y = 0; y < 16; ++y )
							for ( int x = 0; x < 16; ++x )
							{
								ra.setPosition( new int[] { x, y, z, c, pn } );
								in[ i++ ] = ra.get().get();
							}

			final float[] vectors = model.embed( in, n );
			final RandomAccess< FloatType > rd = descriptor.randomAccess();
			double maxDiff = 0, minCosine = 1;
			for ( int pn = 0; pn < n; ++pn )
			{
				double dot = 0, na = 0, nb = 0;
				for ( int d = 0; d < 64; ++d )
				{
					rd.setPosition( new int[] { d, pn } );
					final double x = vectors[ pn * 64 + d ], y = rd.get().get();
					maxDiff = Math.max( maxDiff, Math.abs( x - y ) );
					dot += x * y;
					na += x * x;
					nb += y * y;
				}
				minCosine = Math.min( minCosine, dot / Math.sqrt( na * nb ) );
			}
			assertTrue( maxDiff < 1e-5, "max |diff| to the fixture's descriptors: " + maxDiff );
			assertTrue( minCosine > 0.99999, "min cosine to the fixture's descriptors: " + minCosine );
		}
	}
}
