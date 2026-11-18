package app.folio.reader

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** One small, atomic room document; no library, wallet or reading-state writes. */
internal class CafeGodotState(context: Context) {
    private val app = context.applicationContext
    internal val file = File(app.filesDir,"cafe-room-godot-v1.json")
    private val atomic = AtomicFile(file)

    @Synchronized fun load(): JSONObject {
        val stored = runCatching { atomic.openRead().use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        if(stored != null) return sanitize(stored)
        val previous = CafeRoomPreferences(app)
        return sanitize(JSONObject().put("mode",previous.mode).put("pace",previous.pace)
            .put("ambient",previous.ambient).put("reduced",previous.reduced)
            .put("counts",previous.counts()).toString())
    }

    @Synchronized fun initialize() { if(!file.exists()) write(load()) }

    @Synchronized fun save(json: String): Boolean {
        if(json.toByteArray(Charsets.UTF_8).size>MAX_BYTES) return false
        val incoming=runCatching { JSONObject(json) }.getOrNull() ?: return false
        if(incoming.optInt("version",1)!=1) return false
        val clean=sanitize(json)
        // A delayed writer can never erase discoveries made by a newer engine session.
        val counts=clean.getJSONObject("counts")
        val old=load().getJSONObject("counts")
        cafeRoomActions.keys.forEach { id ->
            val best=maxOf(counts.optInt(id,0),old.optInt(id,0))
            if(best>0) counts.put(id,best)
        }
        return write(clean)
    }

    private fun write(value: JSONObject): Boolean {
        var stream: java.io.FileOutputStream?=null
        return try {
            stream=atomic.startWrite()
            stream.write(value.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            true
        } catch(_:Exception) { stream?.let { atomic.failWrite(it) };false }
    }

    companion object {
        const val MAX_BYTES=16_384
        fun sanitize(json:String): JSONObject {
            val source=if(json.length<=MAX_BYTES) runCatching { JSONObject(json) }.getOrNull() ?: JSONObject() else JSONObject()
            fun bool(key:String,fallback:Boolean)=source.opt(key).let { if(it is Boolean) it else fallback }
            val mode=source.optString("mode").takeIf { it in cafeRoomModes } ?: "random"
            val pace=source.optString("pace").takeIf { it in cafeRoomPaces } ?: "walk"
            val density=source.optInt("density",128).takeIf { it==96 || it==128 } ?: 128
            return JSONObject().put("version",1).put("mode",mode).put("pace",pace).put("density",density)
                .put("ambient",bool("ambient",true)).put("reduced",bool("reduced",false)).put("night",bool("night",false))
                .put("counts",CafeRoomPreferences.sanitize(source.optJSONObject("counts")?.toString() ?: "{}"))
        }
    }
}
