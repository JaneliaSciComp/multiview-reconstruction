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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An ONNX model file ({@code ModelProto}) read without ONNX Runtime and without protobuf-java: the file is a protobuf
 * message, and the wire format (varint tags, length-delimited sub-messages, fixed 32/64-bit numbers) is simple enough to
 * read the few messages a model consists of directly. Everything an evaluator needs is kept: the graph's nodes with their
 * attributes, the initializers (the weights, as stored), the graph's inputs and outputs with their declared shapes, the
 * opset versions and the metadata properties. Sub-graphs (attributes of type GRAPH, control flow), sparse and external
 * tensor data are not supported; a model that has them fails loudly here, it would need ONNX Runtime.
 *
 * Field numbers follow onnx.proto (ONNX IR version 10). Anything unknown is skipped, as protobuf readers do.
 *
 * @author Stephan Preibisch
 */
public class OnnxModel
{
	// TensorProto.DataType
	public static final int FLOAT = 1, UINT8 = 2, INT8 = 3, INT32 = 6, INT64 = 7, BOOL = 9, DOUBLE = 11;

	// AttributeProto.AttributeType
	public static final int ATTR_FLOAT = 1, ATTR_INT = 2, ATTR_STRING = 3, ATTR_TENSOR = 4, ATTR_GRAPH = 5, ATTR_FLOATS = 6, ATTR_INTS = 7, ATTR_STRINGS = 8;

	/** A stored tensor (an initializer or a constant attribute): its data as the file holds it, decoded on request by {@link #floats()} and {@link #longs()}. */
	public record Tensor( String name, int dataType, long[] dims, byte[] raw, float[] floatData, int[] int32Data, long[] int64Data, double[] doubleData )
	{
		public long size()
		{
			long n = 1;
			for ( final long d : dims )
				n *= d;
			return n;
		}

		/** @return the values of a FLOAT (or DOUBLE) tensor as floats, from raw_data or float_data */
		public float[] floats()
		{
			final int n = (int) size();
			final float[] values = new float[ n ];

			if ( dataType == FLOAT )
			{
				if ( raw != null )
					ByteBuffer.wrap( raw ).order( ByteOrder.LITTLE_ENDIAN ).asFloatBuffer().get( values, 0, n );
				else if ( floatData != null && floatData.length >= n )
					System.arraycopy( floatData, 0, values, 0, n );
				else
					throw new IllegalStateException( "tensor '" + name + "' has no float data for its " + n + " values" );
			}
			else if ( dataType == DOUBLE )
			{
				final double[] doubles = new double[ n ];
				if ( raw != null )
					ByteBuffer.wrap( raw ).order( ByteOrder.LITTLE_ENDIAN ).asDoubleBuffer().get( doubles, 0, n );
				else if ( doubleData != null && doubleData.length >= n )
					System.arraycopy( doubleData, 0, doubles, 0, n );
				else
					throw new IllegalStateException( "tensor '" + name + "' has no double data for its " + n + " values" );
				for ( int i = 0; i < n; ++i )
					values[ i ] = (float) doubles[ i ];
			}
			else
				throw new IllegalStateException( "tensor '" + name + "' is of data type " + dataType + ", not float" );

			return values;
		}

		/** @return the values of an INT64 (or INT32, BOOL, INT8, UINT8) tensor as longs, from raw_data or the typed fields */
		public long[] longs()
		{
			final int n = (int) size();
			final long[] values = new long[ n ];

			if ( dataType == INT64 )
			{
				if ( raw != null )
					ByteBuffer.wrap( raw ).order( ByteOrder.LITTLE_ENDIAN ).asLongBuffer().get( values, 0, n );
				else if ( int64Data != null && int64Data.length >= n )
					System.arraycopy( int64Data, 0, values, 0, n );
				else
					throw new IllegalStateException( "tensor '" + name + "' has no int64 data for its " + n + " values" );
			}
			else if ( dataType == INT32 )
			{
				if ( raw != null )
				{
					final ByteBuffer buffer = ByteBuffer.wrap( raw ).order( ByteOrder.LITTLE_ENDIAN );
					for ( int i = 0; i < n; ++i )
						values[ i ] = buffer.getInt( 4 * i );
				}
				else if ( int32Data != null && int32Data.length >= n )
					for ( int i = 0; i < n; ++i )
						values[ i ] = int32Data[ i ];
				else
					throw new IllegalStateException( "tensor '" + name + "' has no int32 data for its " + n + " values" );
			}
			else if ( dataType == BOOL || dataType == INT8 || dataType == UINT8 )
			{
				if ( raw != null )
					for ( int i = 0; i < n; ++i )
						values[ i ] = dataType == INT8 ? raw[ i ] : ( raw[ i ] & 0xff );
				else if ( int32Data != null && int32Data.length >= n )
					for ( int i = 0; i < n; ++i )
						values[ i ] = int32Data[ i ];
				else
					throw new IllegalStateException( "tensor '" + name + "' has no data for its " + n + " values" );
			}
			else
				throw new IllegalStateException( "tensor '" + name + "' is of data type " + dataType + ", not an integer type" );

			return values;
		}
	}

