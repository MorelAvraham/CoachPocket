package com.coachpocket.app.camera

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.coachpocket.core.pose.Landmark
import com.coachpocket.core.pose.PoseFrame
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.ConcurrentSkipListMap

enum class PoseModel(val asset: String, val label: String) {
    LITE("pose_landmarker_lite.task", "lite"),
    FULL("pose_landmarker_full.task", "full"),
}

/**
 * CameraX analyzer that runs the MediaPipe Pose Landmarker (BlazePose) on every frame and hands
 * out [PoseFrame]s stamped with the frame's capture time.
 *
 * All landmarker calls happen on the analysis executor thread, which the GPU delegate requires.
 */
class PoseEstimator(
    private val context: Context,
    private val onPose: (frame: PoseFrame, inferenceMs: Float) -> Unit,
    private val onStatus: (delegate: String, error: String?) -> Unit,
) : ImageAnalysis.Analyzer {

    /** True when the camera stamps frames on the elapsedRealtime clock (set when the camera is bound). */
    @Volatile
    var cameraClockIsRealtime = false

    @Volatile
    var model = PoseModel.FULL

    private class Pending(val captureUs: Long, val submitNs: Long, val aspect: Float)

    private val pending = ConcurrentSkipListMap<Long, Pending>()
    private var landmarker: PoseLandmarker? = null
    private var loadedModel: PoseModel? = null
    private var lastTimestampMs = 0L
    private var failed = false

    override fun analyze(image: ImageProxy) {
        try {
            if (loadedModel != model) {
                release()
                failed = false
            }
            if (failed) return
            val lm = landmarker ?: create() ?: return

            val nowNs = SystemClock.elapsedRealtimeNanos()
            val captureUs = if (cameraClockIsRealtime) image.imageInfo.timestamp / 1000 else nowNs / 1000
            var timestampMs = captureUs / 1000
            if (timestampMs <= lastTimestampMs) timestampMs = lastTimestampMs + 1 // must strictly increase
            lastTimestampMs = timestampMs

            val rotation = image.imageInfo.rotationDegrees
            val bitmap = image.toBitmap()
            val upright = rotation % 180 == 0
            val aspect = if (upright) bitmap.width.toFloat() / bitmap.height else bitmap.height.toFloat() / bitmap.width
            pending[timestampMs] = Pending(captureUs, nowNs, aspect)
            lm.detectAsync(
                BitmapImageBuilder(bitmap).build(),
                ImageProcessingOptions.builder().setRotationDegrees(rotation).build(),
                timestampMs,
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "pose inference failed", e)
            onStatus("-", e.message ?: e.javaClass.simpleName)
        } finally {
            image.close()
        }
    }

    private fun create(): PoseLandmarker? {
        val wanted = model
        for (delegate in listOf(Delegate.GPU, Delegate.CPU)) {
            try {
                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(wanted.asset).setDelegate(delegate).build())
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(0.5f)
                    .setMinPosePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setResultListener { result, _ -> onResult(result) }
                    .setErrorListener { e -> onStatus("-", e.message) }
                    .build()
                landmarker = PoseLandmarker.createFromOptions(context, options)
                loadedModel = wanted
                onStatus(delegate.name, null)
                return landmarker
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not create pose landmarker on $delegate", e)
            }
        }
        failed = true
        loadedModel = wanted
        onStatus("-", "pose model ${wanted.asset} could not be loaded")
        return null
    }

    private fun onResult(result: PoseLandmarkerResult) {
        val key = result.timestampMs()
        val p = pending.remove(key) ?: return
        pending.headMap(key).clear() // frames the landmarker dropped never report back
        val inferenceMs = (SystemClock.elapsedRealtimeNanos() - p.submitNs) / 1e6f
        val image = result.landmarks().firstOrNull()
            ?.map { Landmark(it.x(), it.y(), it.z(), it.visibility().orElse(0f)) } ?: emptyList()
        val world = result.worldLandmarks().firstOrNull()
            ?.map { Landmark(it.x(), it.y(), it.z(), it.visibility().orElse(0f)) } ?: emptyList()
        onPose(PoseFrame(p.captureUs, image, world, p.aspect), inferenceMs)
    }

    /** Must be called on the analysis executor. */
    fun release() {
        landmarker?.close()
        landmarker = null
        loadedModel = null
        pending.clear()
    }

    private companion object {
        const val TAG = "PoseEstimator"
    }
}
