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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;

import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.scijava.ui.behaviour.util.Actions;

import bdv.AbstractSpimSource;
import bdv.BigDataViewer;
import bdv.ViewerImgLoader;
import bdv.cache.SharedQueue;
import bdv.spimdata.WrapBasicImgLoader;
import bdv.tools.InitializeViewerState;
import bdv.tools.transformation.TransformedSource;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerPanel;
import bdv.viewer.render.AlphaWeightedAccumulateProjectorARGB;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import net.imglib2.util.Cast;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import util.URITools;

/**
 * Command-line BigDataViewer for intensity-correction coefficients (as written by BigStitcher-Spark's
 * {@code solve-intensities}). Shows every view of the XML, press {@code C} to toggle the correction.
 * Views without coefficients are shown unmodified.
 */
@Command( name = "view-intensities", mixinStandardHelpOptions = true,
		description = "Show a dataset in BigDataViewer with on-the-fly intensity correction (press C to toggle)." )
public class IntensityCorrectionViewer implements Callable< Void >
{
	@Option( names = { "-x", "--xml" }, required = true,
			description = "XML the coefficients were computed on (the regular XML, not the .split.xml), e.g. /nrs/.../dataset.xml" )
	private String xmlURIString;

	@Option( names = { "-i", "--intensityN5Path" }, required = true,
			description = "N5/ZARR/HDF5 container holding the coefficients, e.g. /nrs/.../coefficients.n5" )
	private String intensityURIString;

	@Option( names = { "-s", "--intensityN5Storage" },
			description = "storage format of the coefficients container (default: guess from the -i ending; ZARR2 must be given explicitly)" )
	private StorageFormat storageFormat = null;

	@Option( names = { "--intensityN5Group" }, description = "group under which coefficient datasets are stored (default: \"\")" )
	private String group = "";

	@Option( names = { "--intensityN5Dataset" }, description = "dataset name of each coefficient dataset (default: \"intensity\")" )
	private String dataset = "intensity";

	@Option( names = { "--enabled" }, negatable = true, defaultValue = "true", fallbackValue = "true",
			description = "start with intensity correction enabled; --no-enabled starts with the uncorrected data (default: enabled)" )
	private boolean enabled = true;

	@Option( names = { "--ignoreThreshold" },
			description = "correct every voxel, ignoring the per-view threshold stored with the coefficients, like fusion without "
					+ "--intensityMaskBelowThreshold. Use it for coefficients with an offset (-tm AFFINE): correcting only above the "
					+ "threshold makes a jump of about the offset there (default: false, voxels below the threshold are left unchanged)" )
	private boolean ignoreThreshold = false;

	@Option( names = { "--blend" },
			description = "average overlapping views (default: show every voxel from the view whose center is closest, like fusion "
					+ "with --fusion CLOSEST_PIXEL_WINS, so the seams look as they will in the fused image)" )
	private boolean blend = false;

	@Option( names = { "--anisotropyFactor" },
			description = "z is divided by this when choosing the closest view, as fusion does with --preserveAnisotropy; use 1 for a "
					+ "fusion without it (default: the dataset's average anisotropy, as --preserveAnisotropy computes it)" )
	private Double anisotropyFactor = null;

	// y = 1 * x + 0, for timepoints without coefficients
	static final Coefficients IDENTITY = new Coefficients( new double[][] { { 1 }, { 0 } }, 1, 1, 1 );

	@Override
	public Void call() throws Exception
	{
		final URI intensityURI = URITools.toURI( intensityURIString );
		if ( storageFormat == null )
			storageFormat = guessFormat( intensityURIString );
		if ( storageFormat == null )
			throw new IllegalArgumentException( "Unable to guess format from '" + intensityURIString + "', please specify using -s" );

		final SpimData2 spimData = new XmlIoSpimData2().load( URITools.toURI( xmlURIString ) );
		final Double closestPixelAnisotropy = blend ? null : anisotropyFactor != null ? anisotropyFactor
				: TransformationTools.getAverageAnisotropyFactor( spimData, spimData.getSequenceDescription().getViewDescriptions().values() );
		show( spimData, URITools.instantiateN5Reader( storageFormat, intensityURI ), group, dataset, enabled, ignoreThreshold, closestPixelAnisotropy );
		return null;
	}

