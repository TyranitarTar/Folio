package app.folio.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

data class AudioPlaybackState(
    val id: String? = null,
    val bookId: String? = null,
    val title: String = "",
    val trackIndex: Int = 0,
    val trackTitle: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val speed: Float = 1f,
    val error: String? = null,
    val cue: AudioCue? = null,
    val sleepRemainingSeconds: Long? = null,
    val seekRevision: Long = 0L,
    val sleepLabel: String? = null
)

/** Process-local playback controls. Progress and bookmarks are owned by AudiobookStore. */
object AudiobookController {
    internal val mutable = MutableStateFlow(AudioPlaybackState())
    val state = mutable.asStateFlow()
    @Volatile internal var serviceRunning = false
    @Volatile internal var startPending = false
    internal val snapshotSwapLock = Any()

    private fun intent(context: Context, action: String) = Intent(context, AudiobookService::class.java).setAction(action)

    fun start(context: Context, id: String, track: Int? = null, positionMs: Long? = null, autoplay: Boolean = true): Unit = synchronized(snapshotSwapLock) {
        val previous = mutable.value
        // Preserve the current ID and position until the service checkpoints them.
        mutable.value = previous.copy(loading = true, playing = false, error = null)
        startPending = true
        SpeechController.stop(context)
        val request = intent(context, AudiobookService.START).putExtra("id", id).putExtra("track", track ?: -1)
            .putExtra("position", positionMs ?: -1L).putExtra("autoplay", autoplay)
        runCatching { ContextCompat.startForegroundService(context, request) }.onFailure {
            startPending = false
            mutable.value = previous.copy(error = "The audiobook could not start. Open Folio and try again.")
        }
        Unit
    }

    fun play(context: Context) = command(context, AudiobookService.PLAY)
    fun pause(context: Context) = command(context, AudiobookService.PAUSE)
    fun stop(context: Context) = command(context, AudiobookService.STOP)
    fun next(context: Context) = command(context, AudiobookService.NEXT)
    fun previous(context: Context) = command(context, AudiobookService.PREVIOUS)
    fun rewind(context:Context)=command(context,AudiobookService.REWIND)
    fun forward(context:Context)=command(context,AudiobookService.FORWARD)
    fun skip(context:Context,seconds:Int)=command(context,AudiobookService.SKIP){putExtra("seconds",seconds.coerceIn(-3600,3600))}
    fun seek(context: Context, positionMs: Long) = command(context, AudiobookService.SEEK) { putExtra("position", positionMs) }
    fun seekTrack(context: Context, track: Int, positionMs: Long) = command(context, AudiobookService.SEEK_TRACK) {
        putExtra("track", track).putExtra("position", positionMs)
    }
    fun speed(context: Context, speed: Float) = command(context, AudiobookService.SPEED) { putExtra("speed", speed) }
    fun sleep(context: Context, minutes: Int, fade: Boolean=false) = command(context, AudiobookService.SLEEP) { putExtra("minutes", minutes);putExtra("fade",fade) }
    fun sleepBoundary(context: Context, passage: Boolean) = command(context,AudiobookService.SLEEP_BOUNDARY) { putExtra("passage",passage) }
    fun extendSleep(context: Context) = command(context,AudiobookService.EXTEND_SLEEP)
    fun clearSleep(context: Context) = command(context, AudiobookService.CLEAR_SLEEP)

    private inline fun command(context: Context, action: String, extras: Intent.() -> Unit = {}) {
        if (mutable.value.id == null && !serviceRunning && !startPending) return
        runCatching { context.startService(intent(context, action).apply(extras)) }
            .onFailure { mutable.value = mutable.value.copy(error = "The audiobook control could not be applied.") }
    }
}

