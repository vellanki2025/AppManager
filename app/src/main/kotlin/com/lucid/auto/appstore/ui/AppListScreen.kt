package com.lucid.auto.appstore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.lucid.auto.appstore.data.install.InstallMode
import com.lucid.auto.appstore.domain.AppItem

/**
 * The main screen: a vertically scrolling list of managed apps with an auto-update toggle.
 *
 * Stateless by design — it takes [AppListUiState] and callbacks, which is what lets the Compose
 * tests drive every state without a ViewModel, a network, or a device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    state: AppListUiState,
    onAppAction: (String) -> Unit,
    onRefresh: () -> Unit,
    onAutoUpdateChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Lucid App Store") },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto-update", style = MaterialTheme.typography.labelLarge)
                        Switch(
                            checked = state.autoUpdateEnabled,
                            onCheckedChange = onAutoUpdateChanged,
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .testTag(AppRowTags.AUTO_UPDATE_SWITCH),
                        )
                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier.testTag(AppRowTags.REFRESH_BUTTON),
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            state.errorMessage?.let { message ->
                ErrorBanner(message = message, onRetry = onRefresh)
            }

            if (state.installMode == InstallMode.INTERACTIVE) {
                InfoNote(
                    "Running unprivileged: installs show the system confirmation dialog. " +
                        "A privileged system build installs silently.",
                )
            }

            AppList(items = state.items, onAppAction = onAppAction)
        }
    }
}

@Composable
private fun AppList(items: List<AppItem>, onAppAction: (String) -> Unit) {
    if (items.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No apps to show yet.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().testTag(AppRowTags.APP_LIST)) {
        items(items, key = { it.packageName }) { item ->
            AppRow(item = item, onAction = onAppAction)
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .testTag(AppRowTags.ERROR_BANNER),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun InfoNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
