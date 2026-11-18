package com.khereba1.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.khereba1.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

fun isPdfFile(name: String?): Boolean =
    name?.substringAfterLast('.', "")?.lowercase() == "pdf"

fun isTextOrCodeFile(name: String?): Boolean {
    val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return false
    return ext in setOf(
        "txt", "py", "kt", "kts", "java", "js", "ts", "jsx", "tsx", "html", "htm",
        "css", "json", "xml", "md", "sh", "bash", "log", "csv", "yml", "yaml",
        "ini", "conf", "cfg", "c", "cpp", "h", "go", "rs", "sql", "gradle", "properties"
    )
}

@Composable
fun InAppImageViewerDialog(
    file: File,
    onSaveToGallery: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val bmp by produceState<ImageBitmap?>(initialValue = null, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            }.getOrNull()
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 4f)
                        scale = nextScale
                        offset = if (nextScale > 1f) offset + pan else Offset.Zero
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp!!,
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onSaveToGallery != null) {
                    IconButton(
                        onClick = onSaveToGallery,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(Icons.Rounded.SaveAlt, contentDescription = null, tint = Color.White)
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun InAppVideoPlayerDialog(
    file: File,
    onOpenExternal: (() -> Unit)? = null,
    onSaveToGallery: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    InAppVideoPlayerCore(
        title = file.name.substringAfter('_', file.name),
        path = file.absolutePath,
        isUrl = false,
        onOpenExternal = onOpenExternal,
        onSaveToGallery = onSaveToGallery,
        onDismiss = onDismiss
    )
}

@Composable
fun InAppStreamVideoPlayerDialog(
    streamUrl: String,
    title: String = "Video",
    onOpenExternal: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    InAppVideoPlayerCore(
        title = title,
        path = streamUrl,
        isUrl = true,
        onOpenExternal = onOpenExternal,
        onSaveToGallery = null,
        onDismiss = onDismiss
    )
}

@Composable
private fun InAppVideoPlayerCore(
    title: String,
    path: String,
    isUrl: Boolean,
    onOpenExternal: (() -> Unit)?,
    onSaveToGallery: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var durationMs by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableIntStateOf(0) }
    var showControls by remember { mutableStateOf(true) }

    LaunchedEffect(isPlaying, isPrepared) {
        while (isPrepared) {
            val vv = videoViewRef
            if (vv != null) {
                runCatching {
                    positionMs = vv.currentPosition
                    if (durationMs <= 0 && vv.duration > 0) durationMs = vv.duration
                    isPlaying = vv.isPlaying
                }
            }
            delay(350)
        }
    }

    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(3500)
            showControls = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showControls = !showControls },
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        videoViewRef = this
                        if (isUrl) setVideoURI(Uri.parse(path)) else setVideoPath(path)
                        setOnPreparedListener { mp ->
                            mediaPlayerRef = mp
                            mp.isLooping = false
                            durationMs = mp.duration.coerceAtLeast(0)
                            isPrepared = true
                            start()
                            isPlaying = true
                        }
                        setOnCompletionListener {
                            isPlaying = false
                            showControls = true
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (!isPrepared) {
                CircularProgressIndicator(color = Color.White)
            }

            // Minimal Telegram-style top & bottom overlay controls
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Top bar
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (onSaveToGallery != null) {
                            IconButton(onClick = onSaveToGallery) {
                                Icon(Icons.Rounded.SaveAlt, contentDescription = null, tint = Color.White)
                            }
                        }
                        if (onOpenExternal != null) {
                            IconButton(onClick = onOpenExternal) {
                                Icon(Icons.Rounded.OpenInNew, contentDescription = null, tint = Color.White)
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White)
                        }
                    }

                    // Center transport controls
                    if (isPrepared) {
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    val vv = videoViewRef ?: return@IconButton
                                    val target = (vv.currentPosition - 10_000).coerceAtLeast(0)
                                    vv.seekTo(target)
                                    positionMs = target
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                            ) {
                                Icon(Icons.Rounded.Replay10, contentDescription = null, tint = Color.White)
                            }
                            IconButton(
                                onClick = {
                                    val vv = videoViewRef ?: return@IconButton
                                    if (vv.isPlaying) {
                                        vv.pause()
                                        isPlaying = false
                                    } else {
                                        vv.start()
                                        isPlaying = true
                                    }
                                },
                                modifier = Modifier
                                    .size(62.dp)
                                    .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    val vv = videoViewRef ?: return@IconButton
                                    val target = (vv.currentPosition + 10_000).coerceAtMost(durationMs.coerceAtLeast(0))
                                    vv.seekTo(target)
                                    positionMs = target
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                            ) {
                                Icon(Icons.Rounded.Forward10, contentDescription = null, tint = Color.White)
                            }
                        }
                    }

                    // Bottom seek bar + speed + mute
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        val safeDur = durationMs.coerceAtLeast(1)
                        Slider(
                            value = (positionMs.toFloat() / safeDur.toFloat()).coerceIn(0f, 1f),
                            onValueChange = { frac ->
                                val target = (frac * safeDur).toInt()
                                videoViewRef?.seekTo(target)
                                positionMs = target
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF19C6B5),
                                activeTrackColor = Color(0xFF19C6B5),
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${formatTimeMs(positionMs)} / ${formatTimeMs(durationMs)}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    TextButton(onClick = {
                                        val next = when {
                                            speed < 1.25f -> 1.5f
                                            speed < 1.75f -> 2.0f
                                            else -> 1.0f
                                        }
                                        speed = next
                                        runCatching {
                                            mediaPlayerRef?.let { mp ->
                                                mp.playbackParams = mp.playbackParams.setSpeed(next)
                                            }
                                        }
                                    }) {
                                        Text("${speed}x", color = Color.White, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                IconButton(onClick = {
                                    isMuted = !isMuted
                                    val vol = if (isMuted) 0f else 1f
                                    runCatching { mediaPlayerRef?.setVolume(vol, vol) }
                                }) {
                                    Icon(
                                        if (isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                                        contentDescription = null,
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Lightweight native PDF Viewer using Android PdfRenderer (zero extra dependencies). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppPdfViewerDialog(
    file: File,
    onOpenExternal: () -> Unit,
    onSaveToGallery: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var pageCount by remember { mutableIntStateOf(0) }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }

    DisposableEffect(file.absolutePath) {
        runCatching {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val r = PdfRenderer(fd)
            pfd = fd
            renderer = r
            pageCount = r.pageCount
        }
        onDispose {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            renderer = null
            pfd = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                file.name.substringAfter('_', file.name),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall
                            )
                            if (pageCount > 0) {
                                Text(
                                    stringResource(R.string.pdf_pages_count, pageCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        if (onSaveToGallery != null) {
                            IconButton(onClick = onSaveToGallery) {
                                Icon(Icons.Rounded.SaveAlt, contentDescription = null)
                            }
                        }
                        IconButton(onClick = onOpenExternal) {
                            Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = null)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        ) { padding ->
            if (pageCount == 0 || renderer == null) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.cannot_open_file))
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(MaterialTheme.colorScheme.background),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(pageCount) { index ->
                        val bmp by produceState<ImageBitmap?>(initialValue = null, index, renderer) {
                            value = withContext(Dispatchers.IO) {
                                runCatching {
                                    val r = renderer ?: return@runCatching null
                                    synchronized(r) {
                                        r.openPage(index).use { page ->
                                            val scale = 2
                                            val w = (page.width * scale).coerceIn(400, 1800)
                                            val h = (page.height * scale).coerceIn(400, 2600)
                                            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                            bitmap.eraseColor(AndroidColor.WHITE)
                                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                            bitmap.asImageBitmap()
                                        }
                                    }
                                }.getOrNull()
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.White,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp!!,
                                    contentDescription = "Page ${index + 1}",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Clean monospace Text & Source Code viewer (.txt, .py, .kt, .json, .xml, .md, .log, etc.). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppTextViewerDialog(
    file: File,
    onOpenExternal: () -> Unit,
    onSaveToGallery: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val content by produceState<String?>(initialValue = null, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val maxChars = 250_000
                val text = file.bufferedReader().use { it.readText() }
                if (text.length > maxChars) text.take(maxChars) + "\n\n... (truncated)" else text
            }.getOrNull()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            file.name.substringAfter('_', file.name),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall
                        )
                    },
                    actions = {
                        IconButton(onClick = {
                            val txt = content ?: return@IconButton
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("text", txt))
                            Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                        }
                        if (onSaveToGallery != null) {
                            IconButton(onClick = onSaveToGallery) {
                                Icon(Icons.Rounded.SaveAlt, contentDescription = null)
                            }
                        }
                        IconButton(onClick = onOpenExternal) {
                            Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = null)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        ) { padding ->
            if (content == null) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp)
                ) {
                    Text(
                        text = content!!,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
    }
}

private fun formatTimeMs(ms: Int): String {
    val totalSecs = (ms / 1000).coerceAtLeast(0)
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
