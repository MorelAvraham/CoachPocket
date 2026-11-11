package com.coachpocket.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coachpocket.app.CoachViewModel
import com.coachpocket.app.UiState
import com.coachpocket.app.ble.PodState
import com.coachpocket.app.nowUs
import com.coachpocket.core.pose.PoseFrame
import com.coachpocket.core.pose.PoseIndex
import com.coachpocket.core.squat.SquatPhase
import com.coachpocket.core.util.fmt
import kotlinx.coroutines.delay

private val Good = Color(0xFF4ADE80)
private val Warn = Color(0xFFFBBF24)
private val Bad = Color(0xFFF87171)
private val Panel = Color(0xB3000000)

@Composable
fun CoachScreen(onOpenEmulator: () -> Unit, viewModel: CoachViewModel = viewModel()) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val pose by viewModel.pose.collectAsStateWithLifecycle()
    var frontCamera by rememberSaveable { mutableStateOf(false) }
    var showRecordDialog by remember { mutableStateOf(false) }
    var showTuningDialog by remember { mutableStateOf(false) }
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraGranted = it }
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) viewModel.connectPod()
    }
    LaunchedEffect(Unit) { if (!cameraGranted) cameraPermission.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraGranted) {
            CameraPreview(viewModel, frontCamera, Modifier.fillMaxSize())
            PoseOverlay(pose, ui, mirror = frontCamera, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CoachPocket needs the camera to see your lift. Frames are analysed on this phone and never stored or sent.", color = Color.White)
                Button(onClick = { cameraPermission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Allow camera")
                }
            }
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Hud(ui)
                CueBanner(ui)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RepPanel(ui)
                Controls(
                    ui = ui,
                    onPod = {
                        if (ui.podState == PodState.IDLE || ui.podState == PodState.SIMULATED) {
                            val needed = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                            if (needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                                viewModel.connectPod()
                            } else {
                                bluetoothPermission.launch(needed)
                            }
                        } else {
                            viewModel.disconnectPod()
                        }
                    },
                    onSim = viewModel::toggleSimulation,
                    onRecord = { if (ui.recordingPath != null) viewModel.stopRecording() else showRecordDialog = true },
                    onTune = { showTuningDialog = true },
                    onEmulator = {
                        viewModel.disconnectPod()
                        onOpenEmulator()
                    },
                    onFlip = { frontCamera = !frontCamera },
                    onModel = viewModel::toggleModel,
                    onReset = viewModel::resetSession,
                )
            }
        }
    }

    if (showRecordDialog) {
        RecordDialog(
            initialSubject = ui.lastSubject,
            onStart = { subject, condition, reps ->
                showRecordDialog = false
                viewModel.startRecording(subject, condition, reps)
            },
            onDismiss = { showRecordDialog = false },
        )
    }
    if (showTuningDialog) {
        TuningDialog(
            current = ui.tuning,
            onApply = {
                showTuningDialog = false
                viewModel.setTuning(it)
            },
            onDismiss = { showTuningDialog = false },
        )
    }
}

@Composable
private fun Hud(ui: UiState) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp)).padding(8.dp)) {
        val fpsColor = if (ui.fps >= 28f) Good else if (ui.fps >= 20f) Warn else Bad
        Mono(fmt("POSE  %.0f fps  %.0f ms  %s/%s", ui.fps, ui.inferenceMs, ui.model.label, ui.delegate), fpsColor)
        ui.poseError?.let { Mono("      $it", Bad) }
        if (!ui.cameraClockSynced) Mono("      camera clock is not the boot clock: sync degraded", Warn)
        if (ui.phase != SquatPhase.NOT_TRACKED && !ui.frontalView) Mono("      side view: knee valgus needs the camera in front", Warn)

        val podColor = when (ui.podState) {
            PodState.STREAMING -> Good
            PodState.SIMULATED -> Warn
            else -> Color.LightGray
        }
        Mono("POD   ${ui.podState}${ui.podMessage?.let { " ($it)" } ?: ""}", podColor)
        if (ui.podState == PodState.STREAMING || ui.podState == PodState.SIMULATED) {
            Mono(
                fmt("      %.0f Hz  loss %.2f%% (%d/%d pkts)", 
                    ui.sampleRateHz, ui.lossPercent, ui.packetsLost, ui.packetsReceived + ui.packetsLost,
                ),
                if (ui.lossPercent < 1f) Good else Warn,
            )
            if (ui.packetsMalformed > 0) {
                Mono("      ${ui.packetsMalformed} unreadable packets: wrong format, or cut short by a small MTU", Bad)
            }
            Mono(
                fmt("SYNC  %s  skew %+.0f ppm  bias %.1f ms", if (ui.syncLocked) "locked" else "acquiring", ui.skewPpm, ui.transportBiasMs),
                if (ui.syncLocked) Good else Warn,
            )
            Mono(fmt("      v %+.2f m/s  last descent %s", ui.imuVelocity, ui.lastDescent.ifEmpty { "-" }), Color.LightGray)
        }
        val p95 = ui.latencyP95Ms
        Mono(
            fmt("CUES  %d spoken, %d held back  onset-to-play p50 %s p95 %s", 
                ui.cuesSpoken, ui.cuesSuppressed, ms(ui.latencyP50Ms), ms(p95),
            ),
            if (p95.isNaN()) Color.LightGray else if (p95 <= 300f) Good else if (p95 <= 500f) Warn else Bad,
        )
        if (!ui.cueClipsReady) Mono("      preparing voice clips (live TTS until ready, slower)", Warn)
        if (ui.tuning.mode != com.coachpocket.core.engine.ChannelMode.FUSED) {
            Mono("MODE  ${ui.tuning.mode.name.lowercase()} (ablation: one channel is switched off)", Warn)
        }
        ui.recordingPath?.let { Mono("REC   ${it.substringAfterLast('/')}", Bad) }
    }
}

