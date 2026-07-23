package com.suzuri.lmdroid.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.data.db.MessageRole
import com.suzuri.lmdroid.ui.chat.MessageUiModel

@Composable
fun MessageBubble(message: MessageUiModel, modifier: Modifier = Modifier) {
    val isUser = message.role == MessageRole.USER
    val bubbleColor = when {
        message.isError -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when {
        message.isError -> MaterialTheme.colorScheme.onErrorContainer
        isUser -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(bubbleColor, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = message.content.ifBlank { "…" },
                color = textColor,
            )
        }
    }
}
