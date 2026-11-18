package com.zdownloader.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.*
import com.zdownloader.app.ui.FlowDropdownMenu as DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zdownloader.app.R
import com.zdownloader.app.data.BatchSummary
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.ui.spectrumStringResource as stringResource

private fun filterLabelRes(filter: DownloadFilter): Int = when (filter) {
    DownloadFilter.ALL -> R.string.all
    DownloadFilter.ACTIVE -> R.string.active
    DownloadFilter.WAITING -> R.string.queued
    DownloadFilter.COMPLETED -> R.string.completed
    DownloadFilter.FAILED -> R.string.failed
    DownloadFilter.CANCELLED -> R.string.cancelled
}

private fun filterIcon(filter: DownloadFilter): ImageVector = when (filter) {
    DownloadFilter.ALL -> Icons.Outlined.FilterList
    DownloadFilter.ACTIVE -> Icons.Outlined.Downloading
    DownloadFilter.WAITING -> FlowIcons.Waiting
    DownloadFilter.COMPLETED -> FlowIcons.Success
    DownloadFilter.FAILED -> FlowIcons.Error
    DownloadFilter.CANCELLED -> FlowIcons.Close
}

@Composable
private fun StatusFilterTrigger(
    filter: DownloadFilter,
    label: String,
    count: Int,
    onClick: () -> Unit
) {
    val buttonDesc = stringResource(R.string.filter_by_status)
    val stateDesc = "$label ($count)"
    Surface(
        onClick = onClick,
        modifier = Modifier
            .widthIn(min = 48.dp, max = 160.dp)
            .heightIn(min = 48.dp)
            .testTag("downloads_filter_menu")
            .semantics {
                contentDescription = buttonDesc
                stateDescription = stateDesc
                role = Role.Button
            },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(filterIcon(filter), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                text = stateDesc,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusMenuItem(
    option: DownloadFilter,
    selectedOption: DownloadFilter,
    count: Int,
    onSelect: () -> Unit
) {
    val isSelected = option == selectedOption
    DropdownMenuItem(
        text = {
            Text(
                text = "${stringResource(filterLabelRes(option))} ($count)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        onClick = onSelect,
        leadingIcon = {
            Icon(
                filterIcon(option),
                null,
                Modifier.size(20.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = if (isSelected) {
            { Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
        } else null,
        modifier = Modifier
            .testTag("download_filter_${option.key}")
            .semantics { this.selected = isSelected }
    )
}

@Composable
internal fun DownloadsStatusMenu(
    currentFilter: String,
    onFilterSelected: (String) -> Unit,
    downloads: List<DownloadEntity>,
    batches: List<BatchSummary>
) {
    var expanded by remember { mutableStateOf(false) }
    val activeFilter = remember(currentFilter) { DownloadFilter.fromKey(currentFilter) }
    val activeLabel = stringResource(filterLabelRes(activeFilter))
    val activeCount = remember(downloads, batches, activeFilter) {
        DownloadsPresentation.countMatching(downloads, batches, activeFilter)
    }

    Box {
        StatusFilterTrigger(
            filter = activeFilter,
            label = activeLabel,
            count = activeCount,
            onClick = { expanded = !expanded }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val optionCounts = remember(downloads, batches, activeFilter) {
                DownloadFilter.entries.associateWith {
                    if (it == activeFilter) activeCount else DownloadsPresentation.countMatching(downloads, batches, it)
                }
            }
            DownloadFilter.entries.forEach { option ->
                val count = optionCounts[option] ?: 0
                StatusMenuItem(
                    option = option,
                    selectedOption = activeFilter,
                    count = count,
                    onSelect = {
                        onFilterSelected(option.key)
                        expanded = false
                    }
                )
            }
        }
    }
}
