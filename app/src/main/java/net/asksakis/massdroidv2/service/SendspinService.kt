package net.asksakis.massdroidv2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.asksakis.massdroidv2.R
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.sendspin.SendspinState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.ui.MainActivity
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class SendspinService : Service() {

    companion object {
        private const val TAG = "SendspinService"
        const val ACTION_START = "net.asksakis.massdroidv2.SENDSPIN_START"
        const val ACTION_STOP = "net.asksakis.massdroidv2.SENDSPIN_STOP"
        private const val ACTION_PLAY_PAUSE = "net.asksakis.massdroidv2.SENDSPIN_PLAY_PAUSE"
        private const val ACTION_NEXT = "net.asksakis.massdroidv2.SENDSPIN_NEXT"
        private const val ACTION_PREV = "net.asksakis.massdroidv2.SENDSPIN_PREV"
        private const val CHANNEL_ID = "sendspin_channel"
        private const val NOTIFICATION_ID = 2
    }

    private data class MediaInfo(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val isPlaying: Boolean,
        val artUrl: String?
    )

    @Inject lateinit var sendspinManager: SendspinManager
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var playerRepository: PlayerRepository
    @Inject lateinit var wsClient: MaWebSocketClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Audio focus
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private var hasAudioFocus = false

    // Noisy audio receiver (headset unplug)
    private var noisyReceiverRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.d(TAG, "Audio becoming noisy (headset unplugged), pausing")
                val id = playerRepository.selectedPlayer.value?.playerId ?: return
                scope.launch { playerRepository.pause(id) }
            }
        }
    }

    private var currentArt: Bitmap? = null
    private var currentArtUrl: String? = null
    private var isStreaming = false
    private var wasPlayingBeforeDisconnect = false
    private var currentTitle = ""
    private var currentArtist = ""
    private var currentAlbum = ""
    private var currentDurationMs = 0L
    private var currentPositionMs = 0L
    private var currentIsPlaying = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        setupMediaSession()
        setupAudioFocus()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSendspin()
            ACTION_STOP -> stopSendspin()
            ACTION_PLAY_PAUSE -> {
                val id = playerRepository.selectedPlayer.value?.playerId ?: return START_STICKY
                scope.launch { playerRepository.playPause(id) }
            }
            ACTION_NEXT -> {
                val id = playerRepository.selectedPlayer.value?.playerId ?: return START_STICKY
                scope.launch { playerRepository.next(id) }
            }
            ACTION_PREV -> {
                val id = playerRepository.selectedPlayer.value?.playerId ?: return START_STICKY
                scope.launch { playerRepository.previous(id) }
            }
        }
        return START_STICKY
    }

    private fun setupAudioFocus() {
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttrs)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Log.d(TAG, "Audio focus gained")
                        hasAudioFocus = true
                        if (isStreaming) {
                            sendspinManager.resumeAudio()
                            sendspinManager.setVolume(100)
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        Log.d(TAG, "Audio focus lost permanently")
                        hasAudioFocus = false
                        if (isStreaming) {
                            val id = playerRepository.selectedPlayer.value?.playerId
                            if (id != null) {
                                scope.launch { playerRepository.pause(id) }
                            }
                        }
                        sendspinManager.pauseAudio()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Log.d(TAG, "Audio focus lost transiently")
                        hasAudioFocus = false
                        if (isStreaming) {
                            sendspinManager.pauseAudio()
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        Log.d(TAG, "Audio focus: ducking")
                        sendspinManager.setVolume(30)
                    }
                }
            }
            .build()
    }

    private fun requestAudioFocus(): Boolean {
        val result = audioManager.requestAudioFocus(focusRequest)
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d(TAG, "Audio focus request: ${if (hasAudioFocus) "granted" else "denied"}")
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasAudioFocus = false
        Log.d(TAG, "Audio focus abandoned")
    }

    private fun registerNoisyReceiver() {
        if (!noisyReceiverRegistered) {
            registerReceiver(
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            )
            noisyReceiverRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (noisyReceiverRegistered) {
            try {
                unregisterReceiver(noisyReceiver)
            } catch (_: Exception) {}
            noisyReceiverRegistered = false
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "MassDroidSpeaker").apply {
            @Suppress("DEPRECATION")
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onStop() {
                    stopSendspin()
                }
                override fun onPlay() {
                    val id = playerRepository.selectedPlayer.value?.playerId ?: return
                    scope.launch { playerRepository.play(id) }
                }
                override fun onPause() {
                    val id = playerRepository.selectedPlayer.value?.playerId ?: return
                    scope.launch { playerRepository.pause(id) }
                }
                override fun onSkipToNext() {
                    val id = playerRepository.selectedPlayer.value?.playerId ?: return
                    scope.launch { playerRepository.next(id) }
                }
                override fun onSkipToPrevious() {
                    val id = playerRepository.selectedPlayer.value?.playerId ?: return
                    scope.launch { playerRepository.previous(id) }
                }
                override fun onSeekTo(pos: Long) {
                    val id = playerRepository.selectedPlayer.value?.playerId ?: return
                    scope.launch { playerRepository.seek(id, pos / 1000.0) }
                }
            })
            isActive = true
        }
    }

    private fun startSendspin() {
        // Request audio focus before starting
        requestAudioFocus()
        registerNoisyReceiver()

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Observe connection state
        scope.launch {
            sendspinManager.connectionState.collect { state ->
                val wasStreaming = isStreaming
                isStreaming = state == SendspinState.STREAMING
                // Track if sendspin dropped while actively streaming (for auto-resume)
                if (wasStreaming && !isStreaming) {
                    wasPlayingBeforeDisconnect = true
                    Log.d(TAG, "Sendspin dropped while streaming, marking for auto-resume")
                }
                if (!isStreaming) {
                    currentTitle = when (state) {
                        SendspinState.STREAMING -> ""
                        SendspinState.SYNCING -> "Ready"
                        SendspinState.HANDSHAKING,
                        SendspinState.AUTHENTICATING,
                        SendspinState.CONNECTING -> "Connecting..."
                        SendspinState.ERROR -> "Connection error"
                        SendspinState.DISCONNECTED -> "Disconnected"
                    }
                    currentArtist = ""
                    currentAlbum = ""
                    currentDurationMs = 0
                    currentPositionMs = 0
                    currentIsPlaying = false
                    updateMediaSession()
                    updateNotification()
                }
            }
        }

        // Observe track metadata changes ONLY (no elapsed time!)
        scope.launch {
            combine(
                playerRepository.selectedPlayer,
                playerRepository.queueState
            ) { player, queue ->
                if (!isStreaming || player == null) return@combine null
                val track = queue?.currentItem?.track
                MediaInfo(
                    title = track?.name ?: player.currentMedia?.title ?: "MassDroid Speaker",
                    artist = track?.artistNames ?: player.currentMedia?.artist ?: "",
                    album = track?.albumName ?: player.currentMedia?.album ?: "",
                    durationMs = ((track?.duration ?: queue?.currentItem?.duration
                        ?: player.currentMedia?.duration ?: 0.0) * 1000).toLong(),
                    isPlaying = player.state == PlaybackState.PLAYING,
                    artUrl = queue?.currentItem?.imageUrl ?: player.currentMedia?.imageUrl
                )
            }.distinctUntilChanged().collect { info ->
                if (info == null) return@collect

                val metadataChanged = info.title != currentTitle ||
                        info.artist != currentArtist ||
                        info.album != currentAlbum ||
                        info.durationMs != currentDurationMs
                val stateChanged = info.isPlaying != currentIsPlaying

                val artChanged = info.artUrl != currentArtUrl

                currentTitle = info.title
                currentArtist = info.artist
                currentAlbum = info.album
                currentDurationMs = info.durationMs
                currentIsPlaying = info.isPlaying

                if (artChanged) {
                    currentArtUrl = info.artUrl
                    currentArt = loadArt(info.artUrl)
                }

                currentPositionMs = (playerRepository.elapsedTime.value * 1000).toLong()

                updateMediaSession()

                if (metadataChanged || stateChanged || artChanged) {
                    updateNotification()
                }
            }
        }

        // Immediate audio pause/resume via playback intent (fires BEFORE server round-trip)
        scope.launch {
            playerRepository.playbackIntent.collect { willPlay ->
                if (!isStreaming) return@collect
                if (willPlay) {
                    if (!hasAudioFocus) requestAudioFocus()
                    sendspinManager.resumeAudio()
                } else {
                    sendspinManager.pauseAudio()
                }
            }
        }

        // Sync MediaSession state when server confirms state change
        scope.launch {
            playerRepository.selectedPlayer
                .map { it?.state }
                .distinctUntilChanged()
                .collect { state ->
                    if (!isStreaming) return@collect
                    currentIsPlaying = state == PlaybackState.PLAYING
                    currentPositionMs = (playerRepository.elapsedTime.value * 1000).toLong()
                    updateMediaSession()
                }
        }

        // Read settings and start sendspin
        scope.launch {
            val url = settingsRepository.serverUrl.first()
            val token = settingsRepository.authToken.first()
            if (url.isBlank() || token.isBlank()) {
                Log.e(TAG, "No server URL or token, stopping")
                stopSelf()
                return@launch
            }

            var clientId = settingsRepository.sendspinClientId.first()
            if (clientId == null) {
                clientId = UUID.randomUUID().toString()
                settingsRepository.setSendspinClientId(clientId)
            }

            sendspinManager.start(url, token, clientId, "MassDroid")
            Log.d(TAG, "Sendspin started via service")
        }

        // Resume playback when MA reconnects after a drop (only if was playing before)
        scope.launch {
            var connectedBefore = false
            wsClient.connectionState.collect { state ->
                val isConnected = state is net.asksakis.massdroidv2.data.websocket.ConnectionState.Connected
                if (isConnected && connectedBefore) {
                    val url = settingsRepository.serverUrl.first()
                    val token = wsClient.authToken ?: settingsRepository.authToken.first()
                    var clientId = settingsRepository.sendspinClientId.first()
                    if (clientId == null) {
                        clientId = UUID.randomUUID().toString()
                        settingsRepository.setSendspinClientId(clientId)
                    }

                    // Only restart sendspin if it's idle (not mid-connection or streaming)
                    val currentSsState = sendspinManager.connectionState.value
                    if (currentSsState == SendspinState.DISCONNECTED || currentSsState == SendspinState.ERROR) {
                        Log.d(TAG, "MA reconnected, sendspin is $currentSsState, restarting")
                        if (url.isNotBlank() && token.isNotBlank()) {
                            sendspinManager.start(url, token, clientId, "MassDroid")
                        }
                    } else {
                        Log.d(TAG, "MA reconnected, sendspin already $currentSsState, skipping restart")
                    }

                    val playerId = playerRepository.selectedPlayer.value?.playerId
                    Log.d(TAG, "Reconnect: selectedPlayerId=$playerId, clientId=$clientId, wasPlaying=$wasPlayingBeforeDisconnect")
                    if (playerId != null && wasPlayingBeforeDisconnect) {
                        wasPlayingBeforeDisconnect = false
                        // Wait for sendspin handshake
                        val ssState = sendspinManager.connectionState
                            .first { it == SendspinState.SYNCING || it == SendspinState.STREAMING }
                        Log.d(TAG, "Reconnect: sendspin reached $ssState")
                        // Wait for server to update player state (leaves stale PLAYING)
                        val readyPlayer = kotlinx.coroutines.withTimeoutOrNull(5000) {
                            playerRepository.players
                                .map { list -> list.find { it.playerId == clientId } }
                                .first { it != null && it.state != PlaybackState.PLAYING }
                        }
                        Log.d(TAG, "Reconnect: player state=${readyPlayer?.state ?: "timeout"}")
                        Log.d(TAG, "Resuming playback on $playerId after reconnect")
                        try {
                            playerRepository.play(playerId)
                            Log.d(TAG, "Reconnect: play command sent successfully")
                        } catch (e: Exception) {
                            Log.w(TAG, "Resume playback failed: ${e.message}")
                        }
                    } else if (playerId != null) {
                        Log.d(TAG, "Reconnect: skipping auto-resume, was not playing before disconnect")
                        wasPlayingBeforeDisconnect = false
                    }
                }
                if (isConnected) connectedBefore = true
            }
        }
    }

    private suspend fun loadArt(url: String?): Bitmap? {
        if (url == null) return null
        return withContext(Dispatchers.IO) {
            try {
                val client = wsClient.getHttpClient()
                val request = okhttp3.Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                response.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load album art: ${e.message}")
                null
            }
        }
    }

    private fun updateMediaSession() {
        val actions = PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO

        val pbState = if (currentIsPlaying) PlaybackStateCompat.STATE_PLAYING
        else if (isStreaming) PlaybackStateCompat.STATE_PAUSED
        else PlaybackStateCompat.STATE_BUFFERING

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(pbState, currentPositionMs, if (currentIsPlaying) 1f else 0f)
        mediaSession?.setPlaybackState(stateBuilder.build())

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAlbum)
        if (currentDurationMs > 0) {
            metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDurationMs)
        }
        currentArt?.let {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        }
        mediaSession?.setMetadata(metadataBuilder.build())
    }

    private fun stopSendspin() {
        wasPlayingBeforeDisconnect = false
        abandonAudioFocus()
        unregisterNoisyReceiver()
        sendspinManager.stop()
        mediaSession?.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Sendspin service stopped")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MassDroid Speaker",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when MassDroid is acting as a speaker"
            setShowBadge(false)
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun actionIntent(action: String, code: Int): PendingIntent =
            PendingIntent.getService(
                this, code,
                Intent(this, SendspinService::class.java).apply { this.action = action },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        val session = mediaSession ?: return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MassDroid Speaker")
            .setSmallIcon(R.drawable.ic_notification)
            .build()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle.ifEmpty { "MassDroid Speaker" })
            .setContentText(currentArtist.ifEmpty { if (isStreaming) "Streaming" else "" })
            .setSubText(currentAlbum)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        currentArt?.let { builder.setLargeIcon(it) }

        builder.addAction(androidx.media3.session.R.drawable.media3_icon_previous, "Previous", actionIntent(ACTION_PREV, 10))

        val playPauseIcon = if (currentIsPlaying)
            androidx.media3.session.R.drawable.media3_icon_pause
        else
            androidx.media3.session.R.drawable.media3_icon_play
        val playPauseLabel = if (currentIsPlaying) "Pause" else "Play"
        builder.addAction(playPauseIcon, playPauseLabel, actionIntent(ACTION_PLAY_PAUSE, 11))

        builder.addAction(androidx.media3.session.R.drawable.media3_icon_next, "Next", actionIntent(ACTION_NEXT, 12))
        builder.addAction(androidx.media3.session.R.drawable.media3_icon_stop, "Stop", actionIntent(ACTION_STOP, 13))

        builder.setStyle(
            MediaNotificationCompat.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
                .setShowCancelButton(true)
                .setCancelButtonIntent(actionIntent(ACTION_STOP, 14))
        )

        return builder.build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    @Suppress("WakelockTimeout")
    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MassDroid::Sendspin")
        wakeLock?.acquire()

        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MassDroid::Sendspin")
        wifiLock?.acquire()
    }

    override fun onDestroy() {
        abandonAudioFocus()
        unregisterNoisyReceiver()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        mediaSession?.release()
        mediaSession = null
        scope.cancel()
        sendspinManager.stop()
        super.onDestroy()
    }
}
