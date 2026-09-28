package app.pvpsearch

import app.pvpsearch.engine.GameData
import app.pvpsearch.engine.LeagueOptions
import app.pvpsearch.engine.RankingCache
import app.pvpsearch.engine.SearchOptions
import app.pvpsearch.engine.SearchStringBuilder
import app.pvpsearch.engine.TargetSelector

/** One finished search string, e.g. "Great League 2". */
data class SearchStringItem(val title: String, val text: String, val firstDex: Int, val lastDex: Int)

/**
 * Version 1 settings, fixed: Great and Ultra League, rank-1 IVs (ties included) at level 50,
 * released Pokémon that can reach 1480 / 2450 CP (best buddy allowed), pre-evolutions included,
 * max 5,000 characters, ending in &!#pvp&!#notpvp.
 */
object StringGenerator {
    const val SUFFIX = "&!#pvp&!#notpvp"
    const val MAX_LENGTH = 5000

    private data class League(val key: String, val title: String, val minMaxCp: Int)

    private val leagues = listOf(
        League("great", "Great League", 1480),
        League("ultra", "Ultra League", 2450),
    )

    fun build(data: GameData, progress: (String) -> Unit): List<SearchStringItem> {
        val cache = RankingCache(data)
        progress("Ranking IVs…")
        // Fill the ranking cache on all CPU cores; the selector below then only reads it.
        cache.keysFor(leagues.map { it.key }, listOf(50.0)).toList().parallelStream().forEach(cache::compute)

        val selector = TargetSelector(cache)
        val builder = SearchStringBuilder(data.maxDex)
        return leagues.flatMap { league ->
            progress("Building ${league.title} strings…")
            val selection = selector.select(
                SearchOptions(
                    leagues = listOf(LeagueOptions(league.key, minMaxCp = league.minMaxCp)),
                    cpFilterLevel = 51.0,
                )
            )
            builder.build(selection.target, MAX_LENGTH, SUFFIX).mapIndexed { i, part ->
                SearchStringItem("${league.title} ${i + 1}", part.text, part.firstDex, part.lastDex)
            }
        }
    }
}
