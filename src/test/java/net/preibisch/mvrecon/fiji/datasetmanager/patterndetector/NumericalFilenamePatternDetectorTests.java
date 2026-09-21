package net.preibisch.mvrecon.fiji.datasetmanager.patterndetector;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.Ignore;
import org.junit.Test;

public class NumericalFilenamePatternDetectorTests {

	@Test
	public void testStringRepresentation() {

		final NumericalFilenamePatternDetector patternDetector = new NumericalFilenamePatternDetector();

		patternDetector.detectPatterns(Arrays.asList("0_t0", "1_t0"));
		assertEquals("{0}_t{1}", patternDetector.getStringRepresentation());

		patternDetector.detectPatterns(Arrays.asList("s0_t0", "s1_t0"));
		assertEquals("s{0}_t{1}", patternDetector.getStringRepresentation());

		patternDetector.detectPatterns(Arrays.asList("s0_suffix", "s1_suffix"));
		assertEquals("s{0}_suffix", patternDetector.getStringRepresentation());

		patternDetector.detectPatterns(Arrays.asList("s0_a", "s1_b"));
		assertEquals("s{0}.*", patternDetector.getStringRepresentation());
	}

	@Test
	public void testNumVariables() {

		final List<String> prefixes = Arrays.asList("setup", "");
		final List<String> suffixes = Arrays.asList("_t0", "");
		final List<String> values = Arrays.asList("10", "21");

		pairs(prefixes, suffixes).forEach(ps -> {

			List<String> list = values.stream().map(v -> {
				return ps[0] + v + ps[1];
			}).collect(Collectors.toList());

			final NumericalFilenamePatternDetector patternDetector = new NumericalFilenamePatternDetector();
			patternDetector.detectPatterns(list);

			int nVariables = ps[1].equals(suffixes.get(0)) ? 2 : 1;
			assertEquals(nVariables, patternDetector.getNumVariables());

			assertEquals(values.get(0), patternDetector.getValuesForVariable(0).get(0));
			assertEquals(values.get(1), patternDetector.getValuesForVariable(0).get(1));

			if( nVariables == 2) {
				assertEquals("0", patternDetector.getValuesForVariable(1).get(0));
				assertEquals("0", patternDetector.getValuesForVariable(1).get(1));
			}

		});

	}

	private static List<String[]> pairs( List<String> prefix, List<String> suffix) {

		return prefix.stream().flatMap( p -> {
			return suffix.stream().map(s -> {
				return new String[] {p, s};
			});
		}).collect(Collectors.toList());
	}

}
