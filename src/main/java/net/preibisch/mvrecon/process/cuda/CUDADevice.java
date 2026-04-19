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
package net.preibisch.mvrecon.process.cuda;

public class CUDADevice implements Comparable< CUDADevice >
{
	final String name;
	final long totalMem, freeMem;
	final int id, majorComputeVersion, minorComputeVersion;

	public CUDADevice( final int id, final String name, final long totalMem, final long freeMem, final int majorComputeVersion, final int minorComputeVersion )
	{
		this.id = id;
		this.name = name;
		this.totalMem = totalMem;
		this.freeMem = freeMem;
		this.majorComputeVersion = majorComputeVersion;
		this.minorComputeVersion = minorComputeVersion;
	}

	public int getDeviceId() { return id; }
	public String getDeviceName() { return name; }
	public long getTotalDeviceMemory() { return totalMem; }
	public long getFreeDeviceMemory() { return freeMem; }
	public int getMajorComputeVersion() { return majorComputeVersion; }
	public int getMinorComputeVersion() { return minorComputeVersion; }
	public String getComputeVersion() { return getMajorComputeVersion() + "." + getMinorComputeVersion(); }

	@Override
	public String toString() { return 
			getDeviceName() + " (id=" + getDeviceId() + ", mem=" + getTotalDeviceMemory()/(1024*1024) + 
			"MB (" + getFreeDeviceMemory()/(1024*1024) + "MB free),"
			+ " CUDA capability " + getComputeVersion() + ")"; }

	@Override
	public int compareTo( final CUDADevice dev )
	{
		return getDeviceId() - dev.getDeviceId();
	}
}