	/** An attribute of a node; which field is set depends on {@code type} (ATTR_*). */
	public record Attribute( String name, int type, float f, long i, byte[] s, float[] floats, long[] ints, Tensor t )
	{
		public String string() { return s == null ? null : new String( s, StandardCharsets.UTF_8 ); }
	}

	public record Node( String opType, String name, String domain, List< String > inputs, List< String > outputs, Map< String, Attribute > attributes )
	{
		/** @return the int attribute, or the fallback if the node has none of that name */
		public long attr( final String key, final long fallback )
		{
			final Attribute a = attributes.get( key );
			return a == null ? fallback : a.i();
		}

		public float attr( final String key, final float fallback )
		{
			final Attribute a = attributes.get( key );
			return a == null ? fallback : a.f();
		}

		public long[] attr( final String key, final long[] fallback )
		{
			final Attribute a = attributes.get( key );
			return a == null ? fallback : a.ints();
		}

		public String attr( final String key, final String fallback )
		{
			final Attribute a = attributes.get( key );
			return a == null ? fallback : a.string();
		}
	}

	/** A graph input or output: its element type and declared shape ({@code dims[d] < 0} for a symbolic dimension such as the batch size, named in {@code dimParams[d]}). */
	public record ValueInfo( String name, int elemType, long[] dims, String[] dimParams ) {}

	public record Graph( String name, List< Node > nodes, Map< String, Tensor > initializers, List< ValueInfo > inputs, List< ValueInfo > outputs ) {}

	final long irVersion;
	final Map< String, Long > opsets;
	final String producerName, producerVersion;
	final Map< String, String > metadata;
	final Graph graph;

	public OnnxModel( final long irVersion, final Map< String, Long > opsets, final String producerName, final String producerVersion, final Map< String, String > metadata, final Graph graph )
	{
		this.irVersion = irVersion;
		this.opsets = Collections.unmodifiableMap( opsets );
		this.producerName = producerName;
		this.producerVersion = producerVersion;
		this.metadata = Collections.unmodifiableMap( metadata );
		this.graph = graph;
	}

	public long getIrVersion() { return irVersion; }

	/** @return the opset version per domain ("" is the default ONNX domain) */
	public Map< String, Long > getOpsets() { return opsets; }

	public String getProducerName() { return producerName; }

	public String getProducerVersion() { return producerVersion; }

	/** @return the model's {@code metadata_props} */
	public Map< String, String > getMetadata() { return metadata; }

	public Graph getGraph() { return graph; }

	/** @return the model of an {@code .onnx} file */
	public static OnnxModel read( final Path file ) throws IOException
	{
		try
		{
			return parse( Files.readAllBytes( file ) );
		}
		catch ( final IllegalStateException | IndexOutOfBoundsException e )
		{
			throw new IOException( "'" + file + "' is not an ONNX model this reader understands: " + e.getMessage(), e );
		}
	}

	/** @return the model of the bytes of an {@code .onnx} file */
	public static OnnxModel parse( final byte[] bytes )
	{
		final Reader r = new Reader( bytes, 0, bytes.length );

		long irVersion = -1;
		final Map< String, Long > opsets = new LinkedHashMap<>();
		String producerName = "", producerVersion = "";
		final Map< String, String > metadata = new LinkedHashMap<>();
		Graph graph = null;

		while ( r.hasMore() )
		{
			final int tag = r.tag();
			switch ( tag >>> 3 )
			{
			case 1: irVersion = r.varint(); break;
			case 2: producerName = r.string(); break;
			case 3: producerVersion = r.string(); break;
			case 7: graph = parseGraph( r.sub() ); break;
			case 8:
			{
				final Reader s = r.sub();
				String domain = "";
				long version = -1;
				while ( s.hasMore() )
				{
					final int t = s.tag();
					if ( ( t >>> 3 ) == 1 ) domain = s.string();
					else if ( ( t >>> 3 ) == 2 ) version = s.varint();
					else s.skip( t );
				}
				opsets.put( domain, version );
				break;
			}
			case 14:
			{
				final String[] kv = parseStringStringEntry( r.sub() );
				metadata.put( kv[ 0 ], kv[ 1 ] );
				break;
			}
			default: r.skip( tag );
			}
		}

		if ( graph == null )
			throw new IllegalStateException( "the model has no graph" );

		return new OnnxModel( irVersion, opsets, producerName, producerVersion, metadata, graph );
	}

