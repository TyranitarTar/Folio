package app.folio.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

internal const val AUDIO_METADATA_VERSION=1
internal fun audioChapters(file:File):List<AudioChapter> = runCatching {
    RandomAccessFile(file,"r").use { source -> Mp4ChapterReader(source.length()){offset,size ->
        source.seek(offset);ByteArray(size).also{source.readFully(it)}
    }.read() }
}.getOrDefault(emptyList())
internal fun audioChapters(context:Context,uri:Uri):List<AudioChapter> = runCatching {
    context.contentResolver.openAssetFileDescriptor(uri,"r")?.use { descriptor ->
        descriptor.createInputStream().use { input ->
            val channel=input.channel;val start=descriptor.startOffset
            val length=descriptor.declaredLength.takeIf{it>=0} ?: (channel.size()-start)
            Mp4ChapterReader(length){offset,size ->
                channel.position(start+offset);val buffer=java.nio.ByteBuffer.allocate(size)
                while(buffer.hasRemaining())require(channel.read(buffer)>0)
                buffer.array()
            }.read()
        }
    }.orEmpty()
}.getOrDefault(emptyList())
internal fun thumbnail(data:ByteArray):ByteArray? = runCatching {
    require(data.size in 1..8_000_000)
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    BitmapFactory.decodeByteArray(data,0,data.size,bounds)
    require(bounds.outWidth in 1..30000 && bounds.outHeight in 1..30000 && bounds.outWidth.toLong()*bounds.outHeight<=100_000_000)
    val options=BitmapFactory.Options().apply { var ratio=1;while(maxOf(bounds.outWidth,bounds.outHeight)/ratio>768)ratio*=2;inSampleSize=ratio }
    val bitmap=requireNotNull(BitmapFactory.decodeByteArray(data,0,data.size,options))
    try { ByteArrayOutputStream().also{require(bitmap.compress(Bitmap.CompressFormat.JPEG,86,it))}.toByteArray() }
    finally { bitmap.recycle() }
}.getOrNull()
