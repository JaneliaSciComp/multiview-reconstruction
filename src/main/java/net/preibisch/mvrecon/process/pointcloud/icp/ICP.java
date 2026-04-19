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
package net.preibisch.mvrecon.process.pointcloud.icp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import net.imglib2.RealLocalizable;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.LinkedInterestPoint;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.exception.NoSuitablePointsException;

/**
 * Implementation of the ICP, takes anything RealLocalizable and internally uses LinkedInterestPoint to compute the ICP
 *
 * @author Stephan Preibisch
 *
 * @param <P> - something extening point
 */
public class ICP < P extends RealLocalizable >
{
	final List< LinkedInterestPoint< P > > reference, target;

	List< PointMatchGeneric< LinkedInterestPoint< P > > > pointMatches;
	ArrayList< PointMatch > ambigousMatches;
	PointMatchIdentification< P > pointMatchIdentifier;

	final boolean useRANSAC;
	final double maxEpsilonRANSAC;
	final double minInlierRatio;
	final int maxIterationsRANSAC;

	double avgError, maxError;
	int numMatches;
	
	/**
	 * Instantiates a new {@link ICP} object with the {@link List} of target and reference points as well as the {@link PointMatchIdentification} interface that defines
	 * how corresponding points are identified. <br>
	 *
	 * @param target - the {@link List} of target points
	 * @param reference - the {@link List} of reference points
	 * @param pointMatchIdentifier - the {@link PointMatchIdentification} which defines how correspondences are established
	 * @param useRANSAC - use RANSAC during every iteration
	 * @param minInlierRatio - minInlierRatio for RANSAC
	 * @param maxEpsilonRANSAC - max error for RANSAC if used
	 * @param maxIterationsRANSAC - max iterations for RANSAC if used
	 */
	public ICP(
			final List< P > target,
			final List< P > reference,
			final PointMatchIdentification< P > pointMatchIdentifier,
			final boolean useRANSAC,
			final double minInlierRatio,
			final double maxEpsilonRANSAC,
			final int maxIterationsRANSAC )
	{
		this.reference = new ArrayList<>();
		this.target = new ArrayList<>();

		final double[] l = new double[ reference.get( 0 ).numDimensions() ];

		// the LinkedInterestPoint always clones the location array
		for ( final P p : reference )
		{
			p.localize( l );
			this.reference.add( new LinkedInterestPoint< P >( -1, l, p ) );
		}

		// the LinkedInterestPoint always clones the location array
		for ( final P p : target )
		{
			p.localize( l );
			this.target.add( new LinkedInterestPoint< P >( -1, l, p ) );
		}

		this.ambigousMatches = null;
		this.pointMatches = null;

		this.pointMatchIdentifier = pointMatchIdentifier;

		this.useRANSAC = useRANSAC;
		this.minInlierRatio = minInlierRatio;
		this.maxEpsilonRANSAC = maxEpsilonRANSAC;
		this.maxIterationsRANSAC = maxIterationsRANSAC;

		this.avgError = -1;
		this.maxError = -1;
		this.numMatches = -1;
	}

	/**
	 * Also instantiates a new {@link ICP} instance, but uses the {@link SimplePointMatchIdentification} to define corresponding points.
	 * 
	 * @param target - the {@link List} of target points
	 * @param reference - the {@link List} of reference points
	 * @param distanceThreshold - the maximal distance of {@link SimplePointMatchIdentification}, so that the nearest neighbor of a point is still counted as a corresponding point
	 * @param useRANSAC - use RANSAC during every iteration
	 * @param minInlierRatio - minInlierRatio for RANSAC
	 * @param maxEpsilonRANSAC - max error for RANSAC if used
	 * @param maxIterationsRANSAC - max iterations for RANSAC if used
	 */
	public ICP(
			final List< P > target,
			final List< P > reference,
			final double distanceThreshold,
			final boolean useRANSAC,
			final double minInlierRatio,
			final double maxEpsilonRANSAC,
			final int maxIterationsRANSAC )
	{
		this( target, reference, new SimplePointMatchIdentification< P >( distanceThreshold ), useRANSAC, minInlierRatio, maxEpsilonRANSAC, maxIterationsRANSAC );
	}

