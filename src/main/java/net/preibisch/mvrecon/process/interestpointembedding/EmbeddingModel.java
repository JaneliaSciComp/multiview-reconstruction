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
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A model that turns the image patches around interest points into one unit vector per point (pcmatch's descriptor,
 * stored as the per-point array {@code embedding} and matched by {@code EmbeddingsPairwise}). The thin layer between the
 * sampling of the patches ({@link PatchExtractor}) and whatever runs the network: today the model file is read and
 * evaluated in Java ({@link JavaOnnxEmbeddingModel}, no dependency); a backend on ONNX Runtime can be added behind this
 * interface when a model needs operators the Java evaluator does not have, or a GPU.
 *
 * The model is an ONNX file exported by {@code pcmatch export-onnx} together with its sidecar {@code <file>.onnx.json},
 * which carries the {@link Contract}: pcmatch's {@code DescriptorConfig} (the extents in units of the detection sigma, the
 * patch size, the read floor, ...) that the patch extraction has to follow, the tensor names and shapes, and the model's
 * fingerprint, which the rows are stored under ({@code meta.model.fingerprint}) so that rows of two models are never
 * compared. Everything the network expects of its input beyond that (the per-patch standardisation) is part of the graph.
 *
 * @author Stephan Preibisch
 */
public interface EmbeddingModel
{
	/** the sidecar of an ONNX file: {@code <file>.onnx.json} */
	public static final String SIDECAR_SUFFIX = ".json";

	/**
	 * What the model expects and promises, from the sidecar.
	 *
	 * @param file - the name of the ONNX file
	 * @param checkpoint - the checkpoint the file was exported from (pcmatch's {@code .pt}), the {@code model.file} of the stored rows' meta
	 * @param fingerprint - the identity of the model, the {@code model.fingerprint} of the stored rows' meta
	 * @param extents - the edge of the cube per channel, in units of the detection sigma ({@code extentUnits} "sigma")
	 * @param extentUnits - "sigma"
	 * @param patch - samples per cube edge (16)
	 * @param outputs - the length of a vector (64)
	 * @param readFloor - "detection": no cube is read from a pyramid level finer than the detection ran at; "full": any level
	 * @param border - "mask": samples outside the image are NaN (the graph masks them); "zero": they are 0
	 * @param fusion - "stack": the channels are the extents, one network ("head" models export no single graph)
	 * @param inputName - the graph input, float32 [N, channels, patch, patch, patch] in C order (point, channel, z, y, x)
	 * @param outputName - the graph output, float32 [N, outputs]
	 * @param config - pcmatch's {@code DescriptorConfig} as stored in the sidecar, verbatim (goes into the rows' meta)
	 */
	public record Contract(
			String file,
			String checkpoint,
			String fingerprint,
			double[] extents,
			String extentUnits,
			int patch,
			int outputs,
			String readFloor,
			String border,
			String fusion,
			String inputName,
			String outputName,
			JsonObject config )
	{
		public int channels() { return extents.length; }

		/** @return the floats of one point's input: channels x patch^3 */
		public int patchSize() { return channels() * patch * patch * patch; }

		/** @return the value of a sample outside the image (NaN for border "mask", 0 for "zero") */
		public float outside() { return border.equals( "zero" ) ? 0f : Float.NaN; }

		/** @return whether the cubes may be read from pyramid levels finer than the detection's ({@code readFloor} "full") */
		public boolean readsBelowDetection() { return readFloor.equals( "full" ); }

		/** @return the {@code model} object of the rows' meta, as pcmatch writes it: config, file (the checkpoint), fingerprint, plus the ONNX file */
		public JsonObject metaModel()
		{
			final JsonObject model = new JsonObject();
			model.add( "config", config.deepCopy() );
			model.addProperty( "file", checkpoint );
			model.addProperty( "fingerprint", fingerprint );
			model.addProperty( "onnx", file );
			return model;
		}

		@Override
		public String toString()
		{
			return file + " (fingerprint " + fingerprint + ", extents " + Arrays.toString( extents ) + " x sigma, " + channels() + " x " + patch + "^3 -> " + outputs + ", read floor " + readFloor + ", border " + border + ")";
		}
	}

