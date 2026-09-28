package app.pvpsearch.engine

/** In-game search keywords for the three appraisal stats (localizable; English by default). */
data class StatKeywords(val attack: String = "attack", val defense: String = "defense", val hp: String = "hp") {
    val all: List<String> get() = listOf(attack, defense, hp)
}

/** One search string and the dex numbers it covers. */
data class SearchPart(val text: String, val firstDex: Int, val lastDex: Int)

/**
 * Builds Pokémon GO search strings that match exactly the (dex, appraisal bar group) pairs in a target.
 *
 * Game syntax: `,` `;` `:` = OR, `&` = AND (applied last), `!` = NOT, no parentheses, so every
 * string is an AND of OR-lists. Terms: dex numbers/ranges (`12-28`) and bar ranges (`1-4attack`).
 *
 * 1. Per-stat implication clauses: for each stat and bar value v,
 *    `<bars≠v><stat>,<dexes whose target allows v for that stat>`.
 * 2. Consecutive dexes compress into ranges. Dexes not in the target only need blocking in ONE
 *    ("strict") stat, so in the other two stats they can bridge ranges. All three choices are tried.
 * 3. Per-stat checks let through cross-combinations when a dex has several groups (e.g. Eevee);
 *    each false combination gets an exclusion clause `<not a><not d><not h>,<other dexes>`.
 * 4. If too long, the dex list is split into ranges, balancing part lengths.
 *
 * Every result can be checked with [matcher] / [mismatches].
 */
