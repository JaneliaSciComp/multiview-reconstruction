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
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import net.preibisch.mvrecon.process.interestpointembedding.OnnxEvaluator.Tensor;

/**
 * The {@link EmbeddingModel} that reads the ONNX file itself ({@link OnnxModel}) and evaluates it in Java
 * ({@link OnnxEvaluator}): no native library, no dependency, the same file ONNX Runtime would load. The graph has to
 * consist of the operators the evaluator implements; the constructor checks that and the tensor contract of the sidecar
 * against the graph's declared inputs and outputs.
 *
 * Verified against pcmatch's fixture (256 points of ds1, 2026-10-30): the stored descriptors are reproduced to a maximal
 * difference of 1.7e-6 (float32 summation order), one point and batches give the same result; about 500 points/s per
 * thread on a Threadripper PRO 7985WX (PyTorch's CPU path: 880).
 *
 * @author Stephan Preibisch
 */
public class JavaOnnxEmbeddingModel implements EmbeddingModel
{
	final Path file;
	final Contract contract;
	final OnnxModel model;
	final OnnxEvaluator evaluator;

	/**
	 * @param file the {@code .onnx} file
	 * @param contract its contract (the sidecar, {@link EmbeddingModel#readContract})
	 * @throws IOException if the file cannot be read or parsed, the graph does not match the contract, or it uses operators the evaluator does not implement
	 */
	public JavaOnnxEmbeddingModel( final Path file, final Contract contract ) throws IOException
	{
		this.file = file;
		this.contract = contract;
		this.model = OnnxModel.read( file );

		try
		{
			this.evaluator = new OnnxEvaluator( model );
		}
		catch ( final IllegalArgumentException e )
		{
			throw new IOException( "The model '" + file + "' cannot be run by the Java evaluator: " + e.getMessage(), e );
		}

		// the graph's input and output must be the sidecar's
		OnnxModel.ValueInfo input = null;
		for ( final OnnxModel.ValueInfo v : model.getGraph().inputs() )
			if ( !model.getGraph().initializers().containsKey( v.name() ) )
			{
				if ( input != null )
					throw new IOException( "The model '" + file + "' has more than one input ('" + input.name() + "', '" + v.name() + "'), expected only '" + contract.inputName() + "'." );
				input = v;
			}
		if ( input == null || !input.name().equals( contract.inputName() ) )
			throw new IOException( "The model '" + file + "' has the input '" + ( input == null ? null : input.name() ) + "', the sidecar says '" + contract.inputName() + "'." );
		checkShape( input, new long[] { -1, contract.channels(), contract.patch(), contract.patch(), contract.patch() }, "input" );

		if ( !evaluator.getOutputNames().contains( contract.outputName() ) )
			throw new IOException( "The model '" + file + "' has the outputs " + evaluator.getOutputNames() + ", the sidecar says '" + contract.outputName() + "'." );
		for ( final OnnxModel.ValueInfo v : model.getGraph().outputs() )
			if ( v.name().equals( contract.outputName() ) )
				checkShape( v, new long[] { -1, contract.outputs() }, "output" );
	}

	private void checkShape( final OnnxModel.ValueInfo v, final long[] expected, final String what ) throws IOException
	{
		if ( v.elemType() != 0 && v.elemType() != OnnxModel.FLOAT )
			throw new IOException( "The " + what + " '" + v.name() + "' of '" + file + "' is of ONNX data type " + v.elemType() + ", expected float32." );
		boolean ok = v.dims().length == expected.length;
		for ( int d = 0; ok && d < expected.length; ++d )
			ok = expected[ d ] < 0 || v.dims()[ d ] < 0 || v.dims()[ d ] == expected[ d ];
		if ( !ok )
			throw new IOException( "The " + what + " '" + v.name() + "' of '" + file + "' is declared as " + Arrays.toString( v.dims() ) + " (-1 = symbolic), the sidecar says " + Arrays.toString( expected ) + "." );
	}

	@Override
	public Contract contract() { return contract; }

	@Override
	public String backend() { return "Java evaluator of " + file.getFileName() + " (" + model.getGraph().nodes().size() + " nodes, ONNX opset " + model.getOpsets().get( "" ) + ")"; }

	public OnnxModel getModel() { return model; }

	public OnnxEvaluator getEvaluator() { return evaluator; }

	@Override
	public float[] embed( final float[] patches, final int n )
	{
		final int patchSize = contract.patchSize();
		if ( n < 1 || patches.length < (long) n * patchSize )
			throw new IllegalArgumentException( "embed: " + n + " points need " + ( (long) n * patchSize ) + " floats, got " + patches.length );

		final int p = contract.patch();
		final Tensor input = Tensor.floats( new int[] { n, contract.channels(), p, p, p }, patches );
		final Map< String, Tensor > out = evaluator.run( Map.of( contract.inputName(), input ) );
		final Tensor result = out.get( contract.outputName() );

		if ( result.type != OnnxModel.FLOAT || result.rank() != 2 || result.shape[ 0 ] != n || result.shape[ 1 ] != contract.outputs() )
			throw new IllegalStateException( "The model '" + file.getFileName() + "' returned " + result + " for " + n + " points, expected float32[" + n + ", " + contract.outputs() + "]." );

		return result.f.length == n * contract.outputs() ? result.f : Arrays.copyOf( result.f, n * contract.outputs() );
	}

	@Override
	public String toString() { return backend() + ": " + contract; }
}
