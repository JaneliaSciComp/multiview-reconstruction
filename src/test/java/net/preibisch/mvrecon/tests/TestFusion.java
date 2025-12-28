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

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ij.ImageJ;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.BlockAlgoUtils;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.blk.BlkAffineFusion;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import util.BlockSupplierUtils;

/**
 * Hybrid test class for fusion functionality.
 * Contains both JUnit @Test methods and a main() method for manual testing with ImageJ display.
 */
public class TestFusion
{
	private SpimData2 spimData;
	private Interval bb;

	@BeforeEach
	public void setUp()
	{
		spimData = SimulateUtil.setUp();
		TestInterestPointDetection.testDoG( spimData, "beads" );
		TestRegistration.testRegistration( spimData, "beads", false );
		bb = TestBoundingBox.testBoundingBox( spimData, false );
	}

	@Test
	public void testFusion()
	{
		final double downsampling = 2.0;
		final double anisotropyFactor = 4.0;

		final Pair<BlockSupplier<UnsignedShortType>, Interval> blks =
				testFusion( spimData, bb, FusionType.AVG_BLEND, downsampling, anisotropyFactor );

		assertEquals( 0, blks.getB().min( 0 ), "Downsampled, anisotropic bounding box should have a specific min X." );
		assertEquals( 0, blks.getB().min( 1 ), "Downsampled, anisotropic bounding box should have a specific min Y." );
		assertEquals( -5, blks.getB().min( 2 ), "Downsampled, anisotropic bounding box should have a specific min Z." );

		assertEquals( 64, blks.getB().max( 0 ), "Downsampled, anisotropic bounding box should have a specific max X." );
		assertEquals( 63, blks.getB().max( 1 ), "Downsampled, anisotropic bounding box should have a specific max Y." );
		assertEquals( 11, blks.getB().max( 2 ), "Downsampled, anisotropic bounding box should have a specific max Z." );

		final RandomAccessibleInterval<UnsignedShortType> img =
				Views.translate( 
						BlockAlgoUtils.cellImg( blks.getA(), blks.getB().dimensionsAsLongArray(), new int[] { 32, 32, 32 } ),
						blks.getB().minAsLongArray() ); // offset

		assertEquals( 0, img.getAt( 0, 0, -5 ).get(), "Expecting specific pixel intensities." );
		assertEquals( 0, img.getAt( 64, 63, 11 ).get(), "Expecting specific pixel intensities." );
		assertEquals( 45, img.getAt( 3, 22, 1 ).get(), "Expecting specific pixel intensities." );
		assertEquals( 44, img.getAt( 39, 41, 3 ).get(), "Expecting specific pixel intensities." );

		System.out.println( "✓ Fusion test passed, output interval: " + Util.printInterval( blks.getB() ) );
	}

	public static Pair< BlockSupplier<UnsignedShortType>, Interval > testFusion(
			final SpimData2 spimData,
			final Interval boundingBox,
			final FusionType fusionType,
			final double downsampling,
			final double anisotropyFactor )
	{
		System.out.println( "Testing virtual fusion..." );

		final List< ViewId > viewIds = new ArrayList< ViewId >();
		viewIds.addAll( spimData.getSequenceDescription().getViewDescriptions().values() );

		final List< ViewId > removed = SpimData2.filterMissingViews( spimData, viewIds );
		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " +  removed.size() + " views because they are not present." );

		// apply anisotropy to bounding box
		Interval boundingBoxFusion = FusionTools.createAnisotropicBoundingBox( boundingBox, anisotropyFactor ).getA();

		// apply downsampling to bounding box
		boundingBoxFusion = FusionTools.createDownsampledBoundingBox( boundingBoxFusion, downsampling ).getA();

		final HashMap< ViewId, AffineTransform3D > registrations =
				TransformVirtual.adjustAllTransforms(
						viewIds,
						spimData.getViewRegistrations().getViewRegistrations(),
						anisotropyFactor,
						downsampling );

		final BlockSupplier<UnsignedShortType> blk = BlkAffineFusion.init(
				(i,o) -> o.set( Math.round( i.get() ) ),
				spimData.getSequenceDescription().getImgLoader(),
				viewIds,
				registrations,
				spimData.getSequenceDescription().getViewDescriptions(),
				fusionType,
				anisotropyFactor,
				1,
				null,
				boundingBoxFusion,
				new UnsignedShortType(),
				new int[] { 64, 64, 64 } );

		return new ValuePair<BlockSupplier<UnsignedShortType>, Interval>( blk, boundingBoxFusion );
	}

	// ========== Manual Testing Method ==========

	public static void main( String[] args )
	{
		new ImageJ();

		SpimData2 spimData = SimulateUtil.setUp();//Large();

		System.out.println( "Views present:" );

		for ( final ViewId viewId : spimData.getSequenceDescription().getViewDescriptions().values() )
			System.out.println( Group.pvid( viewId ) );

		TestInterestPointDetection.testDoG( spimData, "beads" );
		TestRegistration.testRegistration( spimData, "beads", false );
		Interval bb = TestBoundingBox.testBoundingBox( spimData, false );

		final double downsampling = 2.0;
		final double anisotropyFactor = 4.0;
		final boolean useCellImg = false;

		final Pair<BlockSupplier<UnsignedShortType>, Interval> blks =
				testFusion( spimData, bb, FusionType.AVG_BLEND, downsampling, anisotropyFactor );

		final RandomAccessibleInterval< UnsignedShortType > img =
				Views.translate(
						(useCellImg) ?
								BlockAlgoUtils.cellImg( blks.getA(), blks.getB().dimensionsAsLongArray(), new int[] { 32, 32, 32 } ) :
								BlockSupplierUtils.arrayImg( blks.getA(), new FinalInterval( blks.getB().dimensionsAsLongArray() ) ),
						blks.getB().minAsLongArray() ); // offset

		ImageJFunctions.show( img );
	}

}
