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
package net.preibisch.mvrecon.fiji.datasetmanager.patterndetector;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

public class NumericalFilenamePatternDetector implements FilenamePatternDetector
{

	private static final Pattern NUMERICAL_PATTERN = Pattern.compile( "(\\d+)(.*)" );
	private static final Pattern PREFIX_PATTERN = Pattern.compile( "([^0-9]+)(\\d+).*" );

	private List<String> invariants;
	private List<List<String>> variables;

	private List<String> tStrings;
	private StringBuilder stringRepresentation;

	@Override
	public void detectPatterns(List< String > files)
	{
		tStrings = new ArrayList<>();
		files.forEach( tStrings::add );
		invariants = new ArrayList<>();
		variables = new ArrayList<>();
		stringRepresentation = new StringBuilder();

		while(true) {
			boolean hadNumeric = numericPrefixes(tStrings);
			boolean hadPrefix = nonnumericPrefixes(tStrings);
			if( !hadNumeric && !hadPrefix )
				break;
		}
		suffix(tStrings);
	}

	private boolean suffix(Iterable<String> strings) {

		String suffix = null;
		for (String s : strings){
			if (suffix == null)
				suffix = s;
			else if( !s.equals(suffix)) {
				stringRepresentation.append(".*");
				return false;
			}
		}
		stringRepresentation.append(suffix);
		return true;
	}

	private boolean nonnumericPrefixes(Iterable<String> strings)
	{
		List<String> prefixes = new ArrayList<>();
		List<String> remainders = new ArrayList<>();
		for (String s : strings){
			Matcher m = PREFIX_PATTERN.matcher( s );
			if (! m.matches())
				return false;

			prefixes.add(m.group( 1 ));
			remainders.add(m.group( 2 ));
		}

		final String prefix = prefixes.get(0);
		if (!prefixes.stream().allMatch(x -> x.equals(prefix))) {
			return false;
		}

		invariants.add(prefix);
		stringRepresentation.append(prefix);

		tStrings = tStrings.stream().map( s -> s.substring(prefix.length() ) ).collect( Collectors.toList() );
		return true;
	}

	private boolean numericPrefixes(Iterable<String> strings)
	{
		List<String> prefixes = new ArrayList<>();
		List<String> remainders = new ArrayList<>();
		for (String s : strings){
			Matcher m = NUMERICAL_PATTERN.matcher( s );
			if (! m.matches())
				return false;

			prefixes.add(m.group( 1 ));
			remainders.add(m.group( 2 ));
		}

		stringRepresentation.append(String.format("{%d}", variables.size()));
		variables.add( prefixes );
		tStrings = remainders;
		return true;
	}

	@Override
	public String getInvariant(int n){ return invariants.get( n );}
	@Override
	public List< String > getValuesForVariable(int n){return variables.get( n );}
	@Override
	public Pattern getPatternAsRegex()
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < invariants.size() - 1; i++)
			sb.append( Pattern.quote( invariants.get( i ) ) + "(\\d+)" );
		sb.append( Pattern.quote( invariants.get( invariants.size()-1 ) ) );
		return Pattern.compile( sb.toString() );
	}

	@Override
	public String getStringRepresentation()
	{
		return stringRepresentation.toString();
	}

	@Deprecated
	public static String maxPrefix(List<String> strings)
	{

		// return default empty String prefix on empty input
		if (strings.size() < 1)
			return "";

		int maxIdx = 0;
		while (true)
		{
			Character currentChar = null;
			for (String s: strings)
			{
				
				if (maxIdx >= s.length())
					return s;
				else
				{
					if (currentChar == null)
						currentChar = s.charAt( maxIdx );
					if (!currentChar.equals( s.charAt( maxIdx ) ))
					{
						return s.substring( 0, maxIdx );
					}
				}
			}
			currentChar = null;
			maxIdx++;
		}
	}
	
	@Deprecated
	public static Pair<List<String>, List<String>> collectNumericPrefixes(Iterable<String> strings)
	{
		Pattern p = Pattern.compile( "(\\d+)(.+?)" );
		List<String> prefixes = new ArrayList<>();
		List<String> remainders = new ArrayList<>();
		for (String s : strings){
			Matcher m = p.matcher( s );
			if (! m.matches())
				return null;
			prefixes.add(m.group( 1 ));
			remainders.add( m.group( 2 ) );				
		}
		return new ValuePair< List<String>, List<String> >( prefixes, remainders );
	}
	
	@Deprecated
	public static Pair<List<String>, List<List<String>>> detectNumericPatterns(Iterable<String> strings)
	{
		
		List<String> tStrings = new ArrayList<>();
		strings.forEach( tStrings::add );
		List<String> invariants = new ArrayList<>();
		List<List<String>> variants = new ArrayList<>();
				
		while(true)
		{
			String prefix = maxPrefix( tStrings );
			
			// we can no longer find a constant prefix -> consider the rest of the filenames as variable
			if (prefix.length() == 0)
			{
				invariants.add( ".*" );
				break;
			}
			
			
			//System.out.println( prefix );
			
			invariants.add( prefix );
			
			tStrings = tStrings.stream().map( s -> s.substring(prefix.length() ) ).collect( Collectors.toList() );
			
			//tStrings.forEach( System.out::println );
			
			Pair< List< String >, List< String > > collectNumericPrefixes = collectNumericPrefixes( tStrings );
			
			if (collectNumericPrefixes == null)
				break;
			
			//collectNumericPrefixes.getA().forEach( System.out::println );
			
			variants.add( collectNumericPrefixes.getA() );
			
			tStrings = collectNumericPrefixes.getB();			
		}
		
		return new ValuePair< List<String>, List<List<String>> >( invariants, variants );
		
	}

	@Override
	public int getNumVariables(){return variables.size();}
	
}
