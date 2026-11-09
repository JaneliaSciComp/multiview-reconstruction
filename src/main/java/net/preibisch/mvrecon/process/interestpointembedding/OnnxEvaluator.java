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
package net.preibisch.mvrecon.process.interestpointembedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.preibisch.mvrecon.process.interestpointembedding.OnnxModel.Node;

/**
 * Evaluates an {@link OnnxModel} in plain Java: a small interpreter for the operators a feed-forward encoder exported from
 * PyTorch consists of (the per-patch standardisation as elementwise and reduce ops, 3-D convolutions, ReLU, max pooling,
 * a linear layer and the L2 normalisation), float32 throughout, with numpy-style broadcasting. Anything the model needs
 * beyond {@link #SUPPORTED_OPS}, or a form of these ops it does not cover (grouped or dilated or strided convolutions,
 * 1-D/2-D convolutions, padded pooling, other data types), is refused in the constructor with the operator named: such a
 * model needs ONNX Runtime behind the same {@link EmbeddingModel} interface, it is never approximated.
 *
 * One evaluator serves any number of threads ({@link #run} keeps all its state local). Values are released as soon as
 * their last consumer ran, so the live set of a batch stays near the largest layer.
 *
 * @author Stephan Preibisch
 */
public class OnnxEvaluator
{
	/** The operators this evaluator implements (default ONNX domain). */
	public static final Set< String > SUPPORTED_OPS = Set.of(
			"Add", "And", "BatchNormalization", "Cast", "Clip", "Concat", "Constant", "Conv", "Div", "Expand", "Flatten", "Gemm",
			"Identity", "IsInf", "IsNaN", "MaxPool", "Mul", "Not", "Pow", "ReduceL2", "ReduceMean", "ReduceSum", "Relu", "Reshape",
			"Shape", "Sqrt", "Sub", "Where" );

	/** the default-domain opsets whose forms of these operators are implemented (axes of the reductions as attribute or as input, Clip bounds as inputs, Shape with start/end) */
	public static final long MIN_OPSET = 11, MAX_OPSET = 22;

	/** A value during evaluation: float32, int64 or bool data with a shape; arrays are never modified in place, so they may be shared between values. */
	public static final class Tensor
	{
		public final int type;
		public final int[] shape;
		public final float[] f;
		public final long[] l;
		public final boolean[] b;

		private Tensor( final int type, final int[] shape, final float[] f, final long[] l, final boolean[] b )
		{
			this.type = type;
			this.shape = shape;
			this.f = f;
			this.l = l;
			this.b = b;

			final int n = OnnxEvaluator.size( shape );
			final int length = f != null ? f.length : l != null ? l.length : b.length;
			if ( length < n )
				throw new IllegalArgumentException( "a tensor of shape " + Arrays.toString( shape ) + " needs " + n + " values, got " + length );
		}

		public static Tensor floats( final int[] shape, final float[] data ) { return new Tensor( OnnxModel.FLOAT, shape, data, null, null ); }

		public static Tensor longs( final int[] shape, final long[] data ) { return new Tensor( OnnxModel.INT64, shape, null, data, null ); }

		public static Tensor bools( final int[] shape, final boolean[] data ) { return new Tensor( OnnxModel.BOOL, shape, null, null, data ); }

		/** @return the value with another shape of the same size, sharing the data */
		public Tensor reshaped( final int[] newShape )
		{
			if ( OnnxEvaluator.size( newShape ) != size() )
				throw new IllegalArgumentException( "cannot reshape " + Arrays.toString( shape ) + " to " + Arrays.toString( newShape ) );
			return new Tensor( type, newShape, f, l, b );
		}

		public int rank() { return shape.length; }

		public int size() { return OnnxEvaluator.size( shape ); }

		@Override
		public String toString() { return typeName( type ) + Arrays.toString( shape ); }
	}

	private final OnnxModel model;
	private final Map< String, Tensor > constants = new HashMap<>();
	private final Map< String, Integer > uses = new HashMap<>();
	private final List< String > outputNames = new ArrayList<>();

	/**
	 * @param model the model to evaluate
	 * @throws IllegalArgumentException if the model uses an operator, an operator form, a data type or an opset this evaluator does not implement
	 */
	public OnnxEvaluator( final OnnxModel model )
	{
		this.model = model;

		final Long opset = model.getOpsets().get( "" ) != null ? model.getOpsets().get( "" ) : model.getOpsets().get( "ai.onnx" );
		if ( opset == null )
			throw new IllegalArgumentException( "the model imports no default ONNX opset (domains " + model.getOpsets().keySet() + "); this evaluator needs opset " + MIN_OPSET + ".." + MAX_OPSET + " (use ONNX Runtime)" );
		if ( opset < MIN_OPSET || opset > MAX_OPSET )
			throw new IllegalArgumentException( "the model uses ONNX opset " + opset + ", this evaluator implements opset " + MIN_OPSET + ".." + MAX_OPSET + " (use ONNX Runtime)" );

		for ( final Map.Entry< String, OnnxModel.Tensor > e : model.getGraph().initializers().entrySet() )
			constants.put( e.getKey(), constant( e.getValue() ) );

		final Set< String > known = new HashSet<>( constants.keySet() );
		for ( final OnnxModel.ValueInfo input : model.getGraph().inputs() )
			known.add( input.name() );

		final TreeSet< String > unsupported = new TreeSet<>();
		for ( final Node node : model.getGraph().nodes() )
		{
			if ( !( node.domain().isEmpty() || node.domain().equals( "ai.onnx" ) ) )
				unsupported.add( node.domain() + "." + node.opType() );
			else if ( !SUPPORTED_OPS.contains( node.opType() ) )
				unsupported.add( node.opType() );

			for ( final String input : node.inputs() )
			{
				if ( input.isEmpty() )
					continue; // an optional input left out
				if ( !known.contains( input ) )
					throw new IllegalArgumentException( "node '" + node.name() + "' (" + node.opType() + ") uses the value '" + input + "' before it is defined; the nodes of the graph are not in topological order" );
				uses.merge( input, 1, Integer::sum );
			}

			for ( final String output : node.outputs() )
				if ( !output.isEmpty() )
					known.add( output );
		}

		if ( !unsupported.isEmpty() )
			throw new IllegalArgumentException( "the model uses the operator(s) " + unsupported + ", which this evaluator does not implement (it implements " + new TreeSet<>( SUPPORTED_OPS ) + "); the model needs ONNX Runtime" );

		for ( final OnnxModel.ValueInfo output : model.getGraph().outputs() )
		{
			if ( !known.contains( output.name() ) )
				throw new IllegalArgumentException( "the graph output '" + output.name() + "' is produced by no node" );
			outputNames.add( output.name() );
			uses.merge( output.name(), 1, Integer::sum );
		}
	}

