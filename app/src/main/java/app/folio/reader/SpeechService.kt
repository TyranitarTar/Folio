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
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

data class SpeechVoice(val name: String, val label: String)
data class SpeechSettings(val voice: String = "", val rate: Float = 1f, val backend: String = "android", val aiVoice: Int = 21,
    val supertonicVoice: Int = 0, val supertonicLanguage: String = "en",
    val navigationUnit: String = "sentence", val sentencePauseMillis: Int = 0, val paragraphPauseMillis: Int = 0,
    val picoVoice: Int = 0) {
    val isLocal: Boolean get() = backend in setOf("kokoro", "supertonic", "pico")
    val stretchesPlayback: Boolean get() = backend in setOf("supertonic", "pico")
    val localVoice: Int get() = when(backend) { "supertonic" -> supertonicVoice; "pico" -> picoVoice; else -> aiVoice }
    val localVoiceIdentity: String get() = when(backend) { "supertonic" -> "supertonic:$supertonicLanguage:$supertonicVoice"; "pico" -> "pico:da:$picoVoice"; else -> "kokoro:$aiVoice" }
    val engineLabel: String get() = when(backend) { "pico" -> "Plapre Pico"; "supertonic" -> "Supertonic 3"; "kokoro" -> "Kokoro"; else -> "Android speech" }
    val synthesisRate: Float get() = if (stretchesPlayback) 1f else rate
    val playbackRate: Float get() = if (stretchesPlayback) rate else 1f
    fun sameSource(other: SpeechSettings) = backend == other.backend &&
        (backend != "android" || voice == other.voice) && localVoiceIdentity == other.localVoiceIdentity && synthesisRate == other.synthesisRate
    fun audioKey(text: String): String = AiListeningStore.key(text, localVoice, synthesisRate, backend, if(backend=="pico") "da" else supertonicLanguage)
    fun save(context: Context) = context.getSharedPreferences("speech", Context.MODE_PRIVATE).edit()
        .putString("voice", voice).putFloat("rate", rate.coerceIn(.5f, 3f))
        .putString("backend", backend).putInt("aiVoice", aiVoice)
        .putInt("picoVoice", picoVoice)
        .putInt("supertonicVoice", supertonicVoice).putString("supertonicLanguage", supertonicLanguage)
        .putString("navigationUnit", navigationUnit).putInt("sentencePauseMillis", sentencePauseMillis.coerceIn(0,5000))
        .putInt("paragraphPauseMillis", paragraphPauseMillis.coerceIn(0,5000)).apply()
    companion object {
        fun load(context: Context): SpeechSettings = context.getSharedPreferences("speech", Context.MODE_PRIVATE).let {
            SpeechSettings(it.getString("voice", "").orEmpty(), it.getFloat("rate", 1f).takeIf { value -> value.isFinite() }?.coerceIn(.5f,3f) ?: 1f,
                it.getString("backend", "android").takeIf { value -> value in setOf("android", "kokoro", "supertonic", "pico") } ?: "android",
                it.getInt("aiVoice", 21).takeIf { id -> id == 21 || id == 3 } ?: 21,
                it.getInt("supertonicVoice", 0).takeIf { id -> id in 0..9 } ?: 0,
                it.getString("supertonicLanguage", "en").takeIf { language -> language == "en" || language == "da" } ?: "en",
                it.getString("navigationUnit", "sentence").takeIf { unit -> unit in setOf("line","sentence","paragraph","chapter") } ?: "sentence",
                it.getInt("sentencePauseMillis", 0).coerceIn(0,5000), it.getInt("paragraphPauseMillis", 0).coerceIn(0,5000),
                it.getInt("picoVoice", 0).takeIf { id -> id in 0..4 } ?: 0)
        }
    }
}
data class SpeechState(
    val bookId: String? = null, val title: String = "", val playing: Boolean = false,
    val preparing: Boolean = false, val locator: Locator? = null, val passage: String = "",
    val voices: List<SpeechVoice> = emptyList(), val selectedVoice: String = "", val error: String? = null,
    val finished: Boolean = false, val backend: String = "android", val preparation: String = "Preparing offline English speech…",
    val audioSample: Int = 0, val audioTotal: Int = 0, val sampleRate: Int = 24000, val recoveryNote: String? = null,
    val playbackRate: Float = 1f, val preparationStage: String = "book", val sleepRemainingSeconds: Long? = null,
    val sleepEndChapter: Boolean = false, val previewing: Boolean = false, val requestId: Long = 0, val chapterLabel: String = "Chapter",
    val seekRevision: Long = 0
)

