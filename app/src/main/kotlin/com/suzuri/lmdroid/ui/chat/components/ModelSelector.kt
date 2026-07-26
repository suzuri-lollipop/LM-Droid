package com.suzuri.lmdroid.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.db.ModelOptionRow
import com.suzuri.lmdroid.data.settings.SelectedModel

/**
 * Lets the user switch which enabled profile's model chat uses, without leaving this screen.
 * Hidden by the caller when [options] is empty — with no enabled profile there's nothing to
 * switch between. The button shows just the current profile's name (Claude-style, rather than a
 * "profile · model" pair on every row) since that's typically the more meaningful label to
 * glance at (e.g. "ローカルサーバー" vs a long/cryptic model id); the menu itself is grouped by
 * profile — with a section header per profile and a checkmark on the active model, each row
 * still showing its own model id — instead of repeating the profile name on every single line.
 */
@Composable
fun ModelSelectorButton(
    options: List<ModelOptionRow>,
    selected: SelectedModel?,
    onSelect: (ModelOptionRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.find { it.profileId == selected?.profileId && it.modelId == selected.model }
    val label = selectedOption?.profileName ?: stringResource(R.string.chat_model_selector_placeholder)
    // Only worth labeling groups when there's more than one profile to tell apart — with a single
    // enabled profile, a lone section header would just repeat what the button already says.
    val groupedOptions = options.groupBy { it.profileId to it.profileName }
    val showGroupHeaders = groupedOptions.size > 1

    Box(modifier = modifier) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.clickable { expanded = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            groupedOptions.entries.forEachIndexed { index, (profile, models) ->
                if (index > 0) HorizontalDivider()
                if (showGroupHeaders) {
                    Text(
                        text = profile.second,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                models.forEach { option ->
                    val isSelected = option.profileId == selected?.profileId && option.modelId == selected.model
                    DropdownMenuItem(
                        text = { Text(option.modelId) },
                        trailingIcon = if (isSelected) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else {
                            null
                        },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}
