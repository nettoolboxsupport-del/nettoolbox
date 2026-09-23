import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/**
 * Release signing credentials, kept out of the repository.
 *
 * `keystore.properties` is listed in .gitignore alongside *.jks and *.keystore.
 * A signing key in version control is a key that has to be considered
 * compromised, and rotating an Android upload key is a support ticket, not a
 * commit.
 *
 * Expected contents:
 *   storeFile=C:/path/to/nettoolbox-release.jks
 *   storePassword=...
 *   keyAlias=nettoolbox
 *   keyPassword=...
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Consumer side of the baseline profile: makes the generated
    // app/src/main/baseline-prof.txt part of the release build so ART can
    // precompile those paths at install time.
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "de.nettoolbox.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "de.nettoolbox.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // versionCode must increase with every Play upload and can never be
        // reused, not even for a rejected release. versionName is what users
        // see; the phase suffix was a development marker and has no place in a
        // published build.
        versionCode = 3
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Phase 5 ships prebuilt native libraries; keep the ABI set explicit from
        // the start so an accidental x86 build never lands in a release APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
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
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Falls back to the debug key when no keystore.properties is
            // present, so that a fresh clone can still build and sideload.
            // The fallback is announced rather than silent: a "release" build
            // signed with the debug key is fine for testing and must never be
            // published, and that distinction is invisible in the resulting
            // file.
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "NetToolbox: no keystore.properties found - the release build " +
                        "is signed with the DEBUG key. Usable for sideloading, not " +
                        "for distribution.",
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/versions/**",
                "/META-INF/OSGI-INF/**",
                "/META-INF/INDEX.LIST",
                "/META-INF/DEPENDENCIES",
                "META-INF/versions/**",
                "META-INF/OSGI-INF/**",
            )
        }
        // iperf3 (phase 5) is launched from nativeLibraryDir, so the .so files
        // must be extracted at install time instead of loaded from the APK.
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    baselineProfile(project(":baselineprofile"))

    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:permissions"))
    implementation(project(":core:ui"))

    implementation(project(":feature:cellular"))
    implementation(project(":feature:fileserver"))
    implementation(project(":feature:iperf"))
    implementation(project(":feature:map"))
    implementation(project(":feature:serial"))
    implementation(project(":feature:ssh"))
    implementation(project(":feature:tools"))
    implementation(project(":feature:wifi"))
    implementation(project(":native:vterm"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.leakcanary)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
