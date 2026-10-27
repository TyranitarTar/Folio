@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package app.folio.reader

import android.app.Application
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material3.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontFamily as ComposeFont
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commitNow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse

class ReaderModel(app: Application) : AndroidViewModel(app) {
    val store = (app as FolioApp).library
    val publication = MutableStateFlow<Publication?>(null)
    val error = MutableStateFlow<String?>(null)
    val notice = MutableStateFlow<String?>(null)
    val location = MutableStateFlow<Locator?>(null)
    val searchResults = MutableStateFlow<List<Locator>>(emptyList())
    val searching = MutableStateFlow(false)
    internal val statistics = MutableStateFlow<BookStatistics?>(null)
    internal val informationWord = MutableStateFlow<Int?>(null)
    internal val statisticsError = MutableStateFlow<String?>(null)
    private var statisticsText: BookText? = null
    private var statisticsJob: Job? = null
    internal fun readingInformation(anchor: Locator?) {
        informationWord.value=null;statisticsError.value=null
        statisticsJob?.cancel()
        statisticsJob=viewModelScope.launch {
            try {
                val pub=publication.value ?: return@launch
                if(statistics.value==null)statistics.value=BookStatisticsStore.read(getApplication(),bookId)
                val text=statisticsText ?: try { BookText.load(pub,bookId) } catch(e: IllegalArgumentException) {
                    if(e.message !in listOf("This book contains no text for paced reading or speech.","This book has no readable text."))throw e
                    BookText(emptyList(),chapterEntries=pub.tableOfContents.mapNotNull { link -> pub.locatorFromLink(link)?.let { ReadingChapter(link.title ?: "Untitled chapter",0,it,null) } })
                }.also { statisticsText=it }
                val info=statistics.value ?: BookStatisticsStore.prepare(getApplication(),bookId,pub,text).also { statistics.value=it }
                informationWord.value=if(info.totalWords==0) 0 else anchor?.let { info.wordOffset(text,it) }
            } catch(e: kotlinx.coroutines.CancellationException){throw e}
            catch(e: Exception){statisticsError.value="Reading information is unavailable: ${e.message}"}
        }
    }
    var appearance by mutableStateOf(Appearance.load(app))
        private set
    var navigation by mutableStateOf(NavigationSettings())
        private set
    var bookNavigation by mutableStateOf(false)
        private set
    var notePosition by mutableStateOf<Locator?>(null)
    var noteDraft by mutableStateOf("")
    var lastReaderInteraction: Long? = null
    var positions: List<Locator> = emptyList()
    var bookId: String = ""
        private set
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private var readingSession: Long? = null
    private var pendingProgress: Locator? = null
    fun load(id: String) {
        if (bookId.isNotEmpty()) return
        bookId = id
        readingSession = store.beginReading(id)
        navigation = NavigationSettings.load(getApplication(), id)
        bookNavigation = NavigationSettings.overridden(getApplication(), id)
        viewModelScope.launch {
            try {
                store.load()
                val book = store.books.value.firstOrNull { it.id == id } ?: error("This book is no longer in your library.")
                location.value = book.position()
                val opened = withContext(Dispatchers.IO) { store.engine.open(store.file(id)) }
                try {
                    positions = withContext(Dispatchers.IO) { opened.positions() }
                    store.cacheRecordedPositions(id,positions)
                    runCatching {
                        val audioStore=(getApplication<Application>() as FolioApp).audiobooks
                        audioStore.load()
                        val active= AudiobookController.state.value.takeIf{it.bookId==id}
                        val audio=RecordedNarration.preferred(audioStore.books.value,id,active?.id)
                        if(audio!=null && (audio.lastPlayed>book.lastRead || active?.playing==true)) {
                            val index=active?.trackIndex ?: audio.positionTrack
                            val at=active?.positionMs ?: audio.positionMs
                            val cue=active?.cue ?: audio.tracks.getOrNull(index)?.cues?.firstOrNull{at>=it.startMs && at<it.endMs}
                            cue?.let(RecordedNarration::locator)?.let { source -> location.value=RecordedNarration.readingLocator(source,positions) }
                        }
                    }.onFailure{notice.value="The recording position could not be restored; your saved reading page is available."}
                    publication.value = opened
                } catch (e: Throwable) { opened.close(); throw e }
            } catch (e: Exception) { error.value = e.message ?: "The book could not be opened." }
        }
    }
    suspend fun moved(locator: Locator) {
        location.value = locator; pendingProgress = locator
        store.queueProgress(bookId, locator, session = readingSession) { notice.value = "Your reading position could not be saved." }
    }
    fun saveOnLeave() {
        pendingProgress?.let { locator -> store.queueProgress(bookId, locator, immediate = true, session = readingSession) { notice.value = "Your reading position could not be saved." } }
        pendingProgress = null
    }
    fun settings(value: Appearance) { appearance = value; value.save(getApplication()) }
    fun navigation(value: NavigationSettings, onlyThisBook: Boolean = bookNavigation) {
        bookNavigation = onlyThisBook; navigation = value
        NavigationSettings.save(getApplication(), bookId, value, onlyThisBook)
    }
    fun mark(locator: Locator, highlight: Boolean = false, note: String = "") {
        viewModelScope.launch {
            runCatching { store.addMark(bookId, locator, highlight, note) }
                .onSuccess { notice.value = if (highlight) "Highlight saved" else "Bookmark saved" }
                .onFailure { notice.value = "This mark could not be saved." }
        }
    }
    fun deleteMark(id: String) { viewModelScope.launch {
        runCatching { store.removeMark(bookId, id) }.onFailure { notice.value = "This mark could not be removed." }
    } }
    fun search(query: String) {
        val request = ++searchGeneration
        searchJob?.cancel()
        searchResults.value = emptyList()
        searching.value = query.isNotBlank()
        if (query.isBlank()) return
        searchJob = viewModelScope.launch {
            var iterator: org.readium.r2.shared.publication.services.search.SearchIterator? = null
            try {
                delay(350)
                iterator = publication.value?.search(query)
                if (iterator == null) { notice.value = "This book cannot be searched."; return@launch }
                val results = mutableListOf<Locator>()
                while (results.size < 200) {
                    ensureActive()
                    val page = withContext(Dispatchers.IO) { iterator.next().getOrElse { throw IllegalStateException("Search failed.") } } ?: break
                    results += page.locators
                    searchResults.value = results.take(200).toList()
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { notice.value = "The book could not be searched." }
            finally { iterator?.close(); if (request == searchGeneration) searching.value = false }
        }
    }
    fun cancelSearch() { searchGeneration++; searchJob?.cancel(); searching.value = false }
    override fun onCleared() { statisticsJob?.cancel(); searchJob?.cancel(); publication.value?.close(); super.onCleared() }
}

class ReaderActivity : FragmentActivity() {
    private val model: ReaderModel by viewModels()
    private var navigator: EpubNavigatorFragment? = null
    private var controls by mutableStateOf(true)
    private var showZones by mutableStateOf(false)
    private var sheet by mutableStateOf<String?>(null)
    private var notePosition: Locator?
        get() = model.notePosition
        set(value) { model.notePosition = value; if (value == null) model.noteDraft = "" }
    private var measuring: EpubNavigatorFragment? = null
    private var counter by mutableStateOf<PageCounter?>(null)
    private var guided by mutableStateOf<GuidedReading?>(null)
    private var speechStart by mutableStateOf<Locator?>(null)
    private var informationAnchor by mutableStateOf<Locator?>(null)
    private var restoreGuidedHighlight = false
    private var restoredGuidedSession = GuidedSession()
    private var viewport = ""
    private var preferencesJob: Job? = null
    private var speechFollowJob: Job? = null
    private var recordedFollowJob: Job? = null
    private lateinit var awake: ReaderAwakeController
    private val cafeReading get() = (application as FolioApp).cafeReading
    private var cafeJob: Job? = null
    private var cafeLastInput = android.os.SystemClock.elapsedRealtime()
    private fun readerInteraction() {
        cafeLastInput = android.os.SystemClock.elapsedRealtime()
        if(!::awake.isInitialized) return
        awake.interact();model.lastReaderInteraction=awake.session.lastInput
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) readerInteraction()
        return super.dispatchTouchEvent(event)
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if(event.actionMasked in listOf(MotionEvent.ACTION_SCROLL,MotionEvent.ACTION_BUTTON_PRESS)) readerInteraction()
        return super.dispatchGenericMotionEvent(event)
    }
    // Android's public Activity callback is inherited through AndroidX Core, whose override
    // carries LIBRARY_GROUP_PREFIX. Observe real keys here and preserve its key dispatch.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(event.action==KeyEvent.ACTION_DOWN && event.keyCode !in listOf(KeyEvent.KEYCODE_POWER,KeyEvent.KEYCODE_SLEEP,KeyEvent.KEYCODE_WAKEUP)) readerInteraction()
        return super.dispatchKeyEvent(event)
    }
    // Material sheets/dialogs own another Window, so their input bypasses Activity dispatch.
    private fun dialogInteraction() = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while(true) {
                val event=awaitPointerEvent(PointerEventPass.Initial)
                if(event.changes.any { it.pressed || it.previousPressed }) readerInteraction()
            }
        }
    }.onPreviewKeyEvent { readerInteraction();false }
    override fun onResume() {
        super.onResume(); if(::awake.isInitialized) awake.foreground(true)
        cafeLastInput = android.os.SystemClock.elapsedRealtime()
        cafeJob?.cancel()
        cafeJob = lifecycleScope.launch {
            while (true) {
                val attentive = guided?.running?.value == true || android.os.SystemClock.elapsedRealtime() - cafeLastInput < 120_000
                cafeReading.reader(hasWindowFocus() && navigator != null && sheet == null && !controls && attentive)
                delay(1000)
            }
        }
    }
    override fun onPause() {
        guided?.pause()
        cafeJob?.cancel(); cafeReading.reader(false)
        if(::awake.isInitialized) awake.foreground(false); super.onPause()
    }

    internal fun isRtlNavigation() = model.navigation.direction == "RTL" || (model.navigation.direction == "Book" &&
        model.publication.value?.metadata?.readingProgression == org.readium.r2.shared.publication.ReadingProgression.RTL)
    private fun manualNavigation() { if (AudiobookController.state.value.bookId == model.bookId) AudiobookController.pause(this); preferencesJob?.cancel(); preferencesJob = null; guided?.clear(); if (SpeechController.state.value.bookId == model.bookId) SpeechController.pause(this) }
    private fun turn(forward: Boolean) {
        manualNavigation()
        if (forward) navigator?.goForward(model.navigation.animate) else navigator?.goBackward(model.navigation.animate)
    }
    private fun openSheet(name: String) {
        guided?.pause()
        sheet = name
        if (name == "guided") guided?.prepare()
        if(name=="information") lifecycleScope.launch {
            val anchor=navigator?.visibleReadingAnchor() ?: model.location.value
            if(sheet=="information"){informationAnchor=anchor;model.readingInformation(anchor)}
        }
        if (name == "speech" || name == "guided") lifecycleScope.launch {
            speechStart = navigator?.visibleReadingAnchor() ?: model.location.value
        }
    }
    private fun listenRecorded(anchor: Locator?) {
        lifecycleScope.launch {
            val store = (application as FolioApp).audiobooks
            try {
                store.load()
                val audio = RecordedNarration.preferred(store.books.value, model.bookId, AudiobookController.state.value.takeIf { it.playing || it.loading }?.id)
                val target = audio?.let { RecordedNarration.find(it, anchor) }
                if (audio == null || target == null) { model.notice.value = "This passage has no recording yet."; return@launch }
                guided?.clear(); sheet = null; controls = false
                AudiobookController.start(this@ReaderActivity, audio.id, target.track, target.positionMs)
            } catch (error: Exception) { model.notice.value = error.message ?: "The recording could not start." }
        }
    }
    private var readerSeekJob: Job? = null
    private fun navigatePlayback(direction: Int) {
        if (guided?.active?.value == true) { guided?.navigate(direction); return }
        val state = SpeechController.state.value
        val unit = SpeechSettings.load(this).navigationUnit
        if (unit != "line") { SpeechController.navigate(this, direction, unit); return }
        readerSeekJob?.cancel()
        readerSeekJob = lifecycleScope.launch {
            val fragment = navigator ?: return@launch
            val anchor = state.locator ?: fragment.visibleReadingAnchor() ?: return@launch
            val line = fragment.renderedReadingLine(anchor, direction)
            if (line != null) SpeechController.seek(this@ReaderActivity, model.bookId, line.first)
            else SpeechController.navigate(this@ReaderActivity, direction, "sentence")
        }
    }
    private fun seekVisiblePage() {
        readerSeekJob?.cancel()
        readerSeekJob = lifecycleScope.launch {
            val anchor = navigator?.visibleReadingAnchor() ?: return@launch
            if (guided?.active?.value == true) guided?.seek(anchor)
            else SpeechController.seek(this@ReaderActivity, model.bookId, anchor)
        }
    }
    private fun readVisibleText() {
        readerSeekJob?.cancel()
        readerSeekJob = lifecycleScope.launch {
            val anchor = navigator?.visibleReadingAnchor()
            if (anchor == null) { model.notice.value = "The visible text is not ready yet."; return@launch }
            if (guided?.active?.value == true) guided?.seek(anchor)
            else { guided?.clear(); SpeechController.start(this@ReaderActivity, model.bookId, anchor); sheet = null; controls = false }
        }
    }
    private fun pageKey() = "v3:padding0:$viewport:${model.appearance.copy(theme = "Paper").preferences(model.navigation.direction)}"
    private fun updatePreferences() {
        val fragment = navigator ?: return
        preferencesJob?.cancel()
        preferencesJob = lifecycleScope.launch {
            val revisionBeforeReflow = SpeechController.state.value.seekRevision
            val guide = guided?.takeIf { it.hasHighlight }
            val spoken = SpeechController.state.value.takeIf { it.bookId == model.bookId && it.playing && SpeechController.follow.value }?.locator
            val passage = guide?.cursor?.value?.locator ?: spoken ?: fragment.visibleReadingAnchor() ?: fragment.firstVisibleElementLocator() ?: fragment.currentLocator.value
            val prefs = model.appearance.preferences(model.navigation.direction)
            fragment.submitPreferences(prefs); measuring?.submitPreferences(prefs)
            delay(300)
            val guideNow = guide?.takeIf { it.hasHighlight }
            val speechNow = SpeechController.state.value
            val latestSpoken = speechNow.takeIf { it.bookId == model.bookId && !it.previewing &&
                (it.playing || it.preparing || it.seekRevision != revisionBeforeReflow) }?.locator
            val target = guideNow?.cursor?.value?.locator ?: latestSpoken ?: passage
            fragment.go(target, false)
            guideNow?.restoreHighlight()
            counter?.recalculate(pageKey(), model.appearance.scroll)
        }
    }
    override fun onStart() { super.onStart(); counter?.setForeground(true) }
    override fun onStop() { counter?.setForeground(false); guided?.pause(); model.saveOnLeave(); super.onStop() }
    override fun onDestroy() {
        guided?.pause(); if(::awake.isInitialized) awake.close(); counter?.close(); preferencesJob?.cancel(); speechFollowJob?.cancel(); recordedFollowJob?.cancel(); readerSeekJob?.cancel()
        // Detach both renderers before the Activity clears its publication-owning ViewModel.
        // Readium destroys child WebViews on detach, including prefetched resource views.
        if (!supportFragmentManager.isDestroyed) supportFragmentManager.beginTransaction().apply {
            navigator?.let { remove(it) }; measuring?.let { remove(it) }
        }.commitNowAllowingStateLoss()
        navigator = null; measuring = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("folio.note", model.noteDraft)
        outState.putString("folio.noteLocator", model.notePosition?.toJSON()?.toString())
        outState.putString("folio.sheet", sheet)
        outState.putBoolean("folio.controls", controls)
        outState.putBoolean("folio.guidedActive", guided?.active?.value == true)
        outState.putInt("folio.guidedWords", guided?.session?.value?.words ?: 0)
        outState.putLong("folio.guidedMillis", guided?.session?.value?.elapsedMillis ?: 0)
        if(::awake.isInitialized) outState.putLong("folio.lastReaderInteraction",awake.session.lastInput)
    }
    // Construct pinned Readium XML views with network blocked before its fragment calls loadUrl.
    // INTERNET is available to the explicit updater; EPUB resources remain served locally.
    private fun offlineWebView(name: String, context: android.content.Context, attrs: android.util.AttributeSet): android.view.View? {
        if (name !in setOf("org.readium.r2.navigator.R2WebView", "org.readium.r2.navigator.R2BasicWebView")) return null
        return (Class.forName(name, false, context.classLoader)
            .getConstructor(android.content.Context::class.java, android.util.AttributeSet::class.java)
            .newInstance(context, attrs) as android.webkit.WebView).apply {
                settings.blockNetworkLoads = true
                setTag(R.id.offline_webview_constructed, true)
            }
    }
    override fun onCreateView(parent: android.view.View?, name: String, context: android.content.Context, attrs: android.util.AttributeSet): android.view.View? =
        offlineWebView(name, context, attrs) ?: super.onCreateView(parent, name, context, attrs)
    override fun onCreateView(name: String, context: android.content.Context, attrs: android.util.AttributeSet): android.view.View? =
        offlineWebView(name, context, attrs) ?: super.onCreateView(name, context, attrs)

    override fun onCreate(savedInstanceState: Bundle?) {
        savedInstanceState?.let { saved ->
            model.noteDraft = saved.getString("folio.note").orEmpty()
            model.notePosition = saved.getString("folio.noteLocator")?.let { raw -> runCatching { Locator.fromJSON(org.json.JSONObject(raw)) }.getOrNull() }
            sheet = saved.getString("folio.sheet")
            controls = saved.getBoolean("folio.controls", true)
            restoreGuidedHighlight = saved.getBoolean("folio.guidedActive", false)
            restoredGuidedSession = GuidedSession(saved.getInt("folio.guidedWords", 0), saved.getLong("folio.guidedMillis", 0))
        }
        // Recreate the navigator from persisted locators instead of restoring an uninitialised Fragment.
        super.onCreate(null)
        val now=android.os.SystemClock.elapsedRealtime()
        val restored=savedInstanceState?.takeIf { it.containsKey("folio.lastReaderInteraction") }?.getLong("folio.lastReaderInteraction")
        val last=model.lastReaderInteraction ?: restored ?: now
        awake=ReaderAwakeController(lifecycleScope,last) { enabled ->
            if(enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        model.lastReaderInteraction=awake.session.lastInput
        model.load(intent.getStringExtra("book") ?: "")
        setContentView(ComposeView(this).apply {
            setContent { FolioTheme(model.appearance.theme == "Night") { ReaderScreen() } }
        })
    }

    private val selectionMenu = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            guided?.pause()
            if(menu.findItem(android.R.id.copy)==null) menu.add(0, android.R.id.copy, 0, "Copy").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, 105, 1, "Danish dictionary (online)")
            menu.add(0, 101, 2, "Highlight").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, 102, 3, "Add note").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, 103, 4, "Read from selection").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            if ((application as FolioApp).audiobooks.books.value.any { it.bookId == model.bookId })
                menu.add(0, 104, 5, "Listen from selection").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId !in listOf(android.R.id.copy, 101, 102, 103, 104, 105)) return false
            lifecycleScope.launch {
                navigator?.currentSelection()?.locator?.let { selected ->
                    if (item.itemId == android.R.id.copy) {
                        val text=selected.text.highlight.orEmpty()
                        if(text.isNotBlank()) (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("Book passage",text))
                    } else if(item.itemId==105) {
                        val word=selected.text.highlight.orEmpty().trim().take(200)
                        if(word.isNotBlank()) runCatching { startActivity(Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://ordnet.dk/ddo/ordbog").buildUpon().appendQueryParameter("query",word).build())) }
                            .onFailure { model.notice.value="No browser is available for dictionary lookup." }
                    } else if (item.itemId == 104) listenRecorded(selected)
                    else if (item.itemId == 103) { guided?.clear(); SpeechController.start(this@ReaderActivity, model.bookId, selected); sheet = null; controls = false }
                    else if (item.itemId == 102) { model.noteDraft = ""; notePosition = selected } else model.mark(selected, highlight = true)
                }
                mode.finish()
                navigator?.clearSelection()
            }
            return true
        }
        override fun onDestroyActionMode(mode: ActionMode) {}
    }

    private fun createNavigator(publication: Publication, container: FrameLayout) {
        val factory = EpubNavigatorFactory(publication).createFragmentFactory(
            initialLocator = model.location.value,
            initialPreferences = model.appearance.preferences(model.navigation.direction),
            paginationListener = object : EpubNavigatorFragment.PaginationListener {
                override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) { counter?.pageChanged(pageIndex, locator, totalPages) }
                override fun onPageLoaded() { counter?.pageLoaded() }
            },
            configuration = EpubNavigatorFragment.Configuration(selectionActionModeCallback = selectionMenu, shouldApplyInsetsPadding = false, decorationTemplates=GuidedBand.templates()),
            listener = object : EpubNavigatorFragment.Listener {
                override fun onExternalLinkActivated(url: org.readium.r2.shared.util.AbsoluteUrl) {
                    if (url.toString().startsWith("https://") || url.toString().startsWith("http://")) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url.toString()))) }
                            .onFailure { model.notice.value = "No browser is available to open this link." }
                    }
                }
                override fun onResourceLoadFailed(href: org.readium.r2.shared.util.Url, error: org.readium.r2.shared.util.data.ReadError) {
                    model.notice.value = "A page could not be loaded. Try another chapter."
                }
            }
        )
        val fragment = factory.instantiate(classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
        supportFragmentManager.commitNow { replace(R.id.reader_container, fragment, "epub") }
        navigator = fragment
        counter = PageCounter(this, model.bookId, publication, fragment, {
            measuring ?: run {
                val hiddenFactory = EpubNavigatorFactory(publication).createFragmentFactory(
                    initialLocator = null, initialPreferences = model.appearance.preferences(model.navigation.direction),
                    configuration = EpubNavigatorFragment.Configuration(shouldApplyInsetsPadding = false, decorationTemplates=GuidedBand.templates()))
                val hidden = hiddenFactory.instantiate(classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
                supportFragmentManager.commitNow { replace(R.id.measure_container, hidden, "measure") }
                measuring = hidden
                hidden
            }
        }, lifecycleScope)
        guided = GuidedReading(this, model.bookId, publication, fragment, lifecycleScope)
        if (restoreGuidedHighlight) {
            restoreGuidedHighlight = false
            val guide = guided!!; guide.active.value = true; guide.session.value = restoredGuidedSession; guide.prepare()
            lifecycleScope.launch {
                guide.ready.first { it }
                if (guide.active.value) guide.restoreHighlight()
            }
        }
        if (intent.getBooleanExtra("speechControls", false)) {
            intent.removeExtra("speechControls")
            lifecycleScope.launch { delay(500); openSheet("speech") }
        }
        container.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val size = "${r-l}x${b-t}"
            if (r > l && b > t && size != viewport) {
                viewport = size
                counter?.recalculate(pageKey(), model.appearance.scroll)
            }
        }
        viewport = "${container.width}x${container.height}"
        counter?.recalculate(pageKey(), model.appearance.scroll)
        fragment.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                val view = fragment.publicationView
                if (view.width <= 0 || view.height <= 0) return false
                val rtl = model.navigation.direction == "RTL" || (model.navigation.direction == "Book" && publication.metadata.readingProgression == org.readium.r2.shared.publication.ReadingProgression.RTL)
                when (model.navigation.action(event.point.x / view.width, event.point.y / view.height, rtl)) {
                    TapAction.NEXT -> turn(true)
                    TapAction.PREVIOUS -> turn(false)
                    TapAction.MENU -> { guided?.pause(); controls = !controls }
                }
                return true
            }
            override fun onDrag(event: DragEvent): Boolean { manualNavigation(); return false }
        })
        speechFollowJob = lifecycleScope.launch {
            var seenSeekRevision = SpeechController.state.value.seekRevision
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(SpeechController.state, guided!!.active) { state, active -> state to active }
                    .distinctUntilChangedBy { (state, active) -> listOf(state.bookId, state.playing, state.preparing, state.locator, state.previewing, state.seekRevision, active) }
                    .collectLatest { (state, _) ->
                        val explicitSeek = state.seekRevision != seenSeekRevision
                        if (state.bookId == model.bookId && !state.previewing && (state.playing || state.preparing || explicitSeek)) guided?.clear()
                        val locator = state.locator
                        if (state.bookId != model.bookId || state.previewing || locator == null || guided?.active?.value == true) {
                            seenSeekRevision = state.seekRevision
                            fragment.commitDecorations(emptyList(), "speech"); return@collectLatest
                        }
                        if (fragment.currentLocator.value.href.removeFragment() != locator.href.removeFragment()) {
                            if (!state.playing && !explicitSeek) { seenSeekRevision = state.seekRevision; fragment.commitDecorations(emptyList(), "speech"); return@collectLatest }
                            fragment.go(locator, false); delay(120)
                        }
                        fragment.commitDecorations(listOf(Decoration("spoken-word", locator,
                            Decoration.Style.Highlight(tint = android.graphics.Color.rgb(145, 182, 225)))), "speech")
                        delay(25)
                        val visible = fragment.evaluateJavascript("(function(){let d=window.readium.getDecorations('speech').items[0];if(!d)return false;let r=d.range.getBoundingClientRect();return r.width>0&&r.left>=-2&&r.right<=innerWidth+2&&r.top>=-2&&r.bottom<=innerHeight+2;})()")
                        if ((state.playing || explicitSeek) && visible != "true") fragment.go(locator, false)
                        seenSeekRevision = state.seekRevision
                    }
            }
        }
        recordedFollowJob = lifecycleScope.launch {
            var seenRevision = AudiobookController.state.value.seekRevision
            var openingFromRecording = intent.getBooleanExtra("recordedFollow", false)
            intent.removeExtra("recordedFollow")
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AudiobookController.state.distinctUntilChangedBy { listOf(it.id, it.bookId, it.playing, it.loading, it.cue, it.seekRevision) }.collectLatest { state ->
                    val explicitSeek = state.seekRevision != seenRevision || openingFromRecording
                    seenRevision = state.seekRevision
                    val locator = state.cue?.let(RecordedNarration::locator)
                    if (state.bookId != model.bookId || locator == null) {
                        fragment.commitDecorations(emptyList(), "recorded-audio"); return@collectLatest
                    }
                    openingFromRecording = false
                    if (state.playing || state.loading || explicitSeek) guided?.clear()
                    if (state.playing || explicitSeek) {
                        if (fragment.currentLocator.value.href.removeFragment() != locator.href.removeFragment()) {
                            fragment.go(locator, false); delay(120)
                        }
                    }
                    fragment.commitDecorations(listOf(Decoration("recorded-sentence", locator,
                        Decoration.Style.Highlight(tint = android.graphics.Color.rgb(145, 182, 225)))), "recorded-audio")
                    if (state.playing || explicitSeek) {
                        delay(30)
                        val visible = fragment.evaluateJavascript("(function(){let d=window.readium.getDecorations('recorded-audio').items[0];if(!d)return false;let r=d.range.getBoundingClientRect();return r.width>0&&r.left>=-2&&r.right<=innerWidth+2&&r.top>=-2&&r.bottom<=innerHeight+2;})()")
                        if (visible != "true") fragment.go(locator, false)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                fragment.currentLocator.collect {
                    model.moved(it)
                    if (publication.metadata.presentation.layout == org.readium.r2.shared.publication.epub.EpubLayout.FIXED) counter?.pageChanged(0, it)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.store.books.map { books -> books.firstOrNull { it.id == model.bookId }?.highlights.orEmpty() }
                    .distinctUntilChanged().collectLatest { marks ->
                    fragment.commitDecorations(marks.mapNotNull { mark -> mark.position()?.let {
                        Decoration(mark.id, it, Decoration.Style.Highlight(tint = android.graphics.Color.rgb(230, 196, 107)))
                    } }, "highlights")
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (model.appearance.volumeKeys && event?.repeatCount == 0) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { turn(!model.navigation.invertVolume); return true }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { turn(model.navigation.invertVolume); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    private fun ReaderScreen() {
        val publication by model.publication.collectAsStateWithLifecycle()
        val books by model.store.books.collectAsStateWithLifecycle()
        val book = books.firstOrNull { it.id == model.bookId }
        val location by model.location.collectAsStateWithLifecycle()
        val error by model.error.collectAsStateWithLifecycle()
        val notice by model.notice.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        val appearance = model.appearance
        val pageLabel by (counter?.label ?: remember { MutableStateFlow("Calculating pages…") }).collectAsStateWithLifecycle()
        val guiding by (guided?.running ?: remember { MutableStateFlow(false) }).collectAsStateWithLifecycle()
        val speech by SpeechController.state.collectAsStateWithLifecycle()
        val audioStore = (application as FolioApp).audiobooks
        val recordings by audioStore.books.collectAsStateWithLifecycle()
        LaunchedEffect(audioStore) { runCatching { audioStore.load() } }
        val recording = RecordedNarration.preferred(recordings, model.bookId)
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let { lifecycleScope.launch {
                runCatching { model.store.exportNotes(model.bookId, it) }.onSuccess { model.notice.value = "Notes exported" }
                    .onFailure { model.notice.value = "Notes could not be exported." }
            } }
        }
        LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); model.notice.value = null } }
        // Non-layout policy/key settings do not pause reading, submit preferences or recount pages.
        LaunchedEffect(appearance.preferences(model.navigation.direction)) {
            guided?.pause()
            updatePreferences()
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = appearance.theme != "Night"
        }
        LaunchedEffect(appearance.awakePolicy,guiding,publication,error) {
            awake.policy(appearance.awakePolicy);awake.reading(publication!=null && error==null);awake.guided(guiding)
        }
        LaunchedEffect(controls) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (controls) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        LaunchedEffect(publication, model.navigation) {
            showZones = publication != null && model.navigation.overlay
            if (showZones) { delay(1000); showZones = false }
        }
        BackHandler {
            if (sheet != null) { sheet = null; model.cancelSearch() }
            else returnToLibrary(this@ReaderActivity,if(intent.getBooleanExtra("recordedFollow",false))"Audiobooks" else "Books")
        }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (controls) Column {
                    PublicationHeader(book?.title ?: "Opening book…",{
                        returnToLibrary(this@ReaderActivity,if(intent.getBooleanExtra("recordedFollow",false))"Audiobooks" else "Books")
                    },actions={
                        IconButton(onClick = { openSheet("search") }) { Icon(Icons.Outlined.Search, "Search book") }
                        IconButton(onClick = { openSheet("settings") }) { Text("Aa", fontSize = 19.sp) }
                    })
                    HorizontalDivider()
                }
            },
            bottomBar = {
                Column(Modifier.navigationBarsPadding()) {
                if (controls && publication != null) Column(Modifier.padding(horizontal = 12.dp)) {
                    var seek by remember(location) { mutableFloatStateOf((location?.locations?.totalProgression ?: 0.0).toFloat()) }
                    Slider(seek, { seek = it }, onValueChangeFinished = {
                        val positions = model.positions
                        manualNavigation()
                        if (positions.isNotEmpty()) navigator?.go(positions[((positions.size - 1) * seek).toInt()])
                    }, modifier = Modifier.fillMaxWidth().height(32.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        IconButton(onClick = { openSheet("contents") }) { Icon(Icons.Outlined.FormatListBulleted, "Contents and bookmarks") }
                        IconButton(onClick = { location?.let { model.mark(it) } }) { Icon(Icons.Outlined.BookmarkAdd, "Save bookmark") }
                        Row(Modifier.weight(1f).heightIn(min=48.dp).clickable(role=Role.Button) { readerInteraction();openSheet("information") }.semantics { contentDescription="Reading information" },verticalAlignment=Alignment.CenterVertically) {
                            Text(pageLabel,Modifier.weight(1f),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Icon(Icons.Outlined.Info,null,Modifier.size(14.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { turn(false) }) { Icon(Icons.AutoMirrored.Outlined.NavigateBefore, "Previous page") }
                        IconButton(onClick = { turn(true) }) { Icon(Icons.AutoMirrored.Outlined.NavigateNext, "Next page") }
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { if (guiding) guided?.pause() else openSheet("guided") }) {
                            Icon(if (guiding) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null)
                            Text(if (guiding) "Pause guided reading" else "Guided reading")
                        }
                        if (recording != null) TextButton(modifier=Modifier.semantics { contentDescription="Listen from here" },onClick = {
                            lifecycleScope.launch { listenRecorded(navigator?.visibleReadingAnchor() ?: model.location.value) }
                        }) { Icon(Icons.Outlined.Headphones, null); Text("Listen") }
                        TextButton(onClick = { openSheet("speech") }) { Icon(Icons.Outlined.RecordVoiceOver, null); Text("Read aloud") }
                    }
                }
                if (publication != null) RecordedPlaybackBar(model.bookId)
                if (publication != null) ReaderPlaybackBar(model.bookId, guided,
                    navigate = { direction -> navigatePlayback(direction) },
                    pageStart = { seekVisiblePage() }, readVisible = { readVisibleText() },
                    settings = { openSheet(if (guided?.active?.value == true) "guided" else "speech") })
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Unable to open this book", fontSize = 23.sp, fontFamily = ComposeFont.Serif)
                        Text(error!!, Modifier.padding(vertical = 16.dp))
                        Button(onClick = { returnToLibrary(this@ReaderActivity,"Books") }) { Text("Back to library") }
                    }
                    publication == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else -> AndroidView(factory = { context ->
                        FrameLayout(context).apply {
                            addView(FragmentContainerView(context).apply { id = R.id.measure_container; alpha = 0f; importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }, FrameLayout.LayoutParams(-1, -1))
                            addView(FragmentContainerView(context).apply { id = R.id.reader_container }, FrameLayout.LayoutParams(-1, -1))
                            post { if (!isFinishing && !isDestroyed) createNavigator(publication!!, this) }
                        }
                    }, modifier = Modifier.fillMaxSize())
                }
                if (showZones) TapZoneOverlay(model.navigation, Modifier.fillMaxSize(), isRtlNavigation())

            }
        }
        if (sheet != null && publication != null) ModalBottomSheet(onDismissRequest = { sheet = null; model.cancelSearch() }, modifier=dialogInteraction(), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            when (sheet) {
                "settings" -> SettingsPanel(appearance, model.navigation, model.bookNavigation, { model.navigation(model.navigation, it) }, { model.navigation(it) }) { readerInteraction();model.settings(it) }
                "guided" -> guided?.let { guide -> GuidedPanel(guide) { fromHere ->
                    lifecycleScope.launch {
                        SpeechController.pause(this@ReaderActivity)
                        AudiobookController.pause(this@ReaderActivity)
                        val from = navigator?.visibleReadingAnchor() ?: model.location.value
                        sheet = null; controls = false
                        delay(350)
                        guide.start(from, fromHere)
                    }
                } }
                "speech" -> Column {
                    SpeechPanel(model.bookId, speechStart ?: location, onAcceptedStart = { guided?.clear(); sheet = null; controls = false }) {
                        lifecycleScope.launch {
                            val from = navigator?.visibleReadingAnchor() ?: model.location.value
                            if (from != null) {
                                guided?.clear()
                                SpeechController.start(this@ReaderActivity, model.bookId, from)
                                sheet = null; controls = false
                            } else model.notice.value = "The visible text is not ready yet. Try again after the page loads."
                        }
                    }
                }
                "information" -> BookStatisticsPanel(model,counter,informationAnchor,
                    jump={ target -> manualNavigation();navigator?.go(target);sheet=null },
                    pauseSpeech={ SpeechController.pause(this@ReaderActivity); AudiobookController.pause(this@ReaderActivity) })
                "search" -> SearchPanel(model) { result -> manualNavigation(); navigator?.go(result); sheet = null; model.cancelSearch() }
                else -> OutlinePanel(publication!!, book, model, go = { link -> manualNavigation(); navigator?.go(link); sheet = null },
                    markGo = { mark -> manualNavigation(); mark.position()?.let { navigator?.go(it) }; sheet = null },
                    export = { export.launch("${book?.title ?: "Folio"} - notes.txt") })
            }
        }
        notePosition?.let { position ->
            val note = model.noteDraft
            AlertDialog(onDismissRequest = { notePosition = null }, modifier=dialogInteraction(), title = { Text("Add a note") },
                text = { OutlinedTextField(note, { readerInteraction();model.noteDraft = it }, label = { Text("Your note") }, minLines = 3, maxLines = 6) },
                confirmButton = { TextButton(onClick = { model.mark(position, highlight = true, note = note.trim()); notePosition = null }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { notePosition = null }) { Text("Cancel") } })
        }
    }
}

@Composable
internal fun SettingsPanel(value: Appearance, navigation: NavigationSettings, bookOnly: Boolean, bookScope: ((Boolean) -> Unit)?, navigate: (NavigationSettings) -> Unit, change: (Appearance) -> Unit) {
    val effectiveRtl = (androidx.activity.compose.LocalActivity.current as? ReaderActivity)?.isRtlNavigation() ?: (navigation.direction == "RTL")
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 650.dp).padding(horizontal = 24.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Text("Make it yours", fontSize = 27.sp, fontFamily = ComposeFont.Serif)
            Text("Reading appearance", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Paper", "Sepia", "Night").forEach { theme -> FilterChip(value.theme == theme, { change(value.copy(theme = theme)) }, label = { Text(theme) }) }
            }
            Text("Text size · ${(value.fontSize * 100).toInt()}%", Modifier.padding(top = 18.dp))
            Slider(value.fontSize, { change(value.copy(fontSize = it)) }, valueRange = .7f..2.0f)
            Text("Typeface", Modifier.padding(top = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Original", "Serif", "Sans").forEach { font -> FilterChip(value.font == font, { change(value.copy(font = font, publisher = font == "Original")) }, label = { Text(font) }) }
            }
            Text("Line spacing · ${"%.2f".format(value.lineHeight)}", Modifier.padding(top = 18.dp))
            Slider(value.lineHeight, { change(value.copy(lineHeight = it, publisher = false)) }, valueRange = 1.1f..2.2f)
            Text("Page margins", Modifier.padding(top = 10.dp))
            Slider(value.margins, { change(value.copy(margins = it, publisher = false)) }, valueRange = .3f..2f)
            SettingSwitch("Scroll instead of page turns", value.scroll) { change(value.copy(scroll = it)) }
            SettingSwitch("Use publisher's layout", value.publisher) { change(value.copy(publisher = it)) }
            Text("Publisher layout preserves the book's typography. Turn it off to use your own spacing and typeface.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val awake=value.awakePolicy
            ChoiceRow("Screen awake",listOf("Device timeout","Timed","Always"),when(awake.mode) { "device" -> "Device timeout"; "always" -> "Always"; else -> "Timed" }) {
                change(value.withAwake(awake.copy(mode=when(it) { "Device timeout" -> "device"; "Always" -> "always"; else -> "timed" })))
            }
            if(awake.mode=="timed") ChoiceRow("After interaction · minutes",ReaderAwakePolicy.presets.map { it.toString() },awake.minutes.toString()) {
                change(value.withAwake(awake.copy(minutes=it.toInt())))
            }
            Text(when(awake.mode) { "always" -> "Keep the visible book awake until you leave or lock the phone."; "device" -> "Use Android's normal screen timeout."; else -> "Stay awake for ${awake.minutes} minutes after touch or page keys, then use Android's timeout." },fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Guided reading keeps the visible page awake while it runs. Narration can continue with the screen off.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            SettingSwitch("Turn pages with volume keys", value.volumeKeys) { change(value.copy(volumeKeys = it)) }
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            Text("Page navigation", style = MaterialTheme.typography.titleLarge)
            bookScope?.let { SettingSwitch("Navigation settings for this book only", bookOnly, it) }
            ChoiceRow("Tap layout", NavigationSettings.layouts, navigation.layout) { navigate(navigation.copy(layout = it)) }
            TapPreview(navigation, effectiveRtl, Modifier.padding(top = 12.dp))
            ChoiceRow("Invert tap zones", listOf("None", "Horizontal", "Vertical", "Both"), navigation.inversion) { navigate(navigation.copy(inversion = it)) }
            ChoiceRow("Reading direction", listOf("Book", "LTR", "RTL"), navigation.direction) { navigate(navigation.copy(direction = it)) }
            SettingSwitch("Animate page turns", navigation.animate) { navigate(navigation.copy(animate = it)) }
            SettingSwitch("Briefly show tap zones when opening", navigation.overlay) { navigate(navigation.copy(overlay = it)) }
            SettingSwitch("Invert volume buttons", navigation.invertVolume) { navigate(navigation.copy(invertVolume = it)) }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f)); Switch(value, change)
    }
}

private fun flatten(links: List<Link>, depth: Int = 0): List<Pair<Link, Int>> = links.flatMap { listOf(it to depth) + flatten(it.children, depth + 1) }

@Composable
private fun OutlinePanel(publication: Publication, book: Book?, model: ReaderModel, go: (Link) -> Unit, markGo: (Mark) -> Unit, export: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 20.dp)) {
        Text("Inside this book", fontSize = 26.sp, fontFamily = ComposeFont.Serif, modifier = Modifier.padding(bottom = 16.dp))
        TabRow(tab) {
            listOf("Contents", "Bookmarks", "Notes").forEachIndexed { index, title -> Tab(tab == index, { tab = index }, text = { Text(title) }) }
        }
        if (tab == 0) {
            val links = flatten(publication.tableOfContents.ifEmpty { publication.readingOrder })
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(links) { (link, depth) ->
                    Text(link.title ?: "Section ${publication.readingOrder.indexOf(link) + 1}", Modifier.fillMaxWidth().clickable { go(link) }.padding(start = (16 + depth * 16).dp, top = 16.dp, bottom = 16.dp))
                    HorizontalDivider()
                }
            }
        } else {
            val marks = if (tab == 1) book?.bookmarks.orEmpty() else book?.highlights.orEmpty()
            if (tab == 2 && marks.isNotEmpty()) TextButton(onClick = export) { Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Export notes") }
            if (marks.isEmpty()) Text(if (tab == 1) "Save a bookmark with the bookmark button." else "Long-press text to highlight it or add a note.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(marks, key = { it.id }) { mark ->
                    Row(Modifier.fillMaxWidth().clickable { markGo(mark) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(mark.quote)
                            if (mark.note.isNotBlank()) Text(mark.note, Modifier.padding(top = 6.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { model.deleteMark(mark.id) }) { Icon(Icons.Outlined.DeleteOutline, "Remove mark") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SearchPanel(model: ReaderModel, go: (Locator) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results by model.searchResults.collectAsStateWithLifecycle()
    val searching by model.searching.collectAsStateWithLifecycle()
    LaunchedEffect(query) { model.search(query.trim()) }
    Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).padding(horizontal = 22.dp)) {
        Text("Find a passage", fontSize = 26.sp, fontFamily = ComposeFont.Serif, modifier = Modifier.padding(bottom = 16.dp))
        OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search this book") }, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Outlined.Search, null) })
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
        Text(if (results.size >= 200) "Showing the first 200 matches" else "${results.size} matches", Modifier.padding(vertical = 12.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(results) { locator ->
                Column(Modifier.fillMaxWidth().clickable { go(locator) }.padding(vertical = 14.dp)) {
                    if (locator.title != null) Text(locator.title!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    Text((locator.text.before.orEmpty().takeLast(60) + locator.text.highlight.orEmpty() + locator.text.after.orEmpty().take(80)).ifBlank { "Matching passage" }, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
                HorizontalDivider()
            }
        }
    }
}
