package de.nettoolbox.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt's application entry point. Deliberately empty: anything that needs to run
 * at startup gets injected and initialised lazily, so cold start stays cheap.
 */
@HiltAndroidApp
class NetToolboxApplication : Application()
