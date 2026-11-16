package com.openguard

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.openguard.core.location.LocationService
import com.openguard.core.location.TrackingPermissions
import com.openguard.core.location.UploadWorker
import com.openguard.feature.auth.AuthViewModel
import com.openguard.feature.auth.LoginScreen
import com.openguard.feature.auth.RegisterScreen
import com.openguard.feature.devices.ConsentScreen
import com.openguard.feature.devices.DeviceListScreen
import com.openguard.feature.devices.DeviceViewModel
import com.openguard.feature.settings.SettingsScreen
import com.openguard.feature.settings.SettingsViewModel
import com.openguard.feature.tracking.HistoryScreen
import com.openguard.feature.tracking.MapScreen
import com.openguard.feature.tracking.TrackingViewModel
import com.openguard.ui.OpenGuardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as OpenGuardApp
        setContent {
            OpenGuardTheme {
                val navController = rememberNavController()
                val startDestination = if (app.tokenStore.isLoggedIn) "devices" else "login"
                NavHost(navController = navController, startDestination = startDestination) {
                    composable("login") {
                        val vm: AuthViewModel =
                            viewModel(factory = vmFactory { AuthViewModel(app.authRepository) })
                        LoginScreen(
                            viewModel = vm,
                            onLoginSuccess = {
                                navController.navigate("devices") {
                                    popUpTo("login") { inclusive = true }
                                }
                            },
                            onRegisterClick = { navController.navigate("register") },
                        )
                    }
                    composable("register") {
                        val vm: AuthViewModel =
                            viewModel(factory = vmFactory { AuthViewModel(app.authRepository) })
                        RegisterScreen(
                            viewModel = vm,
                            onRegisterSuccess = {
                                navController.navigate("devices") {
                                    popUpTo("register") { inclusive = true }
                                }
                            },
                            onLoginClick = { navController.popBackStack() },
                        )
                    }
                    composable("devices") {
                        val vm: DeviceViewModel =
                            viewModel(factory = vmFactory { DeviceViewModel(app.deviceRepository) })
                        DeviceListScreen(
                            viewModel = vm,
                            onDeviceClick = { device ->
                                navController.navigate("map/${device.id}/${Uri.encode(device.name)}")
                            },
                            onAddDeviceClick = { navController.navigate("consent") },
                            onSettingsClick = { navController.navigate("settings") },
                            onStartTracking = {
                                // Tracking needs a registered device + all
                                // permissions; otherwise the consent screen
                                // collects both first.
                                if (app.tokenStore.deviceId == null ||
                                    !TrackingPermissions.allGranted(this@MainActivity)
                                ) {
                                    navController.navigate("consent")
                                } else {
                                    LocationService.start(this@MainActivity)
                                }
                            },
                            onStopTracking = { LocationService.stop(this@MainActivity) },
                        )
                    }
                    composable("consent") {
                        val vm: DeviceViewModel =
                            viewModel(factory = vmFactory { DeviceViewModel(app.deviceRepository) })
                        ConsentScreen(
                            viewModel = vm,
                            onRegistered = {
                                LocationService.start(this@MainActivity)
                                navController.navigate("devices") {
                                    popUpTo("consent") { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(
                        route = "map/{deviceId}/{deviceName}",
                        arguments = listOf(
                            navArgument("deviceId") { type = NavType.StringType },
                            navArgument("deviceName") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        val deviceId = entry.arguments?.getString("deviceId") ?: return@composable
                        val deviceName = entry.arguments?.getString("deviceName") ?: "Device"
                        val vm: TrackingViewModel =
                            viewModel(factory = vmFactory { TrackingViewModel(app.deviceRepository) })
                        MapScreen(
                            deviceId = deviceId,
                            deviceName = deviceName,
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
                            onHistoryClick = {
                                navController.navigate(
                                    "history/$deviceId/${Uri.encode(deviceName)}",
                                )
                            },
                        )
                    }
                    composable(
                        route = "history/{deviceId}/{deviceName}",
                        arguments = listOf(
                            navArgument("deviceId") { type = NavType.StringType },
                            navArgument("deviceName") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        val deviceId = entry.arguments?.getString("deviceId") ?: return@composable
                        val deviceName = entry.arguments?.getString("deviceName") ?: "Device"
                        val vm: TrackingViewModel =
                            viewModel(factory = vmFactory { TrackingViewModel(app.deviceRepository) })
                        HistoryScreen(
                            deviceId = deviceId,
                            deviceName = deviceName,
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("settings") {
                        val vm: SettingsViewModel =
                            viewModel(factory = vmFactory { SettingsViewModel(app.authRepository) })
                        SettingsScreen(
                            viewModel = vm,
                            userEmail = app.tokenStore.userEmail,
                            onBack = { navController.popBackStack() },
                            onLoggedOut = {
                                LocationService.stop(this@MainActivity)
                                UploadWorker.cancelPeriodic(this@MainActivity)
                                navController.navigate("login") {
                                    popUpTo(navController.graph.id) { inclusive = true }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** One tiny factory for all ViewModels (manual DI, no framework). */
private inline fun <reified VM : ViewModel> vmFactory(
    crossinline create: () -> VM,
): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
