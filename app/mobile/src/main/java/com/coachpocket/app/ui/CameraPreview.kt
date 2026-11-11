package com.coachpocket.app.ui

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.coachpocket.app.CoachViewModel

/**
 * Camera preview plus the analysis stream that feeds the pose estimator. Preview and analysis are
 * both 4:3, so normalised keypoints map 1:1 onto the letterboxed preview.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraPreview(viewModel: CoachViewModel, frontCamera: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(frontCamera, lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            if (disposed) return@addListener // left this screen before the camera service answered
            try {
                val cameraProvider = future.get().also { provider = it }
                val fourByThree = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                val preview = Preview.Builder().setResolutionSelector(fourByThree.build()).build()
                preview.surfaceProvider = previewView.surfaceProvider

                val analysisBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        fourByThree.setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        ).build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                val selector = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                // Pin 30 fps where the camera offers it: by default auto-exposure halves the frame
                // rate in a dim gym. The range must be one the camera lists, or the HAL may reject it.
                val fixed30 = Range(30, 30)
                val supportsFixed30 = cameraProvider.availableCameraInfos
                    .filter { selector.filter(listOf(it)).isNotEmpty() }
                    .any { info ->
                        Camera2CameraInfo.from(info)
                            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                            ?.contains(fixed30) == true
                    }
                if (supportsFixed30) {
                    Camera2Interop.Extender(analysisBuilder)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fixed30)
                }
                val analysis = analysisBuilder.build()
                analysis.setAnalyzer(viewModel.analysisExecutor, viewModel.poseEstimator)

                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)

                // Frame timestamps are only comparable with the pod's if the sensor uses the boot clock.
                val source = Camera2CameraInfo.from(camera.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
                viewModel.setCameraClockSynced(source == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)
            } catch (e: Exception) {
                Log.e("CameraPreview", "could not start the camera", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            provider?.unbindAll()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
