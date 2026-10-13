package app.folio.reader

import android.app.Application

class FolioApp : Application() {
    val engine by lazy { BookEngine(this) }
    val library by lazy { LibraryStore(this, engine) }
}
