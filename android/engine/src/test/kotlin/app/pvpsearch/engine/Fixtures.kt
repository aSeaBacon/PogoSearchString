package app.pvpsearch.engine

object Fixtures {
    fun text(name: String): String =
        Fixtures::class.java.getResource("/$name")?.readText() ?: error("missing test resource $name")

    /** Snapshot of the pipeline output (pvp-search-data/docs/v1/data.json). */
    val data: GameData by lazy { GameData.parse(text("data.json")) }

    /** Minimal CSV reader (quoted fields, no embedded newlines). */
    fun csv(name: String): List<Map<String, String>> {
        val lines = text(name).lines().filter { it.isNotBlank() }
        fun split(line: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var quoted = false
            for (ch in line) when {
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out.add(cur.toString()); cur.clear() }
                else -> cur.append(ch)
            }
            out.add(cur.toString())
            return out
        }
        val header = split(lines[0])
        return lines.drop(1).map { header.zip(split(it)).toMap() }
    }

    /** Word set used to match Dittobase names ("Alolan Ninetales") to PvPoke's ("Ninetales (Alolan)"). */
    fun nameKey(name: String): Set<String> =
        name.lowercase().replace(Regex("[()’'.%:]"), " ").split(Regex("\\s+"))
            .filter { it.isNotEmpty() && it != "forme" && it != "form" }.toSet()
}
