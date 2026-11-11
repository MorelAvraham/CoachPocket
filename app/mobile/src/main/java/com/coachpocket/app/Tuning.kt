package com.coachpocket.app

import android.content.Context
import com.coachpocket.core.cue.CueConfig
import com.coachpocket.core.engine.ChannelMode
import com.coachpocket.core.engine.EngineConfig
import com.coachpocket.core.imu.TempoConfig
import com.coachpocket.core.squat.SquatConfig

/**
 * The detection thresholds worth adjusting in the gym without rebuilding the app. Defaults equal
 * the defaults in `:core`. Whatever is set here is written into every recorded session's metadata,
 * so a result can always be traced to the thresholds that produced it.
 */
data class Tuning(
    val valgusThreshold: Float = SquatConfig().valgusOnThreshold,
    val minEccentricS: Float = TempoConfig().minEccentricUs / 1e6f,
    val maxDescentSpeed: Float = TempoConfig().maxDescentSpeed,
    val cueCooldownS: Float = CueConfig().perFaultCooldownUs / 1e6f,
    val mode: ChannelMode = ChannelMode.FUSED,
) {
    fun toEngineConfig(): EngineConfig {
        val minEccentricUs = (minEccentricS * 1e6f).toLong()
        return EngineConfig(
            mode = mode,
            squat = SquatConfig(valgusOnThreshold = valgusThreshold, minEccentricUs = minEccentricUs),
            tempo = TempoConfig(maxDescentSpeed = maxDescentSpeed, minEccentricUs = minEccentricUs),
            cue = CueConfig(perFaultCooldownUs = (cueCooldownS * 1e6f).toLong()),
        )
    }

    fun toMeta(): Map<String, String> = mapOf(
        "tuning.valgus_threshold" to valgusThreshold.toString(),
        "tuning.min_eccentric_s" to minEccentricS.toString(),
        "tuning.max_descent_speed" to maxDescentSpeed.toString(),
        "tuning.cue_cooldown_s" to cueCooldownS.toString(),
        "tuning.mode" to mode.name.lowercase(),
    )
}

class TuningStore(context: Context) {
    private val prefs = context.getSharedPreferences("coachpocket", Context.MODE_PRIVATE)

    fun load(): Tuning {
        val d = Tuning()
        return Tuning(
            valgusThreshold = prefs.getFloat("valgus", d.valgusThreshold),
            minEccentricS = prefs.getFloat("min_eccentric", d.minEccentricS),
            maxDescentSpeed = prefs.getFloat("max_speed", d.maxDescentSpeed),
            cueCooldownS = prefs.getFloat("cooldown", d.cueCooldownS),
            mode = ChannelMode.entries.getOrElse(prefs.getInt("mode", d.mode.ordinal)) { d.mode },
        )
    }

    fun save(t: Tuning) {
        prefs.edit()
            .putFloat("valgus", t.valgusThreshold).putFloat("min_eccentric", t.minEccentricS)
            .putFloat("max_speed", t.maxDescentSpeed).putFloat("cooldown", t.cueCooldownS)
            .putInt("mode", t.mode.ordinal)
            .apply()
    }

    var lastSubject: String
        get() = prefs.getString("subject", "") ?: ""
        set(value) = prefs.edit().putString("subject", value).apply()
}
