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
package net.preibisch.mvrecon.fiji.spimdata.intensityadjust;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;

import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import bdv.img.hdf5.Hdf5ImageLoader;
import bdv.img.n5.N5ImageLoader;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.AllenOMEZarrLoader;
import net.preibisch.mvrecon.process.fusion.intensity.Coefficients;
import net.preibisch.mvrecon.process.fusion.intensity.IntensityCorrection;
import util.URITools;

/**
 * Per-view intensity-correction coefficients (see {@link Coefficients}), computed in the GUI or imported from
 * BigStitcher-Spark's {@code solve-intensities}.
 * <p>
 * Like interest points they live in a container next to the XML ({@code coefficients.ome.zarr}, or
 * {@code coefficients.n5} / {@code coefficients.h5} for N5 / HDF5 datasets), one dataset per view at
 * {@code setup<s>/timepoint<t>/intensity}, the layout BigStitcher-Spark reads with {@code --intensityN5Path}.
 * They are read on first use and written by {@link #save} when the XML is saved.
 */
public class IntensityAdjustments
{
	public static final String GROUP = "";
	public static final String DATASET = "intensity";

	// the reads and writes are small and latency-bound
	private static final int IO_THREADS = 32;

	private final Map< ViewId, Coefficients > coefficients = new HashMap<>();

	/** where the coefficients are on disk, null if never saved */
	private URI container;
	private StorageFormat format;

	/** views whose coefficients may be in {@link #container} but are not read yet, null once read */
	private Collection< ? extends ViewId > unread;

	private boolean modified = false;

	public IntensityAdjustments() {}

	public IntensityAdjustments( final Map< ? extends ViewId, Coefficients > coefficients )
	{
		this.coefficients.putAll( coefficients );
		this.modified = !coefficients.isEmpty();
	}

	/** The coefficients are in {@code container}, for (some of) {@code views}; they are read on first use. */
	public synchronized void setContainer( final URI container, final StorageFormat format, final Collection< ? extends ViewId > views )
	{
		this.container = container;
		this.format = format;
		this.unread = views;
	}

	public synchronized URI getContainer() { return container; }

	public synchronized StorageFormat getFormat() { return format; }

	/** @return all coefficients, read-only (read from the container on first use) */
	public synchronized Map< ViewId, Coefficients > getCoefficients()
	{
		read();
		return Collections.unmodifiableMap( new HashMap<>( coefficients ) );
	}

	/**
	 * @param onlyAboveThreshold keep the threshold stored with each view's coefficients, so that only voxels above
	 *            it are corrected (BigStitcher-Spark fusion's {@code --intensityMaskBelowThreshold}); otherwise
	 *            every voxel is corrected
	 */
	public synchronized Map< ViewId, Coefficients > getCoefficients( final boolean onlyAboveThreshold )
	{
		final Map< ViewId, Coefficients > c = new HashMap<>( getCoefficients() );
		if ( !onlyAboveThreshold )
			c.replaceAll( ( v, x ) -> x.withThreshold( Double.NaN ) );
		return c;
	}

	public synchronized boolean containsAny( final Collection< ? extends ViewId > views )
	{
		read();
		return views.stream().anyMatch( v -> coefficients.containsKey( new ViewId( v.getTimePointId(), v.getViewSetupId() ) ) );
	}

	public synchronized Coefficients get( final ViewId viewId )
	{
		read();
		return coefficients.get( viewId );
	}

	public synchronized boolean isEmpty()
	{
		read();
		return coefficients.isEmpty();
	}

	public synchronized void put( final ViewId viewId, final Coefficients c )
	{
		read();
		coefficients.put( new ViewId( viewId.getTimePointId(), viewId.getViewSetupId() ), c );
		modified = true;
	}

	public synchronized void putAll( final Map< ? extends ViewId, Coefficients > c )
	{
		c.forEach( this::put );
	}

	public synchronized boolean remove( final ViewId viewId )
	{
		read();
		final boolean removed = coefficients.remove( new ViewId( viewId.getTimePointId(), viewId.getViewSetupId() ) ) != null;
		modified |= removed;
		return removed;
	}

	public synchronized boolean isModified() { return modified; }