	/**
	 * Also instantiates a new {@link ICP} instance, but uses the {@link SimplePointMatchIdentification} to define corresponding points.
	 * 
	 * @param target - the {@link List} of target points
	 * @param reference - the {@link List} of reference points
	 * @param useRANSAC - use RANSAC during every iteration
	 * @param minInlierRatio - minInlierRatio for RANSAC
	 * @param maxEpsilonRANSAC - max error for RANSAC if used
	 * @param maxIterationsRANSAC - max iterations for RANSAC if used
	 */
	public ICP(
			final List< P > target,
			final List< P > reference,
			final boolean useRANSAC,
			final double minInlierRatio,
			final double maxEpsilonRANSAC,
			final int maxIterationsRANSAC )
	{
		this( target, reference, new SimplePointMatchIdentification<P>(), useRANSAC, minInlierRatio, maxEpsilonRANSAC, maxIterationsRANSAC );
	}

	/**
	 * Performs one iteration of the {@link ICP}. It takes the last {@link Model} as input to find the corresponding points for the new {@link Model}. 
	 * The result is the new Model, the number of corresponding points, the average, and the maximal error. Note that lastModel and newModel can be the 
	 * same instance and it will be overwritten. 
	 * 
	 * @param lastModel - The last {@link Model} that maps the target.local coordinates to the reference.world coordinates, used to find the corresponding points 
	 * @param newModel - The {@link Model} that maps the target.local coordinates to the reference.world coordinates, will be fitted to the new points
	 * @throws NotEnoughDataPointsException - if there are not enough points
	 * @throws IllDefinedDataPointsException - if the points are ill-defined
	 * @throws NoSuitablePointsException - if points are somehow wrong
	 */
	public void runICPIteration( final Model<?> lastModel, final Model<?> newModel ) throws NotEnoughDataPointsException, IllDefinedDataPointsException, NoSuitablePointsException
	{
		/* apply initial model of the target (from last iteration) */
		for ( final LinkedInterestPoint< P > point : target )
			point.apply( lastModel );
		
		/* get corresponding points for ICP */
		final List< PointMatchGeneric< LinkedInterestPoint< P > > > candidates = pointMatchIdentifier.assignPointMatches( target, reference );

		/* run RANSAC on the identified points if desired */
		final List< PointMatchGeneric< LinkedInterestPoint< P > > > matches = new ArrayList<>();

		if ( useRANSAC )
		{
			newModel.filterRansac( candidates, matches, maxIterationsRANSAC, maxEpsilonRANSAC, minInlierRatio );
			//IOFunctions.println( "RANSAC: " + matches.size() + "/" + candidates.size() );
		}
		else
		{
			matches.addAll( candidates );
		}

		/* remove ambigous correspondences */
		ambigousMatches = removeAmbigousMatches( matches );

		/* fit the model */
		newModel.fit( matches );

		/* apply the new model of the target to determine the error */
		for ( final LinkedInterestPoint< P > point : target )
			point.apply( newModel );

		/* compute the output */
		avgError = meanDistance( matches );
		maxError = maxDistance( matches );
		numMatches = matches.size();
		pointMatches = matches;
	}
		
	/**
	 * Estimates an initial {@link Model} based on some given {@link PointMatch}es. Note that the {@link PointMatch}es have to be stored as PointMatch(target,reference). 
	 * 
	 * @param matches - The {@link List} of apriori known {@link PointMatch}es
	 * @param model - The {@link Model} to use
	 * @throws NotEnoughDataPointsException - if there are not enough points
	 * @throws IllDefinedDataPointsException - if the points are ill-defined
	 */
	public void estimateIntialModel( final List< PointMatchGeneric< LinkedInterestPoint< P > > > matches, final Model<?> model ) throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		/* remove ambigous correspondences */
		ambigousMatches = removeAmbigousMatches( matches );
		
		/* fit the model */
		model.fit( matches );

		/* apply the new model of the target to determine the error */
		for ( final LinkedInterestPoint< P > point : target )
			point.apply( model );

