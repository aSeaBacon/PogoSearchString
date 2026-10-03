package app.pvpsearch

import app.pvpsearch.engine.SearchSettings
import java.io.File

/** The user's settings, saved as JSON in the app's private files. */
class SettingsStore(dir: File) {
    private val file = File(dir, "settings.json")

    fun load(): SearchSettings = SearchSettings.parse(file.takeIf { it.exists() }?.readText())

    fun save(settings: SearchSettings) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(settings.toJson())
        check(tmp.renameTo(file)) { "Could not save settings" }
    }
}