	public Contract contract();

	/**
	 * Embeds a batch of points.
	 *
	 * @param patches the input of {@code n} points, {@code n x contract().patchSize()} floats in C order (point, channel, z, y, x), raw intensities, NaN outside the image
	 * @param n the number of points
	 * @return {@code n x contract().outputs()} floats, one unit vector per point
	 */
	public float[] embed( final float[] patches, final int n );

	/** @return a short description of the backend (for logs) */
	public String backend();

	/**
	 * Loads a model: the Java evaluator of the ONNX file, with the contract of its sidecar.
	 *
	 * @param onnx the {@code .onnx} file; its sidecar {@code <file>.onnx.json} must sit next to it
	 * @throws IOException if a file cannot be read, the sidecar does not describe a model this code can run, or the graph uses operators the Java evaluator does not implement
	 */
	public static EmbeddingModel load( final Path onnx ) throws IOException
	{
		return new JavaOnnxEmbeddingModel( onnx, readContract( onnx ) );
	}

	/** @return the sidecar file of an ONNX file */
	public static Path sidecar( final Path onnx ) { return onnx.resolveSibling( onnx.getFileName() + SIDECAR_SUFFIX ); }

	/**
	 * @return the contract of the sidecar {@code <file>.onnx.json} of an ONNX file
	 * @throws IOException if there is no sidecar or it describes a model this code cannot run (other extent units, fusion or read floor)
	 */
	public static Contract readContract( final Path onnx ) throws IOException
	{
		final Path sidecar = sidecar( onnx );
		if ( !Files.exists( sidecar ) )
			throw new IOException( "The model '" + onnx + "' has no sidecar '" + sidecar.getFileName() + "' next to it (pcmatch export-onnx writes it: the model's DescriptorConfig, tensor contract and fingerprint)." );

		final JsonObject json;
		try ( final Reader reader = Files.newBufferedReader( sidecar, StandardCharsets.UTF_8 ) )
		{
			json = JsonParser.parseReader( reader ).getAsJsonObject();
		}
		catch ( final RuntimeException e )
		{
			throw new IOException( "The sidecar '" + sidecar + "' is no JSON object: " + e.getMessage(), e );
		}

		return contractOf( json, onnx.getFileName().toString(), sidecar.toString() );
	}

