package com.reprivacyspace.app.ui.main

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.reprivacyspace.app.constant.RouteConstant
import com.reprivacyspace.app.ui.screen.about.AboutScreen
import com.reprivacyspace.app.ui.screen.blind.AddBlindAppsScreen
import com.reprivacyspace.app.ui.screen.launcher.LauncherScreen
import com.reprivacyspace.app.ui.screen.managehiddenapps.AddHiddenAppsScreen
import com.reprivacyspace.app.ui.screen.settings.OtherSettingsScreen
import com.reprivacyspace.app.ui.screen.setconnectedapps.SetConnectedAppsScreen
import com.reprivacyspace.app.ui.screen.setwhitelist.SetWhitelistScreen
import com.reprivacyspace.app.ui.theme.PrivacySpaceTheme
import com.reprivacyspace.app.util.AppHelper
import com.reprivacyspace.app.util.ConfigHelper
import com.reprivacyspace.app.util.LocalNavHostController
import com.reprivacyspace.app.util.NavHostControllerWrapper
import com.google.accompanist.insets.ProvideWindowInsets
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Edge-to-edge: keep the system bar icons readable against the app surface instead
        // of white-on-white when the light theme is active.
        val darkTheme = (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
        setContent {
            PrivacySpaceTheme {
                ProvideWindowInsets {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colors.background
                    ) {
                        Content()
                    }
                }
            }
        }

        lifecycleScope.launch {
            AppHelper.initialize(applicationContext)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing
            && ConfigHelper.loadingStatusFlow.value == ConfigHelper.LOADING_STATUS_INIT
        ) {
            ConfigHelper.initConfig(applicationContext)
        }
    }
}

@Composable
private fun Content() {
    val navHostController = rememberNavController()

    val navHostControllerWrapper = remember {
        NavHostControllerWrapper(navHostController)
    }
    CompositionLocalProvider(LocalNavHostController provides navHostControllerWrapper) {
        NavHost(
            navController = navHostController,
            startDestination = RouteConstant.LAUNCHER
        ) {
            composable(RouteConstant.LAUNCHER) {
                LauncherScreen()
            }
            composable(RouteConstant.ADD_HIDDEN_APPS) {
                AddHiddenAppsScreen()
            }
            composable(RouteConstant.WHITELIST) {
                SetWhitelistScreen()
            }
            composable("${RouteConstant.SET_CONNECTED_APPS}?targetPackageName={targetPackageName}") {
                argument("targetPackageName") {
                    type = NavType.StringType
                }
                SetConnectedAppsScreen()
            }
            composable(RouteConstant.BLACKLIST) {
                AddBlindAppsScreen()
            }

            composable(RouteConstant.OTHER_SETTINGS) {
                OtherSettingsScreen()
            }

            composable(RouteConstant.ABOUT) {
                AboutScreen()
            }
        }
    }
}