/** Foreground local-file player with media controls, focus handling and durable checkpoints. */
class AudiobookService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { (application as FolioApp).audiobooks }
    private lateinit var audio: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var focusGeneration = -1L
    private var focusToken = 0L
    private lateinit var session: MediaSession
    private lateinit var wakeLock: PowerManager.WakeLock
    private var focusHeld = false
    private var focusInterrupted=false
    private var ducked=false
    private val interruption=AudioInterruptionGate()
    private var player: MediaPlayer? = null
    private var prepared = false
    private var seekInFlight = false
    private var seekTargetMs = 0L
    private var seekRetries = 0
    private var pendingAutoplay = false
    private var generation = 0L
    private var loadingJob: Job? = null
    private var tickerJob: Job? = null
    private var checkpointJob: Job? = null
    private val checkpointMutex = Mutex()
    private var lastObservedPosition = 0L
    private var lastCheckpointAt = 0L
    private var sleepEndElapsed: Long? = null
    private var sleepJob: Job? = null
    private var sleepBoundary: AudioSleepBoundary? = null
    private var sleepFade = false
    private var sleepVolume = 1f
    private var boundaryResume: AudioPosition? = null
    private var currentBook: Audiobook? = null
    private var pendingSpeed: Float? = null
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                interruption.cancel()
                if(AudioPreferences.load(this@AudiobookService).pauseOnDisconnect)pausePlayback()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AudiobookController.serviceRunning = true
        AudiobookController.startPending = false
        audio = getSystemService(AudioManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Folio:audiobook")
            .apply { setReferenceCounted(false) }
        session = MediaSession(this, "Folio audiobook").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onStop() = stopPlayback()
                override fun onSkipToNext() {val p=AudioPreferences.load(this@AudiobookService);if(p.headsetChapters)nextTrack() else skipSeconds(p.forwardSeconds)}
                override fun onSkipToPrevious() {val p=AudioPreferences.load(this@AudiobookService);if(p.headsetChapters)previousTrack() else skipSeconds(-p.backSeconds)}
                override fun onRewind() = skipSeconds(-AudioPreferences.load(this@AudiobookService).backSeconds)
                override fun onFastForward() = skipSeconds(AudioPreferences.load(this@AudiobookService).forwardSeconds)
                override fun onSeekTo(pos: Long) = seekTo(pos)
            })
            isActive = true
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Audiobook playback", NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        tickerJob = scope.launch { ticker() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                AudiobookController.startPending = false
                // Publish the foreground notification before catalog or audio-file work begins.
                startForeground(NOTIFICATION, notification())
                val id = intent.getStringExtra("id")
                if (id.isNullOrBlank()) {
                    fail("The selected audiobook is unavailable.")
                } else requestStart(id, intent.getIntExtra("track", -1).takeIf { it >= 0 },
                    intent.getLongExtra("position", -1L).takeIf { it >= 0L }, intent.getBooleanExtra("autoplay", true))
            }
            PLAY -> resumePlayback()
            PAUSE -> pausePlayback()
            STOP -> stopPlayback()
            NEXT -> nextTrack()
            PREVIOUS -> previousTrack()
            REWIND -> skipSeconds(-AudioPreferences.load(this).backSeconds)
            FORWARD -> skipSeconds(AudioPreferences.load(this).forwardSeconds)
            SKIP -> skipSeconds(intent.getIntExtra("seconds",0).coerceIn(-3600,3600))
            SEEK -> seekTo(intent.getLongExtra("position", 0L))
            SEEK_TRACK -> seekTrack(intent.getIntExtra("track", -1), intent.getLongExtra("position", 0L))
            SPEED -> setSpeed(intent.getFloatExtra("speed", 1f))
            SLEEP -> setSleep(intent.getIntExtra("minutes", 0),intent.getBooleanExtra("fade",false))
            SLEEP_BOUNDARY -> setSleepBoundary(intent.getBooleanExtra("passage",false))
            EXTEND_SLEEP -> {
                sleepEndElapsed?.let { sleepEndElapsed=maxOf(it,SystemClock.elapsedRealtime())+5*60_000L;sleepVolume=1f;applyVolume() }
            }
            CLEAR_SLEEP -> setSleep(0)
        }
        return START_NOT_STICKY
    }

    private fun requestStart(id: String, requestedTrack: Int?, requestedPosition: Long?, autoplay: Boolean) {
        checkpoint()
        boundaryResume=null
        pendingSpeed = null
        val ticket = ++generation
        loadingJob?.cancel()
        releasePlayer()
        releaseFocusAndWake()
        setSleep(0)
        currentBook = null
        pendingAutoplay = autoplay
        AudiobookController.mutable.value = AudioPlaybackState(id = id, loading = true, playing = false)
        publish()
        loadingJob = scope.launch {
            try {
                val stopDeadline = SystemClock.elapsedRealtime() + 3_000L
                while (SpeechController.serviceRunning && SystemClock.elapsedRealtime() < stopDeadline) delay(50L)
                if (SpeechController.serviceRunning) throw IllegalStateException("Speech playback is still stopping. Try again in a moment.")
                store.load()
                val beforeMetadata=store.books.value.firstOrNull{it.id==id} ?: throw IllegalArgumentException("This audiobook is no longer in your library.")
                store.prepareTrack(id,requestedTrack?.coerceIn(0,beforeMetadata.tracks.lastIndex) ?: beforeMetadata.positionTrack)
                val storedBook = store.books.value.firstOrNull { it.id == id }
                    ?: throw IllegalArgumentException("This audiobook is no longer in your library.")
                if (ticket != generation) return@launch
                val book = pendingSpeed?.let { storedBook.copy(speed = it) } ?: storedBook
                currentBook = book
                val index = requestedTrack?.coerceIn(0, book.tracks.lastIndex) ?: book.positionTrack.coerceIn(0, book.tracks.lastIndex)
                var position = requestedPosition ?: if (index == book.positionTrack) book.positionMs else 0L
                if(autoplay && requestedPosition==null && requestedTrack==null)position=rewoundPosition(id,position)
                openTrack(book, index, position, autoplay, ticket)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (ticket == generation) fail(error.message ?: "The audiobook could not be loaded.")
            }
        }
    }

    private fun openTrack(book: Audiobook, index: Int, position: Long, autoplay: Boolean, ticket: Long = ++generation) {
        if (ticket != generation || index !in book.tracks.indices) return
        loadingJob?.cancel(); loadingJob = null
        releasePlayer()
        currentBook = book
        pendingAutoplay = autoplay
        val track = book.tracks[index]
        if(track.metadataVersion<AUDIO_METADATA_VERSION)scope.launch{runCatching{store.prepareTrack(book.id,index)}}
        val targetPosition = position.coerceAtLeast(0L).let { if (track.durationMs > 0L) it.coerceAtMost(track.durationMs) else it }
        seekInFlight = false
        seekTargetMs = targetPosition
        seekRetries = 0
        val initial = AudioPlaybackState(book.id, book.bookId, book.title, index, track.title, targetPosition,
            track.durationMs, false, true, book.speed, null, cueAt(track, targetPosition), AudiobookController.mutable.value.sleepRemainingSeconds,
            AudiobookController.mutable.value.seekRevision, AudiobookController.mutable.value.sleepLabel)
        AudiobookController.mutable.value = initial
        lastObservedPosition = targetPosition
        lastCheckpointAt = SystemClock.elapsedRealtime()
        checkpoint()
        publish()
        val media = MediaPlayer()
        player = media
        media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        media.setOnPreparedListener { preparedMedia ->
            if (!accepts(ticket, preparedMedia)) { runCatching { preparedMedia.release() }; return@setOnPreparedListener }
            prepared = true
            val actualDuration = runCatching { preparedMedia.duration.toLong().coerceAtLeast(0L) }.getOrDefault(0L)
            val duration = actualDuration.takeIf { it > 0L } ?: track.durationMs
            if(book.sourceRoot!=null && actualDuration>0)scope.launch { runCatching { store.decodedDuration(book.id,index,actualDuration) } }
            seekTargetMs = seekTargetMs.coerceAtLeast(0L).let { if (duration > 0L) it.coerceAtMost(duration) else it }
            if (seekTargetMs > 0L) requestPreparedSeek(ticket, preparedMedia, track, duration, seekTargetMs)
            else finishPreparedTrack(ticket, preparedMedia, track, duration, 0L)
        }
        media.setOnCompletionListener { completed ->
            if (accepts(ticket, completed) && !seekInFlight) completedTrack(ticket, book, index)
        }
        media.setOnErrorListener { failed, _, _ ->
            if (accepts(ticket, failed)) handleMediaError(ticket)
            true
        }
        runCatching {
            if(track.sourceUri!=null)media.setDataSource(this,android.net.Uri.parse(track.sourceUri))
            else media.setDataSource(store.file(book.id, track.file).absolutePath)
            media.prepareAsync()
        }.onFailure { if (accepts(ticket, media)) handleMediaError(ticket) }
    }

    private fun beginPlayback(ticket: Long, target: MediaPlayer?) {
        val media = target ?: return
        if (!accepts(ticket, media) || !prepared || seekInFlight) return
        if (!requestFocus()) {
            pendingAutoplay=false
            AudiobookController.mutable.value = AudiobookController.mutable.value.copy(playing = false, loading = false,
                error = "Another app is using audio. Pause it, then try again.")
            publish(); return
        }
        runCatching {
            applyVolume()
            if (!media.isPlaying) media.start()
            media.setPlaybackParams(PlaybackParams().setSpeed((currentBook?.speed ?: AudiobookController.mutable.value.speed).coerceIn(.5f, 3f)))
            if (!wakeLock.isHeld) wakeLock.acquire()
            pendingAutoplay = false
            lastObservedPosition = runCatching { media.currentPosition.toLong().coerceAtLeast(0L) }.getOrDefault(lastObservedPosition)
            AudiobookController.mutable.value = AudiobookController.mutable.value.copy(playing = true, loading = false, error = null)
            currentBook?.id?.let{id->scope.launch{runCatching{store.markStarted(id)}}}
            publish()
        }.onFailure { handleMediaError(ticket) }
    }

    private fun requestFocus(): Boolean {
        if (focusHeld) return !focusInterrupted
        val ticket = generation
        val token = ++focusToken
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes).setWillPauseWhenDucked(AudioPreferences.load(this).pauseForNotifications)
            .setOnAudioFocusChangeListener { change ->
                scope.launch {
                    if(!focusHeld || generation!=ticket || focusGeneration!=ticket || focusToken!=token)return@launch
                    val options=AudioPreferences.load(this@AudiobookService)
                    when(change) {
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            focusInterrupted=false
                            if(ducked){ducked=false;applyVolume()}
                            if(interruption.regain(ticket) && options.resumeAfterInterruption)resumePlayback(fromFocus=true)
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK && !options.pauseForNotifications) {
                                ducked=true;applyVolume()
                            } else {
                                val wasPlaying=AudiobookController.mutable.value.playing || pendingAutoplay
                                if(!focusInterrupted)interruption.interrupt(ticket,wasPlaying,options.resumeAfterInterruption)
                                focusInterrupted=true;pausePlayback(keepFocus=true)
                            }
                        }
                        AudioManager.AUDIOFOCUS_LOSS -> pausePlayback()
                    }
                }
            }.build()
        focusRequest = request
        focusGeneration = ticket
        focusHeld = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focusHeld) {
            focusRequest = null
            focusGeneration = -1L
            focusToken++
        }
        return focusHeld
    }

    private fun resumePlayback(fromFocus:Boolean=false) {
        if(!fromFocus)interruption.cancel()
        val current = AudiobookController.mutable.value
        if (current.id == null) return
        boundaryResume?.let { next ->
            boundaryResume=null
            transitionTrack(next.track,next.positionMs,true)
            return
        }
        pendingAutoplay = true
        val rewind=rewoundPosition(current.id,current.positionMs)
        if(rewind!=current.positionMs){seekTo(rewind);return}
        if (currentBook != null) {
            val media = player
            if (media != null && prepared) beginPlayback(generation, media)
            else if (!current.loading) requestStart(current.id, current.trackIndex, current.positionMs, true)
        } else if (!current.loading) requestStart(current.id, current.trackIndex, current.positionMs, true)
    }

    private fun pausePlayback(keepFocus:Boolean=false) {
        if(!keepFocus)interruption.cancel()
        val media = player
        val current = AudiobookController.mutable.value
        pendingAutoplay = false
        if (media != null && prepared) {
            val position = runCatching { media.currentPosition.toLong().coerceAtLeast(0L) }.getOrDefault(current.positionMs)
            runCatching { if (media.isPlaying) media.pause() }
            lastObservedPosition = position
            AudiobookController.mutable.value = current.copy(positionMs = position, playing = false,
                cue = currentBook?.tracks?.getOrNull(current.trackIndex)?.let { cueAt(it, position) }, loading = false)
        } else if (current.loading) {
            AudiobookController.mutable.value = current.copy(playing = false)
        } else if (current.id != null) {
            AudiobookController.mutable.value = current.copy(playing = false)
        }
        if(keepFocus){if(wakeLock.isHeld)runCatching{wakeLock.release()}} else releaseFocusAndWake()
        checkpoint()
        if(current.id!=null && (current.playing || current.loading)) {
            getSharedPreferences("audiobook-pauses",0).edit().putLong(current.id,System.currentTimeMillis()).apply()
            history("Paused")
        }
        publish()
    }

    private fun stopPlayback() {
        checkpoint()
        pendingAutoplay = false
        generation++
        loadingJob?.cancel(); loadingJob = null
        releasePlayer()
        releaseFocusAndWake()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun nextTrack() {
        val book = latestBook() ?: return
        val current = AudiobookController.mutable.value
        val chapters=book.listeningChapters();val index=chapters.currentChapter(current.trackIndex,current.positionMs)
        chapters.getOrNull(index+1)?.let{history("Before chapter jump");if(it.track==current.trackIndex)seekTo(it.startMs) else transitionTrack(it.track,it.startMs,current.playing || pendingAutoplay,explicit=true)}
    }

    private fun previousTrack() {
        val current = AudiobookController.mutable.value
        val book=latestBook() ?: return
        val chapters=book.listeningChapters();val index=chapters.currentChapter(current.trackIndex,current.positionMs);val chapter=chapters.getOrNull(index) ?: return
        if (current.positionMs-chapter.startMs > 3_000L) {
            seekTo(chapter.startMs)
            return
        }
        val previous=chapters.getOrNull(index-1) ?: chapter
        if(previous.track==current.trackIndex)seekTo(previous.startMs) else transitionTrack(previous.track,previous.startMs,current.playing || pendingAutoplay,explicit=true)
    }

    private fun latestBook():Audiobook? { val book=currentBook ?: return null;return store.books.value.firstOrNull{it.id==book.id}?.copy(speed=book.speed) ?: book }
    private fun history(reason:String) { val state=AudiobookController.mutable.value;state.id?.let{id->scope.launch{runCatching{store.recordHistory(id,state.trackIndex,state.positionMs,reason)}}} }
    private fun rewoundPosition(id:String,position:Long):Long {
        val pauses=getSharedPreferences("audiobook-pauses",0);val at=pauses.getLong(id,0);pauses.edit().remove(id).apply()
        val options=AudioPreferences.load(this);val elapsed=System.currentTimeMillis()-at
        return if(options.autoRewind && at>0 && elapsed>=options.afterMinutes*60_000L && elapsed<=30L*24*60*60*1000)
            (position-options.rewindSeconds*1000L).coerceAtLeast(0) else position
    }
    private fun skipSeconds(seconds:Int) {
        val state=AudiobookController.mutable.value;val book=latestBook() ?: return
        if(book.durationKnown) {
            val position=book.positionAt(book.tracks.take(state.trackIndex).sumOf{it.durationMs}+state.positionMs+seconds*1000L)
            if(position.track==state.trackIndex)seekTo(position.positionMs) else seekTrack(position.track,position.positionMs)
        } else seekTo((state.positionMs+seconds*1000L).coerceIn(0,state.durationMs.coerceAtLeast(state.positionMs)))
    }

    private fun transitionTrack(index: Int, position: Long, autoplay: Boolean, explicit: Boolean = false) {
        boundaryResume=null
        val book = currentBook ?: return
        if (index !in book.tracks.indices) return
        checkpoint()
        if (explicit) AudiobookController.mutable.value = AudiobookController.mutable.value.copy(seekRevision = AudiobookController.mutable.value.seekRevision + 1L)
        generation++
        releasePlayer()
        releaseFocusAndWake()
        openTrack(book, index, position, autoplay, generation)
    }

    private fun completedTrack(ticket: Long, book: Audiobook, index: Int) {
        if (ticket != generation) return
        val wasPlaying = AudiobookController.mutable.value.playing && !seekInFlight
        if (!wasPlaying) return
        val duration = AudiobookController.mutable.value.durationMs
        if(sleepBoundary?.reached(index,duration)==true) {
            finishSleepBoundary(index,duration)
            return
        }
        AudiobookController.mutable.value = AudiobookController.mutable.value.copy(positionMs = duration, playing = false, loading = false,
            cue = cueAt(book.tracks[index], duration))
        lastObservedPosition = duration
        checkpoint()
        releasePlayer()
        releaseFocusAndWake()
        if (index + 1 < book.tracks.size) {
            // Completion is the sole automatic track-advance path.
            openTrack(book, index + 1, 0L, true)
        } else {
            if(!book.audioIncomplete)scope.launch{runCatching{store.markCompleted(book.id,book.editionRevision,book.tracks.size)}}
            publish()
        }
    }

    private fun seekTo(positionMs: Long) {
        boundaryResume=null
        val current = AudiobookController.mutable.value
        val book = currentBook ?: return
        val track = book.tracks.getOrNull(current.trackIndex) ?: return
        history("Before seek")
        getSharedPreferences("audiobook-pauses",0).edit().remove(book.id).apply()
        val position = positionMs.coerceAtLeast(0L).let { if (current.durationMs > 0L) it.coerceAtMost(current.durationMs) else it }
        val wasPlaying = current.playing || pendingAutoplay
        val media = player
        seekTargetMs = position
        seekRetries = 0
        pendingAutoplay = wasPlaying
        lastObservedPosition = position
        AudiobookController.mutable.value = current.copy(positionMs = position, cue = cueAt(track, position), error = null,
            playing = false, loading = media == null || !prepared, seekRevision = current.seekRevision + 1L)
        if (media == null) {
            generation++
            openTrack(book, current.trackIndex, position, wasPlaying, generation)
            checkpoint()
            publish()
            return
        }
        if (media != null && prepared) {
            runCatching {
                if (media.isPlaying) media.pause()
                requestPreparedSeek(generation, media, track, current.durationMs, position)
            }.onFailure { failSeek(position) }
        }
        checkpoint()
        publish()
    }

    private fun seekTrack(index: Int, positionMs: Long) {
        val book = currentBook ?: return
        if (index !in book.tracks.indices) return
        history("Before chapter jump")
        getSharedPreferences("audiobook-pauses",0).edit().remove(book.id).apply()
        if(index==AudiobookController.mutable.value.trackIndex){seekTo(positionMs);return}
        transitionTrack(index, positionMs, AudiobookController.mutable.value.playing || pendingAutoplay, explicit = true)
    }

    private fun setSpeed(value: Float) {
        if (!value.isFinite() || value !in .5f..3f) return
        val current = AudiobookController.mutable.value
        val media = player
        if (media != null && prepared && current.playing && !seekInFlight) {
            try { media.setPlaybackParams(PlaybackParams().setSpeed(value)) }
            catch (_: Exception) {
                AudiobookController.mutable.value = current.copy(error = "This playback speed is unavailable. Your previous speed is kept.")
                publish(); return
            }
        }
        currentBook = currentBook?.copy(speed = value)
        if (currentBook == null && current.loading) pendingSpeed = value
        AudiobookController.mutable.value = AudiobookController.mutable.value.copy(speed = value, error = null)
        checkpoint()
        publish()
    }

    private fun applyVolume() {
        val volume=sleepVolume*(if(ducked).2f else 1f)
        runCatching { player?.setVolume(volume,volume) }
    }

    private fun setSleep(minutes: Int, fade: Boolean=false) {
        if(minutes!=0 && (minutes !in 1..240 || AudiobookController.mutable.value.id==null))return
        sleepJob?.cancel();sleepJob=null;sleepEndElapsed=null;sleepBoundary=null
        sleepFade=fade;sleepVolume=1f;applyVolume()
        AudiobookController.mutable.value=AudiobookController.mutable.value.copy(sleepRemainingSeconds=null,sleepLabel=null)
        if(minutes==0){publish();return}
        sleepEndElapsed=SystemClock.elapsedRealtime()+minutes*60_000L
        sleepJob=scope.launch {
            while(isActive) {
                val remaining=((sleepEndElapsed ?: break)-SystemClock.elapsedRealtime()).coerceAtLeast(0)
                AudiobookController.mutable.value=AudiobookController.mutable.value.copy(sleepRemainingSeconds=(remaining+999)/1000,
                    sleepLabel=if(sleepFade)"Timed stop · gentle fade" else "Timed stop")
                sleepVolume=if(sleepFade)(remaining/10_000f).coerceIn(0f,1f) else 1f
                applyVolume();publish()
                if(remaining<=0) {
                    sleepEndElapsed=null
                    pausePlayback()
                    sleepVolume=1f;applyVolume()
                    AudiobookController.mutable.value=AudiobookController.mutable.value.copy(sleepRemainingSeconds=null,sleepLabel=null)
                    publish();break
                }
                delay(minOf(250L,remaining))
            }
        }
        publish()
    }

    private fun setSleepBoundary(passage: Boolean) {
        val current=AudiobookController.mutable.value
        val book=latestBook() ?: return
        if(current.loading)return
        val target=if(passage) {
            val cue=current.cue ?: return
            if(cue.endMs<=current.positionMs)return
            AudioSleepBoundary(current.trackIndex,cue.endMs,"Stop after this passage")
        } else {
            val chapters=book.listeningChapters()
            val chapter=chapters[chapters.currentChapter(current.trackIndex,current.positionMs)]
            val end=if(chapter.endMs>0)chapter.endMs else current.durationMs
            if(end<=current.positionMs)return
            AudioSleepBoundary(current.trackIndex,end,"Stop after this chapter")
        }
        setSleep(0);sleepBoundary=target
        AudiobookController.mutable.value=AudiobookController.mutable.value.copy(sleepLabel=target.label)
        sleepJob=scope.launch {
            while(isActive && sleepBoundary==target) {
                val now=AudiobookController.mutable.value
                if(now.playing && prepared && !seekInFlight) {
                    val at=runCatching { player?.currentPosition?.toLong() }.getOrNull() ?: now.positionMs
                    if(target.reached(now.trackIndex,at)) {finishSleepBoundary(now.trackIndex,at);break}
                }
                delay(50)
            }
        }
        publish()
    }

    private fun finishSleepBoundary(track:Int,position:Long) {
        val target=sleepBoundary ?: return
        // Clear first so completion cannot automatically advance through the stop.
        sleepBoundary=null
        sleepJob?.cancel();sleepJob=null
        pausePlayback()
        AudiobookController.mutable.value=AudiobookController.mutable.value.copy(sleepLabel=null,sleepRemainingSeconds=null)
        if(track==target.track && position>target.positionMs)seekTo(target.positionMs)
        val book=latestBook()
        if(book!=null && target.track<book.tracks.lastIndex && target.positionMs>=book.tracks[target.track].durationMs)
            boundaryResume=AudioPosition(target.track+1,0)
        publish()
    }

    private suspend fun ticker() {
        while (scope.coroutineContext[Job]?.isActive == true) {
            delay(500L)
            val current = AudiobookController.mutable.value
            val media = player
            if (current.id != null && media != null && prepared && !seekInFlight) {
                val position = runCatching { media.currentPosition.toLong().coerceAtLeast(0L) }.getOrNull()
                if (position != null) {
                    val delta = position - lastObservedPosition
                    lastObservedPosition = position
                    if (current.playing && delta in 100L..2_000L) (application as FolioApp).cafeReading.spoken()
                    val book = currentBook
                    val track = book?.tracks?.getOrNull(current.trackIndex)
                    val duration = runCatching { media.duration.toLong().coerceAtLeast(0L) }.getOrDefault(current.durationMs)
                    AudiobookController.mutable.value = current.copy(positionMs = position, durationMs = duration,
                        cue = track?.let { cueAt(it, position) }, sleepRemainingSeconds = sleepEndElapsed?.let { ((it - SystemClock.elapsedRealtime()).coerceAtLeast(0L) / 1000L) })
                    if (current.playing && SystemClock.elapsedRealtime() - lastCheckpointAt >= CHECKPOINT_INTERVAL_MS) checkpoint()
                    publish()
                }
            }
        }
    }

    private fun checkpoint() {
        val current = AudiobookController.mutable.value
        val id = current.id ?: return
        val position = if (seekInFlight) seekTargetMs else player?.let { media ->
            if (prepared) runCatching { media.currentPosition.toLong().coerceAtLeast(0L) }.getOrNull() else null
        } ?: current.positionMs
        val track = current.trackIndex
        val speed = currentBook?.speed ?: current.speed
        val observedAt=System.currentTimeMillis()
        lastCheckpointAt = SystemClock.elapsedRealtime()
        checkpointJob = scope.launch {
            try {
                checkpointMutex.withLock { store.savePosition(id, track, position, speed,observedAt) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (AudiobookController.mutable.value.id == id) {
                    AudiobookController.mutable.value = AudiobookController.mutable.value.copy(error = "The audiobook position could not be saved. Check available storage.")
                    publish()
                }
            }
        }
    }

    private fun releasePlayer() {
        player?.let { media ->
            player = null
            prepared = false
            seekInFlight = false
            runCatching { if (media.isPlaying) media.stop() }
            runCatching { media.reset() }
            runCatching { media.release() }
        }
    }

    private fun releaseFocusAndWake() {
        interruption.cancel();focusInterrupted=false;ducked=false
        applyVolume()
        if (wakeLock.isHeld) runCatching { wakeLock.release() }
        focusToken++
        focusRequest?.let { request -> runCatching { audio.abandonAudioFocusRequest(request) } }
        focusRequest = null
        focusGeneration = -1L
        focusHeld = false
    }

    private fun accepts(ticket: Long, media: MediaPlayer): Boolean = ticket == generation && player === media

    private fun cueAt(track: AudioTrack, position: Long): AudioCue? = track.cues.firstOrNull { position >= it.startMs && position < it.endMs }

    private fun requestPreparedSeek(ticket: Long, media: MediaPlayer, track: AudioTrack, duration: Long, position: Long) {
        if (!accepts(ticket, media)) return
        seekTargetMs = position
        seekInFlight = true
        media.setOnSeekCompleteListener { completed ->
            if (!accepts(ticket, completed) || !seekInFlight) return@setOnSeekCompleteListener
            val actual = runCatching { completed.currentPosition.toLong().coerceAtLeast(0L) }.getOrDefault(seekTargetMs)
            if (abs(actual - seekTargetMs) > SEEK_TOLERANCE_MS && seekRetries < MAX_SEEK_RETRIES) {
                seekRetries++
                runCatching { completed.seekTo(seekTargetMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
                    .onFailure { failSeek(actual) }
                return@setOnSeekCompleteListener
            }
            if (abs(actual - seekTargetMs) > SEEK_TOLERANCE_MS) {
                failSeek(actual)
                return@setOnSeekCompleteListener
            }
            seekInFlight = false
            finishPreparedTrack(ticket, completed, track, duration, actual)
        }
        try {
            media.seekTo(position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        } catch (_: Exception) {
            failSeek(runCatching { media.currentPosition.toLong() }.getOrDefault(0L))
        }
    }

    private fun failSeek(actual: Long) {
        seekInFlight = false
        pendingAutoplay = false
        lastObservedPosition = actual
        val current = AudiobookController.mutable.value
        val track = currentBook?.tracks?.getOrNull(current.trackIndex)
        AudiobookController.mutable.value = current.copy(positionMs = actual.coerceAtLeast(0L), playing = false, loading = false,
            cue = track?.let { cueAt(it, actual) }, error = "The audiobook could not move to that position.")
        releaseFocusAndWake()
        checkpoint()
        publish()
    }

    private fun finishPreparedTrack(ticket: Long, media: MediaPlayer, track: AudioTrack, duration: Long, position: Long) {
        if (!accepts(ticket, media)) return
        seekInFlight = false
        val actual = position.coerceAtLeast(0L).let { if (duration > 0L) it.coerceAtMost(duration) else it }
        lastObservedPosition = actual
        AudiobookController.mutable.value = AudiobookController.mutable.value.copy(durationMs = duration,
            positionMs = actual, loading = false, cue = cueAt(track, actual), error = null)
        if (pendingAutoplay) beginPlayback(ticket, media) else publish()
    }

    private fun handleMediaError(ticket: Long) {
        if (ticket != generation) return
        pendingAutoplay = false
        val position = player?.let { media -> if (prepared) runCatching { media.currentPosition.toLong() }.getOrNull() else null }
        if (position != null) AudiobookController.mutable.value = AudiobookController.mutable.value.copy(positionMs = position)
        releasePlayer(); releaseFocusAndWake()
        checkpoint()
        AudiobookController.mutable.value = AudiobookController.mutable.value.copy(playing = false, loading = false,
            error = if(currentBook?.sourceRoot!=null)"The source audio could not be played. Check the file or relink its folder in + Add audiobooks."
                else "This audio track could not be played.")
        publish()
    }

    private fun fail(message: String) {
        AudiobookController.mutable.value = AudiobookController.mutable.value.copy(loading = false, playing = false, error = message)
        publish()
    }

    private fun publish() {
        val current = AudiobookController.mutable.value
        if (::session.isInitialized) {
            val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD
            session.setPlaybackState(PlaybackState.Builder().setActions(actions)
                .setState(if (current.playing) PlaybackState.STATE_PLAYING else if (current.loading) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED,
                    current.positionMs, if (current.playing) current.speed else 0f).build())
            session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, current.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, current.trackTitle)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, current.durationMs).build())
        }
        if (AudiobookController.serviceRunning) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }

    private fun notification(): Notification {
        val current = AudiobookController.mutable.value
        fun action(action: String) = PendingIntent.getService(this, action.hashCode(),
            Intent(this, AudiobookService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 419, Intent(this, AudiobookActivity::class.java)
            .putExtra("audiobook", current.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val paused = !current.playing
        val options=AudioPreferences.load(this)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_folio)
            .setContentTitle(current.title.ifBlank { "Folio audiobook" })
            .setContentText(current.error ?: if (current.loading) "Loading audio…" else if (current.playing) current.trackTitle else "Playback paused")
            .setContentIntent(open).setDeleteIntent(action(STOP)).setOnlyAlertOnce(true)
            .setOngoing(current.playing || current.loading).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_rew, "Back ${options.backSeconds} seconds", action(REWIND)).build())
            .addAction(Notification.Action.Builder(if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "Play" else "Pause", action(if (paused) PLAY else PAUSE)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_ff, "Forward ${options.forwardSeconds} seconds", action(FORWARD)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", action(STOP)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2)).build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        checkpoint()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val current = AudiobookController.mutable.value
        val media = player
        val position = media?.let { if (prepared) runCatching { it.currentPosition.toLong().coerceAtLeast(0L) }.getOrNull() else null } ?: current.positionMs
        if (current.id != null && currentBook != null) runCatching {
            runBlocking { withContext(Dispatchers.IO) { store.savePosition(current.id, current.trackIndex, position, currentBook?.speed ?: current.speed) } }
        }
        pendingAutoplay = false
        generation++
        loadingJob?.cancel(); tickerJob?.cancel(); sleepJob?.cancel()
        releasePlayer(); releaseFocusAndWake()
        runCatching { unregisterReceiver(noisyReceiver) }
        if (::session.isInitialized) { session.isActive = false; session.release() }
        scope.cancel()
        AudiobookController.serviceRunning = false
        AudiobookController.mutable.value = AudioPlaybackState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val START = "folio.audiobook.START"
        const val PLAY = "folio.audiobook.PLAY"
        const val PAUSE = "folio.audiobook.PAUSE"
        const val STOP = "folio.audiobook.STOP"
        const val NEXT = "folio.audiobook.NEXT"
        const val PREVIOUS = "folio.audiobook.PREVIOUS"
        const val REWIND="folio.audiobook.REWIND"
        const val FORWARD="folio.audiobook.FORWARD"
        const val SKIP="folio.audiobook.SKIP"
        const val SEEK = "folio.audiobook.SEEK"
        const val SEEK_TRACK = "folio.audiobook.SEEK_TRACK"
        const val SPEED = "folio.audiobook.SPEED"
        const val SLEEP = "folio.audiobook.SLEEP"
        const val CLEAR_SLEEP = "folio.audiobook.CLEAR_SLEEP"
        const val SLEEP_BOUNDARY = "folio.audiobook.SLEEP_BOUNDARY"
        const val EXTEND_SLEEP = "folio.audiobook.EXTEND_SLEEP"
        private const val CHANNEL = "audiobook-playback"
        private const val NOTIFICATION = 420
        private const val CHECKPOINT_INTERVAL_MS = 10_000L
        private const val SEEK_TOLERANCE_MS = 250L
        private const val MAX_SEEK_RETRIES = 3
    }
}
