@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package app.folio.reader

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.toArgb
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Mihon's library/navigation patterns, adapted to Folio's existing offline EPUB data. */
@Composable
fun FolioHome(model: LibraryViewModel, open: (Book) -> Unit, speech: (Book) -> Unit) {
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    var prefs by remember { mutableStateOf(HomePreferences.load(context)) }
    val night = prefs.theme == "Dark" || (prefs.theme == "System" && isSystemInDarkTheme())
    FolioTheme(night) {
        val statusColor = MaterialTheme.colorScheme.surface.toArgb()
        val navigationColor = MaterialTheme.colorScheme.surfaceContainer.toArgb()
        SideEffect {
            (context as? Activity)?.window?.let { window ->
                window.statusBarColor = statusColor
                window.navigationBarColor = navigationColor
                WindowCompat.getInsetsController(window, window.decorView).let { controller ->
                    controller.isAppearanceLightStatusBars = !night
                    controller.isAppearanceLightNavigationBars = !night
                }
            }
        }
        val books by model.books.collectAsStateWithLifecycle()
        val cafe = (context.applicationContext as FolioApp).cafe
        val cafeProfiles by cafe.profiles.collectAsStateWithLifecycle()
        LaunchedEffect(cafe) { runCatching { cafe.load() } }
        val busy by model.busy.collectAsStateWithLifecycle()
        val loading by model.loading.collectAsStateWithLifecycle()
        val notice by model.notice.collectAsStateWithLifecycle()
        val storedShelves by model.store.shelves.collectAsStateWithLifecycle()
        val recovery by model.store.recovery.collectAsStateWithLifecycle()
        val tabState = rememberSaveableStateHolder()
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var tab by rememberSaveable { mutableStateOf("Library") }
        var query by rememberSaveable { mutableStateOf("") }
        var searching by rememberSaveable { mutableStateOf(false) }
        var shelf by rememberSaveable { mutableStateOf("All books") }
        var detailId by rememberSaveable { mutableStateOf<String?>(null) }
        var selected by remember { mutableStateOf(setOf<String>()) }
        var settings by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var shelfDialog by remember { mutableStateOf(false) }
        var newShelf by remember { mutableStateOf(false) }
        var removeDialog by remember { mutableStateOf(false) }
        var readerSettings by remember { mutableStateOf(false) }
        var about by remember { mutableStateOf(false) }
        var historyNotes by rememberSaveable { mutableStateOf(false) }
        var updates by rememberSaveable { mutableStateOf(false) }
        var speechSettings by rememberSaveable { mutableStateOf(false) }
        var voicePacks by rememberSaveable { mutableStateOf(false) }
        var storage by rememberSaveable { mutableStateOf(false) }
        var renameShelf by remember { mutableStateOf<String?>(null) }
        var licenses by remember { mutableStateOf(false) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.import(it) }
        fun importBooks() = picker.launch(arrayOf("application/epub+zip", "application/octet-stream", "application/zip"))
        fun update(value: HomePreferences) { prefs = value; value.save(context) }
        fun toggle(book: Book) { selected = if (book.id in selected) selected - book.id else selected + book.id }
        fun at(book: Book, locator: org.readium.r2.shared.publication.Locator) = scope.launch {
            runCatching { model.store.progress(book.id, locator) }.onSuccess { open(book) }
                .onFailure { model.notice.value = "This reading position could not be saved." }
        }
        LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); model.notice.value = null } }
        LaunchedEffect(books) { selected = selected.intersect(books.map { it.id }.toSet()) }
        LaunchedEffect(prefs.shelves) { if (shelf != "All books" && shelf !in prefs.shelves) shelf = "All books" }
        LaunchedEffect(storedShelves, loading) {
            if (!loading && prefs.shelves != storedShelves) update(prefs.copy(shelves = storedShelves))
        }
        val visible = remember(books, prefs, query, shelf) { prefs.visibleBooks(books, query, shelf) }
        val detail = books.firstOrNull { it.id == detailId }
        BackHandler(detail != null || selected.isNotEmpty() || searching || tab != "Library") {
            when { selected.isNotEmpty() -> selected = emptySet(); detail != null -> detailId = null
                searching -> { searching = false; query = "" }; else -> tab = "Library" }
        }
        if (detail != null) {
            tabState.SaveableStateProvider("details:${detail.id}") {
                BookDetails(detail, model.store, open, speech, { at(detail, it) }, { detailId = null },
                    { selected = setOf(detail.id); shelfDialog = true }, { selected = setOf(detail.id); removeDialog = true }, snackbar)
            }
        } else Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (searching && tab in listOf("Library", "History")) Row(Modifier.fillMaxWidth().statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close search") }
                    TextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search title or author") },
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                    IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear search") }
                } else TopAppBar(title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (selected.isNotEmpty()) "${selected.size} selected" else tab)
                        if (tab == "Library" && selected.isEmpty()) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(visible.size.toString(), Modifier.padding(horizontal = 10.dp, vertical = 2.dp), fontSize = 14.sp)
                        }
                    }
                }, navigationIcon = { if (selected.isNotEmpty()) IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Outlined.Close, "Clear selection") } }, actions = {
                    if (selected.isNotEmpty()) {
                        IconButton(onClick = { selected = visible.map { it.id }.toSet() }) { Icon(Icons.Outlined.SelectAll, "Select all") }
                        IconButton(onClick = { shelfDialog = true }) { Icon(Icons.Outlined.FolderOpen, "Assign shelf") }
                        IconButton(onClick = { removeDialog = true }) { Icon(Icons.Outlined.DeleteOutline, "Remove selected books") }
                    } else {
                        if (tab in listOf("Library", "History")) IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Search library") }
                        if (tab == "Library") IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.FilterList, "Library settings") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Library menu") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Import EPUB") }, onClick = { menu = false; importBooks() })
                                DropdownMenuItem(text = { Text("Manage shelves") }, onClick = { menu = false; shelfDialog = true })
                                DropdownMenuItem(text = { Text("Open sample book") }, onClick = { menu = false; model.sample(open) })
                                DropdownMenuItem(text = { Text("About Folio") }, onClick = { menu = false; about = true })
                            }
                        }
                    }
                })
            },
            bottomBar = { NavigationBar {
                (listOf("Library" to Icons.Outlined.CollectionsBookmark, "History" to Icons.Outlined.History) +
                    (if(cafeProfiles.normal.enabled || tab == "Café") listOf("Café" to Icons.Outlined.Pets) else emptyList()) +
                    listOf("Browse" to Icons.Outlined.TravelExplore, "More" to Icons.Outlined.MoreHoriz)).forEach { (name, icon) ->
                    NavigationBarItem(tab == name, { tab = name; selected = emptySet(); query = ""; searching = false },
                        icon = { Icon(icon, null) }, label = { Text(name) })
                }
            } },
            floatingActionButton = { if (tab == "Library" && selected.isEmpty()) FloatingActionButton(onClick = { importBooks() }) { Icon(Icons.Outlined.Add, "Import EPUB") } }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(Modifier.weight(1f))
                    TextButton(onClick = { model.cancelImport() }) { Text("Cancel import") }
                }
                if (recovery != null) TextButton(onClick = { storage = true }) { Text("Library recovery available") }
                tabState.SaveableStateProvider(if (tab == "Library") "$tab:$shelf:${prefs.layout}" else tab) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    tab == "Library" -> {
                        ScrollableTabRow((listOf("All books") + prefs.shelves).indexOf(shelf).coerceAtLeast(0), edgePadding = 12.dp) {
                            (listOf("All books") + prefs.shelves).forEach { label -> Tab(shelf == label, { shelf = label }, text = { Text(label) }) }
                        }
                        OutlinedCard(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp)) {
                            Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Outlined.Headphones,null)
                                Column {
                                    Text("Audiobooks",style=MaterialTheme.typography.titleSmall)
                                    Text("Coming later · your audiobook library",style=MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (prefs.status != "All" || prefs.annotated) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { settings = true }, label = { Text(listOfNotNull(prefs.status.takeIf { it != "All" }, "Annotated".takeIf { prefs.annotated }).joinToString(" · ")) })
                            TextButton(onClick = { update(prefs.copy(status = "All", annotated = false)) }) { Text("Clear filters") }
                        }
                        if (books.isEmpty()) EmptyLibrary({ importBooks() }, { model.sample(open) })
                        else if (visible.isEmpty()) EmptyMessage("No books match", "Change the shelf, search or filters to see more books.", Icons.Outlined.FilterList)
                        else LibraryBooks(visible, model.store, prefs, selected, { if (selected.isEmpty()) detailId = it.id else toggle(it) }, ::toggle, open)
                    }
                    tab == "History" -> {
                        val history = books.filter { it.lastRead > 0 && (query.isBlank() || "${it.title} ${it.author}".contains(query, true)) }.sortedByDescending { it.lastRead }
                        if (history.isEmpty()) EmptyMessage("No reading history", "Books you open will appear here with their saved reading position.", Icons.Outlined.History)
                        else LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
                            val groups = history.groupBy { Instant.ofEpochMilli(it.lastRead).atZone(ZoneId.systemDefault()).toLocalDate() }
                            groups.forEach { (day, entries) ->
                                item { Text(day.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")), Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                                items(entries, key = { it.id }) { book -> LibraryBookRow(book, model.store, prefs.showProgress, false, { detailId = book.id }, {}, { open(book) }) }
                            }
                        }
                    }
                    tab == "Browse" -> SourceBrowser(open, { importBooks() }, { model.sample(open) })
                    tab == "Café" -> CafeScreen()
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        HomeAction("Cat Café", "Miso’s Danish story, treat interactions and test mode", Icons.Outlined.Pets, { tab = "Café" })
                        HomeAction("Reader settings", "Typography, page turns and tap zones", Icons.Outlined.MenuBook, { readerSettings = true })
                        HomeAction("Speech settings", "Offline voices and playback speed", Icons.Outlined.RecordVoiceOver, { speechSettings = true })
                        HomeAction("Voice packs", "Listen to samples and download offline voices", Icons.Outlined.Download, { voicePacks = true })
                        HomeAction("Library display", "Grid, list, sorting and filters", Icons.Outlined.GridView, { settings = true })
                        HomeAction("Shelves", "Organise your library", Icons.Outlined.FolderOpen, { shelfDialog = true })
                        HomeAction("Storage and backup", "Export, restore, repair and free space", Icons.Outlined.Save, { storage = true })
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        ChoiceRow("App theme", listOf("System", "Light", "Dark"), prefs.theme) { update(prefs.copy(theme = it)) }
                        Spacer(Modifier.height(16.dp))
                        HomeAction("Version history", "What changed in each release", Icons.Outlined.History, { historyNotes = true })
                        HomeAction("App updates", "Check GitHub for a newer signed version", Icons.Outlined.SystemUpdate, { updates = true })
                        HomeAction("About Folio", "Version $version", Icons.Outlined.Info, { about = true })
                        HomeAction("Licenses", "Mihon, Readium and offline voice credits", Icons.Outlined.Description, { licenses = true })
                    }
                }
                }
            }
        }
        if (historyNotes) VersionHistorySheet { historyNotes = false }
        if (updates) AppUpdatesSheet(close = { updates = false })
        if (voicePacks) VoicePacksPanel(close = { voicePacks = false })
        if (speechSettings) ModalBottomSheet(onDismissRequest = { speechSettings = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            SpeechPanel("", null, settingsOnly = true) {}
        }
        if (storage) StoragePanel(model, { prefs = HomePreferences.load(context).copy(shelves = model.store.shelves.value) }, { storage = false })
        if (settings) LibrarySettingsSheet(prefs, ::update) { settings = false }
        if (readerSettings) {
            var appearance by remember { mutableStateOf(Appearance.load(context)) }
            var navigation by remember { mutableStateOf(NavigationSettings.load(context, "defaults")) }
            ModalBottomSheet(onDismissRequest = { readerSettings = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                SettingsPanel(appearance, navigation, false, null, { navigation = it; NavigationSettings.save(context, "defaults", it, false) }, { appearance = it; it.save(context) })
            }
        }
        if (shelfDialog) ShelfDialog(prefs.shelves, selected.isNotEmpty(),
            books.filter { it.id in selected }.map { it.shelves.toSet() }.reduceOrNull { a, b -> a.intersect(b) }.orEmpty(),
            { shelfDialog = false; if (detail != null) selected = emptySet() }, { newShelf = true }, { labels ->
            if (selected.isNotEmpty()) scope.launch {
                runCatching { model.store.assignShelves(selected, labels.orEmpty()) }
                    .onSuccess { selected = emptySet(); shelfDialog = false }.onFailure { model.notice.value = "The shelf could not be saved." }
            } else { shelf = labels?.firstOrNull() ?: "All books"; tab = "Library"; shelfDialog = false }
        }, { label -> scope.launch {
            runCatching { model.store.deleteShelf(label) }.onSuccess { update(prefs.copy(shelves = prefs.shelves - label)) }
                .onFailure { model.notice.value = "The shelf could not be removed." }
        } }, { label -> renameShelf = label })
        renameShelf?.let { old ->
            var name by remember(old) { mutableStateOf(old) }
            AlertDialog(onDismissRequest = { renameShelf = null }, title = { Text("Rename shelf") }, text = { OutlinedTextField(name, { name = it.take(40) }, label = { Text("Shelf name") }) },
                confirmButton = { TextButton(onClick = { scope.launch {
                    runCatching { model.store.renameShelf(old, name.trim()) }.onSuccess { if (shelf == old) shelf = name.trim(); renameShelf = null }
                        .onFailure { model.notice.value = it.message ?: "The shelf could not be renamed." }
                } }, enabled = name.trim().isNotBlank() && name.trim() != "All books") { Text("Rename") } },
                dismissButton = { TextButton(onClick = { renameShelf = null }) { Text("Cancel") } })
        }
        if (newShelf) {
            var name by remember { mutableStateOf("") }
            val valid = name.trim().isNotEmpty() && name.trim().length <= 40 && !name.trim().equals("All books", true) && prefs.shelves.none { it.equals(name.trim(), true) }
            AlertDialog(onDismissRequest = { newShelf = false }, title = { Text("New shelf") }, text = {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Shelf name") }, singleLine = true)
            }, confirmButton = { TextButton(onClick = { scope.launch { runCatching { model.store.addShelf(name.trim()) }.onSuccess { newShelf = false }.onFailure { model.notice.value = "The shelf could not be created." } } }, enabled = valid) { Text("Create") } },
                dismissButton = { TextButton(onClick = { newShelf = false }) { Text("Cancel") } })
        }
        if (removeDialog) AlertDialog(onDismissRequest = { removeDialog = false; if (detail != null) selected = emptySet() }, title = { Text("Remove ${selected.size} ${if (selected.size == 1) "book" else "books"}?") },
            text = { Text("This removes the selected books, annotations and saved listening/guided positions from Folio. Your original EPUB files are kept.") },
            confirmButton = { TextButton(onClick = { scope.launch {
                runCatching { model.store.removeBooks(selected) }.onSuccess { selected = emptySet(); detailId = null; removeDialog = false }
                    .onFailure { model.notice.value = "The selected books could not be removed." }
            } }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removeDialog = false; selected = emptySet() }) { Text("Cancel") } })
        if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("Folio $version") },
            text = { Text("An offline EPUB reader with a library layout adapted from Mihon. Local shelves, history and book details sit alongside Readium page rendering and offline Kokoro/Supertonic narration. Your books and positions stay on your device.") },
            confirmButton = { TextButton(onClick = { about = false }) { Text("Close") } }, dismissButton = { TextButton(onClick = { about = false; licenses = true }) { Text("Licenses") } })
        if (licenses) {
            val credits = remember { listOf("MIHON_LICENSE.txt", "AI_VOICE_LICENSES.txt", "THIRD_PARTY_NOTICES.txt").joinToString("\n\n") { context.assets.open(it).bufferedReader().use { reader -> reader.readText() } } }
            AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Licenses and credits") },
                text = { Text(credits, Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), fontSize = 12.sp) },
                confirmButton = { TextButton(onClick = { licenses = false }) { Text("Close") } })
        }
    }
}