	static String[] parseStringStringEntry( final Reader r )
	{
		String key = "", value = "";
		while ( r.hasMore() )
		{
			final int t = r.tag();
			if ( ( t >>> 3 ) == 1 ) key = r.string();
			else if ( ( t >>> 3 ) == 2 ) value = r.string();
			else r.skip( t );
		}
		return new String[] { key, value };
	}

	static Graph parseGraph( final Reader r )
	{
		String name = "";
		final List< Node > nodes = new ArrayList<>();
		final Map< String, Tensor > initializers = new LinkedHashMap<>();
		final List< ValueInfo > inputs = new ArrayList<>(), outputs = new ArrayList<>();

		while ( r.hasMore() )
		{
			final int tag = r.tag();
			switch ( tag >>> 3 )
			{
			case 1: nodes.add( parseNode( r.sub() ) ); break;
			case 2: name = r.string(); break;
			case 5:
			{
				final Tensor t = parseTensor( r.sub() );
				initializers.put( t.name(), t );
				break;
			}
			case 11: inputs.add( parseValueInfo( r.sub() ) ); break;
			case 12: outputs.add( parseValueInfo( r.sub() ) ); break;
			case 15: throw new IllegalStateException( "the graph has sparse initializers, which this reader does not support" );
			default: r.skip( tag );
			}
		}

		return new Graph( name, Collections.unmodifiableList( nodes ), Collections.unmodifiableMap( initializers ), Collections.unmodifiableList( inputs ), Collections.unmodifiableList( outputs ) );
	}

	static Node parseNode( final Reader r )
	{
		final List< String > inputs = new ArrayList<>(), outputs = new ArrayList<>();
		String name = "", opType = "", domain = "";
		final Map< String, Attribute > attributes = new LinkedHashMap<>();

		while ( r.hasMore() )
		{
			final int tag = r.tag();
			switch ( tag >>> 3 )
			{
			case 1: inputs.add( r.string() ); break;
			case 2: outputs.add( r.string() ); break;
			case 3: name = r.string(); break;
			case 4: opType = r.string(); break;
			case 5:
			{
				final Attribute a = parseAttribute( r.sub() );
				attributes.put( a.name(), a );
				break;
			}
			case 7: domain = r.string(); break;
			default: r.skip( tag );
			}
		}

		return new Node( opType, name, domain, Collections.unmodifiableList( inputs ), Collections.unmodifiableList( outputs ), Collections.unmodifiableMap( attributes ) );
	}

	static Attribute parseAttribute( final Reader r )
	{
		String name = "";
		int type = 0;
		float f = 0;
		long i = 0;
		byte[] s = null;
		Tensor t = null;
		final List< Float > floats = new ArrayList<>();
		final List< Long > ints = new ArrayList<>();

		while ( r.hasMore() )
		{
			final int tag = r.tag();
			final int wire = tag & 7;
			switch ( tag >>> 3 )
			{
			case 1: name = r.string(); break;
			case 2: f = r.fixed32(); break;
			case 3: i = r.varint(); break;
			case 4: s = r.bytes(); break;
			case 5: t = parseTensor( r.sub() ); break;
			case 6: throw new IllegalStateException( "attribute '" + name + "' holds a sub-graph (control flow), which this reader does not support" );
			case 7:
				if ( wire == 2 )
				{
					final Reader p = r.sub();
					while ( p.hasMore() )
						floats.add( p.fixed32() );
				}
				else
					floats.add( r.fixed32() );
				break;
			case 8:
				if ( wire == 2 )
				{
					final Reader p = r.sub();
					while ( p.hasMore() )
						ints.add( p.varint() );
				}
				else
					ints.add( r.varint() );
				break;
			case 9: throw new IllegalStateException( "attribute '" + name + "' is a list of strings, which this reader does not support" );
			case 10: throw new IllegalStateException( "attribute '" + name + "' is a list of tensors, which this reader does not support" );
			case 11: throw new IllegalStateException( "attribute '" + name + "' holds sub-graphs, which this reader does not support" );
			case 20: type = (int) r.varint(); break;
			default: r.skip( tag );
			}
		}

		final float[] fl = new float[ floats.size() ];
		for ( int k = 0; k < fl.length; ++k )
			fl[ k ] = floats.get( k );
		final long[] il = new long[ ints.size() ];
		for ( int k = 0; k < il.length; ++k )
			il[ k ] = ints.get( k );

		// older files leave the type out; infer it from what is set
		if ( type == 0 )
			type = t != null ? ATTR_TENSOR : s != null ? ATTR_STRING : fl.length > 0 ? ATTR_FLOATS : il.length > 0 ? ATTR_INTS : ATTR_INT;

		return new Attribute( name, type, f, i, s, fl, il, t );
	}

