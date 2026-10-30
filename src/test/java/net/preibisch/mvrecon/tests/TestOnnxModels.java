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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.preibisch.mvrecon.process.interestpointembedding.EmbeddingModel;

/**
 * Writes small ONNX models for the tests of the Java evaluator and the embedding, as {@code torch.onnx.export} would
 * (protobuf wire format, written here without a protobuf library): a tiny encoder of pcmatch's shape (standardisation
 * over the finite samples, one Conv-ReLU-MaxPool block, a linear layer, L2 normalisation) with its sidecar.
 */
public class TestOnnxModels
{
	/** A protobuf message under construction (varint, fixed32, length-delimited fields). */
	public static class Proto
	{
		final ByteArrayOutputStream out = new ByteArrayOutputStream();

		public Proto varint( final int field, final long value ) { tag( field, 0 ); writeVarint( value ); return this; }

		public Proto fixed32( final int field, final float value )
		{
			tag( field, 5 );
			final int bits = Float.floatToIntBits( value );
			for ( int k = 0; k < 4; ++k )
				out.write( ( bits >>> ( 8 * k ) ) & 0xff );
			return this;
		}

		public Proto bytes( final int field, final byte[] b )
		{
			tag( field, 2 );
			writeVarint( b.length );
			out.write( b, 0, b.length );
			return this;
		}

		public Proto string( final int field, final String s ) { return bytes( field, s.getBytes( StandardCharsets.UTF_8 ) ); }

		public Proto message( final int field, final Proto m ) { return bytes( field, m.toByteArray() ); }

		public byte[] toByteArray() { return out.toByteArray(); }

		void tag( final int field, final int wire ) { writeVarint( ( (long) field << 3 ) | wire ); }

		void writeVarint( long v )
		{
			while ( ( v & ~0x7FL ) != 0 )
			{
				out.write( (int) ( ( v & 0x7F ) | 0x80 ) );
				v >>>= 7;
			}
			out.write( (int) v );
		}
	}

	// AttributeProto: name 1, f 2, i 3, s 4, t 5, ints 8, type 20
	public static Proto attrInt( final String name, final long v ) { return new Proto().string( 1, name ).varint( 3, v ).varint( 20, 2 ); }

	public static Proto attrInts( final String name, final long... v )
	{
		final Proto p = new Proto().string( 1, name );
		for ( final long x : v )
			p.varint( 8, x );
		return p.varint( 20, 7 );
	}

	public static Proto attrFloat( final String name, final float f ) { return new Proto().string( 1, name ).fixed32( 2, f ).varint( 20, 1 ); }

	public static Proto attrString( final String name, final String s ) { return new Proto().string( 1, name ).string( 4, s ).varint( 20, 3 ); }

	public static Proto attrTensor( final String name, final Proto tensor ) { return new Proto().string( 1, name ).message( 5, tensor ).varint( 20, 4 ); }

	// NodeProto: input 1, output 2, name 3, op_type 4, attribute 5
	public static Proto node( final String opType, final String[] inputs, final String[] outputs, final Proto... attributes )
	{
		final Proto p = new Proto();
		for ( final String in : inputs )
			p.string( 1, in );
		for ( final String out : outputs )
			p.string( 2, out );
		p.string( 3, opType + "_" + outputs[ 0 ] ).string( 4, opType );
		for ( final Proto a : attributes )
			p.message( 5, a );
		return p;
	}

	// TensorProto: dims 1, data_type 2, name 8, raw_data 9
	public static Proto floatTensor( final String name, final long[] dims, final float[] data )
	{
		final Proto p = new Proto();
		for ( final long d : dims )
			p.varint( 1, d );
		final ByteBuffer b = ByteBuffer.allocate( 4 * data.length ).order( ByteOrder.LITTLE_ENDIAN );
		for ( final float f : data )
			b.putFloat( f );
		return p.varint( 2, 1 ).string( 8, name ).bytes( 9, b.array() );
	}

	public static Proto int64Tensor( final String name, final long[] dims, final long[] data )
	{
		final Proto p = new Proto();
		for ( final long d : dims )
			p.varint( 1, d );
		final ByteBuffer b = ByteBuffer.allocate( 8 * data.length ).order( ByteOrder.LITTLE_ENDIAN );
		for ( final long l : data )
			b.putLong( l );
		return p.varint( 2, 7 ).string( 8, name ).bytes( 9, b.array() );
	}

