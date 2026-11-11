package com.coachpocket.app.record

import android.content.Context
import android.util.Log
import com.coachpocket.core.imu.ImuSample
import com.coachpocket.core.pose.PoseFrame
import com.coachpocket.core.session.SessionMeta
import com.coachpocket.core.session.SessionWriter
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Records a set to app-private storage in the session format defined in `:core`
 * ([com.coachpocket.core.session.SessionFormat]): keypoints, IMU samples and events, never video,
 * so nothing identifiable is stored (requirement R8). All file work happens on one background
 * thread so that neither the camera nor the Bluetooth callbacks ever wait for the disk.
 */
class SessionRecorder(private val context: Context) {
    private val io = Executors.newSingleThreadExecutor()
    private var writer: SessionWriter? = null // touched only on the io thread

    @Volatile
    var directory: File? = null
        private set

    val isRecording: Boolean get() = directory != null

    fun start(meta: SessionMeta): File? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeSubject = meta.subject.replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
        val name = "${stamp}_${safeSubject}_${meta.values["condition"] ?: "clean"}"
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions/$name")
        if (!dir.mkdirs()) return null
        io.execute { guarded { writer = SessionWriter(dir).also { it.open(meta) } } }
        directory = dir
        return dir
    }

    fun stop() {
        directory = null
        io.execute {
            guarded { writer?.close() }
            writer = null
        }
    }

    fun imu(podSamples: List<ImuSample>, phoneSamples: List<ImuSample>, rxUs: Long) {
        if (!isRecording) return
        io.execute { guarded { writer?.imu(podSamples, phoneSamples, rxUs) } }
    }

    fun pose(frame: PoseFrame, rxUs: Long) {
        if (!isRecording) return
        io.execute { guarded { writer?.pose(frame, rxUs) } }
    }

    fun event(tUs: Long, type: String, detail: String) {
        if (!isRecording) return
        io.execute { guarded { writer?.event(tUs, type, detail) } }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            Log.e("SessionRecorder", "write failed", e)
        }
    }
}
