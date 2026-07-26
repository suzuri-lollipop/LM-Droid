package com.suzuri.lmdroid.ui.chat.components

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R

/**
 * A small pill representing a recorded voice message — tap to play/stop it back. Pass [onRemove]
 * to show a small "x" (a not-yet-sent voice message); omit it for a read-only chip on an
 * already-sent message.
 */
@Composable
fun AudioAttachmentChip(
    filePath: String,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
) {
    var isPlaying by remember(filePath) { mutableStateOf(false) }
    val mediaPlayer = remember(filePath) { MediaPlayer() }

    DisposableEffect(filePath) {
        onDispose { mediaPlayer.release() }
    }

    Surface(
        onClick = {
            if (isPlaying) {
                mediaPlayer.stop()
                isPlaying = false
            } else {
                runCatching {
                    mediaPlayer.reset()
                    mediaPlayer.setDataSource(filePath)
                    mediaPlayer.setOnCompletionListener { isPlaying = false }
                    mediaPlayer.prepare()
                    mediaPlayer.start()
                    isPlaying = true
                }
            }
        },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = if (onRemove != null) 4.dp else 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.chat_voice_message_label),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.chat_remove_attachment),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
