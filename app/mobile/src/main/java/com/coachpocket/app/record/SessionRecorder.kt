package com.coachpocket.app.record

import android.content.Context
import android.util.Log
import com.coachpocket.core.imu.ImuSample
import com.coachpocket.core.pose.PoseFrame
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Writes a session as three CSV files in app-private storage:
 * `imu.csv` (every IMU sample with pod and phone time), `pose.csv` (33 keypoints per frame) and
 * `events.csv` (repetitions, faults, cues). Keypoints only, never video, so nothing identifiable
 * is stored (requirement R8). The files can be replayed through `:core` on a laptop.
 */
class SessionRecorder(private val context: Context) {
    private val io = Executors.newSingleThreadExecutor()
    private var imu: BufferedWriter? = null
    private var pose: BufferedWriter? = null
    private var events: BufferedWriter? = null

    @Volatile
    var directory: File? = null
        private set

    val isRecording: Boolean get() = directory != null

    fun start(): File? {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions/$name")
        if (!dir.mkdirs()) return null
        directory = dir
        io.execute {
            guarded {
                imu = File(dir, "imu.csv").bufferedWriter().apply { write("t_phone_us,t_pod_us,ax,ay,az,gx,gy,gz\n") }
                pose = File(dir, "pose.csv").bufferedWriter().apply {
                    write("t_us,aspect")
                    for (i in 0 until 33) write(",x$i,y$i,z$i,v$i")
                    for (i in 0 until 33) write(",wx$i,wy$i,wz$i")
                    write("\n")
                }
                events = File(dir, "events.csv").bufferedWriter().apply { write("t_us,type,detail\n") }
            }
        }
        return dir
    }

    fun stop() {
        directory = null
        io.execute {
            guarded {
                imu?.close(); pose?.close(); events?.close()
            }
            imu = null; pose = null; events = null
        }
    }

    /** [podSamples] and [phoneSamples] are the same samples on the two clocks. */
    fun imu(podSamples: List<ImuSample>, phoneSamples: List<ImuSample>) {
        if (!isRecording) return
        io.execute {
            val w = imu ?: return@execute
            guarded {
                val sb = StringBuilder(64 * podSamples.size)
                for (i in podSamples.indices) {
                    val s = podSamples[i]
                    sb.append(phoneSamples[i].tUs).append(',').append(s.tUs).append(',')
                        .append(s.ax).append(',').append(s.ay).append(',').append(s.az).append(',')
                        .append(s.gx).append(',').append(s.gy).append(',').append(s.gz).append('\n')
                }
                w.write(sb.toString())
            }
        }
    }

    fun pose(frame: PoseFrame) {
        if (!isRecording || frame.image.isEmpty()) return
        io.execute {
            val w = pose ?: return@execute
            guarded {
                val sb = StringBuilder(2048)
                sb.append(frame.tUs).append(',').append(frame.aspect)
                for (l in frame.image) sb.append(',').append(l.x).append(',').append(l.y).append(',').append(l.z).append(',').append(l.visibility)
                for (i in 0 until 33) {
                    val l = frame.world.getOrNull(i)
                    if (l == null) sb.append(",,,") else sb.append(',').append(l.x).append(',').append(l.y).append(',').append(l.z)
                }
                w.write(sb.append('\n').toString())
            }
        }
    }

    fun event(tUs: Long, type: String, detail: String) {
        if (!isRecording) return
        io.execute {
            val w = events ?: return@execute
            guarded {
                w.write("$tUs,$type,\"${detail.replace("\"", "'")}\"\n")
                w.flush()
            }
        }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            Log.e("SessionRecorder", "write failed", e)
        }
    }
}
