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

import static net.preibisch.mvrecon.fiji.spimdata.stitchingresults.XmlKeysStitchingResults.STICHING_CORRELATION_TAG;
import static net.preibisch.mvrecon.fiji.spimdata.stitchingresults.XmlKeysStitchingResults.STICHING_SHIFT_TAG;
import static net.preibisch.mvrecon.fiji.spimdata.stitchingresults.XmlKeysStitchingResults.STITCHINGRESULTS_TAG;
import static net.preibisch.mvrecon.fiji.spimdata.stitchingresults.XmlKeysStitchingResults.STITCHINGRESULT_PW_TAG;
import static net.preibisch.mvrecon.fiji.spimdata.stitchingresults.XmlKeysStitchingResults.STITCHING_HASH_TAG;
import static net.preibisch.mvrecon.fiji.spimdata.stitchingresults.XmlKeysStitchingResults.STITCHING_TP_A_TAG;
import static net.preibisch.mvrecon.fiji.spimdata.stitchingresults.XmlKeysStitchingResults.STITCHING_TP_B_TAG;
import static net.preibisch.mvrecon.fiji.spimdata.stitchingresults.XmlKeysStitchingResults.STITCHING_VS_A_TAG;
import static net.preibisch.mvrecon.fiji.spimdata.stitchingresults.XmlKeysStitchingResults.STITCHING_VS_B_TAG;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.jdom2.Element;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.XmlHelpers;
import mpicbg.spim.data.generic.base.XmlIoSingleton;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RealInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Intervals;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;



public class XmlIoStitchingResults extends XmlIoSingleton<StitchingResults>
{

	public XmlIoStitchingResults()
	{
		super( STITCHINGRESULTS_TAG, StitchingResults.class );
		handledTags.add( STITCHINGRESULTS_TAG );
	}
	
	public Element toXml( final StitchingResults stitchingResults )
	{
		final Element elem = super.toXml();

		for ( final PairwiseStitchingResult< ViewId > sr : stitchingResults.getPairwiseResults().values() )
			elem.addContent( pairwiseResultToXml( sr ) );

		return elem;
	}
	
	public StitchingResults fromXml( final Element allStitchingResults ) throws SpimDataException
	{
		final StitchingResults stitchingResults = super.fromXml( allStitchingResults );

		for ( final Element pairwiseResultsElement : allStitchingResults.getChildren( STITCHINGRESULT_PW_TAG ) )
		{
			
			
			List< Integer > vsA = Arrays.asList( pairwiseResultsElement.getAttributeValue( STITCHING_VS_A_TAG ).split( "," )).stream().map( s -> Integer.parseInt( s ) ).collect( Collectors.toList() );
			List< Integer > vsB = Arrays.asList( pairwiseResultsElement.getAttributeValue( STITCHING_VS_B_TAG ).split( "," )).stream().map( s -> Integer.parseInt( s ) ).collect( Collectors.toList() );
			List< Integer > tpA = Arrays.asList( pairwiseResultsElement.getAttributeValue( STITCHING_TP_A_TAG ).split( "," )).stream().map( s -> Integer.parseInt( s ) ).collect( Collectors.toList() );
			List< Integer > tpB = Arrays.asList( pairwiseResultsElement.getAttributeValue( STITCHING_TP_B_TAG ).split( "," )).stream().map( s -> Integer.parseInt( s ) ).collect( Collectors.toList() );

			final double[] shift = XmlHelpers.getDoubleArray( pairwiseResultsElement, STICHING_SHIFT_TAG );
			final double corr = XmlHelpers.getDouble( pairwiseResultsElement, STICHING_CORRELATION_TAG );
			// default value for hash for backwards compatibility, the results will most likely be ignored though (which is what we want)
			final double hash = XmlHelpers.getDouble( pairwiseResultsElement, STITCHING_HASH_TAG, 0.0 );

			double[] minmax = null; 
			if (pairwiseResultsElement.getChild( XmlKeysStitchingResults.STICHING_BBOX_TAG ) != null)
				minmax = XmlHelpers.getDoubleArray( pairwiseResultsElement, XmlKeysStitchingResults.STICHING_BBOX_TAG );

			AffineTransform3D transform = new AffineTransform3D();
			// backwards-compatibility with just translation
			if (shift.length == 3)
				transform.setTranslation( shift );
			// tag contains row-packed copy of transformation matrix
			else
				transform.set( shift );

			Group<ViewId> vidsA = new Group<>();
			for (int i = 0; i < vsA.size(); i++)
				vidsA.getViews().add( new ViewId( tpA.get( i ), vsA.get( i ) ) );

			Group<ViewId> vidsB = new Group<>();
			for (int i = 0; i < vsB.size(); i++)
				vidsB.getViews().add( new ViewId( tpB.get( i ), vsB.get( i ) ) );
			
			final ValuePair< Group<ViewId>, Group<ViewId> > pair = new ValuePair<>( vidsA, vidsB );

			// TODO: handle null case (maybe insert a default overlap e.g. (0,0,..) -> (1,1,..) ?)
			RealInterval bb = null;
			if (minmax != null)
				bb  = Intervals.createMinMaxReal( minmax );

			
			final PairwiseStitchingResult< ViewId > pairwiseStitchingResult = new PairwiseStitchingResult<>(pair, bb, transform, corr, hash );
			stitchingResults.setPairwiseResultForPair( pair, pairwiseStitchingResult );
		}

		return stitchingResults;
	}
	
	protected Element pairwiseResultToXml( final PairwiseStitchingResult< ViewId > sr )
	{
		final Element elem = new Element( STITCHINGRESULT_PW_TAG );

		elem.setAttribute( STITCHING_VS_A_TAG, String.join( ",", sr.pair().getA().getViews().stream().map( vi ->  Integer.toString( vi.getViewSetupId() ) ).collect( Collectors.toList() ) ) );
		elem.setAttribute( STITCHING_VS_B_TAG, String.join( ",", sr.pair().getB().getViews().stream().map( vi ->  Integer.toString( vi.getViewSetupId() ) ).collect( Collectors.toList() ) ) );
		elem.setAttribute( STITCHING_TP_A_TAG, String.join( ",", sr.pair().getA().getViews().stream().map( vi ->  Integer.toString( vi.getTimePointId() ) ).collect( Collectors.toList() ) ) );
		elem.setAttribute( STITCHING_TP_B_TAG, String.join( ",", sr.pair().getB().getViews().stream().map( vi ->  Integer.toString( vi.getTimePointId() ) ).collect( Collectors.toList() ) ) );
		
		elem.addContent( XmlHelpers.doubleArrayElement( STICHING_SHIFT_TAG, sr.getTransform().getRowPackedCopy() ) );
		elem.addContent( XmlHelpers.doubleElement(  STICHING_CORRELATION_TAG, sr.r() ) );
		elem.addContent( XmlHelpers.doubleElement(  STITCHING_HASH_TAG, sr.getHash() ) );
		
		final RealInterval bb = sr.getBoundingBox();
		final double[] bbox = new double[bb.numDimensions() * 2];
		for (int d = 0; d < bb.numDimensions(); d++)
		{
			bbox[d] = bb.realMin( d );
			bbox[d+bb.numDimensions()] = bb.realMax( d );
		}
		
		elem.addContent( XmlHelpers.doubleArrayElement( XmlKeysStitchingResults.STICHING_BBOX_TAG, bbox ) );
		
		return elem;
	}

}
