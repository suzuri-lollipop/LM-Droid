package com.suzuri.lmdroid.ui.chat.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R

@Composable
fun ChatInputBar(
    input: String,
    isStreaming: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
            enabled = !isStreaming,
        )
        if (isStreaming) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
        } else {
            IconButton(onClick = onSend, enabled = input.isNotBlank()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.chat_send),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
