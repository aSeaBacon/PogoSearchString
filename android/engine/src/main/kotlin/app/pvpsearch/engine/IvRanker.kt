package app.pvpsearch.engine

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** Appraisal bars shown in game: IV 0 -> 0, 1–5 -> 1, 6–10 -> 2, 11–14 -> 3, 15 -> 4. */
object Bars {
    fun of(iv: Int): Int = when {
        iv == 0 -> 0
        iv <= 5 -> 1
        iv <= 10 -> 2
        iv <= 14 -> 3
        else -> 4
    }

    /** A bar group (atk, def, hp bars) packed as atk*25 + def*5 + hp, 0..124. */
    fun group(a: Int, d: Int, h: Int): Int = a * 25 + d * 5 + h
    fun groupOfIvs(a: Int, d: Int, h: Int): Int = group(of(a), of(d), of(h))
    fun atk(g: Int) = g / 25
    fun def(g: Int) = g / 5 % 5
    fun hp(g: Int) = g % 5
    fun label(g: Int) = "${atk(g)}-${def(g)}-${hp(g)}"
    const val GROUPS = 125
}

/** One IV spread at its best level under the CP cap. */
data class RankedSpread(
    val atkIv: Int, val defIv: Int, val hpIv: Int,
    val level: Double, val cp: Int,
    val statProduct: Double,
    /** 1 + number of spreads with a strictly higher stat product, so ties share a rank. */
    val rank: Int,
    /** Stat product as a percentage of the best spread's. */
    val percent: Double,
)

/**
 * Per bar group, the best rank and best stat-product % reached by any spread in that group —
 * all the search-string builder needs. Groups with no valid spread: rank Int.MAX_VALUE, percent 0.
 */
class GroupSummary(val bestRank: IntArray, val bestPercent: DoubleArray) {
    fun groupsWithRankAtMost(n: Int): Set<Int> = (0 until Bars.GROUPS).filterTo(HashSet()) { bestRank[it] <= n }
    fun groupsWithPercentAtLeast(p: Double): Set<Int> =
        (0 until Bars.GROUPS).filterTo(HashSet()) { bestPercent[it] > 0 && bestPercent[it] >= p - 1e-9 }
}

/**
 * IV rankings for one Pokémon in one league: every spread at the highest level (≤ maxLevel) whose
 * CP is ≤ the cap, ordered by stat product `atk·def·floor(hp)` (as PvPoke and Poke Genie do).
 */
class IvRanker(private val cpm: List<Double>) {

    fun cp(s: BaseStats, a: Int, d: Int, h: Int, level: Double): Int {
        val c = cpm[GameData.levelIndex(level)]
        return cpOf((s.atk + a).toDouble(), sqrt((s.def + d).toDouble()), sqrt((s.hp + h).toDouble()), c)
    }

    /** Max CP of a 15/15/15 at [maxLevel]. */
    fun maxCp(s: BaseStats, maxLevel: Double) = cp(s, 15, 15, 15, maxLevel)

    /** Raw per-spread results; index = atk*256 + def*16 + hp. statProduct < 0 = can't get under the cap. */
    private class Table(val statProduct: DoubleArray, val levelIndex: IntArray, val cp: IntArray) {
        /** Valid stat products, ascending. */
        val sorted: DoubleArray = statProduct.filter { it >= 0 }.toDoubleArray().also { it.sort() }
        val best: Double get() = sorted.last()

        /** 1 + number of stat products strictly (beyond float noise) higher than [sp]. */
        fun rankOf(sp: Double): Int {
            val threshold = sp * (1 + TIE_EPS)
            var lo = 0
            var hi = sorted.size // first index with value > threshold
            while (lo < hi) {
                val m = (lo + hi) ushr 1
                if (sorted[m] > threshold) hi = m else lo = m + 1
            }
            return 1 + sorted.size - lo
        }
    }

    private fun table(s: BaseStats, cpCap: Int, maxLevel: Double): Table {
        val top = GameData.levelIndex(maxLevel)
        require(top in cpm.indices) { "level $maxLevel outside CPM table" }
        val sp = DoubleArray(4096) { -1.0 }
        val lvl = IntArray(4096) { -1 }
        val cps = IntArray(4096)
        for (a in 0..15) for (d in 0..15) for (h in 0..15) {
            val atk = (s.atk + a).toDouble()
            val sd = sqrt((s.def + d).toDouble())
            val sh = sqrt((s.hp + h).toDouble())
            // highest level index whose CP fits; CP never decreases with level
            var lo = -1
            var hi = top
            while (lo < hi) {
                val m = (lo + hi + 1) ushr 1
                if (cpOf(atk, sd, sh, cpm[m]) <= cpCap) lo = m else hi = m - 1
            }
            if (lo < 0) continue
            val i = a * 256 + d * 16 + h
            val c = cpm[lo]
            sp[i] = atk * c * (s.def + d) * c * floor((s.hp + h) * c)
            lvl[i] = lo
            cps[i] = cpOf(atk, sd, sh, c)
        }
        return Table(sp, lvl, cps)
    }

    /** Group summary only — the fast path used to fill the app's cache. */
    fun summary(s: BaseStats, cpCap: Int, maxLevel: Double): GroupSummary {
        val t = table(s, cpCap, maxLevel)
        val groupBest = DoubleArray(Bars.GROUPS) { -1.0 }
        for (i in 0 until 4096) {
            val g = Bars.groupOfIvs(i shr 8, (i shr 4) and 15, i and 15)
            if (t.statProduct[i] > groupBest[g]) groupBest[g] = t.statProduct[i]
        }
        val rank = IntArray(Bars.GROUPS) { Int.MAX_VALUE }
        val pct = DoubleArray(Bars.GROUPS)
        if (t.sorted.isEmpty()) return GroupSummary(rank, pct)
        for (g in 0 until Bars.GROUPS) if (groupBest[g] >= 0) {
            rank[g] = t.rankOf(groupBest[g])
            pct[g] = groupBest[g] / t.best * 100
        }
        return GroupSummary(rank, pct)
    }

    /** Every valid spread, best first (for detail screens and tests). */
    fun rankAll(s: BaseStats, cpCap: Int, maxLevel: Double): List<RankedSpread> {
        val t = table(s, cpCap, maxLevel)
        if (t.sorted.isEmpty()) return emptyList()
        return (0 until 4096).filter { t.statProduct[it] >= 0 }.map { i ->
            val sp = t.statProduct[i]
            RankedSpread(i shr 8, (i shr 4) and 15, i and 15, 1.0 + t.levelIndex[i] / 2.0, t.cp[i],
                sp, t.rankOf(sp), sp / t.best * 100)
        }.sortedWith(compareBy<RankedSpread> { it.rank }.thenByDescending { it.statProduct }
            .thenBy { it.atkIv }.thenBy { it.defIv }.thenBy { it.hpIv })
    }

    companion object {
        /** Stat products within this relative distance count as tied (floating-point noise only). */
        const val TIE_EPS = 1e-9

        /** Same operation order as PvPoke: floor(atk·√def·√hp·cpm² / 10), minimum 10. */
        private fun cpOf(atk: Double, sqrtDef: Double, sqrtHp: Double, c: Double) =
            max(10, floor(atk * sqrtDef * sqrtHp * (c * c) / 10).toInt())
    }
}
