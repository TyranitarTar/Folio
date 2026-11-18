package app.folio.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.godotengine.godot.Godot
import org.godotengine.godot.GodotActivity
import org.godotengine.godot.plugin.GodotPlugin
import java.util.concurrent.atomic.AtomicBoolean

/** One engine in a dedicated process: quitting Godot must never end a reader or audio service. */
class CafeActivity : GodotActivity() {
    private var bridge:CafeGodotBridge?=null
    private var loading:LinearLayout?=null
    private val ready=AtomicBoolean(false)
    private val handler=Handler(Looper.getMainLooper())
    private var registered=false
    private val debugReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            if(intent.hasExtra("command")) bridge?.command(intent.getStringExtra("command").orEmpty())
            resultData=org.json.JSONObject(bridge?.latest?.get() ?: "{}").put("keepAwake",
                window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON!=0).toString()
        }
    }
    override fun getCommandLine(): MutableList<String> = super.getCommandLine().toMutableList().apply {
        addAll(listOf("--main-pack","res://cafe-engine/miso-home.pck","--rendering-method","gl_compatibility","--audio-driver","Dummy"))
    }
    override fun getHostPlugins(engine:Godot): Set<GodotPlugin> = super.getHostPlugins(engine)+
        CafeGodotBridge(engine,this).also { bridge=it }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        loading=LinearLayout(this).also { box ->
            box.orientation=LinearLayout.VERTICAL;box.gravity=Gravity.CENTER
            box.setPadding(32,32,32,32);box.setBackgroundColor(Color.rgb(242,235,223))
            box.addView(TextView(this).apply { text="Åbner Misos hjem…";textSize=22f;setTextColor(Color.rgb(57,73,63)) })
            addContentView(box,ViewGroup.LayoutParams(-1,-1))
        }
        if(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0) {
            ContextCompat.registerReceiver(this,debugReceiver,IntentFilter(packageName+".CAFE_DEBUG"),ContextCompat.RECEIVER_NOT_EXPORTED)
            registered=true
        }
        handler.postDelayed({ if(!ready.get() && !isFinishing) loading?.let { box ->
            (box.getChildAt(0) as? TextView)?.text="Misos hjem kunne ikke åbnes."
            box.addView(Button(this).apply { text="Tilbage til Folio";setOnClickListener { finish() } })
            box.addView(Button(this).apply { text="Åbn tidligere værelse";setOnClickListener {
                startActivity(Intent(this@CafeActivity,CafeArchiveActivity::class.java).putExtra("classic",true));finish()
            } })
        } },15_000)
    }
    internal fun engineReady() {
        if(ready.compareAndSet(false,true)) runOnUiThread {
            loading?.let { (it.parent as? ViewGroup)?.removeView(it) };loading=null
        }
    }
    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window,window.decorView).apply {
            isAppearanceLightStatusBars=true;isAppearanceLightNavigationBars=true
        }
    }
    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
    override fun onStop() {
        super.onStop()
        if(!isFinishing) finish()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if(registered) unregisterReceiver(debugReceiver)
        bridge=null
        super.onDestroy()
    }
}
