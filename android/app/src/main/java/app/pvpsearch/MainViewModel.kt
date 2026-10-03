package app.pvpsearch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pvpsearch.engine.GameData
import app.pvpsearch.engine.RankingCache
import app.pvpsearch.engine.SearchSettings
import app.pvpsearch.engine.SearchStringItem
import app.pvpsearch.engine.StringGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiState {
    data class Working(val message: String) : UiState
    data class Ready(
        val items: List<SearchStringItem>,
        /** When the data was generated, e.g. "2026-09-28". */
        val dataDate: String?,
        /** Set when the update check failed and saved data was used instead. */
        val offlineReason: String?,
        /** The settings [items] were built with. */
        val settings: SearchSettings,
    ) : UiState
    data class Failed(val message: String) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DataRepository(app.filesDir)
    private val stringCache = StringCache(app.filesDir)
    private val settingsStore = SettingsStore(app.filesDir)

    private val _state = MutableStateFlow<UiState>(UiState.Working("Loading…"))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<SearchSettings> = _settings.asStateFlow()

    /** Non-null while the settings screen is open. */
    private val _form = MutableStateFlow<SettingsForm?>(null)
    val form: StateFlow<SettingsForm?> = _form.asStateFlow()

    private var job: Job? = null
    private var offlineReason: String? = null

    /** IV rankings for the current data, kept so changing settings doesn't rank everything again. */
    private class Loaded(val sha: String?, val cache: RankingCache)
    private var loaded: Loaded? = null

    init {
        refresh()
    }

    /** Check for new data, then show the strings. */
    fun refresh() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.value = UiState.Working("Checking for new data…")
            showStrings {
                val update = withContext(Dispatchers.IO) { runCatching { repo.update() } }
                offlineReason = update.exceptionOrNull()?.message
                if (!repo.hasData()) throw IllegalStateException(
                    "Couldn't download the Pokémon data. Check your internet connection and try again." +
                        (offlineReason?.let { "\n\n($it)" } ?: "")
                )
            }
        }
    }

    fun openSettings() {
        _form.value = SettingsForm.from(_settings.value)
    }

    fun editSettings(form: SettingsForm) {
        _form.value = form
    }

    fun resetSettings() {
        _form.value = SettingsForm.from(SearchSettings())
    }

    /** Close the settings screen and rebuild the strings if anything changed. Returns false if some invalid values were discarded. */
    fun closeSettings(): Boolean {
        val form = _form.value ?: return true
        _form.value = null
        val new = form.toSettings(_settings.value)
        if (new != _settings.value) {
            _settings.value = new
            viewModelScope.launch(Dispatchers.IO) { settingsStore.save(new) }
            // A running job picks up the new settings when it finishes (see showStrings).
            if (job?.isActive != true && repo.hasData()) job = viewModelScope.launch { showStrings {} }
        }
        return form.isValid
    }

    /**
     * Runs [before], then builds (or loads saved) strings for the current settings. If the settings
     * change during the build, it builds again. Errors are shown on screen.
     */
    private suspend fun showStrings(before: suspend () -> Unit) {
        try {
            before()
            var settings: SearchSettings
            var items: List<SearchStringItem>
            do {
                settings = _settings.value
                items = stringsFor(settings)
            } while (settings != _settings.value)
            _state.value = UiState.Ready(
                items = items,
                dataDate = repo.cachedGeneratedAt()?.take(10),
                offlineReason = offlineReason,
                settings = settings,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UiState.Failed(e.message ?: e.toString())
        }
    }

    private suspend fun stringsFor(settings: SearchSettings): List<SearchStringItem> {
        val sha = repo.cachedSha()
        val key = "$sha:${StringGenerator.VERSION}:${settings.toJson()}"
        withContext(Dispatchers.IO) { stringCache.load(key) }?.let { return it }
        val items = withContext(Dispatchers.Default) {
            val cache = loaded?.takeIf { it.sha == sha }?.cache ?: run {
                _state.value = UiState.Working("Reading data…")
                val text = repo.cachedData() ?: throw IllegalStateException("The saved data is missing. Tap refresh to download it.")
                RankingCache(GameData.parse(text)).also { loaded = Loaded(sha, it) }
            }
            StringGenerator.build(cache, settings) { _state.value = UiState.Working(it) }
        }
        withContext(Dispatchers.IO) { stringCache.save(key, items) }
        return items
    }
}
