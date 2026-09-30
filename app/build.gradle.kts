import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(FileInputStream(file))
}

android {
    namespace = "com.clipcut"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.clipcut"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Real* API credentials, read from the gitignored local.properties so they never land in
        // source control. Blank until set, in which case the Real* implementations fail fast with
        // a clear error rather than making a request with empty credentials. NOTE: these end up in
        // BuildConfig constants inside the built APK and can be extracted from it; acceptable for
        // this dev-only, not-distributed build, but revisit (move behind a backend proxy) before
        // ever shipping this to others. Each X account gets its own consumer key/secret -- some
        // setups use one shared X developer app with the other account added as a Collaborator,
        // others use a separate app per account; per-account fields work for either.
        for (key in listOf(
            "X_MEDIA_CONSUMER_KEY", "X_MEDIA_CONSUMER_SECRET",
            "X_MEDIA_ACCESS_TOKEN", "X_MEDIA_ACCESS_SECRET",
            "X_MAIN_CONSUMER_KEY", "X_MAIN_CONSUMER_SECRET",
            "X_MAIN_ACCESS_TOKEN", "X_MAIN_ACCESS_SECRET",
            // The second media account (RW, Reality World). Blank until all of these are added; the app then
            // keeps it disabled. X_RW_CAPTION_LABEL is what its posts are captioned with, e.g. "RW LOGS".
            "X_RW_CONSUMER_KEY", "X_RW_CONSUMER_SECRET",
            "X_RW_ACCESS_TOKEN", "X_RW_ACCESS_SECRET", "X_RW_HANDLE", "X_RW_CAPTION_LABEL",
            "OPENAI_API_KEY",
        )) {
            buildConfigField("String", key, "\"${localProperties.getProperty(key, "")}\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.squareup.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.org.json) // Android's org.json is a stub on the JVM test classpath.
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
