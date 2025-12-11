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
package net.preibisch.mvrecon.fiji.spimdata.explorer.popup;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.swing.JMenuItem;

import ij.gui.GenericDialog;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxMaximalGroupOverlap;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.fusion.transformed.FusedRandomAccessibleInterval;
import net.preibisch.mvrecon.process.fusion.transformed.TransformView;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.phasecorrelation.PhaseCorrelation2Util;

public class AnalyzeOverlapCrossCorrelationPopup extends JMenuItem implements ExplorerWindowSetable
{
	private static final long serialVersionUID = 1L;

	ExplorerWindow< ? > panel;

	public static int defaultDownsamplingChoiceIndex = 0; // First available resolution
	public static int defaultMinCorrespondences = 10;

	@Override
	public JMenuItem setExplorerWindow( ExplorerWindow< ? > panel )
	{
		this.panel = panel;
		return this;
	}

	public AnalyzeOverlapCrossCorrelationPopup()
	{
		super( "Analyze Overlap Cross-Correlation ..." );
		this.addActionListener( new MyActionListener() );
	}

	public class MyActionListener implements ActionListener
	{
		@Override
		public void actionPerformed( ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set for " + this.getClass().getSimpleName() );
				return;
			}

			if ( !SpimData2.class.isInstance( panel.getSpimData() ) )
			{
				IOFunctions.println( "Only supported for SpimData2 objects: " + this.getClass().getSimpleName() );
				return;
			}

			final SpimData2 spimData = (SpimData2) panel.getSpimData();
			final List< ViewId > viewIds = ApplyTransformationPopup.getSelectedViews( panel );

			if ( viewIds.isEmpty() )
			{
				IOFunctions.println( "No views selected." );
				return;
			}

			// Get available downsampling levels from dataset metadata
			final String[] downsamplingChoices = DownsampleTools.availableDownsamplings( spimData, viewIds.get( 0 ) );

			// Ask user for parameters
			final GenericDialog gd = new GenericDialog( "Cross-Correlation Analysis Parameters" );
			gd.addChoice( "Downsampling", downsamplingChoices, downsamplingChoices[ Math.min( defaultDownsamplingChoiceIndex, downsamplingChoices.length - 1 ) ] );
			gd.addNumericField( "Minimum_correspondences (0 = analyze all overlaps)", defaultMinCorrespondences, 0 );
			gd.addMessage( "Note: Analysis will be performed on the maximal bounding box\nof the overlap between each pair of selected tiles." );
			gd.addMessage( "Set minimum correspondences to 0 to analyze all overlapping pairs\nwithout requiring pre-computed interest points." );

			gd.showDialog();

			if ( gd.wasCanceled() )
				return;

			defaultDownsamplingChoiceIndex = gd.getNextChoiceIndex();
			defaultMinCorrespondences = (int) gd.getNextNumber();


