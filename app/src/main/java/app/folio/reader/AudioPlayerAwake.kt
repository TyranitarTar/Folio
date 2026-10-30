package app.folio.reader

import android.app.Activity
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

@Composable internal fun playerAwakeModifier(id: String, readAlong: Boolean, active: Boolean): Modifier {
    val context = LocalContext.current
    val window = (context as? Activity)?.window
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var lastInput by rememberSaveable(id) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var wasActive by rememberSaveable(id) { mutableStateOf(false) }
    val currentActive by rememberUpdatedState(active)
    DisposableEffect(lifecycle, window) {
        val observer = LifecycleEventObserver { _, event ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_PAUSE) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(active) {
        if (wasActive && !active) lastInput = SystemClock.elapsedRealtime()
        wasActive = active
    }
    LaunchedEffect(id, foreground, readAlong, active, lastInput) {
        while (true) {
            val enabled = audioPlayerAwake(Appearance.load(context).awakePolicy, foreground, readAlong,
                currentActive, SystemClock.elapsedRealtime(), lastInput)
            if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            delay(1000)
        }
    }
    return Modifier.pointerInput(id) {
        awaitPointerEventScope { while (true) {
            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            if (event.changes.any { it.pressed }) lastInput = SystemClock.elapsedRealtime()
        } }
    }
}