	// ValueInfoProto: name 1, type 2 { tensor_type 1 { elem_type 1, shape 2 { dim 1 { dim_value 1 | dim_param 2 } } } }
	public static Proto valueInfo( final String name, final int elemType, final long... dims )
	{
		final Proto shape = new Proto();
		for ( final long d : dims )
			shape.message( 1, d < 0 ? new Proto().string( 2, "N" ) : new Proto().varint( 1, d ) );
		final Proto tensor = new Proto().varint( 1, elemType ).message( 2, shape );
		return new Proto().string( 1, name ).message( 2, new Proto().message( 1, tensor ) );
	}

	// ModelProto: ir_version 1, producer_name 2, graph 7 { node 1, name 2, initializer 5, input 11, output 12 }, opset_import 8 { domain 1, version 2 }, metadata_props 14 { key 1, value 2 }
	public static byte[] model( final List< Proto > nodes, final List< Proto > initializers, final List< Proto > inputs, final List< Proto > outputs, final long opset, final String[]... metadata )
	{
		final Proto graph = new Proto();
		for ( final Proto n : nodes )
			graph.message( 1, n );
		graph.string( 2, "test_graph" );
		for ( final Proto t : initializers )
			graph.message( 5, t );
		for ( final Proto v : inputs )
			graph.message( 11, v );
		for ( final Proto v : outputs )
			graph.message( 12, v );

		final Proto m = new Proto().varint( 1, 10 ).string( 2, "mvr-test" ).message( 7, graph ).message( 8, new Proto().string( 1, "" ).varint( 2, opset ) );
		for ( final String[] kv : metadata )
			m.message( 14, new Proto().string( 1, kv[ 0 ] ).string( 2, kv[ 1 ] ) );
		return m.toByteArray();
	}

	/**
	 * A tiny encoder of pcmatch's shape and its weights: input {@code patches [N, channels, patch^3]} (NaN outside the image),
	 * standardisation per (point, channel) over the finite samples (mean, unbiased std clamped at 1e-6, NaN -> 0), Conv 3^3
	 * (pads 1, {@code convOut} channels) + ReLU + MaxPool 2^3, Gemm to {@code outputs}, L2 normalisation; output
	 * {@code descriptor [N, outputs]}.
	 */
	public record Tiny( int channels, int patch, int convOut, int outputs, float[] w1, float[] b1, float[] w2, float[] b2, byte[] bytes, String fingerprint )
	{
		public int flat() { return convOut * ( patch / 2 ) * ( patch / 2 ) * ( patch / 2 ); }
	}

