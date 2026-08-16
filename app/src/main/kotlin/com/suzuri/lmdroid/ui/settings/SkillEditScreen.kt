package com.suzuri.lmdroid.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R

/**
 * Settings → スキル → skill list → this skill's edit form: name, description (short — this is
 * the only part always shown to the model, so it can decide on its own whether a request matches,
 * see ConversationRepository's skills catalog), and content (the full instructions, only loaded
 * once the skill is actually invoked).
 */
@Composable
fun SkillEditScreen(
    viewModel: SkillEditViewModel,
    skillId: Long,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(skillId) {
        viewModel.loadSkill(skillId)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
    ) {
        OutlinedTextField(
            value = uiState.name,
            onValueChange = viewModel::onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.skill_name_label)) },
            singleLine = true,
        )

        OutlinedTextField(
            value = uiState.description,
            onValueChange = viewModel::onDescriptionChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            label = { Text(stringResource(R.string.skill_description_label)) },
            placeholder = { Text(stringResource(R.string.skill_description_placeholder)) },
            supportingText = { Text(stringResource(R.string.skill_description_hint)) },
            minLines = 2,
        )

        OutlinedTextField(
            value = uiState.content,
            onValueChange = viewModel::onContentChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            label = { Text(stringResource(R.string.skill_content_label)) },
            placeholder = { Text(stringResource(R.string.skill_content_placeholder)) },
            minLines = 6,
        )

        Row(modifier = Modifier.padding(top = 20.dp)) {
            Button(onClick = viewModel::onSave) {
                Text(stringResource(R.string.settings_save))
            }
        }

        if (uiState.saved) {
            Text(
                text = stringResource(R.string.settings_saved),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