	public OnnxModel getModel() { return model; }

	/** @return the names of the graph's outputs, in the graph's order */
	public List< String > getOutputNames() { return outputNames; }

	/**
	 * Evaluates the graph.
	 *
	 * @param inputs the graph inputs by name
	 * @return the graph outputs by name
	 */
	public Map< String, Tensor > run( final Map< String, Tensor > inputs )
	{
		final Map< String, Tensor > values = new HashMap<>();
		for ( final OnnxModel.ValueInfo input : model.getGraph().inputs() )
		{
			if ( constants.containsKey( input.name() ) )
				continue; // an initializer that is also listed as a graph input (older exporters)
			final Tensor t = inputs.get( input.name() );
			if ( t == null )
				throw new IllegalArgumentException( "the graph input '" + input.name() + "' is missing (given: " + inputs.keySet() + ")" );
			if ( input.elemType() != 0 && input.elemType() != t.type )
				throw new IllegalArgumentException( "the graph input '" + input.name() + "' is declared " + typeName( input.elemType() ) + ", got " + typeName( t.type ) );
			values.put( input.name(), t );
		}

		final Map< String, Integer > remaining = new HashMap<>( uses );

		for ( final Node node : model.getGraph().nodes() )
		{
			final Tensor[] in = new Tensor[ node.inputs().size() ];
			for ( int i = 0; i < in.length; ++i )
			{
				final String name = node.inputs().get( i );
				if ( name.isEmpty() )
					continue;
				in[ i ] = constants.get( name );
				if ( in[ i ] == null )
					in[ i ] = values.get( name );
				if ( in[ i ] == null )
					throw new IllegalStateException( "node '" + node.name() + "' (" + node.opType() + "): its input '" + name + "' has no value" );
			}

			final Tensor[] out;
			try
			{
				out = evaluate( node, in );
			}
			catch ( final RuntimeException e )
			{
				throw new IllegalArgumentException( "ONNX node '" + node.name() + "' (" + node.opType() + ", inputs " + Arrays.toString( in ) + "): " + e.getMessage(), e );
			}

			for ( int i = 0; i < node.outputs().size(); ++i )
			{
				final String name = node.outputs().get( i );
				if ( name.isEmpty() )
					continue;
				if ( i >= out.length || out[ i ] == null )
					throw new IllegalArgumentException( "ONNX node '" + node.name() + "' (" + node.opType() + ") asks for output " + i + " ('" + name + "'), which this evaluator does not produce" );
				values.put( name, out[ i ] );
			}

			// release the inputs this node consumed last
			for ( final String name : node.inputs() )
			{
				if ( name.isEmpty() || constants.containsKey( name ) )
					continue;
				final int left = remaining.merge( name, -1, Integer::sum );
				if ( left <= 0 )
					values.remove( name );
			}
		}

		final Map< String, Tensor > outputs = new LinkedHashMap<>();
		for ( final String name : outputNames )
		{
			final Tensor t = constants.containsKey( name ) ? constants.get( name ) : values.get( name );
			if ( t == null )
				throw new IllegalStateException( "the graph output '" + name + "' was not computed" );
			outputs.put( name, t );
		}
		return outputs;
	}

	// ------------------------------------------------------------------------------------------------
	// the operators
	// ------------------------------------------------------------------------------------------------

	private Tensor[] evaluate( final Node node, final Tensor[] in )
	{
		switch ( node.opType() )
		{
		case "Add": return one( arithmetic( ADD, in[ 0 ], in[ 1 ] ) );
		case "Sub": return one( arithmetic( SUB, in[ 0 ], in[ 1 ] ) );
		case "Mul": return one( arithmetic( MUL, in[ 0 ], in[ 1 ] ) );
		case "Div": return one( arithmetic( DIV, in[ 0 ], in[ 1 ] ) );
		case "Pow": return one( arithmetic( POW, in[ 0 ], in[ 1 ] ) );
		case "And": return one( and( in[ 0 ], in[ 1 ] ) );
		case "Not": return one( not( in[ 0 ] ) );
		case "IsNaN": return one( isNaN( in[ 0 ] ) );
		case "IsInf": return one( isInf( in[ 0 ], node.attr( "detect_positive", 1 ) != 0, node.attr( "detect_negative", 1 ) != 0 ) );
		case "Where": return one( where( in[ 0 ], in[ 1 ], in[ 2 ] ) );
		case "Cast": return one( cast( in[ 0 ], (int) node.attr( "to", 0 ) ) );
		case "Clip": return one( clip( in[ 0 ], in.length > 1 ? in[ 1 ] : null, in.length > 2 ? in[ 2 ] : null ) );
		case "Sqrt": return one( unary( SQRT, in[ 0 ] ) );
		case "Relu": return one( unary( RELU, in[ 0 ] ) );
		case "Identity": return one( in[ 0 ] );
		case "Constant": return one( constantOf( node ) );
		case "Shape": return one( shape( in[ 0 ], node.attr( "start", 0 ), node.attributes().containsKey( "end" ) ? node.attr( "end", 0 ) : Long.MAX_VALUE ) );
		case "Reshape": return one( reshape( in[ 0 ], in[ 1 ], node.attr( "allowzero", 0 ) != 0 ) );
		case "Flatten": return one( flatten( in[ 0 ], (int) node.attr( "axis", 1 ) ) );
		case "Expand": return one( expand( in[ 0 ], shapeArgument( in[ 1 ] ) ) );
		case "Concat": return one( concat( in, (int) node.attr( "axis", 0 ) ) );
		case "ReduceSum": return one( reduce( REDUCE_SUM, in[ 0 ], axes( node, in ), node.attr( "keepdims", 1 ) != 0, node.attr( "noop_with_empty_axes", 0 ) != 0 ) );
		case "ReduceMean": return one( reduce( REDUCE_MEAN, in[ 0 ], axes( node, in ), node.attr( "keepdims", 1 ) != 0, node.attr( "noop_with_empty_axes", 0 ) != 0 ) );
		case "ReduceL2": return one( reduce( REDUCE_L2, in[ 0 ], axes( node, in ), node.attr( "keepdims", 1 ) != 0, node.attr( "noop_with_empty_axes", 0 ) != 0 ) );
		case "Gemm": return one( gemm( in[ 0 ], in[ 1 ], in.length > 2 ? in[ 2 ] : null, node.attr( "alpha", 1.0f ), node.attr( "beta", 1.0f ), node.attr( "transA", 0 ) != 0, node.attr( "transB", 0 ) != 0 ) );
		case "Conv": return one( conv3d( node, in[ 0 ], in[ 1 ], in.length > 2 ? in[ 2 ] : null ) );
		case "MaxPool": return one( maxPool3d( node, in[ 0 ] ) );
		case "BatchNormalization": return one( batchNormalization( in[ 0 ], in[ 1 ], in[ 2 ], in[ 3 ], in[ 4 ], node.attr( "epsilon", 1e-5f ), node.attr( "training_mode", 0 ) != 0 ) );
		default: throw new IllegalArgumentException( "the operator " + node.opType() + " is not implemented" );
		}
	}

