package app.pvpsearch.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The data file published by the pvp-search-data pipeline (schema v1). */
@Serializable
data class GameData(
    val schemaVersion: Int,
    /** CP multiplier for levels 1, 1.5, 2, … (index k = level 1 + k/2). */
    val cpm: List<Double>,
    /** League name -> CP cap ("master" has a nominal 10000 cap). */
    val leagues: Map<String, Int>,
    val species: List<Species>,
    val rankings: Map<String, LeagueRankings>,
) {
    val maxDex: Int get() = species.maxOf { it.dex }
    val maxLevel: Double get() = 1.0 + (cpm.size - 1) / 2.0

    fun cpmAt(level: Double): Double = cpm[levelIndex(level)]

    companion object {
        const val SUPPORTED_SCHEMA = 1
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): GameData {
            val data = json.decodeFromString(serializer(), text)
            require(data.schemaVersion == SUPPORTED_SCHEMA) {
                "Unsupported data schema ${data.schemaVersion} (app supports $SUPPORTED_SCHEMA)"
            }
            return data
        }

        fun levelIndex(level: Double): Int = Math.round((level - 1.0) * 2).toInt()
    }
}

@Serializable
data class Species(
    val id: String,
    val name: String,
    val dex: Int,
    val atk: Int,
    val def: Int,
    val hp: Int,
    val released: Boolean,
    val evolvesTo: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    val stats: BaseStats get() = BaseStats(atk, def, hp)
}

@Serializable
data class LeagueRankings(
    val total: Int,
    /** PvPoke species id (shadows as "<id>_shadow") -> position, 1 = best. */
    val ranks: Map<String, Int>,
)

data class BaseStats(val atk: Int, val def: Int, val hp: Int)