@Composable private fun EmptyLibrary(import: () -> Unit, sample: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.CollectionsBookmark, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Your library is empty", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall)
        Text("Add an EPUB to begin reading.", Modifier.padding(vertical = 12.dp))
        Button(onClick = import) { Text("Import your first book") }
        TextButton(onClick = sample) { Text("Try the sample book") }
    }
}
@Composable private fun EmptyMessage(title: String, detail: String, icon: ImageVector) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleLarge)
        Text(detail, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun HomeAction(title: String, subtitle: String, icon: ImageVector, action: () -> Unit, enabled: Boolean = true) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) }, leadingContent = { Icon(icon, null) },
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = action))
}
@Composable internal fun BookCover(book: Book, store: LibraryStore, modifier: Modifier) {
    if (store.cover(book.id).exists()) AsyncImage(store.cover(book.id), "Cover of ${book.title}", modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.primaryContainer).padding(12.dp), contentAlignment = Alignment.Center) {
        Text(book.title, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 5, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun LibraryBooks(books: List<Book>, store: LibraryStore, prefs: HomePreferences, selected: Set<String>, click: (Book) -> Unit, long: (Book) -> Unit, read: (Book) -> Unit) {
    if (prefs.layout == "List") LazyColumn(contentPadding = PaddingValues(bottom = 90.dp)) {
        items(books, key = { it.id }) { LibraryBookRow(it, store, prefs.showProgress, it.id in selected, { click(it) }, { long(it) }, { read(it) }) }
    } else LazyVerticalGrid(GridCells.Adaptive((340 / prefs.columns).coerceAtLeast(90).dp), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 90.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(books, key = { it.id }) { book ->
            val marked = book.id in selected
            Column(Modifier.clip(RoundedCornerShape(6.dp)).background(if (marked) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .combinedClickable(onClick = { click(book) }, onLongClick = { long(book) }).padding(if (marked) 3.dp else 0.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(.68f).clip(RoundedCornerShape(6.dp))) {
                    BookCover(book, store, Modifier.fillMaxSize())
                    if (prefs.layout == "Compact") Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .85f)))).padding(top = 28.dp, start = 6.dp, end = 6.dp, bottom = 7.dp)) {
                        Text(book.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    if (prefs.showProgress) Surface(Modifier.align(Alignment.TopStart).padding(4.dp), shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(if (book.lastRead == 0L) "New" else "${(book.progress * 100).toInt()}%", Modifier.padding(horizontal = 5.dp, vertical = 2.dp), fontSize = 10.sp)
                    }
                    if (marked) Icon(Icons.Outlined.CheckCircle, "Selected ${book.title}", Modifier.align(Alignment.TopEnd).padding(5.dp), tint = MaterialTheme.colorScheme.primary)
                    else IconButton(onClick = { read(book) }, Modifier.align(Alignment.TopEnd).size(40.dp).padding(3.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp))) {
                        Icon(Icons.Outlined.PlayArrow, "Read ${book.title}", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    if (prefs.showProgress && book.lastRead > 0) LinearProgressIndicator(progress = { book.progress.toFloat() }, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
                }
                if (prefs.layout == "Comfortable") Text(book.title, Modifier.padding(horizontal = 2.dp, vertical = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
@Composable private fun LibraryBookRow(book: Book, store: LibraryStore, showProgress: Boolean, selected: Boolean, click: () -> Unit, long: () -> Unit, read: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).combinedClickable(onClick = click, onLongClick = long).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BookCover(book, store, Modifier.width(54.dp).height(78.dp).clip(RoundedCornerShape(4.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(book.author.ifBlank { "Unknown author" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showProgress) Text(if (book.lastRead == 0L) "Unread" else "${(book.progress * 100).toInt()}% read", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = read) { Icon(Icons.Outlined.PlayArrow, "Read ${book.title}") }
    }
}

@Composable private fun LibrarySettingsSheet(prefs: HomePreferences, update: (HomePreferences) -> Unit, dismiss: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Library settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp))
            TabRow(tab) { listOf("Filters", "Sort", "Display").forEachIndexed { index, title -> Tab(tab == index, { tab = index }, text = { Text(title) }) } }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (tab) {
                    0 -> {
                        ChoiceRow("Reading status", listOf("All", "Unread", "Reading", "Finished"), prefs.status) { update(prefs.copy(status = it)) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(prefs.annotated, { update(prefs.copy(annotated = it)) }); Text("Has bookmarks or notes") }
                    }
                    1 -> {
                        ChoiceRow("Sort by", listOf("Last read", "Date added", "Title", "Author"), prefs.sort) { update(prefs.copy(sort = it, descending = it !in listOf("Title", "Author"))) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Switch(prefs.descending, { update(prefs.copy(descending = it)) }); Text("Descending order", Modifier.padding(start = 12.dp)) }
                    }
                    else -> {
                        ChoiceRow("Layout", listOf("Comfortable", "Compact", "List"), prefs.layout) { update(prefs.copy(layout = it)) }
                        if (prefs.layout != "List") {
                            Text("Columns: ${prefs.columns}", Modifier.padding(top = 18.dp))
                            Slider(prefs.columns.toFloat(), { update(prefs.copy(columns = it.toInt())) }, valueRange = 2f..6f, steps = 3)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(prefs.showProgress, { update(prefs.copy(showProgress = it)) }); Text("Show reading progress") }
                    }
                }
                TextButton(onClick = { update(prefs.copy(status = "All", annotated = false)); dismiss() }) { Text("Reset filters") }
            }
        }
    }
}
@Composable private fun ShelfDialog(shelves: List<String>, assigning: Boolean, initial: Set<String>, dismiss: () -> Unit, create: () -> Unit,
    choose: (List<String>?) -> Unit, remove: (String) -> Unit, rename: (String) -> Unit) {
    var labels by remember(initial) { mutableStateOf(initial) }
    var deleteLabel by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (assigning) "Assign shelves" else "Shelves") }, text = {
        Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = { if (assigning) labels = emptySet() else choose(null) }) { Text(if (assigning) "Clear shelves" else "All books") }
            shelves.forEach { label -> Row(Modifier.fillMaxWidth().then(if (assigning) Modifier.toggleable(label in labels, role = Role.Checkbox) { checked -> labels = if (checked) labels + label else labels - label } else Modifier), verticalAlignment = Alignment.CenterVertically) {
                if (assigning) { Checkbox(label in labels, null); Text(label, Modifier.weight(1f)) }
                else {
                    TextButton(onClick = { choose(listOf(label)) }, Modifier.weight(1f)) { Text(label) }
                    IconButton(onClick = { rename(label) }) { Icon(Icons.Outlined.Edit, "Rename shelf $label") }
                    IconButton(onClick = { deleteLabel = label }) { Icon(Icons.Outlined.DeleteOutline, "Remove shelf $label") }
                }
            } }
            TextButton(onClick = create) { Icon(Icons.Outlined.Add, null); Text("New shelf", Modifier.padding(start = 8.dp)) }
        }
    }, confirmButton = { TextButton(onClick = { if (assigning) choose(labels.toList()) else dismiss() }) { Text(if (assigning) "Apply" else "Close") } },
        dismissButton = { if (assigning) TextButton(onClick = dismiss) { Text("Cancel") } })
    deleteLabel?.let { label -> AlertDialog(onDismissRequest = { deleteLabel = null }, title = { Text("Remove shelf $label?") },
        text = { Text("Books remain in the library. Their assignment to this shelf will be removed.") },
        confirmButton = { TextButton(onClick = { deleteLabel = null; remove(label) }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { deleteLabel = null }) { Text("Cancel") } }) }
}
