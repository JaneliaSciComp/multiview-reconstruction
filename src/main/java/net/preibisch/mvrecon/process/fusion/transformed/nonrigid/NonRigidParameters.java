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
package net.preibisch.mvrecon.process.fusion.transformed.nonrigid;

import java.util.ArrayList;

public class NonRigidParameters
{
	protected double alpha = 1.0;
	protected long controlPointDistance = 10;
	protected boolean showDistanceMap = false;
	protected boolean nonRigidAcrossTime = false;
	protected ArrayList< String > labelList = new ArrayList<>();

	public double getAlpha() { return alpha; }
	public long getControlPointDistance() { return controlPointDistance; }
	public boolean showDistanceMap() { return showDistanceMap; }
	public boolean nonRigidAcrossTime() { return nonRigidAcrossTime; }
	public ArrayList< String > getLabels() { return labelList; }
}
