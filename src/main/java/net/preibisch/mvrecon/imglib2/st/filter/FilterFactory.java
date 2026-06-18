package net.preibisch.mvrecon.imglib2.st.filter;

import net.imglib2.IterableRealInterval;

public interface FilterFactory< S, T >
{
	Filter< T > createFilter( final IterableRealInterval< S > data );
	T create();
}