	private static Tensor[] one( final Tensor t ) { return new Tensor[] { t }; }

	static final int ADD = 0, SUB = 1, MUL = 2, DIV = 3, POW = 4;
	static final int SQRT = 0, RELU = 1;
	static final int REDUCE_SUM = 0, REDUCE_MEAN = 1, REDUCE_L2 = 2;

	static Tensor constant( final OnnxModel.Tensor t )
	{
		final int[] shape = shapeOf( t.dims() );
		switch ( t.dataType() )
		{
		case OnnxModel.FLOAT:
		case OnnxModel.DOUBLE:
			return Tensor.floats( shape, t.floats() );
		case OnnxModel.INT64:
		case OnnxModel.INT32:
		case OnnxModel.INT8:
		case OnnxModel.UINT8:
			return Tensor.longs( shape, t.longs() );
		case OnnxModel.BOOL:
		{
			final long[] l = t.longs();
			final boolean[] b = new boolean[ l.length ];
			for ( int i = 0; i < b.length; ++i )
				b[ i ] = l[ i ] != 0;
			return Tensor.bools( shape, b );
		}
		default:
			throw new IllegalArgumentException( "the tensor '" + t.name() + "' has data type " + t.dataType() + "; this evaluator supports float32, float64 (read as float32), int64, int32, int8, uint8 and bool" );
		}
	}

	private static Tensor constantOf( final Node node )
	{
		final OnnxModel.Attribute value = node.attributes().get( "value" );
		if ( value != null && value.t() != null )
			return constant( value.t() );
		final OnnxModel.Attribute f = node.attributes().get( "value_float" );
		if ( f != null )
			return Tensor.floats( new int[ 0 ], new float[] { f.f() } );
		final OnnxModel.Attribute i = node.attributes().get( "value_int" );
		if ( i != null )
			return Tensor.longs( new int[ 0 ], new long[] { i.i() } );
		final OnnxModel.Attribute fs = node.attributes().get( "value_floats" );
		if ( fs != null )
			return Tensor.floats( new int[] { fs.floats().length }, fs.floats() );
		final OnnxModel.Attribute is = node.attributes().get( "value_ints" );
		if ( is != null )
			return Tensor.longs( new int[] { is.ints().length }, is.ints() );
		throw new IllegalArgumentException( "Constant without a supported value attribute (" + node.attributes().keySet() + ")" );
	}

	private static long[] axes( final Node node, final Tensor[] in )
	{
		if ( in.length > 1 && in[ 1 ] != null )
		{
			if ( in[ 1 ].type != OnnxModel.INT64 )
				throw new IllegalArgumentException( "the axes must be int64, got " + in[ 1 ] );
			return Arrays.copyOf( in[ 1 ].l, in[ 1 ].size() );
		}
		final OnnxModel.Attribute a = node.attributes().get( "axes" );
		return a == null ? null : a.ints();
	}

	private static int[] shapeArgument( final Tensor t )
	{
		if ( t.type != OnnxModel.INT64 || t.rank() != 1 )
			throw new IllegalArgumentException( "a shape must be a 1-D int64 tensor, got " + t );
		final int[] shape = new int[ t.shape[ 0 ] ];
		for ( int i = 0; i < shape.length; ++i )
			shape[ i ] = (int) t.l[ i ];
		return shape;
	}

	// ---- elementwise -------------------------------------------------------------------------------

	private static float apply( final int op, final float x, final float y )
	{
		switch ( op )
		{
		case ADD: return x + y;
		case SUB: return x - y;
		case MUL: return x * y;
		case DIV: return x / y;
		default: return y == 2.0f ? x * x : y == 0.5f ? (float) Math.sqrt( x ) : (float) Math.pow( x, y );
		}
	}

	private static long apply( final int op, final long x, final long y )
	{
		switch ( op )
		{
		case ADD: return x + y;
		case SUB: return x - y;
		case MUL: return x * y;
		case DIV: return x / y;
		default: return (long) Math.pow( x, y );
		}
	}

	static Tensor arithmetic( final int op, final Tensor a, final Tensor b )
	{
		final int[] out = broadcast( a.shape, b.shape );
		final int n = size( out );

		if ( a.type == OnnxModel.FLOAT && b.type == OnnxModel.FLOAT )
		{
			final float[] r = new float[ n ];
			if ( n > 0 )
			{
				final int rank = out.length;
				if ( rank == 0 )
					r[ 0 ] = apply( op, a.f[ 0 ], b.f[ 0 ] );
				else
				{
					final int[] sa = strides( a.shape, out ), sb = strides( b.shape, out );
					final int inner = out[ rank - 1 ], ia = sa[ rank - 1 ], ib = sb[ rank - 1 ];
					final int[] idx = new int[ rank ];
					int oa = 0, ob = 0;
					for ( int o = 0; o < n; o += inner )
					{
						if ( ia == 1 && ib == 1 )
							for ( int x = 0; x < inner; ++x )
								r[ o + x ] = apply( op, a.f[ oa + x ], b.f[ ob + x ] );
						else if ( ia == 1 )
						{
							final float y = b.f[ ob ];
							for ( int x = 0; x < inner; ++x )
								r[ o + x ] = apply( op, a.f[ oa + x ], y );
						}
						else if ( ib == 1 )
						{
							final float x0 = a.f[ oa ];
							for ( int x = 0; x < inner; ++x )
								r[ o + x ] = apply( op, x0, b.f[ ob + x ] );
						}
						else
							Arrays.fill( r, o, o + inner, apply( op, a.f[ oa ], b.f[ ob ] ) );

						for ( int d = rank - 2; d >= 0; --d )
						{
							oa += sa[ d ];
							ob += sb[ d ];
							if ( ++idx[ d ] < out[ d ] )
								break;
							oa -= sa[ d ] * out[ d ];
							ob -= sb[ d ] * out[ d ];
							idx[ d ] = 0;
						}
					}
				}
			}
			return Tensor.floats( out, r );
		}
		else if ( a.type == OnnxModel.INT64 && b.type == OnnxModel.INT64 )
		{
			final long[] r = new long[ n ];
			final int[] sa = strides( a.shape, out ), sb = strides( b.shape, out );
			final int[] idx = new int[ out.length ];
			for ( int o = 0; o < n; ++o )
			{
				int oa = 0, ob = 0;
				for ( int d = 0; d < out.length; ++d )
				{
					oa += idx[ d ] * sa[ d ];
					ob += idx[ d ] * sb[ d ];
				}
				r[ o ] = apply( op, a.l[ oa ], b.l[ ob ] );
				increment( idx, out );
			}
			return Tensor.longs( out, r );
		}
		else
			throw new IllegalArgumentException( "arithmetic on " + a + " and " + b + ": both must be float32 or both int64" );
	}

