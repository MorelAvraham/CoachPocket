package com.coachpocket.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.coachpocket.app.audio.CuePlayer
import com.coachpocket.app.ble.PodBleClient
import com.coachpocket.app.ble.PodState
import com.coachpocket.app.camera.PoseEstimator
import com.coachpocket.app.camera.PoseModel
import com.coachpocket.app.record.SessionRecorder
import com.coachpocket.core.engine.CoachEngine
import com.coachpocket.core.engine.CoachEvent
import com.coachpocket.core.pose.PoseFrame
import com.coachpocket.core.protocol.PodPacketCodec
import com.coachpocket.core.protocol.PodStreamDecoder
import com.coachpocket.core.sim.SimulatedPod
import com.coachpocket.core.sim.SquatMotion
import com.coachpocket.core.squat.SquatPhase
import com.coachpocket.core.sync.ClockSync
import com.coachpocket.core.util.RollingStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class UiState(
    // Vision
    val fps: Float = 0f,
    val inferenceMs: Float = 0f,
    val delegate: String = "-",
    val model: PoseModel = PoseModel.FULL,
    val poseError: String? = null,
    val cameraClockSynced: Boolean = false,
    val phase: SquatPhase = SquatPhase.NOT_TRACKED,
    val reps: Int = 0,
    val hipHeight: Float = Float.NaN,
    val kneeAngle: Float = Float.NaN,
    val valgus: Float = Float.NaN,
    val frontalView: Boolean = false,
    val lastRep: String = "",
    // Pod
    val podState: PodState = PodState.IDLE,
    val podMessage: String? = null,
    val sampleRateHz: Float = 0f,
    val packetsReceived: Long = 0,
    val packetsLost: Long = 0,
    val lossPercent: Float = 0f,
    val syncLocked: Boolean = false,
    val skewPpm: Float = 0f,
    val transportBiasMs: Float = 0f,
    val imuVelocity: Float = 0f,
    val lastDescent: String = "",
    // Cues
    val lastCue: String = "",
    val lastCueAtUs: Long = 0,
    val cuesSpoken: Int = 0,
    val cuesSuppressed: Int = 0,
    val latencyP50Ms: Float = Float.NaN,
    val latencyP95Ms: Float = Float.NaN,
    val cueClipsReady: Boolean = false,
    val recordingPath: String? = null,
    val log: List<String> = emptyList(),
)

class CoachViewModel(app: Application) : AndroidViewModel(app), PodBleClient.Listener {
    private val engine = CoachEngine(nowUs = ::nowUs)
    private val decoder = PodStreamDecoder()
    private val sync = ClockSync()
    private val podLock = Any()
    private val cuePlayer = CuePlayer(app)
    private val recorder = SessionRecorder(app)
    private val ble = PodBleClient(app, this)
    private val latency = RollingStats(200)

    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    val poseEstimator = PoseEstimator(app, ::onPose, ::onPoseStatus)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val _pose = MutableStateFlow<PoseFrame?>(null)
    val pose: StateFlow<PoseFrame?> = _pose

    private var simJob: Job? = null
    private var lastFrameUs = 0L
    private var fps = 0f
    private var inferenceMs = 0f
    private var lastUiPushUs = 0L
    private var rateWindowStartUs = 0L
    private var rateWindowSamples = 0L
    private var sampleRateHz = 0f

    // ---- Vision -------------------------------------------------------------------------

    private fun onPose(frame: PoseFrame, inferMs: Float) {
        if (lastFrameUs != 0L && frame.tUs > lastFrameUs) {
            val instant = 1e6f / (frame.tUs - lastFrameUs)
            fps = if (fps == 0f) instant else fps + 0.1f * (instant - fps)
        }
        lastFrameUs = frame.tUs
        inferenceMs = if (inferenceMs == 0f) inferMs else inferenceMs + 0.1f * (inferMs - inferenceMs)

        val events = engine.onPoseFrame(frame)
        recorder.pose(frame)
        _pose.value = frame
        handle(events)
        pushUi(force = events.isNotEmpty())
    }

    private fun onPoseStatus(delegate: String, error: String?) {
        _ui.update { it.copy(delegate = delegate, poseError = error) }
    }

