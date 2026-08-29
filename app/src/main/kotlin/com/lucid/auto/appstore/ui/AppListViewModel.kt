package com.lucid.auto.appstore.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lucid.auto.appstore.data.catalog.CatalogRepository
import com.lucid.auto.appstore.data.install.InstallOutcome
import com.lucid.auto.appstore.data.download.RemoteIconLoader
import com.lucid.auto.appstore.data.settings.SettingsRepository
import com.lucid.auto.appstore.di.AppGraph
import com.lucid.auto.appstore.domain.AppItem
import com.lucid.auto.appstore.domain.AppRepository
import com.lucid.auto.appstore.domain.AppState
import com.lucid.auto.appstore.domain.UpdateCoordinator
import com.lucid.auto.appstore.domain.UpdateSweep
import com.lucid.auto.appstore.update.AutoUpdateScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drives the list screen.
 *
 * Responsibilities, in the order they happen on launch:
 *  1. read the persisted auto-update flag and base URL,
 *  2. start observing merged rows (local versions + catalogue),
 *  3. refresh the catalogue,
 *  4. if — and only if — the flag is on, apply pending updates without any user tap.
 */
class AppListViewModel(
    private val appRepository: AppRepository,
    private val catalogRepository: CatalogRepository,
    private val updateCoordinator: UpdateCoordinator,
    private val settingsRepository: SettingsRepository,
    private val autoUpdateScheduler: AutoUpdateScheduler,
    private val remoteIconLoader: RemoteIconLoader,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AppListUiState(installMode = updateCoordinator.installMode),
    )
    val uiState: StateFlow<AppListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            appRepository.items.collect { items ->
                _uiState.value = _uiState.value.copy(items = items)
                loadMissingIcons(items)
            }
        }
        viewModelScope.launch {
            settingsRepository.autoUpdateEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(autoUpdateEnabled = enabled)
            }
        }
        viewModelScope.launch {
            settingsRepository.catalogBaseUrl.collect { url ->
                _uiState.value = _uiState.value.copy(catalogBaseUrl = url)
            }
        }
        refresh(applyAutoUpdate = true)
    }

    /**
     * Re-reads local versions and the catalogue.
     *
     * @param applyAutoUpdate when true and the flag is enabled, pending updates are applied
     *   straight away — the on-launch half of FR-6.3.
     */
    fun refresh(applyAutoUpdate: Boolean = false) {
        viewModelScope.launch { doRefresh(applyAutoUpdate) }
    }

    private suspend fun doRefresh(applyAutoUpdate: Boolean) {
        _uiState.value = _uiState.value.copy(isRefreshing = true, errorMessage = null)

        // A "Failed" marker describes the last attempt, not the app; a fresh look clears it.
        appRepository.clearFailures()
        appRepository.refreshLocal()
        val baseUrl = settingsRepository.catalogBaseUrl.first()
        val result = appRepository.refreshCatalog(baseUrl)

        _uiState.value = _uiState.value.copy(
            isRefreshing = false,
            errorMessage = result.exceptionOrNull()?.let { humanReadable(it, baseUrl) },
        )

        if (applyAutoUpdate && result.isSuccess && settingsRepository.autoUpdateEnabled.first()) {
            val sweep = updateCoordinator.applyPendingUpdates(catalogRepository.cached().byPackage())
            reportSweepFailures(sweep)
        }
    }

    /** Auto-update runs without a tap, so its failures still need somewhere to surface. */
    private fun reportSweepFailures(sweep: UpdateSweep) {
        if (sweep.failed.isEmpty()) return
        val summary = sweep.failed.joinToString("; ") { "${it.title}: ${it.message}" }
        _uiState.value = _uiState.value.copy(errorMessage = "Auto-update failed for $summary")
    }

    /**
     * Handles a tap on a row's action control.
     *
     * A row in [AppState.INSTALLED] is not actionable, so this is a no-op for it — the
     * "tapping installed does nothing" requirement is enforced here as well as in the UI, so it
     * holds even if a caller bypasses the disabled control.
     */
    fun onAppAction(packageName: String) {
        val item = _uiState.value.items.firstOrNull { it.packageName == packageName } ?: return
        if (!item.isActionable) return

        val remote = catalogRepository.cached().byPackage()[packageName] ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(errorMessage = null)
            val outcome = updateCoordinator.installOrUpdate(remote)
            // Failures are reported here, above the list — never inside a row.
            if (outcome is InstallOutcome.Failure) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "${item.title} could not be installed: ${outcome.message}",
                )
            }
        }
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoUpdateEnabled(enabled)
            // Enqueue or cancel the periodic worker immediately (FR-6.4).
            autoUpdateScheduler.applyFlag(enabled)
            // Refetch before acting: the cached catalogue may predate versions published since
            // launch, and acting on it would silently skip the updates the user just opted into.
            if (enabled) doRefresh(applyAutoUpdate = true)
        }
    }

    fun setCatalogBaseUrl(url: String) {
        viewModelScope.launch {
            settingsRepository.setCatalogBaseUrl(url)
            refresh(applyAutoUpdate = false)
        }
    }

    private suspend fun loadMissingIcons(items: List<AppItem>) {
        for (item in items) {
            if (item.icon != null || appRepository.hasRemoteIcon(item.packageName)) continue
            val url = catalogRepository.cached().byPackage()[item.packageName]?.iconUrl ?: continue
            remoteIconLoader.load(url)?.let { appRepository.cacheRemoteIcon(item.packageName, it) }
        }
    }

    /**
     * Names the URL that was actually tried.
     *
     * Without it, "could not connect" is indistinguishable between a server that is down, one
     * listening on a different port, and a base URL pointing at the wrong host — all of which
     * look identical to the user and each of which needs a different fix.
     */
    private fun humanReadable(t: Throwable, baseUrl: String): String =
        "Could not reach the app catalogue at $baseUrl — " +
            "${t.message ?: t::class.java.simpleName}. " +
            "Check that the catalogue server is running and listening on that address."

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppListViewModel(
            appRepository = graph.appRepository,
            catalogRepository = graph.catalogRepository,
            updateCoordinator = graph.updateCoordinator,
            settingsRepository = graph.settingsRepository,
            autoUpdateScheduler = graph.autoUpdateScheduler,
            remoteIconLoader = graph.remoteIconLoader,
        ) as T
    }
}
