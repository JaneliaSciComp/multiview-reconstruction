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

import java.util.Arrays;

import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Util;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.interestpointembedding.EmbeddingModel.Contract;

/**
 * Samples the input of an {@link EmbeddingModel} around interest points: per point one isotropic cube per extent,
 * {@code patch}^3 trilinear samples from the pyramid level that fits the cube's spacing, NaN outside the image. Exactly
 * pcmatch's {@code patches.extract_multiscale} (2026-10-30, {@code SCALESPACE_TASK_onnx.md} section 3), so that the rows
 * Java computes are the rows pcmatch computes:
 * <ol>
 * <li>the point's sigma: the label's detection sigma {@code σ} ({@link DetectionScale}, full-resolution xy pixels); if
 * every point of the view carries its own sigma (the scale space), {@code max(sigma_i, σ)} quantised to steps of
 * 2^(1/12) ({@link #quantise}); a label without the attribute uses {@code σ} unquantised;</li>
 * <li>per extent {@code k}: the sample spacing {@code d = k σ_i / patch} in full-resolution xy pixels (the cube's edge is {@code k σ_i});</li>
 * <li>the pyramid level: the finest whose xy factor is {@code <= d + 1e-6} (level 0 if none), but never finer than the first
 * level whose factor is {@code >=} the detection's downsampling ({@code read_floor} "detection": no cube is read from data
 * finer than the DoG saw; the coarsest level if none is that coarse);</li>
 * <li>the grid: the point in level voxels {@code (x/fx, y/fy, z/fz)} (the levels' half-voxel offsets are ignored, as pcmatch
 * does); sample {@code (iz, iy, ix)} at {@code + ((ix - r) d/fx, (iy - r) d (vx/vy)/fy, (iz - r) d (vx/vz)/fz)}, {@code r = (patch - 1)/2}:
 * isotropic in physical units (step {@code d vx} along every axis), centred on the point;</li>
 * <li>trilinear interpolation of the level's voxels; a sample is valid iff every coordinate is within {@code [0, size - 1]} of
 * the level, otherwise it is NaN (scipy's {@code mode="constant"}); intensities are the stored values as float, no scaling.</li>
 * </ol>
 * The patches go into the model's input as {@code (point, channel = extent, z, y, x)}.
 *
 * A {@link Sampler} holds the random accesses of one thread; the extractor itself is shared.
 *
 * @author Stephan Preibisch
 */
public class PatchExtractor
{
	/** steps per octave the per-point sigma is quantised to (pcmatch's {@code scale_groups}) */
	public static final int SIGMA_STEPS_PER_OCTAVE = 12;

	/** the tolerance of the level choice (pcmatch's {@code pick_levels}) */
	static final double LEVEL_EPSILON = 1e-6;

	/** The image pyramid of one view: the per-level downsampling factors (x, y, z) relative to level 0 and the level images, opened once on request. */
	public static class Pyramid
	{
		final double[][] factors;
		final LevelOpener opener;
		final RandomAccessibleInterval< ? extends RealType< ? > >[] levels;

		public interface LevelOpener
		{
			public RandomAccessibleInterval< ? > open( final int level );
		}

		@SuppressWarnings( "unchecked" )
		public Pyramid( final double[][] factors, final LevelOpener opener )
		{
			this.factors = factors;
			this.opener = opener;
			this.levels = new RandomAccessibleInterval[ factors.length ];

			if ( factors.length == 0 )
				throw new IllegalArgumentException( "a pyramid needs at least one level" );
			for ( final double[] f : factors )
				if ( f.length != 3 || !( f[ 0 ] > 0 && f[ 1 ] > 0 && f[ 2 ] > 0 ) )
					throw new IllegalArgumentException( "invalid downsampling factors " + Arrays.toString( f ) );
		}

		/**
		 * @return the pyramid of a view: all levels of a multi-resolution image loader ({@code getMipmapResolutions()}), the
		 *         one image of any other loader (factors 1, 1, 1)
		 */
		public static Pyramid of( final BasicImgLoader imgLoader, final ViewId view )
		{
			if ( imgLoader instanceof MultiResolutionImgLoader )
			{
				final MultiResolutionSetupImgLoader< ? > setupLoader = ( (MultiResolutionImgLoader) imgLoader ).getSetupImgLoader( view.getViewSetupId() );
				final double[][] factors = setupLoader.getMipmapResolutions();
				return new Pyramid( factors, level -> setupLoader.getImage( view.getTimePointId(), level ) );
			}
			else
				return new Pyramid( new double[][] { { 1, 1, 1 } }, level -> imgLoader.getSetupImgLoader( view.getViewSetupId() ).getImage( view.getTimePointId() ) );
		}