    fun setCameraClockSynced(synced: Boolean) {
        poseEstimator.cameraClockIsRealtime = synced
        _ui.update { it.copy(cameraClockSynced = synced) }
    }

    fun toggleModel() {
        val next = if (poseEstimator.model == PoseModel.FULL) PoseModel.LITE else PoseModel.FULL
        poseEstimator.model = next
        fps = 0f; inferenceMs = 0f
        _ui.update { it.copy(model = next) }
    }

    // ---- Pod ----------------------------------------------------------------------------

    fun connectPod() {
        stopSimulation()
        resetPodStream()
        ble.connect()
    }

    fun disconnectPod() {
        stopSimulation()
        ble.disconnect()
    }

    override fun onPodState(state: PodState, message: String?) {
        if (state == PodState.CONNECTING) resetPodStream()
        _ui.update { it.copy(podState = state, podMessage = message) }
    }

    override fun onImuPacket(bytes: ByteArray, rxNs: Long) {
        val rxUs = rxNs / 1000
        val events: List<CoachEvent>
        synchronized(podLock) {
            val packet = decoder.decode(bytes) ?: return
            sync.addOneWay(packet.lastSamplePodUs, rxUs)
            val onPhoneClock = packet.samples.map { it.copy(tUs = sync.podToPhoneUs(it.tUs)) }
            recorder.imu(packet.samples, onPhoneClock)
            events = engine.onImuSamples(onPhoneClock)

            if (rateWindowStartUs == 0L) rateWindowStartUs = rxUs
            rateWindowSamples += packet.samples.size
            if (rxUs - rateWindowStartUs >= 1_000_000) {
                sampleRateHz = rateWindowSamples * 1e6f / (rxUs - rateWindowStartUs)
                rateWindowStartUs = rxUs; rateWindowSamples = 0
            }
        }
        if (events.isNotEmpty()) {
            handle(events)
            pushUi(force = true)
        }
    }

    override fun onSyncResponse(bytes: ByteArray, rxNs: Long) {
        val (t1, t2, t3) = PodPacketCodec.decodeSyncResponse(bytes) ?: return
        synchronized(podLock) {
            sync.addTwoWay(t1, decoder.unwrapPodTime(t2), decoder.unwrapPodTime(t3), rxNs / 1000)
        }
    }

    private fun resetPodStream() {
        synchronized(podLock) {
            decoder.reset(); sync.reset()
            rateWindowStartUs = 0; rateWindowSamples = 0; sampleRateHz = 0f
        }
    }

    /**
     * Feeds the pipeline with packets from [SimulatedPod] in real time: two controlled squats and
     * one dive-bomb, repeating. It exercises decoding, loss counting and clock sync on the phone,
     * but it is NOT tied to what the camera sees, so its tempo cues are only vetoed or confirmed
     * by coincidence. For bench testing without hardware.
     */
    fun toggleSimulation() {
        if (simJob != null) {
            stopSimulation()
            _ui.update { it.copy(podState = PodState.IDLE, podMessage = null) }
            return
        }
        ble.disconnect()
        resetPodStream()
        _ui.update { it.copy(podState = PodState.SIMULATED, podMessage = "synthetic data, not a real pod") }
        simJob = viewModelScope.launch(Dispatchers.Default) {
            val pod = SimulatedPod(
                motions = listOf(SquatMotion(), SquatMotion(), SquatMotion(eccentricS = 0.6, concentricS = 1.0)),
                startPhoneUs = nowUs(),
            )
            while (isActive) {
                val packet = pod.nextPacket()
                val wait = packet.readyPhoneUs - nowUs()
                if (wait > 1000) delay(wait / 1000)
                onImuPacket(packet.bytes, nowUs() * 1000)
            }
        }
    }

    private fun stopSimulation() {
        simJob?.cancel()
        simJob = null
    }

    // ---- Session ------------------------------------------------------------------------

    fun toggleRecording() {
        if (recorder.isRecording) {
            recorder.stop()
            _ui.update { it.copy(recordingPath = null) }
        } else {
            val dir = recorder.start()
            _ui.update { it.copy(recordingPath = dir?.path, log = (it.log + "Recording to ${dir?.name}").takeLast(LOG_LINES)) }
        }
    }