		/* compute the output */
		avgError = meanDistance( matches );
		maxError = maxDistance( matches );
		numMatches = matches.size();
		pointMatches = matches;
	}

	/**
	 * Return the {@link List} of {@link PointMatch}es (target, reference) of the last {@link ICP} iteration
	 * @param pointMatches - the matches
	 * @param <P> - something that extens point
	 * @return - {@link List} of {@link PointMatch}es
	 */
	public static < P extends Point > List< PointMatchGeneric< P > > unwrapPointMatches( final List< ? extends PointMatchGeneric< ? extends LinkedInterestPoint< P > > > pointMatches )
	{
		final ArrayList< PointMatchGeneric< P > > unwrapped = new ArrayList<>();

		for ( final PointMatchGeneric< ? extends LinkedInterestPoint< P > > pm : pointMatches )
		{
			final LinkedInterestPoint< P > p1 = pm.getPoint1();
			final LinkedInterestPoint< P > p2 = pm.getPoint2();

			unwrapped.add( new PointMatchGeneric< P >( p1.getLinkedObject(), p2.getLinkedObject(), pm.getWeight() ) );
		}

		return unwrapped;
	}

	/**
	 * Sets the {@link PointMatchIdentification} that defines how {@link PointMatch}es between reference and target are identified.
	 * The simplest way to do it is the {@link SimplePointMatchIdentification} class which takes the nearest neighbor with a minimal distance threshold. 
	 *  
	 * @param pointMatchIdentifier - the new {@link PointMatchIdentification}
	 */
	public void setPointMatchIdentification( final PointMatchIdentification< P > pointMatchIdentifier ) { this.pointMatchIdentifier = pointMatchIdentifier; }
	
	/**
	 * Returns the current {@link PointMatchIdentification} that is used to identfy corresponding points
	 * @return PointMatchIdentification&gt; P &lt;
	 */
	public PointMatchIdentification< P > getPointMatchIdentification() { return pointMatchIdentifier; }
	
	/**
	 * Return the {@link List} of {@link PointMatch}es (target, reference) of the last {@link ICP} iteration
	 * @return - {@link List} of {@link PointMatch}es
	 */
	public List< PointMatchGeneric< LinkedInterestPoint< P > > > getPointMatches() { return pointMatches; }
	
	/**
	 * Returns the average error of the last ICP iteration, or -1 if no iteration has been computed yet.
	 * @return double - average error
	 */
	public double getAverageError() { return avgError; }

	/**
	 * Returns the maximum error of a {@link PointMatch} of the last ICP iteration, or -1 if no iteration has been computed yet.
	 * @return double - maximal error
	 */
	public double getMaximalError() { return maxError; }
	
	/**
	 * Returns the number of {@link PointMatch}es of the last ICP iteration, or -1 if no iteration has been computed yet.
	 * @return int - number of {@link PointMatch}es
	 */
	public int getNumPointMatches() { return numMatches; }
	
	/**
	 * Returns the number of ambigous {@link PointMatch}es indentified in the last ICP iteration, or -1 if no iteration has been computed yet.
	 * @return int - number of ambigous {@link PointMatch}es
	 */
	public int getNumAmbigousMatches()
	{
		if ( ambigousMatches == null )
			return -1;
		else
			return ambigousMatches.size();
	}
	
	/**
	 * Returns the {@link ArrayList} of ambigous {@link PointMatch}es indentified in the last ICP iteration, or null if no iteration has been computed yet.
	 * @return int - {@link ArrayList} of {@link PointMatch}es
	 */
	public ArrayList<PointMatch> getAmbigousMatches() { return this.ambigousMatches; }

	public List< LinkedInterestPoint< P > > getWrappedTargetPoints() { return target; }
	public List< LinkedInterestPoint< P > > getWrappedReferencePoints() { return reference; }
	
	/**
	 * Detects ambigous (and duplicate) {@link PointMatch}es, i.e. if a {@link Point} corresponds with more than one other {@link Point}
	 * @param matches - the {@link List} of {@link PointMatch}es
	 * @param <P> - something extening point
	 * @return - the {@link ArrayList} containing the removed ambigous or duplicate {@link PointMatch}es 
	 */
	public static < P extends Point & RealLocalizable > ArrayList<PointMatch> removeAmbigousMatches( final List<PointMatchGeneric< P > > matches )
	{
		final ArrayList<Integer> inconsistentCorrespondences = new ArrayList<Integer>();
		final ArrayList<PointMatch> ambigousMatches = new ArrayList<PointMatch>();
		
		for ( int i = 0; i < matches.size(); i++ )
		{
			final Point pointTarget = matches.get( i ).getP1();
			final Point pointReference = matches.get( i ).getP2();

			final ArrayList<Integer> inconsistent = getOccurences( pointTarget, pointReference, matches );
			
			if ( inconsistent.size() > 0 )
				for ( int index : inconsistent )
					if ( !inconsistentCorrespondences.contains( index ) )
						inconsistentCorrespondences.add( index );
		}
	
		if ( inconsistentCorrespondences.size() > 0 )
		{
			Collections.sort( inconsistentCorrespondences );

			for ( int i = inconsistentCorrespondences.size() - 1; i >= 0; i-- )
			{
				// save the ambigous match
				final PointMatch pm = matches.get( (int)inconsistentCorrespondences.get(i) );
				ambigousMatches.add( pm );

				// the cast to (int) is essential as otherwise he is looking to remove the Integer object that does not exist in the list 
				matches.remove( (int)inconsistentCorrespondences.get(i) );
			}
		}

		return ambigousMatches;
	}
	
	/**
	 * Computes if one {@link Point} pair occurs more than once in a {@link List} of {@link PointMatch}es 
	 * 
	 * @param pointTarget - one {@link Point}
	 * @param pointReference - the other {@link Point}
	 * @param list - the {@link List} of {@link PointMatch}es (target, reference)
	 * @param <P> - something extening point
	 * @return - an {@link ArrayList} of indices which should be removed due to duplicate or ambigous occurence
	 */
	protected static < P extends Point & RealLocalizable > ArrayList<Integer> getOccurences( final Point pointTarget, final Point pointReference, List< PointMatchGeneric< P > > list )
	{
		final ArrayList<Integer> occurences = new ArrayList<Integer>();
		
		boolean differentOccurence = false;
				
		/* Test if pointReference has matches with different points than pointTarget */
		for ( final PointMatch pm : list )
		{			
			if ( pm.getP2() == pointReference )
			{
				// it is NOT twice the correct occurence
				if ( pm.getP1() != pointTarget )
				{
					differentOccurence = true;
					break;
				}
			}

			if ( pm.getP1() == pointTarget )
			{
				// it is NOT twice the correct occurence
				if ( pm.getP2() != pointReference )
				{
					differentOccurence = true;
					break;
				}				
			}
		}
		
		if ( differentOccurence )
		{
			/* remove all occurences/matches with pointReference as it is ambigous */
			for ( int i = 0; i < list.size(); i++ )
			{
				final PointMatch pm = list.get( i );
				
				if ( pm.getP2() == pointReference )
					occurences.add( i );				
				
				if ( pm.getP1() == pointTarget )
					occurences.add( i );
			}
		}
		else
		{
			/* remove double occurences */
			boolean sameOccurence = false;

			for ( int i = 0; i < list.size(); i++ )
			{
				final PointMatch pm = list.get( i );
				
				/* remove all but the first occurence/match */
				if ( pm.getP2() == pointReference )
				{
					if ( sameOccurence )
						occurences.add( i );
					else
						sameOccurence = true;
				}
			}			
		}
		
		return occurences;
	}

	static public double meanDistance( final Collection< ? extends PointMatch > matches )
	{
		double d = 0.0;
		for ( final PointMatch match : matches )
			d += match.getDistance();
		return d / matches.size();
	}

	static public double maxDistance( final Collection< ? extends PointMatch > matches )
	{
		double max = -Double.MAX_VALUE;
		for ( final PointMatch match : matches )
		{
			final double d = match.getDistance();
			if ( d > max ) max = d;
		}
		return max;
	}

}