/** UI observes this process-local state; durable listening progress lives in ReaderCursorStore. */
object SpeechController {
    internal val mutable = MutableStateFlow(SpeechState())
    val state = mutable.asStateFlow()
    private val mutableFollow = MutableStateFlow(true)
    val follow = mutableFollow.asStateFlow()
    private val requests = AtomicLong()
    fun loadFollow(context: Context) { setFollow(context, true) }
    fun setFollow(context: Context, enabled: Boolean) {
        mutableFollow.value = true
        context.getSharedPreferences("speech", Context.MODE_PRIVATE).edit().putBoolean("follow", true).apply()
    }
    private fun intent(context: Context, action: String) = Intent(context, SpeechService::class.java).setAction(action)
    fun start(context: Context, bookId: String, from: Locator? = null) {
        val i = intent(context, SpeechService.START).putExtra("book", bookId).putExtra("from", from?.toJSON()?.toString()).putExtra("request", requests.incrementAndGet())
        runCatching { ContextCompat.startForegroundService(context, i) }.onFailure {
            val selected=SpeechSettings.load(context)
            mutable.value = SpeechState(bookId=bookId,requestId=i.getLongExtra("request",0),backend=selected.backend,
                playbackRate=selected.rate,seekRevision=mutable.value.seekRevision,preparationStage="error",error="Speech could not start. Open the reader and try again.")
        }
    }
    fun seek(context: Context, bookId: String, stableLocator: Locator, preservePaused: Boolean = true) {
        val request = intent(context, SpeechService.SEEK).putExtra("book", bookId).putExtra("from", stableLocator.toJSON().toString())
            .putExtra("preservePaused", preservePaused).putExtra("request", requests.incrementAndGet())
        runCatching { ContextCompat.startForegroundService(context, request) }.onFailure {
            mutable.value = mutable.value.copy(error = "The listening target could not be changed.")
        }
    }
    fun replay(context: Context) = command(context, SpeechService.REPLAY)
    fun navigate(context: Context, direction: Int, unit: String = "sentence") {
        if (mutable.value.bookId != null) context.startService(intent(context, SpeechService.NAVIGATE).putExtra("direction", direction).putExtra("unit", unit))
    }
    fun sleep(context: Context, minutes: Int = 0, endChapter: Boolean = false) {
        require(endChapter || minutes in setOf(5,15,30,60))
        if (mutable.value.bookId != null) context.startService(intent(context, SpeechService.SLEEP).putExtra("minutes", minutes).putExtra("endChapter", endChapter))
    }
    fun clearSleep(context: Context) = command(context, SpeechService.CLEAR_SLEEP)
    fun preview(context: Context, settings: SpeechSettings) {
        val request=intent(context, SpeechService.PREVIEW).putExtra("backend", settings.backend)
            .putExtra("voice", settings.voice).putExtra("rate", settings.rate).putExtra("aiVoice", settings.aiVoice)
            .putExtra("picoVoice", settings.picoVoice)
            .putExtra("supertonicVoice", settings.supertonicVoice).putExtra("language", settings.supertonicLanguage)
            .putExtra("request", requests.incrementAndGet())
        runCatching { ContextCompat.startForegroundService(context,request) }.onFailure {
            mutable.value=mutable.value.copy(previewing=false,preparing=false,preparationStage="error",error="Voice preview could not start. Open the reader and try again.")
        }
    }
    fun cancelPreview(context: Context) { if (mutable.value.previewing) context.startService(intent(context, SpeechService.CANCEL_PREVIEW)) }
    fun pause(context: Context) = command(context, SpeechService.PAUSE)
    fun resume(context: Context) = command(context, SpeechService.RESUME)
    fun previous(context: Context) = command(context, SpeechService.PREVIOUS)
    fun next(context: Context) = command(context, SpeechService.NEXT)
    @Volatile internal var serviceRunning=false
    fun stop(context: Context) {
        if(serviceRunning || mutable.value.bookId!=null || mutable.value.previewing)runCatching { context.startService(intent(context,SpeechService.STOP)) }
    }
    fun settings(context: Context, settings: SpeechSettings) {
        settings.save(context)
        if (mutable.value.bookId != null) command(context, SpeechService.SETTINGS)
    }
    private fun command(context: Context, action: String) {
        if (mutable.value.bookId != null) runCatching { context.startService(intent(context, action)) }
    }
}

/** Rejects all callbacks from utterances stopped by pause, seek, book switches, or a new request. */
internal class SpeechCallbackGate {
    private var generation = 0L
    private var sequence = 0L
    @Volatile private var current: String? = null
    fun invalidate() { generation++; current = null }
    fun begin(): String = "$generation:${++sequence}".also { current = it }
    fun accepts(id: String?): Boolean = id != null && current == id
    fun complete(id: String?): Boolean = accepts(id).also { if (it) current = null }
}

