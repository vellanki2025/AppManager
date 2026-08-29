package com.lucid.auto.appstore.data.catalog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the last-known-good catalogue.
 *
 * A failed refresh must not blank the list (FR-2.4): the cached catalogue is retained and the
 * failure is surfaced separately so the UI can show a banner over otherwise-valid rows.
 */
class CatalogRepository(
    private val api: CatalogApi,
) {
    private val _catalog = MutableStateFlow(AppCatalog())
    val catalog: StateFlow<AppCatalog> = _catalog.asStateFlow()

    /** @return success with the fresh catalogue, or failure while leaving the cache intact. */
    suspend fun refresh(baseUrl: String): Result<AppCatalog> = runCatching {
        api.fetchCatalog(baseUrl)
    }.onSuccess { _catalog.value = it }

    fun cached(): AppCatalog = _catalog.value
}
