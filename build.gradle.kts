// Root build file: only declares plugins so that every module can apply them
// without re-resolving the classpath. No `subprojects {}` / `allprojects {}` block
// on purpose - it breaks Gradle's configuration cache and project isolation.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    // Baseline profiles. Both belong here for the same reason as the rest: a
    // subproject that requests a version for a plugin already on the classpath
    // fails with "compatibility cannot be checked".
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
}
