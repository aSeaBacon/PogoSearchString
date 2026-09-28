package app.pvpsearch.engine

import java.util.concurrent.ConcurrentHashMap

/** How far down the IV ranking to go. */
sealed interface Cutoff {
    /** Every bar group containing a spread ranked ≤ n (ties share a rank, so `TopRank(1)` includes ties). */
    data class TopRank(val n: Int) : Cutoff
    /** Every bar group containing a spread with stat product ≥ percent of the rank-1 spread. */
    data class MinPercent(val percent: Double) : Cutoff
}

data class LeagueOptions(
    /** Key into [GameData.leagues], e.g. "great". */
    val league: String,
    /** Drop species whose 15/15/15 max CP (at the highest level cap) is below this, e.g. 1480. */
    val minMaxCp: Int? = null,
    /** Drop species not in PvPoke's top N for this league (unranked species are dropped too). */
    val maxPvpokeRank: Int? = null,
)

data class SearchOptions(
    val leagues: List<LeagueOptions>,
    val cutoff: Cutoff = Cutoff.TopRank(1),
    /** Level caps whose rankings are combined, e.g. [50.0] or [50.0, 51.0] (best buddy). */
    val levelCaps: List<Double> = listOf(50.0),
    /** Level used for the [LeagueOptions.minMaxCp] check; defaults to the highest of [levelCaps]. */
    val cpFilterLevel: Double? = null,
    val includeUnreleased: Boolean = false,
    /** Pre-evolutions also match the groups of everything they evolve into (Machop gets Machamp's). */
    val evolutionRule: Boolean = true,
    /** For the PvPoke filter, a species passes if either its normal or shadow entry is ranked high enough. */
    val countShadowRank: Boolean = true,
    /** Species with any of these tags are dropped (legendary, mythical, ultrabeast, …). */
    val excludeTags: Set<String> = emptySet(),
    /** Species ids that are always dropped. Wins over [includeSpecies]. */
    val excludeSpecies: Set<String> = emptySet(),
    /** Species ids that skip the CP and PvPoke filters. */
    val includeSpecies: Set<String> = emptySet(),
)

/** Why a dex is in the target: [species] qualified in [league] with [groups]; [via] = the evolution it was matched through. */
data class Contribution(val species: Species, val league: String, val groups: Set<Int>, val via: Species? = null)

data class Selection(val target: Map<Int, Set<Int>>, val contributions: List<Contribution>)

/**
 * Memoised IV-ranking summaries. Species with identical base stats share one calculation.
 * Thread-safe, so the app can fill it in parallel.
 */
class RankingCache(val data: GameData) {
    val ranker = IvRanker(data.cpm)
    private val memo = ConcurrentHashMap<Key, GroupSummary>()

    data class Key(val stats: BaseStats, val cpCap: Int, val level: Double)

    fun summary(s: Species, league: String, level: Double): GroupSummary {
        val key = Key(s.stats, data.leagues.getValue(league), level)
        return memo.getOrPut(key) { ranker.summary(key.stats, key.cpCap, key.level) }
    }

    /** Everything a set of leagues/levels could need; call once after new data, off the main thread. */
    fun keysFor(leagues: Collection<String>, levels: Collection<Double>): Set<Key> =
        data.species.flatMap { s -> leagues.flatMap { lg -> levels.map { Key(s.stats, data.leagues.getValue(lg), it) } } }.toSet()

    fun compute(key: Key) { memo.getOrPut(key) { ranker.summary(key.stats, key.cpCap, key.level) } }

    /** For persisting the cache (the app stores these and restores them with [put]). */
    fun entries(): Map<Key, GroupSummary> = HashMap(memo)
    fun put(key: Key, value: GroupSummary) { memo[key] = value }
}

class TargetSelector(private val cache: RankingCache) {
    private val data = cache.data
    private val byId = data.species.associateBy { it.id }

    /** species id -> every species it can eventually evolve into. */
    private val descendants: Map<String, Set<String>> = data.species.associate { s ->
        val seen = LinkedHashSet<String>()
        val stack = ArrayDeque(s.evolvesTo)
        while (stack.isNotEmpty()) {
            val e = stack.removeLast()
            if (e != s.id && seen.add(e)) byId[e]?.let { stack.addAll(it.evolvesTo) }
        }
        s.id to seen
    }

    private fun usable(s: Species, o: SearchOptions) =
        "battleform" !in s.tags && (s.released || o.includeUnreleased) &&
            s.id !in o.excludeSpecies && s.tags.none { it in o.excludeTags }

    private fun pvpokeRank(s: Species, league: String, o: SearchOptions): Int? {
        val ranks = data.rankings[league]?.ranks ?: return null
        val own = ranks[s.id]
        val shadow = if (o.countShadowRank) ranks[s.id + "_shadow"] else null
        return listOfNotNull(own, shadow).minOrNull()
    }

    fun select(o: SearchOptions): Selection {
        require(o.levelCaps.isNotEmpty()) { "at least one level cap" }
        val cpLevel = o.cpFilterLevel ?: o.levelCaps.max()
        val contributions = ArrayList<Contribution>()
        for (lo in o.leagues) {
            require(lo.league in data.leagues) { "unknown league ${lo.league}" }
            for (s in data.species) {
                if (!usable(s, o)) continue
                if (s.id !in o.includeSpecies) {
                    if (lo.minMaxCp != null && cache.ranker.maxCp(s.stats, cpLevel) < lo.minMaxCp) continue
                    if (lo.maxPvpokeRank != null) {
                        val r = pvpokeRank(s, lo.league, o) ?: continue
                        if (r > lo.maxPvpokeRank) continue
                    }
                }
                val groups = HashSet<Int>()
                for (level in o.levelCaps) {
                    val sum = cache.summary(s, lo.league, level)
                    groups += when (val c = o.cutoff) {
                        is Cutoff.TopRank -> sum.groupsWithRankAtMost(c.n)
                        is Cutoff.MinPercent -> sum.groupsWithPercentAtLeast(c.percent)
                    }
                }
                if (groups.isNotEmpty()) contributions += Contribution(s, lo.league, groups)
            }
        }
        if (o.evolutionRule) {
            val direct = contributions.toList()
            for (pre in data.species) {
                if (!usable(pre, o)) continue
                val desc = descendants.getValue(pre.id)
                if (desc.isEmpty()) continue
                for (c in direct) if (c.species.id in desc) contributions += Contribution(pre, c.league, c.groups, via = c.species)
            }
        }
        val target = HashMap<Int, MutableSet<Int>>()
        for (c in contributions) target.getOrPut(c.species.dex) { HashSet() } += c.groups
        return Selection(target, contributions)
    }
}
