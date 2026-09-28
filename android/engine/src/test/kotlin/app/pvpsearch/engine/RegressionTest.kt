package app.pvpsearch.engine

import kotlin.test.Test

/**
 * Compares the engine with the strings from the first project (Dittobase species list, rank 1 with
 * ties at level 50, min max-CP 1480/2450 allowing level 51, legendaries and unreleased included,
 * evolution rule). Differences are expected where the species lists differ; this test prints them
 * for review rather than failing.
 */
class RegressionTest {
    private val data = Fixtures.data
    private val builder = SearchStringBuilder(1025)
    private val suffix = "&!#pvp&!#notpvp"

    private fun oldTarget(files: List<String>): Map<Int, Set<Int>> {
        val matchers = files.map { builder.matcher(Fixtures.text("old_strings/$it").trim().removeSuffix(suffix)) }
        val out = HashMap<Int, MutableSet<Int>>()
        for (dex in 1..1025) for (g in 0 until Bars.GROUPS)
            if (matchers.any { it(dex, Bars.atk(g), Bars.def(g), Bars.hp(g)) }) out.getOrPut(dex) { HashSet() } += g
        return out
    }

    @Test
    fun compareWithFirstProject() {
        val selector = TargetSelector(RankingCache(data))
        val cases = listOf(
            Triple("great", 1480, listOf("search_great_1_of_3_dex1-346.txt", "search_great_2_of_3_dex347-685.txt", "search_great_3_of_3_dex686-1025.txt")),
            Triple("ultra", 2450, listOf("search_ultra_1_of_2_dex1-543.txt", "search_ultra_2_of_2_dex544-1025.txt")),
        )
        val names = data.species.groupBy { it.dex }.mapValues { (_, v) -> v.joinToString("/") { it.id } }
        for ((league, minCp, files) in cases) {
            val old = oldTarget(files)
            val sel = selector.select(SearchOptions(
                leagues = listOf(LeagueOptions(league, minMaxCp = minCp)),
                levelCaps = listOf(50.0), includeUnreleased = true))
            // the old CP filter allowed level 51; add species that only reach the threshold there
            val sel51 = selector.select(SearchOptions(
                leagues = listOf(LeagueOptions(league, minMaxCp = minCp)),
                levelCaps = listOf(50.0), includeUnreleased = true, cpFilterLevel = 51.0))
            val new = sel51.target
            val dexes = (old.keys + new.keys).sorted()
            val diff = dexes.filter { old[it].orEmpty() != new[it].orEmpty() }
            println("$league: old ${old.size} dexes, new ${new.size} (${sel.target.size} with the CP filter at L50); ${diff.size} differ")
            for (d in diff) {
                val o = old[d].orEmpty(); val n = new[d].orEmpty()
                println("  #$d ${names[d]}: only old ${(o - n).map(Bars::label)}, only new ${(n - o).map(Bars::label)}")
            }
            val parts = builder.build(new, 5000, suffix)
            println("  new strings at 5000 chars: ${parts.map { "${it.firstDex}-${it.lastDex}: ${it.text.length}" }}")
        }
    }
}
