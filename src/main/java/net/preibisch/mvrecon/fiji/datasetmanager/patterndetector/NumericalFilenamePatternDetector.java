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
package net.preibisch.mvrecon.fiji.datasetmanager.patterndetector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

public class NumericalFilenamePatternDetector implements FilenamePatternDetector
{
	private List<String> invariants;
	private List<List<String>> variables;

	@Override
	public void detectPatterns(List< String > files)
	{
		Pair< List< String >, List< List< String > > > res = detectNumericPatterns( files );
		invariants = res.getA();
		variables = res.getB();
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
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < invariants.size() - 1; i++)
			sb.append( invariants.get( i ) + "{" + i + "}" );
		sb.append( invariants.get( invariants.size()-1 ) );
		return sb.toString();
	}
	
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
	
	public static void main(String[] args)
	{
		List< String > files = null;
		try
		{
			Stream< Path > w = Files.list( Paths.get( "/Users/david/Desktop/Bordeaux/BIC Reconstruction/170331_EA810_Fred_MosZXY_Nocrop_12-36-22/" ));
			
					files = w.filter( new Predicate< Path >()
					{

						boolean res = false;
						@Override
						public boolean test(Path t)
						{
							try
							{
								res = Files.size( t ) > 100000;
							}
							catch ( IOException e )
							{
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
							return res;
						}
					} ).map( p -> p.toFile().getAbsolutePath()).collect( Collectors.toList() );
		}
		catch ( IOException e )
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		System.out.println( files.get( 0 ) );
		
		Pair< List< String >, List< List< String > > > detectNumericPatterns = detectNumericPatterns( files );
		
		
	}
	
}
