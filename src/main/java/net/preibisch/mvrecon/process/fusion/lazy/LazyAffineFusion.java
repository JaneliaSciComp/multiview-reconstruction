/*
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

package net.preibisch.mvrecon.process.fusion.lazy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import ij.ImageJ;
import mpicbg.models.AffineModel1D;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converter;
import net.imglib2.converter.Converters;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.process.deconvolution.DeconViews;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import util.URITools;

/**
 * BigStitcher Affine Fusion in blocks
 *
 * @author Stephan Preibisch
 * @param <T> type of input and output
 */
public class LazyAffineFusion<T extends RealType<T> & NativeType<T>> implements Consumer<RandomAccessibleInterval<T>>
{
	final T type;
	final long[] globalMin;

	final Converter<FloatType, T> converter;
	final BasicImgLoader imgloader;
	final Collection< ? extends ViewId > viewIds;
	final Map< ViewId, ? extends AffineTransform3D > viewRegistrations;
	final Map< ViewId, ? extends BasicViewDescription< ? > > viewDescriptions;

	final FusionType fusionType;
	final int interpolation;
	final Map< ViewId, AffineModel1D > intensityAdjustments;

	/**
	 * Creates a consumer that will fill the requested RandomAccessibleInterval single-threaded
	 *
	 * @param converter - if type is FloatType, converter can be null; converting inside the lazy construct makes
	 * sense since it often requires to save much less data (8 bit or 16 bit instead of 32-bit float)
	 * @param imgloader - the imgloader to fetch raw data
	 * @param viewIds - which viewids to fuse
	 * @param viewRegistrations - the registrations (must include anisotropy and downsampling if desired)
	 * @param viewDescriptions - the viewdescriptions
	 * @param fusionType - how to combine pixels
	 * @param interpolation - 1==linear, 0==nearest neighbor
	 * @param intensityAdjustments - intensity adjustments, can be null
	 * @param globalMin - the output RAI typically sits at 0,0...0 because it usually is a CachedCellImage (but the actual interval to process in many blocks sits somewhere else)
	 * @param type - which type to fuse
	 */
	public LazyAffineFusion(
			final Converter<FloatType, T> converter,
			final BasicImgLoader imgloader,
			final Collection< ? extends ViewId > viewIds,
			final Map< ViewId, ? extends AffineTransform3D > viewRegistrations,
			final Map< ViewId, ? extends BasicViewDescription< ? > > viewDescriptions,
			final FusionType fusionType,
			final int interpolation,
			final Map< ViewId, AffineModel1D > intensityAdjustments,
			final long[] globalMin,
			final T type )
	{
		// TODO: share cache for content-based fusion if wanted
		this.globalMin = globalMin;
		this.type = type;

		this.converter = converter;
		this.imgloader = imgloader;
		this.viewIds = viewIds;
		this.viewRegistrations = viewRegistrations;
		this.viewDescriptions = viewDescriptions;
		this.fusionType = fusionType;
		this.interpolation = interpolation;
		this.intensityAdjustments = intensityAdjustments;
	}

	// Note: the output RAI typically sits at 0,0...0 because it usually is a CachedCellImage
	// (but the actual interval to process in many blocks sits somewhere else) 
	@Override
	public void accept( final RandomAccessibleInterval<T> output )
	{
		// in world coordinates
		final Interval targetBlock = Intervals.translate( new FinalInterval( output ), globalMin );

		// which views to process is now part of fuseVirtual
		final RandomAccessibleInterval<FloatType> fused =
				FusionTools.fuseVirtual(
						imgloader,
						viewRegistrations,
						viewDescriptions,
						viewIds,
						fusionType,
						interpolation, // linear interpolation
						targetBlock,
						intensityAdjustments ); // intensity adjustments

		finish( fused, output, converter, type );
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	protected static final <T extends RealType<T>> void finish(
			final RandomAccessibleInterval<FloatType> fused,
			final RandomAccessibleInterval<T> output,
			final Converter<FloatType, T> converter,
			final T type )
	{
		final RandomAccessibleInterval<T> converted;

		if ( converter == null && type.getClass().isInstance( new FloatType() ) )
			converted = (RandomAccessibleInterval)(Object)fused;
		else
			converted = Converters.convert( fused, converter, type );

		final Cursor<T> cIn = Views.flatIterable( converted ).cursor();
		final Cursor<T> cOut = Views.flatIterable( output ).cursor();

		final long size = Views.flatIterable( output ).size();

		for ( long i = 0; i < size; ++i )
			cOut.next().set( cIn.next() );
	}

	public static final <T extends RealType<T> & NativeType<T>> RandomAccessibleInterval<T> init(
			final Converter<FloatType, T> converter,
			final BasicImgLoader imgloader,
			final Collection< ? extends ViewId > viewIds,
			final Map< ViewId, ? extends AffineTransform3D > viewRegistrations,
			final Map< ViewId, ? extends BasicViewDescription< ? > > viewDescriptions,
			final FusionType fusionType,
			final int interpolation,
			final Map< ViewId, AffineModel1D > intensityAdjustments,
			final Interval fusionInterval,
			final T type,
			final int[] blockSize )
	{
		final LazyAffineFusion< T > lazyAffineFusion =
				new LazyAffineFusion<>(
						converter,
						imgloader,
						viewIds,
						viewRegistrations,
						viewDescriptions,
						fusionType,
						interpolation,
						intensityAdjustments,
						fusionInterval.minAsLongArray(),
						type.createVariable() );

		return LazyFusionTools.initLazy( lazyAffineFusion, fusionInterval, blockSize, type );
	}

	public static void main( String[] args ) throws SpimDataException
	{
		new ImageJ();

		final SpimData2 data = new XmlIoSpimData2().load( URITools.toURI( "/Users/preibischs/Documents/Microscopy/Stitching/Truman/standard/dataset.xml" ) );

		final ArrayList< ViewId > viewIds = new ArrayList<>();

		for ( final ViewDescription vd : data.getSequenceDescription().getViewDescriptions().values() )
			if ( data.getSequenceDescription().getMissingViews() != null && !data.getSequenceDescription().getMissingViews().getMissingViews().contains( vd ) )
				if ( vd.getViewSetup().getChannel().getId() == 0 )
					viewIds.add( vd );

		final double anisotropy = Double.NaN;//LazyFusionTools.estimateAnisotropy( data, viewIds );
		final double downsampling = Double.NaN;

		Interval bb = LazyFusionTools.getBoundingBox( data, viewIds, null );

		// adjust bounding box
		bb = FusionTools.createAnisotropicBoundingBox( bb, anisotropy ).getA();
		bb = FusionTools.createDownsampledBoundingBox( bb, downsampling ).getA();

		// adjust registrations
		final HashMap< ViewId, AffineTransform3D > registrations =
				TransformVirtual.adjustAllTransforms(
						viewIds,
						data.getViewRegistrations().getViewRegistrations(),
						anisotropy,
						downsampling );

		final RandomAccessibleInterval<FloatType> lazyFused = LazyAffineFusion.init(
				null,//(i,o) -> o.set(i),
				data.getSequenceDescription().getImgLoader(),
				viewIds,
				registrations,
				data.getSequenceDescription().getViewDescriptions(),
				FusionType.AVG_BLEND,
				1, // linear interpolatio
				null, // intensity adjustment
				bb,
				new FloatType(),
				new int[] { 128, 128, 1 } // good blocksize for displaying
				);

		ImageJFunctions.show( lazyFused, DeconViews.createExecutorService() );
	}
}
