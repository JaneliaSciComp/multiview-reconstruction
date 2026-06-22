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
package util;

import org.janelia.saalfeldlab.n5.universe.N5Factory;

import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * AWS-SDK-touching S3 configuration, intentionally isolated from {@link URITools}.
 *
 * {@code URITools} references the AWS SDK only through this class, so loading
 * {@code URITools} (which happens for ANY path, e.g. {@code toURI(...)} on a local
 * file) no longer requires the AWS SDK on the classpath — the JVM only links the
 * AWS classes the first time one of these methods is actually invoked, which only
 * happens on a real {@code s3://} code path.
 */
public class S3Tools
{
	/** Configure the factory's S3 client to use the given region (credentialed path). */
	public static void configureRegion( final N5Factory factory, final String region )
	{
		factory.s3Configuration( b -> b.region( Region.of( region ) ) );
	}

	/** Configure the factory's S3 client with anonymous credentials and an optional region. */
	public static void configureAnonymous( final N5Factory factory, final String region )
	{
		factory.s3Configuration( b -> {
			b.credentialsProvider( AnonymousCredentialsProvider.create() );
			if ( region != null )
				b.region( Region.of( region ) );
		} );
	}
}