	static Tensor parseTensor( final Reader r )
	{
		final List< Long > dims = new ArrayList<>();
		int dataType = 0;
		String name = "";
		byte[] raw = null;
		final List< Float > floatData = new ArrayList<>();
		final List< Integer > int32Data = new ArrayList<>();
		final List< Long > int64Data = new ArrayList<>();
		final List< Double > doubleData = new ArrayList<>();

		while ( r.hasMore() )
		{
			final int tag = r.tag();
			final int wire = tag & 7;
			switch ( tag >>> 3 )
			{
			case 1:
				if ( wire == 2 )
				{
					final Reader p = r.sub();
					while ( p.hasMore() )
						dims.add( p.varint() );
				}
				else
					dims.add( r.varint() );
				break;
			case 2: dataType = (int) r.varint(); break;
			case 3: throw new IllegalStateException( "tensor '" + name + "' is segmented, which this reader does not support" );
			case 4:
				if ( wire == 2 )
				{
					final Reader p = r.sub();
					while ( p.hasMore() )
						floatData.add( p.fixed32() );
				}
				else
					floatData.add( r.fixed32() );
				break;
			case 5:
				if ( wire == 2 )
				{
					final Reader p = r.sub();
					while ( p.hasMore() )
						int32Data.add( (int) p.varint() );
				}
				else
					int32Data.add( (int) r.varint() );
				break;
			case 7:
				if ( wire == 2 )
				{
					final Reader p = r.sub();
					while ( p.hasMore() )
						int64Data.add( p.varint() );
				}
				else
					int64Data.add( r.varint() );
				break;
			case 8: name = r.string(); break;
			case 9: raw = r.bytes(); break;
			case 10:
				if ( wire == 2 )
				{
					final Reader p = r.sub();
					while ( p.hasMore() )
						doubleData.add( p.fixed64() );
				}
				else
					doubleData.add( r.fixed64() );
				break;
			case 13: throw new IllegalStateException( "tensor '" + name + "' keeps its data in an external file, which this reader does not support" );
			case 14:
				if ( r.varint() != 0 )
					throw new IllegalStateException( "tensor '" + name + "' keeps its data in an external file, which this reader does not support" );
				break;
			default: r.skip( tag );
			}
		}

		final long[] d = new long[ dims.size() ];
		for ( int k = 0; k < d.length; ++k )
			d[ k ] = dims.get( k );

		return new Tensor( name, dataType, d, raw,
				floatData.isEmpty() ? null : toFloats( floatData ),
				int32Data.isEmpty() ? null : int32Data.stream().mapToInt( Integer::intValue ).toArray(),
				int64Data.isEmpty() ? null : int64Data.stream().mapToLong( Long::longValue ).toArray(),
				doubleData.isEmpty() ? null : doubleData.stream().mapToDouble( Double::doubleValue ).toArray() );
	}

	static float[] toFloats( final List< Float > list )
	{
		final float[] a = new float[ list.size() ];
		for ( int k = 0; k < a.length; ++k )
			a[ k ] = list.get( k );
		return a;
	}