	/**
	 * @param json the sidecar
	 * @param file the name of the ONNX file
	 * @param where the sidecar, for messages
	 * @return the contract
	 * @throws IOException if the sidecar lacks a field or describes a model this code cannot run
	 */
	public static Contract contractOf( final JsonObject json, final String file, final String where ) throws IOException
	{
		final JsonObject config = object( json, "config", where );
		final JsonObject input = object( json, "input", where );
		final JsonObject output = object( json, "output", where );

		final String fingerprint = string( json, "fingerprint", where );
		final String checkpoint = json.has( "checkpoint" ) && json.get( "checkpoint" ).isJsonPrimitive() ? json.get( "checkpoint" ).getAsString() : file;

		final JsonElement extentsElement = config.get( "extents" );
		if ( extentsElement == null || !extentsElement.isJsonArray() || extentsElement.getAsJsonArray().isEmpty() )
			throw new IOException( "'" + where + "': config.extents must be a non-empty list of numbers." );
		final JsonArray extentsArray = extentsElement.getAsJsonArray();
		final double[] extents = new double[ extentsArray.size() ];
		for ( int i = 0; i < extents.length; ++i )
		{
			extents[ i ] = extentsArray.get( i ).getAsDouble();
			if ( !( extents[ i ] > 0 ) )
				throw new IOException( "'" + where + "': config.extents[" + i + "] = " + extents[ i ] + " is no positive number." );
		}

		final String extentUnits = string( config, "extent_units", where );
		if ( !extentUnits.equals( "sigma" ) )
			throw new IOException( "'" + where + "': config.extent_units is '" + extentUnits + "', this code samples extents in units of the detection sigma only ('sigma')." );

		final String fusion = string( config, "fusion", where );
		if ( !fusion.equals( "stack" ) )
			throw new IOException( "'" + where + "': config.fusion is '" + fusion + "', this code runs 'stack' models (the extents as channels of one network) only." );

		final String readFloor = config.has( "read_floor" ) ? string( config, "read_floor", where ) : "full";
		if ( !( readFloor.equals( "detection" ) || readFloor.equals( "full" ) ) )
			throw new IOException( "'" + where + "': config.read_floor is '" + readFloor + "', expected 'detection' or 'full'." );

		final String border = config.has( "border" ) ? string( config, "border", where ) : "mask";
		if ( !( border.equals( "mask" ) || border.equals( "zero" ) ) )
			throw new IOException( "'" + where + "': config.border is '" + border + "', expected 'mask' or 'zero'." );

		final int patch = integer( config, "patch", where );
		final int outputs = integer( config, "emb_dim", where );
		if ( patch < 2 || outputs < 1 )
			throw new IOException( "'" + where + "': config.patch = " + patch + ", config.emb_dim = " + outputs + " make no sense." );

		final String inputName = string( input, "name", where ), outputName = string( output, "name", where );

		// the declared shapes must agree with the config: [N, channels, patch, patch, patch] and [N, outputs]
		final long[] inputShape = shape( input, where ), outputShape = shape( output, where );
		final long[] expectedInput = new long[] { -1, extents.length, patch, patch, patch };
		if ( inputShape.length != 5 || !sameShape( inputShape, expectedInput ) )
			throw new IOException( "'" + where + "': input.shape is " + Arrays.toString( inputShape ) + ", the config says [N, " + extents.length + ", " + patch + ", " + patch + ", " + patch + "]." );
		if ( outputShape.length != 2 || !sameShape( outputShape, new long[] { -1, outputs } ) )
			throw new IOException( "'" + where + "': output.shape is " + Arrays.toString( outputShape ) + ", the config says [N, " + outputs + "]." );

		return new Contract( file, checkpoint, fingerprint, extents, extentUnits, patch, outputs, readFloor, border, fusion, inputName, outputName, config.deepCopy() );
	}

	private static boolean sameShape( final long[] declared, final long[] expected )
	{
		for ( int d = 0; d < expected.length; ++d )
			if ( expected[ d ] >= 0 && declared[ d ] != expected[ d ] )
				return false;
		return true;
	}

	/** @return the shape of a tensor description, symbolic dimensions ("N") as -1 */
	private static long[] shape( final JsonObject tensor, final String where ) throws IOException
	{
		final JsonElement e = tensor.get( "shape" );
		if ( e == null || !e.isJsonArray() )
			throw new IOException( "'" + where + "': " + tensor + " has no 'shape' list." );
		final JsonArray a = e.getAsJsonArray();
		final long[] shape = new long[ a.size() ];
		for ( int d = 0; d < shape.length; ++d )
		{
			final JsonElement v = a.get( d );
			shape[ d ] = v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsLong() : -1;
		}
		return shape;
	}

	private static JsonObject object( final JsonObject json, final String key, final String where ) throws IOException
	{
		final JsonElement e = json.get( key );
		if ( e == null || !e.isJsonObject() )
			throw new IOException( "'" + where + "' lacks the object '" + key + "'." );
		return e.getAsJsonObject();
	}

	private static String string( final JsonObject json, final String key, final String where ) throws IOException
	{
		final JsonElement e = json.get( key );
		if ( e == null || !e.isJsonPrimitive() )
			throw new IOException( "'" + where + "' lacks the value '" + key + "'." );
		return e.getAsString();
	}

	private static int integer( final JsonObject json, final String key, final String where ) throws IOException
	{
		final JsonElement e = json.get( key );
		if ( e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() )
			throw new IOException( "'" + where + "' lacks the number '" + key + "'." );
		return e.getAsInt();
	}
}
