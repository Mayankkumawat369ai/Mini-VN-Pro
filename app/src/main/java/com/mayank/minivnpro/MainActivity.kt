package com.mayank.minivnpro

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.Locale
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

    // Upgraded Draggable Text State
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

    // Upgraded Filter & Color Adjust State (Applied safely to prevent freeze)
    var selectedFilter by remember { mutableStateOf(VideoFilter.ORIGINAL) }
    var brightness by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(0f) }
    var saturation by remember { mutableStateOf(0f) }
    var rotationDegrees by remember { mutableStateOf(0) }
    var effectsVersion by remember { mutableStateOf(0) }

    // VN-Style 5-Point Speed Curve State
    var speedPoints by remember { mutableStateOf(listOf(1.0f, 1.0f, 1.0f, 1.0f, 1.0f)) }
    var selectedSpeedPreset by remember { mutableStateOf("Normal") }
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

    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    val audioPlayer = remember { ExoPlayer.Builder(context).build() }

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

            exoPlayer.setVideoEffects(
                buildPreviewVideoEffects(selectedFilter, brightness, contrast, saturation, rotationDegrees)
            )
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
                // Auto-recover if ExoPlayer ever hits a codec/surface hiccup
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
        }
    }

    // Only reload ExoPlayer effects when user finishes adjusting (prevents freezing!)
    LaunchedEffect(effectsVersion) {
        if (effectsVersion == 0 || clips.isEmpty()) return@LaunchedEffect
        val idx = exoPlayer.currentMediaItemIndex.coerceIn(0, clips.lastIndex)
        val pos = exoPlayer.currentPosition
        val wasPlaying = exoPlayer.isPlaying
        audioPlayer.pause()
        loadClipsSafely(clips, idx, pos, wasPlaying)
        if (wasPlaying && audioUri != null) {
            audioPlayer.play()
        }
    }

    // Live VN Speed Curve playback loop (smooth, zero restarts!)
    LaunchedEffect(isPlaying, clips, speedPoints) {
        while (isPlaying) {
            val pos = getCurrentTotalPosition()
            currentTotalPosMs = pos
            val idx = exoPlayer.currentMediaItemIndex
            if (idx in clips.indices) selectedClipIndex = idx

            val progress = (pos.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
            val targetSpeed = getSpeedAtProgress(speedPoints, progress)
            if (kotlin.math.abs(liveSpeedDisplay - targetSpeed) > 0.04f) {
                liveSpeedDisplay = targetSpeed
                exoPlayer.setPlaybackSpeed(targetSpeed)
            }
            delay(80)
        }
    }

    // Multi-Video Picker (Background IO thread - prevents UI freeze!)
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

    // Multi-Photo Picker
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

    // Multi-Line Text Input Dialog
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

    // WhatsApp-Style Music Search Dialog
    if (showMusicDialog) {
        AlertDialog(
            onDismissRequest = { showMusicDialog = false },
            containerColor = Color(0xFF1A1A20),
            title = {
                Text("गाना सर्च करें", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
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
                        Text("या फोन के स्टोरेज से गाना चुनें", fontSize = 12.sp)
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
                    } else if (searchResults.isEmpty() && searchQuery.isNotBlank()) {
                        Text(
                            text = "सर्च बटन (🔍) दबाएँ या दूसरा नाम लिखें",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                            items(searchResults) { song ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF262630))
                                        .clickable {
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
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color(0xFFFFD600))
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(song.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(song.artist, color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text("Use", color = Color(0xFF2979FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMusicDialog = false }) {
                    Text("Close", color = Color.LightGray)
                }
            }
        )
    }

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

        // 2. Video Preview Area + Draggable Upgraded Text Overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .onSizeChanged { previewSize = it },
            contentAlignment = Alignment.Center
        ) {
            if (clips.isNotEmpty()) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("कोई वीडियो या फोटो नहीं चुना गया", color = Color.Gray, fontSize = 14.sp)
                }
            }

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
                                    textNormX = (textNormX + (dragAmount.x / (previewSize.width / 2f))).coerceIn(-0.85f, 0.85f)
                                    textNormY = (textNormY + (dragAmount.y / (previewSize.height / 2f))).coerceIn(-0.85f, 0.85f)
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
                                val sp = getSpeedAtProgress(speedPoints, startProgress)
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

        // 4A. VN-Style Speed Curve Panel (Graph + Presets + 5 Editable Points)
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
                    Text("VN Speed Curve (शुरू → बीच → अंत)", color = Color(0xFFFFD600), fontSize = 11.sp, fontWeight = FontWeight.Bold)
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

                // Presets Row
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
                                    statusText = "Speed Curve: ${preset.name}"
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(preset.name, color = Color.White, fontSize = 11.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Live VN Curve Graph
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF101016))
                        .border(1.dp, Color(0xFF30303E), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        // 1.0x reference line
                        val yNormal = h * (1f - ((1.0f - 0.3f) / 2.2f))
                        drawLine(
                            color = Color(0xFF3A3A48),
                            start = Offset(0f, yNormal),
                            end = Offset(w, yNormal),
                            strokeWidth = 2f
                        )

                        val path = Path()
                        val coords = speedPoints.mapIndexed { i, sp ->
                            val x = (i.toFloat() / (speedPoints.size - 1)) * w
                            val normY = ((sp - 0.3f) / 2.2f).coerceIn(0f, 1f)
                            val y = h * (1f - normY)
                            Offset(x, y)
                        }
                        coords.forEachIndexed { i, pt ->
                            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                        }
                        drawPath(path, color = Color(0xFFFFD600), style = Stroke(width = 5f, cap = StrokeCap.Round))
                        coords.forEach { pt ->
                            drawCircle(color = Color(0xFF2979FF), radius = 8f, center = pt)
                            drawCircle(color = Color.White, radius = 4f, center = pt)
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // 5 Point Speed Controls (Start, 25%, Mid, 75%, End)
                val labels = listOf("Start", "25%", "Mid", "75%", "End")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    speedPoints.forEachIndexed { idx, sp ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF24242E))
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(labels[idx], color = Color.Gray, fontSize = 9.sp)
                            Text(
                                text = String.format(Locale.US, "%.1fx", sp),
                                color = Color(0xFFFFD600),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "−",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(Color(0xFF383846))
                                        .clickable {
                                            val updated = speedPoints.toMutableList()
                                            updated[idx] = (sp - 0.2f).coerceAtLeast(0.3f)
                                            speedPoints = updated
                                            selectedSpeedPreset = "Custom"
                                        }
                                        .padding(horizontal = 6.dp)
                                )
                                Text(
                                    text = "+",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(Color(0xFF2979FF))
                                        .clickable {
                                            val updated = speedPoints.toMutableList()
                                            updated[idx] = (sp + 0.2f).coerceAtMost(2.5f)
                                            speedPoints = updated
                                            selectedSpeedPreset = "Custom"
                                        }
                                        .padding(horizontal = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4B. Upgraded VN Filter & Color Adjust Panel (Applies on release -> Zero Freeze!)
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
                    Text("VN Filters & Color (स्लाइडर छोड़ते ही लागू होगा)", color = Color(0xFFFFD600), fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
                            effectsVersion++
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
                                    effectsVersion++
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
                        onValueChangeFinished = { effectsVersion++ },
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
                        onValueChangeFinished = { effectsVersion++ },
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
                        onValueChangeFinished = { effectsVersion++ },
                        valueRange = -80f..80f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFFF80AB), activeTrackColor = Color(0xFFFF80AB)),
                        modifier = Modifier.weight(1f).height(24.dp)
                    )
                }
            }
        }

        // 4C. Upgraded Text Adjust Panel (Fonts, Bold/Italic, 4 Box Styles, 8 Colors, Size)
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

                // Font, Bold, Italic & Box Style Chips
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
                    valueRange = 0.6f..2.2f,
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
                effectsVersion++
                statusText = "Rotate: ${rotationDegrees}°"
            }

            ToolButton(
                icon = Icons.Default.MusicNote,
                label = "Music",
                tint = if (audioUri != null) Color(0xFF4CAF50) else Color.White
            ) {
                showMusicDialog = true
            }

            ToolButton(
                icon = Icons.Default.Title,
                label = "Text",
                tint = if (overlayText.isNotBlank()) Color(0xFFFFD600) else Color.White
            ) {
                if (overlayText.isBlank()) {
                    tempTextInput = ""
                    showTextDialog = true
                } else {
                    activePanel = if (activePanel == "TEXT") "NONE" else "TEXT"
                }
            }

            ToolButton(Icons.Default.ContentCut, "Split", tint = Color(0xFFFFD600)) {
                if (clips.isNotEmpty()) {
                    val idx = exoPlayer.currentMediaItemIndex.coerceIn(0, clips.lastIndex)
                    val posInClip = exoPlayer.currentPosition
                    val clip = clips[idx]
                    val splitPoint = clip.startMs + posInClip
                    if (splitPoint - clip.startMs >= 400L && clip.endMs - splitPoint >= 400L) {
                        exoPlayer.pause()
                        audioPlayer.pause()
                        val leftClip = VideoClip(nextClipId++, clip.uri, clip.startMs, splitPoint, clip.isImage)
                        val rightClip = VideoClip(nextClipId++, clip.uri, splitPoint, clip.endMs, clip.isImage)
                        val updated = clips.toMutableList().apply {
                            removeAt(idx)
                            add(idx, rightClip)
                            add(idx, leftClip)
                        }
                        clips = updated
                        selectedClipIndex = idx + 1
                        loadClipsSafely(updated, idx + 1, 0L, false)
                        statusText = "क्लिप Split हो गई!"
                    } else {
                        Toast.makeText(context, "स्लाइडर को क्लिप के थोड़ा बीच में ले जाएँ", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            ToolButton(Icons.Default.Delete, "Delete", tint = Color(0xFFFF5252)) {
                if (clips.isNotEmpty() && selectedClipIndex in clips.indices) {
                    exoPlayer.pause()
                    audioPlayer.pause()
                    val updated = clips.toMutableList().apply { removeAt(selectedClipIndex) }
                    clips = updated
                    if (updated.isEmpty()) {
                        selectedClipIndex = 0
                        currentTotalPosMs = 0L
                        exoPlayer.stop()
                        exoPlayer.clearMediaItems()
                        statusText = "सारी क्लिप्स हट गईं"
                    } else {
                        val newIdx = selectedClipIndex.coerceAtMost(updated.lastIndex)
                        selectedClipIndex = newIdx
                        loadClipsSafely(updated, newIdx, 0L, false)
                        statusText = "क्लिप डिलीट हो गई!"
                    }
                }
            }

            ToolButton(Icons.Default.ContentCopy, "Duplicate") {
                if (selectedClipIndex in clips.indices) {
                    exoPlayer.pause()
                    audioPlayer.pause()
                    val clip = clips[selectedClipIndex]
                    val copy = VideoClip(nextClipId++, clip.uri, clip.startMs, clip.endMs, clip.isImage)
                    val updated = clips.toMutableList().apply { add(selectedClipIndex + 1, copy) }
                    clips = updated
                    selectedClipIndex += 1
                    loadClipsSafely(updated, selectedClipIndex, 0L, false)
                    statusText = "क्लिप कॉपी हो गई!"
                }
            }

            ToolButton(
                icon = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                label = if (isMuted) "Muted" else "Sound"
            ) {
                isMuted = !isMuted
                exoPlayer.volume = if (isMuted || audioUri != null) 0f else 1f
            }
        }
    }
}

@Composable
fun ToolButton(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = Color.LightGray, fontSize = 10.sp)
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

@UnstableApi
fun exportVideoClips(
    context: Context,
    clips: List<VideoClip>,
    audioUri: Uri?,
    audioStartMs: Long,
    audioDurationMs: Long,
    muteOriginal: Boolean,
    filter: VideoFilter,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    rotationDegrees: Int,
    speedPoints: List<Float>,
    overlayText: String,
    textAnchorX: Float,
    textAnchorY: Float,
    textScale: Float,
    textColor: Color,
    textBgMode: TextBgMode,
    textFontStyle: TextFontStyle,
    textIsBold: Boolean,
    textIsItalic: Boolean,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    val tempFile = File(context.cacheDir, "MiniVNPro_${System.currentTimeMillis()}.mp4")
    val rawDuration = clips.sumOf { it.durationMs }.coerceAtLeast(500L)
    val effectiveDuration = estimateCurveDurationMs(rawDuration, speedPoints)

    val commonVideoEffects = buildPreviewVideoEffects(filter, brightness, contrast, saturation, rotationDegrees) +
        buildTextExportEffects(
            text = overlayText,
            anchorX = textAnchorX,
            anchorY = textAnchorY,
            scale = textScale,
            color = textColor,
            bgMode = textBgMode,
            fontStyle = textFontStyle,
            isBold = textIsBold,
            isItalic = textIsItalic
        )

    val editedClips = clips.map { clip ->
        val removeClipAudio = clip.isImage || muteOriginal || audioUri != null
        val (speedAudioFx, speedVideoFx) = if (clip.isImage) {
            emptyList<androidx.media3.common.audio.AudioProcessor>() to emptyList()
        } else {
            buildSpeedCurveEffects(clip.durationMs, speedPoints, removeClipAudio)
        }

        val allVideoFx = commonVideoEffects + speedVideoFx

        val builder = EditedMediaItem.Builder(clip.toMediaItem())
            .setRemoveAudio(removeClipAudio)

        if (clip.isImage) {
            builder.setDurationUs(clip.durationMs.coerceAtLeast(1000L) * 1000L)
            builder.setFrameRate(30)
        }

        if (allVideoFx.isNotEmpty() || speedAudioFx.isNotEmpty()) {
            builder.setEffects(Effects(speedAudioFx, allVideoFx))
        }
        builder.build()
    }

    val transformer = Transformer.Builder(context)
        .setVideoMimeType(MimeTypes.VIDEO_H264)
        .setAudioMimeType(MimeTypes.AUDIO_AAC)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                try {
                    saveToGallery(context, tempFile)
                    tempFile.delete()
                    onSuccess()
                } catch (e: Exception) {
                    onError(e.message ?: "Save failed")
                }
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                tempFile.delete()
                onError(exportException.message ?: "Export error")
            }
        })
        .build()

    val videoSequence = EditedMediaItemSequence(editedClips)

    if (audioUri != null) {
        val clipConfig = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(audioStartMs)
        if (audioDurationMs > 0 && audioStartMs + effectiveDuration < audioDurationMs) {
            clipConfig.setEndPositionMs(audioStartMs + effectiveDuration)
        }

        val audioMediaItem = MediaItem.Builder()
            .setUri(audioUri)
            .setClippingConfiguration(clipConfig.build())
            .build()

        val editedAudio = EditedMediaItem.Builder(audioMediaItem)
            .setRemoveVideo(true)
            .build()

        val audioSequence = EditedMediaItemSequence(listOf(editedAudio))
        val composition = Composition.Builder(listOf(videoSequence, audioSequence)).build()
        transformer.start(composition, tempFile.absolutePath)
    } else {
        val composition = Composition.Builder(listOf(videoSequence)).build()
        transformer.start(composition, tempFile.absolutePath)
    }
}

fun saveToGallery(context: Context, videoFile: File) {
    val fileName = "MiniVNPro_${System.currentTimeMillis()}.mp4"
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MiniVNPro")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw Exception("गैलरी में फाइल नहीं बन सकी")

    resolver.openOutputStream(uri)?.use { out ->
        FileInputStream(videoFile).use { input ->
            input.copyTo(out)
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}
