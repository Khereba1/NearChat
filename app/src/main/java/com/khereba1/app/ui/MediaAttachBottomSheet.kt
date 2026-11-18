package com.khereba1.app.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.khereba1.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class RecentMediaItem(
    val uri: Uri,
    val isVideo: Boolean,
    val dateAdded: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaAttachBottomSheet(
    onSendUris: (List<Uri>) -> Unit,
    onOpenSystemGallery: () -> Unit,
    onOpenCameraPhoto: () -> Unit,
    onOpenCameraVideo: () -> Unit,
    onOpenFileManager: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var recentItems by remember { mutableStateOf<List<RecentMediaItem>>(emptyList()) }
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var hasMediaPerm by remember { mutableStateOf(checkMediaPermission(context)) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasMediaPerm = results.values.any { it } || checkMediaPermission(context)
    }

    LaunchedEffect(hasMediaPerm) {
        if (hasMediaPerm) {
            recentItems = withContext(Dispatchers.IO) { queryRecentMedia(context, 36) }
        } else {
            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            permLauncher.launch(perms)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // Top bar: Recent + System gallery
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.recent_media_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = {
                    onDismiss()
                    onOpenSystemGallery()
                }) {
                    Text(
                        text = stringResource(R.string.system_gallery_btn),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Recent 3-column grid
            if (recentItems.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                ) {
                    items(recentItems, key = { it.uri.toString() }) { item ->
                        val isSelected = selectedUris.contains(item.uri)
                        RecentMediaTile(
                            item = item,
                            isSelected = isSelected,
                            onToggle = {
                                selectedUris = if (isSelected) selectedUris - item.uri else selectedUris + item.uri
                            }
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(onClick = {
                        onDismiss()
                        onOpenSystemGallery()
                    }) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.system_gallery_btn))
                    }
                }
            }

            if (selectedUris.isNotEmpty()) {
                Button(
                    onClick = {
                        val toSend = selectedUris
                        onDismiss()
                        onSendUris(toSend)
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.send_selected_count, selectedUris.size))
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // Bottom quick action bar (Photo, Video, Gallery, File)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AttachBarAction(
                    icon = Icons.Rounded.CameraAlt,
                    label = stringResource(R.string.attach_photo),
                    onClick = { onDismiss(); onOpenCameraPhoto() }
                )
                AttachBarAction(
                    icon = Icons.Rounded.Videocam,
                    label = stringResource(R.string.attach_video),
                    onClick = { onDismiss(); onOpenCameraVideo() }
                )
                AttachBarAction(
                    icon = Icons.Rounded.PhotoLibrary,
                    label = stringResource(R.string.attach_gallery),
                    onClick = { onDismiss(); onOpenSystemGallery() }
                )
                AttachBarAction(
                    icon = Icons.AutoMirrored.Rounded.InsertDriveFile,
                    label = stringResource(R.string.attach_file),
                    onClick = { onDismiss(); onOpenFileManager() }
                )
            }
        }
    }
}

@Composable
private fun RecentMediaTile(
    item: RecentMediaItem,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    val thumb by produceState<ImageBitmap?>(initialValue = null, item.uri) {
        value = withContext(Dispatchers.IO) {
            loadThumbnail(context, item.uri)?.asImageBitmap()
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onToggle)
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .padding(3.dp)
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f))
                .border(1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun AttachBarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun checkMediaPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

private fun queryRecentMedia(context: Context, limit: Int): List<RecentMediaItem> {
    val result = mutableListOf<RecentMediaItem>()
    val cr = context.contentResolver
    val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
    runCatching {
        cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            var count = 0
            while (c.moveToNext() && count < limit) {
                val id = c.getLong(0)
                val date = c.getLong(1)
                result.add(RecentMediaItem(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), false, date))
                count++
            }
        }
    }
    runCatching {
        cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            var count = 0
            while (c.moveToNext() && count < limit / 2) {
                val id = c.getLong(0)
                val date = c.getLong(1)
                result.add(RecentMediaItem(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id), true, date))
                count++
            }
        }
    }
    return result.sortedByDescending { it.dateAdded }.take(limit)
}

private fun loadThumbnail(context: Context, uri: Uri): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(240, 240), null)
        } else {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (_: Throwable) {
        null
    }
}
