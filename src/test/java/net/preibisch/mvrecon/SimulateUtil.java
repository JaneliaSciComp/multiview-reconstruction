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
package net.preibisch.mvrecon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mpicbg.spim.data.legacy.LegacyImgLoader;
import mpicbg.spim.data.legacy.LegacyImgLoaderWrapper;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Cursor;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.fiji.datasetmanager.DatasetCreationUtils;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBoxes;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;
import net.preibisch.simulation.imgloader.SimulatedBeadsImgLoader;

public class SimulateUtil
{
	/**
	 * delta for testing transformations, locations, etc
	 */
	public static final double delta = 0.00001;

	public static SpimData2 setUp()
	{
		// Generate 2 views with 200 beads, smaller image size for faster tests
		final int[] angles = new int[]{ 0, 45, 90 };
		final int axis = 0;
		final int numPoints = 200;
		final double[] sigma = new double[]{ 1, 1, 3 };
		final FinalInterval range = new FinalInterval( 128, 128, 50 );

		return SpimData2.convert( SimulatedBeadsImgLoader.spimdataExample( angles, axis, numPoints, sigma, range ) );
	}

	public static SpimData2 setUpLarge()
	{
		final int[] angles = new int[]{ 0, 45, 90, 135 };
		final int axis = 0;
		final int numPoints = 1000;
		final double[] sigma = new double[]{ 1, 1, 3 };
		final FinalInterval range = new FinalInterval( 512, 512, 200 );

		return SpimData2.convert( SimulatedBeadsImgLoader.spimdataExample( angles, axis, numPoints, sigma, range ) );
	}

	/**
	 * Round beads: as {@link #setUp()}, but the beads have a sigma of 2 px in every dimension. The dataset the x != y
	 * fixtures ({@link #subsampled}) are derived from: a bead that is subsampled by 2 along one axis is still sampled
	 * there (sigma 1 px), the beads of {@link #setUp()} (sigma 1 px in x and y) would alias.
	 */
	public static SpimData2 setUpRoundBeads()
	{
		final int[] angles = new int[]{ 0, 45, 90 };
		final int axis = 0;
		final int numPoints = 200;
		final double[] sigma = new double[]{ 2, 2, 2 };
		final FinalInterval range = new FinalInterval( 128, 128, 64 );

		return SpimData2.convert( SimulatedBeadsImgLoader.spimdataExample( angles, axis, numPoints, sigma, range ) );
	}

	/**
	 * A dataset with unequal pixel sizes in x and y: every view of {@code data} subsampled by {@code factor} along x
	 * ({@code axis} 0) or y ({@code axis} 1), without smoothing and without an offset, i.e. pixel (i, j, k) of a new
	 * view is pixel (i * fx, j * fy, k) of the old one. The voxel size of that axis is multiplied by the factor and the
	 * views get the registrations of a newly defined dataset ({@link DatasetCreationUtils#createViewRegistrations}:
	 * the "calibration" only), so the calibrated coordinates of the new dataset are the pixel coordinates of the old
	 * one when its voxel size is isotropic. Only for datasets of UnsignedShortType (the simulated ones); interest
	 * points, registrations etc. of {@code data} are not carried over.
	 *
	 * @param data - the dataset to subsample (its image loader is kept and asked for the full images)
	 * @param axis - 0 (x) or 1 (y)
	 * @param factor - the subsampling along that axis, e.g. 2
	 */
	public static SpimData2 subsampled( final SpimData2 data, final int axis, final int factor )
	{
		if ( axis != 0 && axis != 1 )
			throw new IllegalArgumentException( "axis must be 0 (x) or 1 (y), but is " + axis );

		final long[] steps = new long[] { 1, 1, 1 };
		steps[ axis ] = factor;

		final SequenceDescription seq = data.getSequenceDescription();
		final SubsampledLoader legacy = new SubsampledLoader( seq.getImgLoader(), steps );

		final List< ViewSetup > setups = new ArrayList<>();

		for ( final ViewSetup vs : seq.getViewSetupsOrdered() )
		{
			final VoxelDimensions v = vs.getVoxelSize();
			final VoxelDimensions voxelSize = new FinalVoxelDimensions( v.unit(), v.dimension( 0 ) * steps[ 0 ], v.dimension( 1 ) * steps[ 1 ], v.dimension( 2 ) * steps[ 2 ] );
			legacy.voxelSizes.put( vs.getId(), voxelSize );

			// the size of the images the loader serves (not derived from the size the setup of the full dataset claims)
			final Dimensions size = legacy.getImageSize( new ViewId( seq.getTimePoints().getTimePointsOrdered().get( 0 ).getId(), vs.getId() ) );

			setups.add( new ViewSetup( vs.getId(), vs.getName(), size, voxelSize, vs.getTile(), vs.getChannel(), vs.getAngle(), vs.getIllumination() ) );
		}

		final ImgLoader loader = new LegacyImgLoaderWrapper<>( legacy );
		final SequenceDescription subsampled = new SequenceDescription( seq.getTimePoints(), setups, loader, seq.getMissingViews() );

		final ViewRegistrations registrations = DatasetCreationUtils.createViewRegistrations(
				subsampled.getViewDescriptions(),
				DatasetCreationUtils.minResolution( subsampled, subsampled.getViewDescriptions().keySet() ) );

		return new SpimData2( data.getBasePathURI(), subsampled, registrations, new ViewInterestPoints(), new BoundingBoxes(), new PointSpreadFunctions(), new StitchingResults(), new IntensityAdjustments() );
	}