	static ValueInfo parseValueInfo( final Reader r )
	{
		String name = "";
		int elemType = 0;
		long[] dims = new long[ 0 ];
		String[] dimParams = new String[ 0 ];

		while ( r.hasMore() )
		{
			final int tag = r.tag();
			switch ( tag >>> 3 )
			{
			case 1: name = r.string(); break;
			case 2:
			{
				// TypeProto: 1 = tensor_type { 1 = elem_type, 2 = shape { 1 = dim { 1 = dim_value | 2 = dim_param } } }
				final Reader type = r.sub();
				while ( type.hasMore() )
				{
					final int tt = type.tag();
					if ( ( tt >>> 3 ) != 1 )
					{
						type.skip( tt );
						continue;
					}
					final Reader tensor = type.sub();
					while ( tensor.hasMore() )
					{
						final int tag2 = tensor.tag();
						if ( ( tag2 >>> 3 ) == 1 )
							elemType = (int) tensor.varint();
						else if ( ( tag2 >>> 3 ) == 2 )
						{
							final Reader shape = tensor.sub();
							final List< Long > ds = new ArrayList<>();
							final List< String > ps = new ArrayList<>();
							while ( shape.hasMore() )
							{
								final int tag3 = shape.tag();
								if ( ( tag3 >>> 3 ) != 1 )
								{
									shape.skip( tag3 );
									continue;
								}
								final Reader dim = shape.sub();
								long value = -1;
								String param = null;
								while ( dim.hasMore() )
								{
									final int tag4 = dim.tag();
									if ( ( tag4 >>> 3 ) == 1 ) value = dim.varint();
									else if ( ( tag4 >>> 3 ) == 2 ) param = dim.string();
									else dim.skip( tag4 );
								}
								ds.add( value );
								ps.add( param );
							}
							dims = ds.stream().mapToLong( Long::longValue ).toArray();
							dimParams = ps.toArray( new String[ 0 ] );
						}
						else
							tensor.skip( tag2 );
					}
				}
				break;
			}
			default: r.skip( tag );
			}
		}

		return new ValueInfo( name, elemType, dims, dimParams );
	}

	/** A cursor over a protobuf message: {@code [pos, limit)} of a byte array. */
	static final class Reader
	{
		final byte[] buf;
		int pos;
		final int limit;

		Reader( final byte[] buf, final int pos, final int limit )
		{
			this.buf = buf;
			this.pos = pos;
			this.limit = limit;
		}

		boolean hasMore() { return pos < limit; }

		int tag()
		{
			final long tag = varint();
			if ( tag <= 0 || tag > Integer.MAX_VALUE )
				throw new IllegalStateException( "invalid protobuf tag " + tag + " at byte " + pos );
			return (int) tag;
		}

		long varint()
		{
			long result = 0;
			int shift = 0;
			while ( true )
			{
				if ( pos >= limit )
					throw new IllegalStateException( "truncated varint at byte " + pos );
				final byte b = buf[ pos++ ];
				result |= (long) ( b & 0x7f ) << shift;
				if ( ( b & 0x80 ) == 0 )
					return result;
				shift += 7;
				if ( shift >= 64 )
					throw new IllegalStateException( "varint longer than 64 bits at byte " + pos );
			}
		}

		float fixed32()
		{
			if ( pos + 4 > limit )
				throw new IllegalStateException( "truncated fixed32 at byte " + pos );
			final int bits = ( buf[ pos ] & 0xff ) | ( buf[ pos + 1 ] & 0xff ) << 8 | ( buf[ pos + 2 ] & 0xff ) << 16 | ( buf[ pos + 3 ] & 0xff ) << 24;
			pos += 4;
			return Float.intBitsToFloat( bits );
		}

		double fixed64()
		{
			if ( pos + 8 > limit )
				throw new IllegalStateException( "truncated fixed64 at byte " + pos );
			long bits = 0;
			for ( int k = 7; k >= 0; --k )
				bits = bits << 8 | ( buf[ pos + k ] & 0xff );
			pos += 8;
			return Double.longBitsToDouble( bits );
		}

		byte[] bytes()
		{
			final long length = varint();
			if ( length < 0 || pos + length > limit )
				throw new IllegalStateException( "truncated length-delimited field at byte " + pos );
			final byte[] b = new byte[ (int) length ];
			System.arraycopy( buf, pos, b, 0, b.length );
			pos += b.length;
			return b;
		}

		String string() { return new String( bytes(), StandardCharsets.UTF_8 ); }

		/** @return a reader over the next length-delimited field (a sub-message or a packed list), advancing past it */
		Reader sub()
		{
			final long length = varint();
			if ( length < 0 || pos + length > limit )
				throw new IllegalStateException( "truncated sub-message at byte " + pos );
			final Reader r = new Reader( buf, pos, (int) ( pos + length ) );
			pos += length;
			return r;
		}

		void skip( final int tag )
		{
			switch ( tag & 7 )
			{
			case 0: varint(); break;
			case 1: pos += 8; break;
			case 2: sub(); break;
			case 5: pos += 4; break;
			default: throw new IllegalStateException( "unsupported protobuf wire type " + ( tag & 7 ) + " at byte " + pos );
			}
			if ( pos > limit )
				throw new IllegalStateException( "truncated field at byte " + pos );
		}
	}
}
