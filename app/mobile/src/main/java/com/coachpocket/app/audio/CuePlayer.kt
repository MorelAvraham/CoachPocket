package com.coachpocket.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.coachpocket.app.nowUs
import com.coachpocket.core.engine.Cue
import com.coachpocket.core.engine.FaultType
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Speaks the corrective cues.
 *
 * Calling TextToSpeech.speak() at the moment of the fault costs a synthesis delay of typically
 * 100-400 ms, which alone would spend the 300 ms budget. So every cue is synthesised once at
 * start-up into a WAV file and loaded into a SoundPool; at run time a cue is a pre-decoded sample
 * that starts within a few milliseconds of [play]. Live TTS remains as a fallback while the clips
 * are still being prepared.
 */
class CuePlayer(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val soundPool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attributes).build()
    private val clips = ConcurrentHashMap<FaultType, Int>()
    private val loading = HashMap<Int, FaultType>() // guarded by itself
    private val retries = ConcurrentHashMap<FaultType, Int>()
    private val tts = TextToSpeech(appContext, this)

    @Volatile
    private var ttsReady = false
    private var currentStream = 0

    /** True once every cue is available as a low-latency clip. */
    val clipsReady: Boolean get() = clips.size == FaultType.entries.size

    init {
        soundPool.setOnLoadCompleteListener { _, soundId, status ->
            // Same lock as the load call, so the id is always registered before it is looked up.
            val fault = synchronized(loading) { loading.remove(soundId) }
            if (status == 0 && fault != null) clips[fault] = soundId
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "text-to-speech engine unavailable ($status)")
            return
        }
        tts.language = Locale.US
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}

            override fun onDone(utteranceId: String) {
                val fault = clipFault(utteranceId) ?: return
                synchronized(loading) { loading[soundPool.load(clipFile(fault).path, 1)] = fault }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String) {
                Log.w(TAG, "could not synthesise $utteranceId")
                retry(utteranceId)
            }

            // The synthesis request was flushed from the queue before it ran.
            override fun onStop(utteranceId: String, interrupted: Boolean) = retry(utteranceId)
        })
        ttsReady = true
        for (fault in FaultType.entries) synthesise(fault)
    }

    private fun synthesise(fault: FaultType) {
        tts.synthesizeToFile(fault.cueText, null, clipFile(fault), CLIP_PREFIX + fault.name)
    }

    private fun clipFault(utteranceId: String): FaultType? =
        if (utteranceId.startsWith(CLIP_PREFIX)) FaultType.valueOf(utteranceId.removePrefix(CLIP_PREFIX)) else null

    /** A clip that failed stays on slow live speech for good unless it is tried again; allow a few attempts. */
    private fun retry(utteranceId: String) {
        val fault = clipFault(utteranceId) ?: return
        if (retries.merge(fault, 1, Int::plus)!! <= MAX_RETRIES) synthesise(fault)
    }

    /** Starts the cue and returns the time (phone clock, microseconds) at which playback was requested. */
    fun play(cue: Cue): Long {
        val clip = clips[cue.fault]
        if (clip != null) {
            if (cue.interrupt && currentStream != 0) soundPool.stop(currentStream)
            currentStream = soundPool.play(clip, 1f, 1f, 1, 0, 1f)
        } else if (ttsReady) {
            // QUEUE_FLUSH would also throw away clips still waiting to be synthesised.
            tts.speak(cue.text, if (clipsReady) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "live")
        }
        return nowUs()
    }

    fun release() {
        soundPool.release()
        tts.shutdown()
    }

    private fun clipFile(fault: FaultType) = File(appContext.cacheDir, "cue_${fault.name.lowercase()}.wav")

    private companion object {
        const val TAG = "CuePlayer"
        const val CLIP_PREFIX = "clip:"
        const val MAX_RETRIES = 3
    }
}
