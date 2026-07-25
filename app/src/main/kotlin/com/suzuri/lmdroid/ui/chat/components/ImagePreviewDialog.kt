package com.suzuri.lmdroid.ui.chat.components

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.attachment.attachmentContentUri
import com.suzuri.lmdroid.data.attachment.decodeSampledBitmap
import com.suzuri.lmdroid.data.attachment.saveAttachmentToGallery
import kotlinx.coroutines.launch

/**
 * A full-screen popup opened by tapping any attachment thumbnail (staged or already sent), with
 * save/share/copy actions. "Save" is here ahead of an eventual image-generation feature — a
 * generated image would be attached the same way, and would want the same escape hatch.
 */
@Composable
fun ImagePreviewDialog(filePath: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember(filePath) { mutableStateOf<ImageBitmap?>(null) }

    val savedMessage = stringResource(R.string.chat_attachment_saved)
    val saveFailedMessage = stringResource(R.string.chat_attachment_save_failed)
    val permissionDeniedMessage = stringResource(R.string.chat_attachment_permission_denied)
    val copiedMessage = stringResource(R.string.chat_copied)

    fun performSave() {
        scope.launch {
            val result = saveAttachmentToGallery(context, filePath)
            Toast.makeText(context, if (result.isSuccess) savedMessage else saveFailedMessage, Toast.LENGTH_SHORT).show()
        }
    }

    // Only needed pre-API 29: MediaStore inserts on API 29+ don't require this permission at all.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            performSave()
        } else {
            Toast.makeText(context, permissionDeniedMessage, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(filePath) {
        bitmap = decodeSampledBitmap(filePath, PREVIEW_MAX_DIMENSION_PX)?.asImageBitmap()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // Tapping anywhere (including the image itself) dismisses, matching how most
                // photo viewers behave — the explicit close button is there too either way.
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
        ) {
            val loadedBitmap = bitmap
            if (loadedBitmap != null) {
                Image(
                    bitmap = loadedBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.chat_edit_cancel),
                    tint = Color.White,
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(
                    onClick = {
                        val needsRuntimePermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                            PackageManager.PERMISSION_GRANTED
                        if (needsRuntimePermission) {
                            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            performSave()
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = stringResource(R.string.chat_attachment_save),
                        tint = Color.White,
                    )
                }
                IconButton(
                    onClick = {
                        val uri = attachmentContentUri(context, filePath)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = stringResource(R.string.chat_attachment_share),
                        tint = Color.White,
                    )
                }
                IconButton(
                    onClick = {
                        val uri = attachmentContentUri(context, filePath)
                        val clipboardManager =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboardManager.setPrimaryClip(ClipData.newUri(context.contentResolver, "attachment", uri))
                        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.chat_copy),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

private const val PREVIEW_MAX_DIMENSION_PX = 2048
