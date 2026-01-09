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

import static mpicbg.spim.data.XmlKeys.IMGLOADER_FORMAT_ATTRIBUTE_NAME;
import static mpicbg.spim.data.XmlKeys.IMGLOADER_TAG;
import static mpicbg.spim.data.XmlKeys.TIMEPOINTS_TIMEPOINT_TAG;
import static mpicbg.spim.data.XmlKeys.VIEWSETUP_TAG;

import java.io.File;
import java.net.URI;
import java.util.Map;

import org.jdom2.DataConversionException;
import org.jdom2.Element;

import mpicbg.spim.data.SpimDataInstantiationException;
import mpicbg.spim.data.XmlHelpers;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.ImgLoaderIo;
import mpicbg.spim.data.generic.sequence.ImgLoaders;
import mpicbg.spim.data.generic.sequence.XmlIoBasicImgLoader;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;


@ImgLoaderIo(format = "spimreconstruction.wrapped.flatfield.default", type = DefaultFlatfieldCorrectionWrappedImgLoader.class)
public class XmlIoFlatfieldCorrectedWrappedImgLoader
		implements XmlIoBasicImgLoader<FlatfieldCorrectionWrappedImgLoader<? extends ImgLoader>>
{
	public final static String WRAPPED_IMGLOADER_TAG = "WrappedImgLoader";
	public final static String FLATFIELDS_TAG = "FlatFields";
	public final static String FLATFIELD_TAG = "FlatField";
	public final static String BRIGHTIMG_TAG = "BrightImg";
	public final static String DARKIMG_TAG = "DarkImg";
	public final static String ACTIVE_TAG = "Active";
	public final static String CACHED_TAG = "Cached";

	@Override
	public FlatfieldCorrectionWrappedImgLoader<? extends ImgLoader> fromXml(Element elem, File basePath,
			AbstractSequenceDescription<?, ?, ?> sequenceDescription)
	{
		return fromXml(elem, basePath == null ? null : basePath.toURI(), sequenceDescription);
	}

	@Override
	public FlatfieldCorrectionWrappedImgLoader<? extends ImgLoader> fromXml(Element elem, URI basePathURI,
			AbstractSequenceDescription<?, ?, ?> sequenceDescription)
	{
		Element wrappedImgLoaderEl = elem.getChild(WRAPPED_IMGLOADER_TAG).getChild(IMGLOADER_TAG);
		XmlIoBasicImgLoader<?> xmlIoWrapped = null;
		try
		{
			xmlIoWrapped = ImgLoaders
					.createXmlIoForFormat(wrappedImgLoaderEl.getAttributeValue(IMGLOADER_FORMAT_ATTRIBUTE_NAME));
		}
		catch (SpimDataInstantiationException e)
		{
			e.printStackTrace();
			return null;
		}

		boolean cached = false;
		boolean active = false;
		try
		{
			cached = elem.getAttribute(CACHED_TAG).getBooleanValue();
			active = elem.getAttribute(ACTIVE_TAG).getBooleanValue();
		}
		catch (DataConversionException e)
		{
			e.printStackTrace();
		}

		BasicImgLoader wrappedImgLoader = xmlIoWrapped.fromXml(wrappedImgLoaderEl, basePathURI, sequenceDescription);

		FlatfieldCorrectionWrappedImgLoader<? extends ImgLoader> res = null;

		if (MultiResolutionImgLoader.class.isInstance(wrappedImgLoader))
			res = new MultiResolutionFlatfieldCorrectionWrappedImgLoader((MultiResolutionImgLoader) wrappedImgLoader,
					cached);
		else if (ImgLoader.class.isInstance(wrappedImgLoader))
			res = new DefaultFlatfieldCorrectionWrappedImgLoader((ImgLoader) wrappedImgLoader, cached);
		else
			return null;

		Element flatfields = elem.getChild(FLATFIELDS_TAG);
		for (Element flatfield : flatfields.getChildren())
		{
			int tp = Integer.parseInt(flatfield.getAttributeValue(TIMEPOINTS_TIMEPOINT_TAG));
			int vs = Integer.parseInt(flatfield.getAttributeValue(VIEWSETUP_TAG));
			URI brightImg = XmlHelpers.loadPathURI(flatfield, BRIGHTIMG_TAG, basePathURI);
			URI darkImg = XmlHelpers.loadPathURI(flatfield, DARKIMG_TAG, basePathURI);
			res.setBrightImage(new ViewId(tp, vs), brightImg);
			res.setDarkImage(new ViewId(tp, vs), darkImg);
		}

		res.setActive(active);
		return res;
	}

	@Override
	public Element toXml(FlatfieldCorrectionWrappedImgLoader<? extends ImgLoader> imgLoader, File basePath)
	{
		return toXml(imgLoader, basePath == null ? null : basePath.toURI());
	}

	@Override
	public Element toXml(FlatfieldCorrectionWrappedImgLoader<? extends ImgLoader> imgLoader, URI basePathURI)
	{
		final Map<ViewId, Pair<URI, URI>> uriMap = ((LazyLoadingFlatFieldCorrectionMap<? extends ImgLoader>) imgLoader).getUriMap();

		final Element wholeElem = new Element(IMGLOADER_TAG);
		wholeElem.setAttribute(IMGLOADER_FORMAT_ATTRIBUTE_NAME,
				this.getClass().getAnnotation(ImgLoaderIo.class).format());
		final Element wrappedIL = new Element(WRAPPED_IMGLOADER_TAG);

		wholeElem.setAttribute(ACTIVE_TAG, Boolean.toString(imgLoader.isActive()));
		wholeElem.setAttribute(CACHED_TAG, Boolean.toString(imgLoader.isCached()));

		try
		{
			@SuppressWarnings("unchecked")
			XmlIoBasicImgLoader<ImgLoader> loaderIO = (XmlIoBasicImgLoader<ImgLoader>) ImgLoaders
					.createXmlIoForImgLoaderClass(imgLoader.getWrappedImgLoder().getClass());
			Element wrappedInner = loaderIO.toXml((ImgLoader) imgLoader.getWrappedImgLoder(), basePathURI);
			wrappedIL.addContent(wrappedInner);
		}
		catch (SpimDataInstantiationException e)
		{
			e.printStackTrace();
			return null;
		}

		final Element elFlatfields = new Element(FLATFIELDS_TAG);

		for (ViewId vid : uriMap.keySet())
		{
			final Pair<URI, URI> uris = uriMap.get(vid);
			if (uris == null || (uris.getA() == null && uris.getB() == null))
				continue;

			final Element elFlatfield = new Element(FLATFIELD_TAG);
			elFlatfield.setAttribute(TIMEPOINTS_TIMEPOINT_TAG, Integer.toString(vid.getTimePointId()));
			elFlatfield.setAttribute(VIEWSETUP_TAG, Integer.toString(vid.getViewSetupId()));

			if (uris.getA() != null)
				elFlatfield.addContent(XmlHelpers.pathElementURI(BRIGHTIMG_TAG, uris.getA(), basePathURI));
			if (uris.getB() != null)
				elFlatfield.addContent(XmlHelpers.pathElementURI(DARKIMG_TAG, uris.getB(), basePathURI));

			elFlatfields.addContent(elFlatfield);
		}

		wholeElem.addContent(wrappedIL);
		wholeElem.addContent(elFlatfields);
		return wholeElem;
	}
}