private fun ms(v: Float) = if (v.isNaN()) "-" else fmt("%.0f ms", v)

@Composable
private fun Mono(text: String, color: Color) {
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
}

/** Shows the cue text for two seconds after it was spoken, for whoever is watching the screen. */
@Composable
private fun CueBanner(ui: UiState) {
    var now by remember { mutableLongStateOf(nowUs()) }
    LaunchedEffect(ui.lastCueAtUs) {
        now = nowUs()
        delay(2000)
        now = nowUs()
    }
    if (ui.lastCueAtUs != 0L && now - ui.lastCueAtUs < 2_000_000) {
        Box(Modifier.fillMaxWidth().background(Bad, RoundedCornerShape(8.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
            Text(ui.lastCue.uppercase(), color = Color.Black, fontSize = 28.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun RepPanel(ui: UiState) {
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${ui.reps}", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.padding(start = 12.dp)) {
            val phase = when (ui.phase) {
                SquatPhase.NOT_TRACKED -> "Step into view: hips, knees and ankles visible"
                SquatPhase.CALIBRATING -> "Stand tall for a moment"
                SquatPhase.TOP -> "Ready"
                SquatPhase.DESCENT -> "Down"
                SquatPhase.ASCENT -> "Up"
            }
            Text("SQUAT  $phase", color = if (ui.phase == SquatPhase.NOT_TRACKED) Warn else Good, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (!ui.hipHeight.isNaN()) {
                Mono(
                    fmt("hip %.2f  knee %s  valgus %s", 
                        ui.hipHeight,
                        if (ui.kneeAngle.isNaN()) "-" else fmt("%.0f deg", ui.kneeAngle),
                        if (ui.valgus.isNaN()) "n/a" else fmt("%+.3f", ui.valgus),
                    ),
                    Color.LightGray,
                )
            }
            for (line in ui.log.takeLast(3)) Mono(line, Color.White)
        }
    }
}

@Composable
private fun Controls(
    ui: UiState,
    onPod: () -> Unit,
    onSim: () -> Unit,
    onRecord: () -> Unit,
    onTune: () -> Unit,
    onEmulator: () -> Unit,
    onFlip: () -> Unit,
    onModel: () -> Unit,
    onReset: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val podLabel = when (ui.podState) {
            PodState.IDLE, PodState.SIMULATED -> "Connect pod"
            PodState.SCANNING -> "Scanning..."
            PodState.CONNECTING -> "Connecting..."
            PodState.STREAMING -> "Disconnect pod"
        }
        Button(onClick = onPod) { Text(podLabel) }
        Button(
            onClick = onRecord,
            colors = if (ui.recordingPath != null) ButtonDefaults.buttonColors(containerColor = Bad) else ButtonDefaults.buttonColors(),
        ) { Text(if (ui.recordingPath != null) "Stop rec" else "Record") }
        FilledTonalButton(onClick = onReset) { Text("Reset") }
        FilledTonalButton(onClick = onTune) { Text("Tune") }
        FilledTonalButton(onClick = onFlip) { Text("Flip") }
        FilledTonalButton(onClick = onModel) { Text("Model: ${ui.model.label}") }
        FilledTonalButton(onClick = onSim) { Text(if (ui.podState == PodState.SIMULATED) "Stop sim" else "Sim pod") }
        FilledTonalButton(onClick = onEmulator) { Text("Be the pod") }
    }
}

/** Skeleton drawn over the letterboxed preview. Legs turn red while a knee is caving in. */
@Composable
private fun PoseOverlay(frame: PoseFrame?, ui: UiState, mirror: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val lm = frame?.image ?: return@Canvas
        if (lm.size < PoseIndex.COUNT) return@Canvas
        // Same geometry as PreviewView.ScaleType.FIT_CENTER.
        val viewAspect = size.width / size.height
        val w: Float
        val h: Float
        if (frame.aspect < viewAspect) {
            h = size.height; w = h * frame.aspect
        } else {
            w = size.width; h = w / frame.aspect
        }
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        fun point(i: Int): Offset {
            val x = if (mirror) 1f - lm[i].x else lm[i].x
            return Offset(left + x * w, top + lm[i].y * h)
        }

        val valgus = !ui.valgus.isNaN() && ui.valgus > ui.tuning.valgusThreshold &&
            (ui.phase == SquatPhase.DESCENT || ui.phase == SquatPhase.ASCENT)
        for ((a, b) in PoseIndex.SKELETON) {
            if (lm[a].visibility < 0.5f || lm[b].visibility < 0.5f) continue
            val leg = a >= PoseIndex.L_HIP && b >= PoseIndex.L_KNEE
            drawLine(if (leg && valgus) Bad else Good, point(a), point(b), strokeWidth = 6f, cap = StrokeCap.Round)
        }
        for (i in PoseIndex.L_SHOULDER until PoseIndex.COUNT) {
            if (lm[i].visibility >= 0.5f) drawCircle(Color.White, 6f, point(i))
        }
    }
}
