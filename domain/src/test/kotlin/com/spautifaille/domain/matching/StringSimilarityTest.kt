package com.spautifaille.domain.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StringSimilarityTest {

    private val eps = 1e-3

    @Test fun `jaro winkler reference values`() {
        // Valeurs de référence classiques (Winkler 1990).
        assertEquals(0.961, StringSimilarity.jaroWinkler("martha", "marhta"), eps)
        assertEquals(0.840, StringSimilarity.jaroWinkler("dwayne", "duane"), eps)
        assertEquals(0.813, StringSimilarity.jaroWinkler("dixon", "dicksonx"), eps)
        assertEquals(0.944, StringSimilarity.jaro("martha", "marhta"), eps)
    }

    @Test fun `jaro winkler edge cases`() {
        assertEquals(1.0, StringSimilarity.jaroWinkler("", ""), 0.0)
        assertEquals(0.0, StringSimilarity.jaroWinkler("", "abc"), 0.0)
        assertEquals(0.0, StringSimilarity.jaroWinkler("abc", ""), 0.0)
        assertEquals(1.0, StringSimilarity.jaroWinkler("abc", "abc"), 0.0)
        assertEquals(0.0, StringSimilarity.jaroWinkler("abc", "xyz"), 0.0)
        assertEquals(1.0, StringSimilarity.jaroWinkler("a", "a"), 0.0)
        assertEquals(0.0, StringSimilarity.jaroWinkler("a", "b"), 0.0)
    }

    @Test fun `jaro winkler is symmetric bounded and works on cjk`() {
        val pairs = listOf("hello" to "hallo", "queen" to "queens", "夜に駆ける" to "夜に駆けろ", "a" to "abcdef")
        for ((a, b) in pairs) {
            val ab = StringSimilarity.jaroWinkler(a, b)
            assertEquals(ab, StringSimilarity.jaroWinkler(b, a), 1e-12)
            assertTrue("$a/$b = $ab", ab in 0.0..1.0)
        }
        assertTrue(StringSimilarity.jaroWinkler("夜に駆ける", "夜に駆けろ") > 0.85)
    }

    @Test fun `jaro winkler prefix bonus favours common prefix`() {
        assertTrue(StringSimilarity.jaroWinkler("prefixabc", "prefixxyz") > StringSimilarity.jaro("prefixabc", "prefixxyz"))
    }

    @Test fun `ratio values`() {
        assertEquals(1.0, StringSimilarity.ratio("abc", "abc"), 0.0)
        assertEquals(0.0, StringSimilarity.ratio("abc", ""), 0.0)
        assertEquals(1.0, StringSimilarity.ratio("", ""), 0.0)
        assertEquals(8.0 / 9.0, StringSimilarity.ratio("abcd", "abcxd"), 1e-9) // LCS = 4, longueurs 4 + 5
    }

    @Test fun `token set ratio ignores order and duplicates`() {
        assertEquals(1.0, StringSimilarity.tokenSetRatio("new york mets", "mets new york"), 0.0)
        assertEquals(1.0, StringSimilarity.tokenSetRatio("hello hello world", "world hello"), 0.0)
    }

    @Test fun `token set ratio subset quirk and disjoint`() {
        assertEquals(1.0, StringSimilarity.tokenSetRatio("new york mets", "new york mets vs atlanta braves"), 0.0)
        assertTrue(StringSimilarity.tokenSetRatio("abc", "xyz") < 0.1)
    }

    @Test fun `token set ratio partial overlap is between`() {
        val r = StringSimilarity.tokenSetRatio("bohemian rhapsody live", "bohemian rapsody")
        assertTrue("r=$r", r in 0.8..1.0)
    }

    @Test fun `token set ratio edge cases`() {
        assertEquals(1.0, StringSimilarity.tokenSetRatio("", ""), 0.0)
        assertEquals(0.0, StringSimilarity.tokenSetRatio("", "abc"), 0.0)
        assertEquals(0.0, StringSimilarity.tokenSetRatio("abc", "   "), 0.0)
        assertEquals(1.0, StringSimilarity.tokenSetRatio("   ", ""), 0.0)
    }
}
