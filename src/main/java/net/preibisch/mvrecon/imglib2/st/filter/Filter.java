package net.preibisch.mvrecon.imglib2.st.filter;

import net.imglib2.RealLocalizable;

public interface Filter< T >
{
	public void filter(
			final RealLocalizable position,
			final T output );
}
