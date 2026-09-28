package app.pvpsearch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pvpsearch.engine.GameData
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
    ) : UiState
    data class Failed(val message: String) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DataRepository(app.filesDir)
    private val _state = MutableStateFlow<UiState>(UiState.Working("Loading…"))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.value = UiState.Working("Checking for new data…")
            try {
                val update = withContext(Dispatchers.IO) { runCatching { repo.update() } }
                val text = withContext(Dispatchers.IO) { repo.cachedData() }
                    ?: throw IllegalStateException(
                        "Couldn't download the Pokémon data. Check your internet connection and try again." +
                            (update.exceptionOrNull()?.message?.let { "\n\n($it)" } ?: "")
                    )
                val items = withContext(Dispatchers.Default) {
                    val data = GameData.parse(text)
                    StringGenerator.build(data) { _state.value = UiState.Working(it) }
                }
                _state.value = UiState.Ready(
                    items = items,
                    dataDate = repo.cachedGeneratedAt()?.take(10),
                    offlineReason = update.exceptionOrNull()?.message,
                )
            } catch (e: Exception) {
                _state.value = UiState.Failed(e.message ?: e.toString())
            }
        }
    }
}
