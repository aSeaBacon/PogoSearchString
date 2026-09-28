package app.pvpsearch

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saves the finished strings so later launches show them instantly. They're reused only while the
 * key (data hash + generator version) is unchanged.
 */
class StringCache(dir: File) {
    private val file = File(dir, "strings.json")

    fun load(key: String): List<SearchStringItem>? = try {
        file.takeIf { it.exists() }?.let { JSONObject(it.readText()) }
            ?.takeIf { it.getString("key") == key }
            ?.getJSONArray("items")
            ?.let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    SearchStringItem(o.getString("title"), o.getString("text"), o.getInt("firstDex"), o.getInt("lastDex"))
                }
            }
    } catch (e: Exception) {
        null // unreadable cache: just rebuild
    }

    fun save(key: String, items: List<SearchStringItem>) {
        val arr = JSONArray()
        for (item in items) {
            arr.put(JSONObject().put("title", item.title).put("text", item.text).put("firstDex", item.firstDex).put("lastDex", item.lastDex))
        }
        file.writeText(JSONObject().put("key", key).put("items", arr).toString())
    }
}
