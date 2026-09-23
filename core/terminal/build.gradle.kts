import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/*
 * The terminal, independent of what is on the other end of it.
 *
 * Split out of :feature:ssh when the serial console arrived. Both need the same
 * three things - the libvterm-backed screen, the invisible input field that
 * catches the soft keyboard, and the extra-key bar - and the input handling in
 * particular was hard-won: a second copy would drift, and the next keyboard fix
 * would land in only one of them.
 */
android {
    namespace = "de.nettoolbox.core.terminal"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // `api`: VtermKey constants are part of this module's public callbacks,
    // so every consumer needs them on its compile classpath anyway.
    api(project(":native:vterm"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
