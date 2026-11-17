package com.carjournal.rebuild.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import java.io.File

/** existingId==null значит фото ещё не записано в Room; удаление не касается исходника из галереи. */
data class PhotoChoice(val existingId: Long? = null, val uri: Uri)

@Composable
fun PhotoPicker(
    photos: List<PhotoChoice>, maxItems: Int,
    onAdd: (List<PhotoChoice>) -> Unit,
    onRemove: (PhotoChoice) -> Unit
) {
    val context = LocalContext.current
    var pendingCameraPhoto by remember { mutableStateOf<Uri?>(null) }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        onAdd(uris.take((maxItems - photos.size).coerceAtLeast(0)).map { PhotoChoice(uri = it) })
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCameraPhoto
        if (success && uri != null && photos.size < maxItems) {
            onAdd(listOf(PhotoChoice(uri = uri)))
        }
        if (!success && uri != null) {
            // Камера сохраняет временный файл. При отказе можно удалить его позже из cacheDir.
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        pendingCameraPhoto = null
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (photos.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(photos, key = { "${it.existingId}:${it.uri}" }) { photo ->
                    Box(Modifier.size(106.dp)) {
                        AsyncImage(
                            model = photo.uri, contentDescription = "Фото",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                        )
                        SmallFloatingActionButton(
                            onClick = { onRemove(photo) },
                            modifier = Modifier.align(Alignment.TopEnd).size(30.dp),
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            Icon(Icons.Outlined.Close, "Убрать фото", Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        Text("${photos.size} из $maxItems", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                enabled = photos.size < maxItems,
                onClick = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Галерея")
            }
            OutlinedButton(
                enabled = photos.size < maxItems,
                onClick = {
                    try {
                        val folder = File(context.cacheDir, "camera").apply { mkdirs() }
                        val output = File.createTempFile("capture_", ".jpg", folder)
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", output
                        )
                        pendingCameraPhoto = uri
                        camera.launch(uri)
                    } catch (_: Exception) {
                        // На устройствах без камеры остаётся возможность выбрать фото из галереи.
                    }
                }, modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.CameraAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Камера")
            }
        }
    }
}