	public static Tiny tiny( final int channels, final int patch, final int convOut, final int outputs, final long seed )
	{
		final Random rnd = new Random( seed );
		final int volume = patch * patch * patch, flat = convOut * ( patch / 2 ) * ( patch / 2 ) * ( patch / 2 );
		final float[] w1 = gaussian( rnd, convOut * channels * 27, 0.2 ), b1 = gaussian( rnd, convOut, 0.1 );
		final float[] w2 = gaussian( rnd, outputs * flat, 0.3 ), b2 = gaussian( rnd, outputs, 0.1 );

		final List< Proto > init = new ArrayList<>();
		init.add( floatTensor( "w1", new long[] { convOut, channels, 3, 3, 3 }, w1 ) );
		init.add( floatTensor( "b1", new long[] { convOut }, b1 ) );
		init.add( floatTensor( "w2", new long[] { outputs, flat }, w2 ) );
		init.add( floatTensor( "b2", new long[] { outputs }, b2 ) );
		init.add( int64Tensor( "shape_rows", new long[] { 2 }, new long[] { -1, volume } ) );
		init.add( int64Tensor( "shape_cube", new long[] { 5 }, new long[] { -1, channels, patch, patch, patch } ) );
		init.add( int64Tensor( "shape_flat", new long[] { 2 }, new long[] { -1, flat } ) );
		init.add( int64Tensor( "axis1", new long[] { 1 }, new long[] { 1 } ) );
		init.add( int64Tensor( "axis_last", new long[] { 1 }, new long[] { -1 } ) );
		init.add( int64Tensor( "outputs", new long[] { 1 }, new long[] { outputs } ) );
		init.add( floatTensor( "zero", new long[ 0 ], new float[] { 0f } ) );
		init.add( floatTensor( "one", new long[ 0 ], new float[] { 1f } ) );
		init.add( floatTensor( "two", new long[ 0 ], new float[] { 2f } ) );
		init.add( floatTensor( "eps", new long[ 0 ], new float[] { 1e-6f } ) );
		init.add( floatTensor( "eps_norm", new long[ 0 ], new float[] { 1e-12f } ) );

		final List< Proto > nodes = new ArrayList<>();
		nodes.add( node( "Shape", new String[] { "patches" }, new String[] { "n" }, attrInt( "start", 0 ), attrInt( "end", 1 ) ) );
		nodes.add( node( "Reshape", new String[] { "patches", "shape_rows" }, new String[] { "rows" }, attrInt( "allowzero", 1 ) ) );
		nodes.add( node( "IsInf", new String[] { "rows" }, new String[] { "inf" } ) );
		nodes.add( node( "Not", new String[] { "inf" }, new String[] { "not_inf" } ) );
		nodes.add( node( "IsNaN", new String[] { "rows" }, new String[] { "nan" } ) );
		nodes.add( node( "Not", new String[] { "nan" }, new String[] { "not_nan" } ) );
		nodes.add( node( "And", new String[] { "not_inf", "not_nan" }, new String[] { "finite" } ) );
		nodes.add( node( "Cast", new String[] { "finite" }, new String[] { "finite_f" }, attrInt( "to", 1 ) ) );
		nodes.add( node( "ReduceSum", new String[] { "finite_f", "axis1" }, new String[] { "count" }, attrInt( "keepdims", 1 ) ) );
		nodes.add( node( "Clip", new String[] { "count", "one" }, new String[] { "count1" } ) );
		nodes.add( node( "Shape", new String[] { "rows" }, new String[] { "rows_shape" } ) );
		nodes.add( node( "Expand", new String[] { "zero", "rows_shape" }, new String[] { "zeros" } ) );
		nodes.add( node( "Where", new String[] { "finite", "rows", "zeros" }, new String[] { "masked" } ) );
		nodes.add( node( "ReduceSum", new String[] { "masked", "axis1" }, new String[] { "sum" }, attrInt( "keepdims", 1 ) ) );
		nodes.add( node( "Div", new String[] { "sum", "count1" }, new String[] { "mean" } ) );
		nodes.add( node( "Sub", new String[] { "masked", "mean" }, new String[] { "centred" } ) );
		nodes.add( node( "Pow", new String[] { "centred", "two" }, new String[] { "squared" } ) );
		nodes.add( node( "Mul", new String[] { "squared", "finite_f" }, new String[] { "squared_masked" } ) );
		nodes.add( node( "ReduceSum", new String[] { "squared_masked", "axis1" }, new String[] { "ss" }, attrInt( "keepdims", 1 ) ) );
		nodes.add( node( "Sub", new String[] { "count1", "one" }, new String[] { "count_m1" } ) );
		nodes.add( node( "Clip", new String[] { "count_m1", "one" }, new String[] { "count_m1c" } ) );
		nodes.add( node( "Div", new String[] { "ss", "count_m1c" }, new String[] { "var" } ) );
		nodes.add( node( "Sqrt", new String[] { "var" }, new String[] { "sd" } ) );
		nodes.add( node( "Clip", new String[] { "sd", "eps" }, new String[] { "sdc" } ) );
		nodes.add( node( "Div", new String[] { "centred", "sdc" }, new String[] { "std" } ) );
		nodes.add( node( "Where", new String[] { "finite", "std", "zeros" }, new String[] { "std0" } ) );
		nodes.add( node( "Reshape", new String[] { "std0", "shape_cube" }, new String[] { "cube" }, attrInt( "allowzero", 1 ) ) );
		nodes.add( node( "Conv", new String[] { "cube", "w1", "b1" }, new String[] { "conv" }, attrInts( "kernel_shape", 3, 3, 3 ), attrInts( "pads", 1, 1, 1, 1, 1, 1 ), attrInts( "strides", 1, 1, 1 ), attrInts( "dilations", 1, 1, 1 ), attrInt( "group", 1 ), attrString( "auto_pad", "NOTSET" ) ) );
		nodes.add( node( "Relu", new String[] { "conv" }, new String[] { "relu" } ) );
		nodes.add( node( "MaxPool", new String[] { "relu" }, new String[] { "pool" }, attrInts( "kernel_shape", 2, 2, 2 ), attrInts( "strides", 2, 2, 2 ), attrInts( "pads", 0, 0, 0, 0, 0, 0 ), attrInt( "ceil_mode", 0 ) ) );
		nodes.add( node( "Reshape", new String[] { "pool", "shape_flat" }, new String[] { "flat" }, attrInt( "allowzero", 1 ) ) );
		nodes.add( node( "Gemm", new String[] { "flat", "w2", "b2" }, new String[] { "linear" }, attrFloat( "alpha", 1f ), attrFloat( "beta", 1f ), attrInt( "transA", 0 ), attrInt( "transB", 1 ) ) );
		nodes.add( node( "ReduceL2", new String[] { "linear", "axis_last" }, new String[] { "norm" }, attrInt( "keepdims", 1 ) ) );
		nodes.add( node( "Clip", new String[] { "norm", "eps_norm" }, new String[] { "normc" } ) );
		nodes.add( node( "Concat", new String[] { "n", "outputs" }, new String[] { "out_shape" }, attrInt( "axis", 0 ) ) );
		nodes.add( node( "Expand", new String[] { "normc", "out_shape" }, new String[] { "normx" } ) );
		nodes.add( node( "Div", new String[] { "linear", "normx" }, new String[] { "descriptor" } ) );

		final byte[] bytes = model( nodes, init, List.of( valueInfo( "patches", 1, -1, channels, patch, patch, patch ) ), List.of( valueInfo( "descriptor", 1, -1, outputs ) ), 18, new String[] { "producer", "TestOnnxModels" } );

		return new Tiny( channels, patch, convOut, outputs, w1, b1, w2, b2, bytes, "tiny-" + seed + "-" + channels + "x" + patch + "-" + outputs );
	}