	static Tensor unary( final int op, final Tensor a )
	{
		requireFloat( a, "the input" );
		final float[] r = new float[ a.size() ];
		if ( op == SQRT )
			for ( int i = 0; i < r.length; ++i )
				r[ i ] = (float) Math.sqrt( a.f[ i ] );
		else
			for ( int i = 0; i < r.length; ++i )
				r[ i ] = Math.max( 0, a.f[ i ] ); // NaN stays NaN only with Math.max( 0, NaN ) = NaN: as ONNX Runtime's Relu
		return Tensor.floats( a.shape, r );
	}

	static Tensor and( final Tensor a, final Tensor b )
	{
		if ( a.type != OnnxModel.BOOL || b.type != OnnxModel.BOOL )
			throw new IllegalArgumentException( "And needs bool inputs, got " + a + " and " + b );
		final int[] out = broadcast( a.shape, b.shape );
		final int n = size( out );
		final boolean[] r = new boolean[ n ];
		final int[] sa = strides( a.shape, out ), sb = strides( b.shape, out );
		final int[] idx = new int[ out.length ];
		for ( int o = 0; o < n; ++o )
		{
			int oa = 0, ob = 0;
			for ( int d = 0; d < out.length; ++d )
			{
				oa += idx[ d ] * sa[ d ];
				ob += idx[ d ] * sb[ d ];
			}
			r[ o ] = a.b[ oa ] && b.b[ ob ];
			increment( idx, out );
		}
		return Tensor.bools( out, r );
	}

	static Tensor not( final Tensor a )
	{
		if ( a.type != OnnxModel.BOOL )
			throw new IllegalArgumentException( "Not needs a bool input, got " + a );
		final boolean[] r = new boolean[ a.size() ];
		for ( int i = 0; i < r.length; ++i )
			r[ i ] = !a.b[ i ];
		return Tensor.bools( a.shape, r );
	}

	static Tensor isNaN( final Tensor a )
	{
		requireFloat( a, "the input" );
		final boolean[] r = new boolean[ a.size() ];
		for ( int i = 0; i < r.length; ++i )
			r[ i ] = Float.isNaN( a.f[ i ] );
		return Tensor.bools( a.shape, r );
	}

	static Tensor isInf( final Tensor a, final boolean positive, final boolean negative )
	{
		requireFloat( a, "the input" );
		final boolean[] r = new boolean[ a.size() ];
		for ( int i = 0; i < r.length; ++i )
			r[ i ] = ( positive && a.f[ i ] == Float.POSITIVE_INFINITY ) || ( negative && a.f[ i ] == Float.NEGATIVE_INFINITY );
		return Tensor.bools( a.shape, r );
	}

	static Tensor where( final Tensor cond, final Tensor x, final Tensor y )
	{
		if ( cond.type != OnnxModel.BOOL )
			throw new IllegalArgumentException( "Where needs a bool condition, got " + cond );
		if ( x.type != y.type )
			throw new IllegalArgumentException( "Where needs branches of one type, got " + x + " and " + y );
		final int[] out = broadcast( broadcast( cond.shape, x.shape ), y.shape );
		final int n = size( out );
		final int[] sc = strides( cond.shape, out ), sx = strides( x.shape, out ), sy = strides( y.shape, out );
		final int rank = out.length;

		if ( x.type == OnnxModel.FLOAT )
		{
			final float[] r = new float[ n ];
			if ( n > 0 )
			{
				if ( rank == 0 )
					r[ 0 ] = cond.b[ 0 ] ? x.f[ 0 ] : y.f[ 0 ];
				else
				{
					final int inner = out[ rank - 1 ], ic = sc[ rank - 1 ], ix = sx[ rank - 1 ], iy = sy[ rank - 1 ];
					final int[] idx = new int[ rank ];
					int oc = 0, ox = 0, oy = 0;
					for ( int o = 0; o < n; o += inner )
					{
						for ( int k = 0; k < inner; ++k )
							r[ o + k ] = cond.b[ oc + k * ic ] ? x.f[ ox + k * ix ] : y.f[ oy + k * iy ];

						for ( int d = rank - 2; d >= 0; --d )
						{
							oc += sc[ d ];
							ox += sx[ d ];
							oy += sy[ d ];
							if ( ++idx[ d ] < out[ d ] )
								break;
							oc -= sc[ d ] * out[ d ];
							ox -= sx[ d ] * out[ d ];
							oy -= sy[ d ] * out[ d ];
							idx[ d ] = 0;
						}
					}
				}
			}
			return Tensor.floats( out, r );
		}
		else if ( x.type == OnnxModel.INT64 )
		{
			final long[] r = new long[ n ];
			final int[] idx = new int[ rank ];
			for ( int o = 0; o < n; ++o )
			{
				int oc = 0, ox = 0, oy = 0;
				for ( int d = 0; d < rank; ++d )
				{
					oc += idx[ d ] * sc[ d ];
					ox += idx[ d ] * sx[ d ];
					oy += idx[ d ] * sy[ d ];
				}
				r[ o ] = cond.b[ oc ] ? x.l[ ox ] : y.l[ oy ];
				increment( idx, out );
			}
			return Tensor.longs( out, r );
		}
		else
			throw new IllegalArgumentException( "Where on " + x + " is not supported" );
	}

	static Tensor cast( final Tensor a, final int to )
	{
		final int n = a.size();
		if ( to == OnnxModel.FLOAT )
		{
			if ( a.type == OnnxModel.FLOAT )
				return a;
			final float[] r = new float[ n ];
			for ( int i = 0; i < n; ++i )
				r[ i ] = a.type == OnnxModel.BOOL ? ( a.b[ i ] ? 1f : 0f ) : a.l[ i ];
			return Tensor.floats( a.shape, r );
		}
		else if ( to == OnnxModel.INT64 )
		{
			if ( a.type == OnnxModel.INT64 )
				return a;
			final long[] r = new long[ n ];
			for ( int i = 0; i < n; ++i )
				r[ i ] = a.type == OnnxModel.BOOL ? ( a.b[ i ] ? 1 : 0 ) : (long) a.f[ i ];
			return Tensor.longs( a.shape, r );
		}
		else if ( to == OnnxModel.BOOL )
		{
			if ( a.type == OnnxModel.BOOL )
				return a;
			final boolean[] r = new boolean[ n ];
			for ( int i = 0; i < n; ++i )
				r[ i ] = a.type == OnnxModel.FLOAT ? a.f[ i ] != 0 : a.l[ i ] != 0;
			return Tensor.bools( a.shape, r );
		}
		else
			throw new IllegalArgumentException( "Cast to data type " + to + " is not supported (float32, int64 and bool are)" );
	}

