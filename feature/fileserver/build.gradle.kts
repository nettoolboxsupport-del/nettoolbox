import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "de.nettoolbox.feature.fileserver"
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

    packaging {
        resources {
            // Apache MINA SSHD and FtpServer ship the usual Maven metadata.
            // None of it is read at runtime, and duplicate LICENSE entries from
            // several jars would otherwise fail the merge outright.
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
    implementation(project(":core:permissions"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // --- Server protocol stacks ---------------------------------------------
    // sshd-scp is not redundant next to sshd-sftp: they are two different wire
    // protocols. Modern OpenSSH clients use SFTP even when the command is
    // "scp", but Cisco IOS and comparable network gear still speak the
    // original SCP protocol, and that gear is the point of this feature.
    implementation(libs.sshd.core)
    implementation(libs.sshd.sftp)
    implementation(libs.sshd.scp)
    implementation(libs.ftpserver.core)
    implementation(libs.slf4j.api)

    // SSHD reaches for Bouncy Castle for key formats Android's JCE does not
    // provide. The provider is already registered by :feature:ssh; this is the
    // same artifact, resolved to one copy.
    implementation(libs.bouncycastle.provider)

    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
