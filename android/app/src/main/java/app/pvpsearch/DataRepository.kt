package app.pvpsearch

import app.pvpsearch.engine.GameData
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the data file published by the repo's daily job (GitHub Pages) and keeps a copy on the
 * phone, so the app works offline. The small manifest is checked first; data.json is only
 * downloaded when its hash changes.
 */
class DataRepository(private val dir: File) {
    private val dataFile = File(dir, "data.json")
    private val manifestFile = File(dir, "manifest.json")

    fun hasData(): Boolean = dataFile.exists()

    /** The saved data file, or null before the first successful download. */
    fun cachedData(): String? = dataFile.takeIf { it.exists() }?.readText()

    /** When the saved data was generated (ISO date-time from the manifest), if known. */
    fun cachedGeneratedAt(): String? =
        manifestFile.takeIf { it.exists() }?.let { JSONObject(it.readText()).optString("generatedAt", "") }
            ?.ifEmpty { null }

    /** Hash of the saved data file (changes whenever the daily job publishes new data). */
    fun cachedSha(): String? =
        manifestFile.takeIf { it.exists() }?.let { JSONObject(it.readText()).optString("dataSha256", "") }
            ?.ifEmpty { null }

    /** Fetch the manifest and download new data if it changed. Returns true if new data was saved. */
    fun update(): Boolean {
        val manifestText = get(BASE_URL + "manifest.json").decodeToString()
        val manifest = JSONObject(manifestText)
        val schema = manifest.getInt("schemaVersion")
        check(schema == GameData.SUPPORTED_SCHEMA) {
            "The data format has changed (version $schema). Please update the app."
        }
        val sha = manifest.getString("dataSha256")
        val saved = manifestFile.takeIf { it.exists() }?.let { JSONObject(it.readText()).optString("dataSha256") }
        if (sha == saved && dataFile.exists()) return false

        val bytes = get(BASE_URL + manifest.getString("dataFile"))
        check(sha256(bytes) == sha) { "The downloaded data was incomplete. Please try again." }
        writeAtomically(dataFile, bytes)
        writeAtomically(manifestFile, manifestText.toByteArray())
        return true
    }

    private fun get(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.useCaches = false
            val code = conn.responseCode
            check(code == 200) { "Download failed (HTTP $code)" }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(bytes)
        check(tmp.renameTo(target)) { "Could not save ${target.name}" }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val BASE_URL = "https://aseabacon.github.io/PogoSearchString/v1/"
    }
}
