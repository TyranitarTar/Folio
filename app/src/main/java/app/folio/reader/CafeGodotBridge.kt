package app.folio.reader

import android.content.Intent
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo
import org.godotengine.godot.plugin.UsedByGodot
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** Local, typed engine bridge. Status stays in memory; only explicit saves touch disk. */
internal class CafeGodotBridge(engine:Godot,private val host:CafeActivity) : GodotPlugin(engine) {
    private val store=CafeGodotState(host)
    val latest=AtomicReference("{}")
    override fun getPluginName()="FolioCafe"
    override fun getPluginSignals(): Set<SignalInfo> = setOf(SignalInfo("host_command",String::class.java))
    @UsedByGodot fun load_state(): String = store.load().put("systemReduced",
        android.provider.Settings.Global.getFloat(host.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f).toString()
    @UsedByGodot fun diagnostics_enabled(): Boolean = host.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0
    @UsedByGodot fun save_state(value:String): Boolean = store.save(value)
    @UsedByGodot fun report_status(value:String) {
        if(value.length>16_384) return
        val json=runCatching { JSONObject(value) }.getOrNull() ?: return
        latest.set(value)
        if(json.optBoolean("ready")) host.engineReady()
    }
    @UsedByGodot fun open_archive() { host.runOnUiThread {
        host.startActivity(Intent(host,CafeArchiveActivity::class.java))
        host.finish()
    } }
    fun command(value:String) { if(value.length<=4096) emitSignal("host_command",value) }
}
