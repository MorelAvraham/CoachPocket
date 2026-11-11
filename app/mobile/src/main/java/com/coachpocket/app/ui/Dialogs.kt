package com.coachpocket.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coachpocket.app.Tuning
import com.coachpocket.core.engine.ChannelMode
import com.coachpocket.core.engine.FaultType
import com.coachpocket.core.util.fmt

/**
 * Asked before every recorded set: who is lifting and what they are about to do on purpose.
 * That answer is the ground-truth label of the set, so it is asked before, not reconstructed after.
 */
@Composable
fun RecordDialog(
    initialSubject: String,
    onStart: (subject: String, condition: Set<FaultType>, expectedReps: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var subject by remember { mutableStateOf(initialSubject) }
    var condition by remember { mutableStateOf(emptySet<FaultType>()) }
    var reps by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record a set") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = subject, onValueChange = { subject = it }, singleLine = true,
                    label = { Text("Lifter ID (e.g. S01, not a name)") }, modifier = Modifier.fillMaxWidth(),
                )
                Text("What will the lifter do in this set?", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = condition.isEmpty(), onClick = { condition = emptySet() }, label = { Text("Clean") })
                }
                for (fault in FaultType.entries) {
                    FilterChip(
                        selected = fault in condition,
                        onClick = { condition = if (fault in condition) condition - fault else condition + fault },
                        label = { Text("Deliberate: ${fault.name.lowercase().replace('_', ' ')}") },
                    )
                }
                OutlinedTextField(
                    value = reps, onValueChange = { v -> reps = v.filter(Char::isDigit).take(2) }, singleLine = true,
                    label = { Text("Planned repetitions (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                Text("Every repetition of the set gets this label. Keypoints and pod data are saved; video is not.", fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = subject.isNotBlank(), onClick = { onStart(subject.trim(), condition, reps.toIntOrNull()) }) {
                Text("Start")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Live threshold adjustment for pilot sessions. Applying restarts the engine (rep count returns to 0). */
@Composable
fun TuningDialog(current: Tuning, onApply: (Tuning) -> Unit, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Detection thresholds") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Channels", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (mode in ChannelMode.entries) {
                        FilterChip(
                            selected = t.mode == mode, onClick = { t = t.copy(mode = mode) },
                            label = { Text(mode.name.lowercase().replace("_only", " only")) },
                        )
                    }
                }
                Setting(fmt("Knee valgus: %.3f of leg length", t.valgusThreshold), t.valgusThreshold, 0.03f..0.20f) { t = t.copy(valgusThreshold = it) }
                Setting(fmt("Shortest allowed descent: %.1f s", t.minEccentricS), t.minEccentricS, 0.4f..3.0f) { t = t.copy(minEccentricS = it) }
                Setting(fmt("Fastest allowed descent (pod): %.2f m/s", t.maxDescentSpeed), t.maxDescentSpeed, 0.3f..1.5f) { t = t.copy(maxDescentSpeed = it) }
                Setting(fmt("Do not repeat a cue within: %.0f s", t.cueCooldownS), t.cueCooldownS, 0f..15f) { t = t.copy(cueCooldownS = it) }
                Text("Values are saved on this phone and written into every recording.", fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onApply(t) }) { Text("Apply") } },
        dismissButton = {
            Row {
                TextButton(onClick = { t = Tuning(mode = t.mode) }) { Text("Defaults") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Setting(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text(label, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
    Slider(value = value, onValueChange = onChange, valueRange = range)
}
