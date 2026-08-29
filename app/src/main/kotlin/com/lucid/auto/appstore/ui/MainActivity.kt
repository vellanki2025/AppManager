package com.lucid.auto.appstore.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lucid.auto.appstore.di.ServiceLocator
import com.lucid.auto.appstore.ui.theme.LucidAppStoreTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppListViewModel by viewModels {
        AppListViewModel.Factory(ServiceLocator.from(applicationContext))
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            LucidAppStoreTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                AppListScreen(
                    state = state,
                    onAppAction = viewModel::onAppAction,
                    onRefresh = { viewModel.refresh(applyAutoUpdate = false) },
                    onAutoUpdateChanged = viewModel::setAutoUpdateEnabled,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the system install dialog: re-read local versions so the row settles.
        viewModel.refresh(applyAutoUpdate = false)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        // Asking again on every cold start would re-prompt a user who already answered.
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