	/**
	 * Shows all views of {@code spimData}, wrapping each view that has coefficients in {@code n5Reader}
	 * (at {@code {group}/setup{s}/timepoint{t}/{dataset}}) with switchable intensity correction (key C).
	 * With {@code ignoreThreshold}, every voxel is corrected; otherwise voxels below the threshold stored
	 * with a view's coefficients are left unchanged. With a {@code closestPixelAnisotropy}, every voxel is shown
	 * from the closest view only, as fused with CLOSEST_PIXEL_WINS (see {@link ClosestViewSource}); with
	 * {@code null}, overlapping views are averaged.
	 */
	public static Bdv show(
			final SpimData2 spimData,
			final N5Reader n5Reader,
			final String group,
			final String dataset,
			final boolean enabled,
			final boolean ignoreThreshold,
			final Double closestPixelAnisotropy )
	{
		final AbstractSequenceDescription< ?, ?, ? > seq = spimData.getSequenceDescription();
		final List< Integer > timepointIds = new ArrayList<>();
		seq.getTimePoints().getTimePointsOrdered().forEach( tp -> timepointIds.add( tp.getId() ) );

		final ArrayList< SourceAndConverter< ? > > sources = new ArrayList<>();
		final boolean wrapped = WrapBasicImgLoader.wrapImgLoaderIfNecessary( spimData ); // e.g. TIFF datasets
		BigDataViewer.initSetups( spimData, new ArrayList<>(), sources );
		final ViewerImgLoader viewerImgLoader = seq.getImgLoader() instanceof ViewerImgLoader ? ( ViewerImgLoader ) seq.getImgLoader() : null;
		// the sources keep the wrapper; the dataset must not (it may be the GUI's, which gets saved)
		if ( wrapped )
			WrapBasicImgLoader.removeWrapperIfPresent( spimData );

		final AtomicBoolean enableIntensityCorrection = new AtomicBoolean( enabled );
		final SharedQueue sharedQueue = new SharedQueue( Math.max( 1, Runtime.getRuntime().availableProcessors() - 1 ) );
		final Bdv bdv = BdvFunctions.show( Bdv.options()
				.frameTitle( "Intensity Correction Preview (press C to toggle)" )
				.accumulateProjectorFactory( AlphaWeightedAccumulateProjectorARGB.factory ) );

		// BDV must pump both queues every frame (prepareNextFrame). Unregistered, a cell that missed its first
		// load is never requested again, so the corrected view stayed stale until e.g. a resize asked for other cells.
		bdv.getBdvHandle().getCacheControls().addCacheControl( sharedQueue );
		if ( viewerImgLoader != null )
			bdv.getBdvHandle().getCacheControls().addCacheControl( viewerImgLoader.getCacheControl() );

		// Loading lazily made pressing C read every view's coefficients on the render thread, one view after
		// another and twice per view (plain and volatile source): minutes on thousands of views.
		final List< Integer > setupIds = new ArrayList<>();
		sources.forEach( source -> setupIds.add( getSetupId( source.getSpimSource() ) ) );
		final long t0 = System.currentTimeMillis();
		final Map< Integer, Coefficients[] > loaded = loadCoefficients( n5Reader, group, dataset, setupIds, timepointIds, ignoreThreshold );
		System.out.println( "Loaded coefficients for " + loaded.size() + " setups in " + ( System.currentTimeMillis() - t0 ) + " ms." );

		final List< SourceAndConverter< ? > > toShow = new ArrayList<>();
		int numCorrected = 0;
		for ( final SourceAndConverter< ? > source : sources )
		{
			final int setupId = getSetupId( source.getSpimSource() );
			final Coefficients[] perTimepoint = loaded.get( setupId );

			if ( perTimepoint == null )
			{
				System.out.println( "No coefficients for setup " + setupId + ", showing it uncorrected." );
				toShow.add( source );
				continue;
			}

			final IntFunction< Coefficients > timepointToCoefficients = timepointIndex -> perTimepoint[ timepointIndex ];
			toShow.add( VisualizeIntensityCorrection.wrapWithIntensityCorrectedSource( source, sharedQueue, timepointToCoefficients, enableIntensityCorrection::get ) );
			++numCorrected;
		}

		final List< SourceAndConverter< ? > > shown = closestPixelAnisotropy == null ? toShow
				: ClosestViewSource.wrap( toShow, new ClosestViewSource.Layout( spimData, setupIds, timepointIds, closestPixelAnisotropy ) );
		BdvFunctions.show( Cast.< List< SourceAndConverter< Object > > >unchecked( shown ), timepointIds.size(), Bdv.options().addTo( bdv ) );
		System.out.println( closestPixelAnisotropy == null
				? "Overlapping views are averaged (--blend)."
				: "Every voxel is shown from the view whose center is closest, like fusion with CLOSEST_PIXEL_WINS (z / "
						+ String.format( "%.3f", closestPixelAnisotropy ) + "); --blend averages overlapping views instead." );

		System.out.println( "Coefficients found for " + numCorrected + " of " + sources.size() + " setups. Press C in the viewer to toggle intensity correction." );
		System.out.println( ignoreThreshold
				? "Correcting every voxel (--ignoreThreshold)."
				: "Voxels below each view's stored threshold are left unchanged (--ignoreThreshold corrects every voxel)." );

		final ViewerPanel viewerPanel = bdv.getBdvHandle().getViewerPanel();
		InitializeViewerState.initBrightness( 0.001, 0.999, viewerPanel.state().snapshot(), bdv.getBdvHandle().getConverterSetups() );

		final Actions actions = new Actions( viewerPanel.getInputTriggerConfig() );
		actions.install( bdv.getBdvHandle().getKeybindings(), "intensity-correction" );
		actions.runnableAction( () -> {
			final boolean b = !enableIntensityCorrection.get();
			enableIntensityCorrection.set( b );
			viewerPanel.showMessage( "Intensity Correction " + ( b ? "enabled" : "disabled" ) );
			viewerPanel.requestRepaint();
		}, "toggle intensity correction", "C" );

		return bdv;
	}

