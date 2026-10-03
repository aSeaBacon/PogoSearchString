package app.pvpsearch.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StringGeneratorTest {
    private val cache = RankingCache(Fixtures.data)

    private fun SearchSettings.withLeague(key: String, f: (LeagueSettings) -> LeagueSettings) =
        copy(leagues = leagues + (key to f(league(key))))

    @Test
    fun defaultsBuildAllLeaguesInOrder() {
        val items = StringGenerator.build(cache, SearchSettings())
        assertEquals(listOf("Little Cup", "Great League", "Ultra League"), items.map { it.title.substringBeforeLast(' ') }.distinct())
        assertTrue(items.all { it.text.length <= 5000 && it.text.endsWith(StringGenerator.SUFFIX) })
        println(items.map { "${it.title}: ${it.text.length}" })
    }

    @Test
    fun disabledLeaguesAreSkipped() {
        val items = StringGenerator.build(cache, SearchSettings().withLeague("great") { it.copy(enabled = false) })
        assertTrue(items.none { it.title.startsWith("Great") })
        assertTrue(items.any { it.title.startsWith("Ultra") })
        val none = StringGenerator.LEAGUES.fold(SearchSettings()) { s, l -> s.withLeague(l.key) { it.copy(enabled = false) } }
        assertEquals(emptyList(), StringGenerator.build(cache, none))
    }

    @Test
    fun maxLengthIsRespected() {
        for (max in listOf(SearchSettings.MAX_LENGTH_RANGE.first, 3000, 20000)) {
            val items = StringGenerator.build(cache, SearchSettings(maxLength = max))
            assertTrue(items.all { it.text.length <= max }, "max $max")
            println("max $max: ${items.size} strings")
        }
    }

    @Test
    fun minCpFilterChangesTheTarget() {
        fun ultraLength(cp: Int) = StringGenerator.build(
            cache,
            SearchSettings(maxLength = 20000).withLeague("ultra") { it.copy(minMaxCp = cp) }
                .withLeague("little") { it.copy(enabled = false) }.withLeague("great") { it.copy(enabled = false) },
        ).single().text.length
        // No filter keeps every species, so the string is longer than with the 2450 default
        assertTrue(ultraLength(0) > ultraLength(2450))
        assertTrue(ultraLength(2450) > ultraLength(3500))
    }

    @Test
    fun settingsRoundTripAndRepair() {
        val s = SearchSettings(maxLength = 7000).withLeague("little") { it.copy(enabled = false, minMaxCp = 400) }
        assertEquals(s, SearchSettings.parse(s.toJson()))
        assertEquals(SearchSettings(), SearchSettings.parse(null))
        assertEquals(SearchSettings(), SearchSettings.parse("not json"))
        // missing league gets its default; out-of-range values are clamped
        val repaired = SearchSettings.parse("""{"leagues":{"great":{"enabled":false,"minMaxCp":-5}},"maxLength":99}""")
        assertEquals(LeagueSettings(enabled = false, minMaxCp = 0), repaired.league("great"))
        assertEquals(SearchSettings().league("ultra"), repaired.league("ultra"))
        assertEquals(SearchSettings.MAX_LENGTH_RANGE.first, repaired.maxLength)
    }

    @Test
    fun widerCutoffsTiming() {
        for ((rank, pct) in listOf(1 to 0.0, 10 to 0.0, 100 to 0.0, 4096 to 99.0, 4096 to 95.0, 100 to 98.0, 4096 to 0.0)) {
            val settings = SearchSettings(
                leagues = SearchSettings().leagues.mapValues { it.value.copy(maxRank = rank, minPercent = pct) }
            )
            val t0 = System.nanoTime()
            val items = StringGenerator.build(cache, settings)
            val secs = (System.nanoTime() - t0) / 1e9
            println("rank ≤ $rank, ≥ $pct%%: ${items.size} strings, ${items.sumOf { it.text.length }} chars, %.1f s".format(secs))
            assertTrue(items.all { it.text.length <= 5000 })
        }
    }

    @Test
    fun cutoffDescriptions() {
        val l = LeagueSettings(minMaxCp = 0)
        assertEquals("rank 1 (ties included)", l.describeCutoff())
        assertEquals("top 100 IV ranks, ≥ 98.5% stat product", l.copy(maxRank = 100, minPercent = 98.5).describeCutoff())
        assertEquals("≥ 99% stat product", l.copy(maxRank = 4096, minPercent = 99.0).describeCutoff())
        assertEquals("all IVs", l.copy(maxRank = 4096).describeCutoff())
    }

    @Test
    fun widerCutoffStringsAreExact() {
        // Many groups per dex need many exclusion clauses; check every part against the target
        val builder = SearchStringBuilder(Fixtures.data.maxDex)
        for (cutoff in listOf(Cutoff(maxRank = 100), Cutoff(maxRank = 30, minPercent = 98.0))) {
            val target = TargetSelector(cache).select(
                SearchOptions(listOf(LeagueOptions("great", minMaxCp = 1480)), cutoff = cutoff, cpFilterLevel = 51.0)
            ).target
            val parts = builder.build(target, 5000)
            for (p in parts) {
                assertEquals(0, builder.mismatches(p.text, target.filterKeys { it in p.firstDex..p.lastDex }), "$cutoff part ${p.firstDex}-${p.lastDex}")
            }
            println("$cutoff: ${parts.size} exact parts")
        }
    }
}
