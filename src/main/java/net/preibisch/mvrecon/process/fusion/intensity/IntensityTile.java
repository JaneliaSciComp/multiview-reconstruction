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
package net.preibisch.mvrecon.process.fusion.intensity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import mpicbg.models.AffineModel1D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedAffineModel1D;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Tile;
import net.imglib2.util.IntervalIndexer;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;


/**
 * A tile that contains a grid of sub-tiles, each of which has a model that can be fitted and applied. This acts as a
 * convenience class for handling the fitting and applying of the models of the sub-tiles necessary for intensity
 * correction. The encapsulated sub-tiles also potentially speed up the optimization process by reducing the overhead
 * of parallelizing the optimization of the sub-tiles.
 * <p>
 * This class doesn't derive from {@link Tile} because most of the methods there are tagged final, so they cannot be
 * overridden. This class should only be used in the context of intensity correction.
 */
class IntensityTile {

	final private int[] nSubTilesPerDimension;
	final private int nFittingCycles;
	final private List<Tile<?>> subTiles;

	private double distance = 0;
	private final Set<IntensityTile> connectedTiles = new HashSet<>();

	/**
	 * Creates a new intensity tile with the specified number of sub-tiles per dimension and the number of fitting
	 * cycles to perform within one fit of the intensity tile.
	 *
	 * @param modelSupplier
	 * 		supplies instances of the model to use for the sub-tiles
	 * @param nSubTilesPerDimension
	 * 		the number of sub-tiles per side of the tile
	 * @param nFittingCycles
	 * 		the number of fitting cycles
	 */
	public <M extends Model<M>> IntensityTile(
			final Supplier<M> modelSupplier,
			final int[] nSubTilesPerDimension,
			final int nFittingCycles
	) {
		this.nSubTilesPerDimension = nSubTilesPerDimension; // TODO: rename? nSubTiles? gridSize?
		this.nFittingCycles = nFittingCycles;

		final int n = Util.safeInt(Intervals.numElements(nSubTilesPerDimension));
		subTiles = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			subTiles.add(new Tile<>(modelSupplier.get()));
		}
	}

	public Tile<?> getSubTileAtIndex(final int i) {
		return this.subTiles.get(i);
	}

	public Tile<?> getSubTileAt(final int[] pos) {
		return getSubTileAtIndex(IntervalIndexer.positionToIndex(pos, nSubTilesPerDimension));
	}

	public int[] getSubTileGridSize() {
		return nSubTilesPerDimension;
	}

	public int nSubTiles() {
		return this.subTiles.size();
	}

	public int nFittingCycles() {
		return nFittingCycles;
	}

	public double getDistance() {
		return distance;
	}

	/**
	 * Updates the distance of this tile. The distance is the maximum distance of all sub-tiles.
	 */
	public void updateDistance() {
		distance = 0;
		for (final Tile<?> subTile : subTiles) {
			subTile.updateCost();
			distance = Math.max(distance, subTile.getDistance());
		}
	}

	public Set<IntensityTile> getConnectedTiles() {
		return connectedTiles;
	}

	/**
	 * Connects this tile to another tile. In contrast to the connect method of the Tile class, this method also
	 * connects the other tile to this tile.
	 *
	 * @param otherTile
	 * 		the tile to connect to (bidirectional connection)
	 */
	public void connectTo(final IntensityTile otherTile) {
		connectedTiles.add(otherTile);
		otherTile.connectedTiles.add(this);
	}

	/**
	 * Fits the model of all sub-tiles as often as specified by the nFittingCycles parameter. After fitting the model,
	 * the model is immediately applied to the sub-tile.
	 *
	 * @param damp
	 * 		the damping factor to apply to the model
	 *
	 * @throws NotEnoughDataPointsException
	 * 		if there are not enough data points to fit the model
	 * @throws IllDefinedDataPointsException
	 * 		if the data points are such that the model cannot be fitted
	 */
	public void fitAndApply(final double damp) throws NotEnoughDataPointsException, IllDefinedDataPointsException {
		final List<Tile<?>> shuffledTiles = new ArrayList<>(subTiles);
		for (int i = 0; i < nFittingCycles; i++) {
			Collections.shuffle(shuffledTiles);
			for (final Tile<?> subTile : shuffledTiles) {
				subTile.fitModel();
				subTile.apply(damp);
			}
		}
	}

	/**
	 * Applies the model of all sub-tiles.
	 */
	public void apply() {
		subTiles.forEach(Tile::apply);
	}

	/**
	 * Returns the model parameters as {@link Coefficients} for applying to
	 * intensity-correct images.
	 */
	public Coefficients getCoefficients() {
		final int numCoefficients = 2; // AffineModel1D
		final int n = nSubTiles();
		final double[][] coefficients = new double[numCoefficients][n];
		for (int i = 0; i < n; i++) {
			final Tile<?> tile = getSubTileAtIndex(i);

			final AffineModel1D model;
			if ( InterpolatedAffineModel1D.class.isInstance( tile.getModel() ) )
				model = ((InterpolatedAffineModel1D<?, ?>)tile.getModel()).createAffineModel1D();
			else
				model = (AffineModel1D) tile.getModel();

			final double[] matrix = model.getMatrix(null);
			final double m00 = matrix[0];
			final double m01 = matrix[1];
			coefficients[0][i] = m00;
			coefficients[1][i] = m01;
		}
		return new Coefficients(coefficients, getSubTileGridSize());
	}
}