    fun resetSession() {
        engine.reset()
        latency.clear()
        _ui.update { it.copy(reps = 0, lastRep = "", lastCue = "", lastDescent = "", log = emptyList(), latencyP50Ms = Float.NaN, latencyP95Ms = Float.NaN) }
    }

    private fun handle(events: List<CoachEvent>) {
        for (e in events) {
            when (e) {
                is CoachEvent.CueIssued -> {
                    val playedUs = cuePlayer.play(e.cue)
                    val ms = (playedUs - e.fault.tOnsetUs) / 1000f
                    latency.add(ms.toDouble())
                    val line = "CUE \"${e.cue.text}\" %.0f ms after onset [${e.fault.channel}] ${e.fault.detail}".format(ms)
                    recorder.event(playedUs, "cue", line)
                    _ui.update { it.copy(lastCue = e.cue.text, lastCueAtUs = playedUs, log = (it.log + line).takeLast(LOG_LINES)) }
                }

                is CoachEvent.RepCompleted -> {
                    val line = "Rep ${e.index}: down %.1f s, up %.1f s, depth %.2f".format(e.eccentricUs / 1e6, e.concentricUs / 1e6, e.depth)
                    recorder.event(e.tUs, "rep", line)
                    _ui.update { it.copy(lastRep = line, log = (it.log + line).takeLast(LOG_LINES)) }
                }

                is CoachEvent.FaultDetected -> recorder.event(e.tConfirmUs, "fault", "${e.fault} ${e.channel} onset=${e.tOnsetUs} ${e.detail}")
                is CoachEvent.CueSuppressed -> recorder.event(e.fault.tConfirmUs, "cue_suppressed", "${e.fault.fault}: ${e.reason}")
                is CoachEvent.FaultVetoed -> {
                    recorder.event(e.fault.tConfirmUs, "fault_vetoed", "${e.fault.fault} ${e.fault.detail}")
                    _ui.update { it.copy(log = (it.log + "Pod fault ignored: no rep in view").takeLast(LOG_LINES)) }
                }

                is CoachEvent.RepStarted -> recorder.event(e.tUs, "rep_start", "")
                is CoachEvent.BottomReached -> recorder.event(e.tUs, "bottom", "depth=${e.depth} eccentric_us=${e.eccentricUs}")
            }
        }
    }

    private fun pushUi(force: Boolean) {
        val now = nowUs()
        if (!force && now - lastUiPushUs < 100_000) return
        lastUiPushUs = now
        val r = engine.lastResult
        val d = engine.lastDescent
        _ui.update {
            it.copy(
                fps = fps, inferenceMs = inferenceMs,
                phase = engine.phase, reps = engine.repCount,
                hipHeight = r?.relativeHipHeight ?: Float.NaN, kneeAngle = r?.kneeAngleDeg ?: Float.NaN,
                valgus = r?.valgusDeviation ?: Float.NaN, frontalView = r?.frontalView ?: false,
                sampleRateHz = sampleRateHz,
                packetsReceived = decoder.packetsReceived, packetsLost = decoder.packetsLost,
                lossPercent = (decoder.lossRatio * 100).toFloat(),
                syncLocked = sync.isLocked, skewPpm = sync.skewPpm.toFloat(), transportBiasMs = sync.transportBiasUs / 1000f,
                imuVelocity = engine.imuVelocity,
                lastDescent = if (d == null) "" else "%.2f s, peak %.2f m/s, %.2f m".format(d.durationUs / 1e6, d.peakSpeed, d.travelM),
                cuesSpoken = engine.cuesSpoken, cuesSuppressed = engine.cuesSuppressed,
                latencyP50Ms = latency.percentile(50.0).toFloat(), latencyP95Ms = latency.percentile(95.0).toFloat(),
                cueClipsReady = cuePlayer.clipsReady,
            )
        }
    }

    override fun onCleared() {
        stopSimulation()
        ble.disconnect()
        recorder.stop()
        cuePlayer.release()
        analysisExecutor.execute { poseEstimator.release() }
        analysisExecutor.shutdown()
    }

    private companion object {
        const val LOG_LINES = 5
    }
}
