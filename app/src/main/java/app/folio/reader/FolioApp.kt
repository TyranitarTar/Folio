package app.folio.reader

import android.app.Application

class FolioApp : Application() {
    val engine by lazy { BookEngine(this) }
    val library by lazy { LibraryStore(this, engine) }
    internal val audiobooks by lazy { AudiobookStore(this) }
    val bookDownloads by lazy { BookDownloads(this) }
    internal val voicePacks by lazy { VoicePackStore(this) }
    internal val voiceSamples by lazy { VoiceSamplePlayer(this) }
    internal val cafe by lazy { CafeStore(this) }
    internal val cafeReading by lazy { CafeReadingRewards(cafe) }
}