		public int numLevels() { return factors.length; }

		/** @return the downsampling factors (x, y, z) of a level relative to level 0 */
		public double[] factors( final int level ) { return factors[ level ]; }

		/** @return the image of a level (opened on the first request) */
		@SuppressWarnings( { "unchecked", "rawtypes" } )
		public synchronized RandomAccessibleInterval< ? extends RealType< ? > > image( final int level )
		{
			if ( levels[ level ] == null )
			{
				final RandomAccessibleInterval< ? > img = opener.open( level );
				if ( img == null )
					throw new IllegalStateException( "level " + level + " could not be opened" );
				if ( img.numDimensions() != 3 )
					throw new IllegalArgumentException( "level " + level + " has " + img.numDimensions() + " dimensions, expected 3" );
				if ( !( Util.getTypeFromInterval( img ) instanceof RealType ) )
					throw new IllegalArgumentException( "level " + level + " is of type " + Util.getTypeFromInterval( img ).getClass().getSimpleName() + ", expected a real type" );
				levels[ level ] = (RandomAccessibleInterval) img;
			}
			return levels[ level ];
		}
	}

	final Pyramid pyramid;
	final double[] voxelSize;
	final DetectionScale detection;
	final Contract contract;
	final boolean pointSigma;
	final int floorLevel;

	/**
	 * @param pyramid the view's pyramid
	 * @param voxelSize the voxel size of the view (x, y, z; only the ratios matter)
	 * @param detection the scale of the label's detection
	 * @param contract the model's contract (extents, patch, read floor, border)
	 * @param pointSigma whether every point of the view carries its own sigma (then the extents are per point)
	 */
	public PatchExtractor( final Pyramid pyramid, final double[] voxelSize, final DetectionScale detection, final Contract contract, final boolean pointSigma )
	{
		this.pyramid = pyramid;
		this.voxelSize = voxelSize.clone();
		this.detection = detection;
		this.contract = contract;
		this.pointSigma = pointSigma;

		if ( voxelSize.length != 3 || !( voxelSize[ 0 ] > 0 && voxelSize[ 1 ] > 0 && voxelSize[ 2 ] > 0 ) )
			throw new IllegalArgumentException( "invalid voxel size " + Arrays.toString( voxelSize ) );

		// the read floor: the first level whose xy factor is >= the detection's downsampling, the coarsest if none is that coarse
		int floor = 0;
		if ( !contract.readsBelowDetection() && detection.downsampleXY() > 1 )
		{
			floor = pyramid.numLevels() - 1;
			for ( int level = 0; level < pyramid.numLevels(); ++level )
				if ( pyramid.factors( level )[ 0 ] >= detection.downsampleXY() - LEVEL_EPSILON )
				{
					floor = level;
					break;
				}
		}
		this.floorLevel = floor;
	}

	public Pyramid getPyramid() { return pyramid; }

	public DetectionScale getDetection() { return detection; }

	public boolean usesPointSigma() { return pointSigma; }

	/** @return the finest level any cube is read from */
	public int getFloorLevel() { return floorLevel; }

	/** @return a sigma quantised to steps of 2^(1/12) (numpy's round, half to even, as pcmatch) */
	public static double quantise( final double sigma )
	{
		final double steps = Math.rint( Math.log( sigma ) / Math.log( 2 ) * SIGMA_STEPS_PER_OCTAVE );
		return Math.pow( 2, steps / SIGMA_STEPS_PER_OCTAVE );
	}

	/** @return the sigma the extents of a point are multiples of (step 1 of the class comment), in full-resolution xy pixels */
	public double sigmaOf( final InterestPoint point )
	{
		if ( !pointSigma )
			return detection.sigma();

		final double own = point.getSigma();
		final double sigma = Double.isFinite( own ) && own > 0 ? Math.max( own, detection.sigma() ) : detection.sigma();
		return quantise( sigma );
	}

	/** @return the sample spacing of an extent for a sigma, in full-resolution xy pixels (step 2) */
	public double spacing( final double extent, final double sigma ) { return extent * sigma / contract.patch(); }

	/** @return the pyramid level a cube of the given spacing is read from (step 3) */
	public int levelOf( final double spacing )
	{
		int level = 0;
		for ( int l = 0; l < pyramid.numLevels(); ++l )
			if ( pyramid.factors( l )[ 0 ] <= spacing + LEVEL_EPSILON )
				level = l;
		return Math.max( level, floorLevel );
	}

	/** @return a sampler for the calling thread */
	public Sampler sampler() { return new Sampler(); }

