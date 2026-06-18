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
package net.preibisch.mvrecon.process.fusion.intensityadjust;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import mpicbg.models.Affine1D;
import mpicbg.models.AffineModel1D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.transformed.TransformView;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class IntensityAdjustmentTools
{
	public static boolean containsAdjustments( final IntensityAdjustments adjustments, final Collection< ? extends ViewId > viewIds )
	{
		for ( final ViewId viewId : viewIds )
			if ( adjustments.getIntensityAdjustments().containsKey( viewId ) )
				return true;

		return false;
	}

	public static < M extends Model< M > & Affine1D< M > > HashMap< ViewId, AffineModel1D > computeIntensityAdjustment(
			final AbstractSpimData< ? > spimData,
			final List< ? extends ViewId > viewIds,
			final M intensityModel,
			Interval bb,
			double downsampling,
			final int maxMatches,
			final Map< ? extends ViewId, AffineModel1D > existingAdjustments )
	{
		if ( !Double.isNaN( downsampling ) )
			bb = TransformVirtual.scaleBoundingBox( bb, 1.0 / downsampling );

		final long[] dim = new long[ bb.numDimensions() ];
		bb.dimensions( dim );

		final ArrayList< RandomAccessibleInterval< FloatType > > images = new ArrayList<>();

		for ( final ViewId viewId : viewIds )
		{
			final BasicImgLoader imgloader = spimData.getSequenceDescription().getImgLoader();
			final ViewRegistration vr = spimData.getViewRegistrations().getViewRegistration( viewId );
			vr.updateModel();
			AffineTransform3D model = vr.getModel();

			if ( !Double.isNaN( downsampling ) )
			{
				model = model.copy();
				TransformVirtual.scaleTransform( model, 1.0 / downsampling );
			}

			// this modifies the model so it maps from a smaller image to the global coordinate space,
			// which applies for the image itself as well as the weights since they also use the smaller
			// input image as reference
			RandomAccessibleInterval inputImg = DownsampleTools.openDownsampled( imgloader, viewId, model );

			if ( existingAdjustments != null && existingAdjustments.containsKey( viewId ) )
				inputImg = Converters.convert(
						FusionTools.convertInput( inputImg ),
						new IntensityAdjuster( existingAdjustments.get( viewId ) ),
						new FloatType() );

			// fuse with nearest neighbor and -1 are intensities outside
			images.add( TransformView.transformView( inputImg, model, bb, -1, 1 ) );
		}

		final int m = images.size();

		final HashMap< Integer, ViewId > viewMap = new HashMap<>();
		final Cursor< FloatType > cursor = Views.iterable( images.get( 0 ) ).localizingCursor();
		final ArrayList< RandomAccess< FloatType > > accesses = new ArrayList<>();

		for ( int i = 0; i < m; ++i )
		{
			accesses.add( images.get( i ).randomAccess() );
			viewMap.put( i, viewIds.get( i ) );
		}

		final HashMap< Pair< Integer, Integer >, ArrayList< PointMatch > > intensityMatches = new HashMap<>();

		for ( int i = 0; i < m - 1; ++i )
			for ( int j = i + 1; j < m; ++j )
				intensityMatches.put( new ValuePair< Integer, Integer >( i, j ), new ArrayList<>() );

		final ArrayList< Pair< Integer, Float > > values = new ArrayList<>();

		while ( cursor.hasNext() )
		{
			cursor.fwd();

			values.clear();

			for ( int i = 0; i < m; ++i )
			{
				final RandomAccess< FloatType > r = accesses.get( i );
				r.setPosition( cursor );

				final float value = r.get().get();

				if ( value >= 0 )
					values.add( new ValuePair< Integer, Float >( i, value ) );
			}

			// there are corresponding intensities
			if ( values.size() > 1 )
				for ( int i = 0; i < values.size() - 1; ++i )
					for ( int j = i + 1; j < values.size(); ++j )
					{
						final ArrayList< PointMatch > matches = intensityMatches.get( new ValuePair< Integer, Integer >( values.get( i ).getA(), values.get( j ).getA() ) );
						matches.add( new PointMatch( new Point( new double[] { values.get( i ).getB() } ), new Point( new double[] { values.get( j ).getB() } ) ) );
					}
		}

		final Random rnd = new Random( 344 );
		for ( final ArrayList< PointMatch > matches : intensityMatches.values() )
		{
			while ( matches.size() >= maxMatches )
				matches.remove( rnd.nextInt( matches.size() ) );
		}

		final HashMap< ViewId, AffineModel1D > newModels = runGlobal( intensityMatches, viewMap, intensityModel );

		if ( existingAdjustments != null )
		{
			IOFunctions.println( "Updating previous intensity mappings ... " );

			for ( final ViewId viewId : newModels.keySet() )
			{
				if ( existingAdjustments.containsKey( viewId ) )
				{
					final AffineModel1D updatedModel = existingAdjustments.get( viewId ).copy();
					String out = Group.pvid( viewId ) + ": " + updatedModel + " >>> ";
					updatedModel.preConcatenate( newModels.get( viewId ) );
					IOFunctions.println( out + updatedModel );
				}
			}
		}

		return newModels;
	}

	/**
	 * @param intensityMatches - all pointmatches for the pairs of images
	 * @param viewMap - links images to ViewIds - integers need to be between 0 and viewMap.keySet().size() - 1
	 * @param model - the model to use
	 * @param <M> - which model type
	 * @return the transformations for each image indexed by integer
	 */
	public static < M extends Model< M > & Affine1D< M > > HashMap< ViewId, AffineModel1D > runGlobal(
			final HashMap< Pair< Integer, Integer >, ArrayList< PointMatch > > intensityMatches,
			final HashMap< Integer, ViewId > viewMap,
			final M model )
	{
		final int m = viewMap.keySet().size();

		// create a new tileconfiguration organizing the global optimization
		final TileConfiguration tc = new TileConfiguration();

		// assemble a list of all tiles
		final HashMap< Integer, Tile< M > > tiles = new HashMap<>();

		for ( int i = 0; i < m; ++i )
			tiles.put( i, new Tile<>( model.copy() ) );

		for ( int i = 0; i < m - 1; ++i )
			for ( int j = i + 1; j < m; ++j )
			{
				final Tile< M > ti = tiles.get( i );
				final Tile< M > tj = tiles.get( j );

				final ArrayList< PointMatch > correspondences = intensityMatches.get( new ValuePair< Integer, Integer >( i, j ) );

				IOFunctions.println( Group.pvid( viewMap.get( i ) )  + " <> " + Group.pvid( viewMap.get( j ) ) + ": " + correspondences.size() );
	
				if ( correspondences.size() > 0 )
					addPointMatches( correspondences, ti, tj );
			}

		for ( int i = 0; i < m; ++i )
		{
			final Tile< M > tile = tiles.get( i );

			if ( tile.getConnectedTiles().size() > 0 || tc.getFixedTiles().contains( tile ) )
				tc.addTile( tile );
		}

		try 
		{
			int unaligned = tc.preAlign().size();
			if ( unaligned > 0 )
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): pre-aligned all tiles but " + unaligned );
			else
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): prealigned all tiles" );

			tc.optimize( 5, 300, 200 );

			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Global optimization of " + 
				tc.getTiles().size() +  " view-tiles:" );
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "):    Avg Error: " + tc.getError() + "px" );
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "):    Min Error: " + tc.getMinError() + "px" );
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "):    Max Error: " + tc.getMaxError() + "px" );
		}
		catch (NotEnoughDataPointsException e)
		{
			IOFunctions.println( "Global optimization failed: " + e );
			e.printStackTrace();
		}
		catch (IllDefinedDataPointsException e)
		{
			IOFunctions.println( "Global optimization failed: " + e );
			e.printStackTrace();
		}

		final HashMap< ViewId, AffineModel1D > result = new HashMap<>();

		final double[] array = new double[ 2 ];
		double minOffset = Double.MAX_VALUE;

		for ( int i = 0; i < m; ++i )
		{
			final Tile< M > tile = tiles.get( i );
			tile.getModel().toArray( array );

			minOffset = Math.min( minOffset, array[ 1 ] );
		}

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Min offset (will be corrected to avoid negative intensities: " + minOffset );
		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Intensity adjustments:" );

		for ( int i = 0; i < m; ++i )
		{
			final Tile< M > tile = tiles.get( i );
			tile.getModel().toArray( array );
			array[ 1 ] -= minOffset;
			final AffineModel1D modelView = new AffineModel1D();
			modelView.set( array[ 0 ], array[ 1 ] );
			result.put( viewMap.get( i ), modelView );

			IOFunctions.println( Group.pvid( viewMap.get( i ) ) + ": " + Util.printCoordinates( array )  );
		}

		return result;
	}

	private final static void addPointMatches( final List< ? extends PointMatch > correspondences, final Tile< ? > tileA, final Tile< ? > tileB )
	{
		final ArrayList< PointMatch > pm = new ArrayList<>();
		pm.addAll( correspondences );

		if ( correspondences.size() > 0 )
		{
			tileA.addMatches( pm );
			tileB.addMatches( PointMatch.flip( pm ) );
			tileA.addConnectedTile( tileB );
			tileB.addConnectedTile( tileA );
		}
	}

}
