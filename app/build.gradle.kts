import java.util.Properties

/**
 * Whether the app serves generated weather instead of calling Open-Meteo.
 *
 * A per-developer choice, so it is read from the untracked local.properties rather than kept
 * in a source file where flipping it shows up as a diff and eventually gets committed. Set
 * `useMockData=true` there, or pass `-PuseMockData=true` for a single build.
 */
val useMockData: Boolean = run {
    val fromCommandLine = providers.gradleProperty("useMockData").orNull
    val fromLocalProperties = Properties().apply {
        rootProject.file("local.properties")
            .takeIf { it.exists() }
            ?.reader()
            ?.use { load(it) }
    }.getProperty("useMockData")

    (fromCommandLine ?: fromLocalProperties ?: "false").toBoolean()
}

/**
 * Upload-key credentials, kept in an untracked keystore.properties at the repo root:
 *
 *     storeFile=/home/you/keys/migrainewatch-upload.jks
 *     storePassword=…
 *     keyAlias=upload
 *     keyPassword=…
 *
 * Empty when the file is absent, which is what lets a fresh clone still build a release.
 */
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties")
        .takeIf { it.exists() }
        ?.reader()
        ?.use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.radami.migrainewatch"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.radami.migrainewatch"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "com.radami.migrainewatch.HiltTestRunner"
    }

    signingConfigs {
        // Declared only when the credentials are present. Reading them unconditionally would
        // break `assembleRelease` for anyone without the file — CI, a fresh clone — with an
        // error about a missing keystore rather than anything to do with their change.
        if (!keystoreProperties.isEmpty) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "USE_MOCK_DATA", useMockData.toString())
        }

        release {
            // Null without keystore.properties, which yields an unsigned bundle rather than a
            // failed build. Play rejects it at upload, so it cannot be mistaken for shippable.
            signingConfig = signingConfigs.findByName("release")

            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Hard-coded rather than read from the property: a release must never serve
            // generated weather, whatever a local.properties happens to say.
            buildConfigField("boolean", "USE_MOCK_DATA", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        // MigrationTestHelper reads the exported schemas off the device as assets, so the
        // directory Room writes them to has to be packaged into the instrumented test APK.
        getByName("androidTest").assets.srcDirs(files("$projectDir/schemas"))
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Plain JVM tests exercise code that calls android.util.Log; without this
            // the stubbed android.jar throws instead of returning a default value.
            isReturnDefaultValues = true
        }
    }
}

ksp {
    // Room writes one JSON file per schema version here, and they are committed. Without the
    // previous version's file there is nothing for a migration to start from and nothing for
    // a migration test to verify against, so dropping these is what forces a destructive wipe.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // WorkManager
    implementation(libs.workmanager.runtime)
    testImplementation(libs.workmanager.testing)
    androidTestImplementation(libs.workmanager.testing)

    // Networking
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Location
    implementation(libs.play.services.location)

    // Charts
    implementation(libs.vico.compose.m3)
    implementation(libs.vico.core)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.arch.core.testing)
}
