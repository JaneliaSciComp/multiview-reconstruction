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
package net.preibisch.mvrecon.process.fusion.intensity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Localizable;
import net.imglib2.RealLocalizable;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.position.FunctionRealRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.Type;
import net.imglib2.type.logic.BoolType;
import net.imglib2.type.mask.Masked;
import net.imglib2.util.Cast;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;

/**
 * A {@link Source} that is transparent wherever another view's center is closer, so that BigDataViewer's
 * alpha-weighted projector shows every voxel from one view only, the way fusion with {@code CLOSEST_PIXEL_WINS}
 * picks it ({@code blk.Distance}): smallest squared distance, in fused coordinates, to the view's transformed
 * center, among the views whose interval contains the voxel. Fused coordinates are global coordinates with z
 * divided by the anisotropy factor, as with {@code --preserveAnisotropy}.
 * <p>
 * Uses the registrations of the XML, so for a thin-plate-spline fusion the seams are where an affine fusion
 * would put them.
 */
class ClosestViewSource< T, M extends Masked< T > & Type< M > > implements Source< T >
{
	/** Per timepoint index: where every shown view is and which views overlap it. */
	static final class Layout
	{
		private final SpimData2 spimData;
		private final List< Integer > setupIds;
		private final List< Integer > timepointIds;
		private final double zScale;
		private final Map< Integer, Geometry > geometries = new ConcurrentHashMap<>();

		Layout( final SpimData2 spimData, final List< Integer > setupIds, final List< Integer > timepointIds, final double anisotropyFactor )
		{
			this.spimData = spimData;
			this.setupIds = setupIds;
			this.timepointIds = timepointIds;
			this.zScale = 1.0 / anisotropyFactor;
		}

		Geometry geometry( final int t )
		{
			return geometries.computeIfAbsent( t, k -> new Geometry( spimData, setupIds, timepointIds.get( k ), zScale ) );
		}
	}

	static final class Geometry
	{
		final double zScale;
		/** view center in fused coordinates, null if the view is missing */
		final double[][] centers;
		/** global to full-resolution local coordinates, row-packed */
		final double[][] toLocal;
		final long[][] dims;
		/** indices of the views whose bounding box overlaps this one's */
		final int[][] neighbors;

