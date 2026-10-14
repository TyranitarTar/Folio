@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package app.folio.reader

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

data class Pace(val unit: String = "WPM", val speed: Double = 200.0) {
    fun interval(characters: Int): Long = (if (unit == "CPS") 1000.0 * characters.coerceAtLeast(1) / speed else 60000.0 / speed).toLong().coerceAtLeast(20)
    fun save(context: Context) { context.getSharedPreferences("pace", 0).edit().putString("unit", unit).putFloat("speed", speed.toFloat()).apply() }
    companion object { fun load(context: Context): Pace { val p = context.getSharedPreferences("pace", 0); return Pace(p.getString("unit", "WPM").takeIf { it in listOf("WPM", "CPS") } ?: "WPM", p.getFloat("speed", 200f).toDouble().takeIf { it.isFinite() && it > 0 } ?: 200.0) } }
}

class GuidedReading(private val context: Context, private val id: String, private val publication: Publication,
    private val navigator: EpubNavigatorFragment, private val scope: CoroutineScope) {
    val running = MutableStateFlow(false)
    val ready = MutableStateFlow(false)
    val cursor = MutableStateFlow<ReadingCursor?>(null)
    val message = MutableStateFlow<String?>(null)
    val storageFailed = MutableStateFlow(false)
    val active = MutableStateFlow(false)
    val session = MutableStateFlow(GuidedSession())
    val countdown = MutableStateFlow(0)
    private var selectedOptions by mutableStateOf(GuidedOptions.load(context))
    val options: GuidedOptions get() = selectedOptions
    private var selectedPace by mutableStateOf(Pace.load(context))
    val pace: Pace get() = selectedPace
    private var text: BookText? = null
    private var job: Job? = null
    private var renderJob: Job? = null
    private var navigationJob: Job? = null
    private val storage = ReaderCursorStore(context)
    private var resumeAfterImage: ReadingCursor? = null
    var hasHighlight = false
        private set
    private var preparing: Job? = null
    private var epoch = 0L
    private var generation = 0L
    private var lastSaved = 0L
    fun prepare() {
        if (ready.value || preparing?.isActive == true) return
        preparing = scope.launch {
            try {
                message.value = "Preparing guided reading…"
                val restored = GuidedProgress.begin(context.applicationContext, id)
                epoch = restored.first
                text = BookText.load(publication, id)
                cursor.value = text!!.restore(restored.second)
                ready.value = true; message.value = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { message.value = e.message ?: "This book cannot be used for guided reading." }
        }
    }
    private fun checkpoint() {
        val point = cursor.value ?: return
        if (epoch == 0L || storageFailed.value) return
        GuidedProgress.save(context.applicationContext, id, epoch, point) {
            scope.launch { storageFailed.value = true; message.value = "Guided position could not be saved. Free storage, then retry saving."; pause() }
        }
        lastSaved = android.os.SystemClock.elapsedRealtime()
    }
    fun retrySaving() {
        val point = cursor.value ?: return
        scope.launch {
            try {
                GuidedProgress.exclusive {
                    check(java.io.File(context.filesDir, "library/$id.epub").exists()) { "This book is no longer in the library." }
                    storage.write(id, "guided", point)
                }
                storageFailed.value = false; message.value = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { message.value = "The guided position still cannot be saved: ${e.message}" }
        }
    }
    fun setOptions(value: GuidedOptions) { pause(); selectedOptions = value.normalized(); options.save(context); renderJob = scope.launch { if (hasHighlight) restoreHighlight() } }
    fun resetSession() { session.value = GuidedSession() }
    fun seek(locator: Locator) {
        resumeAfterImage = null
        val wasRunning = running.value
        pause()
        val book = text ?: return
        cursor.value = book.locate(locator); active.value = true
        if (wasRunning) start() else { renderJob?.cancel(); renderJob = scope.launch { restoreHighlight(); checkpoint() } }
    }
    fun navigate(direction: Int) {
        val book = text ?: return; val current = cursor.value ?: return
        navigationJob?.cancel()
        val unit = options.navigationUnit
        val range = SpeechNavigation.range(book, current, unit) ?: return
        val target = SpeechNavigation.target(book, current, direction, unit,
            replayCurrent = direction < 0 && current.wordIndex > range.first) ?: return
        seek(target.locator)
    }
    fun replaySentence() { val book = text ?: return; val point = cursor.value ?: return
        book.sentence(point)?.let { book.cursor(point.sectionIndex, it.firstWord)?.let { seek(it.locator) } } }
    fun setPace(value: Pace) { selectedPace = value.copy(speed = value.speed.takeIf { it.isFinite() }?.coerceIn(if (value.unit == "CPS") 1.0 else 30.0, if (value.unit == "CPS") 200.0 else 1200.0) ?: 200.0); pace.save(context) }
    fun pause() { generation++; renderJob?.cancel(); renderJob = null; job?.cancel(); job = null; running.value = false; countdown.value = 0; checkpoint() }
    fun start(from: Locator? = null, fromHere: Boolean = false) {
        pause()
        if (storageFailed.value) return
        val book = text ?: run { prepare(); return }
        val ticket = generation
        val beginning = if (fromHere) book.locate(from) else resumeAfterImage ?: cursor.value ?: book.locate(from)
        resumeAfterImage = null
        cursor.value = beginning
        message.value = null
        if (options.practiceMinutes > 0 && session.value.elapsedMillis >= options.practiceMinutes * 60000L) resetSession()
        job = scope.launch {
            try {
                running.value = true; active.value = true
                for (remaining in options.countdown downTo 1) { countdown.value = remaining; delay(1000) }
                countdown.value = 0
                var position = beginning
                var rampMinute = session.value.elapsedMillis / 60000
                while (position != null) {
                    ensureActive()
                    cursor.value = position
                    val cycleStarted = android.os.SystemClock.elapsedRealtime()
                    val started = show(position, force = false)
                    if (started - lastSaved >= 1000) checkpoint()
                    val section = book.sections[position.sectionIndex]
                    val paragraph = book.paragraph(position)!!
                    val available = paragraph.endWord - position.wordIndex
                    val lineEnd = if (options.mode == "Line") navigator.renderedReadingLine(position.locator)?.last
                        ?.let { book.locate(it) }?.takeIf { it.sectionIndex == position.sectionIndex && it.wordIndex >= position.wordIndex }
                        ?.wordIndex?.plus(1)?.coerceAtMost(paragraph.endWord) else null
                    val consumed = if (lineEnd != null) lineEnd - position.wordIndex else
                        GuidedTiming.consumed(options.mode, options.words, available)
                    val words = section.words.subList(position.wordIndex, position.wordIndex + consumed)
                    fun duration() = GuidedTiming.milliseconds(pace.unit, pace.speed,
                        words.map { it.text to it.characterCount }, options.punctuation, options.longWords,
                        position!!.wordIndex + consumed == paragraph.endWord)
                    var fraction = (android.os.SystemClock.elapsedRealtime() - started).toDouble() / duration()
                    var last = android.os.SystemClock.elapsedRealtime()
                    while (fraction < 1) {
                        delay(20)
                        val now = android.os.SystemClock.elapsedRealtime()
                        fraction += (now - last).toDouble() / duration(); last = now
                    }
                    val elapsed = android.os.SystemClock.elapsedRealtime() - cycleStarted
                    session.value = session.value.copy(words = session.value.words + consumed,
                        elapsedMillis = session.value.elapsedMillis + elapsed)
                    if (options.practiceMinutes > 0 && session.value.elapsedMillis >= options.practiceMinutes * 60000L) {
                        var savedNext: ReadingCursor? = position
                        repeat(consumed) { savedNext = savedNext?.let { book.next(it) } }
                        savedNext?.let { cursor.value = it; show(it, force = false) }
                        message.value = "Practice complete — reflect on what you understood."; break
                    }
                    val minute = session.value.elapsedMillis / 60000
                    if (options.gradualIncrease && pace.unit == "WPM" && minute > rampMinute) {
                        setPace(pace.copy(speed = pace.speed + 25)); rampMinute = minute
                    }
                    var next: ReadingCursor? = position
                    repeat(consumed) { next = next?.let { book.next(it) } }
                    val following = next
                    if (following != null && following.sectionIndex != position.sectionIndex) {
                        val order = publication.readingOrder
                        val a = order.indexOfFirst { it.url().removeFragment() == position!!.locator.href.removeFragment() }
                        val b = order.indexOfFirst { it.url().removeFragment() == following.locator.href.removeFragment() }
                        if (b > a + 1 && a >= 0) {
                            resumeAfterImage = following
                            navigator.go(order[a + 1], false)
                            message.value = "Illustration — resume when you're ready."
                            break
                        }
                    }
                    position = following
                }
                if (position == null) message.value = "End of book"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = "Guided reading paused: ${e.message}" }
            finally { if (ticket == generation) { running.value = false; countdown.value = 0; checkpoint() } }
        }
    }
    suspend fun restoreHighlight() { cursor.value?.let { show(it, force = true) } }
    private suspend fun show(position: ReadingCursor, force: Boolean): Long {
        hasHighlight = true; active.value = true
        var movedPage = false
        if (navigator.currentLocator.value.href.removeFragment() != position.locator.href.removeFragment()) {
            movedPage = true
            navigator.go(position.locator, false)
            delay(120)
        }
        val applied = android.os.SystemClock.elapsedRealtime()
        val tint=GuidedBand.tint(context)
        navigator.commitDecorations(listOf(Decoration("current-word", position.locator,GuidedBandStyle(tint))), "guided")
        // Resolve invisible anchors without replacing the previous complete painted window.
        withTimeout(2500) {
            while(navigator.evaluateJavascript(GuidedBand.anchorReady(position.locator))!="true") delay(12)
        }
        val book = text
        if (book != null) {
            val paragraphEnd = book.paragraph(position)?.endWord ?: position.wordIndex + 1
            val end = if (options.mode == "Line") navigator.renderedReadingLine(position.locator)?.last
                ?.let { book.locate(it) }?.takeIf { it.sectionIndex == position.sectionIndex && it.wordIndex >= position.wordIndex }
                ?.wordIndex?.plus(1)?.coerceAtMost(paragraphEnd) ?: (position.wordIndex + 1)
                else (position.wordIndex + options.words).coerceAtMost(paragraphEnd)
            val last=book.cursor(position.sectionIndex,end-1) ?: position
            GuidedBand.apply(navigator,position.locator,last.locator,tint)
        }
        // Paint the complete destination while offscreen before a same-document page jump.
        val visible = navigator.evaluateJavascript("""(function(){let d=window.readium.getDecorations('guided').items[0];if(!d)return false;let r=d.range.getBoundingClientRect();return r.width>0&&r.left>=-2&&r.right<=innerWidth+2&&r.top>=-2&&r.bottom<=innerHeight+2;})()""")
        if (force || visible != "true") {
            movedPage = true
            navigator.go(position.locator, false)
            withTimeout(2500) {
                while (navigator.evaluateJavascript("(function(){let d=window.readium.getDecorations('guided').items[0];if(!d)return false;let r=d.range.getBoundingClientRect();return r.width>0&&r.left>=-2&&r.right<=innerWidth+2&&r.top>=-2&&r.bottom<=innerHeight+2;})()") != "true") delay(20)
            }
        }
        // Include ordinary decoration work in the scheduled interval; page transitions get full visible dwell.
        return if (movedPage) android.os.SystemClock.elapsedRealtime() else applied
    }
    fun clear() { resumeAfterImage = null; pause(); navigationJob?.cancel(); navigationJob = null; hasHighlight = false; active.value = false; scope.launch { navigator.commitDecorations(emptyList(), "guided") } }
}
