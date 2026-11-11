import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.coachpocket.tools.MainKt")
    applicationName = "coachpocket"
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}

// `./gradlew :tools:run --args="eval path/to/sessions"` resolves paths from where it was called.
tasks.named<JavaExec>("run") {
    workingDir = File(System.getProperty("user.dir"))
}
