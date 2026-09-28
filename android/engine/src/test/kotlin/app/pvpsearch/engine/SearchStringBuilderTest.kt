package app.pvpsearch.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SearchStringBuilderTest {
    private val maxDex = 1025
    private val builder = SearchStringBuilder(maxDex)
    private val suffix = "&!#pvp&!#notpvp"

    private fun randomTarget(seed: Int, dexCount: Int, maxGroups: Int): Map<Int, Set<Int>> {
        val rnd = Random(seed)
        return (1..maxDex).shuffled(rnd).take(dexCount).associateWith {
            (1..rnd.nextInt(1, maxGroups + 1)).map { rnd.nextInt(Bars.GROUPS) }.toSet()
        }
    }

    /** Each part must fit, match exactly its own dexes' groups, and the parts must cover the target. */
    private fun check(target: Map<Int, Set<Int>>, maxLength: Int) {
        val parts = builder.build(target, maxLength, suffix)
        val covered = HashSet<Int>()
        for (p in parts) {
            assertTrue(p.text.length <= maxLength, "part too long: ${p.text.length} > $maxLength")
            assertTrue(p.text.endsWith(suffix))
            val own = target.filterKeys { it in p.firstDex..p.lastDex }
            assertEquals(0, builder.mismatches(p.text.removeSuffix(suffix), own), "mismatches in part ${p.firstDex}-${p.lastDex}")
            covered += own.keys
        }
        assertEquals(target.keys, covered)
        println("maxLength $maxLength: ${parts.size} parts, lengths ${parts.map { it.text.length }}")
    }

    @Test
    fun randomTargetsAreExact() {
        check(randomTarget(1, 50, 1), 5000)
        check(randomTarget(2, 300, 3), 5000)
        check(randomTarget(3, 300, 3), 2000)
        check(randomTarget(4, 600, 6), 5000)
    }

    @Test
    fun multiGroupDexNeedsExclusionClauses() {
        // Eevee-like: two groups whose per-stat combination would also allow 0-4-0 and 4-0-4
        val target = mapOf(133 to setOf(Bars.group(0, 0, 0), Bars.group(4, 4, 4)), 134 to setOf(Bars.group(0, 3, 3)))
        val s = builder.best(target)
        assertEquals(0, builder.mismatches(s, target))
    }

    @Test
    fun emptyTargetGivesNoStrings() = assertEquals(emptyList(), builder.build(emptyMap(), 5000))

    @Test
    fun tooShortMaxLengthFails() {
        assertFailsWith<IllegalArgumentException> { builder.build(mapOf(1 to setOf(0)), 40) }
    }

    @Test
    fun matcherFollowsGameSyntax() {
        // (attack bar ≥ 1 OR dex 5) AND (dex 3 OR dex 10–12)
        val m = builder.matcher("1-4attack,5&3,10-12")
        assertTrue(m(11, 1, 0, 0))
        assertTrue(m(3, 4, 0, 0))
        assertTrue(!m(11, 0, 0, 0))
        assertTrue(!m(5, 0, 0, 0))
        assertTrue(!m(4, 2, 0, 0))
    }
}
