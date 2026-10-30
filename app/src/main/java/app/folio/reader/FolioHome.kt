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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
    var audioPrefs by remember { mutableStateOf(HomePreferences.load(context,"audioHome")) }
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
        val audioStore = (context.applicationContext as FolioApp).audiobooks
        val audioBooks by audioStore.books.collectAsStateWithLifecycle()
        LaunchedEffect(audioStore) { runCatching { audioStore.load();audioStore.prepareLinkedMetadata() }.onFailure { model.notice.value = "Audiobooks could not be loaded." } }
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
        var tab by rememberSaveable { mutableStateOf("Books") }
        val libraryRequest by model.libraryRequest.collectAsStateWithLifecycle()
        var query by rememberSaveable { mutableStateOf("") }
        var searching by rememberSaveable { mutableStateOf(false) }
        var shelf by rememberSaveable { mutableStateOf("All books") }
        var audioShelf by rememberSaveable { mutableStateOf("All books") }
        var detailId by rememberSaveable { mutableStateOf<String?>(null) }
        var selected by remember { mutableStateOf(setOf<String>()) }
        var settings by remember { mutableStateOf(false) }
        var settingsAudio by remember { mutableStateOf(false) }
        var audioScanRequest by rememberSaveable { mutableIntStateOf(0) }
        var audioImportRequest by rememberSaveable { mutableIntStateOf(0) }
        var audioScanning by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var shelfDialog by remember { mutableStateOf(false) }
        var newShelf by remember { mutableStateOf(false) }
        var removeDialog by remember { mutableStateOf(false) }
        var removingBooks by remember{mutableStateOf(false)}
        var keepOriginalBooks by remember{mutableStateOf(false)}
        var removalError by remember{mutableStateOf<String?>(null)}
        var readerSettings by remember { mutableStateOf(false) }
        var about by remember { mutableStateOf(false) }
        var historyNotes by rememberSaveable { mutableStateOf(false) }
        var savedQuotes by rememberSaveable { mutableStateOf(false) }
        var updates by rememberSaveable { mutableStateOf(false) }
        var speechSettings by rememberSaveable { mutableStateOf(false) }
        var audioSettings by rememberSaveable { mutableStateOf(false) }
        var voicePacks by rememberSaveable { mutableStateOf(false) }
        var storage by rememberSaveable { mutableStateOf(false) }
        var renameShelf by remember { mutableStateOf<String?>(null) }
        var licenses by remember { mutableStateOf(false) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.import(it) }
        fun importBooks() = picker.launch(arrayOf("application/epub+zip", "application/octet-stream", "application/zip"))
        fun update(value: HomePreferences) { prefs = value; value.save(context) }
        fun updateAudio(value: HomePreferences) { audioPrefs=value;value.save(context,"audioHome") }
        LaunchedEffect(libraryRequest) { libraryRequest?.let {
            tab=it.section;detailId=null;selected=emptySet();searching=false;query=""
            model.libraryRequest.value=null
        } }
        fun toggle(book: Book) { selected = if (book.id in selected) selected - book.id else selected + book.id }
        fun at(book: Book, locator: org.readium.r2.shared.publication.Locator) = scope.launch {
            runCatching { model.store.progress(book.id, locator) }.onSuccess { open(book) }
                .onFailure { model.notice.value = "This reading position could not be saved." }
        }
        LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); model.notice.value = null } }
        LaunchedEffect(books) { selected = selected.intersect(books.map { it.id }.toSet()) }
        LaunchedEffect(prefs.shelves) { if (shelf != "All books" && shelf !in prefs.shelves) shelf = "All books" }
        LaunchedEffect(prefs.shelves) { if(audioShelf!="All books" && audioShelf !in prefs.shelves)audioShelf="All books" }
        LaunchedEffect(storedShelves, loading) {
            if (!loading && prefs.shelves != storedShelves) update(prefs.copy(shelves = storedShelves))
        }
        val visible = remember(books, prefs, query, shelf) { prefs.visibleBooks(books, query, shelf) }
        val detail = books.firstOrNull { it.id == detailId }
        BackHandler(detail != null || selected.isNotEmpty() || searching || tab != "Books") {
            when { selected.isNotEmpty() -> selected = emptySet(); detail != null -> detailId = null
                searching -> { searching = false; query = "" }; else -> tab = "Books" }
        }
        if (detail != null) {
            tabState.SaveableStateProvider("details:${detail.id}") {
                BookDetails(detail, model.store, open, speech, { at(detail, it) }, { detailId = null },
                    { selected = setOf(detail.id); shelfDialog = true }, { selected = setOf(detail.id); removeDialog = true }, snackbar)
            }
        } else Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (searching && tab in listOf("Books", "Audiobooks", "History")) Row(Modifier.fillMaxWidth().statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close search") }
                    TextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search title or author") },
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                    IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear search") }
                } else TopAppBar(title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if(tab in listOf("Books","Audiobooks") && selected.isEmpty())Box {
                            var shelfMenu by remember{mutableStateOf(false)}
                            TextButton(onClick={shelfMenu=true},contentPadding=PaddingValues(0.dp)) {
                                val label=if(tab=="Audiobooks")audioShelf else shelf
                                Text(if(label=="All books"){if(tab=="Audiobooks")"Audiobooks" else "eBooks"}else label,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleLarge,modifier=Modifier.widthIn(max=160.dp))
                                Icon(Icons.Outlined.ArrowDropDown,"Choose shelf")
                            }
                            DropdownMenu(shelfMenu,{shelfMenu=false}) {
                                (listOf("All books")+prefs.shelves).forEach{label->DropdownMenuItem(text={Text(label)},onClick={if(tab=="Audiobooks")audioShelf=label else shelf=label;shelfMenu=false})}
                                DropdownMenuItem(text={Text("Manage shelves")},onClick={shelfMenu=false;shelfDialog=true})
                            }
                        } else Text(if(selected.isNotEmpty())"${selected.size} selected" else tab,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleLarge)
                        if (tab=="Books" && selected.isEmpty()) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text((if(tab=="Books")visible.size else audioPrefs.visibleAudiobooks(audioBooks,query).size).toString(), Modifier.padding(horizontal = 10.dp, vertical = 2.dp), fontSize = 14.sp)
                        }
                    }
                }, navigationIcon = { if (selected.isNotEmpty()) IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Outlined.Close, "Clear selection") } }, actions = {
                    if (selected.isNotEmpty()) {
                        Box {
                            var statusMenu by remember { mutableStateOf(false) }
                            IconButton(onClick={statusMenu=true}) { Icon(Icons.Outlined.MoreVert,"Reading status") }
                            DropdownMenu(statusMenu,{statusMenu=false}) {
                                listOf("New","Reading","Finished").forEach { status -> DropdownMenuItem(text={Text("Mark ${status.lowercase()}")},onClick={
                                    statusMenu=false
                                    val ids=selected
                                    scope.launch { runCatching { ids.forEach { id -> model.store.update(id) { it.copy(readState=if(status=="Finished")"Read" else "Unread",lastRead=if(status=="New")0 else maxOf(it.lastRead,System.currentTimeMillis())) } } }
                                        .onSuccess { selected=emptySet() }.onFailure { model.notice.value="Reading status could not be saved." } }
                                }) }
                            }
                        }
                        IconButton(onClick = { selected = visible.map { it.id }.toSet() }) { Icon(Icons.Outlined.SelectAll, "Select all") }
                        IconButton(onClick = { shelfDialog = true }) { Icon(Icons.Outlined.FolderOpen, "Assign shelf") }
                        IconButton(onClick = { removeDialog = true }) { Icon(Icons.Outlined.DeleteOutline, "Remove selected books") }
                    } else {
                        if (tab in listOf("Books", "Audiobooks", "History")) IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Search library") }
                        if(tab=="Audiobooks")IconButton(onClick={audioScanRequest++},enabled=!audioScanning) { Icon(Icons.Outlined.Refresh,"Rescan audiobook folders") }
                        if (tab in listOf("Books","Audiobooks")) IconButton(onClick = { settingsAudio=tab=="Audiobooks";settings = true }) { Icon(Icons.Outlined.FilterList, "Library settings") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Library menu") }
                            DropdownMenu(menu, { menu = false }) {
                                if(tab=="Audiobooks") {
                                    DropdownMenuItem(text={Text("Add audiobooks / folders")},onClick={menu=false;audioImportRequest++})
                                    DropdownMenuItem(text={Text("Rescan folders")},enabled=!audioScanning,onClick={menu=false;audioScanRequest++})
                                } else if(tab=="Books") {
                                    DropdownMenuItem(text = { Text("Import EPUB") }, onClick = { menu = false; importBooks() })
                                    DropdownMenuItem(text = { Text("Manage shelves") }, onClick = { menu = false; shelfDialog = true })
                                }
                                DropdownMenuItem(text = { Text("About Folio") }, onClick = { menu = false; about = true })
                            }
                        }
                    }
                },expandedHeight=52.dp)
            },
            bottomBar = { Surface(tonalElevation=2.dp) { Column(Modifier.navigationBarsPadding()) { LibraryAudioBar();Row(Modifier.fillMaxWidth()) {
                (listOf("Books" to Icons.Outlined.CollectionsBookmark, "Audiobooks" to Icons.Outlined.Headphones) +
                    listOf("Café" to Icons.Outlined.Pets) +
                    listOf("Browse" to Icons.Outlined.TravelExplore, "More" to Icons.Outlined.MoreHoriz)).forEach { (name, icon) ->
                    Column(Modifier.weight(1f).heightIn(min=56.dp).semantics{contentDescription=if(name=="Books")"eBooks library" else "$name tab"}.selectable(tab==name,role=Role.Tab,onClick={tab=name;selected=emptySet();query="";searching=false}),
                        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                        Box(Modifier.clip(RoundedCornerShape(20.dp)).background(if(tab==name)MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).padding(horizontal=14.dp,vertical=3.dp)) { Icon(icon,null,Modifier.size(22.dp)) }
                        Text(if(name=="Books")"eBooks" else name,fontSize=11.sp,maxLines=1,softWrap=false)
                    }
                }
            } } } },
            floatingActionButton = { if (tab == "Books" && selected.isEmpty()) FloatingActionButton(onClick = { importBooks() }) { Icon(Icons.Outlined.Add, "Import EPUB") } }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(Modifier.weight(1f))
                    TextButton(onClick = { model.cancelImport() }) { Text("Cancel import") }
                }
                if (recovery != null) TextButton(onClick = { storage = true }) { Text("Library recovery available") }
                tabState.SaveableStateProvider(if (tab == "Books") "$tab:$shelf:${prefs.layout}" else tab) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    tab == "Books" -> {
                        LibraryStatusPager(prefs.status,{update(prefs.copy(status=it))},selected.isEmpty()) { page ->
                        val pagePrefs=prefs.copy(status=page)
                        val pageBooks=remember(books,pagePrefs,query,shelf){pagePrefs.visibleBooks(books,query,shelf)}
                        if (books.isEmpty()) EmptyLibrary({ importBooks() }, { model.sample(open) })
                        else if (pageBooks.isEmpty()) EmptyMessage("No books match", "Change the shelf, search or filters to see more books.", Icons.Outlined.FilterList)
                        else LibraryBooks(pageBooks, model.store, pagePrefs, selected, { if (selected.isEmpty()) detailId = it.id else toggle(it) }, ::toggle, open,audioBooks.mapNotNull{it.bookId}.toSet())
                        }
                    }
                    tab == "Audiobooks" -> AudiobookLibrary(audioStore, books, audioPrefs, query, ::updateAudio, open,audioScanRequest,{audioScanning=it},audioImportRequest,audioShelf,{shelfDialog=true})
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
                        HomeAction("Reading history", "Your recently opened books", Icons.Outlined.History, { tab = "History" })
                        HomeAction("Saved highlights & quotes", "Passages saved from your books", Icons.Outlined.FormatQuote, { savedQuotes = true })
                        HomeAction("Reader settings", "Typography, page turns and tap zones", Icons.Outlined.MenuBook, { readerSettings = true })
                        HomeAction("Speech settings", "Offline voices and playback speed", Icons.Outlined.RecordVoiceOver, { speechSettings = true })
                        HomeAction("Voice packs", "Listen to samples and download offline voices", Icons.Outlined.Download, { voicePacks = true })
                        HomeAction("Bluetooth & headset", "Playback controls, interruptions and sleep timer", Icons.Outlined.Headphones, { audioSettings = true })
                        HomeAction("Storage and backup", "Export, restore, repair and free space", Icons.Outlined.Save, { storage = true })
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        ChoiceRow("App theme", listOf("System", "Light", "Dark"), prefs.theme) { update(prefs.copy(theme = it)) }
                        Spacer(Modifier.height(16.dp))
                        HomeAction("App updates", "Check GitHub for a newer signed version", Icons.Outlined.SystemUpdate, { updates = true })
                        HomeAction("About Folio", "Version $version", Icons.Outlined.Info, { about = true })
                        HomeAction("Licenses", "Mihon, Readium and offline voice credits", Icons.Outlined.Description, { licenses = true })
                    }
                }
                }
            }
        }
        if (historyNotes) VersionHistorySheet { historyNotes = false;about=true }
        if (savedQuotes) SavedQuotesSheet(model.store,open) { savedQuotes=false }
        if(audioSettings)AudioSettingsSheet{audioSettings=false}
        if (updates) AppUpdatesSheet(close = { updates = false })
        if (voicePacks) VoicePacksPanel(close = { voicePacks = false })
        if (speechSettings) ModalBottomSheet(onDismissRequest = { speechSettings = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            SpeechPanel("", null, settingsOnly = true) {}
        }
        if (storage) StoragePanel(model, { prefs = HomePreferences.load(context).copy(shelves = model.store.shelves.value) }, { storage = false })
        if (settings) LibrarySettingsSheet(if(settingsAudio)audioPrefs else prefs,if(settingsAudio)::updateAudio else ::update,{ settings=false },settingsAudio)
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
            } else { if(tab=="Audiobooks")audioShelf=labels?.firstOrNull() ?: "All books" else shelf=labels?.firstOrNull() ?: "All books";shelfDialog = false }
        }, { label -> scope.launch {
            runCatching { audioStore.renameShelf(label,null);model.store.deleteShelf(label) }.onSuccess { update(prefs.copy(shelves = prefs.shelves - label));if(audioShelf==label)audioShelf="All books" }
                .onFailure { model.notice.value = "The shelf could not be removed." }
        } }, { label -> renameShelf = label })
        renameShelf?.let { old ->
            var name by remember(old) { mutableStateOf(old) }
            AlertDialog(onDismissRequest = { renameShelf = null }, title = { Text("Rename shelf") }, text = { OutlinedTextField(name, { name = it.take(40) }, label = { Text("Shelf name") }) },
                confirmButton = { TextButton(onClick = { scope.launch {
                    runCatching { audioStore.renameShelf(old,name.trim());model.store.renameShelf(old, name.trim()) }.onSuccess { if (shelf == old) shelf = name.trim();if(audioShelf==old)audioShelf=name.trim();renameShelf = null }
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
        if (removeDialog) AlertDialog(onDismissRequest = { if(!removingBooks){removeDialog = false;keepOriginalBooks=false;removalError=null;if (detail != null) selected = emptySet()} }, title = { Text("Delete ${selected.size} ${if (selected.size == 1) "eBook" else "eBooks"}?") },
            text = { Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState())) {
                Text("Delete the stored EPUBs, annotations and saved reading positions. Recorded source EPUBs are deleted too; older imports without a recorded source can delete only Folio’s copy.")
                books.filter{it.id in selected}.forEach{book->Text(book.title,Modifier.padding(top=5.dp),style=MaterialTheme.typography.labelLarge)
                    book.originalSources.take(3).forEach{Text(android.net.Uri.decode(it).substringAfter("/document/").takeLast(160),style=MaterialTheme.typography.bodySmall)}
                }
                Row(verticalAlignment=Alignment.CenterVertically){Checkbox(keepOriginalBooks,{if(!removingBooks)keepOriginalBooks=it});Text("Keep original files",style=MaterialTheme.typography.bodySmall)}
                if(removingBooks)LinearProgressIndicator(Modifier.fillMaxWidth())
                removalError?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            } },
            confirmButton = { TextButton(enabled=!removingBooks,onClick = { removingBooks=true;removalError=null;scope.launch {
                runCatching { if(keepOriginalBooks)model.store.removeBooks(selected) else model.store.deleteBooksFromDevice(selected) }
                    .onSuccess { selected = emptySet(); detailId = null; removeDialog = false;keepOriginalBooks=false;removalError=null }
                    .onFailure { removalError = it.message ?: "The selected books could not be deleted." }
                removingBooks=false
            } }) { Text(if(keepOriginalBooks)"Remove copies" else "Delete") } }, dismissButton = { TextButton(enabled=!removingBooks,onClick = { removeDialog = false; selected = emptySet();keepOriginalBooks=false;removalError=null }) { Text("Cancel") } })
        if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("About Folio") },
            text = { Column { Text("Folio $version · build ${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName,0))}")
                Text("Read and listen offline. Your library and positions stay on your device.",Modifier.padding(vertical=12.dp))
                TextButton(onClick={about=false;historyNotes=true}) { Text("Version history") }
            } },
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

@Composable private fun LibraryBooks(books: List<Book>, store: LibraryStore, prefs: HomePreferences, selected: Set<String>, click: (Book) -> Unit, long: (Book) -> Unit, read: (Book) -> Unit,linked:Set<String> = emptySet()) {
    if (prefs.layout == "List") LazyColumn(Modifier.fillMaxSize(),contentPadding = PaddingValues(bottom = 90.dp)) {
        items(books, key = { it.id }) { LibraryBookRow(it, store, prefs.showProgress, it.id in selected, { click(it) }, { long(it) }, { read(it) },it.id in linked) }
    } else LazyVerticalGrid(GridCells.Fixed(prefs.columns.coerceIn(2,4)),Modifier.fillMaxSize(),contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 90.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(books, key = { it.id }) { book ->
            LibraryCoverTile(book.title,if(book.isRead)"Finished" else if(book.lastRead==0L)"New" else "${(book.progress*100).toInt()}%",
                book.progress.toFloat(),prefs,book.id in selected,{click(book)},{long(book)},"Read ${book.title}",{read(book)},folioBook=book.id in linked) {
                BookCover(book,store,Modifier.fillMaxSize())
            }
        }
    }
}
@Composable private fun LibraryBookRow(book: Book, store: LibraryStore, showProgress: Boolean, selected: Boolean, click: () -> Unit, long: () -> Unit, read: () -> Unit,folioBook:Boolean=false) {
    LibraryTitleRow(book.title,book.author,if(showProgress)book.statusLabel else "",selected,click,long,"Read ${book.title}",read,folioBook=folioBook) {
        BookCover(book,store,Modifier.fillMaxSize())
    }
}

@Composable private fun LibrarySettingsSheet(prefs: HomePreferences, update: (HomePreferences) -> Unit, dismiss: () -> Unit,audio: Boolean=false) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Library settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp))
            TabRow(tab) { listOf("Filters", "Sort", "Display").forEachIndexed { index, title -> Tab(tab == index, { tab = index }, text = { Text(title) }) } }
            Column(Modifier.weight(1f, fill=false).verticalScroll(rememberScrollState())) {
                when (tab) {
                    0 -> {
                        ChoiceRow("Reading status", libraryStatuses, prefs.status) { update(prefs.copy(status = it)) }
                        if(!audio)Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(prefs.annotated, { update(prefs.copy(annotated = it)) }); Text("Has bookmarks or notes") }
                    }
                    1 -> {
                        val choices=if(audio)listOf("Last listened","Title","Author") else listOf("Last read","Date added","Title","Author")
                        ChoiceRow("Sort by", choices,if(audio && prefs.sort=="Last read")"Last listened" else prefs.sort) { update(prefs.copy(sort = if(it=="Last listened")"Last read" else it, descending = it !in listOf("Title", "Author"))) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Switch(prefs.descending, { update(prefs.copy(descending = it)) }); Text("Descending order", Modifier.padding(start = 12.dp)) }
                    }
                    else -> {
                        ChoiceRow("Layout", listOf("Comfortable", "Compact", "List"), prefs.layout) { update(prefs.copy(layout = it)) }
                        if (prefs.layout != "List") {
                            Text("Columns: ${prefs.columns}", Modifier.padding(top = 18.dp))
                            Slider(prefs.columns.coerceIn(2,4).toFloat(), { update(prefs.copy(columns = it.toInt())) }, valueRange = 2f..4f, steps = 1)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(prefs.showProgress, { update(prefs.copy(showProgress = it)) }); Text("Show reading progress") }
                    }
                }
                TextButton(onClick = { update(prefs.copy(status = "All", annotated = false)); dismiss() }) { Text("Reset filters") }
            }
        }
    }
}
@Composable internal fun ShelfDialog(shelves: List<String>, assigning: Boolean, initial: Set<String>, dismiss: () -> Unit, create: () -> Unit,
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
