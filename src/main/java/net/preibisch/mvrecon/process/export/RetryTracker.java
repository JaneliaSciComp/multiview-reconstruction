/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2026 Multiview Reconstruction developers.
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
package net.preibisch.mvrecon.process.export;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Function;

import net.preibisch.legacy.io.IOFunctions;

/**
 * Simple utility to track retry attempts and handle retry logic for failed
 * blocks. Keeps the parallelization code separate while avoiding retry logic
 * duplication.
 */
public class RetryTracker<T>
{
	private final Map<String, Integer> retryCount = new HashMap<>();
	private final Function<T, String> keyExtractor;
	private final String operationName;
	private final int maxRetries;
	private final boolean giveUpOnFailure;
	private final long retryDelayMs;
	private final boolean triggerGC;
	private int totalAttempts = 0;
	private final int maxTotalAttempts;

	public RetryTracker(
			final Function<T, String> keyExtractor,
			final String operationName,
			final int maxRetries,
			final boolean giveUpOnFailure,
			final long retryDelayMs,
			final boolean triggerGC,
			final int totalBlocks)
	{
		this.keyExtractor = keyExtractor;
		this.operationName = operationName;
		this.maxRetries = maxRetries;
		this.giveUpOnFailure = giveUpOnFailure;
		this.retryDelayMs = retryDelayMs;
		this.triggerGC = triggerGC;
		this.maxTotalAttempts = totalBlocks * maxRetries;
	}

	/**
	 * Convenience constructor for grid blocks with default settings
	 * 
	 * @param operationName any descriptive name for the operation for output messages
	 * @param totalBlocks number of blocks that will be processed
	 * 
	 * @return the RetryTracker instance
	 */
	public static RetryTracker<long[][]> forGridBlocks(final String operationName, final int totalBlocks)
	{
		return new RetryTracker<>(block -> Arrays.toString(block[0]), operationName, 5, true, 2000, true, totalBlocks);
	}

	public Function<T, String> keyExtractor() { return keyExtractor; }

	/**
	 * Call this before each retry cycle. Returns false if max total attempts
	 * exceeded.
	 * 
	 * @return if begin was successful
	 */
	public boolean beginAttempt() {
		if (++totalAttempts > maxTotalAttempts) {
			IOFunctions.println("Maximum retry attempts (" + maxTotalAttempts + ") exceeded for " + operationName);
			return false;
		}

		// Add delay and GC hint for retries
		if (totalAttempts > 1) {
			IOFunctions.println("Retry attempt " + totalAttempts + " for " + operationName);

			if (triggerGC) {
				IOFunctions.println("Triggering GC and waiting...");
				System.gc();
			}

			if (retryDelayMs > 0) {
				try {
					Thread.sleep(retryDelayMs);
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
			}
		}

		return true;
	}

	/**
	 * Create the map of all blocks, from which we then remove each one that succeeded piece by piece
	 *
	 * @param grid - all blocks
	 * @return HashMap from created unique key to block
	 */
	public HashMap< String, T > createFailedBlocksMap( final Collection< T > grid )
	{
		final HashMap< String, T > failedBlocksMap = new HashMap<>();
		grid.forEach( gridBlock -> failedBlocksMap.put( keyExtractor.apply( gridBlock ), gridBlock ) );

		return failedBlocksMap;
	}

	/**
	 * Method to process all results when running with a service that returns futures. When running with Spark,
	 * this method needs to be adjusted.
	 *
	 * @param futures - list of Futures
	 * @param grid - list blocks that were processed
	 * @return set of failed blocks
	 */
	public Set< T > processWithFutures( final List<Future<T>> futures, final Collection< T > grid )
	{
		// we add all blocks to the failedBlocksSet, and remove the ones that succeeded
		final HashMap< String, T > failedBlocksMap = createFailedBlocksMap( grid );

		for ( final Future<T> future : futures )
		{
			try
			{
				final T result = future.get();

				if ( result != null )
					failedBlocksMap.remove( keyExtractor().apply( result ) );
			}
			catch ( Exception e )
			{
				IOFunctions.println( "block error s0 (will be re-tried): " + e );
			}
		}

		// Convert to Set<long[][]> for RetryTracker
		return new HashSet<>( failedBlocksMap.values() );
	}

	/**
	 * Remove successful blocks from the failed set and update retry counts. Returns
	 * false if should stop due to too many individual block failures.
	 * 
	 * @param failedBlocks failed block to re-process
	 * @return if it did not give up now
	 */
	public boolean processFailures(final Set<T> failedBlocks) {
		IOFunctions.println("Blocks remaining for retry in " + operationName + ": " + failedBlocks.size());

		final Iterator<T> blockIterator = failedBlocks.iterator();
		while (blockIterator.hasNext()) {
			final T block = blockIterator.next();
			final String blockKey = keyExtractor.apply(block);
			final int retries = retryCount.getOrDefault(blockKey, 0) + 1;

			if (retries > maxRetries) {
				if (giveUpOnFailure) {
					IOFunctions.println("Block " + blockKey + " failed " + retries + " times in " + operationName
							+ ", giving up and stopping.");
					return false;
				} else {
					IOFunctions.println("Block " + blockKey + " failed " + retries + " times in " + operationName
							+ ", giving up on this block and continuing.");
					blockIterator.remove();
				}
			} else {
				retryCount.put(blockKey, retries);
			}
		}

		return true;
	}

	public int getTotalAttempts() {
		return totalAttempts;
	}
}
