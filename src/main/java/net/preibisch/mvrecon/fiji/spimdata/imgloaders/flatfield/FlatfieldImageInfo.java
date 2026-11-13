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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield;

import java.net.URI;
import java.util.Objects;

import org.janelia.saalfeldlab.n5.universe.StorageFormat;

/**
 * Data class holding flatfield image metadata: URI, format, and dataset path.
 */
public class FlatfieldImageInfo {

	private final URI uri;
	private final StorageFormat format;
	private final String dataset;

	/**
	 * Create a FlatfieldImageInfo with all parameters.
	 *
	 * @param uri the URI to the flatfield image
	 * @param format the storage format (TIF uses null, others use StorageFormat enum)
	 * @param dataset the dataset path within the container (null or empty for root)
	 */
	public FlatfieldImageInfo(final URI uri, final StorageFormat format, final String dataset) {
		this.uri = uri;
		this.format = format;
		this.dataset = dataset;
	}

	/**
	 * Create a FlatfieldImageInfo with URI, using TIF format and root dataset.
	 */
	public FlatfieldImageInfo(final URI uri) {
		this(uri, null, null);
	}

	/**
	 * Create a FlatfieldImageInfo with URI and format, using root dataset.
	 */
	public FlatfieldImageInfo(final URI uri, final StorageFormat format) {
		this(uri, format, null);
	}

	public URI getUri() {
		return uri;
	}

	/**
	 * Get the storage format.
	 * @return the format, or null for TIF format
	 */
	public StorageFormat getFormat() {
		return format;
	}

	/**
	 * Get the dataset path within the container.
	 * @return the dataset path, or null/empty for root
	 */
	public String getDataset() {
		return dataset;
	}

	/**
	 * Get the effective dataset path (empty string if null).
	 */
	public String getEffectiveDataset() {
		return dataset == null ? "" : dataset;
	}

	/**
	 * Check if this is a TIF format (format is null).
	 */
	public boolean isTif() {
		return format == null;
	}

	@Override
	public String toString() {
		return "FlatfieldImageInfo{uri=" + uri + ", format=" + format + ", dataset=" + dataset + "}";
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		FlatfieldImageInfo that = (FlatfieldImageInfo) o;
		if (!Objects.equals(uri, that.uri)) return false;
		if (format != that.format) return false;
		return Objects.equals(dataset, that.dataset);
	}

	@Override
	public int hashCode() {
		int result = uri != null ? uri.hashCode() : 0;
		result = 31 * result + (format != null ? format.hashCode() : 0);
		result = 31 * result + (dataset != null ? dataset.hashCode() : 0);
		return result;
	}
}
