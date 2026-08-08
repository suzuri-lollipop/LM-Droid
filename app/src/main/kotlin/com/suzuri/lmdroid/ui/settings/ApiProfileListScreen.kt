package com.suzuri.lmdroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import kotlinx.coroutines.launch

/** API設定 → the list of saved provider profiles (LLM endpoints and Brave Search alike); tap one to edit it, "+" to register another. */
@Composable
fun ApiProfileListScreen(
    viewModel: ApiProfileListViewModel,
    onNavigateToProfile: (id: Long, providerType: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    if (showAddDialog) {
        AddProfileDialog(
            onConfirm = { name, providerType ->
                showAddDialog = false
                scope.launch {
                    val id = viewModel.createProfile(name, providerType)
                    onNavigateToProfile(id, providerType)
                }
            },
            onDismiss = { showAddDialog = false },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (uiState.profiles.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.api_profile_empty_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(uiState.profiles, key = { it.id }) { profile ->
                    ListItem(
                        headlineContent = { Text(profile.name) },
                        supportingContent = { Text(profile.baseUrl) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = profile.enabled,
                                    onCheckedChange = { enabled -> viewModel.onToggleEnabled(profile.id, enabled) },
                                )
                                IconButton(onClick = { viewModel.onDeleteProfile(profile.id) }) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.api_profile_delete),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.clickable { onNavigateToProfile(profile.id, profile.providerType) },
                    )
                    HorizontalDivider()
                }
            }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAddDialog = true }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = stringResource(R.string.api_profile_add),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProfileDialog(onConfirm: (name: String, providerType: String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val providerOptions = listOf(
        ApiProfileEntity.PROVIDER_OPENAI_COMPATIBLE to stringResource(R.string.settings_openai_compatible_title),
        ApiProfileEntity.PROVIDER_BRAVE_SEARCH to stringResource(R.string.settings_brave_search_title),
        ApiProfileEntity.PROVIDER_VOICEVOX_COMPATIBLE to stringResource(R.string.settings_voicevox_title),
        ApiProfileEntity.PROVIDER_YOUTUBE_DATA_API to stringResource(R.string.settings_youtube_data_api_title),
        ApiProfileEntity.PROVIDER_DASHSCOPE to stringResource(R.string.settings_dashscope_title),
        ApiProfileEntity.PROVIDER_STABLE_DIFFUSION to stringResource(R.string.settings_stable_diffusion_title),
        ApiProfileEntity.PROVIDER_COMFYUI to stringResource(R.string.settings_comfyui_title),
        ApiProfileEntity.PROVIDER_LOCAL to stringResource(R.string.settings_local_generation_title),
    )
    var selectedProviderType by rememberSaveable { mutableStateOf(ApiProfileEntity.PROVIDER_OPENAI_COMPATIBLE) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.api_profile_add)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.api_profile_name_label)) },
                    placeholder = { Text(stringResource(R.string.api_profile_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    OutlinedTextField(
                        value = providerOptions.first { it.first == selectedProviderType }.second,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.api_profile_provider_label)) },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        providerOptions.forEach { (providerType, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedProviderType = providerType
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, selectedProviderType) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.api_profile_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_edit_cancel))
            }
        },
    )
}
