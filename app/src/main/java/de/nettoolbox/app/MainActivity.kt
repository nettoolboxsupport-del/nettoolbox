package de.nettoolbox.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import de.nettoolbox.app.ui.AppLocaleStore
import de.nettoolbox.app.ui.NetToolboxApp
import de.nettoolbox.app.ui.onboarding.OnboardingScreen
import de.nettoolbox.core.permissions.PermissionCoordinator
import de.nettoolbox.core.datastore.model.ThemePreference
import de.nettoolbox.core.ui.theme.NetToolboxTheme
import de.nettoolbox.core.ui.theme.NetToolboxThemeMode

/**
 * Single-activity host. The whole app lives in one activity so that long-running
 * measurements survive configuration changes without any activity juggling.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /**
     * Injected here rather than created in the composable: the coordinator
     * keeps an "already asked" history in SharedPreferences, and a second
     * instance would forget what the first one asked.
     */
    @Inject lateinit var permissionCoordinator: PermissionCoordinator

    /**
     * Applies the language before anything else exists.
     *
     * Doing it here rather than by swapping `LocalContext` inside Compose is
     * deliberate: a context from `createConfigurationContext` is not a wrapper
     * around the activity, and Hilt walks the wrapper chain to find one. Handing
     * that context to the composition breaks every `hiltViewModel()` call.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleStore.applyLocale(newBase, AppLocaleStore.readTag(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        splashScreen.setKeepOnScreenCondition {
            viewModel.uiState.value is MainUiState.Loading
        }

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val settings = (uiState as? MainUiState.Ready)?.settings

            val language = settings?.language
            LaunchedEffect(language) {
                // Only when it actually differs, otherwise this would recreate the
                // activity on every start.
                if (language != null && !AppLocaleStore.matches(this@MainActivity, language)) {
                    AppLocaleStore.writeTag(this@MainActivity, language.tag)
                    recreate()
                }
            }

            NetToolboxTheme(
                themeMode = settings?.theme.toThemeMode(),
                dynamicColor = settings?.dynamicColor ?: true,
            ) {
                // The introduction replaces the app rather than sitting on top
                // of it. It explains what happens with location and telephony
                // data, and that answer belongs before the first permission
                // dialog, not behind a dismissable sheet over a live dashboard.
                if (settings != null && !settings.onboardingCompleted) {
                    OnboardingScreen(
                        coordinator = permissionCoordinator,
                        onFinish = viewModel::completeOnboarding,
                    )
                } else {
                    NetToolboxApp()
                }
            }
        }
    }
}

private fun ThemePreference?.toThemeMode(): NetToolboxThemeMode = when (this) {
    ThemePreference.LIGHT -> NetToolboxThemeMode.LIGHT
    ThemePreference.DARK -> NetToolboxThemeMode.DARK
    ThemePreference.FIELD -> NetToolboxThemeMode.FIELD
    ThemePreference.SYSTEM, null -> NetToolboxThemeMode.SYSTEM
}