class SearchStringBuilder(
    /** Highest dex number in the game; dexes 1..maxDex are the universe the string must handle. */
    private val maxDex: Int,
    private val keywords: StatKeywords = StatKeywords(),
) {
    /**
     * @param target dex -> allowed bar groups (see [Bars.group])
     * @param maxLength max characters per string, including [suffix]
     * @param suffix appended verbatim to every string, e.g. `&!#pvp&!#notpvp&!purified`
     */
    fun build(target: Map<Int, Set<Int>>, maxLength: Int, suffix: String = ""): List<SearchPart> {
        val t = target.filterValues { it.isNotEmpty() }
        if (t.isEmpty()) return emptyList()
        val limit = maxLength - suffix.length
        val dexes = t.keys.sorted()
        val candidates = VARIANTS.map { (strict, pairwise) -> exact(t, strict, pairwise) }
        val whole = candidates.minBy { it.length }
        if (whole.length <= limit) return listOf(SearchPart(whole + suffix, dexes.first(), dexes.last()))

        // Searching for cut points builds thousands of candidate parts, so use only the construction
        // that is shortest for the whole target. The final parts get the full [best] below, which
        // can only make them shorter.
        val (strict, pairwise) = VARIANTS[candidates.indexOf(whole)]
        val cache = HashMap<Long, Int>()
        fun length(from: Int, to: Int): Int = cache.getOrPut(from.toLong() shl 32 or to.toLong()) {
            exact(dexes.subList(from, to).associateWith { t.getValue(it) }, strict, pairwise).length
        }

        fun greedy(lim: Int): List<Int>? { // cut indices, or null if a single dex doesn't fit
            val cuts = mutableListOf(0)
            var i = 0
            while (i < dexes.size) {
                if (length(i, i + 1) > lim) return null
                var lo = i + 1
                var hi = dexes.size
                while (lo < hi) {
                    val mid = (lo + hi + 1) ushr 1
                    if (length(i, mid) <= lim) lo = mid else hi = mid - 1
                }
                cuts.add(lo)
                i = lo
            }
            return cuts
        }

        var cuts = greedy(limit) ?: throw IllegalArgumentException(
            "Max length $maxLength is too short: a single Pokémon's string doesn't fit")
        // Same number of parts, but shrink the longest part (to within BALANCE_SLACK characters).
        val n = cuts.size
        var lo = (0 until n - 1).maxOf { length(cuts[it], cuts[it + 1]) } / 2
        var hi = limit
        while (hi - lo > BALANCE_SLACK) {
            val mid = (lo + hi) ushr 1
            val c = greedy(mid)
            if (c != null && c.size <= n) hi = mid else lo = mid + 1
        }
        cuts = greedy(hi)!!
        return (0 until cuts.size - 1).map {
            val part = best(dexes.subList(cuts[it], cuts[it + 1]).associateWith { d -> t.getValue(d) })
            SearchPart(part + suffix, dexes[cuts[it]], dexes[cuts[it + 1] - 1])
        }
    }

    /** Shortest exact string for [per] (no splitting). */
    fun best(per: Map<Int, Set<Int>>): String =
        VARIANTS.map { (strict, pairwise) -> exact(per, strict, pairwise) }.minBy { it.length }

    // --- construction -------------------------------------------------------------------------

    private fun stat(g: Int, s: Int) = when (s) { 0 -> Bars.atk(g); 1 -> Bars.def(g); else -> Bars.hp(g) }

    private fun basic(per: Map<Int, Set<Int>>, strict: Int): List<String> {
        val clauses = ArrayList<String>()
        for (s in 0..2) {
            val byValue = Array(5) { sortedSetOf<Int>() }
            for ((dex, gs) in per) for (g in gs) byValue[stat(g, s)].add(dex)
            for (v in 0..4) {
                val others = valueTerms((0..4).filter { it != v }, s)
                val ds = byValue[v]
                if (ds.isEmpty()) { clauses.add(others); continue }
                var rs = ranges(ds)
                if (s != strict) rs = bridge(rs, per.keys)
                clauses.add(others + "," + formatRanges(rs))
            }
        }
        return clauses
    }

    /** Merge ranges whose gaps contain only dexes outside the target (the strict stat blocks those). */
    private fun bridge(rs: List<IntRange>, listed: Set<Int>): List<IntRange> {
        val out = mutableListOf(rs[0])
        for (r in rs.drop(1)) {
            val last = out.last()
            if ((last.last + 1 until r.first).none { it in listed }) out[out.size - 1] = last.first..r.last
            else out.add(r)
        }
        return out
    }

    private fun exact(per: Map<Int, Set<Int>>, strict: Int, pairwise: Boolean): String {
        val clauses = basic(per, strict).toMutableList()
        // combinations the per-stat clauses let through, per dex
        val cur = per.mapValues { (_, gs) ->
            val a = gs.map { Bars.atk(it) }.toSet(); val d = gs.map { Bars.def(it) }.toSet(); val h = gs.map { Bars.hp(it) }.toSet()
            a.flatMap { x -> d.flatMap { y -> h.map { z -> Bars.group(x, y, z) } } }.toMutableSet()
        }
        if (pairwise) {
            for ((i, j) in listOf(0 to 1, 0 to 2, 1 to 2)) {
                val offend = sortedMapOf<Int, MutableSet<Int>>() // pair key i-value*5+j-value -> dexes
                for ((dex, gs) in per) {
                    val proj = gs.map { stat(it, i) * 5 + stat(it, j) }.toSet()
                    for (vi in gs.map { stat(it, i) }.toSet()) for (vj in gs.map { stat(it, j) }.toSet()) {
                        val key = vi * 5 + vj
                        if (key !in proj) offend.getOrPut(key) { sortedSetOf() }.add(dex)
                    }
                }
                for ((key, ds) in offend) {
                    val vi = key / 5; val vj = key % 5
                    clauses.add((listOf(notValue(vi, i), notValue(vj, j)) + formatRanges(complement(ds))).joinToString(","))
                    for (d in ds) cur.getValue(d).removeAll { stat(it, i) == vi && stat(it, j) == vj }
                }
            }
        }
        val offend = sortedMapOf<Int, MutableSet<Int>>()
        for ((dex, gs) in per) for (g in cur.getValue(dex)) if (g !in gs) offend.getOrPut(g) { sortedSetOf() }.add(dex)
        for ((g, ds) in offend) {
            clauses.add((listOf(notValue(Bars.atk(g), 0), notValue(Bars.def(g), 1), notValue(Bars.hp(g), 2)) +
                formatRanges(complement(ds))).joinToString(","))
        }
        return clauses.joinToString("&")
    }

    private fun notValue(v: Int, s: Int) = valueTerms((0..4).filter { it != v }, s)

    private fun valueTerms(values: List<Int>, s: Int) =
        ranges(values).joinToString(",") { rangeText(it) + keywords.all[s] }

    /** All dexes 1..maxDex except [excluded], as ranges. */
    private fun complement(excluded: Set<Int>): List<IntRange> {
        val out = ArrayList<IntRange>()
        var cur = 1
        for (x in excluded.sorted()) {
            if (x > cur) out.add(cur until x)
            cur = x + 1
        }
        if (cur <= maxDex) out.add(cur..maxDex)
        return out
    }

    // --- verification -------------------------------------------------------------------------

    /**
     * Parse [s] once into a predicate (dex, atkBar, defBar, hpBar) -> would the game show it?
     * Core syntax only (dex/bar terms); tags such as `!#pvp` aren't supported.
     */
    fun matcher(s: String): (Int, Int, Int, Int) -> Boolean {
        // term: [stat (0 = dex, 1..3 = atk/def/hp), lo, hi]
        val clauses = s.split("&").map { clause ->
            clause.split(",").map { term ->
                val m = TERM.matchEntire(term) ?: throw IllegalArgumentException("unexpected term '$term'")
                val stat = when (m.groupValues[3]) {
                    "" -> 0
                    keywords.attack -> 1
                    keywords.defense -> 2
                    keywords.hp -> 3
                    else -> throw IllegalArgumentException("unknown keyword in '$term'")
                }
                val lo = m.groupValues[1].toInt()
                intArrayOf(stat, lo, m.groupValues[2].ifEmpty { m.groupValues[1] }.toInt())
            }.toTypedArray()
        }.toTypedArray()
        return { dex, a, d, h ->
            val x = intArrayOf(dex, a, d, h)
            clauses.all { terms -> terms.any { x[it[0]] in it[1]..it[2] } }
        }
    }

    /** Number of (dex, bar group) pairs, over the whole dex, where [s] disagrees with [target]. */
    fun mismatches(s: String, target: Map<Int, Set<Int>>): Int {
        val match = matcher(s)
        var bad = 0
        for (dex in 1..maxDex) for (g in 0 until Bars.GROUPS) {
            val want = target[dex]?.contains(g) == true
            if (match(dex, Bars.atk(g), Bars.def(g), Bars.hp(g)) != want) bad++
        }
        return bad
    }

    companion object {
        /** (strict stat, pairwise exclusion clauses) combinations tried by [best]. */
        private val VARIANTS = (0..2).flatMap { listOf(it to true, it to false) }
        private const val BALANCE_SLACK = 64

        private val TERM = Regex("""(\d+)(?:-(\d+))?([^\d,&]*)""")

        fun ranges(xs: Collection<Int>): List<IntRange> {
            val out = ArrayList<IntRange>()
            for (x in xs.sorted()) {
                if (out.isNotEmpty() && x == out.last().last + 1) out[out.size - 1] = out.last().first..x
                else out.add(x..x)
            }
            return out
        }

        private fun rangeText(r: IntRange) = if (r.first == r.last) "${r.first}" else "${r.first}-${r.last}"
        fun formatRanges(rs: List<IntRange>) = rs.joinToString(",") { rangeText(it) }
    }
}
