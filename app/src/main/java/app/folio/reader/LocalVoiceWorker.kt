package app.folio.reader

import java.util.concurrent.Executors

/** Native release and initialization share one queue across all local speech backends. */
internal object LocalVoiceWorker {
    val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Folio local voice").apply { isDaemon = true }
    }
}
