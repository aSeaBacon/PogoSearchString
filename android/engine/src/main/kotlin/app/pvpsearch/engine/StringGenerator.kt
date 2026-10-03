package app.pvpsearch.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.stream.Collectors

/** One finished search string, e.g. "Great League 2". */
@Serializable
data class SearchStringItem(val title: String, val text: String, val firstDex: Int, val lastDex: Int)

/** A league the app builds strings for. */
data class LeagueDef(
    /** Key into [GameData.leagues]. */
    val key: String,
    val title: String,
    val defaultMinMaxCp: Int,
    val levelCaps: List<Double> = listOf(50.0),
    /** Little Cup rules: unevolved species that can evolve, no evolution rule. */
    val littleCup: Boolean = false,
)

@Serializable
data class LeagueSettings(
    val enabled: Boolean = true,
    /** Drop species whose 15/15/15 can't reach this CP (checked at level 51); 0 = no filter. */
    val minMaxCp: Int,
    /** Include IV spreads ranked this high or better (1 = rank 1 and its ties). */
    val maxRank: Int = 1,
    /** …that also have at least this % of rank 1's stat product (0 = no limit). */
    val minPercent: Double = 0.0,
) {
    val cutoff: Cutoff get() = Cutoff(maxRank, minPercent)

    /** e.g. "rank 1 (ties included)", "top 100 IV ranks, ≥ 98% stat product". */
    fun describeCutoff(): String {
        val rank = when {
            maxRank >= Cutoff.MAX_RANK -> null
            maxRank == 1 -> "rank 1 (ties included)"
            else -> "top $maxRank IV ranks"
        }
        val percent = if (minPercent > 0) "≥ ${formatPercent(minPercent)}% stat product" else null
        return listOfNotNull(rank, percent).joinToString(", ").ifEmpty { "all IVs" }
    }
}

/** 98.0 -> "98", 99.25 -> "99.25". */
fun formatPercent(p: Double): String =
    java.math.BigDecimal.valueOf(p).stripTrailingZeros().toPlainString()

/** The user's string-building settings, saved on the phone. */
@Serializable
data class SearchSettings(
    /** League key -> settings; every league in [StringGenerator.LEAGUES] has an entry. */
    val leagues: Map<String, LeagueSettings> = StringGenerator.LEAGUES.associate { it.key to LeagueSettings(minMaxCp = it.defaultMinMaxCp) },
    /** Max characters per string, including the suffix. */
    val maxLength: Int = DEFAULT_MAX_LENGTH,
) {
    fun league(key: String): LeagueSettings = leagues.getValue(key)

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val DEFAULT_MAX_LENGTH = 5000
        /** Android's search bar takes at most 5,000 characters; above this the app shows a warning. */
        const val SAFE_MAX_LENGTH = 5000
        val MAX_LENGTH_RANGE = 1000..20000
        val MIN_CP_RANGE = 0..10000
        val RANK_RANGE = 1..Cutoff.MAX_RANK
        const val MAX_PERCENT = 100.0

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * Reads saved settings. Leagues missing from the file (e.g. added in an app update) get their
         * defaults, and out-of-range values are clamped. Unreadable text gives the defaults.
         */
        fun parse(text: String?): SearchSettings {
            val saved = text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: return SearchSettings()
            val defaults = SearchSettings()
            return SearchSettings(
                leagues = defaults.leagues.mapValues { (key, def) ->
                    val s = saved.leagues[key] ?: def
                    s.copy(
                        minMaxCp = s.minMaxCp.coerceIn(MIN_CP_RANGE),
                        maxRank = s.maxRank.coerceIn(RANK_RANGE),
                        minPercent = s.minPercent.takeIf { it.isFinite() }?.coerceIn(0.0, MAX_PERCENT) ?: 0.0,
                    )
                },
                maxLength = saved.maxLength.coerceIn(MAX_LENGTH_RANGE),
            )
        }
    }
}

/**
 * Builds the search strings for the user's [SearchSettings]. Per league: on/off, minimum max CP and
 * the IV cutoff (top-N rank and/or minimum stat product %). Fixed for now: released Pokémon only, minimum CP checked at level 51 (best buddy), strings ending in
 * &!#pvp&!#notpvp. Legendaries, mythicals and Ultra Beasts are included.
 * - Little Cup: Pokémon that haven't evolved and can still evolve, ranked at level 50 and 51 (best
 *   buddy spreads are in the same string).
 * - Great / Ultra League: ranked at level 50, pre-evolutions included.
 */
object StringGenerator {
    const val SUFFIX = "&!#pvp&!#notpvp"

    /** Bump when the output changes for the same data and settings, so saved strings are rebuilt. */
    const val VERSION = 4

    val LEAGUES = listOf(
        LeagueDef("little", "Little Cup", 450, levelCaps = listOf(50.0, 51.0), littleCup = true),
        LeagueDef("great", "Great League", 1480),
        LeagueDef("ultra", "Ultra League", 2450),
    )

    /** [cache] can be kept between calls (same data) so changing settings doesn't re-rank IVs. */
    fun build(cache: RankingCache, settings: SearchSettings, progress: (String) -> Unit = {}): List<SearchStringItem> {
        val leagues = LEAGUES.filter { settings.league(it.key).enabled }
        progress("Ranking IVs…")
        // Fill the ranking cache on all CPU cores; the selector below then only reads it.
        leagues.flatMap { cache.keysFor(listOf(it.key), it.levelCaps) }.distinct()
            .parallelStream().forEach(cache::compute)

        val selector = TargetSelector(cache)
        val builder = SearchStringBuilder(cache.data.maxDex)
        progress("Building strings…")
        // The leagues are independent, so build them at the same time (order is kept).
        return leagues.parallelStream().map { league ->
            val ls = settings.league(league.key)
            val selection = selector.select(
                SearchOptions(
                    leagues = listOf(
                        LeagueOptions(league.key, minMaxCp = ls.minMaxCp.takeIf { it > 0 }, unevolvedOnly = league.littleCup)
                    ),
                    cutoff = ls.cutoff,
                    levelCaps = league.levelCaps,
                    cpFilterLevel = 51.0,
                    // Little Cup Pokémon are used unevolved, so their evolutions' IVs don't matter
                    evolutionRule = !league.littleCup,
                )
            )
            builder.build(selection.target, settings.maxLength, SUFFIX).mapIndexed { i, part ->
                SearchStringItem("${league.title} ${i + 1}", part.text, part.firstDex, part.lastDex)
            }
        }.collect(Collectors.toList()).flatten()
    }
}
