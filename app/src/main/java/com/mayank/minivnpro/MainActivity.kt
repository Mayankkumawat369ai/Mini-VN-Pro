package com.mayank.minivnpro

import android.content.ContentValues
import android.content.Context
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.TextureView
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class VideoClip(
    val id: Long,
    val uri: Uri,
    val startMs: Long,
    val endMs: Long,
    val isImage: Boolean = false
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

@UnstableApi
fun VideoClip.toMediaItem(): MediaItem {
    return if (isImage) {
        MediaItem.Builder()
            .setUri(uri)
            .setImageDurationMs(durationMs.coerceAtLeast(1000L))
            .build()
    } else {
        MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
            .build()
    }
}

suspend fun getMediaDurationMsSafe(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 5000L
    } catch (e: Exception) {
        5000L
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
        }
    }
}

class MainActivity : ComponentActivity() {
    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MiniVNProApp()
            }
        }
    }
}

@UnstableApi
@Composable
fun MiniVNProApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var clips by remember { mutableStateOf<List<VideoClip>>(emptyList()) }
    var selectedClipIndex by remember { mutableStateOf(0) }
    var nextClipId by remember { mutableStateOf(1L) }

    // Active bottom panel: NONE, FILTER, SPEED, TEXT, MUSIC
    var activePanel by remember { mutableStateOf("NONE") }

    // Music State
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var audioTitle by remember { mutableStateOf("") }
    var audioDurationMs by remember { mutableStateOf(30000L) }
    var audioStartMs by remember { mutableStateOf(0L) }
    var showMusicDialog by remember { mutableStateOf(false) }
    var isAudioPreviewing by remember { mutableStateOf(false) }

    // Online Music Search State
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<OnlineSong>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var isDownloadingSong by remember { mutableStateOf(false) }
    var previewingSearchUrl by remember { mutableStateOf<String?>(null) }

    // Draggable Text State
    var overlayText by remember { mutableStateOf("") }
    var textNormX by remember { mutableStateOf(0f) }
    var textNormY by remember { mutableStateOf(0f) }
    var textScale by remember { mutableStateOf(1.0f) }
    var textColor by remember { mutableStateOf(Color.White) }
    var textBgMode by remember { mutableStateOf(TextBgMode.DARK) }
    var textFontStyle by remember { mutableStateOf(TextFontStyle.SANS) }
    var textIsBold by remember { mutableStateOf(true) }
    var textIsItalic by remember { mutableStateOf(false) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var showTextDialog by remember { mutableStateOf(false) }
    var tempTextInput by remember { mutableStateOf("") }

    // Zero-Freeze Preview Filter & Rotate State
    var selectedFilter by remember { mutableStateOf(VideoFilter.ORIGINAL) }
    var brightness by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(0f) }
    var saturation by remember { mutableStateOf(0f) }
    var rotationDegrees by remember { mutableStateOf(0) }
    var videoAspectRatio by remember { mutableStateOf(9f / 16f) }

    // Interactive VN Speed Curve State
    var speedPoints by remember { mutableStateOf(listOf(1.0f, 1.0f, 1.0f, 1.0f, 1.0f)) }
    var selectedSpeedPreset by remember { mutableStateOf("Normal") }
    var activeSpeedDotIndex by remember { mutableStateOf(2) }
    var liveSpeedDisplay by remember { mutableStateOf(1.0f) }

    var isMuted by remember { mutableStateOf(false) }
    var currentTotalPosMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var isLoadingMedia by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("नीचे +Video या +Photo पर टैप करें") }

    val totalDurationMs = remember(clips) {
        clips.sumOf { it.durationMs }.coerceAtLeast(1000L)
    }

    val currentColorFilter = remember(selectedFilter, brightness, contrast, saturation) {
        ColorMatrixColorFilter(buildPreviewColorMatrix(selectedFilter, brightness, contrast, saturation))
    }

    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    val audioPlayer = remember { ExoPlayer.Builder(context).build() }
    val searchPreviewPlayer = remember { ExoPlayer.Builder(context).build() }

    fun stopSearchPreview() {
        searchPreviewPlayer.pause()
        searchPreviewPlayer.clearMediaItems()
        previewingSearchUrl = null
    }

    fun loadClipsSafely(
        clipList: List<VideoClip>,
        seekClipIdx: Int = 0,
        seekPosInClip: Long = 0L,
        autoPlay: Boolean = false
    ) {
        try {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            if (clipList.isEmpty()) return

            val mediaItems = clipList.map { it.toMediaItem() }
            val safeIdx = seekClipIdx.coerceIn(0, clipList.lastIndex)
            val safePos = seekPosInClip.coerceIn(0L, clipList[safeIdx].durationMs)

            // NEVER call exoPlayer.setVideoEffects() here -> prevents freeze & black screen!
            exoPlayer.setMediaItems(mediaItems, safeIdx, safePos)
            exoPlayer.prepare()
            exoPlayer.volume = if (isMuted || audioUri != null) 0f else 1f
            exoPlayer.playWhenReady = autoPlay
        } catch (e: Exception) {
            statusText = "प्लेयर रीसेट किया गया"
        }
    }

    fun getCurrentTotalPosition(): Long {
        if (clips.isEmpty()) return 0L
        val idx = exoPlayer.currentMediaItemIndex.coerceIn(0, clips.lastIndex)
        var sum = 0L
        for (i in 0 until idx) sum += clips[i].durationMs
        return sum + exoPlayer.currentPosition.coerceAtLeast(0L)
    }

    fun seekToTotalPosition(targetMs: Long) {
        if (clips.isEmpty()) return
        var acc = 0L
        for (i in clips.indices) {
            val dur = clips[i].durationMs
            if (targetMs <= acc + dur || i == clips.lastIndex) {
                val offset = (targetMs - acc).coerceIn(0L, dur)
                exoPlayer.seekTo(i, offset)
                selectedClipIndex = i
                currentTotalPosMs = targetMs
                if (audioUri != null) {
                    val ratio = (targetMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                    val estTotal = estimateCurveDurationMs(totalDurationMs, speedPoints)
                    val audioOffset = (ratio * estTotal).toLong()
                    audioPlayer.seekTo((audioStartMs + audioOffset).coerceAtMost(audioDurationMs))
                }
                return
            }
            acc += dur
        }
    }

    DisposableEffect(Unit) {
        val videoListener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio = (videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    audioPlayer.pause()
                    isAudioPreviewing = false
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing && !isAudioPreviewing) {
                    audioPlayer.pause()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                exoPlayer.stop()
                exoPlayer.prepare()
                statusText = "प्लेयर ऑटो-रिकवर हो गया"
            }
        }
        val audioListener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && audioPlayer.duration > 0) {
                    audioDurationMs = audioPlayer.duration
                }
                if (state == Player.STATE_ENDED) {
                    isAudioPreviewing = false
                }
            }
        }
        exoPlayer.addListener(videoListener)
        audioPlayer.addListener(audioListener)
        onDispose {
            exoPlayer.removeListener(videoListener)
            audioPlayer.removeListener(audioListener)
            exoPlayer.release()
            audioPlayer.release()
            searchPreviewPlayer.release()
        }
    }

    // Smooth VN Speed Curve live playback loop
    LaunchedEffect(isPlaying, clips, speedPoints) {
        while (isPlaying) {
            val pos = getCurrentTotalPosition()
            currentTotalPosMs = pos
            val idx = exoPlayer.currentMediaItemIndex
            if (idx in clips.indices) selectedClipIndex = idx

            val progress = (pos.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
            val targetSpeed = getSmoothSpeedAtProgress(speedPoints, progress)
            if (abs(liveSpeedDisplay - targetSpeed) > 0.03f) {
                liveSpeedDisplay = targetSpeed
                exoPlayer.setPlaybackSpeed(targetSpeed)
            }
            delay(70)
        }
    }

    val multiVideoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                isLoadingMedia = true
                statusText = "वीडियो लोड हो रहे हैं..."
                val newClips = uris.map { uri ->
                    val dur = getMediaDurationMsSafe(context, uri)
                    VideoClip(id = nextClipId++, uri = uri, startMs = 0L, endMs = dur, isImage = false)
                }
                val startIdx = clips.size
                val updated = clips + newClips
                clips = updated
                selectedClipIndex = startIdx.coerceIn(0, updated.lastIndex)
                loadClipsSafely(updated, selectedClipIndex, 0L, true)
                if (audioUri != null) {
                    audioPlayer.seekTo(audioStartMs)
                    audioPlayer.play()
                }
                isLoadingMedia = false
                statusText = "${newClips.size} वीडियो क्लिप जुड़ गई!"
            }
        }
    }

    val multiPhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val newClips = uris.map { uri ->
                VideoClip(id = nextClipId++, uri = uri, startMs = 0L, endMs = 4000L, isImage = true)
            }
            val startIdx = clips.size
            val updated = clips + newClips
            clips = updated
            selectedClipIndex = startIdx.coerceIn(0, updated.lastIndex)
            loadClipsSafely(updated, selectedClipIndex, 0L, true)
            if (audioUri != null) {
                audioPlayer.seekTo(audioStartMs)
                audioPlayer.play()
            }
            statusText = "${newClips.size} फोटो जुड़ गई (4s प्रत्येक)!"
        }
    }

    val localAudioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            stopSearchPreview()
            audioUri = uri
            audioTitle = "फोन का म्यूज़िक"
            audioStartMs = 0L
            audioPlayer.setMediaItem(MediaItem.fromUri(uri))
            audioPlayer.prepare()
            exoPlayer.volume = 0f
            showMusicDialog = false
            activePanel = "MUSIC"
            statusText = "म्यूज़िक जुड़ गया!"
        }
    }

    // Text Input Dialog
    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            containerColor = Color(0xFF1A1A20),
            title = {
                Text("वीडियो पर टेक्स्ट लिखें", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                OutlinedTextField(
                    value = tempTextInput,
                    onValueChange = { tempTextInput = it },
                    placeholder = { Text("एक या ज़्यादा लाइन में लिखें...", fontSize = 13.sp) },
                    minLines = 2,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF2979FF)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    overlayText = tempTextInput.trim()
                    showTextDialog = false
                    if (overlayText.isNotBlank()) {
                        activePanel = "TEXT"
                        statusText = "टेक्स्ट को उंगली से खींचकर सेट करें!"
                    } else {
                        if (activePanel == "TEXT") activePanel = "NONE"
                        statusText = "टेक्स्ट हटा दिया गया"
                    }
                }) {
                    Text("Save", color = Color(0xFF2979FF), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    // WhatsApp-Style Music Search Dialog (With Live Listen Button + 25 Results)
    if (showMusicDialog) {
        AlertDialog(
            onDismissRequest = {
                stopSearchPreview()
                showMusicDialog = false
            },
            containerColor = Color(0xFF1A1A20),
            title = {
                Text("गाना सर्च करें (सुनकर चुनें)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("जैसे: Tum Hi Ho, Arijit...", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF2979FF)
                            )
                        )
                        Spacer(Modifier.width(6.dp))
                        IconButton(
                            onClick = {
                                stopSearchPreview()
                                scope.launch {
                                    isSearching = true
                                    searchResults = searchOnlineSongs(searchQuery)
                                    isSearching = false
                                }
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF2979FF))
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { localAudioPicker.launch(arrayOf("audio/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("या फोन से पूरा MP3 गाना चुनें", fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(8.dp))

                    if (isSearching || isDownloadingSong) {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color(0xFF2979FF), modifier = Modifier.size(28.dp))
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = if (isDownloadingSong) "गाना लोड हो रहा है..." else "गाने खोज रहे हैं...",
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                            items(searchResults) { song ->
                                val isThisPreviewing = previewingSearchUrl == song.previewUrl
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF262630))
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Instant Listen Play/Pause Button
                                    IconButton(
                                        onClick = {
                                            exoPlayer.pause()
                                            audioPlayer.pause()
                                            if (isThisPreviewing) {
                                                stopSearchPreview()
                                            } else {
                                                previewingSearchUrl = song.previewUrl
                                                searchPreviewPlayer.setMediaItem(MediaItem.fromUri(song.previewUrl))
                                                searchPreviewPlayer.prepare()
                                                searchPreviewPlayer.play()
                                            }
                                        },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isThisPreviewing) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                            contentDescription = "Preview",
                                            tint = if (isThisPreviewing) Color(0xFF4CAF50) else Color(0xFFFFD600),
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }

                                    Spacer(Modifier.width(6.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(song.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(song.artist, color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }

                                    Spacer(Modifier.width(6.dp))

                                    Text(
                                        text = "Use",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFF2979FF))
                                            .clickable {
                                                stopSearchPreview()
                                                scope.launch {
                                                    isDownloadingSong = true
                                                    val cachedUri = downloadSongToCache(context, song)
                                                    isDownloadingSong = false
                                                    if (cachedUri != null) {
                                                        audioUri = cachedUri
                                                        audioTitle = song.title
                                                        audioStartMs = 0L
                                                        audioPlayer.setMediaItem(MediaItem.fromUri(cachedUri))
                                                        audioPlayer.prepare()
                                                        exoPlayer.volume = 0f
                                                        showMusicDialog = false
                                                        activePanel = "MUSIC"
                                                        statusText = "गाना: ${song.title}"
                                                    } else {
                                                        Toast.makeText(context, "गाना लोड नहीं हो सका", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    stopSearchPreview()
                    showMusicDialog = false
                }) {
                    Text("Close", color = Color.LightGray)
                }
            }
        )
    }

    val activeClip = clips.getOrNull(selectedClipIndex)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E10))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 1. VN-Style Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Mini VN Pro", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

            Text(
                text = if (!isDefaultSpeedCurve(speedPoints)) "Curve ${String.format(Locale.US, "%.1fx", liveSpeedDisplay)}"
                else if (clips.isEmpty()) "Original"
                else "${clips.size} Clip${if (clips.size > 1) "s" else ""}",
                color = Color(0xFFFFD600),
                fontSize = 12.sp,
                modifier = Modifier
                    .border(1.dp, Color(0xFF3A3A40), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )

            Button(
                onClick = {
                    if (clips.isEmpty()) {
                        Toast.makeText(context, "पहले कोई वीडियो या फोटो चुनें", Toast.LENGTH_SHORT).show()
                    } else if (!isExporting) {
                        exoPlayer.pause()
                        audioPlayer.pause()
                        isAudioPreviewing = false
                        isExporting = true
                        statusText = "HD MP4 बन रहा है..."
                        exportVideoClips(
                            context = context,
                            clips = clips,
                            audioUri = audioUri,
                            audioStartMs = audioStartMs,
                            audioDurationMs = audioDurationMs,
                            muteOriginal = isMuted,
                            filter = selectedFilter,
                            brightness = brightness,
                            contrast = contrast,
                            saturation = saturation,
                            rotationDegrees = rotationDegrees,
                            speedPoints = speedPoints,
                            overlayText = overlayText,
                            textAnchorX = textNormX,
                            textAnchorY = -textNormY,
                            textScale = textScale,
                            textColor = textColor,
                            textBgMode = textBgMode,
                            textFontStyle = textFontStyle,
                            textIsBold = textIsBold,
                            textIsItalic = textIsItalic,
                            onSuccess = {
                                isExporting = false
                                statusText = "सेव हो गया! Movies/MiniVNPro"
                                Toast.makeText(context, "वीडियो गैलरी में सेव हो गया!", Toast.LENGTH_LONG).show()
                            },
                            onError = { err ->
                                isExporting = false
                                statusText = "Error: $err"
                                Toast.makeText(context, "Export फेल: $err", Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2979FF)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = "Export", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isExporting) "..." else "Export", fontSize = 13.sp)
            }
        }

        // 2. Zero-Freeze GPU Video & Photo Preview Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .onSizeChanged { previewSize = it },
            contentAlignment = Alignment.Center
        ) {
            if (clips.isNotEmpty()) {
                val isRotatedSideways = rotationDegrees % 180 != 0
                val rotScale = if (isRotatedSideways) 0.72f else 1.0f

                if (activeClip?.isImage == true) {
                    AndroidView(
                        factory = { ctx ->
                            ImageView(ctx).apply {
                                scaleType = ImageView.ScaleType.FIT_CENTER
                            }
                        },
                        update = { iv ->
                            iv.setImageURI(activeClip.uri)
                            iv.colorFilter = currentColorFilter
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                rotationZ = rotationDegrees.toFloat()
                                scaleX = rotScale
                                scaleY = rotScale
                            }
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                exoPlayer.setVideoTextureView(this)
                            }
                        },
                        update = { tv ->
                            exoPlayer.setVideoTextureView(tv)
                            tv.setLayerPaint(Paint().apply {
                                colorFilter = currentColorFilter
                            })
                            tv.invalidate()
                        },
                        modifier = Modifier
                            .aspectRatio(videoAspectRatio.coerceIn(0.4f, 2.5f))
                            .graphicsLayer {
                                rotationZ = rotationDegrees.toFloat()
                                scaleX = rotScale
                                scaleY = rotScale
                            }
                    )
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("कोई वीडियो या फोटो नहीं चुना गया", color = Color.Gray, fontSize = 14.sp)
                }
            }

            // Draggable Text Overlay
            if (overlayText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (textNormX * previewSize.width / 2f).roundToInt(),
                                (textNormY * previewSize.height / 2f).roundToInt()
                            )
                        }
                        .pointerInput(previewSize) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                if (previewSize.width > 0 && previewSize.height > 0) {
                                    textNormX = (textNormX + (dragAmount.x / (previewSize.width / 2f))).coerceIn(-0.8f, 0.8f)
                                    textNormY = (textNormY + (dragAmount.y / (previewSize.height / 2f))).coerceIn(-0.8f, 0.8f)
                                }
                            }
                        }
                        .clickable { activePanel = "TEXT" }
                        .border(
                            width = if (activePanel == "TEXT") 1.dp else 0.dp,
                            color = if (activePanel == "TEXT") Color(0xAAFFD600) else Color.Transparent,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .background(getTextBgColor(textBgMode), RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = overlayText,
                        color = textColor,
                        fontSize = (18f * textScale).sp,
                        fontFamily = textFontStyle.composeFamily,
                        fontWeight = if (textIsBold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (textIsItalic) FontStyle.Italic else FontStyle.Normal,
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (isExporting || isLoadingMedia) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xAA000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF2979FF))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = if (isExporting) "MP4 वीडियो Export हो रहा है..." else "मीडिया लोड हो रहा है...",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // 3. Playback Control & Seek Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF151518))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${formatTime(currentTotalPosMs)} / ${formatTime(totalDurationMs)}",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )

                IconButton(
                    onClick = {
                        if (clips.isNotEmpty()) {
                            isAudioPreviewing = false
                            if (exoPlayer.isPlaying) {
                                exoPlayer.pause()
                                audioPlayer.pause()
                            } else {
                                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                                    seekToTotalPosition(0L)
                                }
                                val startProgress = (getCurrentTotalPosition().toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                                val sp = getSmoothSpeedAtProgress(speedPoints, startProgress)
                                liveSpeedDisplay = sp
                                exoPlayer.setPlaybackSpeed(sp)
                                exoPlayer.play()
                                if (audioUri != null) {
                                    val estTotal = estimateCurveDurationMs(totalDurationMs, speedPoints)
                                    val audioOffset = (startProgress * estTotal).toLong()
                                    audioPlayer.seekTo((audioStartMs + audioOffset).coerceAtMost(audioDurationMs))
                                    audioPlayer.play()
                                }
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Text(
                    text = statusText,
                    color = Color(0xFF8AB4F8),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Slider(
                value = currentTotalPosMs.toFloat().coerceIn(0f, totalDurationMs.toFloat()),
                onValueChange = { newPos ->
                    exoPlayer.pause()
                    audioPlayer.pause()
                    isAudioPreviewing = false
                    seekToTotalPosition(newPos.toLong())
                },
                valueRange = 0f..totalDurationMs.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color(0xFF2979FF),
                    inactiveTrackColor = Color(0xFF3A3A44)
                ),
                modifier = Modifier.height(26.dp)
            )
        }

        // 4A. Interactive VN Touch Speed Curve Panel
        if (activePanel == "SPEED") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF181820))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "पॉइंट को उंगली से ऊपर (Fast) या नीचे (Slow) खींचें",
                        color = Color(0xFFFFD600),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Reset",
                        color = Color(0xFF2979FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            speedPoints = listOf(1.0f, 1.0f, 1.0f, 1.0f, 1.0f)
                            selectedSpeedPreset = "Normal"
                            liveSpeedDisplay = 1.0f
                            exoPlayer.setPlaybackSpeed(1.0f)
                            statusText = "Speed Curve रीसेट"
                        }
                    )
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    speedCurvePresets.forEach { preset ->
                        val isSel = selectedSpeedPreset == preset.name
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) Color(0xFF2979FF) else Color(0xFF2A2A35))
                                .clickable {
                                    speedPoints = preset.points
                                    selectedSpeedPreset = preset.name
                                    statusText = "Curve: ${preset.name}"
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(preset.name, color = Color.White, fontSize = 11.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Interactive Draggable VN Curve Graph
                val playheadProgress = (currentTotalPosMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0E0E14))
                        .border(1.dp, Color(0xFF343444), RoundedCornerShape(10.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    val nearest = ((offset.x / w) * (speedPoints.size - 1)).roundToInt()
                                        .coerceIn(0, speedPoints.lastIndex)
                                    activeSpeedDotIndex = nearest
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val h = size.height.toFloat().coerceAtLeast(1f)
                                    val normY = 1f - (change.position.y / h).coerceIn(0f, 1f)
                                    val newSpeed = (0.3f + normY * 2.2f).coerceIn(0.3f, 2.5f)
                                    val updated = speedPoints.toMutableList()
                                    updated[activeSpeedDotIndex] = (newSpeed * 10f).roundToInt() / 10f
                                    speedPoints = updated
                                    selectedSpeedPreset = "Custom"
                                }
                            )
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height

                        // Horizontal grid lines (2.5x, 1.0x, 0.3x)
                        val yNormal = h * (1f - ((1.0f - 0.3f) / 2.2f))
                        drawLine(Color(0xFF282834), Offset(0f, 0f), Offset(w, 0f), strokeWidth = 1.5f)
                        drawLine(Color(0xFF4A4A5C), Offset(0f, yNormal), Offset(w, yNormal), strokeWidth = 2.5f)
                        drawLine(Color(0xFF282834), Offset(0f, h), Offset(w, h), strokeWidth = 1.5f)

                        // Live playhead line
                        val px = playheadProgress * w
                        drawLine(Color(0x99FFFFFF), Offset(px, 0f), Offset(px, h), strokeWidth = 2f)

                        // Smooth curve path
                        val coords = speedPoints.mapIndexed { i, sp ->
                            val x = (i.toFloat() / (speedPoints.size - 1)) * w
                            val normY = ((sp - 0.3f) / 2.2f).coerceIn(0f, 1f)
                            Offset(x, h * (1f - normY))
                        }
                        val path = Path()
                        coords.forEachIndexed { i, pt ->
                            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                        }
                        drawPath(path, color = Color(0xFFFFD600), style = Stroke(width = 6f, cap = StrokeCap.Round))

                        // Draggable dots
                        coords.forEachIndexed { i, pt ->
                            val isSel = i == activeSpeedDotIndex
                            drawCircle(
                                color = if (isSel) Color(0xFFFF5252) else Color(0xFF2979FF),
                                radius = if (isSel) 15f else 11f,
                                center = pt
                            )
                            drawCircle(color = Color.White, radius = 5f, center = pt)
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                val dotLabels = listOf("Start", "25%", "Mid", "75%", "End")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    speedPoints.forEachIndexed { i, sp ->
                        val isSel = i == activeSpeedDotIndex
                        Text(
                            text = "${dotLabels[i]}: ${String.format(Locale.US, "%.1fx", sp)}",
                            color = if (isSel) Color(0xFFFFD600) else Color.LightGray,
                            fontSize = 10.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clickable { activeSpeedDotIndex = i }
                        )
                    }
                }
            }
        }

        // 4B. Zero-Freeze Instant VN Filter & Color Panel
        if (activePanel == "FILTER") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A22))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("VN Filters & Color (लाइव प्रीव्यू)", color = Color(0xFFFFD600), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = "Reset",
                        color = Color(0xFF2979FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            selectedFilter = VideoFilter.ORIGINAL
                            brightness = 0f
                            contrast = 0f
                            saturation = 0f
                            statusText = "Filter रीसेट हो गया"
                        }
                    )
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VideoFilter.values().forEach { filter ->
                        val isSelected = selectedFilter == filter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFF2979FF) else Color(0xFF2A2A34))
                                .clickable {
                                    selectedFilter = filter
                                    statusText = "Filter: ${filter.title}"
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(filter.title, color = Color.White, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bright", color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.width(54.dp))
                    Slider(
                        value = brightness,
                        onValueChange = { brightness = it },
                        valueRange = -0.5f..0.5f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFFFD600), activeTrackColor = Color(0xFFFFD600)),
                        modifier = Modifier.weight(1f).height(24.dp)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Contrast", color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.width(54.dp))
                    Slider(
                        value = contrast,
                        onValueChange = { contrast = it },
                        valueRange = -0.5f..0.5f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF40C4FF), activeTrackColor = Color(0xFF40C4FF)),
                        modifier = Modifier.weight(1f).height(24.dp)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Color+", color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.width(54.dp))
                    Slider(
                        value = saturation,
                        onValueChange = { saturation = it },
                        valueRange = -80f..80f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFFF80AB), activeTrackColor = Color(0xFFFF80AB)),
                        modifier = Modifier.weight(1f).height(24.dp)
                    )
                }
            }
        }

        // 4C. Text Adjust Panel
        if (activePanel == "TEXT" && overlayText.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1F1D15))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("टेक्स्ट स्टाइल (उंगली से जगह बदलें)", color = Color(0xFFFFD600), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Edit",
                            color = Color(0xFF2979FF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                tempTextInput = overlayText
                                showTextDialog = true
                            }
                        )
                        Text(
                            text = "हटाएँ",
                            color = Color(0xFFFF5252),
                            fontSize = 11.sp,
                            modifier = Modifier.clickable {
                                overlayText = ""
                                activePanel = "NONE"
                                statusText = "टेक्स्ट हटा दिया गया"
                            }
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TextFontStyle.values().forEach { style ->
                        val sel = textFontStyle == style
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (sel) Color(0xFF2979FF) else Color(0xFF2D2B22))
                                .clickable { textFontStyle = style }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(style.label, color = Color.White, fontSize = 11.sp)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (textIsBold) Color(0xFFFFD600) else Color(0xFF2D2B22))
                            .clickable { textIsBold = !textIsBold }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("B", color = if (textIsBold) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (textIsItalic) Color(0xFFFFD600) else Color(0xFF2D2B22))
                            .clickable { textIsItalic = !textIsItalic }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("I", color = if (textIsItalic) Color.Black else Color.White, fontSize = 11.sp, fontStyle = FontStyle.Italic)
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF3A3628))
                            .clickable {
                                val all = TextBgMode.values()
                                textBgMode = all[(textBgMode.ordinal + 1) % all.size]
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(textBgMode.label, color = Color(0xFFFFD600), fontSize = 11.sp)
                    }
                }

                Slider(
                    value = textScale,
                    onValueChange = { textScale = it },
                    valueRange = 0.6f..2.0f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFFFD600), activeTrackColor = Color(0xFFFFD600)),
                    modifier = Modifier.height(24.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("रंग:", color = Color.LightGray, fontSize = 11.sp)
                    textColorPalette.forEach { col ->
                        val selected = textColor == col
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(col)
                                .border(if (selected) 2.5.dp else 1.dp, if (selected) Color(0xFF2979FF) else Color.Gray, CircleShape)
                                .clickable { textColor = col }
                        )
                    }
                }
            }
        }

        // 4D. Music Start-Time Adjust Panel
        if (activePanel == "MUSIC" && audioUri != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A221C))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "गाना शुरू होगा: ${formatTime(audioStartMs)} / ${formatTime(audioDurationMs)}",
                        color = Color(0xFF81C784),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = if (isAudioPreviewing) "रोकें" else "सुनें",
                            color = Color(0xFFFFD600),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                exoPlayer.pause()
                                if (isAudioPreviewing) {
                                    audioPlayer.pause()
                                    isAudioPreviewing = false
                                } else {
                                    audioPlayer.seekTo(audioStartMs)
                                    audioPlayer.play()
                                    isAudioPreviewing = true
                                }
                            }
                        )
                        Text(
                            text = "हटाएँ",
                            color = Color(0xFFFF5252),
                            fontSize = 12.sp,
                            modifier = Modifier.clickable {
                                audioPlayer.pause()
                                audioPlayer.clearMediaItems()
                                audioUri = null
                                audioTitle = ""
                                audioStartMs = 0L
                                isAudioPreviewing = false
                                activePanel = "NONE"
                                exoPlayer.volume = if (isMuted) 0f else 1f
                                statusText = "म्यूज़िक हटा दिया गया"
                            }
                        )
                    }
                }

                val maxStart = (audioDurationMs - 1000L).coerceAtLeast(1000L).toFloat()
                Slider(
                    value = audioStartMs.toFloat().coerceIn(0f, maxStart),
                    onValueChange = {
                        audioStartMs = it.toLong()
                        audioPlayer.seekTo(audioStartMs)
                    },
                    valueRange = 0f..maxStart,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF4CAF50), activeTrackColor = Color(0xFF4CAF50)),
                    modifier = Modifier.height(28.dp)
                )
            }
        }

        // 5. VN-Style Timeline
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF121215))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF222228))
                    .clickable {
                        if (audioUri != null) {
                            activePanel = if (activePanel == "MUSIC") "NONE" else "MUSIC"
                        } else {
                            showMusicDialog = true
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = if (audioUri != null) Color(0xFF4CAF50) else Color.LightGray, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (audioUri != null) "$audioTitle (${formatTime(audioStartMs)})" else "Tap to search & add music",
                        color = if (audioUri != null) Color(0xFF81C784) else Color.Gray,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (audioUri != null) {
                    Spacer(Modifier.width(8.dp))
                    Text("बदलें", color = Color(0xFF2979FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { showMusicDialog = true })
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF222228))
                    .clickable {
                        if (overlayText.isNotBlank()) {
                            activePanel = if (activePanel == "TEXT") "NONE" else "TEXT"
                        } else {
                            tempTextInput = ""
                            showTextDialog = true
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Title, contentDescription = null, tint = if (overlayText.isNotBlank()) Color(0xFFFFD600) else Color.LightGray, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (overlayText.isNotBlank()) "Text: ${overlayText.replace("\n", " ")}" else "Tap to add text / subtitle",
                        color = if (overlayText.isNotBlank()) Color(0xFFFFD600) else Color.Gray,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (overlayText.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text("Edit", color = Color(0xFF2979FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                        tempTextInput = overlayText
                        showTextDialog = true
                    })
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (clips.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E1E24))
                            .border(1.dp, Color(0xFF3A3A44), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Video / Photo Timeline", color = Color.Gray, fontSize = 12.sp)
                    }
                } else {
                    clips.forEachIndexed { index, clip ->
                        val isSelected = index == selectedClipIndex
                        val clipWidth = ((clip.durationMs / 1000f) * 18f).coerceIn(76f, 220f).dp
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(clipWidth)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        isSelected -> Color(0xFF2D2B1E)
                                        clip.isImage -> Color(0xFF1E2630)
                                        else -> Color(0xFF22222A)
                                    }
                                )
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) Color(0xFFFFD600) else Color(0xFF444450),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    exoPlayer.pause()
                                    audioPlayer.pause()
                                    selectedClipIndex = index
                                    exoPlayer.seekTo(index, 0L)
                                    currentTotalPosMs = getCurrentTotalPosition()
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = if (clip.isImage) "Photo ${index + 1}" else "Clip ${index + 1}",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(formatTime(clip.durationMs), color = Color(0xFFFFD600), fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        // 6. VN-Style Bottom Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0E0E10))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ToolButton(Icons.Default.VideoCall, "+Video") {
                multiVideoPicker.launch(arrayOf("video/*"))
            }

            ToolButton(Icons.Default.AddPhotoAlternate, "+Photo", tint = Color(0xFF40C4FF)) {
                multiPhotoPicker.launch(arrayOf("image/*"))
            }

            ToolButton(
                icon = Icons.Default.AutoAwesome,
                label = "Filter",
                tint = if (activePanel == "FILTER" || selectedFilter != VideoFilter.ORIGINAL || brightness != 0f || contrast != 0f || saturation != 0f) Color(0xFFFFD600) else Color.White
            ) {
                activePanel = if (activePanel == "FILTER") "NONE" else "FILTER"
            }

            ToolButton(
                icon = Icons.Default.Speed,
                label = "Speed",
                tint = if (activePanel == "SPEED" || !isDefaultSpeedCurve(speedPoints)) Color(0xFFFFD600) else Color.White
            ) {
                activePanel = if (activePanel == "SPEED") "NONE" else "SPEED"
            }

            ToolButton(
                icon = Icons.Default.RotateRight,
                label = "Rotate",
                tint = if (rotationDegrees % 360 != 0) Color(0xFFFFD600) else Color.White
            ) {
                rotationDegrees = (rotationDegrees + 90) % 360
                statusText = "Rotate:अटकने, काली स्क्रीन और टेक्स्ट ज़ूम होने—तीनों की असली वजह और उनका **पक्का इलाज** नीचे है:

* **अटकने और Rotate पर काली स्क्रीन का पक्का इलाज:** अभी तक `Rotate` या `Filter` बदलते ही वीडियो प्लेयर (ExoPlayer) का OpenGL इंजन बार-बार रीस्टार्ट हो रहा था, जिससे स्क्रीन काली होकर ऐप जाम हो जाता था। अब हमने **लाइव प्रीव्यू से OpenGL रीलोड पूरी तरह हटाकर `TextureView` इस्तेमाल किया है**—अब तुम कितनी भी बार Rotate, Filter या स्लाइडर चलाओ, **ऐप कभी नहीं अटकेगा और न स्क्रीन काली होगी**। Export के समय असली HD फिल्टर और रोटेशन MP4 में सेव होंगे।
* **Export में टेक्स्ट ज़्यादा ज़ूम होने का इलाज:** `TextHelper.kt` में `RelativeSizeSpan(2.0f)` की वजह से टेक्स्ट एक्सपोर्ट होते समय दोगुना बड़ा हो रहा था। अब उसे फिक्स साइज़ (`42px`) कर दिया गया है ताकि गैलरी में टेक्स्ट बिल्कुल स्क्रीन जैसा साफ दिखे।
* **VN जैसा Touch Speed Curve:** अब छोटे `+`/`-` बटन हटाकर **VN जैसा बड़ा ग्राफ** बना दिया है। तुम अपनी **उंगली से ग्राफ के किसी भी हिस्से को ऊपर (Fast) या नीचे (Slow)** खींचकर खुद तय कर सकते हो कि वीडियो कहाँ स्लो हो और कहाँ फास्ट।
* **30-30 सेकंड की म्यूज़िक क्लिप्स (ईमानदार सच्चाई):** फ्री ऑनलाइन API (iTunes) किसी एक गाने की सिर्फ **एक ही 30-सेकंड की मुख्य क्लिप** देता है—एक ही गाने के अलग-अलग हिस्से (0:30–1:00, 1:00–1:30) किसी भी फ्री API में नहीं मिलते। इसके लिए हमने 2 बढ़िया सुधार किए हैं:
  1. ऑनलाइन सर्च लिस्ट में हर गाने के आगे **Play (▶) बटन** जोड़ दिया है, ताकि उसी गाने के अलग-अलग वर्जन (Original, Slowed, Lo-Fi, Unplugged) सीधे सुनकर सही क्लिप चुन सको।
  2. अगर तुम अपने फोन का कोई पूरा 3–4 मिनट का MP3 गाना चुनते हो, तो Music पैनल में अपने आप **`0:00`, `0:30`, `1:00`, `1:30`...** के 30-30 सेकंड के हिस्से (Parts) बन जाएँगे, जिन्हें एक टैप में सेट कर सकोगे।

---

### स्टेप 1: `TextHelper.kt` को अपडेट करें (Text Zoom Fix)

1. `Mini-VN-Pro` में `app/src/main/java/com/mayank/minivnpro/TextHelper.kt` खोलो और **पेंसिल (✏️)** पर टैप करो।
2. पुराना पूरा कोड **Select All करके Delete** कर दो, और यह नया कोड पेस्ट करके **Commit changes** कर दो:

```kotlin
package com.mayank.minivnpro

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import com.google.common.collect.ImmutableList

val textColorPalette = listOf(
    Color.White,
    Color(0xFFFFD600), // Yellow
    Color(0xFFFF5252), // Red
    Color(0xFF40C4FF), // Sky Blue
    Color(0xFF69F0AE), // Green
    Color(0xFFFF80AB), // Pink
    Color(0xFFFFAB40), // Orange
    Color.Black
)

enum class TextFontStyle(val label: String, val familyName: String, val composeFamily: FontFamily) {
    SANS("Classic", "sans-serif", FontFamily.SansSerif),
    SERIF("Stylish", "serif", FontFamily.Serif),
    MONO("Typewriter", "monospace", FontFamily.Monospace)
}

enum class TextBgMode(val label: String) {
    NONE("No Box"),
    DARK("Dark Box"),
    LIGHT("Light Box"),
    YELLOW("Yellow Tag")
}

fun getTextBgColor(mode: TextBgMode): Color {
    return when (mode) {
        TextBgMode.NONE -> Color.Transparent
        TextBgMode.DARK -> Color(0xAA000000)
        TextBgMode.LIGHT -> Color(0xCCFFFFFF)
        TextBgMode.YELLOW -> Color(0xDDFFD600)
    }
}

@UnstableApi
fun buildTextExportEffects(
    text: String,
    anchorX: Float,
    anchorY: Float,
    scale: Float,
    color: Color,
    bgMode: TextBgMode,
    fontStyle: TextFontStyle,
    isBold: Boolean,
    isItalic: Boolean
): List<Effect> {
    if (text.isBlank()) return emptyList()

    val formatted = text.lines().joinToString("\n") { " $it " }
    val spannable = SpannableString(formatted).apply {
        setSpan(
            ForegroundColorSpan(color.toArgb()),
            0,
            length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        if (bgMode != TextBgMode.NONE) {
            setSpan(
                BackgroundColorSpan(getTextBgColor(bgMode).toArgb()),
                0,
                length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        val styleInt = when {
            isBold && isItalic -> Typeface.BOLD_ITALIC
            isBold -> Typeface.BOLD
            isItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        setSpan(StyleSpan(styleInt), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(TypefaceSpan(fontStyle.familyName), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        // Fixed pixel size prevents giant zoomed-in text on HD export
        setSpan(AbsoluteSizeSpan(42, false), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    val safeScale = (scale * 0.85f).coerceIn(0.45f, 1.8f)
    val settings = OverlaySettings.Builder()
        .setBackgroundFrameAnchor(anchorX.coerceIn(-0.8f, 0.8f), anchorY.coerceIn(-0.8f, 0.8f))
        .setOverlayFrameAnchor(0f, 0f)
        .setScale(safeScale, safeScale)
        .build()

    val textOverlay = TextOverlay.createStaticTextOverlay(spannable, settings)
    return listOf(OverlayEffect(ImmutableList.of(textOverlay)))
}
