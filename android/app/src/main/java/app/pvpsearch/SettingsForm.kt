package app.pvpsearch

import app.pvpsearch.engine.LeagueSettings
import app.pvpsearch.engine.SearchSettings
import app.pvpsearch.engine.formatPercent

/** Settings while they're being edited; number fields are kept as text so half-typed values are allowed. */
data class SettingsForm(val leagues: Map<String, LeagueForm>, val maxLength: String) {
    data class LeagueForm(val enabled: Boolean, val minMaxCp: String, val maxRank: String, val minPercent: String)

    fun minMaxCpError(key: String): String? = rangeError(leagues.getValue(key).minMaxCp, SearchSettings.MIN_CP_RANGE)
    fun maxRankError(key: String): String? = rangeError(leagues.getValue(key).maxRank, SearchSettings.RANK_RANGE)
    fun minPercentError(key: String): String? {
        val p = leagues.getValue(key).minPercent.toDoubleOrNull() ?: return "Enter a number"
        return if (p in 0.0..SearchSettings.MAX_PERCENT) null else "Must be 0–100"
    }
    fun maxLengthError(): String? = rangeError(maxLength, SearchSettings.MAX_LENGTH_RANGE)
    fun maxLengthOverSafe(): Boolean = (maxLength.toIntOrNull() ?: 0) > SearchSettings.SAFE_MAX_LENGTH

    val isValid: Boolean
        get() = maxLengthError() == null &&
            leagues.keys.all { minMaxCpError(it) == null && maxRankError(it) == null && minPercentError(it) == null }

    fun withLeague(key: String, f: (LeagueForm) -> LeagueForm) = copy(leagues = leagues + (key to f(leagues.getValue(key))))

    /** The edited settings; fields that aren't valid keep their value from [previous]. */
    fun toSettings(previous: SearchSettings) = SearchSettings(
        leagues = previous.leagues.mapValues { (key, old) ->
            val f = leagues[key] ?: return@mapValues old
            LeagueSettings(
                enabled = f.enabled,
                minMaxCp = if (minMaxCpError(key) == null) f.minMaxCp.toInt() else old.minMaxCp,
                maxRank = if (maxRankError(key) == null) f.maxRank.toInt() else old.maxRank,
                minPercent = if (minPercentError(key) == null) f.minPercent.toDouble() else old.minPercent,
            )
        },
        maxLength = if (maxLengthError() == null) maxLength.toInt() else previous.maxLength,
    )

    companion object {
        fun from(s: SearchSettings) = SettingsForm(
            leagues = s.leagues.mapValues { (_, l) ->
                LeagueForm(l.enabled, l.minMaxCp.toString(), l.maxRank.toString(), formatPercent(l.minPercent))
            },
            maxLength = s.maxLength.toString(),
        )

        private fun rangeError(text: String, range: IntRange): String? {
            val n = text.toIntOrNull() ?: return "Enter a number"
            return if (n in range) null else "Must be %,d–%,d".format(range.first, range.last)
        }
    }
}