			new Thread( new Runnable()
			{
				@Override
				public void run()
				{
					analyzeCrossCorrelations( spimData, viewIds, defaultDownsamplingChoiceIndex, defaultMinCorrespondences );
				}
			} ).start();
		}
	}

	public static class OverlapResult
	{
		public ViewId viewA;
		public ViewId viewB;
		public int numCorrespondences;
		public double crossCorrelation;
		public double avgIntensityA;
		public double avgIntensityB;
		public String label;
		public int downsamplingIndex;

		public OverlapResult( ViewId viewA, ViewId viewB, String label, int numCorr, double cc, double avgA, double avgB, int dsIndex )
		{
			this.viewA = viewA;
			this.viewB = viewB;
			this.label = label;
			this.numCorrespondences = numCorr;
			this.crossCorrelation = cc;
			this.avgIntensityA = avgA;
			this.avgIntensityB = avgB;
			this.downsamplingIndex = dsIndex;
		}
	}

	public static void analyzeCrossCorrelations(
			final SpimData2 spimData,
			final List< ViewId > viewIds,
			final int downsamplingIndex,
			final int minCorrespondences )
	{
		IOFunctions.println( "Starting Cross-Correlation Analysis..." );
		IOFunctions.println( "Downsampling index: " + downsamplingIndex );
		IOFunctions.println( "Minimum correspondences: " + minCorrespondences );
		IOFunctions.println( "Number of views: " + viewIds.size() );

		final ExecutorService service = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
		final List< OverlapResult > results = new ArrayList<>();

		final String selectedLabel;

		// Only load interest points if minCorrespondences > 0
		if ( minCorrespondences > 0 )
		{
			// Load all interest points and correspondences
			IOFunctions.println( "Loading interest points and correspondences..." );
			final Map< ViewId, ViewInterestPointLists > interestPoints = new HashMap<>();

			for ( final ViewId viewId : viewIds )
			{
				final ViewInterestPointLists vipl = spimData.getViewInterestPoints().getViewInterestPointLists( viewId );
				interestPoints.put( viewId, vipl );
			}

			// Get available labels (interest point types)
			final Map< String, Integer > labelCounts = new HashMap<>();
			for ( final ViewId viewId : viewIds )
			{
				final ViewInterestPointLists vipl = interestPoints.get( viewId );
				for ( final String label : vipl.getHashMap().keySet() )
				{
					labelCounts.put( label, labelCounts.getOrDefault( label, 0 ) + 1 );
				}
			}

			// Find the most common label
			String tempLabel = null;
			int maxCount = 0;
			for ( final Map.Entry< String, Integer > entry : labelCounts.entrySet() )
			{
				if ( entry.getValue() > maxCount )
				{
					maxCount = entry.getValue();
					tempLabel = entry.getKey();
				}
			}

			if ( tempLabel == null )
			{
				IOFunctions.println( "ERROR: No interest points found in selected views." );
				service.shutdown();
				return;
			}

			selectedLabel = tempLabel;
			IOFunctions.println( "Using interest point label: " + selectedLabel );
		}
		else
		{
			IOFunctions.println( "Minimum correspondences = 0, analyzing all overlapping pairs without checking interest points." );
			selectedLabel = null;
		}

		// Analyze all pairs in parallel
		final List< Future< OverlapResult > > futures = new ArrayList<>();

		for ( int i = 0; i < viewIds.size() - 1; ++i )
		{
			for ( int j = i + 1; j < viewIds.size(); ++j )
			{
				final ViewId viewA = viewIds.get( i );
				final ViewId viewB = viewIds.get( j );

				// Submit pair analysis as a parallel task
				futures.add( service.submit( new Callable< OverlapResult >()
				{
					@Override
					public OverlapResult call()
					{
						return analyzePair( spimData, viewA, viewB, selectedLabel,
								downsamplingIndex, minCorrespondences, service );
					}
				} ) );
			}
		}

		// Collect results from all tasks
		for ( Future< OverlapResult > future : futures )
		{
			try
			{
				final OverlapResult result = future.get();
				if ( result != null )
					results.add( result );
			}
			catch ( Exception e )
			{
				IOFunctions.println( "Error processing pair: " + e.getMessage() );
				e.printStackTrace();
			}
		}

		service.shutdown();

		// Sort by cross-correlation (ascending)
		Collections.sort( results, new Comparator< OverlapResult >()
		{
			@Override
			public int compare( OverlapResult o1, OverlapResult o2 )
			{
				return Double.compare( o1.crossCorrelation, o2.crossCorrelation );
			}
		} );

		// Print results
		IOFunctions.println( "\n========================================" );
		IOFunctions.println( "Cross-Correlation Analysis Results" );
		IOFunctions.println( "========================================" );
		if ( minCorrespondences > 0 )
		{
			IOFunctions.println( String.format( "%-30s %-15s %-15s %-15s %-15s %-15s",
					"Tile Pair", "# Corresp.", "Cross-Corr", "Avg Int. A", "Avg Int. B", "Label" ) );
		}
		else
		{
			IOFunctions.println( String.format( "%-30s %-15s %-15s %-15s",
					"Tile Pair", "Cross-Corr", "Avg Int. A", "Avg Int. B" ) );
		}
		IOFunctions.println( "----------------------------------------" );

		for ( final OverlapResult result : results )
		{
			final String pairName = getTileName( spimData, result.viewA ) + " <-> " + getTileName( spimData, result.viewB );
			if ( minCorrespondences > 0 )
			{
				IOFunctions.println( String.format( "%-30s %-15d %-15.4f %-15.2f %-15.2f %-15s",
						pairName,
						result.numCorrespondences,
						result.crossCorrelation,
						result.avgIntensityA,
						result.avgIntensityB,
						result.label != null ? result.label : "N/A" ) );
			}
			else
			{
				IOFunctions.println( String.format( "%-30s %-15.4f %-15.2f %-15.2f",
						pairName,
						result.crossCorrelation,
						result.avgIntensityA,
						result.avgIntensityB ) );
			}
		}

		IOFunctions.println( "========================================\n" );
	}

	private static OverlapResult analyzePair(
			final SpimData2 spimData,
			final ViewId viewA,
			final ViewId viewB,
			final String label,
			final int downsamplingIndex,
			final int minCorrespondences,
			final ExecutorService service )
	{
		int numCorr = 0;

		// Only check correspondences if minCorrespondences > 0
		if ( minCorrespondences > 0 )
		{
			// Get interest points
			final ViewInterestPointLists viplA = spimData.getViewInterestPoints().getViewInterestPointLists( viewA );
			final ViewInterestPointLists viplB = spimData.getViewInterestPoints().getViewInterestPointLists( viewB );

			if ( viplA.getInterestPointList( label ) == null || viplB.getInterestPointList( label ) == null )
				return null;

			// Count correspondences between A and B
			final Collection< CorrespondingInterestPoints > corrsA = viplA.getInterestPointList( label ).getCorrespondingInterestPointsCopy();

			for ( final CorrespondingInterestPoints cip : corrsA )
			{
				if ( cip.getCorrespondingViewId().equals( viewB ) && cip.getCorrespodingLabel().equals( label ) )
					numCorr++;
			}

			if ( numCorr < minCorrespondences )
			{
				IOFunctions.println( getTileName( spimData, viewA ) + " <-> " + getTileName( spimData, viewB ) +
						": " + numCorr + " correspondences (< " + minCorrespondences + "), skipping." );
				return null;
			}

			IOFunctions.println( "Processing: " + getTileName( spimData, viewA ) + " <-> " + getTileName( spimData, viewB ) +
					" (" + numCorr + " correspondences)" );
		}
		else
		{
			IOFunctions.println( "Processing: " + getTileName( spimData, viewA ) + " <-> " + getTileName( spimData, viewB ) );
		}

		// Compute overlap bounding box
		final List< List< ViewId > > viewGroups = new ArrayList<>();
		final List< ViewId > groupA = new ArrayList<>();
		groupA.add( viewA );
		final List< ViewId > groupB = new ArrayList<>();
		groupB.add( viewB );
		viewGroups.add( groupA );
		viewGroups.add( groupB );

		final BoundingBoxMaximalGroupOverlap< ViewId > bbDet = new BoundingBoxMaximalGroupOverlap<>( viewGroups, spimData );
		final BoundingBox bbOverlap = bbDet.estimate( "overlap" );

		if ( bbOverlap == null )
		{
			IOFunctions.println( "  No overlap found, skipping." );
			return null;
		}

		// Use downsampling index to open image at specified resolution level
		// No need to specify downsampling factors - the index handles it

		try
		{
			final RandomAccessibleInterval< FloatType > imgA = loadImageInBB(
					spimData.getSequenceDescription(),
					spimData.getViewRegistrations(),
					viewA,
					bbOverlap,
					downsamplingIndex );

			final RandomAccessibleInterval< FloatType > imgB = loadImageInBB(
					spimData.getSequenceDescription(),
					spimData.getViewRegistrations(),
					viewB,
					bbOverlap,
					downsamplingIndex );

			// Compute downsampling factor for adaptive sampling
			final double downsamplingFactor = Math.pow( 2, downsamplingIndex );

			// Compute average intensities and cross-correlation
			final double avgA = computeAverageIntensity( imgA, downsamplingFactor );
			final double avgB = computeAverageIntensity( imgB, downsamplingFactor );
			final double cc = PhaseCorrelation2Util.getCorrelation( imgA, imgB );

			IOFunctions.println( "  Cross-correlation: " + cc + ", Avg intensities: " + avgA + ", " + avgB );

			return new OverlapResult( viewA, viewB, label, numCorr, cc, avgA, avgB, downsamplingIndex );
		}
		catch ( Exception e )
		{
			IOFunctions.println( "  Error processing pair: " + e.getMessage() );
			e.printStackTrace();
			return null;
		}
	}

	private static RandomAccessibleInterval< FloatType > loadImageInBB(
			final AbstractSequenceDescription< ?, ?, ? > sd,
			final ViewRegistrations vrs,
			final ViewId viewId,
			final Interval boundingBox,
			final int downsamplingIndex )
	{
		final BasicImgLoader imgLoader = sd.getImgLoader();
		final ViewRegistration vr = vrs.getViewRegistration( viewId );

		// Synchronize access to ViewRegistration to prevent race conditions when running in parallel
		final AffineTransform3D model;
		synchronized ( vr )
		{
			vr.updateModel();
			model = vr.getModel().copy();
		}

		// Load image at the specific resolution level chosen by user
		RandomAccessibleInterval inputImg;

		if ( imgLoader instanceof MultiResolutionImgLoader )
		{
			final MultiResolutionImgLoader mrImgLoader = (MultiResolutionImgLoader) imgLoader;
			final int setupId = viewId.getViewSetupId();
			final int timepointId = viewId.getTimePointId();

			// Load image at user-specified level
			inputImg = mrImgLoader.getSetupImgLoader( setupId ).getImage( timepointId, downsamplingIndex );

			// Get and apply the mipmap transform for this level
			final AffineTransform3D mipmapTransform = mrImgLoader.getSetupImgLoader( setupId ).getMipmapTransforms()[ downsamplingIndex ];
			model.concatenate( mipmapTransform );

			// Use bounding box in world coordinates (do NOT scale it)
			// The model transform (with mipmap concatenated) handles the coordinate mapping
			// NOTE: The result image will have the same dimensions regardless of downsampling level
			// because the bounding box is in world coordinates. The speedup from downsampling comes
			// from faster access to the pre-downsampled input image, not from smaller output dimensions.
			final Interval bb = new FinalInterval( boundingBox );

			// Transform view to bounding box
			return TransformView.transformView( inputImg, model, bb, 0, 1 );
		}
		else
		{
			// Fallback for non-multiresolution loaders: load full resolution
			inputImg = imgLoader.getSetupImgLoader( viewId.getViewSetupId() ).getImage( viewId.getTimePointId() );

			// Compute downsampling factor: 2^index
			final double downsamplingFactor = Math.pow( 2, downsamplingIndex );
			final double[] downsamplingFactors = new double[ 3 ];
			for ( int d = 0; d < 3; ++d )
				downsamplingFactors[ d ] = downsamplingFactor;

			// Scale bounding box
			final Interval bbSc = TransformVirtual.scaleBoundingBox( new FinalInterval( boundingBox ), inverse( downsamplingFactors ) );

			// Scale transform
			TransformVirtual.scaleTransform( model, inverse( downsamplingFactors ) );

			// Transform view to bounding box
			return TransformView.transformView( inputImg, model, bbSc, 0, 1 );
		}
	}

	private static double[] inverse( double[] in )
	{
		final double[] res = new double[ in.length ];
		for ( int i = 0; i < in.length; i++ )
			res[ i ] = 1.0 / in[ i ];
		return res;
	}

	private static double computeAverageIntensity( final RandomAccessibleInterval< FloatType > img, final double downsamplingFactor )
	{
		// Sample every Nth pixel in each dimension for speed
		// Scale sampling step with downsampling level: at 8x downsampling, sample 2x less frequently than at 4x
		final int baseStep = 4;
		final int step = Math.max( 1, (int) (baseStep * Math.sqrt( downsamplingFactor )) );

		double sum = 0;
		long count = 0;

		final long[] min = new long[ img.numDimensions() ];
		final long[] max = new long[ img.numDimensions() ];
		img.min( min );
		img.max( max );

		final net.imglib2.RandomAccess< FloatType > ra = img.randomAccess();
		final long[] pos = new long[ img.numDimensions() ];

		// Sample pixels at regular intervals
		if ( img.numDimensions() == 3 )
		{
			for ( long z = min[2]; z <= max[2]; z += step )
			{
				pos[2] = z;
				for ( long y = min[1]; y <= max[1]; y += step )
				{
					pos[1] = y;
					for ( long x = min[0]; x <= max[0]; x += step )
					{
						pos[0] = x;
						ra.setPosition( pos );
						sum += ra.get().get();
						count++;
					}
				}
			}
		}
		else if ( img.numDimensions() == 2 )
		{
			for ( long y = min[1]; y <= max[1]; y += step )
			{
				pos[1] = y;
				for ( long x = min[0]; x <= max[0]; x += step )
				{
					pos[0] = x;
					ra.setPosition( pos );
					sum += ra.get().get();
					count++;
				}
			}
		}
		else
		{
			// Fallback for other dimensions: use cursor (slower)
			final Cursor< FloatType > cursor = Views.iterable( img ).cursor();
			while ( cursor.hasNext() )
			{
				cursor.fwd();
				sum += cursor.get().get();
				count++;
			}
		}

		return count > 0 ? sum / count : 0;
	}

	private static String getTileName( final SpimData2 spimData, final ViewId viewId )
	{
		final BasicViewDescription< ? > vd = spimData.getSequenceDescription().getViewDescriptions().get( viewId );
		if ( vd != null )
			return Group.pvid( viewId );
		else
			return viewId.toString();
	}
}