	/** The random accesses of one thread, one per level, created on first use. */
	public class Sampler
	{
		@SuppressWarnings( "unchecked" )
		final RandomAccess< ? extends RealType< ? > >[] accesses = new RandomAccess[ pyramid.numLevels() ];
		final long[][] sizes = new long[ pyramid.numLevels() ][];

		private RandomAccess< ? extends RealType< ? > > access( final int level )
		{
			if ( accesses[ level ] == null )
			{
				final RandomAccessibleInterval< ? extends RealType< ? > > img = pyramid.image( level );
				accesses[ level ] = img.randomAccess();
				sizes[ level ] = img.dimensionsAsLongArray();
				// the levels of these loaders start at 0
				if ( img.min( 0 ) != 0 || img.min( 1 ) != 0 || img.min( 2 ) != 0 )
					throw new IllegalArgumentException( "level " + level + " does not start at the origin: " + Arrays.toString( img.minAsLongArray() ) );
			}
			return accesses[ level ];
		}

		/**
		 * Samples the cubes of one point into a model input buffer.
		 *
		 * @param point the point (local coordinates in full-resolution pixels, its sigma attribute)
		 * @param patches the buffer, {@code contract.patchSize()} floats per point
		 * @param offset where the point's floats start
		 */
		public void extract( final InterestPoint point, final float[] patches, final int offset )
		{
			final int p = contract.patch();
			final double r = ( p - 1 ) / 2.0;
			final double sigma = sigmaOf( point );
			final float outside = contract.outside();
			final double x = point.getL()[ 0 ], y = point.getL()[ 1 ], z = point.getL()[ 2 ];
			final double ratioY = voxelSize[ 0 ] / voxelSize[ 1 ], ratioZ = voxelSize[ 0 ] / voxelSize[ 2 ];

			int i = offset;
			for ( int c = 0; c < contract.channels(); ++c )
			{
				final double d = spacing( contract.extents()[ c ], sigma );
				final int level = levelOf( d );
				final RandomAccess< ? extends RealType< ? > > ra = access( level );
				final double[] f = pyramid.factors( level );
				final long[] size = sizes[ level ];
				final double maxX = size[ 0 ] - 1, maxY = size[ 1 ] - 1, maxZ = size[ 2 ] - 1;

				final double px = x / f[ 0 ], py = y / f[ 1 ], pz = z / f[ 2 ];
				final double sx = d / f[ 0 ], sy = d * ratioY / f[ 1 ], sz = d * ratioZ / f[ 2 ];

				for ( int iz = 0; iz < p; ++iz )
				{
					final double cz = pz + ( iz - r ) * sz;
					for ( int iy = 0; iy < p; ++iy )
					{
						final double cy = py + ( iy - r ) * sy;
						for ( int ix = 0; ix < p; ++ix )
						{
							final double cx = px + ( ix - r ) * sx;

							if ( !( cx >= 0 && cx <= maxX && cy >= 0 && cy <= maxY && cz >= 0 && cz <= maxZ ) )
							{
								patches[ i++ ] = outside;
								continue;
							}

							patches[ i++ ] = (float) trilinear( ra, cx, cy, cz, size );
						}
					}
				}
			}
		}

		/** trilinear interpolation at a position inside {@code [0, size - 1]}: the 8 neighbours (the upper one clamped at the edge), weights from the fractional parts */
		private double trilinear( final RandomAccess< ? extends RealType< ? > > ra, final double cx, final double cy, final double cz, final long[] size )
		{
			final long x0 = (long) Math.floor( cx ), y0 = (long) Math.floor( cy ), z0 = (long) Math.floor( cz );
			final double fx = cx - x0, fy = cy - y0, fz = cz - z0;
			final long x1 = Math.min( x0 + 1, size[ 0 ] - 1 ), y1 = Math.min( y0 + 1, size[ 1 ] - 1 ), z1 = Math.min( z0 + 1, size[ 2 ] - 1 );

			double value = 0;
			for ( int dz = 0; dz < 2; ++dz )
			{
				final double wz = dz == 0 ? 1 - fz : fz;
				ra.setPosition( dz == 0 ? z0 : z1, 2 );
				for ( int dy = 0; dy < 2; ++dy )
				{
					final double wy = dy == 0 ? 1 - fy : fy;
					ra.setPosition( dy == 0 ? y0 : y1, 1 );
					for ( int dx = 0; dx < 2; ++dx )
					{
						final double wx = dx == 0 ? 1 - fx : fx;
						final double w = wz * wy * wx;
						if ( w == 0 )
							continue;
						ra.setPosition( dx == 0 ? x0 : x1, 0 );
						value += w * ra.get().getRealDouble();
					}
				}
			}
			return value;
		}
	}
}
