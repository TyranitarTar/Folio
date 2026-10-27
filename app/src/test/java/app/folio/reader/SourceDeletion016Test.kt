package app.folio.reader

import android.content.*
import android.content.pm.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract as D
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SourceDeletion016Test {
    private lateinit var context:Context
    private lateinit var provider:FilesProvider
    private lateinit var folder:File
    private val tree=Uri.parse("content://folio.test.documents/tree/root")
    private fun doc(name:String)=D.buildDocumentUriUsingTree(tree,"root/$name")
    @Before fun setup() {
        val base=ApplicationProvider.getApplicationContext<Context>()
        folder=java.nio.file.Files.createTempDirectory(base.cacheDir.toPath(),"delete-016-").toFile()
        val resolver=base.contentResolver;provider=FilesProvider()
        val info=ProviderInfo().apply{authority="folio.test.documents";name=FilesProvider::class.java.name;packageName=base.packageName;exported=true;grantUriPermissions=true}
        provider.attachInfo(base,info);ShadowContentResolver.registerProviderInternal(info.authority,provider)
        shadowOf(base.packageManager).addResolveInfoForIntent(Intent(D.PROVIDER_INTERFACE),ResolveInfo().apply{providerInfo=info})
        context=object:ContextWrapper(base) {
            override fun getContentResolver():ContentResolver=resolver
            override fun getFilesDir():File=folder
            override fun checkUriPermission(uri:Uri,pid:Int,uid:Int,modeFlags:Int)=PackageManager.PERMISSION_GRANTED
        }
    }
    @Test fun partial_provider_failure_keeps_bookmarks_and_retries_without_deleting_other_books()=runBlocking {
        val store=AudiobookStore(context)
        val book=store.linkAudio(tree,"series","v1","Series",listOf("First" to doc("first.mp3"),"Second" to doc("second.mp3")))
        val other=store.linkAudio(tree,"other","v1","Other",listOf("Other" to doc("other.mp3")))
        val mark=store.addBookmark(book.id,1,100,"Keep this")
        provider.fail="root/second.mp3"
        assertTrue(runCatching{store.deleteFromDevice(book.id)}.isFailure)
        assertEquals(mark,store.books.value.first{it.id==book.id}.bookmarks.single())
        assertEquals(listOf("root/first.mp3"),provider.deleted)
        provider.fail=null;store.deleteFromDevice(book.id)
        assertFalse(store.books.value.any{it.id==book.id});assertTrue(store.books.value.any{it.id==other.id})
        assertEquals(listOf("root/first.mp3","root/second.mp3"),provider.deleted)
    }
    @Test fun parent_directory_and_shared_documents_are_refused_before_any_delete() {
        provider.directory=true
        assertTrue(runCatching{deleteSourceDocuments(context,listOf(doc("parent")),emptyList(),File(folder,"a.json"))}.isFailure)
        provider.directory=false
        val alternate=D.buildDocumentUriUsingTree(Uri.parse("content://folio.test.documents/tree/another-root"),"root/first.mp3")
        assertTrue(runCatching{deleteSourceDocuments(context,listOf(doc("first.mp3")),listOf(alternate),File(folder,"b.json"))}.isFailure)
        assertTrue(provider.deleted.isEmpty())
    }
    @Test @Config(sdk=[35],shadows=[ReplacingAtomicRename::class]) fun folderGuardsAndRetry() {
        val own=doc("book/first.mp3");val dir=doc("book")
        provider.children=listOf("root/book/first.mp3" to "first.mp3")
        assertTrue(runCatching{deleteBookSourceFolder(context,tree,D.buildDocumentUriUsingTree(tree,"root"),listOf(own),emptyList(),File(folder,"root.json"))}.isFailure)
        assertTrue(runCatching{deleteBookSourceFolder(context,tree,dir,listOf(own),listOf(own),File(folder,"shared.json"))}.isFailure)
        provider.children+= "root/book/second.mp3" to "second.mp3"
        assertTrue(runCatching{deleteBookSourceFolder(context,tree,dir,listOf(own),emptyList(),File(folder,"extra.json"))}.isFailure)
        assertTrue(provider.deleted.isEmpty())
        provider.children=listOf("root/book/first.mp3" to "first.mp3","root/book/cover.jpg" to "cover.jpg")
        deleteBookSourceFolder(context,tree,dir,listOf(own),emptyList(),File(folder,"ok.json"))
        assertEquals(listOf("root/book"),provider.deleted)
        assertTrue("Receipt: ${folder.absolutePath}",File(folder,"ok.json").isFile)
        assertTrue(JSONObject(File(folder,"ok.json").readText()).getBoolean("deleted"))
        deleteBookSourceFolder(context,tree,dir,listOf(own),emptyList(),File(folder,"ok.json"))
        assertEquals(1,provider.deleted.size)
    }
    @Test fun splitting_linked_files_preserves_each_files_marks_and_active_cursor()=runBlocking {
        val store=AudiobookStore(context)
        val book=store.linkAudio(tree,"series","v1","Series",listOf("Red Mars.m4b" to doc("red.m4b"),"Green Mars.m4b" to doc("green.m4b")))
        store.assignShelves(book.id,listOf("Science fiction"));store.savePosition(book.id,1,12000,1.5f)
        val red=store.addBookmark(book.id,0,7000,"Red point");val green=store.addBookmark(book.id,1,11000,"Green point")
        val children=store.splitFilesIntoBooks(book.id)
        assertEquals(2,children.size);assertFalse(store.books.value.any{it.id==book.id})
        assertEquals(12000L,children[1].positionMs);assertEquals(1.5f,children[1].speed)
        assertEquals(red.copy(track=0),children[0].bookmarks.single());assertEquals(green.copy(track=0),children[1].bookmarks.single())
        assertTrue(children.all{it.shelves==listOf("Science fiction")});assertTrue(provider.deleted.isEmpty())
        val reloaded=AudiobookStore(context);reloaded.load();assertEquals(children.map{it.id}.toSet(),reloaded.books.value.map{it.id}.toSet())
    }
    private class FilesProvider:ContentProvider() {
        var directory=false;var fail:String?=null;val deleted=mutableListOf<String>();var children=emptyList<Pair<String,String>>()
        override fun onCreate()=true
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor =
            if(uri.lastPathSegment=="children")MatrixCursor(arrayOf(D.Document.COLUMN_DOCUMENT_ID,D.Document.COLUMN_DISPLAY_NAME,D.Document.COLUMN_MIME_TYPE)).apply{children.forEach{(id,name)->addRow(arrayOf(id,name,if(name.endsWith(".jpg"))"image/jpeg" else "audio/mpeg"))}}
            else MatrixCursor(arrayOf(D.Document.COLUMN_MIME_TYPE,D.Document.COLUMN_FLAGS)).apply{addRow(arrayOf<Any>(if(directory)D.Document.MIME_TYPE_DIR else "audio/mpeg",D.Document.FLAG_SUPPORTS_DELETE))}
        override fun getType(uri:Uri)="audio/mpeg"
        override fun insert(uri:Uri,values:ContentValues?):Uri?=null
        override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
        override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
        @Suppress("DEPRECATION") override fun call(method:String,arg:String?,extras:Bundle?):Bundle {
            if(method=="android:isChildDocument")return Bundle().apply{putBoolean("result",true)}
            val uri=checkNotNull(extras?.getParcelable<Uri>("uri"));val id=D.getDocumentId(uri)
            if(id==fail)throw SecurityException("Authored provider failure")
            deleted+=id;return Bundle()
        }
    }
    // Android rename replaces the destination atomically; File.renameTo in the
    // Windows-hosted SDK 35 Robolectric sandbox cannot replace an existing file.
    @Implements(android.util.AtomicFile::class)
    class ReplacingAtomicRename {
        companion object {
            @JvmStatic @Implementation fun rename(source:File,target:File) {
                java.nio.file.Files.move(source.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
