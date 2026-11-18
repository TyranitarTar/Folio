package app.folio.reader

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.core.view.WindowInsetsControllerCompat
import org.godotengine.godot.GodotActivity

/** Debug-only engine host in its own process: Godot's process exit cannot end playback. */
class CafePrototypeActivity : GodotActivity() {
    override fun getCommandLine(): MutableList<String> = super.getCommandLine().toMutableList().apply {
        addAll(listOf("--main-pack", "res://cafe-prototype.pck", "--rendering-method", "gl_compatibility"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("FolioCafePrototype", "Created engine host pid=${android.os.Process.myPid()}")
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window,window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // This experiment owns no durable game session. Release the engine offscreen.
        if (!isFinishing) finish()
    }

    override fun onGodotMainLoopStarted() {
        Log.i("FolioCafePrototype", "Godot main loop started")
    }
}
