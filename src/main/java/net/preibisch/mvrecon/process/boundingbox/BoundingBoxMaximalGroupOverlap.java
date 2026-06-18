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
package net.preibisch.mvrecon.process.boundingbox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.Sets;

import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.FinalRealInterval;
import net.imglib2.RealInterval;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.Translation;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;

public class BoundingBoxMaximalGroupOverlap< V extends ViewId > implements BoundingBoxEstimation
{
	final Collection< ? extends Iterable< V > > viewGroups;
	protected final ViewRegistrations vrs;
	protected final Map< Integer, ? extends BasicViewSetup > vss;

	public BoundingBoxMaximalGroupOverlap( final Collection< ? extends Iterable< V > > viewGroups,
			final AbstractSpimData< ? > spimData )
	{
		this.vss = spimData.getSequenceDescription().getViewSetups();
		this.vrs = spimData.getViewRegistrations();
		this.viewGroups = viewGroups;
	}

	public BoundingBoxMaximalGroupOverlap( final Collection< ? extends Iterable< V > > viewGroups,
			final AbstractSequenceDescription< ? extends BasicViewSetup, ?, ? > sd, final ViewRegistrations vrs )
	{
		this.vss = sd.getViewSetups();
		this.vrs = vrs;
		this.viewGroups = viewGroups;
	}
	
	@Override
	public BoundingBox estimate( String title )
	{
		Collection< Collection< Pair< RealInterval, AffineGet > > > viewGroupsInner = new ArrayList< >();

		for ( Iterable< V > group : viewGroups )
		{
			ArrayList< Pair< RealInterval, AffineGet > > viewGroupInnerI = new ArrayList< >();

			for ( ViewId view : group )
			{
				Dimensions viewSize = vss.get( view.getViewSetupId() ).getSize();
				if ( viewSize == null )
				{
					System.err.println( "WARNING: View " + view + " has no size, ignoring it." );
					continue;
				}

				FinalInterval interval = new FinalInterval( viewSize );
				AffineTransform3D model = vrs.getViewRegistration( view ).getModel();

				ValuePair< RealInterval, AffineGet > viewI = new ValuePair< >( interval, model );
				viewGroupInnerI.add( viewI );
			}
			viewGroupsInner.add( viewGroupInnerI );
		}

		final RealInterval maxBoundingInterval = getMinBoundingInterval( viewGroupsInner );
		
		if (maxBoundingInterval == null)
			return null;

		final int[] min = new int[maxBoundingInterval.numDimensions()];
		final int[] max = new int[maxBoundingInterval.numDimensions()];

		for ( int d = 0; d < maxBoundingInterval.numDimensions(); d++ )
		{
			min[d] = (int) Math.floor( maxBoundingInterval.realMin( d ) );
			max[d] = (int) Math.ceil( maxBoundingInterval.realMax( d ) );
		}

		return new BoundingBox( title, min, max );
	}

	public static RealInterval getMinBoundingInterval(Collection<Collection<Pair<RealInterval, AffineGet>>> viewGroups)
	{
		
		// TODO: any problems with using Sets here? if yes, we need newer guava (or write cart. prod)
		List< HashSet< Pair< RealInterval, AffineGet > > > viewGroupsSets = 
				viewGroups.stream().map( viewGroup -> new HashSet<>(viewGroup) ).collect( Collectors.toList() );
		
		Set< List< Pair< RealInterval, AffineGet > > > cartesianProduct = Sets.cartesianProduct( viewGroupsSets );
		
		RealInterval res = null;
		
		for (List< Pair< RealInterval, AffineGet > > combination : cartesianProduct)
		{
			RealInterval boundingInterval = getMinBoundingIntervalSingle(combination);
			
			if (boundingInterval == null)
				continue;
			
			if (res == null)
			{
				res = boundingInterval;
				continue;
			}
			
			res = realUnion( res, boundingInterval );
		}
		
		return res;
		
	}

