package com.coachpocket.core.session

import com.coachpocket.core.engine.FaultType
import com.coachpocket.core.imu.ImuSample
import com.coachpocket.core.pose.Landmark
import com.coachpocket.core.pose.PoseFrame
import com.coachpocket.core.pose.PoseIndex
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.util.Properties

/**
 * Recorded-session format, version 1. One directory per recorded set:
 *
 *  - `meta.properties`  who, what and how (see [SessionMeta])
 *  - `imu.csv`          every IMU sample: phone-clock time, pod-clock time, packet arrival time, 6 axes
 *  - `pose.csv`         every pose frame: capture time, result arrival time, 33 keypoints (+ world)
 *  - `events.csv`       what the app reported live (informational; evaluation re-runs the engine)
 *  - `labels.csv`       optional per-repetition ground truth that overrides the set-level condition
 *
 * All times are microseconds. Arrival times are stored so that an offline replay can feed the
 * engine in the order, and with the delays, that the phone actually experienced.
 */
object SessionFormat {
    const val VERSION = 1
    const val META = "meta.properties"
    const val IMU = "imu.csv"
    const val POSE = "pose.csv"
    const val EVENTS = "events.csv"
    const val LABELS = "labels.csv"
    const val CLEAN = "clean"

    /** "clean" or fault names joined with '+', e.g. "knee_valgus+too_fast_descent". */
    fun encodeFaults(faults: Set<FaultType>): String =
        if (faults.isEmpty()) CLEAN else faults.sortedBy { it.ordinal }.joinToString("+") { it.name.lowercase() }

    fun decodeFaults(text: String): Set<FaultType> {
        val t = text.trim().lowercase()
        if (t.isEmpty() || t == CLEAN) return emptySet()
        return t.split('+').map { name ->
            FaultType.entries.firstOrNull { it.name.lowercase() == name.trim() }
                ?: throw IllegalArgumentException("unknown fault '$name'")
        }.toSet()
    }
}

/** Set-level description. Free-form keys are allowed; the typed accessors cover what evaluation needs. */
class SessionMeta(val values: Map<String, String>) {
    val exercise: String get() = values["exercise"] ?: "squat"
    val subject: String get() = values["subject"]?.ifBlank { null } ?: "unknown"

    /** Faults the lifter performed on purpose in this set; empty = clean technique. */
    val condition: Set<FaultType> get() = SessionFormat.decodeFaults(values["condition"] ?: SessionFormat.CLEAN)

    /** How many repetitions the lifter actually performed, if the operator entered it. */
    val expectedReps: Int? get() = values["expected_reps"]?.toIntOrNull()
    val view: String get() = values["view"] ?: "unknown"

    fun with(vararg pairs: Pair<String, String>): SessionMeta = SessionMeta(values + pairs)

    companion object {
        fun of(
            subject: String,
            condition: Set<FaultType>,
            expectedReps: Int? = null,
            exercise: String = "squat",
            extra: Map<String, String> = emptyMap(),
        ): SessionMeta {
            val m = LinkedHashMap<String, String>()
            m["format_version"] = SessionFormat.VERSION.toString()
            m["exercise"] = exercise
            m["subject"] = subject
            m["condition"] = SessionFormat.encodeFaults(condition)
            if (expectedReps != null) m["expected_reps"] = expectedReps.toString()
            m.putAll(extra)
            return SessionMeta(m)
        }
    }
}

class ImuRow(
    /** The sample with [ImuSample.tUs] on the phone clock, as mapped live by the clock sync. */
    val sample: ImuSample,
    val podUs: Long,
    /** Arrival time of the BLE packet that carried the sample. */
    val rxUs: Long,
)

class PoseRow(val frame: PoseFrame, /** When the pose result became available. */ val rxUs: Long)

class RecordedSession(
    val name: String,
    val meta: SessionMeta,
    val imu: List<ImuRow>,
    val poses: List<PoseRow>,
    /** Per-repetition ground truth (1-based repetition index); missing repetitions use the set condition. */
    val repLabels: Map<Int, Set<FaultType>> = emptyMap(),
) {
    fun labelOf(repIndex: Int): Set<FaultType> = repLabels[repIndex] ?: meta.condition
}

/** Not thread-safe: call from a single writer thread. */
class SessionWriter(private val dir: File) : Closeable {
    private var imu: BufferedWriter? = null
    private var pose: BufferedWriter? = null
    private var events: BufferedWriter? = null
    private val line = StringBuilder(4096)

    fun open(meta: SessionMeta) {
        dir.mkdirs()
        writeMeta(dir, meta)
        imu = File(dir, SessionFormat.IMU).bufferedWriter().apply { write("t_phone_us,t_pod_us,t_rx_us,ax,ay,az,gx,gy,gz\n") }
        pose = File(dir, SessionFormat.POSE).bufferedWriter().apply {
            write("t_us,t_rx_us,aspect")
            for (i in 0 until PoseIndex.COUNT) write(",x$i,y$i,z$i,v$i")
            for (i in 0 until PoseIndex.COUNT) write(",wx$i,wy$i,wz$i")
            write("\n")
        }
        events = File(dir, SessionFormat.EVENTS).bufferedWriter().apply { write("t_us,type,detail\n") }
    }

    /** [podSamples] and [phoneSamples] are the same samples on the two clocks. */
    fun imu(podSamples: List<ImuSample>, phoneSamples: List<ImuSample>, rxUs: Long) {
        val w = imu ?: return
        line.setLength(0)
        for (i in podSamples.indices) {
            val s = podSamples[i]
            line.append(phoneSamples[i].tUs).append(',').append(s.tUs).append(',').append(rxUs).append(',')
                .append(s.ax).append(',').append(s.ay).append(',').append(s.az).append(',')
                .append(s.gx).append(',').append(s.gy).append(',').append(s.gz).append('\n')
        }
        w.write(line.toString())
    }

