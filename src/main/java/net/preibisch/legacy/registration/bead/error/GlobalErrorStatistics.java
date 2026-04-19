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
package net.preibisch.legacy.registration.bead.error;

public interface GlobalErrorStatistics
{
	public double getMinAlignmentError();
	public double getAverageAlignmentError();
	public double getMaxAlignmentError();

	public void setMinAlignmentError( double min );
	public void setAverageAlignmentError( double avg );
	public void setMaxAlignmentError( double max );

    public int getNumDetections();
    public int getNumCandidates();
    public int getNumCorrespondences();

    public void setNumDetections( int numDetection );
    public void setNumCandidates( int numCandidates );
    public void setNumCorrespondences( int numCorrespondences );
    
    public void setAbsoluteLocalAlignmentError( double error );
    public void setAlignmentErrorCount( int count );
    public double getAbsoluteLocalAlignmentError();
    public int getAlignmentErrorCount();
    
    public double getAverageLocalAlignmentError();
    
    public void reset();
}