	static Tensor clip( final Tensor a, final Tensor min, final Tensor max )
	{
		requireFloat( a, "the input" );
		final float lo = min == null ? Float.NEGATIVE_INFINITY : scalar( min, "Clip's min" );
		final float hi = max == null ? Float.POSITIVE_INFINITY : scalar( max, "Clip's max" );
		final float[] r = new float[ a.size() ];
		for ( int i = 0; i < r.length; ++i )
			r[ i ] = Math.min( hi, Math.max( lo, a.f[ i ] ) );
		return Tensor.floats( a.shape, r );
	}

	private static float scalar( final Tensor t, final String what )
	{
		if ( t.type != OnnxModel.FLOAT || t.size() != 1 )
			throw new IllegalArgumentException( what + " must be one float, got " + t );
		return t.f[ 0 ];
	}

	// ---- shapes ------------------------------------------------------------------------------------

	static Tensor shape( final Tensor a, final long start, final long end )
	{
		final int rank = a.rank();
		final int s = (int) Math.max( 0, Math.min( rank, start < 0 ? start + rank : start ) );
		final int e = (int) Math.max( s, Math.min( rank, end == Long.MAX_VALUE ? rank : end < 0 ? end + rank : end ) );
		final long[] r = new long[ e - s ];
		for ( int i = s; i < e; ++i )
			r[ i - s ] = a.shape[ i ];
		return Tensor.longs( new int[] { r.length }, r );
	}

	static Tensor reshape( final Tensor a, final Tensor shapeTensor, final boolean allowZero )
	{
		final int[] requested = shapeArgument( shapeTensor );
		final int[] shape = new int[ requested.length ];
		int inferred = -1;
		long known = 1;
		for ( int d = 0; d < shape.length; ++d )
		{
			if ( requested[ d ] == -1 )
			{
				if ( inferred >= 0 )
					throw new IllegalArgumentException( "Reshape to " + Arrays.toString( requested ) + ": at most one dimension may be -1" );
				inferred = d;
			}
			else if ( requested[ d ] == 0 && !allowZero )
			{
				if ( d >= a.rank() )
					throw new IllegalArgumentException( "Reshape to " + Arrays.toString( requested ) + ": dimension " + d + " copies from the input " + Arrays.toString( a.shape ) + ", which has no such dimension" );
				shape[ d ] = a.shape[ d ];
				known *= shape[ d ];
			}
			else
			{
				shape[ d ] = requested[ d ];
				known *= shape[ d ];
			}
		}
		if ( inferred >= 0 )
		{
			if ( known == 0 || a.size() % known != 0 )
				throw new IllegalArgumentException( "Reshape of " + Arrays.toString( a.shape ) + " to " + Arrays.toString( requested ) + ": the size " + a.size() + " is no multiple of " + known );
			shape[ inferred ] = (int) ( a.size() / known );
		}
		return a.reshaped( shape );
	}

	static Tensor flatten( final Tensor a, final int axisIn )
	{
		final int axis = axisIn < 0 ? axisIn + a.rank() : axisIn;
		int outer = 1, inner = 1;
		for ( int d = 0; d < a.rank(); ++d )
			if ( d < axis ) outer *= a.shape[ d ];
			else inner *= a.shape[ d ];
		return a.reshaped( new int[] { outer, inner } );
	}

	static Tensor expand( final Tensor a, final int[] shape )
	{
		final int[] out = broadcast( a.shape, shape );
		if ( Arrays.equals( out, a.shape ) )
			return a;
		final int n = size( out );
		final int[] sa = strides( a.shape, out );
		final int[] idx = new int[ out.length ];
		if ( a.type == OnnxModel.FLOAT )
		{
			final float[] r = new float[ n ];
			for ( int o = 0; o < n; ++o )
			{
				int oa = 0;
				for ( int d = 0; d < out.length; ++d )
					oa += idx[ d ] * sa[ d ];
				r[ o ] = a.f[ oa ];
				increment( idx, out );
			}
			return Tensor.floats( out, r );
		}
		else if ( a.type == OnnxModel.INT64 )
		{
			final long[] r = new long[ n ];
			for ( int o = 0; o < n; ++o )
			{
				int oa = 0;
				for ( int d = 0; d < out.length; ++d )
					oa += idx[ d ] * sa[ d ];
				r[ o ] = a.l[ oa ];
				increment( idx, out );
			}
			return Tensor.longs( out, r );
		}
		else
		{
			final boolean[] r = new boolean[ n ];
			for ( int o = 0; o < n; ++o )
			{
				int oa = 0;
				for ( int d = 0; d < out.length; ++d )
					oa += idx[ d ] * sa[ d ];
				r[ o ] = a.b[ oa ];
				increment( idx, out );
			}
			return Tensor.bools( out, r );
		}
	}

	static Tensor concat( final Tensor[] in, final int axisIn )
	{
		final int rank = in[ 0 ].rank();
		final int axis = axisIn < 0 ? axisIn + rank : axisIn;
		final int[] out = in[ 0 ].shape.clone();
		out[ axis ] = 0;
		for ( final Tensor t : in )
		{
			if ( t.type != in[ 0 ].type || t.rank() != rank )
				throw new IllegalArgumentException( "Concat needs inputs of one type and rank, got " + Arrays.toString( in ) );
			for ( int d = 0; d < rank; ++d )
				if ( d != axis && t.shape[ d ] != out[ d ] )
					throw new IllegalArgumentException( "Concat along axis " + axis + ": " + Arrays.toString( in ) + " differ in dimension " + d );
			out[ axis ] += t.shape[ axis ];
		}

		int outer = 1, inner = 1;
		for ( int d = 0; d < rank; ++d )
			if ( d < axis ) outer *= out[ d ];
			else if ( d > axis ) inner *= out[ d ];

		final int n = size( out );
		final Object r = in[ 0 ].type == OnnxModel.FLOAT ? new float[ n ] : in[ 0 ].type == OnnxModel.INT64 ? new long[ n ] : new boolean[ n ];
		int offset = 0;
		for ( int o = 0; o < outer; ++o )
			for ( final Tensor t : in )
			{
				final int block = t.shape[ axis ] * inner;
				final Object src = t.type == OnnxModel.FLOAT ? t.f : t.type == OnnxModel.INT64 ? t.l : t.b;
				System.arraycopy( src, o * block, r, offset, block );
				offset += block;
			}
		return in[ 0 ].type == OnnxModel.FLOAT ? Tensor.floats( out, (float[]) r ) : in[ 0 ].type == OnnxModel.INT64 ? Tensor.longs( out, (long[]) r ) : Tensor.bools( out, (boolean[]) r );
	}

