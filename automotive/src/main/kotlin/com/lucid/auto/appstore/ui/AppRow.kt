package com.lucid.auto.appstore.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lucid.auto.appstore.domain.AppItem
import com.lucid.auto.appstore.domain.AppState

object AppRowTags {
    fun row(packageName: String) = "appRow_$packageName"
    fun title(packageName: String) = "appTitle_$packageName"
    fun version(packageName: String) = "appVersion_$packageName"
    fun action(packageName: String) = "actionButton_$packageName"
    const val AUTO_UPDATE_SWITCH = "autoUpdateSwitch"
    const val REFRESH_BUTTON = "refreshButton"
    const val ERROR_BANNER = "errorBanner"
    const val APP_LIST = "appList"
}

/**
 * One catalogue row: icon, title, version line, and a state-dependent action control.
 *
 * The `INSTALLED` control is rendered disabled, so a tap cannot even reach [onAction]
 * (requirement FR-4.4).
 */
@Composable
fun AppRow(
    item: AppItem,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(AppRowTags.row(item.packageName))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppIcon(item)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(AppRowTags.title(item.packageName)),
            )
            Text(
                text = item.versionLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.hasFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(AppRowTags.version(item.packageName)),
            )
        }

        ActionControl(item = item, onAction = onAction)
    }
    HorizontalDivider()
}

@Composable
private fun AppIcon(item: AppItem) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val icon = item.icon
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = "${item.title} icon",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(48.dp),
            )
        } else {
            // Neutral placeholder: first letter of the title (FR-1.5).
            Text(
                text = item.title.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "${item.title} icon" },
            )
        }
    }
}

@Composable
private fun ActionControl(item: AppItem, onAction: (String) -> Unit) {
    Button(
        onClick = { onAction(item.packageName) },
        enabled = item.isActionable,
        modifier = Modifier.testTag(AppRowTags.action(item.packageName)),
        colors = when (item.state) {
            AppState.UPDATE -> ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
            )
            else -> ButtonDefaults.buttonColors()
        },
    ) {
        // While work is in flight the label does NOT change — a spinner appears beside it and
        // the control goes inert. The row still reads Install / Update / Installed.
        if (item.isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = item.actionLabel,
            modifier = Modifier.padding(start = if (item.isBusy) 8.dp else 0.dp),
        )
    }
}
