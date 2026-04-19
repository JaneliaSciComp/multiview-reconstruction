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
package net.preibisch.mvrecon.process.fusion.lazy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxTools;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import util.Lazy;

public class LazyFusionTools {

	public static int defaultAffineExpansion = 2;
	public static int defaultNonrigidExpansion = 50;

	public static int[] defaultBlockSize2d = new int[] { 256, 256 };
	public static int[] defaultBlockSize3d = DoGImgLib2.blockSize;

	public static <T extends RealType<T> & NativeType<T>> RandomAccessibleInterval<T> initLazy(
			final Consumer<RandomAccessibleInterval<T>> consumer,
			final Interval fusionInterval,
			final int[] blockSize,
			final T type )
	{
		final RandomAccessibleInterval<T> fused =
				Views.translate(
						Lazy.process(
								fusionInterval,
								blockSize,
								type.createVariable(),
								AccessFlags.setOf(),
								consumer ),
						// TODO: fix cache with block=1
						// TODO: nullcache
						fusionInterval.minAsLongArray() );

		return fused;
	}

	public static double estimateAnisotropy(
			final SpimData data,
			final List< ViewId > viewIds )
	{
		return TransformationTools.getAverageAnisotropyFactor( data, viewIds );
	}

	public static BoundingBox getBoundingBox(
			final SpimData2 data,
			final List< ViewId > viewIds,
			final String boundingBoxName )
			throws IllegalArgumentException
	{
		BoundingBox bb = null;

		if ( boundingBoxName == null )
		{
			bb = BoundingBoxTools.maximalBoundingBox( data, viewIds, "All Views" );
		}
		else
		{
			final List<BoundingBox> boxes = BoundingBoxTools.getAllBoundingBoxes( data, null, false );

			for ( final BoundingBox box : boxes )
				if ( box.getTitle().equals( boundingBoxName ) )
					bb = box;

			if ( bb == null )
			{
				throw new IllegalArgumentException( "Bounding box '" + boundingBoxName + "' not present in XML." );
			}
		}

		return bb;
	}

	public static HashMap< ViewId, Dimensions > assembleDimensions(
			final Collection<? extends ViewId> viewIds,
			final SpimData data )
	{
		return assembleDimensions(viewIds, data.getSequenceDescription().getViewDescriptions() );
	}

	public static HashMap< ViewId, Dimensions > assembleDimensions(
			final Collection< ? extends ViewId > viewIds,
			final Map< ViewId, ? extends BasicViewDescription< ? > > viewDescriptions )
	{
		final HashMap< ViewId, Dimensions > viewDimensions = new HashMap<>();

		for ( final ViewId viewId : viewIds )
			viewDimensions.put( viewId, viewDescriptions.get( viewId ).getViewSetup().getSize() );

		return viewDimensions;
	}

	public static final ArrayList< ViewId > overlappingViewIds(
			final Interval targetBlock,
			final Collection< ? extends ViewId > allViewIds,
			final Map< ? extends ViewId, ? extends AffineTransform3D > viewRegistrations,
			final Map< ? extends ViewId, ? extends Dimensions > viewDimensions,
			final int expandOverlap )
	{
		final ArrayList< ViewId > overlappingViewIds = new ArrayList<>();

		for ( final ViewId viewId : allViewIds )
		{
			// expand to be conservative ...
			final AffineTransform3D t = viewRegistrations.get( viewId );
			final Dimensions dim = viewDimensions.get( viewId );
			final RealInterval ri = t.estimateBounds( new FinalInterval( dim ) );
			final Interval boundingBoxLocal = Intervals.largestContainedInterval( ri );
			final Interval bounds = Intervals.expand( boundingBoxLocal, expandOverlap );

			if ( overlaps( targetBlock, bounds ) )
				overlappingViewIds.add( viewId );
		}

		return overlappingViewIds;
	}

	public static boolean overlaps( final Interval interval1, final Interval interval2 )
	{
		return !Intervals.isEmpty( Intervals.intersect( interval1, interval2 ) );
	}

}
