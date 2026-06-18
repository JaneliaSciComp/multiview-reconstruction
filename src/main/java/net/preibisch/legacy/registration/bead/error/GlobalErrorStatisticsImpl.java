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


public class GlobalErrorStatisticsImpl implements GlobalErrorStatistics 
{
	// statistics
	protected double avgError = -1;
	protected double minError = -1;
	protected double maxError = -1;
	protected int numDetections = 0;
	protected int numCorrespondences = 0;
	protected int numCandidates = 0;
	protected int countAvgErrors = 0;
	protected double avgLocalError = 0;

	@Override
	public void reset()
	{
		avgError = minError = maxError = -1;
		numDetections = numCorrespondences = numCandidates = countAvgErrors = 0;
		avgLocalError = 0;
	}
	
    //
	// Statics methods
    //    
	@Override
    public double getAverageAlignmentError(){ return avgError; }
	@Override
    public double getMinAlignmentError(){ return minError; }
	@Override
    public double getMaxAlignmentError(){ return maxError; }
    
	@Override
    public void setAverageAlignmentError( final double avg ){ avgError = avg; }
	@Override
    public void setMinAlignmentError( final double min ){ minError = min; }
	@Override
    public void setMaxAlignmentError( final double max ){ maxError = max; }

	@Override
    public int getNumDetections(){ return numDetections; }
	@Override
    public int getNumCandidates(){ return numCandidates; }
	@Override
    public int getNumCorrespondences(){ return numCorrespondences; }
	
	@Override
    public void setNumDetections( int numDetection ) { this.numDetections = numDetection; }
	@Override
	public void setNumCandidates( int numCandidates ) { this.numCandidates = numCandidates; }
	@Override
	public void setNumCorrespondences( int numCorrespondences ) { this.numCorrespondences = numCorrespondences; }
	
	@Override
    public double getAverageLocalAlignmentError(){ return avgLocalError/(double)countAvgErrors; }
	@Override
	public void setAbsoluteLocalAlignmentError( final double error ) { avgLocalError = error; }
	@Override
	public void setAlignmentErrorCount( final int count ) { countAvgErrors = count; }
	@Override
	public double getAbsoluteLocalAlignmentError() { return avgLocalError; }
	@Override
	public int getAlignmentErrorCount() { return countAvgErrors; }
}
