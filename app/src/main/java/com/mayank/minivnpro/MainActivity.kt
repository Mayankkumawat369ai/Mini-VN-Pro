package com.mayank.minivnpro

import android.content.ContentValues
import android.content.Context
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.util.Locale

data class VideoClip(
    val id: Long,
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
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

    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var clips by remember { mutableStateOf<List<VideoClip>>(emptyList()) }
    var selectedClipIndex by remember { mutableStateOf(0) }
    var nextClipId by remember { mutableStateOf(1L) }

    // Music State
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var audioTitle by remember { mutableStateOf("") }
    var audioDurationMs by remember { mutableStateOf(30000L) }
    var audioStartMs by remember { mutableStateOf(0L) }
    var showMusicDialog by remember { mutableStateOf(false) }
    var showMusicAdjust by remember { mutableStateOf(false) }
    var isAudioPreviewing by remember { mutableStateOf(false) }

    // Online Music Search State
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<OnlineSong>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var isDownloadingSong by remember { mutableStateOf(false) }

    var isMuted by remember { mutableStateOf(false) }
    var currentTotalPosMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("नीचे Video पर टैप करके वीडियो चुनें") }

    val totalDurationMs = remember(clips) {
        clips.sumOf { it.durationMs }.coerceAtLeast(1000L)
    }

    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    val audioPlayer = remember { ExoPlayer.Builder(context).build() }

    fun loadClipsIntoPlayer(
        uri: Uri,
        clipList: List<VideoClip>,
        seekClipIdx: Int = 0,
        seekPosInClip: Long = 0L,
        autoPlay: Boolean = false
    ) {
        val mediaItems = clipList.map { clip ->
            MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.startMs)
                        .setEndPositionMs(clip.endMs)
                        .build()
                )
                .build()
        }
        val safeIdx = seekClipIdx.coerceIn(0, (clipList.size - 1).coerceAtLeast(0))
        exoPlayer.setMediaItems(mediaItems, safeIdx, seekPosInClip)
        exoPlayer.prepare()
        exoPlayer.volume = if (isMuted || audioUri != null) 0f else 1f
        exoPlayer.playWhenReady = autoPlay
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
                    audioPlayer.seekTo((audioStartMs + targetMs).coerceAtMost(audioDurationMs))
                }
                return
            }
            acc += dur
        }
    }

    DisposableEffect(Unit) {
        val videoListener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && clips.isEmpty() && exoPlayer.duration > 0) {
                    val dur = exoPlayer.duration
                    clips = listOf(VideoClip(nextClipId++, 0L, dur))
                    selectedClipIndex = 0
                }
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

    LaunchedEffect(isPlaying, clips) {
        while (isPlaying) {
            currentTotalPosMs = getCurrentTotalPosition()
            val idx = exoPlayer.currentMediaItemIndex
            if (idx in clips.indices) selectedClipIndex = idx
            delay(100)
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            videoUri = uri
            clips = emptyList()
            selectedClipIndex = 0
            currentTotalPosMs = 0L
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            exoPlayer.volume = if (isMuted || audioUri != null) 0f else 1f
            exoPlayer.playWhenReady = true
            if (audioUri != null) {
                audioPlayer.seekTo(audioStartMs)
                audioPlayer.play()
            }
            statusText = "वीडियो लोड हो गया!"
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
            showMusicAdjust = true
            statusText = "म्यूज़िक जुड़ गया!"
        }
    }

    // WhatsApp-Style Music Search Dialog
    if (showMusicDialog) {
        AlertDialog(
            onDismissRequest = { showMusicDialog = false },
            containerColor = Color(0xFF1A1A20),
            title = {
                Text(
                    text = "गाना सर्च करें",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
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
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
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
                                                    showMusicAdjust = true
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
                                        Text(
                                            text = song.title,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = song.artist,
                                            color = Color.Gray,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
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
            Text(
                text = "Mini VN Pro",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = if (clips.isEmpty()) "Original" else "${clips.size} Clip${if (clips.size > 1) "s" else ""}",
                color = Color(0xFFFFD600),
                fontSize = 12.sp,
                modifier = Modifier
                    .border(1.dp, Color(0xFF3A3A40), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )

            Button(
                onClick = {
                    val uri = videoUri
                    if (uri == null || clips.isEmpty()) {
                        Toast.makeText(context, "पहले कोई वीडियो चुनें", Toast.LENGTH_SHORT).show()
                    } else if (!isExporting) {
                        exoPlayer.pause()
                        audioPlayer.pause()
                        isAudioPreviewing = false
                        isExporting = true
                        statusText = "HD MP4 बन रहा है..."
                        exportVideoClips(
                            context = context,
                            videoUri = uri,
                            clips = clips,
                            audioUri = audioUri,
                            audioStartMs = audioStartMs,
                            audioDurationMs = audioDurationMs,
                            muteOriginal = isMuted,
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

        // 2. Video Preview Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (videoUri != null) {
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
                    Icon(
                        Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("कोई वीडियो नहीं चुना गया", color = Color.Gray, fontSize = 14.sp)
                }
            }

            if (isExporting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xAA000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF2979FF))
                        Spacer(Modifier.height(10.dp))
                        Text("MP4 वीडियो Export हो रहा है...", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }

        // 3. Playback Control & Precision Seek Bar
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
                        if (videoUri != null && clips.isNotEmpty()) {
                            isAudioPreviewing = false
                            if (exoPlayer.isPlaying) {
                                exoPlayer.pause()
                                audioPlayer.pause()
                            } else {
                                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                                    seekToTotalPosition(0L)
                                }
                                exoPlayer.play()
                                if (audioUri != null) {
                                    val audioPos = (audioStartMs + getCurrentTotalPosition()).coerceAtMost(audioDurationMs)
                                    audioPlayer.seekTo(audioPos)
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

        // 4. Music Start-Time Adjust Panel (WhatsApp Style)
        if (showMusicAdjust && audioUri != null) {
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
                                showMusicAdjust = false
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
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF4CAF50),
                        activeTrackColor = Color(0xFF4CAF50),
                        inactiveTrackColor = Color(0xFF3A4A3E)
                    ),
                    modifier = Modifier.height(28.dp)
                )
            }
        }

        // 5. VN-Style Timeline (Music Track + Video Clips)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF121215))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Music Track Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF222228))
                    .clickable {
                        if (audioUri != null) {
                            showMusicAdjust = !showMusicAdjust
                        } else {
                            showMusicDialog = true
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = if (audioUri != null) Color(0xFF4CAF50) else Color.LightGray,
                        modifier = Modifier.size(18.dp)
                    )
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
                    Text(
                        text = "बदलें",
                        color = Color(0xFF2979FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { showMusicDialog = true }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Video Clips Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
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
                        Text("Video Timeline", color = Color.Gray, fontSize = 12.sp)
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
                                .background(if (isSelected) Color(0xFF2D2B1E) else Color(0xFF22222A))
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
                            Text("Clip ${index + 1}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
            ToolButton(Icons.Default.VideoCall, "Video") {
                videoPicker.launch(arrayOf("video/*"))
            }

            ToolButton(
                icon = Icons.Default.MusicNote,
                label = "Music",
                tint = if (audioUri != null) Color(0xFF4CAF50) else Color.White
            ) {
                showMusicDialog = true
            }

            ToolButton(Icons.Default.ContentCut, "Split", tint = Color(0xFFFFD600)) {
                val uri = videoUri
                if (uri != null && clips.isNotEmpty()) {
                    val idx = exoPlayer.currentMediaItemIndex.coerceIn(0, clips.lastIndex)
                    val posInClip = exoPlayer.currentPosition
                    val clip = clips[idx]
                    val splitPoint = clip.startMs + posInClip
                    if (splitPoint - clip.startMs >= 400L && clip.endMs - splitPoint >= 400L) {
                        exoPlayer.pause()
                        audioPlayer.pause()
                        val leftClip = VideoClip(nextClipId++, clip.startMs, splitPoint)
                        val rightClip = VideoClip(nextClipId++, splitPoint, clip.endMs)
                        val updated = clips.toMutableList().apply {
                            removeAt(idx)
                            add(idx, rightClip)
                            add(idx, leftClip)
                        }
                        clips = updated
                        selectedClipIndex = idx + 1
                        loadClipsIntoPlayer(uri, updated, idx + 1, 0L, false)
                        statusText = "क्लिप Split हो गई!"
                    } else {
                        Toast.makeText(context, "स्लाइडर को क्लिप के थोड़ा बीच में ले जाएँ", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            ToolButton(Icons.Default.Delete, "Delete", tint = Color(0xFFFF5252)) {
                val uri = videoUri
                if (uri != null && clips.size > 1 && selectedClipIndex in clips.indices) {
                    exoPlayer.pause()
                    audioPlayer.pause()
                    val updated = clips.toMutableList().apply { removeAt(selectedClipIndex) }
                    clips = updated
                    val newIdx = selectedClipIndex.coerceAtMost(updated.lastIndex)
                    selectedClipIndex = newIdx
                    loadClipsIntoPlayer(uri, updated, newIdx, 0L, false)
                    statusText = "क्लिप डिलीट हो गई!"
                } else {
                    Toast.makeText(context, "पहले Split करें (कम से कम 1 क्लिप रहनी चाहिए)", Toast.LENGTH_SHORT).show()
                }
            }

            ToolButton(Icons.Default.ContentCopy, "Duplicate") {
                val uri = videoUri
                if (uri != null && selectedClipIndex in clips.indices) {
                    exoPlayer.pause()
                    audioPlayer.pause()
                    val clip = clips[selectedClipIndex]
                    val copy = VideoClip(nextClipId++, clip.startMs, clip.endMs)
                    val updated = clips.toMutableList().apply { add(selectedClipIndex + 1, copy) }
                    clips = updated
                    selectedClipIndex += 1
                    loadClipsIntoPlayer(uri, updated, selectedClipIndex, 0L, false)
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
    videoUri: Uri,
    clips: List<VideoClip>,
    audioUri: Uri?,
    audioStartMs: Long,
    audioDurationMs: Long,
    muteOriginal: Boolean,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    val tempFile = File(context.cacheDir, "MiniVNPro_${System.currentTimeMillis()}.mp4")
    val totalDuration = clips.sumOf { it.durationMs }.coerceAtLeast(500L)

    val editedClips = clips.map { clip ->
        val mediaItem = MediaItem.Builder()
            .setUri(videoUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.startMs)
                    .setEndPositionMs(clip.endMs)
                    .build()
            )
            .build()

        EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(muteOriginal || audioUri != null)
            .build()
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
        if (audioDurationMs > 0 && audioStartMs + totalDuration < audioDurationMs) {
            clipConfig.setEndPositionMs(audioStartMs + totalDuration)
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
