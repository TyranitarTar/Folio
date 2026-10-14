package app.folio.reader

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit, reproducible release-asset generation; never downloads or runs in ordinary tests. */
@RunWith(AndroidJUnit4::class)
class VoiceSampleGenerationInstrumentedTest {
    @Test fun generateAuthenticCatalogSamples() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("generateVoiceSamples") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FolioApp
        SpeechController.stop(app)
        val output = File(app.filesDir, "voice-preview-artifacts").apply { mkdirs() }
        val report = JSONArray()
        for (pack in app.voicePacks.catalog) {
            val engine = createLocalSpeechEngine(app, pack.backend)
            try {
                engine.prepare()
                for (voice in pack.voices) {
                    val phrase = if (voice.language == "da") "Velkommen til Folio. Denne stemme læser dine bøger højt i det tempo, du vælger."
                        else "Welcome to Folio. This voice reads your books aloud at your chosen pace."
                    val settings = SpeechSettings(backend=pack.backend, aiVoice=voice.id.toInt(),
                        supertonicVoice=voice.id.toInt(), supertonicLanguage=voice.language)
                    val clip = engine.generate(phrase, settings)
                    assertTrue(clip.samples.size > clip.sampleRate)
                    assertTrue(clip.samples.size < clip.sampleRate * 25)
                    val name = if (pack.backend == "kokoro") "sample-kokoro-${if(voice.id=="21")"emma" else "heart"}-en.m4a"
                        else "sample-supertonic-v${(voice.id.toInt()+1).toString().padStart(2,'0')}-${voice.language}.m4a"
                    val file = File(output, name)
                    encode(file, clip)
                    assertTrue(file.length() in 1..2_000_000)
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(file.path)
                        val format=extractor.getTrackFormat(0)
                        assertEquals("audio/mp4a-latm", format.getString(MediaFormat.KEY_MIME))
                        assertTrue(format.getLong(MediaFormat.KEY_DURATION) > 1_000_000)
                    } finally { extractor.release() }
                    report.put(JSONObject().put("file",name).put("backend",pack.backend).put("voice",voice.id)
                        .put("language",voice.language).put("phrase",phrase).put("rate",1)
                        .put("sourceSamples",clip.samples.size).put("sampleRate",clip.sampleRate)
                        .put("synthesisMillis",clip.synthesisMillis).put("pcmSha256",SpeechPcm.digest(clip.samples,clip.sampleRate)))
                    File(output,"provenance.json").writeText(report.toString(2))
                }
            } finally { engine.close() }
        }
        assertEquals(22, report.length())
        Unit
    }

    private fun encode(file: File, clip: AiAudioClip) {
        val format=MediaFormat.createAudioFormat("audio/mp4a-latm",clip.sampleRate,1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE,64000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,8192)
        }
        val codec=MediaCodec.createEncoderByType("audio/mp4a-latm")
        val muxer=MediaMuxer(file.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started=false;var track=-1;var offset=0;var inputDone=false;var outputDone=false
        val info=MediaCodec.BufferInfo()
        try {
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start()
            val deadline=android.os.SystemClock.elapsedRealtime()+60000
            while(!outputDone) {
                check(android.os.SystemClock.elapsedRealtime()<deadline) { "AAC encoding timed out" }
                if(!inputDone) {
                    val index=codec.dequeueInputBuffer(10000)
                    if(index>=0) {
                        val buffer=codec.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN);buffer.clear()
                        val count=minOf(buffer.remaining()/2,clip.samples.size-offset)
                        for(i in 0 until count)buffer.putShort((clip.samples[offset+i].coerceIn(-1f,1f)*32767).toInt().toShort())
                        val pts=offset.toLong()*1_000_000/clip.sampleRate;offset+=count
                        inputDone=offset==clip.samples.size
                        codec.queueInputBuffer(index,0,count*2,pts,if(inputDone)MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val index=codec.dequeueOutputBuffer(info,10000)
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!started);track=muxer.addTrack(codec.outputFormat);muxer.start();started=true
                } else if(index>=0) {
                    if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                        check(started);val buffer=codec.getOutputBuffer(index)!!
                        buffer.position(info.offset);buffer.limit(info.offset+info.size);muxer.writeSampleData(track,buffer,info)
                    }
                    outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    codec.releaseOutputBuffer(index,false)
                }
            }
        } finally {
            runCatching { codec.stop() };codec.release()
            if(started)runCatching { muxer.stop() };muxer.release()
        }
    }
}
