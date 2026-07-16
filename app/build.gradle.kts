import org.gradle.kotlin.dsl.implementation
import java.io.FileInputStream
import java.util.Properties

// 1. Define a cache-safe task class at the top of your script
abstract class IncrementVersionTask : DefaultTask() {
    @get:InputFile
    abstract val propertiesFile: RegularFileProperty

    @TaskAction
    fun increment() {
        val targetFile = propertiesFile.get().asFile
        if (targetFile.exists()) {
            val localProps = Properties()
            targetFile.inputStream().use { localProps.load(it) }

            val currentBuild = localProps.getProperty("VERSION_BUILD", "1").toInt()
            localProps["VERSION_BUILD"] = (currentBuild + 1).toString()

            targetFile.writer().use { writer ->
                localProps.store(writer, "Automated version increment")
            }
        }
    }
}

// 2. Load properties for the Android configuration block
val versionPropsFile = rootProject.file("version.properties")
var initialBuildCode: Int
var initialVersionName: String

if (versionPropsFile.exists()) {
    val versionProps = Properties().apply {
        FileInputStream(versionPropsFile).use { load(it) }
    }
    val major = versionProps.getProperty("VERSION_MAJOR", "1").toInt()
    val minor = versionProps.getProperty("VERSION_MINOR", "0").toInt()
    val patch = versionProps.getProperty("VERSION_PATCH", "0").toInt()
    val build = versionProps.getProperty("VERSION_BUILD", "1").toInt()

    initialBuildCode = (major * 10000) + (minor * 100) + build
    initialVersionName = "$major.$minor.$patch"
} else {
    initialBuildCode = 10001
    initialVersionName = "1.0.0"
}

android {
    defaultConfig {
        versionCode = initialBuildCode
        versionName = initialVersionName
    }
}

// 3. Register the task safely using the new class
val incrementVersion = tasks.register<IncrementVersionTask>("incrementVersion") {
    propertiesFile.set(rootProject.file("version.properties"))
}

// 4. Hook it to your release flows safely
afterEvaluate {
    tasks.findByName("assembleRelease")?.finalizedBy(incrementVersion)
    tasks.findByName("bundleRelease")?.finalizedBy(incrementVersion)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "eu.ydiaeresis.filmasonde"
    compileSdk = 37

    defaultConfig {
        applicationId = "eu.ydiaeresis.filmasonde"
        minSdk = 26
        targetSdk = 37
        //versionCode = 10000
        //versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                )
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.compose.animation.core)
    implementation(libs.androidx.ui.text)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.camera.effects)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.material3)
    implementation(libs.play.services.location)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.io.bytestring)
    implementation(libs.mqttclient)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.encoding)
    implementation(libs.kotlinx.datetime)
    implementation(libs.androidx.camera.video)
}
