package app.pvpsearch.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IvRankerTest {
    private val data = Fixtures.data
    private val ranker = IvRanker(data.cpm)

    @Test
    fun barGroups() {
        assertEquals(listOf(0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 3, 4), (0..15).map { Bars.of(it) })
        assertEquals("4-0-2", Bars.label(Bars.groupOfIvs(15, 0, 7)))
    }

    @Test
    fun cpmTableMatchesKnownValues() {
        assertEquals(0.0939999967813491, data.cpmAt(1.0), 1e-15)
        assertEquals(0.792803950958807, data.cpmAt(40.5), 1e-15) // PvPoke's value
        assertEquals(0.845300018787384, data.cpmAt(51.0), 1e-15)
        assertEquals(51.0, data.maxLevel)
    }

    /**
     * Dittobase's rank-1 spread (IVs, level, CP) for every Great/Ultra row downloaded in the first
     * project must be one of the engine's rank-1 spreads at level 50 (Dittobase lists one per species;
     * the engine also returns ties).
     */
    @Test
    fun rank1MatchesDittobase() {
        val rows = Fixtures.csv("dittobase_rank1_pvp_ivs.csv")
        val byDex = data.species.groupBy { it.dex }
        val caps = mapOf("Great" to 1500, "Ultra" to 2500)
        var compared = 0
        val unmatched = ArrayList<String>()
        val differences = ArrayList<String>()
        for (r in rows) {
            val cap = caps[r.getValue("league")] ?: continue
            val sp = byDex[r.getValue("dex").toInt()].orEmpty()
                .firstOrNull { Fixtures.nameKey(it.name) == Fixtures.nameKey(r.getValue("pokemon")) }
            if (sp == null) { unmatched += "${r["league"]} ${r["pokemon"]}"; continue }
            compared++
            val want = listOf(r.getValue("atk_iv").toInt(), r.getValue("def_iv").toInt(), r.getValue("sta_iv").toInt(),
                r.getValue("level").toDouble(), r.getValue("cp").toInt())
            val top = ranker.rankAll(sp.stats, cap, 50.0).filter { it.rank == 1 }
                .map { listOf(it.atkIv, it.defIv, it.hpIv, it.level, it.cp) }
            if (want !in top) differences += "${r["league"]} ${sp.id}: Dittobase $want, engine $top"
        }
        println("compared $compared rows; ${unmatched.size} without a same-named species (naming differences)")
        differences.forEach(::println)
        assertTrue(compared > 1300, "too few rows matched ($compared)")
        assertEquals(0, differences.size, "rank-1 differences")
    }

    @Test
    fun tiesShareRankOne() {
        // Raichu UL: 15-15-14 and 15-15-15 floor to the same HP (noted in the first project)
        val raichu = data.species.first { it.id == "raichu" }
        val top = ranker.rankAll(raichu.stats, 2500, 50.0).filter { it.rank == 1 }
        assertEquals(setOf("15-15-14", "15-15-15"), top.map { "${it.atkIv}-${it.defIv}-${it.hpIv}" }.toSet())
    }

    @Test
    fun summaryAgreesWithFullRanking() {
        for (id in listOf("medicham", "azumarill", "eevee", "giratina_altered", "mewtwo")) {
            val s = data.species.first { it.id == id }
            for (cap in listOf(500, 1500, 2500, 10000)) {
                val full = ranker.rankAll(s.stats, cap, 50.0)
                val sum = ranker.summary(s.stats, cap, 50.0)
                for (g in 0 until Bars.GROUPS) {
                    val inGroup = full.filter { Bars.groupOfIvs(it.atkIv, it.defIv, it.hpIv) == g }
                    assertEquals(inGroup.minOfOrNull { it.rank } ?: Int.MAX_VALUE, sum.bestRank[g], "$id $cap ${Bars.label(g)}")
                    assertEquals(inGroup.maxOfOrNull { it.percent } ?: 0.0, sum.bestPercent[g], 1e-9)
                }
            }
        }
    }

    @Test
    fun timeFullCache() {
        val cache = RankingCache(data)
        val keys = cache.keysFor(data.leagues.keys, listOf(50.0, 51.0))
        val t0 = System.nanoTime()
        keys.forEach(cache::compute)
        println("computed ${keys.size} rankings in %.1f s (single thread)".format((System.nanoTime() - t0) / 1e9))
    }
}
