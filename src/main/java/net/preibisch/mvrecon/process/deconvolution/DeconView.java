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
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import bdv.util.ConstantRandomAccessible;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.process.cuda.Block;
import net.preibisch.mvrecon.process.cuda.BlockGeneratorFixedSizePrecise;
import net.preibisch.mvrecon.process.cuda.BlockSorter;
import net.preibisch.mvrecon.process.deconvolution.DeconViewPSF.PSFTYPE;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.ImagePortion;

/**
 * One view for the multiview deconvolution, contains image, weight, and PSFs
 *
 * @author stephan.preibisch@gmx.de
 *
 */
public class DeconView
{
	public static int[] defaultBlockSize = new int[]{ 384, 384, 384 };

	final DeconViewPSF psf;
	final RandomAccessibleInterval< FloatType > image, weight;

	final int n, numBlocks;
	final int[] blockSize;
	final List< List< Block > > nonInterferingBlocks;

	String title = null;

	public DeconView(
			final ExecutorService service,
			final RandomAccessibleInterval< FloatType > image,
			final ArrayImg< FloatType, ? > kernel )
	{
		this(
				service,
				image,
				Views.interval(
						new ConstantRandomAccessible< FloatType >(
								new FloatType( 1 ),
								image.numDimensions() ),
						new FinalInterval( image ) ),
				kernel );
	}

	public DeconView(
			final ExecutorService service,
			final RandomAccessibleInterval< FloatType > image,
			final RandomAccessibleInterval< FloatType > weight,
			final ArrayImg< FloatType, ? > kernel )
	{
		this( service, image, weight, kernel, PSFTYPE.INDEPENDENT, defaultBlockSize, 1, true );
	}

	public DeconView(
			final ExecutorService service,
			final RandomAccessibleInterval< FloatType > image,
			final RandomAccessibleInterval< FloatType > weight,
			final ArrayImg< FloatType, ? > kernel,
			final int[] blockSize )
	{
		this( service, image, weight, kernel, PSFTYPE.INDEPENDENT, blockSize, 1, true );
	}

	public DeconView(
			final ExecutorService service,
			final RandomAccessibleInterval< FloatType > image,
			final RandomAccessibleInterval< FloatType > weight,
			final ArrayImg< FloatType, ? > kernel,
			final PSFTYPE psfType,
			final int[] blockSize,
			final boolean filterBlocksForContent )
	{
		this( service, image, weight, kernel, psfType, blockSize, 1, filterBlocksForContent );
	}

	public DeconView(
			final ExecutorService service,
			final RandomAccessibleInterval< FloatType > image,
			final RandomAccessibleInterval< FloatType > weight,
			final ArrayImg< FloatType, ? > kernel,
			final PSFTYPE psfType,
			final int[] blockSize,
			final int minRequiredBlocks,
			final boolean filterBlocksForContent )
	{
		this.n = image.numDimensions();
		this.psf = new DeconViewPSF( kernel, psfType );

		if ( Views.isZeroMin( image ) )
			this.image = image;
		else
			this.image = Views.zeroMin( image );

		if ( Views.isZeroMin( weight ) )
			this.weight = weight;
		else
			this.weight = Views.zeroMin( weight );

		this.blockSize = new int[ n ];

		// define the blocksize so that it is one single block
		for ( int d = 0; d < this.blockSize.length; ++d )
			this.blockSize[ d ] = blockSize[ d ];

		final long[] imgSize = new long[ n ];
		final long[] kernelSize = new long[ n ];

		image.dimensions( imgSize );
		kernel.dimensions( kernelSize );

		final BlockGeneratorFixedSizePrecise blockGenerator = new BlockGeneratorFixedSizePrecise( service, Util.int2long( this.blockSize ) );

		// we need double the kernel size since we convolve twice in one run
		for ( int d = 0; d < n; ++d )
			kernelSize[ d ] = kernelSize[ d ] * 2 - 1;

		final ArrayList< Block > blocks = blockGenerator.divideIntoBlocks( imgSize, kernelSize );

		if ( blocks == null )
		{
			this.numBlocks = -1;
			this.nonInterferingBlocks = null;

			return;
		}
		else
		{
			this.numBlocks = blocks.size();

			IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Number of blocks: " + numBlocks + ", dim=" + Util.printCoordinates( this.blockSize ) + ", Effective size of each block (due to kernel size) " + Util.printCoordinates( blocks.get( 0 ).getEffectiveSize() ) );

			this.nonInterferingBlocks = BlockSorter.sortBlocksBySmallestFootprint( blocks, new FinalInterval( image ), minRequiredBlocks );

			if ( filterBlocksForContent )
			{
				final Pair< Integer, Integer > removed = filterBlocksForContent( nonInterferingBlocks, weight, service );

				if ( removed.getA() > 0 )
					IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Removed " + removed.getA() + " blocks, " + removed.getB() + " entire batches" );
			}
		}
	}

	public void setTitle( final String title ) { this.title = title; }
	public String getTitle() { return this.title; }
	public RandomAccessibleInterval< FloatType > getImage() { return image; }
	public RandomAccessibleInterval< FloatType > getWeight() { return weight; }
	public DeconViewPSF getPSF() { return psf; }
	public int[] getBlockSize() { return blockSize; }
	public List< List< Block > > getNonInterferingBlocks() { return nonInterferingBlocks; }
	public int getNumBlocks() { return numBlocks; }

	@Override
	public String toString()
	{
		if ( title == null )
			return super.toString();
		else
			return getTitle();
	}

	public static Pair< Integer, Integer > filterBlocksForContent( final List< List< Block > > blocksList, final RandomAccessibleInterval< FloatType > weight, final ExecutorService service )
	{
		int removeBlocks = 0;
		int removeBlockBatch = 0;

		for ( int j = blocksList.size() - 1; j >= 0; --j )
		{
			final List< Block > blocks = blocksList.get( j );

			for ( int i = blocks.size() - 1; i >= 0; --i )
			{
				if ( !blockContainsContent( blocks.get( i ), weight, service ) )
				{
					blocks.remove( i );
					++removeBlocks;
				}
			}

			if ( blocks.size() == 0 )
			{
				blocksList.remove( j );
				++removeBlockBatch;
			}
		}

		return new ValuePair<>( removeBlocks, removeBlockBatch );
	}

	public static boolean blockContainsContent( final Block blockStruct, final RandomAccessibleInterval< FloatType > weight, final ExecutorService service )
	{
		final IterableInterval< FloatType > toTest = Views.iterable( Views.interval( Views.extendZero( weight ), blockStruct ) );

		final Vector< ImagePortion > portions = FusionTools.divideIntoPortions( toTest.size() );
		final ArrayList< Callable< Boolean > > tasks = new ArrayList<>();

		for ( final ImagePortion portion : portions )
		{
			tasks.add( new Callable< Boolean >()
			{
				@Override
				public Boolean call() throws Exception
				{
					final Cursor< FloatType > c = toTest.cursor();

					c.jumpFwd( portion.getStartPosition() );

					for ( long l = 0; l < portion.getLoopSize(); ++l )
						if ( c.next().get() != 0.0 )
							return true;

					return false;
				}
			});
		}

		try
		{
			// invokeAll() returns when all tasks are complete
			for ( final Future< Boolean > results : service.invokeAll( tasks ) )
				if ( results.get() == true )
					return true;

			return false;
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "Failed to identify if block contains data: " + e );
			e.printStackTrace();
			return true;
		}
	}
}
