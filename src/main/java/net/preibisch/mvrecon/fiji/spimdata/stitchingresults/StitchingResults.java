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
package net.preibisch.mvrecon.fiji.spimdata.stitchingresults;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.util.Pair;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;


public class StitchingResults implements PairwiseLinkInterface
{
	Map<Pair<Group<ViewId>, Group<ViewId>>, PairwiseStitchingResult<ViewId>> pairwiseResults;
	// TODO: check and potentially change error calculation (this was done way back when we used absolute shifts)
	Map<ViewId, AffineGet> globalShifts;

	public StitchingResults()
	{
		pairwiseResults = new HashMap<>();
		globalShifts = new HashMap<>();
	}

	public Map< Pair< Group<ViewId>, Group<ViewId> >, PairwiseStitchingResult<ViewId> > getPairwiseResults() { return pairwiseResults; }

	public Map< ViewId, AffineGet > getGlobalShifts() { return globalShifts;	}

	/*
	 * save the PairwiseStitchingResult for a pair of ViewIds, using the sorted ViewIds as a key
	 * use this method to ensure consistency of the pairwiseResults Map 
	 * TODO: we do not sort a.t.m. -> do we need this?
	 * @param pair
	 * @param res
	 */
	public void setPairwiseResultForPair(Pair<Group<ViewId>, Group<ViewId>> pair, PairwiseStitchingResult<ViewId> res )
	{
		pairwiseResults.put( pair, res );
	}	

	public PairwiseStitchingResult<ViewId> getPairwiseResultsForPair(Pair<Group<ViewId>, Group<ViewId>> pair)
	{
		return pairwiseResults.get( pair );
	}

	public void removePairwiseResultForPair(Pair<Group<ViewId>, Group<ViewId>> pair)
	{
		pairwiseResults.remove( pair );
	}

	public ArrayList< PairwiseStitchingResult<ViewId> > getAllPairwiseResultsForViewId(Set<ViewId> vid)
	{
		ArrayList< PairwiseStitchingResult<ViewId> > res = new ArrayList<>();
		for (Pair< Group<ViewId>, Group<ViewId> > p : pairwiseResults.keySet())
		{
			if (p.getA().getViews().equals( vid ) || p.getB().getViews().equals( vid )){
				res.add( pairwiseResults.get( p ) );
			}
		}
		return res;
	}

	/*
	 * TODO: check if this still returns useful values
	 * @param vid
	 * @return
	 */
	public ArrayList< Double > getErrors(Set<ViewId> vid)
	{
		List<PairwiseStitchingResult<ViewId>> psrs = getAllPairwiseResultsForViewId( vid );
		ArrayList< Double > res = new ArrayList<>();
		for (PairwiseStitchingResult <ViewId>psr : psrs)
		{
			if (globalShifts.containsKey( psr.pair().getA()) && globalShifts.containsKey( psr.pair().getB() ))
			{
				double[] vGlobal1 = new double[3];
				double[] vGLobal2 = new double[3];
				double[] vPairwise = new double[3];
				globalShifts.get( psr.pair().getA() ).apply( vGlobal1, vGlobal1 );
				globalShifts.get( psr.pair().getB() ).apply( vGLobal2, vGLobal2 );
				psr.getTransform().apply( vPairwise, vPairwise );
				double[] relativeGlobal = VectorUtil.getVectorDiff( vGlobal1, vGLobal2 );
				res.add( new Double(VectorUtil.getVectorLength(  VectorUtil.getVectorDiff( relativeGlobal, vPairwise ) )) );
			}
		}
		return res;
	}

	public double getAvgCorrelation(Set<ViewId> vid)
	{
		double sum = 0.0;
		int count = 0;
		for (PairwiseStitchingResult<ViewId> psr : pairwiseResults.values())
		{
			if (vid.equals( psr .pair().getA().getViews()) || vid.equals( psr .pair().getB().getViews()))
			{
				sum += psr.r();
				count++;
			}
		}

		if (count == 0)
			return 0;
		else
			return sum/count;
	}

	public static void main(String[] args)
	{
//		StitchingResults sr = new StitchingResults();
//		sr.getPairwiseResults().put( new ValuePair<>(new ViewId( 0, 0 ), new ViewId( 0, 1 )), null );
//		sr.getPairwiseResults().put( new ValuePair<>(new ViewId( 0, 0 ), new ViewId( 0, 1 )), null );
//		sr.getPairwiseResults().put( new ValuePair<>(new ViewId( 0, 1 ), new ViewId( 0, 2 )), null );
//		
//		ArrayList< PairwiseStitchingResult<ViewId> > psr = sr.getAllPairwiseResultsForViewId( new ViewId( 0, 0 ) );
//		System.out.println( psr.size() );
	}

	@Override
	public Set< Pair< Group< ViewId >, Group< ViewId > > > getPairwiseLinks()
	{
		return getPairwiseResults().keySet();
	}
}
