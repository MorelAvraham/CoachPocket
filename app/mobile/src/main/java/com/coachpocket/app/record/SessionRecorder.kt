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
import java.util.concurrent.RejectedExecutionException

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
        submit { writer = SessionWriter(dir).also { it.open(meta) } }
        directory = dir
        return dir
    }

    fun stop() {
        directory = null
        submit {
            try {
                writer?.close()
            } finally {
                writer = null
            }
        }
    }

    /** Finishes any open session and ends the writer thread. The recorder cannot be used afterwards. */
    fun close() {
        stop()
        io.shutdown()
    }

    fun imu(podSamples: List<ImuSample>, phoneSamples: List<ImuSample>, rxUs: Long) {
        if (!isRecording) return
        submit { writer?.imu(podSamples, phoneSamples, rxUs) }
    }

    fun pose(frame: PoseFrame, rxUs: Long) {
        if (!isRecording) return
        submit { writer?.pose(frame, rxUs) }
    }

    fun event(tUs: Long, type: String, detail: String) {
        if (!isRecording) return
        submit { writer?.event(tUs, type, detail) }
    }

    private fun submit(block: () -> Unit) {
        try {
            io.execute {
                try {
                    block()
                } catch (e: IOException) {
                    Log.e(TAG, "write failed", e)
                }
            }
        } catch (e: RejectedExecutionException) {
            // A camera or Bluetooth callback that was already in flight when the recorder closed.
        }
    }

    private companion object {
        const val TAG = "SessionRecorder"
    }
}
