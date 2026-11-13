package app.folio.reader

import java.util.concurrent.atomic.AtomicLong

/** Process-local observation order, captured BEFORE a save is dispatched to IO. */
internal object AudioCursorOrder {
    private val sequence=AtomicLong()
    fun next():Long=sequence.incrementAndGet()
}

internal class AudioCursorGate {
    private val latest=mutableMapOf<String,Long>()
    fun accepts(id:String,order:Long):Boolean=(latest[id] ?: Long.MIN_VALUE)<=order
    fun committed(id:String,order:Long){latest[id]=order}
}
