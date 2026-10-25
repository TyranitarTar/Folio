package app.folio.reader

internal val audioExtensions=setOf("mp3","m4b","m4a","ogg","opus","wav","flac","aac")
internal fun audioExtensionOrNull(name:String?,mime:String?):String? = name?.substringAfterLast('.',"")?.lowercase(java.util.Locale.ROOT)?.takeIf{it in audioExtensions}
    ?: when(mime?.substringBefore(';')?.lowercase(java.util.Locale.ROOT)) {
        "audio/mpeg","audio/mp3"->"mp3";"audio/mp4","audio/x-m4a","audio/m4a"->"m4a";"audio/m4b","audio/x-m4b"->"m4b"
        "audio/ogg","application/ogg"->"ogg";"audio/opus"->"opus";"audio/wav","audio/x-wav","audio/wave"->"wav"
        "audio/flac","audio/x-flac"->"flac";"audio/aac","audio/aacp","audio/x-aac"->"aac";else->null
    }
internal data class AudioSourceEntry(val id:String,val name:String,val type:String,val bytes:Long=0,val modified:Long=0)
internal data class AudioSourceGroup(val key:String,val title:String,val tracks:List<AudioSourceEntry>,val cover:AudioSourceEntry?)

/** Traverse containers separately from books, keeping CD folders in their book. */
internal suspend fun discoverAudioBooks(root:String,title:String,skipped:(AudioSourceEntry)->Unit={},children:suspend (String)->List<AudioSourceEntry>):List<AudioSourceGroup> {
    val groups=mutableListOf<AudioSourceGroup>();val seen=mutableSetOf<String>()
    val directory="vnd.android.document/directory"
    val disc=Regex("(?i)(cd|disc|disk)[ _-]*\\d+.*")
    fun audio(e:AudioSourceEntry)=audioExtensionOrNull(e.name,e.type)!=null
    fun cover(entries:List<AudioSourceEntry>)=entries.filter{it.name.matches(Regex("(?i)(cover|folder|front|book)\\.(jpe?g|png|webp)"))}
        .minByOrNull{listOf("cover","folder","front","book").indexOf(it.name.substringBeforeLast('.').lowercase())}
    fun add(key:String,name:String,tracks:List<AudioSourceEntry>,art:AudioSourceEntry?) {
        if(tracks.isEmpty())return
        require(tracks.size<=256){"A book has more than 256 tracks; choose a smaller folder."}
        require(groups.size<10_000){"This library contains too many books."}
        groups+=AudioSourceGroup(key,name,tracks,art)
    }
    suspend fun collect(id:String,prefix:String,depth:Int):List<AudioSourceEntry> {
        require(depth<=8 && seen.add(id)){"Folder nesting is too deep or contains a cycle."}
        val result=mutableListOf<AudioSourceEntry>()
        for(e in children(id)) {
            if(e.type.startsWith("audio/") && !audio(e))skipped(e)
            if(e.type==directory)result+=collect(e.id,"$prefix${e.name}/",depth+1)
            else if(audio(e))result+=e.copy(name="$prefix${e.name}")
            require(result.size<=256){"A book has more than 256 tracks; choose a smaller folder."}
        }
        return result
    }
    suspend fun visit(id:String,name:String,depth:Int,isRoot:Boolean=false) {
        require(depth<=8 && seen.add(id)){"Folder nesting is too deep or contains a cycle."}
        val items=children(id);val folders=items.filter{it.type==directory};val art=cover(items)
        items.filter{it.type.startsWith("audio/") && !audio(it)}.forEach(skipped)
        items.filter{it.name.endsWith(".foliobook",true)}.forEach{add(it.id,it.name.substringBeforeLast('.'),listOf(it),art)}
        val standalone=items.filter{it.name.endsWith(".m4b",true)}
        standalone.forEach{add(if(!isRoot && standalone.size==1 && folders.isEmpty())id else it.id,if(!isRoot && standalone.size==1 && folders.isEmpty())name else it.name.substringBeforeLast('.'),listOf(it),art)}
        val tracks=items.filter{audio(it) && !it.name.endsWith(".m4b",true)}.toMutableList()
        val discs=folders.filter{disc.matches(it.name)}
        discs.forEach{tracks+=collect(it.id,"${it.name}/",depth+1)}
        if(isRoot && discs.isEmpty())tracks.forEach{add(it.id,it.name.substringBeforeLast('.'),listOf(it),art)}
        else add(if(isRoot)"@root" else id,name,tracks,art)
        folders.filterNot{disc.matches(it.name)}.forEach{visit(it.id,it.name,depth+1)}
    }
    visit(root,title,0,true)
    return groups
}
