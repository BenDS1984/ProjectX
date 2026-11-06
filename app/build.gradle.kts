import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------
// Faust: zet src/main/faust/voice.dsp om naar C++ voordat de native code bouwt.
// Pad naar faust.exe kan in local.properties staan als  faust.exe=C:\\pad\\faust.exe
// ---------------------------------------------------------------------------
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val faustExe: String = localProps.getProperty("faust.exe") ?: "C:\\Program Files\\Faust\\bin\\faust.exe"
val faustSrc = file("src/main/faust/voice.dsp")
val faustGenDir = layout.buildDirectory.dir("generated/faust")

val generateFaust = tasks.register<Exec>("generateFaust") {
    group = "faust"
    description = "Compileert de Faust-DSP naar C++"
    inputs.file(faustSrc)
    outputs.dir(faustGenDir)
    val outFile = faustGenDir.map { it.file("SynthVoice.h") }
    doFirst { faustGenDir.get().asFile.mkdirs() }
    commandLine(
        faustExe, "-lang", "cpp", "-cn", "SynthVoice", "-single", "-ftz", "2",
        faustSrc.absolutePath, "-o", outFile.get().asFile.absolutePath,
    )
}

val appVersionName = "0.10"

android {
    namespace = "com.projectx.synth"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.projectx.synth"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = appVersionName

        // App-naam met versienummer, bv. "Project X v0.3" (onder het icoon op de tablet).
        resValue("string", "app_name", "Project X v$appVersionName")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DFAUST_GEN_DIR=" + faustGenDir.get().asFile.absolutePath.replace('\\', '/'),
                )
            }
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
        prefab = true
        buildConfig = true   // maakt het versienummer beschikbaar in de app (BuildConfig.VERSION_NAME)
        resValues = true     // nodig voor app_name met versienummer (resValue hierboven)
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// Native build moet wachten op de Faust-code.
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake") ||
        name.startsWith("generateJsonModel")
    ) {
        dependsOn(generateFaust)
    }
}

// Kopieer de debug-APK naar <project>/apk/ met een herkenbare naam.
val copyApk = tasks.register<Copy>("copyApk") {
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("apk"))
    rename { "ProjectX-v$appVersionName.apk" }
}
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(copyApk) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.oboe:oboe:1.11.0")
}
