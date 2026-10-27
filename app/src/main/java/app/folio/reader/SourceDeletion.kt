package app.folio.reader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract as D
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal fun persistDocumentAccess(context:Context,uri:Uri) {
    if(uri.scheme!="content")return
    runCatching{context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
        .recoverCatching{context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
}
internal fun sourceDocumentKey(uri:Uri):String=uri.authority+"/"+D.getDocumentId(uri)

/** Folder deletion is a separate, explicit action and rejects roots/series/shared content. */
internal fun deleteBookSourceFolder(context:Context,tree:Uri,folder:Uri,owned:List<Uri>,otherSources:List<Uri>,journal:File) {
    require(validLinkedAudio(tree,folder) && D.getDocumentId(folder)!=D.getTreeDocumentId(tree)){"A library root cannot be deleted as a book folder."}
    val receipt=AtomicFile(journal)
    val previous=if(receipt.baseFile.exists())receipt.openRead().bufferedReader().use{JSONObject(it.readText())}else null
    require(previous==null || previous.optString("folder")==folder.toString()){"The source folder changed during deletion; review it before continuing."}
    // The provider may no longer recognise an already deleted directory.
    if(previous?.optBoolean("deleted")==true)return
    val root=D.buildDocumentUriUsingTree(tree,D.getTreeDocumentId(tree))
    if(android.os.Build.VERSION.SDK_INT>=29)require(D.isChildDocument(context.contentResolver,root,folder)){"The folder is not a verified child of this library root."}
    else error("Verified folder deletion needs Android 10 or later. Delete the book’s source files instead.")
    val seen=mutableSetOf<String>();val files=mutableSetOf<String>();var entries=0
    val discs=Regex("(?i)(cd|disc|disk)[ _-]*\\d+.*")
    fun visit(id:String,depth:Int) {
        require(depth<=8 && seen.add(id) && id!=D.getTreeDocumentId(tree)){"The book folder has an unsafe or ambiguous directory layout."}
        context.contentResolver.query(D.buildChildDocumentsUriUsingTree(tree,id),arrayOf(D.Document.COLUMN_DOCUMENT_ID,D.Document.COLUMN_DISPLAY_NAME,D.Document.COLUMN_MIME_TYPE),null,null,null)?.use{c->
            while(c.moveToNext()) {
                require(++entries<=5000){"This folder is too large to delete safely from the book action."}
                val child=c.getString(0);val name=c.getString(1).orEmpty();val type=c.getString(2).orEmpty()
                if(type==D.Document.MIME_TYPE_DIR){require(discs.matches(name)){"This folder contains other folders. Delete just this book’s source files instead."};visit(child,depth+1)}
                else {
                    val key=folder.authority+"/"+child;files+=key
                    if(audioExtensionOrNull(name,type)!=null || name.endsWith(".foliobook",true))
                        require(owned.any{sourceDocumentKey(it)==key}){"This folder contains other recordings; it was kept."}
                }
            }
        } ?: error("The source folder could not be inspected.")
    }
    visit(D.getDocumentId(folder),0)
    require(owned.isNotEmpty() && owned.all{sourceDocumentKey(it) in files}){"The folder no longer contains the complete saved playlist. Rescan or delete individual files."}
    require(otherSources.none{runCatching{sourceDocumentKey(it) in files}.getOrDefault(false)}){"Another library entry uses files in this folder; the folder was kept."}
    val granted=context.checkUriPermission(folder,Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==PackageManager.PERMISSION_GRANTED ||
        context.contentResolver.persistedUriPermissions.any{it.isWritePermission && validLinkedAudio(it.uri,folder)}
    require(granted){"Relink the source folder with write access before deleting it."}
    fun save(deleted:Boolean) {journal.parentFile?.mkdirs();val stream=receipt.startWrite()
        try{stream.write(JSONObject().put("folder",folder.toString()).put("deleted",deleted).toString().toByteArray());receipt.finishWrite(stream)}catch(e:Throwable){receipt.failWrite(stream);throw e}}
    save(false);check(D.deleteDocument(context.contentResolver,folder)){"The provider could not delete the book folder. The library entry was kept."};save(true)
}

/** Explicit file deletion only. Never infer or recursively delete a parent/root folder. */
internal fun deleteSourceDocuments(context:Context,targets:List<Uri>,otherSources:List<Uri>,journal:File) {
    if(targets.isEmpty())return
    require(targets.size<=256 && targets.all{it.scheme=="content" && D.isDocumentUri(context,it)}){"The original source cannot be deleted safely. Remove the library entry only, or relink its folder."}
    val documents=targets.distinctBy(::sourceDocumentKey)
    val shared=otherSources.mapNotNull{runCatching{sourceDocumentKey(it)}.getOrNull()}.toSet()
    require(documents.none{sourceDocumentKey(it) in shared}){"These files are also used by another library entry. Shared originals were kept."}
    val atomic=AtomicFile(journal);val wanted=documents.map{it.toString()}
    val old=if(atomic.baseFile.exists())atomic.openRead().bufferedReader().use{JSONObject(it.readText())}else JSONObject()
    val prior=old.optJSONArray("targets")?.let{a->(0 until a.length()).map{a.getString(it)}}
    require(prior==null || prior==wanted){"The source playlist changed during deletion. Review the files before trying again."}
    val deleted=old.optJSONArray("deleted")?.let{a->(0 until a.length()).map{a.getString(it)}.toMutableSet()} ?: mutableSetOf()
    require(deleted.all{it in wanted})
    fun receipt() {
        journal.parentFile?.mkdirs();val stream=atomic.startWrite()
        try{stream.write(JSONObject().put("targets",JSONArray(wanted)).put("deleted",JSONArray(deleted.toList())).toString().toByteArray());atomic.finishWrite(stream)}
        catch(e:Throwable){atomic.failWrite(stream);throw e}
    }
    // Preflight every remaining file before deleting any of them.
    for(uri in documents.filterNot{it.toString() in deleted}) {
        val granted=context.checkUriPermission(uri,Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==PackageManager.PERMISSION_GRANTED ||
            context.contentResolver.persistedUriPermissions.any{it.isWritePermission && (it.uri==uri || validLinkedAudio(it.uri,uri))}
        require(granted){"The source is read-only. Relink its folder with write access, or remove the library entry only."}
        context.contentResolver.query(uri,arrayOf(D.Document.COLUMN_MIME_TYPE,D.Document.COLUMN_FLAGS),null,null,null)?.use{c->
            require(c.moveToFirst()){ "A source file is missing. Relink it, or remove the library entry only." }
            require(c.getString(0)!=D.Document.MIME_TYPE_DIR){"Folder deletion is not allowed through the file action."}
            require(c.getInt(1) and D.Document.FLAG_SUPPORTS_DELETE!=0){"This provider does not allow deleting the original file."}
        } ?: error("Source file access is unavailable; originals and library entry were kept.")
    }
    receipt()
    for(uri in documents.filterNot{it.toString() in deleted}) {
        check(D.deleteDocument(context.contentResolver,uri)){"A source file could not be deleted. The library entry and deletion receipt were kept for retry."}
        deleted+=uri.toString();receipt()
    }
}
