package app.pvpsearch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.pvpsearch.engine.Cutoff
import app.pvpsearch.engine.SearchSettings
import app.pvpsearch.engine.StringGenerator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    form: SettingsForm,
    onChange: (SettingsForm) -> Unit,
    onClose: () -> Unit,
    onReset: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                    }
                },
                actions = { TextButton(onClick = onReset) { Text("Reset") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionTitle("Leagues") }
            items(StringGenerator.LEAGUES.size) { i ->
                val league = StringGenerator.LEAGUES[i]
                val f = form.leagues.getValue(league.key)
                LeagueCard(
                    title = league.title,
                    form = f,
                    minCpError = form.minMaxCpError(league.key),
                    rankError = form.maxRankError(league.key),
                    percentError = form.minPercentError(league.key),
                    onChange = { new -> onChange(form.withLeague(league.key) { new }) },
                )
            }
            item { SectionTitle("Search strings") }
            item {
                val error = form.maxLengthError()
                OutlinedTextField(
                    value = form.maxLength,
                    onValueChange = { onChange(form.copy(maxLength = digits(it))) },
                    label = { Text("Max characters per string") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        when {
                            error != null -> Text(error)
                            form.maxLengthOverSafe() -> Text(
                                "Not all devices support search strings over %,d characters. Android phones stop at %,d, so longer strings get cut off there."
                                    .format(SearchSettings.SAFE_MAX_LENGTH, SearchSettings.SAFE_MAX_LENGTH),
                                color = MaterialTheme.colorScheme.error,
                            )
                            else -> Text("Longer searches are split into several strings. Default %,d.".format(SearchSettings.DEFAULT_MAX_LENGTH))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun LeagueCard(
    title: String,
    form: SettingsForm.LeagueForm,
    minCpError: String?,
    rankError: String?,
    percentError: String?,
    onChange: (SettingsForm.LeagueForm) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = form.enabled, onCheckedChange = { onChange(form.copy(enabled = it)) })
            }
            if (form.enabled) {
                OutlinedTextField(
                    value = form.minMaxCp,
                    onValueChange = { onChange(form.copy(minMaxCp = digits(it))) },
                    label = { Text("Minimum max CP") },
                    singleLine = true,
                    isError = minCpError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        Text(minCpError ?: "Skip Pokémon that can't reach this CP, even as a best buddy (level 51). 0 = no limit.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.maxRank,
                    onValueChange = { onChange(form.copy(maxRank = digits(it))) },
                    label = { Text("IV rank (top N)") },
                    singleLine = true,
                    isError = rankError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        Text(rankError ?: "Include IVs ranked this high or better. 1 = rank 1 and its ties, ${Cutoff.MAX_RANK} = any rank.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.minPercent,
                    onValueChange = { onChange(form.copy(minPercent = decimal(it))) },
                    label = { Text("Minimum stat product %") },
                    singleLine = true,
                    isError = percentError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = {
                        Text(percentError ?: "…and at least this % of rank 1's stat product. 0 = no limit.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun digits(text: String) = text.filter(Char::isDigit).take(5)

/** Digits and at most one decimal point (a comma, as typed in some locales, counts as the point). */
private fun decimal(text: String): String {
    val t = text.replace(',', '.').filter { it.isDigit() || it == '.' }
    val dot = t.indexOf('.')
    return (if (dot < 0) t else t.substring(0, dot + 1) + t.substring(dot + 1).replace(".", "")).take(7)
}
