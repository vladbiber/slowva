import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Public builds contain no maintainer Client ID; users enter their own in the one-time setup.
val bundledSpotifyClientId = providers.gradleProperty("SPOTIFY_CLIENT_ID")
    .orElse(providers.environmentVariable("SPOTIFY_CLIENT_ID"))
    .orNull
    ?.trim()
    ?.takeIf { it.matches(Regex("[A-Za-z0-9]{32}")) }
    .orEmpty()
val localDebugKeystore = file(System.getProperty("user.home") + "/.android/debug.keystore")

android {
    namespace = "com.slowva.player"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.slowva.player"
        minSdk = 26
        targetSdk = 36
        versionCode = 19
        versionName = "3.0.0"
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$bundledSpotifyClientId\"")
        vectorDrawables { useSupportLibrary = true }
        // Last.fm key for genres and creators: local.properties, else the environment; blank hides those parts
        val lastFmKey = rootProject.file("local.properties").takeIf { it.exists() }
            ?.let { f -> Properties().apply { f.inputStream().use(::load) }.getProperty("LASTFM_API_KEY") }
            ?: System.getenv("LASTFM_API_KEY").orEmpty()
        buildConfigField("String", "LASTFM_API_KEY", "\"${lastFmKey.trim().filter(Char::isLetterOrDigit)}\"")
    }

    // one APK per ABI: the librespot binary is 14 MB, no point shipping both
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    // same key as every earlier slowva release, so the update installs over it
    signingConfigs {
        if (localDebugKeystore.isFile) create("localRelease") {
            storeFile = localDebugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("localRelease")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // librespot ships as lib*.so so Android extracts it into nativeLibraryDir,
    // the only directory an app may still exec a binary from since API 29
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/**"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.documentfile)
    implementation(libs.coil.compose)
    implementation(libs.materialkolor.material3)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.hls)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.browser)
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    testImplementation(libs.junit)
    // real org.json for parsing tests; the android.jar copy is a stub
    testImplementation(libs.org.json)
}
