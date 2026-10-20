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
            // Main's app is only used for reads (app-only counts and balance), so its posting tokens are not built in.
            "X_MAIN_CONSUMER_KEY", "X_MAIN_CONSUMER_SECRET",
            // The second media account (RW, Reality World). Blank until all of these are added; the app then
            // keeps it disabled.
            "X_RW_CONSUMER_KEY", "X_RW_CONSUMER_SECRET",
            "X_RW_ACCESS_TOKEN", "X_RW_ACCESS_SECRET", "X_RW_HANDLE",
            // The LLM layer (captions, analysis). LLM_PROVIDER is "xai" (default) or "openai"; the other one is the
            // fallback when it has a key. Model names are optional: blank means the defaults in LlmConfig.
            "LLM_PROVIDER", "XAI_API_KEY", "OPENAI_API_KEY",
            "XAI_MODEL_ANALYSIS", "XAI_MODEL_CAPTION", "XAI_MODEL_HIGH",
            "OPENAI_MODEL_ANALYSIS", "OPENAI_MODEL_CAPTION", "OPENAI_MODEL_HIGH",
            // The Unfollowers tracker (a Cloudflare Worker): its address and the token the app reads it with.
            "UNFOLLOW_URL", "UNFOLLOW_TOKEN",
            // The website the Boss Meter and Tagline Counter results are published to: the bearer key it accepts.
            "WEBSITE_PUBLISH_KEY",
            // Boss Meter's Instagram side: the igscraper server's address and its X-API-Key.
            "IG_BASE_URL", "IG_API_KEY",
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
