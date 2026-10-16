package app.folio.reader

import android.content.Context

/** Both bundled models return the same PCM format to the checkpointed audio player. */
internal interface LocalSpeechEngine {
    val backend: String
    val prepared: Boolean
    suspend fun prepare()
    suspend fun generate(text: String, settings: SpeechSettings): AiAudioClip
    fun cancelGeneration()
    fun close()
}

internal fun createLocalSpeechEngine(context: Context, backend: String): LocalSpeechEngine = when (backend) {
    "pico" -> PicoEngine(context)
    "kokoro" -> object : LocalSpeechEngine {
        private val engine = KokoroEngine(context)
        override val backend = "kokoro"
        override val prepared get() = engine.isPrepared
        override suspend fun prepare() = engine.prepare()
        override suspend fun generate(text: String, settings: SpeechSettings) = engine.generate(text, settings.aiVoice, settings.rate)
        override fun cancelGeneration() = engine.cancelGeneration()
        override fun close() = engine.close()
    }
    "supertonic" -> object : LocalSpeechEngine {
        private val engine = SupertonicEngine(context)
        override val backend = "supertonic"
        override val prepared get() = engine.isPrepared
        override suspend fun prepare() = engine.prepare()
        override suspend fun generate(text: String, settings: SpeechSettings) = engine.generate(text, settings.supertonicVoice, 1f, settings.supertonicLanguage)
        override fun cancelGeneration() = engine.cancelGeneration()
        override fun close() = engine.close()
    }
    else -> throw IllegalArgumentException("Choose a bundled local voice engine.")
}