	static float[] gaussian( final Random rnd, final int n, final double scale )
	{
		final float[] v = new float[ n ];
		for ( int i = 0; i < n; ++i )
			v[ i ] = (float) ( rnd.nextGaussian() * scale );
		return v;
	}

	/** The sidecar of a model, as pcmatch's {@code export-onnx} writes it (the fields {@link EmbeddingModel#contractOf} reads). */
	public static JsonObject sidecar( final String onnxName, final double[] extents, final int patch, final int outputs, final String fingerprint, final String readFloor )
	{
		final JsonObject json = new JsonObject();
		json.addProperty( "onnx", onnxName );
		json.addProperty( "checkpoint", onnxName.replace( ".onnx", ".pt" ) );
		json.addProperty( "fingerprint", fingerprint );
		final JsonObject config = new JsonObject();
		final JsonArray ext = new JsonArray();
		for ( final double e : extents )
			ext.add( e );
		config.add( "extents", ext );
		config.addProperty( "extent_units", "sigma" );
		config.addProperty( "border", "mask" );
		config.addProperty( "min_valid", 0.5 );
		config.addProperty( "patch", patch );
		config.addProperty( "emb_dim", outputs );
		config.addProperty( "fusion", "stack" );
		config.addProperty( "head_arch", "linear" );
		config.addProperty( "head_dim", outputs );
		config.addProperty( "read_floor", readFloor );
		config.addProperty( "rotation_invariant", false );
		json.add( "config", config );
		final JsonObject input = new JsonObject();
		input.addProperty( "name", "patches" );
		input.addProperty( "dtype", "float32" );
		final JsonArray inShape = new JsonArray();
		inShape.add( "N" );
		inShape.add( extents.length );
		inShape.add( patch );
		inShape.add( patch );
		inShape.add( patch );
		input.add( "shape", inShape );
		json.add( "input", input );
		final JsonObject output = new JsonObject();
		output.addProperty( "name", "descriptor" );
		output.addProperty( "dtype", "float32" );
		final JsonArray outShape = new JsonArray();
		outShape.add( "N" );
		outShape.add( outputs );
		output.add( "shape", outShape );
		json.add( "output", output );
		json.addProperty( "opset", 18 );
		return json;
	}

	/** Writes a tiny model and its sidecar; the extents give the channels. */
	public static Tiny write( final Path onnx, final double[] extents, final int patch, final int convOut, final int outputs, final long seed ) throws IOException
	{
		final Tiny tiny = tiny( extents.length, patch, convOut, outputs, seed );
		Files.write( onnx, tiny.bytes() );
		Files.writeString( EmbeddingModel.sidecar( onnx ), sidecar( onnx.getFileName().toString(), extents, patch, outputs, tiny.fingerprint(), "detection" ).toString() );
		return tiny;
	}

