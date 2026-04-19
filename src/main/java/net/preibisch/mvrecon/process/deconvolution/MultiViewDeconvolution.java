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
package net.preibisch.mvrecon.process.deconvolution;

import java.util.ArrayList;
import java.util.Date;
import java.util.Vector;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.process.cuda.Block;
import net.preibisch.mvrecon.process.deconvolution.init.PsiInit;
import net.preibisch.mvrecon.process.deconvolution.init.PsiInitFactory;
import net.preibisch.mvrecon.process.deconvolution.iteration.ComputeBlockThread;
import net.preibisch.mvrecon.process.deconvolution.iteration.ComputeBlockThreadFactory;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.fusion.FusionTools;

public abstract class MultiViewDeconvolution< C extends ComputeBlockThread >
{
	final public static float outsideValueImg = 0f; // the value the input image has if there is no data at this pixel
	final public static float minValueImg = 1f; // mininal value for the input image (as it is not normalized)
	final public static float minValue = 0.0001f; // minimal value for the deconvolved image

	public static int defaultBlendingRange = 12;
	public static int defaultBlendingBorder = -8;
	public static int cellDim = 32;
	public static int maxCacheSize = 10000;

	// for additional smoothing of weights in areas where many views contribute less than 100%
	public static float maxDiffRange = 0.1f;
	public static float scalingRange = 0.05f;
	public static boolean additionalSmoothBlending = false;

	// current iteration
	int it = 0;

	// the multi-view deconvolved image
	final Img< FloatType > psi;

	// the input data
	final DeconViews views;

	// max intensities for each contributing view, ordered as in views
	final float[] max;

	final int numIterations;
	final double avgMax;

	boolean debug = false;
	int debugInterval = 1;

	// the thread that will compute the iteration for each block independently
	final ComputeBlockThreadFactory< C > computeBlockFactory;

	// the actual block compute threads
	final ArrayList< C > computeBlockThreads;

	// for debug
	ImageStack stack;
	CompositeImage ci;

	public MultiViewDeconvolution(
			final DeconViews views,
			final int numIterations,
			final PsiInitFactory psiInitFactory,
			final ComputeBlockThreadFactory< C > computeBlockFactory,
			final ImgFactory< FloatType > psiFactory )
	{
		this.views = views;
		this.numIterations = numIterations;

		IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Deconvolved image factory: " + psiFactory.getClass().getSimpleName() );

		this.psi = psiFactory.create( views.getPSIDimensions(), new FloatType() );

		this.computeBlockFactory = computeBlockFactory;

		IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Setting up " + computeBlockFactory.numParallelBlocks() + " Block Thread(s), using '" + computeBlockFactory.getClass().getSimpleName() + "'" );

		this.computeBlockThreads = new ArrayList<>();

		for ( int i = 0; i < computeBlockFactory.numParallelBlocks(); ++i )
			computeBlockThreads.add( computeBlockFactory.create( i ) );

		IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Inititalizing PSI image using '" + psiInitFactory.getClass().getSimpleName() + "'" );

		final PsiInit psiInit = psiInitFactory.createPsiInitialization();

		IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Running PSI image '" + psiInit.getClass().getSimpleName() + "'" );

		if ( !psiInit.runInitialization( psi, views.getViews(), views.getExecutorService() ) )
		{
			this.max = null;
			this.avgMax = 0;
		}
		else
		{
			this.max = psiInit.getMax();
	
			double avgMaxIntensity = 0;
			for ( int i = 0; i < max.length; ++i )
			{
				avgMaxIntensity += max[ i ];
				IOFunctions.println( "Max intensity in overlapping area of view " + i + ": " + max[ i ] );
			}
			this.avgMax = avgMaxIntensity / (double)max.length;
		}
	}

	public boolean initWasSuccessful() { return max != null; }
	public Img< FloatType > getPSI() { return psi; }
	public void setDebug( final boolean debug ) { this.debug = debug; }
	public CompositeImage getDebugImage() { return ci; }
	public void setDebugInterval( final int debugInterval ) { this.debugInterval = debugInterval; }

	public void runIterations()
	{
		if ( this.max == null )
			return;

		// run the deconvolution
		while ( it < numIterations )
		{
			// show the fused image first
			if ( debug && ( it-1 ) % debugInterval == 0 )
			{
				// if it is slices, wrap & copy otherwise virtual & copy - never use the actual image
				// as it is being updated in the process
				final ImagePlus tmp = FusionTools.getImagePlusInstance( psi, false, "Psi", 0, avgMax, DisplayImage.service );

				if ( this.stack == null )
				{
					this.stack = tmp.getImageStack();
					for ( int i = 0; i < this.psi.dimension( 2 ); ++i )
						this.stack.setSliceLabel( "Iteration 1", i + 1 );

					tmp.setTitle( "debug view" );
					this.ci = new CompositeImage( tmp, CompositeImage.COMPOSITE );
					this.ci.setDimensions( 1, (int)this.psi.dimension( 2 ), 1 );
					this.ci.setDisplayMode( IJ.GRAYSCALE );
					this.ci.show();
				}
				else if ( stack.getSize() == this.psi.dimension( 2 ) )
				{
					final ImageStack t = tmp.getImageStack();
					for ( int i = 0; i < this.psi.dimension( 2 ); ++i )
						this.stack.addSlice( "Iteration 2", t.getProcessor( i + 1 ) );
					this.ci.hide();

					this.ci = new CompositeImage( new ImagePlus( "debug view", this.stack ), CompositeImage.COMPOSITE );
					this.ci.setDimensions( 1, (int)this.psi.dimension( 2 ), 2 );
					this.ci.setDisplayMode( IJ.GRAYSCALE );
					this.ci.show();
				}
				else
				{
					final ImageStack t = tmp.getImageStack();
					for ( int i = 0; i < this.psi.dimension( 2 ); ++i )
						this.stack.addSlice( "Iteration " + i, t.getProcessor( i + 1 ) );

					this.ci.setStack( this.stack, 1, (int)this.psi.dimension( 2 ), stack.getSize() / (int)this.psi.dimension( 2 ) );
				}
			}

			runNextIteration();
		}

		// TODO: IOFunctions.println( "Masking never updated pixels." );
		// maskNeverUpdatedPixels( tmp1, views.getViews() );

		IOFunctions.println( "DONE (" + new Date(System.currentTimeMillis()) + ")." );
	}

	public abstract void runNextIteration();

	protected static final void writeBack( final Img< FloatType > psi, final Vector< Pair< Pair< Integer, Block >, Img< FloatType > > > blockWritebackQueue )
	{
		for ( final Pair< Pair< Integer, Block >, Img< FloatType > > writeBackBlock : blockWritebackQueue )
		{
			long time = System.currentTimeMillis();
			writeBackBlock.getA().getB().pasteBlock( psi, writeBackBlock.getB() );
			System.out.println( " block " + writeBackBlock.getA().getA() + ", (CPU): paste " + (System.currentTimeMillis() - time) );
		}
	}
}
