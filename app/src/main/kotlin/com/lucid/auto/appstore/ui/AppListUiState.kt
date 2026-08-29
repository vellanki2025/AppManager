package com.lucid.auto.appstore.ui

import com.lucid.auto.appstore.data.install.InstallMode
import com.lucid.auto.appstore.domain.AppItem

data class AppListUiState(
    val items: List<AppItem> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val autoUpdateEnabled: Boolean = false,
    val installMode: InstallMode = InstallMode.INTERACTIVE,
    val catalogBaseUrl: String = "",
)
