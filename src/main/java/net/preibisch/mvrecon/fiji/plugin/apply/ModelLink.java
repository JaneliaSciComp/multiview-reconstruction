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
package net.preibisch.mvrecon.fiji.plugin.apply;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewDescription;

public class ModelLink// implements Type< ModelLink >
{
	final Set< TimePoint > ts;
	final Set< Channel > cs;
	final Set< Illumination > is;
	final Set< Angle > as;

	final List< ViewDescription > vds;

	double[] model;
	String modelDesc;

	public ModelLink( final ViewDescription vd )
	{
		this.ts = new HashSet< TimePoint >();
		this.cs = new HashSet< Channel >();
		this.is = new HashSet< Illumination >();
		this.as = new HashSet< Angle >();
		this.vds = new ArrayList< ViewDescription >();

		add( vd );
	}

	public List< ViewDescription > viewDescriptions() { return vds; }
	public double[] model() { return model; }
	public String modelDescription() { return modelDesc; }
	public Set< Angle > angles() { return as; }

	public void setModel( final double[] model, final String modelDesc )
	{
		this.model = model;
		this.modelDesc = modelDesc;
	}

	public void add( final ViewDescription vd )
	{
		if ( vd == null )
			return;

		this.ts.add( vd.getTimePoint() );
		this.cs.add( vd.getViewSetup().getChannel() );
		this.is.add( vd.getViewSetup().getIllumination() );
		this.as.add( vd.getViewSetup().getAngle() );
		this.vds.add( vd );
	}

	public String dialogName()
	{
		String s = "";

		s += ( ts.size() > 1 ) ? "all_timepoints" : "timepoint_" + ts.iterator().next().getName();
		s += ( cs.size() > 1 ) ? "_all_channels" : "_channel_" + cs.iterator().next().getName();
		s += ( is.size() > 1 ) ? "_all_illuminations" : "_illumination_" + is.iterator().next().getName();
		s += ( as.size() > 1 ) ? "_all_angles" : "_angle_" + as.iterator().next().getName();

		return s;
	}
	/*
	@Override
	public ModelLink createVariable() { return new ModelLink(); }

	@Override
	public ModelLink copy() { return new ModelLink(); }

	@Override
	public void set( final ModelLink c ) {}*/
}