	private void read()
	{
		if ( unread == null )
			return;

		final Collection< ? extends ViewId > views = unread;
		unread = null;
		final Map< ViewId, Coefficients > loaded = new ConcurrentHashMap<>();
		final ForkJoinPool pool = new ForkJoinPool( IO_THREADS );
		try ( final N5Reader reader = URITools.instantiateN5Reader( format, container ) )
		{
			pool.submit( () -> views.parallelStream().forEach( v -> {
				if ( reader.exists( IntensityCorrection.getCoefficientsDatasetPath( GROUP, DATASET, v.getViewSetupId(), v.getTimePointId() ) ) )
					loaded.put( new ViewId( v.getTimePointId(), v.getViewSetupId() ), IntensityCorrection.readCoefficients( reader, GROUP, DATASET, v ) );
			} ) ).get();
			coefficients.putAll( loaded );
			IOFunctions.println( "Loaded intensity correction coefficients for " + loaded.size() + " views from " + container );
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "WARNING: could not read intensity correction coefficients from " + container + ": " + e );
		}
		finally
		{
			pool.shutdown();
		}
	}

	/**
	 * Writes the coefficients next to the XML in {@code basePath} if they changed or the XML moved, keeping the
	 * name of an existing container. Views of {@code allViews} without coefficients are removed from it.
	 */
	public synchronized void save( final URI basePath, final BasicImgLoader imgLoader, final Collection< ? extends ViewId > allViews )
	{
		final String name = container != null ? URITools.getFileName( container ) : defaultName( defaultFormat( imgLoader ) );
		final URI target = URITools.toURI( URITools.appendName( basePath, name ) );

		if ( !modified && ( container == null || container.equals( target ) ) )
			return;

		read(); // a moved XML takes its coefficients along
		if ( coefficients.isEmpty() && container == null )
			return;

		final StorageFormat targetFormat = container != null ? format : defaultFormat( imgLoader );
		final List< ViewId > views = new ArrayList<>();
		allViews.forEach( v -> views.add( new ViewId( v.getTimePointId(), v.getViewSetupId() ) ) );
		coefficients.keySet().stream().filter( v -> !views.contains( v ) ).forEach( views::add );

		// HDF5 writers are not thread-safe
		final ForkJoinPool pool = new ForkJoinPool( targetFormat == StorageFormat.HDF5 ? 1 : IO_THREADS );
		try ( final N5Writer writer = URITools.instantiateN5Writer( targetFormat, target ) )
		{
			pool.submit( () -> views.parallelStream().forEach( v -> {
				final Coefficients c = coefficients.get( v );
				final String path = IntensityCorrection.getCoefficientsDatasetPath( GROUP, DATASET, v.getViewSetupId(), v.getTimePointId() );
				if ( c != null )
					IntensityCorrection.writeCoefficients( writer, GROUP, DATASET, Map.of( v, c ) );
				else if ( writer.exists( path ) )
					writer.remove( path );
			} ) ).get();
		}
		catch ( final Exception e )
		{
			throw new RuntimeException( "could not save intensity correction coefficients to " + target + ": " + e, e );
		}
		finally
		{
			pool.shutdown();
		}

		IOFunctions.println( "Saved intensity correction coefficients for " + coefficients.size() + " views to " + target );
		container = coefficients.isEmpty() ? null : target;
		format = coefficients.isEmpty() ? null : targetFormat;
		modified = false;
	}

	/** the dataset's own format: N5 or HDF5 for those datasets, Zarr otherwise */
	public static StorageFormat defaultFormat( final BasicImgLoader imgLoader )
	{
		if ( imgLoader instanceof AllenOMEZarrLoader )
			return ( ( AllenOMEZarrLoader ) imgLoader ).getFormat() == StorageFormat.ZARR2 ? StorageFormat.ZARR2 : StorageFormat.ZARR;
		if ( imgLoader instanceof N5ImageLoader )
			return ( ( N5ImageLoader ) imgLoader ).getN5URI().toString().replaceAll( "/+$", "" ).toLowerCase().endsWith( ".zarr" ) ? StorageFormat.ZARR : StorageFormat.N5;
		if ( imgLoader instanceof Hdf5ImageLoader )
			return StorageFormat.HDF5;
		return StorageFormat.ZARR;
	}

	public static String defaultName( final StorageFormat format )
	{
		switch ( format )
		{
		case N5: return "coefficients.n5";
		case HDF5: return "coefficients.h5";
		default: return "coefficients.ome.zarr";
		}
	}
}
