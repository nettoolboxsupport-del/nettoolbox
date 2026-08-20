package de.nettoolbox.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test

/**
 * Records which code paths ART should precompile at install time.
 *
 * The profile is not written by hand: this test drives the real app on a real
 * device, and the recorded paths become `app/src/main/baseline-prof.txt`.
 *
 * What it covers is a deliberate choice. Only startup and the first screen are
 * exercised, because those are what every user hits and what the JIT is slowest
 * at. Measuring rarely used screens would grow the profile without shortening
 * the path anyone actually walks.
 *
 * Deliberately NOT included: anything that needs a permission, a network peer or
 * a running server. A generator run has to be reproducible on any device without
 * setup, and a dialog waiting for a tap would hang it.
 */
class StartupBaselineProfile {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE_NAME,
        // Several iterations because the first run is polluted by installation
        // and first-time setup; the recorder keeps what recurs.
        maxIterations = 8,
        stableIterations = 3,
    ) {
        pressHome()
        startActivityAndWait()

        // Let the dashboard settle. The tiles are the app's landing surface and
        // the first Compose layout pass is the expensive one.
        device.waitForIdle()
    }

    private companion object {
        /**
         * The release application id, without the debug suffix.
         *
         * A profile is generated against the non-debuggable variant, because
         * that is the code that ships. Profiling `.debug` would record class
         * names that do not exist in the released build.
         */
        const val PACKAGE_NAME = "de.nettoolbox.app"
    }
}
