package net.asksakis.massdroidv2.ui.screens.nowplaying

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.RepeatMode
import net.asksakis.massdroidv2.ui.components.VolumeSlider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    onNavigateToQueue: () -> Unit,
    onNavigateToArtist: (itemId: String, provider: String, name: String) -> Unit = { _, _, _ -> },
    onNavigateToAlbum: (itemId: String, provider: String, name: String) -> Unit = { _, _, _ -> },
    viewModel: NowPlayingViewModel = hiltViewModel()
) {
    val player by viewModel.selectedPlayer.collectAsStateWithLifecycle()
    val queueState by viewModel.queueState.collectAsStateWithLifecycle()
    val elapsedTime by viewModel.elapsedTime.collectAsStateWithLifecycle()

    val currentTrack = queueState?.currentItem?.track
    val title = currentTrack?.name ?: player?.currentMedia?.title ?: "No track"
    val artist = currentTrack?.artistNames ?: player?.currentMedia?.artist ?: ""
    val album = currentTrack?.albumName ?: player?.currentMedia?.album ?: ""
    val imageUrl = currentTrack?.imageUrl ?: queueState?.currentItem?.imageUrl
        ?: player?.currentMedia?.imageUrl
    val duration = currentTrack?.duration ?: queueState?.currentItem?.duration
        ?: player?.currentMedia?.duration ?: 0.0
    val isPlaying = player?.state == PlaybackState.PLAYING

    val isDark = isSystemInDarkTheme()
    val surfaceColor = MaterialTheme.colorScheme.surface
    val dominantColor by extractDominantColor(imageUrl, isDark)
    val animatedColor by animateColorAsState(
        targetValue = dominantColor,
        animationSpec = tween(durationMillis = 800),
        label = "bg_color"
    )
    val gradientAlpha = if (isDark) 0.35f else 0.25f
    val gradient = Brush.verticalGradient(
        colors = listOf(animatedColor.copy(alpha = gradientAlpha), surfaceColor)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(player?.displayName ?: "Now Playing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToQueue) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.QueueMusic, contentDescription = "Queue")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(surfaceColor)
                .background(gradient)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Artwork with swipe
                SwipeableAlbumArt(
                    imageUrl = imageUrl,
                    onNext = { viewModel.next() },
                    onPrevious = { viewModel.previous() }
                )

                Spacer(modifier = Modifier.height(48.dp))

                // Track info + favorite
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 48.dp)
                            .basicMarquee(iterations = Int.MAX_VALUE, velocity = 60.dp)
                    )
                    IconButton(
                        onClick = { viewModel.toggleFavorite() },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Icon(
                            if (currentTrack?.favorite == true) Icons.Default.Favorite
                            else Icons.Default.FavoriteBorder,
                            contentDescription = "Toggle favorite",
                            tint = if (currentTrack?.favorite == true) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                val artistClickable =
                    currentTrack?.artistItemId != null && currentTrack.artistProvider != null
                Text(
                    text = artist,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (artistClickable) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (artistClickable) Modifier.clickable {
                        onNavigateToArtist(
                            currentTrack.artistItemId!!,
                            currentTrack.artistProvider!!,
                            artist
                        )
                    } else Modifier
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (album.isNotBlank()) {
                    val albumClickable =
                        currentTrack?.albumItemId != null && currentTrack.albumProvider != null
                    Text(
                        text = album,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (albumClickable) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (albumClickable) Modifier.clickable {
                            onNavigateToAlbum(
                                currentTrack.albumItemId!!,
                                currentTrack.albumProvider!!,
                                album
                            )
                        } else Modifier
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Seek bar
                SeekBar(
                    elapsed = elapsedTime,
                    duration = duration,
                    onSeek = { viewModel.seek(it) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Transport controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.toggleShuffle() }) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (queueState?.shuffleEnabled == true)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = { viewModel.previous() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    FilledIconButton(
                        onClick = { viewModel.playPause() },
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.next() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next",
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(onClick = { viewModel.cycleRepeat() }) {
                        Icon(
                            when (queueState?.repeatMode) {
                                RepeatMode.ONE -> Icons.Default.RepeatOne
                                else -> Icons.Default.Repeat
                            },
                            contentDescription = "Repeat",
                            tint = if (queueState?.repeatMode != RepeatMode.OFF)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Volume
                VolumeSlider(
                    volume = player?.volumeLevel ?: 0,
                    isMuted = player?.volumeMuted ?: false,
                    onVolumeChange = { viewModel.setVolume(it) },
                    onMuteToggle = { viewModel.toggleMute() }
                )
            }
        }
    }
}

@Composable
private fun SwipeableAlbumArt(
    imageUrl: String?,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var containerWidth by remember { mutableIntStateOf(1) }

    val shape = MaterialTheme.shapes.medium

    Box(
        modifier = Modifier
            .fillMaxWidth(0.75f)
            .aspectRatio(1f)
            .pointerInput(Unit) {
                containerWidth = size.width
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val threshold = containerWidth * 0.25f
                        val current = offsetX.value
                        scope.launch {
                            if (current < -threshold) {
                                offsetX.animateTo(
                                    -containerWidth.toFloat(),
                                    animationSpec = tween(150)
                                )
                                onNext()
                                offsetX.snapTo(containerWidth.toFloat())
                                offsetX.animateTo(0f, animationSpec = tween(200))
                            } else if (current > threshold) {
                                offsetX.animateTo(
                                    containerWidth.toFloat(),
                                    animationSpec = tween(150)
                                )
                                onPrevious()
                                offsetX.snapTo(-containerWidth.toFloat())
                                offsetX.animateTo(0f, animationSpec = tween(200))
                            } else {
                                offsetX.animateTo(0f, animationSpec = tween(200))
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch { offsetX.animateTo(0f, animationSpec = tween(200)) }
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                    }
                )
            }
    ) {
        val progress = if (containerWidth > 0) {
            (offsetX.value / containerWidth).coerceIn(-1f, 1f)
        } else {
            0f
        }
        val scale = 1f - 0.05f * kotlin.math.abs(progress)
        val alpha = 1f - 0.3f * kotlin.math.abs(progress)

        AsyncImage(
            model = imageUrl,
            contentDescription = "Album art",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offsetX.value
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .clip(shape),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun extractDominantColor(imageUrl: String?, isDark: Boolean): State<Color> {
    val context = LocalContext.current
    val colorState = remember { mutableStateOf(Color.Transparent) }

    LaunchedEffect(imageUrl, isDark) {
        if (imageUrl == null) {
            colorState.value = Color.Transparent
            return@LaunchedEffect
        }

        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .size(128)
            .allowHardware(false)
            .memoryCacheKey("palette_$imageUrl")
            .build()

        val result = context.imageLoader.execute(request)
        if (result is SuccessResult) {
            val bitmap = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bitmap != null) {
                colorState.value = extractColor(bitmap, isDark)
            } else {
                colorState.value = Color.Transparent
            }
        } else {
            colorState.value = Color.Transparent
        }
    }

    return colorState
}

private fun extractColor(bitmap: Bitmap, isDark: Boolean): Color {
    val palette = Palette.from(bitmap).generate()

    val swatch = if (isDark) {
        palette.darkMutedSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch
    } else {
        palette.mutedSwatch ?: palette.lightMutedSwatch ?: palette.dominantSwatch
    }

    if (swatch == null) return Color.Transparent

    val r = swatch.rgb.red
    val g = swatch.rgb.green
    val b = swatch.rgb.blue

    val hsl = FloatArray(3)
    ColorUtils.RGBToHSL(r, g, b, hsl)

    // Clamp lightness to avoid too bright or too dark colors
    hsl[2] = hsl[2].coerceIn(0.2f, 0.6f)
    hsl[1] = hsl[1].coerceIn(0.3f, 0.7f)

    val clamped = ColorUtils.HSLToColor(hsl)

    return Color(clamped)
}

@Composable
private fun SeekBar(
    elapsed: Double,
    duration: Double,
    onSeek: (Double) -> Unit
) {
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(0f) }
    var seekTarget by remember { mutableFloatStateOf(-1f) }

    // Release hold once server position catches up to the seek target
    if (seekTarget >= 0f && !seeking) {
        if (kotlin.math.abs(elapsed.toFloat() - seekTarget) < 2f) {
            seekTarget = -1f
        }
    }

    val displayValue = when {
        seeking -> seekValue
        seekTarget >= 0f -> seekTarget
        else -> elapsed.toFloat()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = displayValue,
            onValueChange = {
                seeking = true
                seekValue = it
            },
            onValueChangeFinished = {
                onSeek(seekValue.toDouble())
                seekTarget = seekValue
                seeking = false
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatTime(displayValue.toDouble()), style = MaterialTheme.typography.bodySmall)
            Text(formatTime(duration), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatTime(seconds: Double): String {
    val totalSeconds = seconds.toInt()
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return "%d:%02d".format(mins, secs)
}
