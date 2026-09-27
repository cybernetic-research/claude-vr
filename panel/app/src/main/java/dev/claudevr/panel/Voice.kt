package dev.claudevr.panel

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Mic input and spoken replies through the headset's built-in speech services
 * (on Quest: Meta's on-device recogniser; TTS falls back to any engine with a
 * voice, eSpeak NG first). Call from the main thread.
 */
class Voice(private val context: Context) {

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onListeningChanged(listening: Boolean)
        fun onError(message: String)
    }

    var listener: Listener? = null
    var listening = false
        private set

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private val triedEngines = mutableSetOf<String>()
    private val ttsThread = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)

    val canListen: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening() {
        if (listening) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(recognitionListener)
            recognizer = it
        }
        val intent = recognizeIntent()
        stopSpeaking()
        Log.i(TAG, "startListening (recognition available=${canListen})")
        r.startListening(intent)
        setListening(true)
    }

    private fun recognizeIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

    /** Asks the recogniser to fetch its model for our language (Android 13+ API). */
    private fun downloadRecognizerModel() {
        val r = recognizer ?: return
        if (Build.VERSION.SDK_INT < 33) return
        val intent = recognizeIntent()
        r.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                Log.i(TAG, "ASR support: installed=${support.installedOnDeviceLanguages} " +
                    "pending=${support.pendingOnDeviceLanguages} supported=${support.supportedOnDeviceLanguages} " +
                    "online=${support.onlineLanguages}")
            }
            override fun onError(error: Int) = Unit.also { Log.w(TAG, "ASR support check error $error") }
        })
        try {
            r.triggerModelDownload(intent)
            Log.i(TAG, "ASR model download requested")
        } catch (e: Exception) {
            Log.w(TAG, "ASR model download request failed", e)
        }
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    fun speak(text: String) {
        val engine = tts
        if (engine == null) {
            pendingSpeech = text
            startTts(prefs.getString(KEY_ENGINE, null) ?: ESPEAK)
            return
        }
        if (!ttsReady) {
            pendingSpeech = text // spoken once an engine is ready
            return
        }
        ttsThread.execute {
            val result = engine.speak(plain(text), TextToSpeech.QUEUE_FLUSH, null, "reply")
            Log.i(TAG, "TTS speak() -> $result (${text.length} chars)")
            if (result != TextToSpeech.SUCCESS) main.post { listener?.onError("Text-to-speech failed ($result).") }
        }
    }

    /**
     * Binds an engine (null = system default). If it has no usable voice, e.g.
     * Meta's engine before it has downloaded one, move on to the next installed
     * engine. The engine that works is remembered. Engine calls can block for
     * seconds (Meta's does), so they run on ttsThread, never the UI thread.
     */
    private fun startTts(enginePackage: String?) {
        ttsReady = false
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context, { status ->
            ttsThread.execute {
                val name = enginePackage ?: engine.defaultEngine
                Log.i(TAG, "TTS init $name status=$status")
                triedEngines += name
                val ok = status == TextToSpeech.SUCCESS && setUpTts(engine, name)
                val next = if (ok) null else engine.engines.map { it.name }.firstOrNull { it !in triedEngines }
                if (!ok) engine.shutdown()
                main.post {
                    when {
                        ok -> {
                            prefs.edit().putString(KEY_ENGINE, name).apply()
                            ttsReady = true
                            pendingSpeech?.let { pendingSpeech = null; speak(it) }
                        }
                        next != null -> startTts(next)
                        else -> {
                            tts = null
                            pendingSpeech = null
                            triedEngines.clear()
                            prefs.edit().remove(KEY_ENGINE).apply()
                            listener?.onError("No text-to-speech voice is installed on the headset.")
                        }
                    }
                }
            }
        }, enginePackage)
        tts = engine
    }

    fun stopSpeaking() {
        val engine = tts ?: return
        if (ttsReady) ttsThread.execute { engine.stop() }
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
        tts?.let { engine -> ttsThread.execute { engine.shutdown() } }
        tts = null
    }

    /** Picks a language the engine can speak; false if it has none. */
    private fun setUpTts(engine: TextToSpeech, name: String): Boolean {
        Log.i(TAG, "TTS engine=$name voices=${engine.voices?.size}")
        // Try the headset's locale, then fall back to US English.
        val ok = listOf(Locale.getDefault(), Locale.US).any { loc ->
            engine.setLanguage(loc).also { Log.i(TAG, "TTS setLanguage($loc) -> $it") } >= TextToSpeech.LANG_AVAILABLE
        }
        if (!ok) return false
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { Log.i(TAG, "TTS started") }
            override fun onDone(utteranceId: String?) { Log.i(TAG, "TTS done") }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { Log.w(TAG, "TTS error") }
            override fun onError(utteranceId: String?, errorCode: Int) { Log.w(TAG, "TTS error $errorCode") }
        })
        return true
    }

    private fun setListening(value: Boolean) {
        listening = value
        listener?.onListeningChanged(value)
    }

    /** Strip markdown so the TTS doesn't read out asterisks and hashes. */
    private fun plain(text: String) = text
        .replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " (code omitted) ")
        .replace(Regex("[*_#`>]"), "")

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle) {
            Log.i(TAG, "ASR partial: ${first(partialResults)}")
            first(partialResults)?.let { listener?.onPartial(it) }
        }

        override fun onResults(results: Bundle) {
            Log.i(TAG, "ASR results: ${first(results)}")
            setListening(false)
            val text = first(results)
            if (text.isNullOrBlank()) listener?.onError("Didn't catch that.")
            else listener?.onFinal(text)
        }

        override fun onError(error: Int) {
            Log.w(TAG, "ASR error $error")
            setListening(false)
            if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) downloadRecognizerModel()
            listener?.onError(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recogniser is busy; try again."
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "The headset's speech model isn't downloaded yet; asked it to download. Try the mic again in a minute."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Your language isn't supported by the headset's speech recogniser."
                    else -> "Speech recognition error ($error)."
                }
            )
        }

        override fun onEndOfSpeech() { Log.i(TAG, "ASR end of speech") }
        override fun onReadyForSpeech(params: Bundle?) { Log.i(TAG, "ASR ready") }
        override fun onBeginningOfSpeech() { Log.i(TAG, "ASR speech began") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        private fun first(b: Bundle) =
            b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    }

    companion object {
        private const val TAG = "ClaudePanel"
        private const val KEY_ENGINE = "ttsEngine"

        /** eSpeak NG (F-Droid): robotic but instant. Other engines are only tried if it's missing. */
        private const val ESPEAK = "com.reecedunn.espeak"
    }
}
