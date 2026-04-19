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
package net.preibisch.mvrecon.fiji.plugin.interestpointregistration.global;

import ij.gui.GenericDialog;

public class GlobalOptimizationParameters
{
	public static int defaultGlobalOpt = 3;
	public static int defaultSimple = 5;
	public static boolean defaultPrealign = true;

	final static double relativeBase = 2.5;
	final static double absoluteBase = 3.5;

	public static double defaultRelativeError = relativeBase;
	public static double defaultAbsoluteError = absoluteBase;

	public static boolean defaultExpertGrouping = false;

	public enum GlobalOptType
	{
		ONE_ROUND_SIMPLE,
		ONE_ROUND_ITERATIVE,
		TWO_ROUND_SIMPLE,
		TWO_ROUND_ITERATIVE,
		NO_OPTIMIZATION
	}

	public enum PreAlign
	{
		TRUE,
		FALSE,
		ASK
	}

	private final static String[] methodDescriptions = {
			"One-Round",
			"One-Round with iterative dropping of bad links",
			"Two-Round using metadata to align unconnected Tiles",
			"Two-Round using Metadata to align unconnected Tiles and iterative dropping of bad links", // default
			"NO global optimization, just store the corresponding interest points"
	};

	private final static String[] methodDescriptionsSimple = {
			"One-Round: DO NOT handle unconnected tiles, DO NOT remove wrong links ('classic option')",
			"One-Round: DO NOT handle unconnected tiles, handle wrong links STRICT (2.5x / 3.5px)",
			"One-Round: DO NOT handle unconnected tiles, handle wrong links RELAXED (5.0x / 7.0px)",
			"Two-Round: Handle unconnected tiles, DO NOT remove wrong links",
			"Two-Round: Handle unconnected tiles, remove wrong links STRICT (2.5x / 3.5px)",
			"Two-Round: Handle unconnected tiles, remove wrong links RELAXED (5.0x / 7.0px)", // default
			"NO global optimization, just store the corresponding interest points",
			"Show full options dialog"
	};

	public GlobalOptType method;
	public boolean preAlign;
	public double relativeThreshold;
	public double absoluteThreshold;
	public boolean showExpertGrouping;

	public GlobalOptimizationParameters()
	{
		this( defaultRelativeError, defaultAbsoluteError, GlobalOptType.TWO_ROUND_ITERATIVE, true, false );
	}

	public GlobalOptimizationParameters(double relativeThreshold, double absoluteThreshold, GlobalOptType method, boolean preAlign, boolean showExpertGrouping)
	{
		this.relativeThreshold = relativeThreshold;
		this.absoluteThreshold = absoluteThreshold;
		this.method = method;
		this.preAlign = preAlign;
		this.showExpertGrouping = showExpertGrouping;
	}

	public static void addSimpleParametersToDialog( final GenericDialog gd )
	{
		gd.addChoice( "Global_optimization_strategy", methodDescriptionsSimple, methodDescriptionsSimple[ defaultSimple ] );
		gd.addCheckbox( "Pre-align images (otherwise use current transforms as initialization)", defaultPrealign );
	}

	public static GlobalOptimizationParameters parseSimpleParametersFromDialog( final GenericDialog gd )
	{
		GlobalOptimizationParameters gp = 
				getGlobalOptimizationParametersForSelection(
						defaultSimple = gd.getNextChoiceIndex(),
						defaultPrealign = gd.getNextBoolean() );

		return gp;
	}

	public static GlobalOptimizationParameters getGlobalOptimizationParametersForSelection( final int selected, final boolean preAlign )
	{
		if ( selected == 7 )
			return askUserForParameters( false, preAlign ? PreAlign.TRUE : PreAlign.FALSE );
		else if ( selected == 0 )
			return new GlobalOptimizationParameters( Double.MAX_VALUE, Double.MAX_VALUE, GlobalOptType.ONE_ROUND_SIMPLE, preAlign, false );
		else if ( selected == 1 )
			return new GlobalOptimizationParameters( relativeBase, absoluteBase, GlobalOptType.ONE_ROUND_ITERATIVE, preAlign, false );
		else if ( selected == 2 )
			return new GlobalOptimizationParameters( 2 * relativeBase, 2 * absoluteBase, GlobalOptType.ONE_ROUND_ITERATIVE, preAlign, false );
		else if ( selected == 3 )
			return new GlobalOptimizationParameters( Double.MAX_VALUE, Double.MAX_VALUE, GlobalOptType.TWO_ROUND_SIMPLE, preAlign, false );
		else if ( selected == 4 )
			return new GlobalOptimizationParameters( relativeBase, absoluteBase, GlobalOptType.TWO_ROUND_ITERATIVE, preAlign, false );
		else if ( selected == 5 )
			return new GlobalOptimizationParameters( 2 * relativeBase, 2 * absoluteBase, GlobalOptType.TWO_ROUND_ITERATIVE, preAlign, false );
		else //if ( selected == 6 )
			return new GlobalOptimizationParameters( Double.MAX_VALUE, Double.MAX_VALUE, GlobalOptType.NO_OPTIMIZATION, preAlign, false );
	}

	public static GlobalOptimizationParameters askUserForSimpleParameters()
	{
		final GenericDialog gd = new GenericDialog( "Global optimization options" );

		addSimpleParametersToDialog( gd );

		gd.showDialog();

		if (gd.wasCanceled())
			return null;

		return parseSimpleParametersFromDialog( gd );
	}

	public static GlobalOptimizationParameters askUserForParameters( final boolean askForGrouping, final PreAlign preAlign )
	{
		// ask user for parameters
		final GenericDialog gd = new GenericDialog("Global optimization options");
		gd.addChoice( "Global_optimization_strategy", methodDescriptions, methodDescriptions[ defaultGlobalOpt ] );
		if ( preAlign == PreAlign.ASK)
			gd.addCheckbox( "Pre-align images (otherwise use current transforms as initialization)", defaultPrealign );
		gd.addNumericField( "relative error threshold (for handling wrong links)", 2.5, 3 );
		gd.addNumericField( "absolute error threshold (for handling wrong links)", 3.5, 3 );
		if (askForGrouping )
			gd.addCheckbox( "show_expert_grouping_options", defaultExpertGrouping );
		gd.showDialog();

		if (gd.wasCanceled())
			return null;

		double relTh = gd.getNextNumber();
		double absTh = gd.getNextNumber();
		final int methodIdx = defaultGlobalOpt = gd.getNextChoiceIndex();
		final boolean preAlignValue;
		if ( preAlign == PreAlign.ASK )
			preAlignValue = defaultPrealign = gd.getNextBoolean();
		else
			preAlignValue = (preAlign == PreAlign.TRUE);

		final boolean expertGrouping = askForGrouping ? gd.getNextBoolean() : false;

		final GlobalOptType method;
		if (methodIdx == 0)
			method = GlobalOptType.ONE_ROUND_SIMPLE;
		else if (methodIdx == 1)
			method = GlobalOptType.ONE_ROUND_ITERATIVE;
		else if (methodIdx == 2)
		{
			method = GlobalOptType.TWO_ROUND_SIMPLE;
			relTh = absTh = Double.MAX_VALUE;
		}
		else if (methodIdx == 2)
			method = GlobalOptType.TWO_ROUND_ITERATIVE;
		else
			method = GlobalOptType.NO_OPTIMIZATION;

		return new GlobalOptimizationParameters(relTh, absTh, method, preAlignValue, expertGrouping);
	}
}
