plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Public builds contain no maintainer Client ID. Users enter their own ID in
// the one-time setup. Maintainers with an approved Spotify app may explicitly
// opt in through -PSPOTIFY_CLIENT_ID=... or the matching environment variable.
val bundledSpotifyClientId = providers.gradleProperty("SPOTIFY_CLIENT_ID")
    .orElse(providers.environmentVariable("SPOTIFY_CLIENT_ID"))
    .orNull
    ?.trim()
    ?.takeIf { it.matches(Regex("[A-Za-z0-9]{32}")) }
    .orEmpty()
val localDebugKeystore = file(System.getProperty("user.home") + "/.android/debug.keystore")

android {
    namespace = "com.slowva.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.slowva.player"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "2.0.5"
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$bundledSpotifyClientId\"")
        vectorDrawables { useSupportLibrary = true }
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
            if (localDebugKeystore.isFile) {
                signingConfig = signingConfigs.getByName("localRelease")
            }
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
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // librespot ships as lib*.so so Android extracts it into nativeLibraryDir,
    // the only directory an app may still exec a binary from since API 29.
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.media:media:1.7.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
