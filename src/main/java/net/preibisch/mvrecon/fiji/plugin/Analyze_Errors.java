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
import java.util.List;

import ij.ImageJ;
import ij.plugin.PlugIn;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.plugin.queryXML.GenericLoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.analyzeerror.AnalyzeErrorsUtil;

/**
 * Fiji menu entry for "Analyze Alignment Errors". All compute / dialog / print
 * logic lives in {@link AnalyzeErrorsUtil}; this class is the PlugIn shim that
 * keeps the menu wiring in {@code plugins.config} stable.
 */
public class Analyze_Errors implements PlugIn
{
	@Override
	public void run( final String arg0 )
	{
		final LoadParseQueryXML result = new LoadParseQueryXML();

		if ( !result.queryXML( "analyze interest point errors", "Analyze", true, true, true, true, true ) )
			return;

		final SpimData2 data = result.getData();
		final List< ViewId > viewIds =
				SpimData2.getAllViewIdsSorted( result.getData(), result.getViewSetupsToProcess(), result.getTimePointsToProcess() );

		final AnalyzeErrorsUtil.Parameters params = AnalyzeErrorsUtil.getParametersExtended( data, viewIds );
		if ( params == null )
			return;

		final ArrayList< AnalyzeErrorsUtil.PairError > errors =
				AnalyzeErrorsUtil.getErrors( data, viewIds, params.labelAndWeights );

		AnalyzeErrorsUtil.printResults( data, errors, params );
	}

	public static void main( String[] args )
	{
		new ImageJ();

		if ( System.getProperty("os.name").toLowerCase().contains( "mac" ) )
			GenericLoadParseQueryXML.defaultXMLURI = "/Users/preibischs/SparkTest/Stitching/dataset.xml";

		new Analyze_Errors().run( null );
	}
}