	// ---- reductions --------------------------------------------------------------------------------

	static Tensor reduce( final int kind, final Tensor a, final long[] axes, final boolean keepdims, final boolean noopWithEmptyAxes )
	{
		requireFloat( a, "the input" );
		final int rank = a.rank();
		final boolean[] reduced = new boolean[ rank ];

		if ( axes == null || axes.length == 0 )
		{
			if ( noopWithEmptyAxes )
				return a;
			Arrays.fill( reduced, true );
		}
		else
			for ( final long axis : axes )
			{
				final long ax = axis < 0 ? axis + rank : axis;
				if ( ax < 0 || ax >= rank )
					throw new IllegalArgumentException( "axis " + axis + " is out of range for " + a );
				reduced[ (int) ax ] = true;
			}

		final int[] kept = new int[ rank ]; // the output shape with keepdims
		int count = 1;
		for ( int d = 0; d < rank; ++d )
		{
			kept[ d ] = reduced[ d ] ? 1 : a.shape[ d ];
			if ( reduced[ d ] )
				count *= a.shape[ d ];
		}
		final int[] so = strides( kept, a.shape ); // output offset per input index (0 along reduced dims)
		final int outSize = size( kept );
		final double[] acc = new double[ outSize ];
		final int n = a.size();

		if ( n > 0 )
		{
			if ( rank == 0 )
				acc[ 0 ] = kind == REDUCE_L2 ? (double) a.f[ 0 ] * a.f[ 0 ] : a.f[ 0 ];
			else
			{
				final int inner = a.shape[ rank - 1 ], io = so[ rank - 1 ];
				final int[] idx = new int[ rank ];
				int oo = 0;
				for ( int i = 0; i < n; i += inner )
				{
					if ( io == 0 )
					{
						double s = 0;
						if ( kind == REDUCE_L2 )
							for ( int x = 0; x < inner; ++x )
								s += (double) a.f[ i + x ] * a.f[ i + x ];
						else
							for ( int x = 0; x < inner; ++x )
								s += a.f[ i + x ];
						acc[ oo ] += s;
					}
					else
					{
						if ( kind == REDUCE_L2 )
							for ( int x = 0; x < inner; ++x )
								acc[ oo + x ] += (double) a.f[ i + x ] * a.f[ i + x ];
						else
							for ( int x = 0; x < inner; ++x )
								acc[ oo + x ] += a.f[ i + x ];
					}

					for ( int d = rank - 2; d >= 0; --d )
					{
						oo += so[ d ];
						if ( ++idx[ d ] < a.shape[ d ] )
							break;
						oo -= so[ d ] * a.shape[ d ];
						idx[ d ] = 0;
					}
				}
			}
		}

		final float[] r = new float[ outSize ];
		for ( int o = 0; o < outSize; ++o )
			r[ o ] = (float) ( kind == REDUCE_MEAN ? acc[ o ] / count : kind == REDUCE_L2 ? Math.sqrt( acc[ o ] ) : acc[ o ] );

		if ( keepdims )
			return Tensor.floats( kept, r );

		final int[] squeezed = new int[ rank - countTrue( reduced ) ];
		for ( int d = 0, k = 0; d < rank; ++d )
			if ( !reduced[ d ] )
				squeezed[ k++ ] = a.shape[ d ];
		return Tensor.floats( squeezed, r );
	}

	private static int countTrue( final boolean[] b )
	{
		int c = 0;
		for ( final boolean v : b )
			if ( v )
				++c;
		return c;
	}

	// ---- the layers --------------------------------------------------------------------------------

	static Tensor gemm( final Tensor a, final Tensor b, final Tensor c, final float alpha, final float beta, final boolean transA, final boolean transB )
	{
		requireFloat( a, "A" );
		requireFloat( b, "B" );
		if ( a.rank() != 2 || b.rank() != 2 )
			throw new IllegalArgumentException( "Gemm needs matrices, got " + a + " and " + b );
		final int m = transA ? a.shape[ 1 ] : a.shape[ 0 ], k = transA ? a.shape[ 0 ] : a.shape[ 1 ];
		final int kb = transB ? b.shape[ 1 ] : b.shape[ 0 ], n = transB ? b.shape[ 0 ] : b.shape[ 1 ];
		if ( k != kb )
			throw new IllegalArgumentException( "Gemm: the inner dimensions of " + a + ( transA ? "^T" : "" ) + " and " + b + ( transB ? "^T" : "" ) + " differ" );

		final float[] y = new float[ m * n ];
		for ( int i = 0; i < m; ++i )
			for ( int j = 0; j < n; ++j )
			{
				double s = 0;
				if ( !transA && transB )
				{
					final int oa = i * k, ob = j * k;
					for ( int p = 0; p < k; ++p )
						s += (double) a.f[ oa + p ] * b.f[ ob + p ];
				}
				else
					for ( int p = 0; p < k; ++p )
						s += (double) a.f[ transA ? p * m + i : i * k + p ] * b.f[ transB ? j * k + p : p * n + j ];
				y[ i * n + j ] = (float) ( alpha * s );
			}

		if ( c != null && beta != 0 )
		{
			requireFloat( c, "C" );
			final Tensor bias = expand( c, new int[] { m, n } );
			if ( !Arrays.equals( bias.shape, new int[] { m, n } ) )
				throw new IllegalArgumentException( "Gemm: C " + c + " does not broadcast to [" + m + ", " + n + "]" );
			for ( int i = 0; i < y.length; ++i )
				y[ i ] += beta * bias.f[ i ];
		}

		return Tensor.floats( new int[] { m, n }, y );
	}

