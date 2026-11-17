package com.openguard

import android.content.Intent
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
import com.openguard.core.security.EraseStore
import com.openguard.core.security.LostModeStore
import com.openguard.core.security.OfflineAutoLock
import com.openguard.feature.auth.AuthViewModel
import com.openguard.feature.auth.LoginScreen
import com.openguard.feature.auth.RecoverAccountScreen
import com.openguard.feature.auth.RegisterScreen
import com.openguard.feature.devices.ConsentScreen
import com.openguard.feature.devices.DeviceListScreen
import com.openguard.feature.devices.DeviceViewModel
import com.openguard.feature.geofence.GeofenceRepository
import com.openguard.feature.geofence.GeofenceScreen
import com.openguard.feature.geofence.GeofenceViewModel
import com.openguard.feature.health.HealthScreen
import com.openguard.feature.health.HealthViewModel
import com.openguard.feature.passkeys.PasskeyScreen
import com.openguard.feature.passkeys.PasskeyViewModel
import com.openguard.feature.protection.OfflineProtectionScreen
import com.openguard.feature.protection.OfflineProtectionViewModel
import com.openguard.feature.security.CommandCenterScreen
import com.openguard.feature.security.CommandCenterViewModel
import com.openguard.feature.security.EraseCountdownActivity
import com.openguard.feature.security.LostModeActivity
import com.openguard.feature.security.TimelineScreen
import com.openguard.feature.security.TimelineViewModel
import com.openguard.feature.settings.RecoveryScreen
import com.openguard.feature.settings.RecoveryViewModel
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
        // Warning-tap deep link: the offline auto-lock warning
        // notification opens the app on the protection screen with the
        // cancel dialog. The tap itself requires unlocking the phone
        // first when it is locked, so a thief cannot cancel from the
        // lock screen.
        val showCancelFromNotification =
            intent.getBooleanExtra(OfflineAutoLock.EXTRA_SHOW_CANCEL, false)
        // Pending erase takes precedence over Lost Mode: the countdown
        // screen is the one the owner must see first.
        if (EraseStore.pending(this) != null) {
            startActivity(Intent(this, EraseCountdownActivity::class.java))
        } else if (LostModeStore.isOn(this)) {
            // Lost Mode on: surface the Lost Mode screen over the normal UI.
            startActivity(Intent(this, LostModeActivity::class.java))
        }
        setContent {
            OpenGuardTheme {
                val navController = rememberNavController()
                val startDestination = when {
                    !app.tokenStore.isLoggedIn -> "login"
                    showCancelFromNotification -> "protection?showCancel=true"
                    else -> "devices"
                }
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
                            onRecoverClick = { navController.navigate("recover") },
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
                    composable("recover") {
                        val vm: AuthViewModel =
                            viewModel(factory = vmFactory { AuthViewModel(app.authRepository) })
                        RecoverAccountScreen(
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
                            onRecovered = { navController.popBackStack() },
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
                            onCommandsClick = { device ->
                                navController.navigate(
                                    "commands/${device.id}/${Uri.encode(device.name)}",
                                )
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
                    composable(
                        route = "commands/{deviceId}/{deviceName}",
                        arguments = listOf(
                            navArgument("deviceId") { type = NavType.StringType },
                            navArgument("deviceName") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        val deviceId = entry.arguments?.getString("deviceId") ?: return@composable
                        val deviceName = entry.arguments?.getString("deviceName") ?: "Device"
                        val vm: CommandCenterViewModel =
                            viewModel(factory = vmFactory {
                                CommandCenterViewModel(
                                    app.commandRepository,
                                    app.deviceRepository,
                                    app.authRepository,
                                )
                            })
                        CommandCenterScreen(
                            deviceId = deviceId,
                            deviceName = deviceName,
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
                            onLocateClick = {
                                navController.navigate(
                                    "map/$deviceId/${Uri.encode(deviceName)}",
                                )
                            },
                            onAlertsClick = {
                                navController.navigate(
                                    "timeline/$deviceId/${Uri.encode(deviceName)}",
                                )
                            },
                            onHealthClick = {
                                navController.navigate(
                                    "health/$deviceId/${Uri.encode(deviceName)}",
                                )
                            },
                            onHistoryClick = {
                                navController.navigate(
                                    "history/$deviceId/${Uri.encode(deviceName)}",
                                )
                            },
                            onGeofencesClick = {
                                navController.navigate(
                                    "geofences/$deviceId/${Uri.encode(deviceName)}",
                                )
                            },
                            onProtectionClick = { navController.navigate("protection") },
                            onPasskeysClick = { navController.navigate("passkeys") },
                        )
                    }
                    composable("passkeys") {
                        val vm: PasskeyViewModel =
                            viewModel(factory = vmFactory {
                                PasskeyViewModel(app.api, app.authRepository)
                            })
                        PasskeyScreen(
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(
                        route = "geofences/{deviceId}/{deviceName}",
                        arguments = listOf(
                            navArgument("deviceId") { type = NavType.StringType },
                            navArgument("deviceName") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        val deviceId = entry.arguments?.getString("deviceId") ?: return@composable
                        val deviceName = entry.arguments?.getString("deviceName") ?: "Device"
                        val vm: GeofenceViewModel =
                            viewModel(factory = vmFactory {
                                GeofenceViewModel(
                                    GeofenceRepository(app.api),
                                    app.deviceRepository,
                                    this@MainActivity.applicationContext,
                                )
                            })
                        GeofenceScreen(
                            deviceId = deviceId,
                            deviceName = deviceName,
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(
                        route = "health/{deviceId}/{deviceName}",
                        arguments = listOf(
                            navArgument("deviceId") { type = NavType.StringType },
                            navArgument("deviceName") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        val deviceId = entry.arguments?.getString("deviceId") ?: return@composable
                        val deviceName = entry.arguments?.getString("deviceName") ?: "Device"
                        val vm: HealthViewModel =
                            viewModel(factory = vmFactory { HealthViewModel(app.deviceRepository) })
                        HealthScreen(
                            deviceId = deviceId,
                            deviceName = deviceName,
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(
                        route = "timeline/{deviceId}/{deviceName}",
                        arguments = listOf(
                            navArgument("deviceId") { type = NavType.StringType },
                            navArgument("deviceName") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        val deviceId = entry.arguments?.getString("deviceId") ?: return@composable
                        val deviceName = entry.arguments?.getString("deviceName") ?: "Device"
                        val vm: TimelineViewModel =
                            viewModel(factory = vmFactory { TimelineViewModel(app.commandRepository) })
                        TimelineScreen(
                            deviceId = deviceId,
                            deviceName = deviceName,
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(
                        route = "protection?showCancel={showCancel}",
                        arguments = listOf(
                            navArgument("showCancel") {
                                type = NavType.BoolType
                                defaultValue = false
                            },
                        ),
                    ) { entry ->
                        val vm: OfflineProtectionViewModel =
                            viewModel(factory = vmFactory {
                                OfflineProtectionViewModel(this@MainActivity.applicationContext)
                            })
                        OfflineProtectionScreen(
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
                            showCancel = entry.arguments?.getBoolean("showCancel") ?: false,
                        )
                    }
                    composable("settings") {
                        val vm: SettingsViewModel =
                            viewModel(factory = vmFactory {
                                SettingsViewModel(app.authRepository, app.deviceRepository)
                            })
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
                            onRecoveryClick = { navController.navigate("recovery") },
                            onDeviceSignedOut = {
                                LocationService.stop(this@MainActivity)
                                UploadWorker.cancelPeriodic(this@MainActivity)
                                navController.navigate("devices") {
                                    popUpTo("settings") { inclusive = true }
                                }
                            },
                        )
                    }
                    composable("recovery") {
                        val vm: RecoveryViewModel =
                            viewModel(factory = vmFactory { RecoveryViewModel(app.authRepository) })
                        RecoveryScreen(
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
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
