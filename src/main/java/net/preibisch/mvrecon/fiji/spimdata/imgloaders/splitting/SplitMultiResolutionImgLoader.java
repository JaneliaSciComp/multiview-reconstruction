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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting;

import java.util.HashMap;

import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.SequenceDescription;
import net.imglib2.Interval;

public class SplitMultiResolutionImgLoader implements MultiResolutionImgLoader
{
	final MultiResolutionImgLoader underlyingImgLoader;

	/**
	 * Maps the newly assigned ViewSetupId to the old ViewSetupId
	 */
	final HashMap< Integer, Integer > new2oldSetupId;

	/**
	 * Maps the newly assigned ViewSetupId to Interval inside the old ViewSetupId
	 */
	final HashMap< Integer, Interval > newSetupId2Interval;

	/**
	 * The old SequenceDescription is be needed for the underlying imgloader
	 */
	final SequenceDescription oldSD;

	/**
	 * Remembers instances of SplitSetupImgLoader
	 */
	private final HashMap< Integer, SplitMultiResolutionSetupImgLoader< ? > > splitSetupImgLoaders;

	public SplitMultiResolutionImgLoader(
			final MultiResolutionImgLoader underlyingImgLoader,
			final HashMap< Integer, Integer > new2oldSetupId,
			final HashMap< Integer, Interval > newSetupId2Interval,
			final SequenceDescription oldSD )
	{
		this.underlyingImgLoader = underlyingImgLoader;
		this.new2oldSetupId = new2oldSetupId;
		this.newSetupId2Interval = newSetupId2Interval;
		this.splitSetupImgLoaders = new HashMap<>();
		this.oldSD = oldSD;
	}

	@Override
	public SplitMultiResolutionSetupImgLoader< ? > getSetupImgLoader( final int setupId )
	{
		return getSplitSetupImgLoader( underlyingImgLoader, new2oldSetupId.get( setupId ), setupId, newSetupId2Interval.get( setupId ) );
	}

	public MultiResolutionImgLoader getUnderlyingImgLoader()
	{
		return underlyingImgLoader;
	}

	private final synchronized < T > SplitMultiResolutionSetupImgLoader< ? > getSplitSetupImgLoader( final MultiResolutionImgLoader underlyingImgLoader, final int oldSetupId, final int newSetupId, final Interval interval )
	{
		SplitMultiResolutionSetupImgLoader< ? > sil = splitSetupImgLoaders.get( newSetupId );
		if ( sil == null )
		{
			sil = createNewSetupImgLoader( underlyingImgLoader.getSetupImgLoader( oldSetupId ), interval );
			splitSetupImgLoaders.put( newSetupId, sil );
		}
		return sil;
	}

	private final synchronized < T > SplitMultiResolutionSetupImgLoader< ? > createNewSetupImgLoader( final MultiResolutionSetupImgLoader< T > setupImgLoader, final Interval interval )
	{
		return new SplitMultiResolutionSetupImgLoader< T >( setupImgLoader, interval );
	}
}
