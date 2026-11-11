package com.coachpocket.core.eval

import com.coachpocket.core.engine.ChannelMode
import com.coachpocket.core.engine.CoachEngine
import com.coachpocket.core.engine.CoachEvent
import com.coachpocket.core.engine.EngineConfig
import com.coachpocket.core.imu.ImuSample
import com.coachpocket.core.session.RecordedSession

/**
 * Runs a recorded session through the same [CoachEngine] the phone runs, delivering pose frames
 * and IMU packets in the order and at the times they arrived on the phone. Because the engine's
 * clock is driven by those arrival times, cue-decision latency measured here equals what the phone
 * experienced, minus the engine's own compute time (microseconds).
 */
object Replay {
    fun run(session: RecordedSession, config: EngineConfig = EngineConfig()): List<CoachEvent> {
        var now = 0L
        val engine = CoachEngine(config) { now }
        val out = ArrayList<CoachEvent>()
        val imu = session.imu
        val poses = session.poses
        var i = 0
        var p = 0
        val batch = ArrayList<ImuSample>(32)
        while (i < imu.size || p < poses.size) {
            val imuRx = if (i < imu.size) imu[i].rxUs else Long.MAX_VALUE
            val poseRx = if (p < poses.size) poses[p].rxUs else Long.MAX_VALUE
            if (imuRx <= poseRx) {
                batch.clear()
                while (i < imu.size && imu[i].rxUs == imuRx) batch += imu[i++].sample
                now = imuRx
                out += engine.onImuSamples(batch)
            } else {
                now = poseRx
                out += engine.onPoseFrame(poses[p++].frame)
            }
        }
        return out
    }

    fun run(session: RecordedSession, mode: ChannelMode, base: EngineConfig = EngineConfig()): List<CoachEvent> =
        run(session, base.copy(mode = mode))
}
