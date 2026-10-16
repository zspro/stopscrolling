package io.github.stopscrolling.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.stopscrolling.app.data.SettingsStore
import io.github.stopscrolling.app.ui.home.HomeScreen
import io.github.stopscrolling.app.ui.pick.PickAppsScreen
import io.github.stopscrolling.app.ui.settings.SettingsScreen
import io.github.stopscrolling.app.ui.theme.StopScrollTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 首次启动：先进引导页（欢迎 + 权限自动授权链）
        val onboardingDone = runBlocking {
            SettingsStore(applicationContext).onboardingDone.first()
        }
        if (!onboardingDone) {
            startActivity(android.content.Intent(this, OnboardingActivity::class.java))
        }

        setContent {
            StopScrollTheme {
                StopScrollAppUi()
            }
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val PICK = "pick"
    const val SETTINGS = "settings"
}

@Composable
private fun StopScrollAppUi(vm: MainViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val showBottomBar = currentRoute == Routes.HOME || currentRoute == Routes.SETTINGS

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.HOME,
                        onClick = {
                            navController.navigate(Routes.HOME) { launchSingleTop = true }
                        },
                        icon = {
                            Icon(
                                if (currentRoute == Routes.HOME) Icons.Filled.Home else Icons.Outlined.Home,
                                contentDescription = "首页"
                            )
                        },
                        label = { Text("首页") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.SETTINGS,
                        onClick = {
                            navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                        },
                        icon = {
                            Icon(
                                if (currentRoute == Routes.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                                contentDescription = "设置"
                            )
                        },
                        label = { Text("设置") }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    vm = vm,
                    onAddApps = { navController.navigate(Routes.PICK) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) }
                )
            }
            composable(Routes.PICK) {
                PickAppsScreen(
                    vm = vm,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(vm = vm)
            }
        }
    }
}
