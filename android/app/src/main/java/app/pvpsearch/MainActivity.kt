package app.pvpsearch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pvpsearch.engine.SearchStringItem
import app.pvpsearch.engine.StringGenerator

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        // Android 12+: use the colours from the phone's wallpaper theme
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val context = LocalContext.current

    form?.let { f ->
        val close: () -> Unit = {
            if (!vm.closeSettings()) {
                Toast.makeText(context, "Invalid values weren't saved", Toast.LENGTH_SHORT).show()
            }
        }
        BackHandler(onBack = close)
        SettingsScreen(f, onChange = vm::editSettings, onClose = close, onReset = vm::resetSettings)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PvP Search Strings") },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = state !is UiState.Working) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Check for new data")
                    }
                    IconButton(onClick = vm::openSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            is UiState.Working -> CenteredMessage(padding) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(s.message)
            }
            is UiState.Failed -> CenteredMessage(padding) {
                Text(s.message, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = vm::refresh) { Text("Try again") }
            }
            is UiState.Ready -> StringList(s, padding)
        }
    }
}

@Composable
private fun CenteredMessage(padding: PaddingValues, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
private fun StringList(state: UiState.Ready, padding: PaddingValues) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                for (league in StringGenerator.LEAGUES) {
                    val ls = state.settings.league(league.key)
                    if (!ls.enabled) continue
                    Text(
                        "${league.title}: ${ls.describeCutoff()}, level ${league.levelCaps.joinToString(" + ") { formatLevel(it) }}" +
                            (if (ls.minMaxCp > 0) ", max CP ≥ ${ls.minMaxCp}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.dataDate?.let {
                    Text(
                        "Data from $it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.offlineReason?.let {
                    Text(
                        "Couldn't check for new data, showing saved data. ($it)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (state.items.isEmpty()) {
            item {
                Text(
                    "No leagues are turned on. Turn one on in Settings.",
                    modifier = Modifier.padding(top = 24.dp).fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
        items(state.items, key = { it.title }) { StringCard(it) }
    }
}

@Composable
private fun StringCard(item: SearchStringItem) {
    val context = LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${item.text.length} characters · dex ${item.firstDex}–${item.lastDex}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        item.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                    )
                }
            }
            IconButton(onClick = { copyToClipboard(context, item) }) {
                Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy ${item.title}")
            }
        }
    }
}

private fun copyToClipboard(context: Context, item: SearchStringItem) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(item.title, item.text))
    // Android 13+ shows its own "Copied" confirmation
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "${item.title} copied", Toast.LENGTH_SHORT).show()
    }
}

/** 50.0 -> "50", 40.5 -> "40.5". */
private fun formatLevel(level: Double) = if (level % 1.0 == 0.0) level.toInt().toString() else level.toString()
