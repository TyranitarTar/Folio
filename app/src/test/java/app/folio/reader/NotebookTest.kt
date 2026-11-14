package app.folio.reader

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class NotebookTest {
    private val id="a".repeat(64)
    private fun book()=Audiobook(id,"Test","",tracks=listOf(AudioTrack("a.wav","First chapter",60000)))
    private fun fixture(test:suspend(File,AudioJournalStore,BookNotebookStore)->Unit)=runBlocking {
        val root=Files.createTempDirectory("notebook-test").toFile();File(root,id).mkdirs()
        try {val journal=AudioJournalStore(root);test(root,journal,BookNotebookStore(root,journal))}finally{root.deleteRecursively()}
    }
    @Test fun bulletEditingDoesNotRewritePastesAndEmptyDraftsAreNotSaved() {
        assertEquals("",NotebookEditing.document(NotebookEditing.begin("").text))
        assertEquals("Earlier",NotebookEditing.document(NotebookEditing.begin("Earlier").text))
        assertEquals("Earlier\n",NotebookEditing.document(NotebookEditing.begin("Earlier\n").text,"Earlier\n"))
        val full="x".repeat(BookNotebookStore.MAX_CHARS)
        assertEquals(full,NotebookEditing.begin(full).text)
        assertEquals(full.length,NotebookEditing.begin(full).selection.end)
        val before=TextFieldValue("- Æble",TextRange(6))
        assertEquals("- Æble\n- ",NotebookEditing.edit(before,TextFieldValue("- Æble\n",TextRange(7))).text)
        val paste="- Æble\nSeveral\nlines"
        assertEquals(paste,NotebookEditing.edit(before,TextFieldValue(paste,TextRange(paste.length))).text)
        val empty=TextFieldValue("- ",TextRange(2))
        assertEquals("\n",NotebookEditing.edit(empty,TextFieldValue("- \n",TextRange(3))).text)
        val selected=TextFieldValue("- Æble",TextRange(0,6))
        assertEquals("replacement",NotebookEditing.edit(selected,TextFieldValue("replacement")).text)
    }
    @Test fun legacyMigrationIsIdempotentRetainsContextAndDoesNotRetireOnFailure()=fixture{root,journal,store->
        val note=AudioJournalStore.newNote(0,12000,"Original passage",10).copy(text="Min note: æøå 🐈")
        journal.saveNote(id,note)
        assertTrue(runCatching{journal.retireNotes(id){error("Simulated interrupted migration")}}.isFailure)
        assertEquals(note,journal.load(id).notes.single())
        val migrated=store.open(book());assertTrue(migrated.text.contains(note.text));assertEquals(listOf(note.id),migrated.migratedIds)
        assertTrue(journal.load(id).notes.isEmpty())
        val archive=AudioJournalStore.decode(JSONObject(File(root,"$id/notes-legacy.json").readText()))
        assertEquals(note,archive.notes.single())
        assertEquals(migrated,BookNotebookStore(root,AudioJournalStore(root)).open(book()))
        // Simulate interruption after notebook commit, before the journal retirement.
        journal.saveNote(id,note)
        assertEquals(migrated.text,store.open(book()).text)
        assertTrue(journal.load(id).notes.isEmpty())
    }
    @Test fun orderedSavesLongTextAndSplitRetainBothExistingDocuments()=fixture{root,_,store->
        store.open(book());val first=store.nextOrder(id);val second=store.nextOrder(id)
        val text="Dansk æøå 🐈\n".repeat(10000)
        store.save(id,text,second);store.save(id,"stale",first)
        assertEquals(text,store.open(book()).text)
        val child="b".repeat(64);File(root,child).mkdirs()
        val childBook=book().copy(id=child);store.open(childBook);store.save(child,"Child notes",store.nextOrder(child))
        store.copyToSplit(id,child)
        val merged=store.open(childBook).text
        assertTrue(merged.startsWith("Child notes"));assertTrue(merged.endsWith(text))
        val stage=File(root,"stage").apply{mkdirs()};var committed=false
        store.withSnapshot(id,stage){committed=true}
        assertTrue(committed);assertEquals(text,JSONObject(File(stage,"notebook.json").readText()).getString("text"))
        assertTrue(runCatching{store.save(id,"x".repeat(BookNotebookStore.MAX_CHARS+1),store.nextOrder(id))}.isFailure)
        assertEquals(text,store.open(book()).text)
    }
    @Test @Config(sdk=[35]) fun failedMainCommitKeepsRecoverableDraftAndCanBeRetried()=fixture{root,_,store->
        store.open(book())
        val target=File(root,"$id/notebook.json.new").apply{mkdirs()};File(target,"block").writeText("block")
        val saved=store.save(id,"Recover this",store.nextOrder(id));assertTrue(saved.recovered)
        target.deleteRecursively()
        assertEquals("Recover this",store.open(book()).text)
        assertFalse(store.save(id,"Recovered",store.nextOrder(id)).recovered)
        assertFalse(File(root,"$id/notebook-draft.json").exists())
    }
    @Test fun canonicalHistoryHasCurrentNotesAndNoDuplicateVersions() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val history=ReleaseCatalog.history(context)
        assertEquals("0.17.2",history.first().version)
        assertEquals("2026-11-14",history.first().date)
        assertTrue(ReleaseCatalog.body(history.first()).contains("Copy all"))
        assertEquals(history.size,history.map{it.version}.distinct().size)
    }
}
