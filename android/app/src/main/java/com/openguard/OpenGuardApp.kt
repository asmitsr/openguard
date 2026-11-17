package com.openguard

import android.app.Application
import com.openguard.core.database.AppDatabase
import com.openguard.core.evidence.EvidenceApi
import com.openguard.core.location.BatteryBurstWorker
import com.openguard.core.location.UploadWorker
import com.openguard.core.security.OfflineAutoLock
import com.openguard.core.network.ApiClient
import com.openguard.core.network.ApiService
import com.openguard.core.network.TokenStore
import com.openguard.feature.auth.AuthRepository
import com.openguard.feature.devices.DeviceRepository
import com.openguard.feature.security.CommandRepository
import org.maplibre.android.MapLibre

/**
 * Manual dependency container (no DI framework in the MVP — the graph is
 * small enough that constructor wiring in one place is the least code).
 *
 * Modules note: the blueprint's core and feature layers are packages
 * inside this single app module. Splitting them into Gradle modules is
 * deferred until the app grows enough to need it.
 */
class OpenGuardApp : Application() {

    lateinit var tokenStore: TokenStore
        private set

    lateinit var database: AppDatabase
        private set

    /** Lazy: ApiClient fails fast if API_BASE_URL is still the placeholder. */
    private val apiClient by lazy { ApiClient(tokenStore) }

    val api: ApiService by lazy { apiClient.api }

    val evidenceApi: EvidenceApi by lazy { apiClient.evidenceApi }

    val authRepository: AuthRepository by lazy { AuthRepository(api, tokenStore) }

    val deviceRepository: DeviceRepository by lazy {
        DeviceRepository(api, tokenStore, applicationContext)
    }

    val commandRepository: CommandRepository by lazy { CommandRepository(api) }

    override fun onCreate() {
        super.onCreate()
        // Must run before any MapView is created.
        MapLibre.getInstance(this)
        tokenStore = TokenStore(this)
        database = AppDatabase.create(this)
        // The worker no-ops until a device is registered, so it is safe
        // to schedule unconditionally.
        UploadWorker.schedulePeriodic(this)
        // Re-schedule the protection workers: a force-stop cancels all
        // WorkManager work until the app runs again. KEEP makes these
        // no-ops when already scheduled, and both workers no-op (or
        // self-cancel) while their feature is disabled.
        OfflineAutoLock.ensureScheduled(this)
        BatteryBurstWorker.enqueueWatch(this)
    }
}