	static Tensor conv3d( final Node node, final Tensor x, final Tensor w, final Tensor bias )
	{
		requireFloat( x, "the input" );
		requireFloat( w, "the kernel" );
		if ( x.rank() != 5 || w.rank() != 5 )
			throw new IllegalArgumentException( "this evaluator implements 3-D convolutions (input [N, C, D, H, W]), got " + x + " and kernel " + w );

		final long group = node.attr( "group", 1 );
		final long[] dilations = node.attr( "dilations", new long[] { 1, 1, 1 } ), strides = node.attr( "strides", new long[] { 1, 1, 1 } );
		final long[] pads = node.attr( "pads", new long[] { 0, 0, 0, 0, 0, 0 } ), kernel = node.attr( "kernel_shape", new long[] { w.shape[ 2 ], w.shape[ 3 ], w.shape[ 4 ] } );
		final String autoPad = node.attr( "auto_pad", "NOTSET" );
		if ( group != 1 || !all( dilations, 1 ) || !all( strides, 1 ) || !autoPad.equals( "NOTSET" ) || pads.length != 6 )
			throw new IllegalArgumentException( "this evaluator implements convolutions with group 1, dilations 1, strides 1 and explicit pads; got group " + group + ", dilations " + Arrays.toString( dilations ) + ", strides " + Arrays.toString( strides ) + ", auto_pad " + autoPad + ", pads " + Arrays.toString( pads ) );
		if ( kernel.length != 3 || kernel[ 0 ] != w.shape[ 2 ] || kernel[ 1 ] != w.shape[ 3 ] || kernel[ 2 ] != w.shape[ 4 ] )
			throw new IllegalArgumentException( "kernel_shape " + Arrays.toString( kernel ) + " does not match the kernel " + w );

		final int N = x.shape[ 0 ], C = x.shape[ 1 ], D = x.shape[ 2 ], H = x.shape[ 3 ], W = x.shape[ 4 ];
		final int M = w.shape[ 0 ], KD = w.shape[ 2 ], KH = w.shape[ 3 ], KW = w.shape[ 4 ];
		if ( w.shape[ 1 ] != C )
			throw new IllegalArgumentException( "the kernel " + w + " expects " + w.shape[ 1 ] + " channels, the input " + x + " has " + C );
		if ( bias != null && ( bias.type != OnnxModel.FLOAT || bias.size() != M ) )
			throw new IllegalArgumentException( "the bias " + bias + " does not fit " + M + " output channels" );

		final int pz0 = (int) pads[ 0 ], py0 = (int) pads[ 1 ], px0 = (int) pads[ 2 ], pz1 = (int) pads[ 3 ], py1 = (int) pads[ 4 ], px1 = (int) pads[ 5 ];
		final int OD = D + pz0 + pz1 - KD + 1, OH = H + py0 + py1 - KH + 1, OW = W + px0 + px1 - KW + 1;
		if ( OD <= 0 || OH <= 0 || OW <= 0 )
			throw new IllegalArgumentException( "the kernel " + w + " is larger than the padded input " + x );

		// im2col per sample: the zero-padded input is gathered into one array per kernel tap (c, kz, ky, kx) over the output
		// positions, then every output channel is a sum of scaled rows into a fresh accumulator. The inner loop runs over whole
		// arrays from index 0: the C2 JIT of JDK 21 vectorizes a stride-1 loop only when its loads and stores are provably
		// aligned to each other, and an invariant offset (a direct convolution's shift between taps, or a row offset into one
		// large array) leaves it scalar and four times slower (measured 2026-10-30, Threadripper PRO 7985WX)
		final float[] out = new float[ N * M * OD * OH * OW ];
		final float[] in = x.f, k = w.f;
		final int PD = D + pz0 + pz1, PH = H + py0 + py1, PW = W + px0 + px1, padded = PD * PH * PW;
		final int OUT = OD * OH * OW, taps = C * KD * KH * KW;
		final float[] pin = new float[ C * padded ];
		final float[][] col = new float[ taps ][ OUT ];
		final float[] acc = new float[ OUT ];
		final int planeIn = H * W, volumeIn = D * planeIn;

		for ( int n = 0; n < N; ++n )
		{
			if ( padded != volumeIn )
				Arrays.fill( pin, 0 );
			for ( int c = 0; c < C; ++c )
				for ( int z = 0; z < D; ++z )
					for ( int y = 0; y < H; ++y )
						System.arraycopy( in, ( n * C + c ) * volumeIn + z * planeIn + y * W, pin, ( c * PD + z + pz0 ) * PH * PW + ( y + py0 ) * PW + px0, W );

			for ( int c = 0, t = 0; c < C; ++c )
				for ( int kz = 0; kz < KD; ++kz )
					for ( int ky = 0; ky < KH; ++ky )
						for ( int kx = 0; kx < KW; ++kx, ++t )
							for ( int oz = 0; oz < OD; ++oz )
								for ( int oy = 0; oy < OH; ++oy )
									System.arraycopy( pin, ( c * PD + oz + kz ) * PH * PW + ( oy + ky ) * PW + kx, col[ t ], ( oz * OH + oy ) * OW, OW );

			for ( int m = 0; m < M; ++m )
			{
				Arrays.fill( acc, bias == null ? 0 : bias.f[ m ] );

				int t = 0;
				for ( ; t + 3 < taps; t += 4 ) // four taps per pass over the accumulator
				{
					final float w0 = k[ m * taps + t ], w1 = k[ m * taps + t + 1 ], w2 = k[ m * taps + t + 2 ], w3 = k[ m * taps + t + 3 ];
					final float[] r0 = col[ t ], r1 = col[ t + 1 ], r2 = col[ t + 2 ], r3 = col[ t + 3 ];
					for ( int j = 0; j < OUT; ++j )
						acc[ j ] += w0 * r0[ j ] + w1 * r1[ j ] + w2 * r2[ j ] + w3 * r3[ j ];
				}
				for ( ; t < taps; ++t )
				{
					final float wv = k[ m * taps + t ];
					final float[] row = col[ t ];
					for ( int j = 0; j < OUT; ++j )
						acc[ j ] += wv * row[ j ];
				}

				System.arraycopy( acc, 0, out, ( n * M + m ) * OUT, OUT );
			}
		}

		return Tensor.floats( new int[] { N, M, OD, OH, OW }, out );
	}