	/** serves the images of another loader subsampled per axis (copies, kept per view) */
	private static class SubsampledLoader implements LegacyImgLoader< UnsignedShortType >
	{
		final ImgLoader full;
		final long[] steps;
		final Map< Integer, VoxelDimensions > voxelSizes = new HashMap<>();
		final Map< ViewId, Img< UnsignedShortType > > images = new HashMap<>();

		SubsampledLoader( final ImgLoader full, final long[] steps )
		{
			this.full = full;
			this.steps = steps;
		}

		@Override
		@SuppressWarnings( "unchecked" )
		public synchronized RandomAccessibleInterval< UnsignedShortType > getImage( final ViewId view )
		{
			Img< UnsignedShortType > img = images.get( view );

			if ( img == null )
			{
				final Object type = full.getSetupImgLoader( view.getViewSetupId() ).getImageType();

				if ( !( type instanceof UnsignedShortType ) )
					throw new IllegalStateException( "Only UnsignedShortType datasets can be subsampled by this test loader, the type is " + type.getClass().getSimpleName() );

				final RandomAccessibleInterval< UnsignedShortType > in =
						Views.subsample( Views.zeroMin( (RandomAccessibleInterval< UnsignedShortType >)full.getSetupImgLoader( view.getViewSetupId() ).getImage( view.getTimePointId() ) ), steps );

				img = ArrayImgs.unsignedShorts( in.dimensionsAsLongArray() );

				final Cursor< UnsignedShortType > from = Views.flatIterable( in ).cursor();
				final Cursor< UnsignedShortType > to = img.cursor();

				while ( to.hasNext() )
					to.next().set( from.next() );

				images.put( new ViewId( view.getTimePointId(), view.getViewSetupId() ), img );
			}

			return img;
		}

		@Override
		public UnsignedShortType getImageType() { return new UnsignedShortType(); }

		@Override
		public RandomAccessibleInterval< FloatType > getFloatImage( final ViewId view, final boolean normalize )
		{
			final RandomAccessibleInterval< UnsignedShortType > in = getImage( view );
			final Img< FloatType > img = ArrayImgs.floats( in.dimensionsAsLongArray() );

			float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;

			final Cursor< UnsignedShortType > from = Views.flatIterable( in ).cursor();
			final Cursor< FloatType > to = img.cursor();

			while ( to.hasNext() )
			{
				final float v = from.next().getRealFloat();
				to.next().set( v );
				min = Math.min( min, v );
				max = Math.max( max, v );
			}

			if ( normalize && max > min )
				for ( final FloatType t : img )
					t.set( ( t.get() - min ) / ( max - min ) );

			return img;
		}

		@Override
		public Dimensions getImageSize( final ViewId view ) { return new FinalDimensions( getImage( view ).dimensionsAsLongArray() ); }

		@Override
		public VoxelDimensions getVoxelSize( final ViewId view ) { return voxelSizes.get( view.getViewSetupId() ); }
	}
}
