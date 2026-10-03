package app.pvpsearch.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TargetSelectorTest {
    private val selector = TargetSelector(RankingCache(Fixtures.data))

    @Test
    fun littleCupKeepsOnlyUnevolvedSpeciesThatCanEvolve() {
        val ids = selector.select(SearchOptions(listOf(LeagueOptions("little", unevolvedOnly = true))))
            .contributions.map { it.species.id }.toSet()
        assertTrue("pichu" in ids) // baby
        assertTrue("bulbasaur" in ids)
        assertTrue("meowth_galarian" in ids) // regional form with its own evolution
        assertTrue("pikachu" !in ids) // evolves from Pichu
        assertTrue("ivysaur" !in ids)
        assertTrue("venusaur" !in ids)
        assertTrue("tauros" !in ids) // can't evolve
    }

    @Test
    fun littleCupRank1OfAWeakPokemonIsTheHundo() {
        // Happiny can't reach 500 CP, so its best spread is 15-15-15 at the level cap
        val sel = selector.select(SearchOptions(listOf(LeagueOptions("little", unevolvedOnly = true))))
        val happiny = sel.contributions.single { it.species.id == "happiny" }
        assertEquals(setOf(Bars.group(4, 4, 4)), happiny.groups)
    }

    @Test
    fun cutoffCombinesRankAndPercent() {
        val data = Fixtures.data
        val cache = RankingCache(data)
        val azu = data.species.single { it.id == "azumarill" }
        val sum = cache.summary(azu, "great", 50.0)
        val rank1 = Cutoff().groups(sum)
        // 100 % = rank 1 and its ties; any % ≤ 100 with rank 1 = rank 1
        assertEquals(rank1, Cutoff(maxRank = Cutoff.MAX_RANK, minPercent = 100.0).groups(sum))
        assertEquals(rank1, Cutoff(maxRank = 1, minPercent = 50.0).groups(sum))
        val top100 = Cutoff(maxRank = 100).groups(sum)
        val p99 = Cutoff(maxRank = Cutoff.MAX_RANK, minPercent = 99.0).groups(sum)
        assertTrue(top100.containsAll(rank1) && top100.size > rank1.size)
        // both limits = intersection
        assertEquals(top100 intersect p99, Cutoff(maxRank = 100, minPercent = 99.0).groups(sum))
        // no limits = every group that has a valid spread
        assertEquals(125, Cutoff(maxRank = Cutoff.MAX_RANK).groups(sum).size)
    }
}
