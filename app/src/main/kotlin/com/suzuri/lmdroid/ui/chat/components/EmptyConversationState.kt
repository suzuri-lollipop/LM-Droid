package com.suzuri.lmdroid.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R

private val SUGGESTION_KEYS = listOf(
    R.string.chat_suggestion_brainstorm,
    R.string.chat_suggestion_summarize,
    R.string.chat_suggestion_debug,
    R.string.chat_suggestion_explain,
)

/** The icon + greeting shown above the input bar for a brand-new, empty conversation. */
@Composable
fun EmptyConversationGreeting(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }

        Text(
            text = stringResource(R.string.chat_empty_greeting),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )
    }
}

/** Tappable example prompts shown below the input bar; tapping one fills the input with its text. */
@Composable
fun EmptyConversationSuggestions(onSuggestionClick: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        SUGGESTION_KEYS.forEach { key ->
            val text = stringResource(key)
            SuggestionChip(
                onClick = { onSuggestionClick(text) },
                label = { Text(text) },
                shape = SuggestionChipDefaults.shape,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
        }
    }
}
