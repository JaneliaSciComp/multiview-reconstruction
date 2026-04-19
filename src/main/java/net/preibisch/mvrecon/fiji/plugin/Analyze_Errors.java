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
package net.preibisch.mvrecon.fiji.plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import ij.ImageJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import mpicbg.models.Point;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Pair;
import net.imglib2.util.RealSum;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.queryXML.GenericLoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class Analyze_Errors implements PlugIn
{

	@Override
	public void run( final String arg0 )
	{
		// ask for everything
		final LoadParseQueryXML result = new LoadParseQueryXML();

		if ( !result.queryXML( "analyze interest point errors", "Analyze", true, true, true, true, true ) )
			return;

		final SpimData2 data = result.getData();
		final List< ViewId > viewIds =
				SpimData2.getAllViewIdsSorted( result.getData(), result.getViewSetupsToProcess(), result.getTimePointsToProcess() );

		final HashMap<String, Double > labelAndWeights = getParameters(data, viewIds);

		final ArrayList<Pair<Pair<ViewId, ViewId>, Double>> errors = getErrors(data, viewIds, labelAndWeights);

		errors.forEach( e -> IOFunctions.println( Group.pvid( e.getA().getA() ) + " <-> " + Group.pvid( e.getA().getB() ) + ": " + e.getB() + " px.") );
	}

	/*
	 * @param data
	 * @param viewIds
	 * @param labelAndWeights
	 * @return - sorted list of weighted errors between pairs of views (big to small errors)
	 */
	public static ArrayList< Pair< Pair< ViewId, ViewId >, Double > > getErrors(
			final SpimData2 data,
			final List< ViewId > viewIds,
			final Map<String, Double > labelAndWeights )
	{
		// load all ViewRegistrations
		final HashMap< ViewId, AffineTransform3D > viewToModel = new HashMap<>();

		viewIds.forEach( viewId -> {
			final ViewRegistration vr = data.getViewRegistrations().getViewRegistration( viewId );
			vr.updateModel();
			viewToModel.put( viewId, vr.getModel() );
		});

		// load all interest points in parallel
		viewIds.parallelStream().forEach( viewId -> {
			final ViewInterestPointLists vip = data.getViewInterestPoints().getViewInterestPointLists( viewId );

			for ( final Entry<String, Double> e : labelAndWeights.entrySet() )
			{
				if ( vip.getInterestPointList( e.getKey() ) != null )
				{
					vip.getInterestPointList( e.getKey() ).getInterestPointsCopy();
					vip.getInterestPointList( e.getKey() ).getCorrespondingInterestPointsCopy();
				}
			}
		});

		// go over all pairs of tiles and compute the error in parallel
		final ArrayList< Pair< ViewId, ViewId > > pairs = new ArrayList<>();

		for ( int i = 0; i < viewIds.size() - 1; ++i )
			for ( int j = i + 1; j < viewIds.size(); ++j )
				pairs.add( new ValuePair<ViewId, ViewId>( viewIds.get( i ), viewIds.get( j ) ) );

		final ArrayList< Pair< Pair< ViewId, ViewId >, Double > > pairResults = new ArrayList<>();

		pairs.parallelStream().forEach( pair -> {
			final AffineTransform3D mA = viewToModel.get( pair.getA() );
			final AffineTransform3D mB = viewToModel.get( pair.getB() );

			final ViewInterestPointLists vipA = data.getViewInterestPoints().getViewInterestPointLists( pair.getA() );
			final ViewInterestPointLists vipB = data.getViewInterestPoints().getViewInterestPointLists( pair.getB() );

			final RealSum sum = new RealSum();
			final RealSum sumWeights = new RealSum();

			labelAndWeights.forEach( (l,w) -> {
				if ( vipA.getInterestPointList( l ) != null && vipB.getInterestPointList( l ) != null )
				{
					final double[] tmpA = new double[ 3 ];
					final double[] tmpB = new double[ 3 ];

					final Map<Integer,InterestPoint> plA = vipA.getInterestPointList( l ).getInterestPointsCopy();
					final Map<Integer,InterestPoint> plB = vipB.getInterestPointList( l ).getInterestPointsCopy();
	
					//System.out.println( Group.pvid( pair.getA() ) + " <-> " + Group.pvid( pair.getB() ) + ": " + pA.size() + ", " + pB.size() );
					final Collection<CorrespondingInterestPoints> cA = vipA.getInterestPointList( l ).getCorrespondingInterestPointsCopy();
					//final List<CorrespondingInterestPoints> cB = vipA.getInterestPointList( l ).getCorrespondingInterestPointsCopy();

					//final ArrayList< PointMatch > pm = new ArrayList<>();
					cA.forEach( cpA ->
					{
						if ( cpA.getCorrespondingViewId().equals( pair.getB() ) && cpA.getCorrespodingLabel().equals( l ) )
						{
							final InterestPoint pA = plA.get( cpA.getDetectionId() );
							final InterestPoint pB = plB.get( cpA.getCorrespondingDetectionId() );

							mA.apply( pA.getL(), tmpA );
							mB.apply( pB.getL(), tmpB );

							final double distance = Point.distance( new Point( tmpA, tmpA ), new Point( tmpB, tmpB ) );
							sum.add( distance * w );
							sumWeights.add( w );
						}
					});
				}
			});

			if ( sumWeights.getSum() > 0 )
			{
				final double error = sum.getSum() / sumWeights.getSum();
				pairResults.add( new ValuePair<>( pair, error ) );
				//IOFunctions.println( Group.pvid( pair.getA() ) + " <-> " + Group.pvid( pair.getB() ) + ": " + error + " px.");
			}
		});

		// sort by error
		Collections.sort( pairResults, (o1,o2) -> o2.getB().compareTo( o1.getB() ));

		return pairResults;
	}

	public static HashMap<String, Double > getParameters(
			final SpimData2 data,
			final List< ViewId > viewIds )
	{
		final GenericDialog gd = new GenericDialog( "Analyze Interest Point Errors" );

		// check which channels and labels are available and build the choices
		final String[] labelsRaw = InterestPointTools.getAllInterestPointLabels( data, viewIds );

		if ( labelsRaw.length == 0 )
		{
			IOFunctions.printErr( "No interest points available, stopping. Please run Interest Point Detection first" );
			return null;
		}

		final String[] labels = new String[ labelsRaw.length + 1 ];
		for ( int i = 0; i < labelsRaw.length; ++i )
			labels[ i ] = labelsRaw[ i ];
		labels[ labelsRaw.length ] = "Select multiple interestpoints [extra dialog]";

		// choose the first label that is complete if possible
		if ( Interest_Point_Registration.defaultLabel < 0 || Interest_Point_Registration.defaultLabel >= labels.length )
		{
			Interest_Point_Registration.defaultLabel = -1;

			for ( int i = 0; i < labels.length; ++i )
				if ( !labels[ i ].contains( InterestPointTools.warningLabel ) && !labels[ i ].startsWith( "Select multiple interestpoints") )
				{
					Interest_Point_Registration.defaultLabel = i;
					break;
				}

			if ( Interest_Point_Registration.defaultLabel == -1 )
				Interest_Point_Registration.defaultLabel = 0;
		}

		System.out.println( Interest_Point_Registration.defaultLabel );

		gd.addChoice( "Interest_points" , labels, labels[ Interest_Point_Registration.defaultLabel ] );
		//gd.addCheckbox( "Print sorted results", true );
		//gd.addCheckbox( "Select & color-code worst pair in GUI", true );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return null;

		// assemble which label has been selected
		final int labelChoice = Interest_Point_Registration.defaultLabel = gd.getNextChoiceIndex();
		//final boolean print = gd.getNextBoolean();
		//final boolean select = gd.getNextBoolean();

		final HashMap<String, Double > labelAndWeight = new HashMap<>();

		if ( labelChoice < labels.length - 1 )
		{
			labelAndWeight.put( InterestPointTools.getSelectedLabel( labels, labelChoice ), 1.0 );
		}
		else
		{
			final ArrayList< String > labelChoices = Interest_Point_Registration.multipleInterestPointsGUI( labels );

			final GenericDialog gdLabel2 = new GenericDialog( "Select error weights" );

			if ( Interest_Point_Registration.defaultLabelWeights == null || Interest_Point_Registration.defaultLabelWeights.length != labelChoices.size() )
			{
				Interest_Point_Registration.defaultLabelWeights = new double[ labelChoices.size() ];
				Arrays.setAll( Interest_Point_Registration.defaultLabelWeights, d -> 1.0 );
			}
			
			gdLabel2.addMessage( "Weights for interest point labels:", GUIHelper.largefont );

			for ( int i = 0; i < labelChoices.size(); ++i )
				gdLabel2.addNumericField( labelChoices.get( i ) + " w=", Interest_Point_Registration.defaultLabelWeights[ i ], 2 );

			gdLabel2.showDialog();
			if ( gdLabel2.wasCanceled() )
				return null;

			for ( int i = 0; i < labelChoices.size(); ++i )
			{
				labelAndWeight.put( labelChoices.get( i ), Interest_Point_Registration.defaultLabelWeights[ i ] = gdLabel2.getNextNumber() );
				IOFunctions.println( labelChoices.get( i ) + ", weight=" + labelAndWeight.get( labelChoices.get( i ) ) );
			}
		}

		return labelAndWeight;
	}

	public static void main( String[] args )
	{
		new ImageJ();

		if ( System.getProperty("os.name").toLowerCase().contains( "mac" ) )
			GenericLoadParseQueryXML.defaultXMLURI = "/Users/preibischs/SparkTest/Stitching/dataset.xml";

		new Analyze_Errors().run( null );
	}
	
}