/** All offline backends share media controls and a listening cursor independent of the activity. */
class SpeechService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var destroyed = false
    private var persistenceFailed = false
    private var checkpointSession: OrderedSpeechWriter.Session? = null
    private val pcmBudget = SpeechPcmBudget()
    private var idleRelease: Job? = null
    private val gate = SpeechCallbackGate()
    private var loading: Job? = null
    private var generation = 0L
    private var publication: Publication? = null
    private var text: BookText? = null
    private var cursor: ReadingCursor? = null
    private var tts: TextToSpeech? = null
    private var initialized: CompletableDeferred<Boolean>? = null
    private var settings = SpeechSettings()
    private var currentWords: List<ReadingWord> = emptyList()
    private var currentWordEnds = IntArray(0)
    private var currentStartWord = 0
    private var voiceList: List<Voice> = emptyList()
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private lateinit var focus: AudioFocusRequest
    private var focusHeld = false
    private var playRequested = false
    private var aiEngine: LocalSpeechEngine? = null
    private var aiPlayer: AiAudioPlayer? = null
    private var aiJob: Job? = null
    private lateinit var aiStore: AiListeningStore
    private var aiPoint: AiListeningPoint? = null
    private var aiKey: String? = null
    private var aiOffset = 0
    private var aiClip: AiAudioClip? = null
    private var aiPlaybackId: String? = null
    private var lastAiCheckpoint = 0L
    private data class PendingPassage(val cursor: ReadingCursor, val key: String, val audio: Deferred<Result<AiAudioClip>>, val owner: Any)
    private val ahead = ArrayDeque<PendingPassage>()
    private var needsAudioBuffer = true
    private var naturalContinuation = false
    private val sleepTimer = SpeechSleepTimer()
    private var sleepJob: Job? = null
    private var transitionJob: Job? = null
    private var previewJob: Job? = null
    private var previewPlayer: AiAudioPlayer? = null
    private var previewOwner: Any? = null
    private var previewGeneration = 0L
    private var lastBackReplay: String? = null
    private var lastBackReplayMillis = 0L
    private lateinit var wakeLock: PowerManager.WakeLock
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause() }
    }

    override fun onCreate() {
        SpeechController.serviceRunning=true
        super.onCreate()
        settings = SpeechSettings.load(this)
        SpeechController.loadFollow(this)
        aiStore = AiListeningStore(this)
        audio = getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change -> if (change != AudioManager.AUDIOFOCUS_GAIN) scope.launch { pause() } }
            .setWillPauseWhenDucked(true).build()
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Folio:spoken-book").apply { setReferenceCounted(false) }
        session = MediaSession(this, "Folio narration").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { scope.launch { resume() } }
                override fun onPause() { pause() }
                override fun onStop() { stopSelf() }
                override fun onSkipToNext() { skip(1) }
                override fun onSkipToPrevious() { skip(-1) }
            })
            isActive = true
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Book narration", NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)

    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                // A foreground-service request must publish its notification before parsing a book or initializing TTS.
                startForeground(NOTIFICATION, notification())
                val book = intent.getStringExtra("book")
                val locator = intent.getStringExtra("from")?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
                if (book != null) startBook(book, locator, acceptedRequest = intent.getLongExtra("request",0)) else stopSelf()
            }
            PAUSE -> pause()
            RESUME -> { startForeground(NOTIFICATION,notification()); scope.launch { resume() } }
            PREVIOUS -> navigate(-1, settings.navigationUnit)
            NEXT -> navigate(1, settings.navigationUnit)
            REPLAY -> navigate(0, "sentence")
            NAVIGATE -> navigate(intent.getIntExtra("direction", 0), intent.getStringExtra("unit") ?: "sentence")
            SEEK -> {
                startForeground(NOTIFICATION, notification())
                val book = intent.getStringExtra("book")
                val target = intent.getStringExtra("from")?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
                if (book != null && target != null) explicitSeek(book, target, intent.getBooleanExtra("preservePaused", true), intent.getLongExtra("request", 0))
            }
            SLEEP -> configureSleep(intent.getIntExtra("minutes", 0), intent.getBooleanExtra("endChapter", false))
            CLEAR_SLEEP -> configureSleep(0, false)
            PREVIEW -> {
                startForeground(NOTIFICATION, notification())
                val selected = settings.copy(backend = intent.getStringExtra("backend") ?: "android", voice = intent.getStringExtra("voice").orEmpty(),
                    rate = intent.getFloatExtra("rate",1f).takeIf { it.isFinite() }?.coerceIn(.5f,3f) ?: 1f,
                    aiVoice = intent.getIntExtra("aiVoice",21), picoVoice = intent.getIntExtra("picoVoice",0).coerceIn(0,4), supertonicVoice = intent.getIntExtra("supertonicVoice",0),
                    supertonicLanguage = intent.getStringExtra("language") ?: "en")
                preview(selected, intent.getLongExtra("request",0))
            }
            CANCEL_PREVIEW -> { cancelPreview(); if (state().bookId == null) stopSelf() }
            SETTINGS -> updateSettings()

            STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun state() = SpeechController.mutable.value
    private fun startBook(bookId: String, from: Locator?, autoPlay: Boolean = true, acceptedRequest: Long = 0) {
        cancelPreview()
        configureSleep(0,false)
        val request = ++generation
        loading?.cancel()
        pause()
        idleRelease?.cancel()
        checkpointSession?.let { SpeechCheckpointCoordinator.end(it) }; checkpointSession = null
        clearAiAudio()
        cursor = null; text = null
        currentWords = emptyList(); currentWordEnds = IntArray(0); currentStartWord = 0
        publication?.close(); publication = null
        playRequested = autoPlay
        settings = SpeechSettings.load(this)
        SpeechController.mutable.value = SpeechState(bookId = bookId, preparing = true, backend = settings.backend,
            requestId = acceptedRequest, seekRevision = state().seekRevision, playbackRate = settings.rate, preparation = "Preparing book…")
        loading = scope.launch {
            var opened: Publication? = null
            try {
                val app = application as FolioApp
                app.library.load()
                val book = app.library.books.value.firstOrNull { it.id == bookId }
                    ?: throw IllegalArgumentException("This book is no longer in your library.")
                opened = app.engine.open(app.library.file(bookId))
                val parsed = BookText.load(opened, bookId)
                val restored = SpeechCheckpointCoordinator.begin(this@SpeechService, bookId, app.library.file(bookId),
                    restore = from == null, includeAi = settings.isLocal)
                if (request != generation) { SpeechCheckpointCoordinator.end(restored.session); return@launch }
                checkpointSession = restored.session
                persistenceFailed = false
                val saved = restored.cursor
                val savedAi = restored.ai
                publication?.close(); publication = opened; opened = null
                text = parsed
                cursor = if (from != null) parsed.locate(from) else parsed.restore(saved)
                aiPoint = savedAi?.takeIf { it.section == cursor?.sectionIndex && it.word == cursor?.wordIndex }
                SpeechController.mutable.value = state().copy(title = book.title, locator = cursor?.locator,
                    seekRevision = state().seekRevision + if (from != null && !autoPlay && cursor != null) 1 else 0,
                    recoveryNote = restored.recoveryNote, chapterLabel=if(parsed.chapters.isEmpty()) "Section" else "Chapter")
                session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, book.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, book.author).build())
                SpeechController.mutable.value = state().copy(preparing = false, error = null)
                if (playRequested) resume() else { rememberCursor(mandatory=true); publishPlayback() }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (error: Throwable) {
                if (request == generation) fail(error.message ?: "This book could not be read aloud.")
            } finally { opened?.close() }
        }
    }

    private suspend fun ensureEngine(): Boolean {
        val ready = initialized ?: CompletableDeferred<Boolean>().also { pending ->
            initialized = pending
            tts = TextToSpeech(this) { status -> scope.launch { pending.complete(status == TextToSpeech.SUCCESS) } }
        }
        val result = kotlinx.coroutines.withTimeoutOrNull(15000) { ready.await() } ?: false
        if (!result) { tts?.shutdown(); tts = null; initialized = null; return false }
        tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { scope.launch { if (gate.accepts(utteranceId)) { SpeechController.mutable.value = state().copy(playing=true,preparing=false,preparationStage="playing"); rememberCursor(); publishPlayback() } } }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                scope.launch {
                    if (!gate.accepts(utteranceId)) return@launch
                    var low = 0; var high = currentWordEnds.size
                    while (low < high) {
                        val middle = (low + high) ushr 1
                        if (currentWordEnds[middle] < start) low = middle + 1 else high = middle
                    }
                    val relative = low.coerceIn(0, currentWords.lastIndex.coerceAtLeast(0))
                    cursor = text?.cursor(cursor?.sectionIndex ?: 0, currentStartWord + relative)
                    rememberCursor(); publishPlayback()
                }
            }
            override fun onDone(utteranceId: String?) {
                scope.launch {
                    if (!gate.complete(utteranceId)) return@launch
                    finishPassage()
                }
            }
            @Deprecated("Android legacy callback") override fun onError(utteranceId: String?) { handleError(utteranceId, TextToSpeech.ERROR) }
            override fun onError(utteranceId: String?, errorCode: Int) { handleError(utteranceId, errorCode) }
        })
        return true
    }

    private fun handleError(id: String?, error: Int) { scope.launch {
        if (!gate.accepts(id)) return@launch
        fail(if (error == TextToSpeech.ERROR_NOT_INSTALLED_YET) "This voice's offline data is missing. Download an English voice in Android Speech settings."
            else "The speech engine could not play this passage. Your listening position is saved; try another offline English voice.")
    } }
    private fun applyVoice(): Boolean {
        val engine = tts ?: return false
        voiceList = engine.voices.orEmpty().filter { it.locale.language == "en" && !it.isNetworkConnectionRequired &&
            !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenByDescending { it.locale.country == "GB" }.thenBy { it.latency }.thenBy { it.name })
        SpeechController.mutable.value = state().copy(voices = voiceList.map {
            SpeechVoice(it.name, "${it.locale.getDisplayName(Locale.ENGLISH)} · ${it.name}")
        })
        val voice = voiceList.firstOrNull { it.name == settings.voice } ?: voiceList.firstOrNull()
        if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) {
            fail("No installed offline English voice is available. Open Android Speech settings and download English voice data, then try again.")
            return false
        }
        settings = settings.copy(voice = voice.name)
        settings.save(this)
        if (engine.setSpeechRate(settings.rate) != TextToSpeech.SUCCESS) {
            fail("The speech engine could not use this speed. Choose another speed or offline voice.")
            return false
        }
        SpeechController.mutable.value = state().copy(selectedVoice = voice.name, error = null)
        return true
    }
    private suspend fun resume() {
        idleRelease?.cancel(); idleRelease = null
        cancelPreview()
        if(sleepTimer.disarmExpired(android.os.SystemClock.elapsedRealtime())) {
            sleepJob?.cancel(); sleepJob=null
            SpeechController.mutable.value=state().copy(sleepRemainingSeconds=0,sleepEndChapter=false)
        }
        if (state().bookId==null) { stopSelf(); return }
        if (text == null && state().bookId != null && !state().preparing) { startBook(state().bookId!!, null); return }
        if (persistenceFailed) { fail("Listening progress could not be saved. Free some storage, then restart narration."); return }
        playRequested = true
        if (state().preparing || state().playing || cursor == null) return
        val ticket = gate.begin()
        if (settings.backend == "android") {
            SpeechController.mutable.value = state().copy(preparing = true, preparation = "Preparing speech…", preparationStage = "speech")
            val ready = ensureEngine()
            if (!gate.accepts(ticket) || !playRequested) return
            SpeechController.mutable.value = state().copy(preparing = false)
            if (!ready) { fail("Android speech is unavailable. Check your installed speech engine."); return }
            if (!applyVoice()) return
        }
        if (!focusHeld) focusHeld = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focusHeld) { fail("Another app is using audio. Pause it, then resume listening."); return }
        wakeLock.acquire(30 * 60 * 1000L)
        SpeechController.mutable.value = state().copy(playing = !settings.isLocal, error = null, finished = false, playbackRate = settings.rate)
        val pausedClip = aiClip
        if (settings.isLocal && pausedClip != null) {
            if (aiPlayer?.setPlaybackRate(settings.playbackRate)==false) { fail("Pitch-preserving speed is unavailable. Choose 1.0x."); return }
            try {
                if (!warmAudioBuffer(pausedClip, aiOffset, ticket)) return
                if (aiPlayer?.resume(ticket) == true) {
                    aiPlaybackId = ticket
                    SpeechController.mutable.value = state().copy(preparing = false)
                    queuePreparedAudio()
                    publishPlayback(); return
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Throwable) { if (gate.accepts(ticket)) fail("Voice audio could not be prepared. ${error.message.orEmpty()}"); return }
        }
        speakNext()
    }
    private fun speakNext() {
        val position = cursor ?: return
        val sentence = text?.sentence(position) ?: run { fail("This passage has no speakable text."); return }
        currentStartWord = position.wordIndex.coerceIn(sentence.firstWord, sentence.endWord - 1)
        currentWords = passageWords(position, settings.isLocal)
        val message = currentWords.joinToString(" ") { it.text }
        var endOffset = -1
        currentWordEnds = IntArray(currentWords.size) { index ->
            endOffset += currentWords[index].text.length + if (index == 0) 0 else 1
            endOffset
        }
        val id = gate.begin()
        // Refresh a bounded wake lock between sentences; release it immediately on every pause.
        wakeLock.acquire(30 * 60 * 1000L)
        SpeechController.mutable.value = state().copy(passage = message, locator = position.locator)
        publishPlayback()
        if (settings.isLocal) { speakAi(message, id, position); return }
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        if (tts?.speak(message, TextToSpeech.QUEUE_ADD, params, id) != TextToSpeech.SUCCESS) fail("The speech engine rejected this passage. Try another offline English voice.")
    }
    private fun persistenceError(error: Throwable) {
        if (destroyed || persistenceFailed) return
        persistenceFailed = true
        SpeechController.mutable.value = state().copy(error = "Listening progress could not be saved. Free some storage before continuing.")
        pause()
    }
    private fun forgetAudio() {
        val checkpoint = checkpointSession ?: return
        if (!persistenceFailed) SpeechCheckpointCoordinator.forgetAudio(this, checkpoint, ::persistenceError)
    }
    private fun rememberCursor(mandatory: Boolean = false) {
        if (persistenceFailed) return
        val checkpoint = checkpointSession ?: return
        val point = cursor ?: return
        if (settings.isLocal && aiKey != null) rememberAi(mandatory)
        else SpeechCheckpointCoordinator.save(this, checkpoint, point, mandatory = mandatory, onError = ::persistenceError)
        val visible = if (settings.isLocal && currentWords.isNotEmpty()) {
            currentWords.first().locator.copy(text = Locator.Text(before = currentWords.first().locator.text.before,
                highlight = currentWords.joinToString(" ") { it.text }, after = currentWords.last().locator.text.after))
        } else point.locator
        SpeechController.mutable.value = state().copy(locator = visible)
    }
    private fun clearAiAudio(stopOutput: Boolean = true) {
        if (stopOutput) { aiPlayer?.stop(); naturalContinuation = false }
        pcmBudget.clearActive()
        aiStore.unpin()
        aiClip = null; aiPlaybackId = null; aiKey = null; aiPoint = null; aiOffset = 0
        SpeechController.mutable.value = state().copy(audioSample = 0, audioTotal = 0)
    }
    private fun rememberAi(mandatory: Boolean = false) {
        if (persistenceFailed) return
        val session = checkpointSession ?: return
        val point = cursor ?: return
        val key = aiKey ?: return
        val checkpoint = AiListeningPoint(point.sectionIndex, currentStartWord, key, aiOffset, aiClip?.digest ?: aiPoint?.audioDigest)
        aiPoint = checkpoint
        SpeechCheckpointCoordinator.save(this, session, point, checkpoint, mandatory, ::persistenceError)
    }
    private fun finishPassage() {
        val here=cursor ?: return
        val last=text?.cursor(here.sectionIndex,currentStartWord+currentWords.lastIndex) ?: return
        val next=text?.next(last)
        val sleepBoundary=sleepTimer.chapter==SpeechNavigation.chapterIndex(text!!,here) &&
            (next==null || SpeechNavigation.chapterIndex(text!!,next)!=sleepTimer.chapter)
        if (next==null || sleepBoundary) {
            transitionJob=scope.launch {
                if(settings.isLocal) delay(aiPlayer?.tailGuardMillis() ?: 250)
                clearAiAudio(); forgetAudio()
                cursor=if(sleepBoundary && next!=null) next else last
                currentWords=emptyList(); currentStartWord=cursor!!.wordIndex
                rememberCursor(mandatory=true); pause()
                if(sleepBoundary) { sleepTimer.clear(); sleepJob?.cancel() }
                SpeechController.mutable.value=state().copy(finished=next==null,
                    locator=cursor?.locator, passage=if(next==null) "End of book" else "${state().chapterLabel} ended — listening paused",
                    sleepEndChapter=false,sleepRemainingSeconds=if(sleepBoundary) 0 else state().sleepRemainingSeconds)
                publishPlayback()
            }
            return
        }
        val gap=SpeechNavigation.pauseAfter(text!!,last,next,settings)
        naturalContinuation=settings.isLocal && gap==0L
        clearAiAudio(stopOutput=!naturalContinuation && gap==0L)
        forgetAudio(); cursor=next; rememberCursor(mandatory=true)
        if (playRequested && gap>0) {
            SpeechController.mutable.value=state().copy(preparing=true,preparation="Pausing between passages…",preparationStage="buffering")
            transitionJob=scope.launch {
                delay(maxOf(gap,if(settings.isLocal) aiPlayer?.tailGuardMillis() ?: 250 else 0))
                aiPlayer?.stop(); naturalContinuation=false
                SpeechController.mutable.value=state().copy(preparing=false)
                if(playRequested) speakNext()
            }
        } else if(playRequested) speakNext()
    }
    private fun localPlayer(): AiAudioPlayer = aiPlayer ?: AiAudioPlayer(object : AiAudioPlayer.Listener {
        override fun onStarted(utteranceId: String) {
            if (gate.accepts(utteranceId)) { SpeechController.mutable.value = state().copy(playing=true,preparing=false,preparationStage="playing"); rememberCursor(); publishPlayback() }
        }
        override fun onPosition(utteranceId: String, playedSamples: Int, sampleRate: Int, totalSamples: Int) {
            if (!gate.accepts(utteranceId)) return
            aiOffset = playedSamples
            aiClip?.let { pcmBudget.updateActive(it, aiOffset) }
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAiCheckpoint >= 1000) { lastAiCheckpoint = now; rememberAi() }
            SpeechController.mutable.value = state().copy(audioSample = playedSamples, audioTotal = totalSamples, sampleRate = sampleRate)
        }
        override fun onCompleted(utteranceId: String) {
            if (gate.complete(utteranceId)) finishPassage()
        }
        override fun onError(utteranceId: String, message: String) { if (gate.accepts(utteranceId)) fail(message) }
    }).also { aiPlayer = it }

    private fun localEngine(selected: SpeechSettings): LocalSpeechEngine {
        aiEngine?.takeIf { it.backend == selected.backend }?.let { return it }
        // Release is queued before the new engine initializes on the same native worker.
        aiEngine?.close()
        return createLocalSpeechEngine(applicationContext, selected.backend).also { aiEngine = it }
    }

    private fun speakAi(message: String, id: String, position: ReadingCursor) {
        val selected = settings
        val key = selected.audioKey(message)
        aiKey = key
        if (selected.stretchesPlayback && aiPoint!=null && aiPoint?.key!=key) {
            SpeechController.mutable.value=state().copy(recoveryNote="The previous audio bookmark belongs to a different source waveform. This passage is repeated once so no words are skipped.")
        }
        val savedPoint = aiPoint?.takeIf { it.key == key && it.section == position.sectionIndex && it.word == position.wordIndex }
        val offset = savedPoint?.playedSamples ?: 0
        aiOffset = offset
        SpeechController.mutable.value = state().copy(preparing = true,
            preparation = "Buffering audio…", preparationStage = "buffering", backend = selected.backend)
        publishPlayback()
        aiJob = scope.launch {
            var reservation: Any? = null
            var activated = false
            try {
                val upcoming = ahead.firstOrNull()?.takeIf {
                    it.key == key && it.cursor.sectionIndex == position.sectionIndex && it.cursor.wordIndex == position.wordIndex
                }
                if (upcoming != null) ahead.removeFirst()
                else if (ahead.isNotEmpty()) cancelAudioBuffer()
                val owner = upcoming?.owner ?: Any()
                reservation = owner
                if (upcoming == null) pcmBudget.acquire(owner)
                val cached = upcoming?.audio?.await()?.getOrThrow() ?: withContext(Dispatchers.IO) { aiStore.loadClip(key) }
                val clip = cached ?: run {
                    val engine = localEngine(selected)
                    if(!engine.prepared) SpeechController.mutable.value = state().copy(preparation = "Loading ${selected.engineLabel}…", preparationStage="loading")
                    engine.prepare()
                    if (!gate.accepts(id)) return@launch
                    SpeechController.mutable.value = state().copy(preparation = "Preparing speech…", preparationStage="speech")
                    publishPlayback()
                    engine.generate(message, selected).also { generated ->
                        withContext(Dispatchers.IO) { aiStore.saveClip(key, generated) }
                    }
                }
                if (!gate.accepts(id) || !playRequested) return@launch
                val changedAudio = offset > 0 && (savedPoint?.audioDigest?.let { it != clip.digest } ?: (cached == null))
                aiOffset = if (changedAudio) 0 else offset.coerceIn(0, clip.samples.size)
                if (changedAudio) SpeechController.mutable.value = state().copy(recoveryNote =
                    "The saved audio was rebuilt. This passage is repeated so no words are skipped.")
                withContext(Dispatchers.IO) { aiStore.ensurePinned(key, clip) { gate.accepts(id) } }
                if (!gate.accepts(id) || !playRequested) return@launch
                pcmBudget.activate(clip, aiOffset, owner); activated = true
                aiClip = clip
                if (!warmAudioBuffer(clip, aiOffset, id)) return@launch
                SpeechController.mutable.value = state().copy(preparing = false, playing = false, error = null,
                    selectedVoice = selected.localVoiceIdentity, audioSample = aiOffset, audioTotal = clip.samples.size, sampleRate = clip.sampleRate)
                // Save the current anchor before sound starts. Only later played-head callbacks advance the offset.
                rememberAi()
                val player = localPlayer()
                if (!player.setPlaybackRate(settings.playbackRate)) throw IllegalStateException("Pitch-preserving playback is unavailable. Choose 1.0x.")
                val appended = naturalContinuation && player.append(clip, id, aiOffset)
                naturalContinuation = false
                if (!appended) player.play(clip, id, aiOffset)
                aiPlaybackId = id
                queuePreparedAudio()
                publishPlayback()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Throwable) {
                if (gate.accepts(id)) fail("Local AI voice could not play this passage. ${error.message ?: "Try Android speech."}")
            } finally { if (!activated) reservation?.let { pcmBudget.release(it) } }
        }
    }
    private fun passageWords(position: ReadingCursor, bounded: Boolean): List<ReadingWord> {
        val book = text ?: return emptyList()
        val sentence = book.sentence(position) ?: return emptyList()
        val chapterEnd=SpeechNavigation.nextChapter(book,position)?.takeIf { it.sectionIndex==position.sectionIndex }?.wordIndex
        val endWord=minOf(sentence.endWord,chapterEnd ?: sentence.endWord)
        val remaining = book.sections[position.sectionIndex].words.subList(position.wordIndex, endWord)
        val limit = if (bounded && settings.backend=="pico") 160 else if (bounded) 320 else TextToSpeech.getMaxSpeechInputLength()
        var characters = 0; var end = 0
        for (word in remaining) {
            if (end > 0 && characters + word.text.length + 1 > limit) break
            characters += word.text.length + 1; end++
        }
        return remaining.take(end)
    }
    /** Keep a bounded ordered queue. Only AudioTrack callbacks advance the listening cursor. */
    private fun fillAudioBuffer() {
        if (!settings.isLocal || !playRequested) return
        val book = text ?: return
        val here = cursor ?: return
        val selected = settings
        var previous = ahead.lastOrNull()
        var last = previous?.let { pending ->
            val words = passageWords(pending.cursor, true)
            book.cursor(pending.cursor.sectionIndex, pending.cursor.wordIndex + words.lastIndex)
        } ?: book.cursor(here.sectionIndex, currentStartWord + currentWords.lastIndex) ?: return
        while (ahead.size < MAX_BUFFER_PASSAGES) {
            val next = book.next(last) ?: break
            if (sleepTimer.chapter != null && SpeechNavigation.chapterIndex(book,next) != sleepTimer.chapter) break
            val words = passageWords(next, true)
            val message = words.joinToString(" ") { it.text }
            if (message.isBlank()) break
            val key = selected.audioKey(message)
            val predecessor = previous?.audio
            val owner = Any()
            val prepared = scope.async {
                var completed = false
                try {
                    // Reserve worst-case bytes/time before cache loading or non-preemptible native synthesis.
                    predecessor?.await()?.getOrThrow()
                    pcmBudget.acquire(owner)
                    val clip = withContext(Dispatchers.IO) { aiStore.loadClip(key) } ?: run {
                        val engine = localEngine(selected)
                        engine.generate(message, selected).also { generated ->
                            withContext(Dispatchers.IO) { aiStore.saveClip(key, generated) }
                        }
                    }
                    pcmBudget.commit(owner, clip.samples.size.toLong() * 4, clip.samples.size.toDouble() / clip.sampleRate)
                    completed = true
                    Result.success(clip)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Throwable) { Result.failure(error) }
                finally { if (!completed) pcmBudget.release(owner) }
            }
            previous = PendingPassage(next, key, prepared, owner).also { ahead.addLast(it) }
            prepared.invokeOnCompletion { error -> if (error == null) scope.launch { queuePreparedAudio() } }
            last = book.cursor(next.sectionIndex, next.wordIndex + words.lastIndex) ?: break
        }
    }
    /** Submission does not promote metadata or consume its reservation: COMPLETE remains authoritative. */
    private suspend fun queuePreparedAudio() {
        if (!playRequested || !settings.isLocal || aiClip == null || aiPlaybackId == null) return
        val player = aiPlayer ?: return
        if (player.state.value.utteranceId != aiPlaybackId) return
        val book = text ?: return
        var last = book.cursor(cursor?.sectionIndex ?: return, currentStartWord + currentWords.lastIndex) ?: return
        for (pending in ahead) {
            if (sleepTimer.chapter != null && SpeechNavigation.chapterIndex(book,pending.cursor) != sleepTimer.chapter) break
            if (SpeechNavigation.pauseAfter(book,last,pending.cursor,settings) > 0) break
            if (!pending.audio.isCompleted || pending.audio.isCancelled) break
            val clip = pending.audio.await().getOrNull() ?: break
            if (!player.queueNext(clip)) break
            val words = passageWords(pending.cursor,true)
            last = book.cursor(pending.cursor.sectionIndex,pending.cursor.wordIndex+words.lastIndex) ?: break
        }
    }
    private fun cancelAudioBuffer() {
        ahead.forEach { it.audio.cancel(); pcmBudget.release(it.owner) }; ahead.clear()
        needsAudioBuffer = true
    }
    private suspend fun warmAudioBuffer(clip: AiAudioClip, offset: Int, ticket: String): Boolean {
        fillAudioBuffer()
        if (needsAudioBuffer) {
            SpeechController.mutable.value = state().copy(preparing = true, preparation = "Buffering audio…", preparationStage="buffering")
            publishPlayback()
            var seconds = (clip.samples.size - offset).toDouble() / clip.sampleRate
            // Pay preparation once, ahead of playback, rather than after each short line.
            for (pending in ahead.toList()) {
                if (seconds / settings.playbackRate >= START_BUFFER_SECONDS) break
                val prepared = pending.audio.await().getOrThrow()
                seconds += prepared.samples.size.toDouble() / prepared.sampleRate
            }
            if (!gate.accepts(ticket) || !playRequested) return false
            needsAudioBuffer = false
        }
        return gate.accepts(ticket) && playRequested
    }
    private fun pause() {
        cancelPreview()
        transitionJob?.cancel(); transitionJob=null
        playRequested = false
        if (settings.isLocal) {
            val player = aiPlayer
            val matchesCurrent = aiClip != null && aiPlaybackId != null && player?.state?.value?.utteranceId == aiPlaybackId
            // Queued PCM can already be playing while a new metadata ID waits for IO activation.
            // Pause output regardless; only a matching active ID may advance this passage's offset.
            val played = player?.pause()
            if (matchesCurrent && played != null) aiOffset = played
            rememberAi(mandatory = true)
        } else rememberCursor(mandatory = true)
        gate.invalidate()
        cancelAudioBuffer()
        aiJob?.cancel(); aiJob = null
        aiEngine?.cancelGeneration()
        tts?.stop()
        if (wakeLock.isHeld) wakeLock.release()
        if (focusHeld) { audio.abandonAudioFocusRequest(focus); focusHeld = false }
        SpeechController.mutable.value = state().copy(playing = false, preparing = false, preparationStage="paused", audioSample = aiOffset)
        publishPlayback()
        idleRelease?.cancel()
        idleRelease = scope.launch {
            delay(60_000)
            if (!playRequested) {
                aiPlayer?.close(); aiPlayer = null
                aiEngine?.close(); aiEngine = null
                aiClip = null; aiPlaybackId = null
                pcmBudget.clearActive(); aiStore.unpin()
            }
        }
    }
    private fun explicitSeek(bookId: String, locator: Locator, preservePaused: Boolean, request: Long) {
        val continuePlaying = !preservePaused || playRequested || state().playing
        cancelPreview()
        if (state().bookId != bookId || text == null) {
            startBook(bookId,locator,continuePlaying,request); return
        }
        val target = text?.locate(locator) ?: return
        seekTo(target,continuePlaying,request)
    }
    private fun seekTo(target: ReadingCursor, continuePlaying: Boolean, request: Long = state().requestId) {
        generation++; loading?.cancel(); loading=null
        pause(); clearAiAudio(); forgetAudio()
        cursor=target; currentStartWord=target.wordIndex; currentWords=emptyList()
        rememberCursor(mandatory=true)
        SpeechController.mutable.value=state().copy(locator=target.locator,passage="",finished=false,error=null,
            preparing=false,playing=false,preparationStage="paused",requestId=request,seekRevision=state().seekRevision+1)
        publishPlayback()
        if (continuePlaying) scope.launch { resume() }
    }
    private fun navigate(direction: Int, requestedUnit: String) {
        val book=text ?: return
        val point=cursor ?: return
        val unit=requestedUnit.takeIf { it in setOf("sentence","paragraph","chapter") } ?: "sentence"
        val range=SpeechNavigation.range(book,point,unit) ?: return
        val key="${point.sectionIndex}:${range.first}:$unit"
        val now=android.os.SystemClock.elapsedRealtime()
        val secondTap=direction<0 && lastBackReplay==key && now-lastBackReplayMillis<=2000
        val insideUnit=if(unit=="chapter") SpeechNavigation.chapterStart(book,point)?.let { SpeechNavigation.compare(point,it)>0 } ?: false else point.wordIndex>range.first
        val replay=direction<0 && !secondTap && (insideUnit || (state().playing && aiOffset>0))
        val target=SpeechNavigation.target(book,point,direction,unit,replay) ?: return
        lastBackReplay=if(replay) key else null; lastBackReplayMillis=now
        seekTo(target,playRequested || state().playing)
    }
    private fun skip(direction: Int) = navigate(direction,settings.navigationUnit)
    private fun updateSettings() {
        cancelPreview()
        val changed=SpeechSettings.load(this)
        val old=settings
        if (old.stretchesPlayback && changed.sameSource(old) &&
            changed.sentencePauseMillis==old.sentencePauseMillis && changed.paragraphPauseMillis==old.paragraphPauseMillis) {
            if (aiPlayer?.setPlaybackRate(changed.playbackRate)==false) { settings=old;old.save(this);fail("This audio output cannot preserve pitch at ${changed.rate}×. Your previous speed is kept.");return }
            settings=changed
            SpeechController.mutable.value=state().copy(playbackRate=changed.rate,error=null)
            aiClip?.let { rememberAi(mandatory=true) }
            publishPlayback(); return
        }
        val continuePlaying=playRequested || state().playing
        pause(); settings=changed
        if (!changed.sameSource(old)) {
            clearAiAudio(); forgetAudio()
            if (aiEngine?.backend!=changed.backend) { aiEngine?.close(); aiEngine=null }
        }
        SpeechController.mutable.value=state().copy(backend=changed.backend,playbackRate=changed.rate,error=null)
        if (continuePlaying) scope.launch { resume() }
    }
    private fun configureSleep(minutes: Int, endChapter: Boolean) {
        sleepJob?.cancel(); sleepJob=null
        val now=android.os.SystemClock.elapsedRealtime()
        val continuePlaying=playRequested || state().playing
        if (endChapter) {
            val book=text ?: return
            val point=cursor ?: return
            val chapter=SpeechNavigation.chapterIndex(book,point)
            pause(); sleepTimer.armChapter(chapter)
        } else if (minutes in setOf(5,15,30,60)) sleepTimer.armMinutes(minutes,now) else sleepTimer.clear()
        SpeechController.mutable.value=state().copy(sleepRemainingSeconds=sleepTimer.remainingSeconds(now),sleepEndChapter=sleepTimer.chapter!=null)
        if (!endChapter && minutes>0) sleepJob=scope.launch {
            while (!sleepTimer.expired(android.os.SystemClock.elapsedRealtime())) {
                SpeechController.mutable.value=state().copy(sleepRemainingSeconds=sleepTimer.remainingSeconds(android.os.SystemClock.elapsedRealtime()))
                delay(1000)
            }
            pause(); sleepTimer.clear()
            SpeechController.mutable.value=state().copy(sleepRemainingSeconds=0,sleepEndChapter=false,preparation="Sleep timer ended")
        }
        if (endChapter && continuePlaying) scope.launch { resume() }
    }
    private fun cancelPreview() {
        previewGeneration++; previewJob?.cancel(); previewJob=null
        previewPlayer?.close(); previewPlayer=null
        // The cancelled job releases its reservation in finally, including any still-running cache IO.
        previewOwner=null
        if (state().previewing) {
            aiEngine?.cancelGeneration(); tts?.stop()
            if (wakeLock.isHeld) wakeLock.release()
            if (focusHeld) { audio.abandonAudioFocusRequest(focus); focusHeld=false }
            SpeechController.mutable.value=state().copy(previewing=false,preparing=false,playing=false,preparationStage="paused")
        }
    }
    private fun preview(selected: SpeechSettings, request: Long) {
        pause(); idleRelease?.cancel(); idleRelease=null
        generation++; loading?.cancel(); loading=null
        val ticket=++previewGeneration
        val phrase=if(selected.backend=="pico" || (selected.backend=="supertonic" && selected.supertonicLanguage=="da"))
            "Regnen er holdt op. Hun lukker bogen, tager sin jakke og går stille ud i den klare morgen."
        else "The rain has stopped. She closes the book, puts on her coat, and walks quietly into the clear morning."
        SpeechController.mutable.value=state().copy(previewing=true,preparing=true,playing=false,error=null,backend=selected.backend,
            preparation=if(selected.isLocal) "Buffering audio…" else "Preparing voice preview…",preparationStage=if(selected.isLocal) "buffering" else "speech",requestId=request)
        publishPlayback()
        previewJob=scope.launch {
            val owner=Any(); previewOwner=owner
            try {
                if (!focusHeld) focusHeld=audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                check(focusHeld) { "Another app is using audio. Pause it, then try preview." }
                wakeLock.acquire(120_000)
                if (!selected.isLocal) {
                    check(ensureEngine()) { "Android speech is unavailable." }
                    if(ticket!=previewGeneration) return@launch
                    val engine=tts ?: error("Android speech is unavailable.")
                    val voice=engine.voices.orEmpty().firstOrNull { it.name==selected.voice && it.locale.language=="en" && !it.isNetworkConnectionRequired }
                        ?: engine.voices.orEmpty().filter { it.locale.language=="en" && !it.isNetworkConnectionRequired }.maxByOrNull { it.quality }
                        ?: error("No installed offline English voice is available.")
                    check(engine.setVoice(voice)==TextToSpeech.SUCCESS && engine.setSpeechRate(selected.rate)==TextToSpeech.SUCCESS)
                    val done=CompletableDeferred<Unit>()
                    val id="preview:$ticket"
                    engine.setOnUtteranceProgressListener(object: UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { scope.launch { if(ticket==previewGeneration) SpeechController.mutable.value=state().copy(preparing=false,preparationStage="playing") } }
                        override fun onDone(utteranceId: String?) { if(utteranceId==id) done.complete(Unit) }
                        @Deprecated("Android legacy callback") override fun onError(utteranceId: String?) { done.completeExceptionally(IllegalStateException("The preview voice could not play.")) }
                    })
                    check(engine.speak(phrase,TextToSpeech.QUEUE_FLUSH,Bundle(),id)==TextToSpeech.SUCCESS)
                    kotlinx.coroutines.withTimeout(60_000) { done.await() }
                } else {
                    pcmBudget.acquire(owner)
                    val key=selected.audioKey(phrase)
                    val clip=withContext(Dispatchers.IO) { aiStore.loadClip(key) } ?: run {
                        val engine=localEngine(selected)
                        if(!engine.prepared) SpeechController.mutable.value=state().copy(preparation="Loading ${selected.engineLabel}…",preparationStage="loading")
                        engine.prepare()
                        if(ticket!=previewGeneration) return@launch
                        SpeechController.mutable.value=state().copy(preparation="Preparing speech…",preparationStage="speech")
                        engine.generate(phrase,selected).also { withContext(Dispatchers.IO) { aiStore.saveClip(key,it) } }
                    }
                    if(ticket!=previewGeneration) return@launch
                    pcmBudget.commit(owner,clip.samples.size.toLong()*4,clip.samples.size.toDouble()/clip.sampleRate)
                    val done=CompletableDeferred<Unit>()
                    val player=AiAudioPlayer(object: AiAudioPlayer.Listener {
                        override fun onStarted(utteranceId: String) { if(ticket==previewGeneration) SpeechController.mutable.value=state().copy(preparing=false,preparationStage="playing") }
                        override fun onCompleted(utteranceId: String) { done.complete(Unit) }
                        override fun onError(utteranceId: String,message: String) { done.completeExceptionally(IllegalStateException(message)) }
                    })
                    previewPlayer=player
                    check(player.setPlaybackRate(selected.playbackRate)) { "Pitch-preserving playback is unavailable. Try 1.0x." }
                    SpeechController.mutable.value=state().copy(preparation="Buffering audio…",preparationStage="buffering")
                    player.play(clip,"preview:$ticket")
                    kotlinx.coroutines.withTimeout(120_000) { done.await() }
                    delay(player.tailGuardMillis())
                }
                if(ticket==previewGeneration) { cancelPreview(); pause(); publishPlayback(); if(state().bookId==null) stopSelf() }
            } catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch(error: Throwable) {
                if(ticket==previewGeneration) {
                    cancelPreview(); pause(); SpeechController.mutable.value=state().copy(error=error.message ?: "Voice preview failed.",preparationStage="error")
                    publishPlayback(); if(state().bookId==null) stopSelf()
                }
            } finally { pcmBudget.release(owner); if(previewOwner===owner) previewOwner=null }
        }
    }
    private fun fail(message: String) {
        pause()
        SpeechController.mutable.value = state().copy(preparing = false, error = message, playing = false, preparationStage="error")
        publishPlayback()
    }
    private fun publishPlayback() {
        val current = state()
        val audible=current.playing || (current.previewing && !current.preparing)
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(if (audible) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (audible) settings.rate else 0f).build())
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }
    private fun notification(): Notification {
        val current = state()
        fun action(action: String) = PendingIntent.getService(this, action.hashCode(), Intent(this, SpeechService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, ReaderActivity::class.java).putExtra("book", current.bookId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_folio).setContentTitle(current.title.ifBlank { "Folio narration" })
            .setContentText(current.error ?: if (current.preparing) current.preparation else if(current.previewing) "Voice preview" else if (current.playing) "Reading aloud" else "Listening paused")
            .setContentIntent(open).setDeleteIntent(action(STOP)).setOnlyAlertOnce(true).setOngoing(current.playing || current.previewing).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous sentence", action(PREVIOUS)).build())
            .addAction(Notification.Action.Builder(if (current.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if(current.previewing) "Cancel preview" else if (current.playing) "Pause" else "Resume", action(if(current.previewing) CANCEL_PREVIEW else if (current.playing) PAUSE else RESUME)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Next sentence", action(NEXT)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", action(STOP)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2)).build()
    }
    override fun onDestroy() {
        destroyed = true
        pause()
        generation++; loading?.cancel(); gate.invalidate()
        checkpointSession?.let { SpeechCheckpointCoordinator.end(it) }; checkpointSession = null
        idleRelease?.cancel(); sleepJob?.cancel(); transitionJob?.cancel(); cancelPreview()
        tts?.stop(); tts?.shutdown()
        aiPlayer?.close(); aiEngine?.close()
        aiClip = null; pcmBudget.clearActive(); aiStore.unpin()
        if (wakeLock.isHeld) wakeLock.release()
        if (focusHeld) audio.abandonAudioFocusRequest(focus)
        runCatching { unregisterReceiver(noisy) }
        session.isActive = false; session.release()
        publication?.close()
        scope.cancel()
        SpeechController.serviceRunning=false
        SpeechController.mutable.value = state().copy(bookId = null, playing = false, preparing = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val START = "folio.speech.START"
        const val PAUSE = "folio.speech.PAUSE"
        const val RESUME = "folio.speech.RESUME"
        const val PREVIOUS = "folio.speech.PREVIOUS"
        const val NEXT = "folio.speech.NEXT"
        const val STOP = "folio.speech.STOP"
        const val SETTINGS = "folio.speech.SETTINGS"
        const val SEEK = "folio.speech.SEEK"
        const val NAVIGATE = "folio.speech.NAVIGATE"
        const val REPLAY = "folio.speech.REPLAY"
        const val SLEEP = "folio.speech.SLEEP"
        const val CLEAR_SLEEP = "folio.speech.CLEAR_SLEEP"
        const val PREVIEW = "folio.speech.PREVIEW"
        const val CANCEL_PREVIEW = "folio.speech.CANCEL_PREVIEW"
        private const val CHANNEL = "narration"
        private const val NOTIFICATION = 410
        private const val MAX_BUFFER_PASSAGES = 32
        private const val START_BUFFER_SECONDS = 12.0
    }
}