	/**
	 * The tiny encoder computed directly in double: the reference the evaluator is compared with.
	 *
	 * @return {@code n x outputs}
	 */
	public static double[] reference( final Tiny t, final float[] patches, final int n )
	{
		final int C = t.channels(), P = t.patch(), V = P * P * P, M = t.convOut(), H = P / 2, flat = t.flat();
		final double[] result = new double[ n * t.outputs() ];
		final double[] cube = new double[ C * V ];
		final double[] conv = new double[ M * V ];
		final double[] pooled = new double[ flat ];

		for ( int i = 0; i < n; ++i )
		{
			// standardise per channel over the finite samples
			for ( int c = 0; c < C; ++c )
			{
				int count = 0;
				double sum = 0;
				for ( int v = 0; v < V; ++v )
				{
					final float x = patches[ ( i * C + c ) * V + v ];
					if ( Float.isFinite( x ) )
					{
						++count;
						sum += x;
					}
				}
				final double mean = sum / Math.max( 1, count );
				double ss = 0;
				for ( int v = 0; v < V; ++v )
				{
					final float x = patches[ ( i * C + c ) * V + v ];
					if ( Float.isFinite( x ) )
						ss += ( x - mean ) * ( x - mean );
				}
				final double sd = Math.max( 1e-6, Math.sqrt( ss / Math.max( 1, count - 1 ) ) );
				for ( int v = 0; v < V; ++v )
				{
					final float x = patches[ ( i * C + c ) * V + v ];
					cube[ c * V + v ] = Float.isFinite( x ) ? ( x - mean ) / sd : 0;
				}
			}

			// conv 3^3, pads 1, relu
			for ( int m = 0; m < M; ++m )
				for ( int z = 0; z < P; ++z )
					for ( int y = 0; y < P; ++y )
						for ( int x = 0; x < P; ++x )
						{
							double s = t.b1()[ m ];
							for ( int c = 0; c < C; ++c )
								for ( int kz = 0; kz < 3; ++kz )
									for ( int ky = 0; ky < 3; ++ky )
										for ( int kx = 0; kx < 3; ++kx )
										{
											final int iz = z + kz - 1, iy = y + ky - 1, ix = x + kx - 1;
											if ( iz < 0 || iz >= P || iy < 0 || iy >= P || ix < 0 || ix >= P )
												continue;
											s += t.w1()[ ( ( m * C + c ) * 3 + kz ) * 9 + ky * 3 + kx ] * cube[ ( c * P + iz ) * P * P + iy * P + ix ];
										}
							conv[ ( m * P + z ) * P * P + y * P + x ] = Math.max( 0, s );
						}

			// max pool 2^3
			for ( int m = 0; m < M; ++m )
				for ( int z = 0; z < H; ++z )
					for ( int y = 0; y < H; ++y )
						for ( int x = 0; x < H; ++x )
						{
							double max = Double.NEGATIVE_INFINITY;
							for ( int kz = 0; kz < 2; ++kz )
								for ( int ky = 0; ky < 2; ++ky )
									for ( int kx = 0; kx < 2; ++kx )
										max = Math.max( max, conv[ ( m * P + 2 * z + kz ) * P * P + ( 2 * y + ky ) * P + 2 * x + kx ] );
							pooled[ ( m * H + z ) * H * H + y * H + x ] = max;
						}

			// linear, normalise
			final double[] lin = new double[ t.outputs() ];
			double norm = 0;
			for ( int o = 0; o < t.outputs(); ++o )
			{
				double s = t.b2()[ o ];
				for ( int k = 0; k < flat; ++k )
					s += t.w2()[ o * flat + k ] * pooled[ k ];
				lin[ o ] = s;
				norm += s * s;
			}
			norm = Math.max( 1e-12, Math.sqrt( norm ) );
			for ( int o = 0; o < t.outputs(); ++o )
				result[ i * t.outputs() + o ] = lin[ o ] / norm;
		}

		return result;
	}

	/** random patches with a few NaN borders, as the extractor produces them */
	public static float[] randomPatches( final int n, final int channels, final int patch, final long seed )
	{
		final Random rnd = new Random( seed );
		final int V = patch * patch * patch;
		final float[] p = new float[ n * channels * V ];
		for ( int i = 0; i < n; ++i )
			for ( int c = 0; c < channels; ++c )
			{
				final int cut = rnd.nextInt( 4 ) == 0 ? rnd.nextInt( patch ) : -1; // some cubes are truncated along z
				for ( int v = 0; v < V; ++v )
				{
					final int z = v / ( patch * patch );
					p[ ( i * channels + c ) * V + v ] = z <= cut ? Float.NaN : (float) ( 100 + 50 * rnd.nextGaussian() );
				}
			}
		return p;
	}

	public static String describe( final Tiny t ) { return "tiny encoder " + t.channels() + " x " + t.patch() + "^3 -> conv " + t.convOut() + " -> " + t.outputs() + ", " + t.bytes().length + " bytes, " + Arrays.toString( new int[] { t.w1().length, t.w2().length } ) + " weights"; }
}