    fun pose(frame: PoseFrame, rxUs: Long) {
        val w = pose ?: return
        if (frame.image.size < PoseIndex.COUNT) return // nobody in view: nothing to store
        line.setLength(0)
        line.append(frame.tUs).append(',').append(rxUs).append(',').append(frame.aspect)
        for (l in frame.image) line.append(',').append(l.x).append(',').append(l.y).append(',').append(l.z).append(',').append(l.visibility)
        for (i in 0 until PoseIndex.COUNT) {
            val l = frame.world.getOrNull(i)
            if (l == null) line.append(",,,") else line.append(',').append(l.x).append(',').append(l.y).append(',').append(l.z)
        }
        w.write(line.append('\n').toString())
    }

    fun event(tUs: Long, type: String, detail: String) {
        val w = events ?: return
        w.write("$tUs,$type,\"${detail.replace('"', '\'').replace('\n', ' ')}\"\n")
        w.flush()
    }

    override fun close() {
        imu?.close(); pose?.close(); events?.close()
        imu = null; pose = null; events = null
    }

    companion object {
        fun writeMeta(dir: File, meta: SessionMeta) {
            // Plain key=value lines in insertion order (java.util.Properties would shuffle them).
            File(dir, SessionFormat.META).bufferedWriter().use { w ->
                for ((k, v) in meta.values) w.write("$k=${v.replace("\\", "\\\\").replace("\n", " ")}\n")
            }
        }

        fun writeLabels(dir: File, labels: Map<Int, Set<FaultType>>) {
            File(dir, SessionFormat.LABELS).bufferedWriter().use { w ->
                w.write("rep,label\n")
                for ((rep, faults) in labels.toSortedMap()) w.write("$rep,${SessionFormat.encodeFaults(faults)}\n")
            }
        }

        fun write(dir: File, session: RecordedSession) {
            SessionWriter(dir).use { w ->
                w.open(session.meta)
                var i = 0
                while (i < session.imu.size) {
                    var j = i
                    while (j < session.imu.size && session.imu[j].rxUs == session.imu[i].rxUs) j++
                    val batch = session.imu.subList(i, j)
                    w.imu(batch.map { it.sample.copy(tUs = it.podUs) }, batch.map { it.sample }, session.imu[i].rxUs)
                    i = j
                }
                for (p in session.poses) w.pose(p.frame, p.rxUs)
            }
            if (session.repLabels.isNotEmpty()) writeLabels(dir, session.repLabels)
        }
    }
}

object SessionReader {
    fun isSession(dir: File): Boolean = File(dir, SessionFormat.META).isFile

    /** All session directories under [root], or [root] itself if it is one. */
    fun find(root: File): List<File> =
        if (isSession(root)) listOf(root) else root.walkTopDown().filter { it.isDirectory && isSession(it) }.sortedBy { it.path }.toList()

    fun read(dir: File): RecordedSession {
        val props = Properties()
        File(dir, SessionFormat.META).reader(Charsets.UTF_8).use { props.load(it) }
        val meta = SessionMeta(props.stringPropertyNames().associateWith { props.getProperty(it) })

        val imu = ArrayList<ImuRow>()
        File(dir, SessionFormat.IMU).takeIf { it.isFile }?.useLines { lines ->
            for (l in lines.drop(1)) {
                val c = l.split(',')
                if (c.size < 9) continue
                imu += ImuRow(
                    ImuSample(c[0].toLong(), c[3].toFloat(), c[4].toFloat(), c[5].toFloat(), c[6].toFloat(), c[7].toFloat(), c[8].toFloat()),
                    podUs = c[1].toLong(), rxUs = c[2].toLong(),
                )
            }
        }

        val poses = ArrayList<PoseRow>()
        File(dir, SessionFormat.POSE).takeIf { it.isFile }?.useLines { lines ->
            val n = PoseIndex.COUNT
            for (l in lines.drop(1)) {
                val c = l.split(',')
                if (c.size < 3 + n * 4) continue
                val image = ArrayList<Landmark>(n)
                for (i in 0 until n) {
                    val o = 3 + i * 4
                    image += Landmark(c[o].toFloat(), c[o + 1].toFloat(), c[o + 2].toFloat(), c[o + 3].toFloat())
                }
                val world = ArrayList<Landmark>(n)
                val wo = 3 + n * 4
                if (c.size >= wo + n * 3 && c[wo].isNotEmpty()) {
                    for (i in 0 until n) {
                        val o = wo + i * 3
                        world += Landmark(c[o].toFloat(), c[o + 1].toFloat(), c[o + 2].toFloat(), image[i].visibility)
                    }
                }
                poses += PoseRow(PoseFrame(c[0].toLong(), image, world, c[2].toFloat()), c[1].toLong())
            }
        }

        val labels = HashMap<Int, Set<FaultType>>()
        File(dir, SessionFormat.LABELS).takeIf { it.isFile }?.useLines { lines ->
            for (l in lines.drop(1)) {
                val c = l.split(',')
                if (c.size >= 2 && c[0].trim().toIntOrNull() != null) labels[c[0].trim().toInt()] = SessionFormat.decodeFaults(c[1])
            }
        }
        return RecordedSession(dir.name, meta, imu, poses, labels)
    }
}