		Geometry( final SpimData2 spimData, final List< Integer > setupIds, final int timepointId, final double zScale )
		{
			this.zScale = zScale;
			final int n = setupIds.size();
			centers = new double[ n ][];
			toLocal = new double[ n ][];
			dims = new long[ n ][];
			final double[][] min = new double[ n ][], max = new double[ n ][];
			for ( int i = 0; i < n; ++i )
			{
				final ViewId viewId = new ViewId( timepointId, setupIds.get( i ) );
				final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( viewId );
				final ViewRegistration reg = spimData.getViewRegistrations().getViewRegistration( viewId );
				if ( vd == null || !vd.isPresent() || reg == null )
					continue;
				reg.updateModel();
				final AffineTransform3D model = reg.getModel();
				dims[ i ] = vd.getViewSetup().getSize().dimensionsAsLongArray();
				toLocal[ i ] = model.inverse().getRowPackedCopy();

				final double[] c = new double[ 3 ];
				for ( int d = 0; d < 3; ++d )
					c[ d ] = ( dims[ i ][ d ] - 1 ) / 2.0; // as blk.Distance
				model.apply( c, c );
				c[ 2 ] *= zScale;
				centers[ i ] = c;

				min[ i ] = new double[] { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
				max[ i ] = new double[] { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
				final double[] p = new double[ 3 ];
				for ( int corner = 0; corner < 8; ++corner )
				{
					for ( int d = 0; d < 3; ++d )
						p[ d ] = ( corner >> d & 1 ) == 0 ? 0 : dims[ i ][ d ] - 1;
					model.apply( p, p );
					for ( int d = 0; d < 3; ++d )
					{
						min[ i ][ d ] = Math.min( min[ i ][ d ], p[ d ] );
						max[ i ][ d ] = Math.max( max[ i ][ d ], p[ d ] );
					}
				}
			}

			// ponytail: O(n^2) bounding-box test, ~1 s for 10k views; a spatial index if datasets get much larger
			final List< List< Integer > > overlapping = new ArrayList<>();
			for ( int i = 0; i < n; ++i )
				overlapping.add( new ArrayList<>() );
			for ( int i = 0; i < n; ++i )
				for ( int j = i + 1; j < n; ++j )
					if ( centers[ i ] != null && centers[ j ] != null && overlaps( min[ i ], max[ i ], min[ j ], max[ j ] ) )
					{
						overlapping.get( i ).add( j );
						overlapping.get( j ).add( i );
					}
			neighbors = new int[ n ][];
			for ( int i = 0; i < n; ++i )
				neighbors[ i ] = overlapping.get( i ).stream().mapToInt( Integer::intValue ).toArray();
		}

		private static boolean overlaps( final double[] minA, final double[] maxA, final double[] minB, final double[] maxB )
		{
			for ( int d = 0; d < 3; ++d )
				if ( maxA[ d ] < minB[ d ] || maxB[ d ] < minA[ d ] )
					return false;
			return true;
		}

		/**
		 * Whether view {@code i} wins each voxel of an image placed by {@code sourceToGlobal} (one of view
		 * {@code i}'s mipmap levels).
		 */
		BiConsumer< RealLocalizable, BoolType > closest( final int i, final AffineTransform3D sourceToGlobal )
		{
			final double[] toFused = sourceToGlobal.getRowPackedCopy();
			for ( int k = 8; k < 12; ++k )
				toFused[ k ] *= zScale;
			final int[] nb = neighbors[ i ];
			final double[][] toNeighbor = new double[ nb.length ][];
			for ( int k = 0; k < nb.length; ++k )
			{
				final AffineTransform3D t = new AffineTransform3D();
				t.set( toLocal[ nb[ k ] ] );
				t.concatenate( sourceToGlobal );
				toNeighbor[ k ] = t.getRowPackedCopy();
			}
			final double[] self = centers[ i ];
			return ( pos, out ) -> {
				final double x = pos.getDoublePosition( 0 ), y = pos.getDoublePosition( 1 ), z = pos.getDoublePosition( 2 );
				final double f0 = apply( toFused, 0, x, y, z ), f1 = apply( toFused, 4, x, y, z ), f2 = apply( toFused, 8, x, y, z );
				final double ds = sq( f0 - self[ 0 ] ) + sq( f1 - self[ 1 ] ) + sq( f2 - self[ 2 ] );
				for ( int k = 0; k < nb.length; ++k )
				{
					final double[] c = centers[ nb[ k ] ];
					if ( sq( f0 - c[ 0 ] ) + sq( f1 - c[ 1 ] ) + sq( f2 - c[ 2 ] ) >= ds )
						continue;
					final double[] a = toNeighbor[ k ];
					final long[] dim = dims[ nb[ k ] ];
					if ( inside( apply( a, 0, x, y, z ), dim[ 0 ] ) && inside( apply( a, 4, x, y, z ), dim[ 1 ] ) && inside( apply( a, 8, x, y, z ), dim[ 2 ] ) )
					{
						out.set( false );
						return;
					}
				}
				out.set( true );
			};
		}

		private static double apply( final double[] a, final int row, final double x, final double y, final double z )
		{
			return a[ row ] * x + a[ row + 1 ] * y + a[ row + 2 ] * z + a[ row + 3 ];
		}

		// as blk.Distance: [0, dim-1], and a single-pixel dimension is always inside
		private static boolean inside( final double l, final long dim )
		{
			return dim <= 1 ? l >= 0 && l < dim : l >= 0 && l <= dim - 1;
		}

		private static double sq( final double v )
		{
			return v * v;
		}
	}

	/** {@code sources.get(i)} shows view {@code layout}'s {@code setupIds.get(i)}. */
	static List< SourceAndConverter< ? > > wrap( final List< SourceAndConverter< ? > > sources, final Layout layout )
	{
		final List< SourceAndConverter< ? > > wrapped = new ArrayList<>( sources.size() );
		for ( int i = 0; i < sources.size(); ++i )
			wrapped.add( wrap( Cast.unchecked( sources.get( i ) ), layout, i ) );
		return wrapped;
	}

	@SuppressWarnings( { "rawtypes", "unchecked" } )
	private static < T > SourceAndConverter< T > wrap( final SourceAndConverter< T > source, final Layout layout, final int index )
	{
		final SourceAndConverter volatileSource = source.asVolatile();
		final ClosestViewSource< T, ? > s = new ClosestViewSource<>( source.getSpimSource(), layout, index );
		if ( volatileSource == null )
			return new SourceAndConverter<>( s, source.getConverter() );
		return new SourceAndConverter( s, source.getConverter(), new SourceAndConverter(
				new ClosestViewSource<>( volatileSource.getSpimSource(), layout, index ), volatileSource.getConverter() ) );
	}

	private final Source< T > delegate;
	private final Layout layout;
	private final int index;
	/**
	 * per (timepoint index, level), a new test per thread; ponytail: ignores later manual transforms of the source
	 * (BDV's T key)
	 */
	private final Map< List< Integer >, Supplier< BiConsumer< RealLocalizable, BoolType > > > closest = new ConcurrentHashMap<>();

	private ClosestViewSource( final Source< T > delegate, final Layout layout, final int index )
	{
		this.delegate = delegate;
		this.layout = layout;
		this.index = index;
	}

	private Supplier< BiConsumer< RealLocalizable, BoolType > > closest( final int t, final int level )
	{
		return closest.computeIfAbsent( List.of( t, level ), k -> {
			final AffineTransform3D sourceToGlobal = new AffineTransform3D();
			delegate.getSourceTransform( t, level, sourceToGlobal );
			final Geometry geometry = layout.geometry( t );
			return () -> geometry.closest( index, sourceToGlobal );
		} );
	}

	// Neither is cached: the delegate returns different images when intensity correction is toggled.

	@Override
	public RandomAccessibleInterval< ? extends Masked< T > > getMaskedSource( final int t, final int level )
	{
		final Supplier< BiConsumer< RealLocalizable, BoolType > > test = closest( t, level );
		final FunctionRandomAccessible< BoolType > wins = new FunctionRandomAccessible<>( 3, () -> {
			final BiConsumer< RealLocalizable, BoolType > f = test.get();
			return ( Localizable pos, BoolType out ) -> f.accept( pos, out );
		}, BoolType::new );
		final RandomAccessibleInterval< M > masked = Cast.unchecked( delegate.getMaskedSource( t, level ) );
		return Converters.convert( masked, Views.interval( wins, masked ), ClosestViewSource::mask, masked.getType().createVariable() );
	}

	/**
	 * Decides at the sampled position, not at the voxel's center: at a coarse level, two views' voxels straddling the
	 * seam would otherwise both lose (a dark line) or both win.
	 */
	@Override
	public RealRandomAccessible< ? extends Masked< T > > getInterpolatedMaskedSource( final int t, final int level, final Interpolation method )
	{
		final RealRandomAccessible< M > masked = Cast.unchecked( delegate.getInterpolatedMaskedSource( t, level, method ) );
		final Supplier< BiConsumer< RealLocalizable, BoolType > > test = closest( t, level );
		final FunctionRealRandomAccessible< BoolType > wins = new FunctionRealRandomAccessible< BoolType >( 3, () -> test.get(), BoolType::new );
		return Converters.convert( masked, wins, ClosestViewSource::mask, masked.getType().createVariable() );
	}

	private static < M extends Masked< ? > & Type< M > > void mask( final M in, final BoolType wins, final M out )
	{
		out.set( in );
		if ( !wins.get() )
			out.setMask( 0 );
	}

	@Override
	public boolean isPresent( final int t )
	{
		return delegate.isPresent( t );
	}

	@Override
	public RandomAccessibleInterval< T > getSource( final int t, final int level )
	{
		return delegate.getSource( t, level );
	}

	@Override
	public RealRandomAccessible< T > getInterpolatedSource( final int t, final int level, final Interpolation method )
	{
		return delegate.getInterpolatedSource( t, level, method );
	}

	@Override
	public void getSourceTransform( final int t, final int level, final AffineTransform3D transform )
	{
		delegate.getSourceTransform( t, level, transform );
	}

	@Override
	public T getType()
	{
		return delegate.getType();
	}

	@Override
	public String getName()
	{
		return delegate.getName();
	}

	@Override
	public VoxelDimensions getVoxelDimensions()
	{
		return delegate.getVoxelDimensions();
	}

	@Override
	public int getNumMipmapLevels()
	{
		return delegate.getNumMipmapLevels();
	}

	@Override
	public boolean doBoundingBoxCulling()
	{
		return delegate.doBoundingBoxCulling();
	}
}
