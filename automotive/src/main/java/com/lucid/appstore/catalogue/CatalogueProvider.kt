package com.lucid.appstore.catalogue

import com.lucid.appstore.ui.AppInfo

/** Source of the apps offered for download. Replace [StubCatalogueProvider] with the backend-backed one. */
interface CatalogueProvider {
    suspend fun availableApps(): List<AppInfo>
}

class StubCatalogueProvider : CatalogueProvider {
    override suspend fun availableApps(): List<AppInfo> = listOf(
        AppInfo("com.lucid.navigation", "Lucid Navigation", "2.4.0", 600L * 1024 * 1024),
    )
}