	// the reads are small and latency-bound, so many more threads than cores pay off
	private static final int LOAD_THREADS = 32;

	/**
	 * Coefficients of every setup that has any, per timepoint index ({@code IDENTITY} for a timepoint without),
	 * read in parallel. Setups without coefficients are absent from the map.
	 */
	static Map< Integer, Coefficients[] > loadCoefficients(
			final N5Reader n5Reader,
			final String group,
			final String dataset,
			final List< Integer > setupIds,
			final List< Integer > timepointIds,
			final boolean ignoreThreshold )
	{
		final Map< Integer, Coefficients[] > loaded = new ConcurrentHashMap<>();
		final ForkJoinPool pool = new ForkJoinPool( LOAD_THREADS );
		try
		{
			pool.submit( () -> setupIds.parallelStream().forEach( setupId -> {
				final Coefficients[] perTimepoint = new Coefficients[ timepointIds.size() ];
				boolean any = false;
				for ( int i = 0; i < perTimepoint.length; ++i )
				{
					final String path = IntensityCorrection.getCoefficientsDatasetPath( group, dataset, setupId, timepointIds.get( i ) );
					if ( n5Reader.exists( path ) )
					{
						final Coefficients coefficients = CoefficientsIO.load( n5Reader, path );
						perTimepoint[ i ] = ignoreThreshold ? coefficients.withThreshold( Double.NaN ) : coefficients;
						any = true;
					}
					else
						perTimepoint[ i ] = IDENTITY;
				}
				if ( any )
					loaded.put( setupId, perTimepoint );
			} ) ).get();
		}
		catch ( final InterruptedException | ExecutionException e )
		{
			throw new RuntimeException( "loading intensity coefficients failed", e );
		}
		finally
		{
			pool.shutdown();
		}
		return loaded;
	}

	// same rule as BigStitcher-Spark's IntensitySolver / SparkFusion, except that a local Zarr v2 container is recognized
	public static StorageFormat guessFormat( final String uri )
	{
		final String s = uri.toLowerCase().replaceAll( "/+$", "" );
		if ( s.endsWith( ".zarr" ) )
		{
			try
			{
				if ( java.nio.file.Files.exists( java.nio.file.Paths.get( URITools.toURI( uri ) ).resolve( ".zgroup" ) ) )
					return StorageFormat.ZARR2;
			}
			catch ( final Exception e ) {} // not a local path: cannot tell, assume v3
			return StorageFormat.ZARR;
		}
		if ( s.endsWith( ".n5" ) )
			return StorageFormat.N5;
		if ( s.endsWith( ".h5" ) || s.endsWith( ".hdf5" ) )
			return StorageFormat.HDF5;
		return null;
	}

	private static int getSetupId( final Source< ? > source )
	{
		if ( source instanceof TransformedSource )
			return getSetupId( ( ( TransformedSource< ? > ) source ).getWrappedSource() );
		else if ( source instanceof AbstractSpimSource )
			return ( ( AbstractSpimSource< ? > ) source ).getSetupId();
		else
			throw new IllegalArgumentException( "Unexpected source type " + source.getClass() );
	}

	public static void main( final String... args )
	{
		// no System.exit: the viewer window keeps the JVM alive
		final int exitCode = new CommandLine( new IntensityCorrectionViewer() ).execute( args );
		if ( exitCode != 0 )
			System.exit( exitCode );
	}
}
