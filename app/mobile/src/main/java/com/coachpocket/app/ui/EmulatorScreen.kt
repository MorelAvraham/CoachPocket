package com.coachpocket.app.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coachpocket.app.ble.EmulatorStats
import com.coachpocket.app.ble.PodEmulator
import com.coachpocket.core.util.fmt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class EmulatorViewModel(app: Application) : AndroidViewModel(app) {
    private val _stats = MutableStateFlow(EmulatorStats())
    val stats: StateFlow<EmulatorStats> = _stats
    private val emulator = PodEmulator(app) { _stats.value = it }

    fun start() = emulator.start()

    fun stop() = emulator.stop()

    override fun onCleared() = emulator.stop()
}

/** The "this phone is the pod" mode: see [PodEmulator]. */
@Composable
fun EmulatorScreen(onBack: () -> Unit, viewModel: EmulatorViewModel = viewModel()) {
    val context = LocalContext.current
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val needed = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) viewModel.start()
    }
    val leave = {
        viewModel.stop()
        onBack()
    }
    BackHandler(onBack = leave)

    Column(
        Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Pod emulator", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "This phone pretends to be the wrist pod: it streams its own accelerometer and gyroscope over " +
                "Bluetooth in the pod's packet format. Strap it to the wrist, then press \"Connect pod\" in " +
                "CoachPocket on the phone that is on the tripod.",
            color = Color.LightGray, fontSize = 14.sp,
        )
        Text(
            "For developing the link and the sync before the real pod exists. A phone IMU gives about " +
                "200 to 500 Hz, not 1 kHz, so do not use it for the R1 throughput test.",
            color = Color(0xFFFBBF24), fontSize = 13.sp,
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Line("state", if (stats.running) "running" else "stopped")
            Line("connected to", stats.central ?: "-")
            Line("subscribed", if (stats.subscribed) "yes" else "no")
            Line("IMU rate", if (stats.running) fmt("%.0f Hz", stats.sampleRateHz) else "-")
            Line("packets sent", stats.packetsSent.toString())
            Line("packets dropped", stats.packetsDropped.toString())
        }
        stats.message?.let { Text(it, color = Color.White, fontSize = 14.sp) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (stats.running) {
                Button(onClick = viewModel::stop) { Text("Stop") }
            } else {
                Button(onClick = {
                    if (needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                        viewModel.start()
                    } else {
                        permission.launch(needed)
                    }
                }) { Text("Start") }
            }
            FilledTonalButton(onClick = leave) { Text("Back to coach") }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Text(fmt("%-16s %s", label, value), color = Color(0xFF4ADE80), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
}