	static Tensor maxPool3d( final Node node, final Tensor x )
	{
		requireFloat( x, "the input" );
		if ( x.rank() != 5 )
			throw new IllegalArgumentException( "this evaluator implements 3-D max pooling (input [N, C, D, H, W]), got " + x );

		final long[] kernel = node.attr( "kernel_shape", (long[]) null );
		if ( kernel == null || kernel.length != 3 )
			throw new IllegalArgumentException( "MaxPool needs a 3-D kernel_shape, got " + Arrays.toString( kernel ) );
		final long[] strides = node.attr( "strides", new long[] { 1, 1, 1 } ), dilations = node.attr( "dilations", new long[] { 1, 1, 1 } );
		final long[] pads = node.attr( "pads", new long[] { 0, 0, 0, 0, 0, 0 } );
		final String autoPad = node.attr( "auto_pad", "NOTSET" );
		if ( !all( pads, 0 ) || !all( dilations, 1 ) || node.attr( "ceil_mode", 0 ) != 0 || !autoPad.equals( "NOTSET" ) || strides.length != 3 )
			throw new IllegalArgumentException( "this evaluator implements max pooling without padding, dilation or ceil_mode; got pads " + Arrays.toString( pads ) + ", dilations " + Arrays.toString( dilations ) + ", ceil_mode " + node.attr( "ceil_mode", 0 ) + ", auto_pad " + autoPad );

		final int N = x.shape[ 0 ], C = x.shape[ 1 ], D = x.shape[ 2 ], H = x.shape[ 3 ], W = x.shape[ 4 ];
		final int KD = (int) kernel[ 0 ], KH = (int) kernel[ 1 ], KW = (int) kernel[ 2 ];
		final int SD = (int) strides[ 0 ], SH = (int) strides[ 1 ], SW = (int) strides[ 2 ];
		final int OD = ( D - KD ) / SD + 1, OH = ( H - KH ) / SH + 1, OW = ( W - KW ) / SW + 1;
		if ( OD <= 0 || OH <= 0 || OW <= 0 )
			throw new IllegalArgumentException( "the pooling kernel " + Arrays.toString( kernel ) + " is larger than the input " + x );

		final float[] out = new float[ N * C * OD * OH * OW ];
		final float[] in = x.f;
		int o = 0;
		for ( int nc = 0; nc < N * C; ++nc )
		{
			final int iVolume = nc * D * H * W;
			for ( int oz = 0; oz < OD; ++oz )
				for ( int oy = 0; oy < OH; ++oy )
					for ( int ox = 0; ox < OW; ++ox )
					{
						float max = Float.NEGATIVE_INFINITY;
						for ( int kz = 0; kz < KD; ++kz )
							for ( int ky = 0; ky < KH; ++ky )
							{
								final int base = iVolume + ( oz * SD + kz ) * H * W + ( oy * SH + ky ) * W + ox * SW;
								for ( int kx = 0; kx < KW; ++kx )
								{
									final float v = in[ base + kx ];
									if ( v > max || Float.isNaN( v ) )
										max = v;
								}
							}
						out[ o++ ] = max;
					}
		}

		return Tensor.floats( new int[] { N, C, OD, OH, OW }, out );
	}

	static Tensor batchNormalization( final Tensor x, final Tensor scale, final Tensor bias, final Tensor mean, final Tensor variance, final float epsilon, final boolean training )
	{
		requireFloat( x, "the input" );
		if ( training )
			throw new IllegalArgumentException( "BatchNormalization in training mode is not supported" );
		if ( x.rank() < 2 )
			throw new IllegalArgumentException( "BatchNormalization needs an input [N, C, ...], got " + x );
		final int N = x.shape[ 0 ], C = x.shape[ 1 ];
		for ( final Tensor t : new Tensor[] { scale, bias, mean, variance } )
			if ( t.type != OnnxModel.FLOAT || t.size() != C )
				throw new IllegalArgumentException( "BatchNormalization: " + t + " does not fit " + C + " channels" );
		final int inner = x.size() / ( N * C );
		final float[] r = new float[ x.size() ];
		for ( int n = 0; n < N; ++n )
			for ( int c = 0; c < C; ++c )
			{
				final float a = (float) ( scale.f[ c ] / Math.sqrt( variance.f[ c ] + epsilon ) );
				final float b = bias.f[ c ] - a * mean.f[ c ];
				final int base = ( n * C + c ) * inner;
				for ( int i = 0; i < inner; ++i )
					r[ base + i ] = a * x.f[ base + i ] + b;
			}
		return Tensor.floats( x.shape, r );
	}

	// ---- helpers -----------------------------------------------------------------------------------

	private static void requireFloat( final Tensor t, final String what )
	{
		if ( t.type != OnnxModel.FLOAT )
			throw new IllegalArgumentException( what + " must be float32, got " + t );
	}

	private static boolean all( final long[] values, final long expected )
	{
		for ( final long v : values )
			if ( v != expected )
				return false;
		return true;
	}

	static int[] shapeOf( final long[] dims )
	{
		final int[] shape = new int[ dims.length ];
		for ( int d = 0; d < dims.length; ++d )
		{
			if ( dims[ d ] < 0 || dims[ d ] > Integer.MAX_VALUE )
				throw new IllegalArgumentException( "unsupported dimension " + dims[ d ] + " in " + Arrays.toString( dims ) );
			shape[ d ] = (int) dims[ d ];
		}
		return shape;
	}

	static int size( final int[] shape )
	{
		long n = 1;
		for ( final int d : shape )
			n *= d;
		if ( n > Integer.MAX_VALUE - 8 )
			throw new IllegalArgumentException( "a tensor of shape " + Arrays.toString( shape ) + " is too large for one Java array" );
		return (int) n;
	}

	/** @return the shape two shapes broadcast to (numpy rules: right-aligned, a dimension of 1 stretches) */
	static int[] broadcast( final int[] a, final int[] b )
	{
		final int rank = Math.max( a.length, b.length );
		final int[] out = new int[ rank ];
		for ( int d = 0; d < rank; ++d )
		{
			final int da = d - ( rank - a.length ), db = d - ( rank - b.length );
			final int x = da < 0 ? 1 : a[ da ], y = db < 0 ? 1 : b[ db ];
			if ( x != y && x != 1 && y != 1 )
				throw new IllegalArgumentException( "the shapes " + Arrays.toString( a ) + " and " + Arrays.toString( b ) + " do not broadcast" );
			out[ d ] = x == 1 ? y : x;
		}
		return out;
	}

	/** @return the stride per output dimension into a tensor of {@code shape} that is broadcast to {@code out} (0 where the tensor's dimension is 1) */
	static int[] strides( final int[] shape, final int[] out )
	{
		final int rank = out.length;
		final int[] strides = new int[ rank ];
		int stride = 1;
		for ( int d = rank - 1; d >= 0; --d )
		{
			final int ds = d - ( rank - shape.length );
			final int size = ds < 0 ? 1 : shape[ ds ];
			strides[ d ] = size == 1 ? 0 : stride;
			stride *= size;
		}
		return strides;
	}

	/** advances a multi-index over {@code shape} by one (last dimension fastest) */
	static void increment( final int[] idx, final int[] shape )
	{
		for ( int d = shape.length - 1; d >= 0; --d )
		{
			if ( ++idx[ d ] < shape[ d ] )
				return;
			idx[ d ] = 0;
		}
	}

	static String typeName( final int type )
	{
		switch ( type )
		{
		case OnnxModel.FLOAT: return "float32";
		case OnnxModel.INT64: return "int64";
		case OnnxModel.BOOL: return "bool";
		case OnnxModel.INT32: return "int32";
		case OnnxModel.DOUBLE: return "float64";
		default: return "type" + type;
		}
	}
}
