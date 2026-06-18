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
package net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating;

import java.util.ArrayList;
import java.util.Collection;

import mpicbg.models.Point;
import mpicbg.models.PointMatch;

public class QualityPointMatch extends PointMatch
{
	private static final long serialVersionUID = 1L;

	final double quality;

	public QualityPointMatch( final Point p1, final Point p2, final double quality )
	{
		super( p1, p2 );

		this.quality = quality;
	}

	public double getQuality() { return quality; }

	private static void flipQ(
			final Collection< QualityPointMatch > matches,
			final Collection< QualityPointMatch > flippedMatches )
	{
		for ( final QualityPointMatch match : matches )
			flippedMatches.add(
					new QualityPointMatch(
							match.p2,
							match.p1,
							match.getQuality() ) );
	}

	final public static Collection< PointMatch > flipQ( final Collection< PointMatch > matches )
	{
		final ArrayList< QualityPointMatch > list = new ArrayList<>();
		flipQ( (Collection< QualityPointMatch >)(Object)matches, list );
		return (Collection< PointMatch >)(Object)list;
	}
}