	/**
	 * Compute the smallest interval that contains both input intervals.
	 * 
	 * Create a {@link FinalInterval} that represents that interval.
	 * 
	 * @param intervalA
	 *            input interval
	 * @param intervalB
	 *            input interval
	 * @return union of input intervals
	 */
	public static FinalRealInterval realUnion( final RealInterval intervalA, final RealInterval intervalB )
	{
		assert intervalA.numDimensions() == intervalB.numDimensions();

		final int n = intervalA.numDimensions();
		final double[] min = new double[ n ];
		final double[] max = new double[ n ];
		for ( int d = 0; d < n; ++d )
		{
			min[ d ] = Math.min( intervalA.realMin( d ), intervalB.realMin( d ) );
			max[ d ] = Math.max( intervalA.realMax( d ), intervalB.realMax( d ) );
		}
		return new FinalRealInterval( min, max );
	}

	/*
	 * Calculate the boundary interval of the intersection of all views (view := interval + transformation)
	 * may be null, if no intersection exists 
	 * @param views
	 */
	public static RealInterval getMinBoundingIntervalSingle(Collection<Pair<RealInterval, AffineGet>> views)
	{
		double[] min = null;
		double[] max = null;
		Integer n = null;


			for (Pair<RealInterval, AffineGet> view : views)
			{
				FinalRealInterval transformedBounds = estimateBounds( view.getA(), view.getB() );
				if (n == null)
					n = transformedBounds.numDimensions();


				double[] minI = new double[n];
				double[] maxI = new double[n];

				transformedBounds.realMin( minI );
				transformedBounds.realMax( maxI );

				if (max == null)
				{
					min = minI.clone();
					max = maxI.clone();
					continue;
				}

				Util.max( min, minI );
				Util.min( max, maxI );
			}

		if (min == null)
			return null;
		
		for (int d = 0; d < n; d++)
			if ( min[d] > max[d] )
				return null;

		return new FinalRealInterval( min, max );
	}

	/**
	 * Calculate the boundary interval of an interval after it has been
	 * transformed by transform.
	 * 
	 * generalized version of code in {@link AffineTransform3D}
	 * 
	 * @param interval - the interval
	 * @param transform - the transform
	 * @return - boundary of transformed interval
	 */
	public static FinalRealInterval estimateBounds( final RealInterval interval, final AffineGet transform )
	{
		final int n =  interval.numDimensions();
		final double[] min = new double[ n];
		final double[] max = new double[ n ];
		final double[] rMin = new double[ n ];
		final double[] rMax = new double[ n ];

		interval.realMin( min );
		interval.realMax( max );
		
		for (int d = 0; d < n; d++)
		{
			rMin[ d ] = Double.MAX_VALUE;
			rMax[ d ] = -Double.MAX_VALUE;
		}

		final double[] f = new double[ n ];
		final double[] g = new double[ n ];

		for (int i = 0; i < (int) Math.pow( 2, n ); i++)
		{
			int j = i;
			for (int d = 0; d < n; d++)
			{
				f[d] = j % 2 == 0 ? min[d] : max[d];
				j /= 2;
			}
			transform.apply( f, g );
			Util.min( rMin, g );
			Util.max( rMax, g );
		}

		return new FinalRealInterval( rMin, rMax );
	}

	public static void main(String[] args)
	{
		Collection<Collection<Pair<RealInterval, AffineGet>>> viewGroups = new ArrayList<>();
		
		Collection<Pair<RealInterval, AffineGet>> viewGroup1 = new ArrayList<>();		
		viewGroup1.add( new ValuePair<>(Intervals.createMinMaxReal(  0,0,1,1 ), new Translation( 0, 0 )) );
		AffineTransform2D rot = new AffineTransform2D();
		rot.rotate( Math.PI / 4 );
		viewGroup1.add( new ValuePair<>(Intervals.createMinMaxReal(  0,0,1,1 ), rot) );
		
		Collection<Pair<RealInterval, AffineGet>> viewGroup2 = new ArrayList<>();		
		viewGroup2.add( new ValuePair<>(Intervals.createMinMaxReal(  0,0,1,1 ), new Translation( -0.5, 0 )) );
		viewGroup2.add( new ValuePair<>(Intervals.createMinMaxReal(  0,0,1,1 ), new Translation( 0, 1.1 )) );
		
		
		viewGroups.add( viewGroup1 );
		viewGroups.add( viewGroup2 );
		
		System.out.println( TransformationTools.printRealInterval( getMinBoundingInterval(  viewGroups ) ) );

	}

}
