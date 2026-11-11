import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.coachpocket.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.coachpocket.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // MediaPipe ships native code for four ABIs; every phone we target is 64-bit ARM.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        noCompress += "task"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    implementation(libs.mediapipe.tasks.vision)
}

// The pose models are binaries published by Google; fetch them instead of committing them.
val poseModels = mapOf(
    "pose_landmarker_lite.task" to
        "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task",
    "pose_landmarker_full.task" to
        "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task",
)

val downloadPoseModels by tasks.registering {
    val assetsDir = layout.projectDirectory.dir("src/main/assets")
    val models = poseModels
    outputs.files(models.keys.map { assetsDir.file(it) })
    doLast {
        val dir = assetsDir.asFile.apply { mkdirs() }
        for ((name, url) in models) {
            val target = dir.resolve(name)
            if (target.exists() && target.length() > 1_000_000) continue
            logger.lifecycle("Downloading $name")
            val tmp = dir.resolve("$name.part")
            URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.renameTo(target)) { "could not move $tmp" }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(downloadPoseModels)
}
