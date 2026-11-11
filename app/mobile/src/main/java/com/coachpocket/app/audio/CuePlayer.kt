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
    private val loading = ConcurrentHashMap<Int, FaultType>()
    private val tts = TextToSpeech(appContext, this)

    @Volatile
    private var ttsReady = false
    private var currentStream = 0

    /** True once every cue is available as a low-latency clip. */
    val clipsReady: Boolean get() = clips.size == FaultType.entries.size

    init {
        soundPool.setOnLoadCompleteListener { _, soundId, status ->
            val fault = loading.remove(soundId)
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
                if (!utteranceId.startsWith(CLIP_PREFIX)) return
                val fault = FaultType.valueOf(utteranceId.removePrefix(CLIP_PREFIX))
                loading[soundPool.load(clipFile(fault).path, 1)] = fault
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String) {
                Log.w(TAG, "could not synthesise $utteranceId")
            }
        })
        ttsReady = true
        for (fault in FaultType.entries) {
            tts.synthesizeToFile(fault.cueText, null, clipFile(fault), CLIP_PREFIX + fault.name)
        }
    }

    /** Starts the cue and returns the time (phone clock, microseconds) at which playback was requested. */
    fun play(cue: Cue): Long {
        val clip = clips[cue.fault]
        if (clip != null) {
            if (cue.interrupt && currentStream != 0) soundPool.stop(currentStream)
            currentStream = soundPool.play(clip, 1f, 1f, 1, 0, 1f)
        } else if (ttsReady) {
            tts.speak(cue.text, TextToSpeech.QUEUE_FLUSH, null, "live")
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
    }
}
